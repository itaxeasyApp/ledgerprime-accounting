package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.AppDatabase
import com.example.accounting.core.security.ISecureStorage
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.OutboxSyncEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.CompanyRepository
import com.example.accounting.data.repository.FinancialYearRepository
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.company.CompanyStatus
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.FinancialYearStatus
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Phase0TestSuite {

    // ==========================================
    // 1. COMPANY ISOLATION TESTS
    // ==========================================
    @Test
    fun testCompanyIsolation_CrossTenantAccessBlocked() = runBlocking {
        val fakeDao = FakeAccountingDao()
        val repository = CompanyRepository(fakeDao)

        val companyA = Company(
            companyId = "COMP_ALPHA",
            name = "Alpha Corp",
            legalName = "Alpha Corporation Pvt Ltd",
            gstin = "27AAACA1234F1ZQ",
            pan = "AAACA1234F"
        )
        repository.createCompany(companyA)

        // Attempt to update companyA using companyB's ID in tenant context
        val updateAttempt = companyA.copy(name = "Alpha Tampered")
        val result = repository.updateCompany("COMP_BETA", updateAttempt)

        assertTrue("Cross-tenant mutation must fail", result is AccountingResult.Failure)
        val error = (result as AccountingResult.Failure).error
        assertTrue("Error must be TenantMismatch", error is AppError.TenantMismatch)
    }

    // ==========================================
    // 2. FINANCIAL YEAR ISOLATION TESTS
    // ==========================================
    @Test
    fun testFinancialYearIsolation_ScopedByCompanyId() = runBlocking {
        val fakeDao = FakeAccountingDao()
        val repository = FinancialYearRepository(fakeDao)

        val fy1 = FinancialYear.createIndianFY("FY_2026_ALPHA", "COMP_ALPHA", 2026, isCurrent = true)
        val fy2 = FinancialYear.createIndianFY("FY_2026_BETA", "COMP_BETA", 2026, isCurrent = true)

        repository.createFinancialYear("COMP_ALPHA", fy1)
        repository.createFinancialYear("COMP_BETA", fy2)

        // Tenant Alpha querying its FY
        val alphaResult = repository.getFinancialYear("COMP_ALPHA", "FY_2026_ALPHA")
        assertTrue(alphaResult is AccountingResult.Success)

        // Tenant Beta attempting to query Alpha's FY
        val foreignResult = repository.getFinancialYear("COMP_BETA", "FY_2026_ALPHA")
        assertTrue("Beta cannot query Alpha's FY", foreignResult is AccountingResult.Failure)
    }

    // ==========================================
    // 3. CORRECT INDIAN FY BOUNDARIES
    // ==========================================
    @Test
    fun testIndianFinancialYearBoundaries() {
        val validFY = FinancialYear.createIndianFY("FY_2026_27", "COMP_001", 2026)
        
        assertEquals(LocalDate.of(2026, 4, 1), validFY.startDate)
        assertEquals(LocalDate.of(2027, 3, 31), validFY.endDate)
        assertTrue("Must be standard Indian FY (1 Apr - 31 Mar)", validFY.isStandardIndianFinancialYear())
        
        // Check date containment
        assertTrue(validFY.contains(LocalDate.of(2026, 4, 1)))
        assertTrue(validFY.contains(LocalDate.of(2026, 12, 31)))
        assertTrue(validFY.contains(LocalDate.of(2027, 3, 31)))
        assertFalse(validFY.contains(LocalDate.of(2026, 3, 31))) // Prior FY
        assertFalse(validFY.contains(LocalDate.of(2027, 4, 1))) // Next FY

        // Validate invalid boundary (startDate >= endDate) throws
        try {
            FinancialYear(
                financialYearId = "FY_INVALID",
                companyId = "COMP_001",
                fyCode = "INVALID",
                startDate = LocalDate.of(2027, 4, 1),
                endDate = LocalDate.of(2026, 3, 31)
            )
            fail("Invalid financial year with startDate > endDate must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be before endDate") == true)
        }
    }

    // ==========================================
    // 4. ACCOUNTING PERIOD OPEN / LOCKED STATE
    // ==========================================
    @Test
    fun testAccountingPeriod_OpenLockedState() {
        val openPeriod = AccountingPeriod(
            periodId = "PER_01",
            companyId = "COMP_001",
            financialYearId = "FY_001",
            name = "April 2026",
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 30),
            status = PeriodStatus.OPEN
        )
        assertTrue(openPeriod.isOpen)
        assertFalse(openPeriod.isLocked)

        val lockedPeriod = openPeriod.copy(status = PeriodStatus.LOCKED)
        assertFalse(lockedPeriod.isOpen)
        assertTrue(lockedPeriod.isLocked)
    }

    // ==========================================
    // 5. LOCKED PERIOD REJECTION
    // ==========================================
    @Test
    fun testLockedPeriod_OperationRejection() {
        val lockedPeriod = AccountingPeriod(
            periodId = "PER_02",
            companyId = "COMP_001",
            financialYearId = "FY_001",
            name = "May 2026",
            startDate = LocalDate.of(2026, 5, 1),
            endDate = LocalDate.of(2026, 5, 31),
            status = PeriodStatus.LOCKED
        )

        try {
            lockedPeriod.validateOperationPermitted()
            fail("Operation on locked period must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("locked") == true)
        }
    }

    // ==========================================
    // 6. MONEY PAISE PRECISION TESTS
    // ==========================================
    @Test
    fun testMoneyPaisePrecision_ExactArithmetic() {
        // ₹100.50 = 10050 paise
        val m1 = Money(10050L)
        val m2 = Money(4950L) // ₹49.50
        val sum = m1 + m2
        assertEquals(15000L, sum.paise) // ₹150.00
        assertEquals(BigDecimal("150.00"), sum.toBigDecimal)

        // Precision multiplication: ₹100.50 * 18% GST
        val gst = m1.percentage(18.0)
        assertEquals(1809L, gst.paise) // ₹18.09

        // Exact formatting
        assertEquals("₹100.50", m1.format())
        assertEquals("₹150.00", sum.format())
    }

    // ==========================================
    // 7. ROOM DATABASE CREATION & CONFIG
    // ==========================================
    @Test
    fun testRoomDatabaseCreation_Invariants() {
        // AppDatabase class must exist. Schema is now version 15 (Phase 4: inventory tables +
        // company accountingMode/businessType columns; Phase 5: GST transactions/settlement
        // allocations/GST filing periods + voucher referenceVoucherId/paymentMode columns; Phase
        // 7A: parties/invoices/invoice_lines tables; Phase 7B: trade_documents/trade_document_lines
        // tables + invoices.sourceTradeDocumentId column; Phase 7D: document_templates/
        // business_profiles/individual_profiles/document_assets/rendered_document_records tables;
        // Business Profile hardening: business_profiles.constitutionType/tan/udyam columns; Phase
        // 7F: recurring_voucher_schedules/recurring_voucher_lines/recurring_voucher_generation_log
        // tables; Phase 7J-B: voucher_drafts/voucher_draft_lines/voucher_document_references/
        // company_subscriptions/bank_upi_profiles tables; GST Settings: company gstEnabled column;
        // Architecture Checkpoint: gst_transactions.voucherId relaxed to nullable; Rule 30: Party
        // Data Validation: ledgers.gstRegistrationStatus column; Rule 31: Purchase/RCM Foundation:
        // gst_transactions.chargeType column; Rule 33: GST Return Dashboard & Filing Foundation:
        // companies.gstScheme column + gst_returns/gst_return_artifacts/gst_return_sections/
        // gst_return_submissions tables; Rule 33 redesign: companies.gstFilingFrequency column;
        // PIN-code address lookup: business_profiles/individual_profiles pinCode/city/state/
        // country columns; Phase 7J-B.2: voucher_document_references.(voucherId, documentAssetId)
        // unique index; D1a: companies.gstOperatingMode column; D1b: gst_transactions.supplyNature/
        // transactionGroupId/transactionDate/partyGstRegistrationStatus columns), backed by exactly
        // eighteen explicit, non-destructive migrations - see testMigrationInfrastructure_ExplicitRegistry.
        assertNotNull(AppDatabase::class.java)
        assertEquals(19, AppDatabase.ALL_MIGRATIONS.size)
    }

    // ==========================================
    // 8. MIGRATION INFRASTRUCTURE
    // ==========================================
    @Test
    fun testMigrationInfrastructure_ExplicitRegistry() {
        val migrations = AppDatabase.ALL_MIGRATIONS
        assertNotNull("Explicit migrations array must be defined", migrations)
        assertEquals("Version 1->2 (Phase 4), 2->3 (Phase 5), 3->4 (Phase 7A), 4->5 (Phase 7B), 5->6 (Phase 7D), 6->7 (Business Profile hardening), 7->8 (Phase 7F: Recurring Voucher Engine), 8->9 (Phase 7J-B: Management Layer), 9->10 (GST Settings: company gstEnabled column), 10->11 (Architecture Checkpoint: gst_transactions.voucherId relaxed to nullable), 11->12 (Rule 30: Party Data Validation - ledgers.gstRegistrationStatus column), 12->13 (Rule 31: Purchase/RCM Foundation - gst_transactions.chargeType column), 13->14 (Rule 33: GST Return Dashboard & Filing Foundation - companies.gstScheme column + gst_returns/gst_return_artifacts/gst_return_sections/gst_return_submissions tables), 14->15 (Rule 33 redesign: companies.gstFilingFrequency column), 15->16 (PIN-code address lookup: business_profiles/individual_profiles pinCode/city/state/country columns), 16->17 (Phase 7J-B.2: voucher_document_references.(voucherId, documentAssetId) unique index), 17->18 (D1a: companies.gstOperatingMode column), 18->19 (D1b: gst_transactions.supplyNature/transactionGroupId/transactionDate/partyGstRegistrationStatus columns), and 19->20 (Company/Profile/Ledger Setup audit: ledgers.bankName/bankBranch columns) are the only migrations registered so far", 19, migrations.size)
        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)
        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)
        assertEquals(3, migrations[2].startVersion)
        assertEquals(4, migrations[2].endVersion)
        assertEquals(4, migrations[3].startVersion)
        assertEquals(5, migrations[3].endVersion)
        assertEquals(5, migrations[4].startVersion)
        assertEquals(6, migrations[4].endVersion)
        assertEquals(6, migrations[5].startVersion)
        assertEquals(7, migrations[5].endVersion)
        assertEquals(7, migrations[6].startVersion)
        assertEquals(8, migrations[6].endVersion)
        assertEquals(8, migrations[7].startVersion)
        assertEquals(9, migrations[7].endVersion)
        assertEquals(9, migrations[8].startVersion)
        assertEquals(10, migrations[8].endVersion)
        assertEquals(10, migrations[9].startVersion)
        assertEquals(11, migrations[9].endVersion)
        assertEquals(11, migrations[10].startVersion)
        assertEquals(12, migrations[10].endVersion)
        assertEquals(12, migrations[11].startVersion)
        assertEquals(13, migrations[11].endVersion)
        assertEquals(13, migrations[12].startVersion)
        assertEquals(14, migrations[12].endVersion)
        assertEquals(14, migrations[13].startVersion)
        assertEquals(15, migrations[13].endVersion)
        assertEquals(15, migrations[14].startVersion)
        assertEquals(16, migrations[14].endVersion)
        assertEquals(16, migrations[15].startVersion)
        assertEquals(17, migrations[15].endVersion)
        assertEquals(17, migrations[16].startVersion)
        assertEquals(18, migrations[16].endVersion)
        assertEquals(18, migrations[17].startVersion)
        assertEquals(19, migrations[17].endVersion)
        assertEquals(19, migrations[18].startVersion)
        assertEquals(20, migrations[18].endVersion)
    }

    // ==========================================
    // 9. SECURE STORAGE BEHAVIOR
    // ==========================================
    @Test
    fun testSecureStorage_InMemoryContract() {
        val storage: ISecureStorage = FakeSecureStorage()

        // Device ID must be populated
        assertFalse(storage.getDeviceId().isBlank())

        // Auth tokens
        storage.setAuthToken("token_xyz_123")
        assertEquals("token_xyz_123", storage.getAuthToken())

        // Company settings isolation
        storage.setCompanySetting("COMP_A", "printer_ip", "192.168.1.50")
        storage.setCompanySetting("COMP_B", "printer_ip", "192.168.2.100")

        assertEquals("192.168.1.50", storage.getCompanySetting("COMP_A", "printer_ip", ""))
        assertEquals("192.168.2.100", storage.getCompanySetting("COMP_B", "printer_ip", ""))

        // Session clearing
        storage.clearSession()
        assertEquals(null, storage.getAuthToken())
    }

    // ==========================================
    // 10. OFFLINE REPOSITORY OPERATION
    // ==========================================
    @Test
    fun testOfflineRepositoryOperation_LocalPersistence() = runBlocking {
        val fakeDao = FakeAccountingDao()
        val repo = CompanyRepository(fakeDao)

        val newCompany = Company(
            companyId = "COMP_OFFLINE_01",
            name = "Offline Trading Co",
            legalName = "Offline Trading Co Pvt Ltd",
            gstin = "27AABCT1234D1Z2",
            pan = "AABCT1234D",
            status = CompanyStatus.ACTIVE
        )

        val createResult = repo.createCompany(newCompany)
        assertTrue("Must create and persist offline", createResult is AccountingResult.Success)

        val fetchResult = repo.getCompany("COMP_OFFLINE_01")
        assertTrue("Must read back persisted entity", fetchResult is AccountingResult.Success)
        assertEquals("Offline Trading Co", (fetchResult as AccountingResult.Success).data.name)
    }

    // ==========================================
    // 11. OUTBOX IDEMPOTENCY
    // ==========================================
    @Test
    fun testOutboxIdempotency_UniqueKeyGeneration() {
        fun freshOutboxItem() = OutboxSyncEntity(
            syncId = java.util.UUID.randomUUID().toString(),
            companyId = "COMP_001",
            entityType = "COMPANY",
            entityId = "COMP_001",
            operation = "INSERT",
            payloadJson = "{}",
            syncState = SyncState.PENDING,
            retryCount = 0,
            lastError = null,
            createdAt = 0L,
            updatedAt = 0L
        )
        val item1 = freshOutboxItem()
        val item2 = freshOutboxItem()

        assertFalse("Each outbox mutation must generate a unique idempotency key", item1.idempotencyKey == item2.idempotencyKey)
        assertEquals(SyncState.PENDING, item1.syncState)
        assertEquals(0, item1.retryCount)
    }

    // ==========================================
    // 12. DUPLICATE SYNCHRONIZATION PREVENTION
    // ==========================================
    @Test
    fun testDuplicateSynchronizationPrevention() = runBlocking {
        val fakeDao = FakeAccountingDao()
        val idempotencyKey = UUID.randomUUID().toString()

        val entity1 = OutboxSyncEntity(
            syncId = "SYNC_001",
            companyId = "COMP_001",
            entityType = "COMPANY",
            entityId = "COMP_001",
            operation = "INSERT",
            payloadJson = "{}",
            idempotencyKey = idempotencyKey,
            syncState = com.example.accounting.domain.accounting.SyncState.PENDING,
            retryCount = 0,
            lastError = null,
            version = 1L,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        fakeDao.insertOutboxSync(entity1)

        // Verify duplicate idempotency key detection
        val isDuplicate = fakeDao.hasIdempotencyKey(idempotencyKey)
        assertTrue("Idempotency key must be detected as existing to prevent duplicate sync", isDuplicate)
    }
}

/**
 * In-memory fake DAO for isolated, fast JVM testing of repositories.
 */
class FakeAccountingDao : AccountingDao {
    private val companies = ConcurrentHashMap<String, CompanyEntity>()
    private val financialYears = ConcurrentHashMap<String, FinancialYearEntity>()
    private val periods = ConcurrentHashMap<String, AccountingPeriodEntity>()
    private val outbox = ConcurrentHashMap<String, OutboxSyncEntity>()
    private val ledgers = ConcurrentHashMap<String, LedgerEntity>()
    private val vouchers = ConcurrentHashMap<String, VoucherEntity>()
    private val journalItems = ConcurrentHashMap<String, JournalItemEntity>()

    fun hasIdempotencyKey(key: String): Boolean {
        return outbox.values.any { it.idempotencyKey == key }
    }

    override fun getAllCompanies(): Flow<List<CompanyEntity>> = flowOf(companies.values.toList())

    override suspend fun getCompanyById(companyId: String): CompanyEntity? = companies[companyId]

    override suspend fun getDefaultCompany(): CompanyEntity? = companies.values.firstOrNull { it.isDefault }

    override suspend fun insertCompany(company: CompanyEntity) {
        companies[company.companyId] = company
    }

    override suspend fun updateCompany(company: CompanyEntity) {
        companies[company.companyId] = company
    }

    override fun getBranchesByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.BranchEntity>())
    override suspend fun getBranchById(companyId: String, branchId: String) = null
    override suspend fun insertBranch(branch: com.example.accounting.data.local.entity.BranchEntity) {}
    override suspend fun insertBranches(branches: List<com.example.accounting.data.local.entity.BranchEntity>) {}

    override fun getFinancialYearsByCompany(companyId: String): Flow<List<FinancialYearEntity>> =
        flowOf(financialYears.values.filter { it.companyId == companyId })

    override suspend fun getCurrentFinancialYear(companyId: String): FinancialYearEntity? =
        financialYears.values.firstOrNull { it.companyId == companyId && it.isCurrent }

    override suspend fun getFinancialYearById(companyId: String, fyId: String): FinancialYearEntity? =
        financialYears.values.firstOrNull { it.companyId == companyId && it.financialYearId == fyId }

    override suspend fun getFinancialYearById(fyId: String): FinancialYearEntity? =
        financialYears[fyId]

    override suspend fun insertFinancialYear(fy: FinancialYearEntity) {
        financialYears[fy.financialYearId] = fy
    }

    override suspend fun updateFinancialYear(fy: FinancialYearEntity) {
        financialYears[fy.financialYearId] = fy
    }

    override suspend fun lockFinancialYear(companyId: String, fyId: String, lockedBy: String, lockedAt: Long) {
        val existing = financialYears[fyId]
        if (existing != null && existing.companyId == companyId) {
            financialYears[fyId] = existing.copy(isLocked = true, lockedBy = lockedBy, lockedAt = lockedAt)
        }
    }

    override fun getPeriodsByFinancialYear(companyId: String, fyId: String): Flow<List<AccountingPeriodEntity>> =
        flowOf(periods.values.filter { it.companyId == companyId && it.financialYearId == fyId })

    override fun getPeriodsByFinancialYear(fyId: String): Flow<List<AccountingPeriodEntity>> =
        flowOf(periods.values.filter { it.financialYearId == fyId })

    override suspend fun getPeriodById(companyId: String, periodId: String): AccountingPeriodEntity? =
        periods.values.firstOrNull { it.companyId == companyId && it.periodId == periodId }

    override suspend fun getPeriodById(periodId: String): AccountingPeriodEntity? =
        periods[periodId]

    override suspend fun insertPeriods(periodsList: List<AccountingPeriodEntity>) {
        periodsList.forEach { periods[it.periodId] = it }
    }

    override suspend fun setPeriodStatus(companyId: String, periodId: String, status: PeriodStatus, lockedBy: String?, lockedAt: Long?) {
        val existing = periods[periodId]
        if (existing != null && existing.companyId == companyId) {
            periods[periodId] = existing.copy(status = status, lockedBy = lockedBy, lockedAt = lockedAt)
        }
    }

    override suspend fun updatePeriodStatus(periodId: String, status: PeriodStatus, timestamp: Long?, user: String?) {
        val existing = periods[periodId]
        if (existing != null) {
            periods[periodId] = existing.copy(status = status, lockedBy = user, lockedAt = timestamp)
        }
    }

    override fun getGroupsByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.GroupEntity>())
    override suspend fun getGroupById(companyId: String, groupId: String) = null
    override suspend fun insertGroup(group: com.example.accounting.data.local.entity.GroupEntity) {}
    override suspend fun insertGroups(groups: List<com.example.accounting.data.local.entity.GroupEntity>) {}
    override suspend fun updateGroup(group: com.example.accounting.data.local.entity.GroupEntity) {}
    override suspend fun deleteGroup(companyId: String, groupId: String): Int = 1

    override fun getLedgersByCompany(companyId: String) = flowOf(ledgers.values.filter { it.companyId == companyId })
    override fun getActiveLedgersByCompany(companyId: String) = flowOf(ledgers.values.filter { it.companyId == companyId && it.isActive })
    override suspend fun getLedgerById(companyId: String, ledgerId: String) = ledgers.values.firstOrNull { it.companyId == companyId && it.ledgerId == ledgerId }
    override suspend fun getLedgersByGroupId(companyId: String, groupId: String) = ledgers.values.filter { it.companyId == companyId && it.groupId == groupId }
    override suspend fun insertLedger(ledger: com.example.accounting.data.local.entity.LedgerEntity) {
        ledgers[ledger.ledgerId] = ledger
    }
    override suspend fun insertLedgers(ledgersList: List<com.example.accounting.data.local.entity.LedgerEntity>) {
        ledgersList.forEach { ledgers[it.ledgerId] = it }
    }
    override suspend fun updateLedger(ledger: com.example.accounting.data.local.entity.LedgerEntity) {
        ledgers[ledger.ledgerId] = ledger
    }
    override suspend fun updateLedgerBalance(companyId: String, ledgerId: String, balancePaise: Long, balanceType: com.example.accounting.core.common.DrCr) {
        ledgers[ledgerId]?.let {
            if (it.companyId == companyId) {
                ledgers[ledgerId] = it.copy(currentBalancePaise = balancePaise, currentBalanceType = balanceType)
            }
        }
    }
    override suspend fun deleteLedgerSafely(companyId: String, ledgerId: String) = 0
    override suspend fun deleteLedger(companyId: String, ledgerId: String): Int {
        val removed = ledgers.remove(ledgerId)
        return if (removed != null) 1 else 0
    }
    override suspend fun countTransactionsForLedger(companyId: String, ledgerId: String) = 0
    override suspend fun countJournalEntriesForLedger(companyId: String, ledgerId: String) = 0

    override fun getVouchersByCompany(companyId: String) = flowOf(vouchers.values.filter { it.companyId == companyId })
    override fun getAllVouchersByCompany(companyId: String) = flowOf(vouchers.values.filter { it.companyId == companyId })
    override fun getVouchersByFinancialYear(companyId: String, fyId: String) = flowOf(vouchers.values.filter { it.companyId == companyId && it.financialYearId == fyId })
    override fun getVouchersByDateRange(companyId: String, startDate: String, endDate: String) = flowOf(vouchers.values.filter { it.companyId == companyId && it.date in startDate..endDate })
    override suspend fun getVoucherById(companyId: String, voucherId: String) = vouchers.values.firstOrNull { it.companyId == companyId && it.voucherId == voucherId }
    override suspend fun getVoucherCountByType(companyId: String, fyId: String, type: VoucherType) = vouchers.values.count { it.companyId == companyId && it.financialYearId == fyId && it.voucherType == type }
    override suspend fun insertVoucher(voucher: com.example.accounting.data.local.entity.VoucherEntity) {
        vouchers[voucher.voucherId] = voucher
    }
    override suspend fun updateVoucher(voucher: com.example.accounting.data.local.entity.VoucherEntity) {
        vouchers[voucher.voucherId] = voucher
    }
    override suspend fun cancelVoucher(companyId: String, voucherId: String, updatedAt: Long) {
        vouchers[voucherId]?.let {
            if (it.companyId == companyId) {
                vouchers[voucherId] = it.copy(isCancelled = true, updatedAt = updatedAt)
            }
        }
    }
    override suspend fun deleteVoucher(companyId: String, voucherId: String) {
        val v = vouchers[voucherId]
        if (v?.companyId == companyId) {
            vouchers.remove(voucherId)
        }
    }
    override suspend fun isVoucherNumberTaken(companyId: String, financialYearId: String, voucherNumber: String) =
        vouchers.values.any { it.companyId == companyId && it.financialYearId == financialYearId && it.voucherNumber == voucherNumber }

    override fun getJournalItemsByVoucher(voucherId: String) = flowOf(journalItems.values.filter { it.voucherId == voucherId })
    override fun getAllJournalItems(companyId: String, fyId: String) = flowOf(journalItems.values.filter { it.companyId == companyId && it.financialYearId == fyId })
    override suspend fun getJournalItemsForVoucherSync(voucherId: String) = journalItems.values.filter { it.voucherId == voucherId }
    override suspend fun getJournalItemsByLedger(companyId: String, ledgerId: String) = journalItems.values.filter { it.companyId == companyId && it.ledgerId == ledgerId }
    override suspend fun insertJournalItems(items: List<com.example.accounting.data.local.entity.JournalItemEntity>) {
        items.forEach { journalItems[it.itemId] = it }
    }
    override suspend fun deleteJournalItemsByVoucher(voucherId: String) {
        journalItems.values.filter { it.voucherId == voucherId }.forEach { journalItems.remove(it.itemId) }
    }

    override fun getStockItemsByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.StockItemEntity>())
    override suspend fun getStockItemById(companyId: String, itemId: String) = null
    override suspend fun insertStockItem(stockItem: com.example.accounting.data.local.entity.StockItemEntity) {}
    override suspend fun insertStockItems(stockItems: List<com.example.accounting.data.local.entity.StockItemEntity>) {}
    override suspend fun updateStockQuantity(companyId: String, itemId: String, newQuantity: Long) {}
    override suspend fun updateStockCache(companyId: String, itemId: String, newQuantity: Long, newAvgCostPaise: Long) {}
    override suspend fun getStockLinesForVoucher(voucherId: String) = emptyList<com.example.accounting.data.local.entity.VoucherStockLineEntity>()
    override suspend fun insertVoucherStockLines(lines: List<com.example.accounting.data.local.entity.VoucherStockLineEntity>) {}
    override suspend fun getStockMovementsForItem(companyId: String, itemId: String) = emptyList<com.example.accounting.data.local.entity.StockMovementEntity>()
    override suspend fun getStockMovementsForVoucher(voucherId: String) = emptyList<com.example.accounting.data.local.entity.StockMovementEntity>()
    override suspend fun getStockMovementsForCompanyFY(companyId: String, fyId: String) = emptyList<com.example.accounting.data.local.entity.StockMovementEntity>()
    override suspend fun insertStockMovement(movement: com.example.accounting.data.local.entity.StockMovementEntity) {}
    override suspend fun insertStockMovements(movements: List<com.example.accounting.data.local.entity.StockMovementEntity>) {}

    // Phase 5 - mechanical no-op stubs only, satisfying the now-larger AccountingDao interface.
    // Zero behavior change to Phase 0-4 tests, which never exercise GST-transaction/settlement/
    // GST-filing-period data. Phase5TestSuite.kt backs these with a real decorator instead.
    override suspend fun getGstTransactionsForVoucher(voucherId: String): List<com.example.accounting.data.local.entity.GstTransactionEntity> = emptyList()
    override suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String): List<com.example.accounting.data.local.entity.GstTransactionEntity> = emptyList()
    override suspend fun getGstTransactionsByGroupId(companyId: String, groupId: String): List<com.example.accounting.data.local.entity.GstTransactionEntity> = emptyList()
    override suspend fun insertGstTransactions(transactions: List<com.example.accounting.data.local.entity.GstTransactionEntity>) {}
    override suspend fun getAllocationsForInvoice(invoiceVoucherId: String): List<com.example.accounting.data.local.entity.SettlementAllocationEntity> = emptyList()
    override suspend fun getAllocationsForSettlement(settlementVoucherId: String): List<com.example.accounting.data.local.entity.SettlementAllocationEntity> = emptyList()
    override suspend fun insertSettlementAllocations(allocations: List<com.example.accounting.data.local.entity.SettlementAllocationEntity>) {}
    override fun getGstFilingPeriodsByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.GstFilingPeriodEntity>())
    override suspend fun insertGstFilingPeriod(period: com.example.accounting.data.local.entity.GstFilingPeriodEntity) {}
    override suspend fun setGstFilingPeriodLock(companyId: String, filingPeriodId: String, isLocked: Boolean, lockedAt: Long?, lockedBy: String?) {}

    // Phase 7A - mechanical no-op stubs only, satisfying the now-larger AccountingDao interface.
    // Zero behavior change to Phase 0-6A tests, which never exercise Party/Invoice data.
    override fun getPartiesByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.PartyEntity>())
    override fun getPartiesByRole(companyId: String, role: com.example.accounting.domain.party.PartyRole) = flowOf(emptyList<com.example.accounting.data.local.entity.PartyEntity>())
    override suspend fun getPartyById(companyId: String, partyId: String): com.example.accounting.data.local.entity.PartyEntity? = null
    override suspend fun getPartyByLedgerId(companyId: String, ledgerId: String): com.example.accounting.data.local.entity.PartyEntity? = null
    override suspend fun insertParty(party: com.example.accounting.data.local.entity.PartyEntity) {}
    override suspend fun updateParty(party: com.example.accounting.data.local.entity.PartyEntity) {}
    override fun getInvoicesByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.InvoiceEntity>())
    override fun getInvoicesByParty(companyId: String, partyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.InvoiceEntity>())
    override suspend fun getInvoiceById(companyId: String, invoiceId: String): com.example.accounting.data.local.entity.InvoiceEntity? = null
    override suspend fun getInvoiceByVoucherId(voucherId: String): com.example.accounting.data.local.entity.InvoiceEntity? = null
    override suspend fun getInvoiceCountByType(companyId: String, fyId: String, invoiceType: com.example.accounting.domain.invoice.InvoiceType): Int = 0
    override suspend fun insertInvoice(invoice: com.example.accounting.data.local.entity.InvoiceEntity) {}
    override suspend fun updateInvoice(invoice: com.example.accounting.data.local.entity.InvoiceEntity) {}
    override suspend fun linkInvoiceToVoucher(companyId: String, invoiceId: String, voucherId: String, updatedAt: Long) {}
    override suspend fun deleteDraftInvoice(companyId: String, invoiceId: String): Int = 0
    override suspend fun getLinesForInvoice(invoiceId: String): List<com.example.accounting.data.local.entity.InvoiceLineEntity> = emptyList()
    override suspend fun insertInvoiceLines(lines: List<com.example.accounting.data.local.entity.InvoiceLineEntity>) {}
    override suspend fun deleteLinesForInvoice(invoiceId: String) {}

    // Phase 7B - mechanical no-op stubs only, satisfying the now-larger AccountingDao interface.
    // Zero behavior change to Phase 0-7A tests, which never exercise TradeDocument data.
    override fun getTradeDocumentsByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.TradeDocumentEntity>())
    override fun getTradeDocumentsByType(companyId: String, documentType: com.example.accounting.domain.document.DocumentType) = flowOf(emptyList<com.example.accounting.data.local.entity.TradeDocumentEntity>())
    override suspend fun getTradeDocumentById(companyId: String, tradeDocumentId: String): com.example.accounting.data.local.entity.TradeDocumentEntity? = null
    override suspend fun getTradeDocumentCountByType(companyId: String, fyId: String, documentType: com.example.accounting.domain.document.DocumentType): Int = 0
    override suspend fun insertTradeDocument(document: com.example.accounting.data.local.entity.TradeDocumentEntity) {}
    override suspend fun updateTradeDocument(document: com.example.accounting.data.local.entity.TradeDocumentEntity) {}
    override suspend fun deleteTradeDocument(companyId: String, tradeDocumentId: String): Int = 0
    override suspend fun getLinesForTradeDocument(tradeDocumentId: String): List<com.example.accounting.data.local.entity.TradeDocumentLineEntity> = emptyList()
    override suspend fun insertTradeDocumentLines(lines: List<com.example.accounting.data.local.entity.TradeDocumentLineEntity>) {}
    override suspend fun deleteLinesForTradeDocument(tradeDocumentId: String) {}

    // Phase 7D - mechanical no-op stubs only, satisfying the now-larger AccountingDao interface.
    // Zero behavior change to Phase 0-7C tests, which never exercise template/branding data.
    override fun getActiveTemplatesByType(companyId: String, documentType: com.example.accounting.domain.document.DocumentType) = flowOf(emptyList<com.example.accounting.data.local.entity.DocumentTemplateEntity>())
    override suspend fun getActiveTemplateVersion(companyId: String, templateId: String): com.example.accounting.data.local.entity.DocumentTemplateEntity? = null
    override suspend fun getTemplateVersion(companyId: String, templateId: String, version: Int): com.example.accounting.data.local.entity.DocumentTemplateEntity? = null
    override suspend fun getDefaultTemplate(companyId: String, documentType: com.example.accounting.domain.document.DocumentType): com.example.accounting.data.local.entity.DocumentTemplateEntity? = null
    override suspend fun getMaxTemplateVersion(companyId: String, templateId: String): Int? = null
    override suspend fun insertDocumentTemplate(template: com.example.accounting.data.local.entity.DocumentTemplateEntity) {}
    override suspend fun setTemplateStatus(companyId: String, templateId: String, version: Int, status: com.example.accounting.domain.rendering.TemplateStatus) {}
    override suspend fun clearDefaultTemplateFlag(companyId: String, documentType: com.example.accounting.domain.document.DocumentType) {}
    override suspend fun getBusinessProfile(companyId: String): com.example.accounting.data.local.entity.BusinessProfileEntity? = null
    override suspend fun insertBusinessProfile(profile: com.example.accounting.data.local.entity.BusinessProfileEntity) {}
    override suspend fun updateBusinessProfile(
        companyId: String, businessProfileId: String, businessName: String, legalName: String,
        constitutionType: com.example.accounting.domain.rendering.ConstitutionType, address: String,
        pinCode: String, city: String, state: String, country: String,
        phone: String, email: String, website: String, gstin: String, pan: String, tan: String, udyam: String, logoAssetId: String?,
        bankName: String, bankAccountNumber: String, bankIfsc: String, bankBranch: String, upiId: String,
        qrCodeAssetId: String?, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
    ) {}
    override suspend fun getIndividualProfile(companyId: String): com.example.accounting.data.local.entity.IndividualProfileEntity? = null
    override suspend fun insertIndividualProfile(profile: com.example.accounting.data.local.entity.IndividualProfileEntity) {}
    override suspend fun updateIndividualProfile(
        companyId: String, individualProfileId: String, name: String, address: String,
        pinCode: String, city: String, state: String, country: String, pan: String,
        phone: String, email: String, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
    ) {}
    override fun getDocumentAssetsByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.DocumentAssetEntity>())
    override suspend fun getDocumentAssetById(companyId: String, assetId: String): com.example.accounting.data.local.entity.DocumentAssetEntity? = null
    override suspend fun insertDocumentAsset(asset: com.example.accounting.data.local.entity.DocumentAssetEntity) {}
    override suspend fun deleteDocumentAsset(companyId: String, assetId: String): Int = 0
    override fun getRenderedDocumentRecords(companyId: String, documentId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.RenderedDocumentRecordEntity>())
    override suspend fun insertRenderedDocumentRecord(record: com.example.accounting.data.local.entity.RenderedDocumentRecordEntity) {}

    override fun getActiveRecurringVoucherSchedules(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.RecurringVoucherScheduleEntity>())
    override suspend fun getRecurringVoucherScheduleById(companyId: String, scheduleId: String): com.example.accounting.data.local.entity.RecurringVoucherScheduleEntity? = null
    override suspend fun insertRecurringVoucherSchedule(schedule: com.example.accounting.data.local.entity.RecurringVoucherScheduleEntity) {}
    override suspend fun getLinesForRecurringVoucherSchedule(scheduleId: String): List<com.example.accounting.data.local.entity.RecurringVoucherLineEntity> = emptyList()
    override suspend fun insertRecurringVoucherLines(lines: List<com.example.accounting.data.local.entity.RecurringVoucherLineEntity>) {}
    override suspend fun getRecurringVoucherDraftForPeriod(scheduleId: String, periodKey: String): com.example.accounting.data.local.entity.RecurringVoucherDraftEntity? = null
    override fun getRecurringVoucherDraftsByStatus(companyId: String, status: com.example.accounting.domain.recurring.RecurringVoucherDraftStatus) = flowOf(emptyList<com.example.accounting.data.local.entity.RecurringVoucherDraftEntity>())
    override suspend fun getRecurringVoucherDraftById(companyId: String, draftId: String): com.example.accounting.data.local.entity.RecurringVoucherDraftEntity? = null
    override suspend fun insertRecurringVoucherDraft(draft: com.example.accounting.data.local.entity.RecurringVoucherDraftEntity) {}
    override suspend fun updateRecurringVoucherDraft(draft: com.example.accounting.data.local.entity.RecurringVoucherDraftEntity) {}
    override suspend fun getLinesForRecurringVoucherDraft(draftId: String): List<com.example.accounting.data.local.entity.RecurringVoucherDraftLineEntity> = emptyList()
    override suspend fun insertRecurringVoucherDraftLines(lines: List<com.example.accounting.data.local.entity.RecurringVoucherDraftLineEntity>) {}
    override suspend fun deleteLinesForRecurringVoucherDraft(draftId: String) {}

    // Phase 7J-B: Management Layer - permanent no-op stubs by design (each phase's suite only pays
    // for real backing on the entities it needs, matching every prior "AwareDao" wrapper precedent).
    override suspend fun getVoucherDraftById(companyId: String, draftId: String): com.example.accounting.data.local.entity.VoucherDraftEntity? = null
    override fun getVoucherDraftsByStatus(companyId: String, status: com.example.accounting.application.voucher.VoucherDraftStatus) = flowOf(emptyList<com.example.accounting.data.local.entity.VoucherDraftEntity>())
    override suspend fun insertVoucherDraft(draft: com.example.accounting.data.local.entity.VoucherDraftEntity) {}
    override suspend fun updateVoucherDraft(draft: com.example.accounting.data.local.entity.VoucherDraftEntity) {}
    override suspend fun getLinesForVoucherDraft(draftId: String): List<com.example.accounting.data.local.entity.VoucherDraftLineEntity> = emptyList()
    override suspend fun insertVoucherDraftLines(lines: List<com.example.accounting.data.local.entity.VoucherDraftLineEntity>) {}
    override suspend fun deleteLinesForVoucherDraft(draftId: String) {}
    override suspend fun insertVoucherDocumentReference(reference: com.example.accounting.data.local.entity.VoucherDocumentReferenceEntity) {}
    override suspend fun getDocumentReferencesForVoucher(companyId: String, voucherId: String): List<com.example.accounting.data.local.entity.VoucherDocumentReferenceEntity> = emptyList()
    override suspend fun deleteVoucherDocumentReference(companyId: String, referenceId: String): Int = 0
    override suspend fun getVoucherAttachments(companyId: String, voucherId: String): List<com.example.accounting.data.local.dao.VoucherAttachmentRow> = emptyList()
    override suspend fun getSubscriptionForCompanyAndFy(companyId: String, financialYearId: String): com.example.accounting.data.local.entity.CompanySubscriptionEntity? = null
    override fun getSubscriptionsForCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.CompanySubscriptionEntity>())
    override suspend fun insertSubscription(subscription: com.example.accounting.data.local.entity.CompanySubscriptionEntity) {}
    override suspend fun updateSubscription(subscription: com.example.accounting.data.local.entity.CompanySubscriptionEntity) {}
    override fun getBankUpiProfilesForCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.BankUpiProfileEntity>())
    override fun getBankUpiProfilesForParty(companyId: String, partyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.BankUpiProfileEntity>())
    override suspend fun getBankUpiProfileById(companyId: String, bankUpiProfileId: String): com.example.accounting.data.local.entity.BankUpiProfileEntity? = null
    override suspend fun insertBankUpiProfile(profile: com.example.accounting.data.local.entity.BankUpiProfileEntity) {}
    override suspend fun updateBankUpiProfile(profile: com.example.accounting.data.local.entity.BankUpiProfileEntity) {}
    override suspend fun deleteBankUpiProfile(companyId: String, bankUpiProfileId: String): Int = 0

    override fun getGstReturnsForCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.GstReturnEntity>())
    override suspend fun getGstReturnById(companyId: String, gstReturnId: String): com.example.accounting.data.local.entity.GstReturnEntity? = null
    override suspend fun findGstReturn(companyId: String, periodKey: String, returnType: String, scheme: String): com.example.accounting.data.local.entity.GstReturnEntity? = null
    override suspend fun insertGstReturn(gstReturn: com.example.accounting.data.local.entity.GstReturnEntity) {}
    override suspend fun updateGstReturn(gstReturn: com.example.accounting.data.local.entity.GstReturnEntity) {}
    override suspend fun getArtifactsForGstReturn(gstReturnId: String): List<com.example.accounting.data.local.entity.GstReturnArtifactEntity> = emptyList()
    override suspend fun getGstReturnArtifactById(artifactId: String): com.example.accounting.data.local.entity.GstReturnArtifactEntity? = null
    override suspend fun insertGstReturnArtifact(artifact: com.example.accounting.data.local.entity.GstReturnArtifactEntity) {}
    override suspend fun getSectionsForGstReturn(gstReturnId: String): List<com.example.accounting.data.local.entity.GstReturnSectionEntity> = emptyList()
    override suspend fun upsertGstReturnSection(section: com.example.accounting.data.local.entity.GstReturnSectionEntity) {}
    override suspend fun getSubmissionsForGstReturn(gstReturnId: String): List<com.example.accounting.data.local.entity.GstReturnSubmissionEntity> = emptyList()
    override suspend fun insertGstReturnSubmission(submission: com.example.accounting.data.local.entity.GstReturnSubmissionEntity) {}

    override fun getAuditLogsByCompany(companyId: String) = flowOf(emptyList<com.example.accounting.data.local.entity.AuditLogEntity>())
    override suspend fun insertAuditLog(log: com.example.accounting.data.local.entity.AuditLogEntity) {}

    override fun getOutboxQueue(companyId: String): Flow<List<OutboxSyncEntity>> =
        flowOf(outbox.values.filter { it.companyId == companyId })
    override suspend fun getOutboxItemsByCompany(companyId: String, limit: Int): List<OutboxSyncEntity> =
        outbox.values.filter { it.companyId == companyId }.take(limit)
    override suspend fun getPendingOutboxBatch(companyId: String, batchSize: Int): List<OutboxSyncEntity> =
        outbox.values.filter { it.companyId == companyId && it.syncState == com.example.accounting.domain.accounting.SyncState.PENDING }.take(batchSize)
    override suspend fun getOutboxByIdempotencyKey(key: String): OutboxSyncEntity? =
        outbox.values.firstOrNull { it.idempotencyKey == key }
    override fun getPendingSyncCount(companyId: String): Flow<Int> =
        flowOf(outbox.values.count { it.companyId == companyId && it.syncState == com.example.accounting.domain.accounting.SyncState.PENDING })
    override suspend fun countPendingOutbox(companyId: String): Int =
        outbox.values.count { it.companyId == companyId && it.syncState == com.example.accounting.domain.accounting.SyncState.PENDING }
    override suspend fun insertOutboxSync(item: OutboxSyncEntity) {
        outbox[item.syncId] = item
    }
    override suspend fun insertOutboxItem(item: OutboxSyncEntity) {
        outbox[item.syncId] = item
    }
    override suspend fun updateOutboxItem(item: OutboxSyncEntity) {
        outbox[item.syncId] = item
    }
    override suspend fun updateOutboxStatus(syncId: String, state: SyncState, retries: Int, error: String?, updatedAt: Long) {
        outbox[syncId]?.let { outbox[syncId] = it.copy(syncState = state, retryCount = retries, lastError = error, updatedAt = updatedAt) }
    }
    override suspend fun updateOutboxSyncStatus(syncId: String, status: com.example.accounting.domain.accounting.SyncState, retryCount: Int, lastError: String?, updatedAt: Long) {
        outbox[syncId]?.let { outbox[syncId] = it.copy(syncState = status, retryCount = retryCount, lastError = lastError, updatedAt = updatedAt) }
    }
    override suspend fun markOutboxSynced(syncIds: List<String>, timestamp: Long) {
        syncIds.forEach { id ->
            outbox[id]?.let { outbox[id] = it.copy(syncState = com.example.accounting.domain.accounting.SyncState.SYNCED, updatedAt = timestamp) }
        }
    }
    override suspend fun deleteOutboxItem(syncId: String) {
        outbox.remove(syncId)
    }
    override suspend fun clearSyncedOutbox() {
        outbox.values.filter { it.syncState == com.example.accounting.domain.accounting.SyncState.SYNCED }.forEach { outbox.remove(it.syncId) }
    }
}

/**
 * In-memory ISecureStorage implementation for JVM testing.
 */
class FakeSecureStorage : ISecureStorage {
    private var authToken: String? = null
    private var refreshToken: String? = null
    private var apiKey: String? = null
    private var apiBaseUrl: String = "https://api.test.local/v1/"
    private var activeCompanyId: String? = null
    private var biometricEnabled: Boolean = false
    private val settings = ConcurrentHashMap<String, String>()
    private val deviceId: String = "DEV_TEST_${UUID.randomUUID().toString().take(8)}"

    override fun getDeviceId(): String = deviceId
    override fun getAuthToken(): String? = authToken
    override fun setAuthToken(token: String?) { authToken = token }
    override fun getRefreshToken(): String? = refreshToken
    override fun setRefreshToken(token: String?) { refreshToken = token }
    override fun getApiKey(): String? = apiKey
    override fun setApiKey(apiKey: String?) { this.apiKey = apiKey }
    override fun getApiBaseUrl(): String = apiBaseUrl
    override fun setApiBaseUrl(url: String) { this.apiBaseUrl = url }
    override fun getActiveCompanyId(): String? = activeCompanyId
    override fun setActiveCompanyId(companyId: String?) { this.activeCompanyId = companyId }
    override fun isBiometricEnabled(): Boolean = biometricEnabled
    override fun setBiometricEnabled(enabled: Boolean) { this.biometricEnabled = enabled }
    override fun getCompanySetting(companyId: String, key: String, defaultValue: String): String =
        settings["$companyId:$key"] ?: defaultValue
    override fun setCompanySetting(companyId: String, key: String, value: String) {
        settings["$companyId:$key"] = value
    }
    override fun getCompanyBooleanSetting(companyId: String, key: String, defaultValue: Boolean): Boolean =
        settings["$companyId:$key"]?.toBooleanStrictOrNull() ?: defaultValue
    override fun setCompanyBooleanSetting(companyId: String, key: String, value: Boolean) {
        settings["$companyId:$key"] = value.toString()
    }
    override fun clearSession() {
        authToken = null
        refreshToken = null
        apiKey = null
    }
}
