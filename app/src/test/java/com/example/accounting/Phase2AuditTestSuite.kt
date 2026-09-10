package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.AccountingTransactionException
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.AuditLogEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.OutboxSyncEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.DoubleEntryValidator
import com.example.accounting.domain.accounting.JournalEntry
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 2 Final Audit Test Pass.
 *
 * Adds the verification coverage requested in the Phase 2 audit that is NOT already exercised
 * by `AccountingInvariantsTest` (Phase 0), `Phase0TestSuite` (Phase 0), or `Phase2TestSuite`
 * (Phase 2 happy-path/idempotency/reversal). Where a numbered item from the audit spec is
 * already covered elsewhere, it is noted with a comment instead of being duplicated.
 *
 * All tests here are pure JVM (no Robolectric), against [FakeAccountingDao] and
 * [AccountingRepository] constructed with `db = null`. This works because
 * [AccountingRepository.postVoucher] and [AccountingRepository.deleteVoucherSafely] both run
 * their full pre-validation (double-entry, FY, period-lock, tenant isolation) BEFORE touching
 * `dbTransaction` - so rejection paths are exercised end-to-end through real production code,
 * not just the validator in isolation. Only the final atomic write needs a real Room
 * `AppDatabase`, which remains the known Robolectric-blocked item (see Section G below).
 *
 * Already covered elsewhere (not duplicated here):
 * - A1 balanced succeeds, A2 unbalanced rejected -> AccountingInvariantsTest
 * - A6 multi-line balanced succeeds -> Phase2TestSuite.testFullChain_SalesVoucher...
 * - B7 OPEN allows posting, B8 LOCKED rejects posting -> AccountingInvariantsTest
 * - D14 same-company succeeds, D15 foreign ledger rejected -> AccountingInvariantsTest
 * - E19 duplicate voucher number rejected -> Phase2TestSuite
 * - F22/F23 idempotent replay (no double effect) -> Phase2TestSuite
 * - H31-H35, H37, H38 reversal correctness, double-cancel rejection -> Phase2TestSuite
 * - J39/J40/J41 Suspense DR/CR balance flexibility, non-deletable -> SuspenseControlArchitectureTest
 *   (Robolectric; currently infrastructure-blocked, NOT re-implemented here as a duplicate)
 */
class Phase2AuditTestSuite {

    private val companyId = "COMP_AUDIT_A"
    private val otherCompanyId = "COMP_AUDIT_B"
    private val fyId = "FY_AUDIT_2026_27"

    // ---------- fixtures ----------

    private fun fy(id: String = fyId, company: String = companyId, current: Boolean = true, locked: Boolean = false) = FinancialYearEntity(
        financialYearId = id,
        companyId = company,
        fyCode = "FY 2026-27",
        startDate = "2026-04-01",
        endDate = "2027-03-31",
        isCurrent = current,
        isLocked = locked,
        lockedAt = null,
        lockedBy = null
    )

    private fun period(
        id: String,
        company: String = companyId,
        financialYearId: String = fyId,
        start: String,
        end: String,
        status: PeriodStatus
    ) = AccountingPeriodEntity(
        periodId = id,
        companyId = company,
        financialYearId = financialYearId,
        name = "Period $id",
        startDate = start,
        endDate = end,
        status = status,
        lockedAt = null,
        lockedBy = null
    )

    private fun ledger(id: String, company: String = companyId, openingPaise: Long = 0L, type: DrCr = DrCr.DEBIT) = LedgerEntity(
        ledgerId = id,
        companyId = company,
        groupId = "GRP_TEST",
        name = id,
        code = id,
        openingBalancePaise = openingPaise,
        openingBalanceType = type,
        currentBalancePaise = openingPaise,
        currentBalanceType = type,
        gstin = "",
        pan = "",
        stateCode = "27",
        email = "",
        phone = "",
        address = "",
        bankAccountNumber = "",
        bankIfsc = "",
        isSystem = false,
        isActive = true,
        hsnSacCode = "",
        defaultTaxRate = 0.0
    )

    private fun domainVoucher(
        voucherId: String,
        number: String,
        date: LocalDate,
        company: String = companyId,
        financialYearId: String = fyId,
        debitLedger: String = "LED_A",
        creditLedger: String = "LED_B",
        amountPaise: Long = 100_00L
    ) = Voucher(
        voucherId = voucherId,
        companyId = company,
        financialYearId = financialYearId,
        voucherNumber = number,
        voucherType = VoucherType.PAYMENT,
        date = date,
        totalAmount = Money.fromPaise(amountPaise),
        items = listOf(
            JournalItem(UUID.randomUUID().toString(), voucherId, company, financialYearId, debitLedger, debitLedger, DrCr.DEBIT, Money.fromPaise(amountPaise), lineOrder = 1),
            JournalItem(UUID.randomUUID().toString(), voucherId, company, financialYearId, creditLedger, creditLedger, DrCr.CREDIT, Money.fromPaise(amountPaise), lineOrder = 2)
        ),
        createdBy = "AUDITOR"
    )

    /** Seeds a company + FY + one OPEN period covering all of April 2026, plus two ledgers. */
    private fun FakeAccountingDao.seedOpenCompany(company: String = companyId, financialYearId: String = fyId) = runBlocking {
        insertFinancialYear(fy(financialYearId, company))
        insertPeriods(listOf(period("PER_${company}_1", company, financialYearId, "2026-04-01", "2026-04-30", PeriodStatus.OPEN)))
        insertLedger(ledger("LED_A", company))
        insertLedger(ledger("LED_B", company))
    }

    // ==========================================
    // A. DOUBLE ENTRY (new items only)
    // ==========================================
    @Test
    fun a3_ZeroValueLine_Rejected() {
        val voucher = domainVoucher("VCH_A3", "PMT-A3", LocalDate.of(2026, 4, 10)).let {
            it.copy(items = listOf(
                JournalItem("I1", it.voucherId, companyId, fyId, "LED_A", "LED_A", DrCr.DEBIT, Money.ZERO, lineOrder = 1),
                JournalItem("I2", it.voucherId, companyId, fyId, "LED_B", "LED_B", DrCr.CREDIT, Money.ZERO, lineOrder = 2)
            ))
        }
        val result = DoubleEntryValidator.validate(voucher, null, null, setOf("LED_A", "LED_B"))
        assertTrue("Zero-value lines must be rejected", result is AccountingResult.Failure)
    }

    @Test
    fun a4_LineWithBothDebitAndCredit_UnrepresentableByJournalEntryType() {
        // JournalItem has a single `type: DrCr` field, so a single line with both Dr and Cr
        // cannot even be constructed via the domain model - this is a compile-time guarantee,
        // not merely a runtime check. The equivalent JournalEntry (debit/credit as two separate
        // Money fields) DOES enforce this at construction via an `init { require(...) }` block:
        try {
            JournalEntry(
                journalEntryId = "JE_BAD",
                voucherId = "VCH_BAD",
                companyId = companyId,
                financialYearId = fyId,
                ledgerId = "LED_A",
                debit = Money.fromPaise(100_00L),
                credit = Money.fromPaise(50_00L)
            )
            fail("Expected construction to reject a line with both Dr and Cr populated")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("cannot contain both") == true)
        }
    }

    @Test
    fun a5_SingleLineVoucher_Rejected() {
        val voucher = domainVoucher("VCH_A5", "PMT-A5", LocalDate.of(2026, 4, 10)).let {
            it.copy(items = listOf(it.items.first()))
        }
        val result = DoubleEntryValidator.validate(voucher, null, null, setOf("LED_A", "LED_B"))
        assertTrue("Single-line voucher must be rejected (needs >=1 debit AND >=1 credit line)", result is AccountingResult.Failure)
    }

    // ==========================================
    // B. PERIOD (new statuses + cancellation parity)
    // ==========================================
    @Test
    fun b9_ClosedPeriodConcept_MappedToNonOpenStatuses() {
        // NOTE: PeriodStatus in this codebase has exactly OPEN / LOCKED / AUDIT_LOCKED - there is
        // no distinct CLOSED value. "Closed" is represented as LOCKED (see docs/04_ACCOUNTING_PERIOD.md
        // and Project Principle 4: "Closed or audit-locked accounting periods reject all new
        // postings, edits, and cancellations"). Per the audit instruction not to add a new schema
        // field/enum value unless genuinely necessary, this test documents that LOCKED already
        // covers "closed to new postings" and is exercised by b8; AUDIT_LOCKED is exercised by b10.
        assertEquals(setOf(PeriodStatus.OPEN, PeriodStatus.LOCKED, PeriodStatus.AUDIT_LOCKED).size, PeriodStatus.entries.size)
    }

    @Test
    fun b10_AuditLockedPeriod_RejectsPosting() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertFinancialYear(fy())
        dao.insertPeriods(listOf(period("PER_AUDIT_LOCK", start = "2026-04-01", end = "2026-04-30", status = PeriodStatus.AUDIT_LOCKED)))
        dao.insertLedger(ledger("LED_A"))
        dao.insertLedger(ledger("LED_B"))
        val repo = AccountingRepository(dao)

        val result = repo.postVoucher(domainVoucher("VCH_B10", "PMT-B10", LocalDate.of(2026, 4, 15)))
        assertTrue("AUDIT_LOCKED period must reject posting", result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.PeriodLocked)
    }

    @Test
    fun b_Cancellation_RespectsSamePeriodLockRuleAsPosting() = runBlocking {
        val dao = FakeAccountingDao()
        dao.seedOpenCompany()
        val repo = AccountingRepository(dao)

        // Seed a voucher as if it had been posted earlier while the period was still OPEN, then
        // lock the period and attempt cancellation through the repository's real pre-validation
        // path (the same `findMatchingPeriod` + `isOpen` check `postVoucher` uses).
        val voucherId = "VCH_B_CANCEL_LOCK"
        dao.insertVoucher(VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-BCL",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        ))
        dao.setPeriodStatus(companyId, "PER_${companyId}_1", PeriodStatus.LOCKED, "SUPERVISOR", System.currentTimeMillis())

        val cancelResult = repo.deleteVoucherSafely(companyId, fyId, voucherId)
        assertTrue("Cancellation in a locked period must be rejected, same as posting", cancelResult is AccountingResult.Failure)
        assertTrue((cancelResult as AccountingResult.Failure).error is AppError.PeriodLocked)
    }

    // ==========================================
    // C. FINANCIAL YEAR
    // ==========================================
    @Test
    fun c11_VoucherDateInsideFY_Succeeds_ValidatorLevel() {
        val fyDomain = FinancialYear(fyId, companyId, "FY 2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), isCurrent = true)
        val period = AccountingPeriod("PER_1", companyId, fyId, "Apr", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), PeriodStatus.OPEN)
        val voucher = domainVoucher("VCH_C11", "PMT-C11", LocalDate.of(2026, 4, 15))
        val result = DoubleEntryValidator.validate(voucher, fyDomain, period, setOf("LED_A", "LED_B"))
        assertTrue(result is AccountingResult.Success)
    }

    // c12 (date outside FY rejected) already covered by AccountingInvariantsTest.testFinancialYearIsolation...

    @Test
    fun c13_PeriodMustBelongToVoucherFY_Rejected() {
        val fyDomain = FinancialYear(fyId, companyId, "FY 2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), isCurrent = true)
        // Period genuinely belongs to a DIFFERENT financial year id than the voucher's.
        val foreignPeriod = AccountingPeriod("PER_X", companyId, "FY_OTHER", "Apr", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), PeriodStatus.OPEN)
        val voucher = domainVoucher("VCH_C13", "PMT-C13", LocalDate.of(2026, 4, 15), financialYearId = fyId)
        val result = DoubleEntryValidator.validate(voucher, fyDomain, foreignPeriod, setOf("LED_A", "LED_B"))
        assertTrue("Period belonging to a different FY than the voucher must be rejected", result is AccountingResult.Failure)
    }

    // ==========================================
    // D. TENANT ISOLATION (new items only)
    // ==========================================
    @Test
    fun d16_ForeignFinancialYear_Rejected() {
        val foreignFy = FinancialYear(fyId, otherCompanyId, "FY 2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), isCurrent = true)
        val voucher = domainVoucher("VCH_D16", "PMT-D16", LocalDate.of(2026, 4, 15), company = companyId)
        val result = DoubleEntryValidator.validate(voucher, foreignFy, null, setOf("LED_A", "LED_B"))
        assertTrue("Financial Year belonging to another company must be rejected", result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.TenantMismatch)
    }

    @Test
    fun d17_ForeignPeriod_Rejected() {
        val fyDomain = FinancialYear(fyId, companyId, "FY 2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), isCurrent = true)
        val foreignPeriod = AccountingPeriod("PER_X", otherCompanyId, fyId, "Apr", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), PeriodStatus.OPEN)
        val voucher = domainVoucher("VCH_D17", "PMT-D17", LocalDate.of(2026, 4, 15), company = companyId)
        val result = DoubleEntryValidator.validate(voucher, fyDomain, foreignPeriod, setOf("LED_A", "LED_B"))
        assertTrue("Accounting period belonging to another company must be rejected", result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.TenantMismatch)
    }

    // ==========================================
    // E. NUMBERING
    // ==========================================
    @Test
    fun e18_VoucherNumberGeneratedCorrectly() = runBlocking {
        val dao = FakeAccountingDao()
        dao.seedOpenCompany()
        val repo = AccountingRepository(dao)
        val number = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.PAYMENT)
        assertEquals("PMT-2026-0001", number)
    }

    @Test
    fun e20_SameTypeDifferentFY_IndependentSequence() = runBlocking {
        val dao = FakeAccountingDao()
        val fy2 = "FY_AUDIT_2027_28"
        dao.seedOpenCompany()
        dao.insertFinancialYear(fy(fy2, companyId).copy(startDate = "2027-04-01", endDate = "2028-03-31"))

        // Simulate one PAYMENT already posted in the first FY.
        dao.insertVoucher(VoucherEntity(
            voucherId = "VCH_E20_1", companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-2026-0001",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        ))

        val repo = AccountingRepository(dao)
        val numberInFy1 = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.PAYMENT)
        val numberInFy2 = repo.generateNextVoucherNumber(companyId, fy2, VoucherType.PAYMENT)

        assertEquals("PMT-2026-0002", numberInFy1) // continues fy1's sequence
        assertEquals("PMT-2027-0001", numberInFy2) // fy2 starts fresh, independent of fy1
    }

    @Test
    fun e21_DifferentVoucherTypes_IndependentSequence() = runBlocking {
        val dao = FakeAccountingDao()
        dao.seedOpenCompany()
        dao.insertVoucher(VoucherEntity(
            voucherId = "VCH_E21_1", companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-2026-0001",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        ))
        val repo = AccountingRepository(dao)
        val nextPayment = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.PAYMENT)
        val nextReceipt = repo.generateNextVoucherNumber(companyId, fyId, VoucherType.RECEIPT)
        assertEquals("PMT-2026-0002", nextPayment)
        assertEquals("RCT-2026-0001", nextReceipt) // RECEIPT sequence unaffected by PAYMENT count
    }

    // ==========================================
    // F. IDEMPOTENCY (new items only)
    // ==========================================
    @Test
    fun f24_DifferentIdempotencyKey_CreatesSeparateValidTransaction() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_A", openingPaise = 0L))
        dao.insertLedger(ledger("LED_B", openingPaise = 500_00L))

        val v1 = VoucherEntity(
            voucherId = "VCH_F24_1", companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-F24-1",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        )
        val v2 = v1.copy(voucherId = "VCH_F24_2", voucherNumber = "PMT-F24-2")
        val items1 = listOf(JournalItemEntity("I1", v1.voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 100_00L, "", 1), JournalItemEntity("I2", v1.voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 100_00L, "", 2))
        val items2 = listOf(JournalItemEntity("I3", v2.voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 50_00L, "", 1), JournalItemEntity("I4", v2.voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 50_00L, "", 2))

        VoucherPostingEngine.post(dao, v1, items1, "IK-F24-A", "AUDITOR")
        VoucherPostingEngine.post(dao, v2, items2, "IK-F24-B", "AUDITOR")

        assertNotNull(dao.getVoucherById(companyId, "VCH_F24_1"))
        assertNotNull(dao.getVoucherById(companyId, "VCH_F24_2"))
        assertEquals(350_00L, dao.getLedgerById(companyId, "LED_B")!!.currentBalancePaise) // 500 - 100 - 50
        assertNotNull(dao.getOutboxByIdempotencyKey("IK-F24-A"))
        assertNotNull(dao.getOutboxByIdempotencyKey("IK-F24-B"))
    }

    @Test
    fun f25_CancellationReplay_DoesNotDuplicateReversal() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_A", openingPaise = 0L))
        dao.insertLedger(ledger("LED_B", openingPaise = 500_00L))
        val voucherId = "VCH_F25"
        val voucher = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-F25",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(JournalItemEntity("I1", voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 100_00L, "", 1), JournalItemEntity("I2", voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 100_00L, "", 2))
        VoucherPostingEngine.post(dao, voucher, items, "IK-F25-POST", "AUDITOR")

        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-F25-CANCEL", "AUDITOR")
        val itemCountAfterFirstCancel = dao.getJournalItemsForVoucherSync(voucherId).size

        // Replaying the exact same cancellation request (same idempotency key) must be a no-op,
        // not a second reversal (which would double-reverse the ledger).
        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-F25-CANCEL", "AUDITOR")
        val itemCountAfterReplay = dao.getJournalItemsForVoucherSync(voucherId).size

        assertEquals("Replaying the cancellation request must not create extra reversal lines", itemCountAfterFirstCancel, itemCountAfterReplay)
        assertEquals(0L, dao.getLedgerById(companyId, "LED_A")!!.currentBalancePaise)
        assertEquals(500_00L, dao.getLedgerById(companyId, "LED_B")!!.currentBalancePaise)
    }

    // ==========================================
    // G. ATOMICITY - failure injection
    // ==========================================
    // IMPORTANT SCOPE NOTE: `VoucherPostingEngine` is deliberately Room-independent (see
    // DatabaseTransaction.kt) so its guard logic can run against a fake DAO. That means these
    // tests can verify the ENGINE correctly THROWS and PROPAGATES an exception at each failure
    // point (the necessary precondition for Room's `withTransaction` to roll back in production),
    // and can observe that the in-memory fake itself has NO transactional rollback (writes made
    // before the injected failure remain visible in the fake - this is a property of the fake,
    // not of production behavior). Genuine SQLite-transaction rollback verification requires a
    // real Room `AppDatabase` and is Robolectric/instrumented-test territory - INFRASTRUCTURE
    // BLOCKED in this environment, consistent with `SuspenseControlArchitectureTest`. This is
    // reported separately in the final result, not claimed as covered.

    private class ThrowingAccountingDao(
        private val delegate: AccountingDao,
        private val failStep: String
    ) : AccountingDao by delegate {
        override suspend fun insertVoucher(voucher: VoucherEntity) {
            if (failStep == "voucher") throw RuntimeException("Injected failure: voucher insert")
            delegate.insertVoucher(voucher)
        }
        override suspend fun insertJournalItems(items: List<JournalItemEntity>) {
            if (failStep == "journal") throw RuntimeException("Injected failure: journal insert")
            delegate.insertJournalItems(items)
        }
        override suspend fun updateLedgerBalance(companyId: String, ledgerId: String, balancePaise: Long, balanceType: DrCr) {
            if (failStep == "ledger") throw RuntimeException("Injected failure: ledger balance update")
            delegate.updateLedgerBalance(companyId, ledgerId, balancePaise, balanceType)
        }
        override suspend fun insertAuditLog(log: AuditLogEntity) {
            if (failStep == "audit") throw RuntimeException("Injected failure: audit log insert")
            delegate.insertAuditLog(log)
        }
        override suspend fun insertOutboxItem(item: OutboxSyncEntity) {
            if (failStep == "outbox") throw RuntimeException("Injected failure: outbox insert")
            delegate.insertOutboxItem(item)
        }
    }

    private fun freshPostingFixture(): Pair<FakeAccountingDao, Pair<VoucherEntity, List<JournalItemEntity>>> {
        val fake = FakeAccountingDao()
        runBlocking {
            fake.insertLedger(ledger("LED_A", openingPaise = 0L))
            fake.insertLedger(ledger("LED_B", openingPaise = 500_00L))
        }
        val voucherId = "VCH_ATOMIC_${UUID.randomUUID()}"
        val voucher = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-${voucherId.takeLast(6)}",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(
            JournalItemEntity("I1_${voucherId}", voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 100_00L, "", 1),
            JournalItemEntity("I2_${voucherId}", voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 100_00L, "", 2)
        )
        return fake to (voucher to items)
    }

    @Test
    fun g26_VoucherInsertFailure_NothingWasWrittenBeforeIt() = runBlocking {
        val (fake, fixture) = freshPostingFixture()
        val (voucher, items) = fixture
        val throwing = ThrowingAccountingDao(fake, failStep = "voucher")
        try {
            VoucherPostingEngine.post(throwing, voucher, items, "IK-G26", "AUDITOR")
            fail("Expected injected voucher-insert failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("Injected failure: voucher insert", e.message)
        }
        // Voucher insert is the first write step - nothing preceding it writes, so the fake is
        // genuinely untouched here (this case happens to demonstrate full rollback-equivalent state).
        assertNull(fake.getVoucherById(companyId, voucher.voucherId))
        assertTrue(fake.getJournalItemsForVoucherSync(voucher.voucherId).isEmpty())
        assertEquals(500_00L, fake.getLedgerById(companyId, "LED_B")!!.currentBalancePaise) // unchanged
        assertNull(fake.getOutboxByIdempotencyKey("IK-G26"))
    }

    @Test
    fun g27_JournalInsertFailure_PropagatesException() = runBlocking {
        val (fake, fixture) = freshPostingFixture()
        val (voucher, items) = fixture
        val throwing = ThrowingAccountingDao(fake, failStep = "journal")
        try {
            VoucherPostingEngine.post(throwing, voucher, items, "IK-G27", "AUDITOR")
            fail("Expected injected journal-insert failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("Injected failure: journal insert", e.message)
        }
        // Voucher header WAS written before the injected failure. The in-memory fake has no
        // transaction semantics, so it remains present here - a real Room `withTransaction`
        // would roll this back too (Robolectric/instrumented-test territory, not verifiable here).
        assertNotNull("Fake has no rollback; documents the gap real Room atomicity must close", fake.getVoucherById(companyId, voucher.voucherId))
        assertTrue(fake.getJournalItemsForVoucherSync(voucher.voucherId).isEmpty())
        assertEquals(500_00L, fake.getLedgerById(companyId, "LED_B")!!.currentBalancePaise) // unchanged
    }

    @Test
    fun g28_LedgerEffectFailure_PropagatesException() = runBlocking {
        val (fake, fixture) = freshPostingFixture()
        val (voucher, items) = fixture
        val throwing = ThrowingAccountingDao(fake, failStep = "ledger")
        try {
            VoucherPostingEngine.post(throwing, voucher, items, "IK-G28", "AUDITOR")
            fail("Expected injected ledger-balance failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("Injected failure: ledger balance update", e.message)
        }
        assertNotNull(fake.getVoucherById(companyId, voucher.voucherId))
        assertEquals(2, fake.getJournalItemsForVoucherSync(voucher.voucherId).size)
        // LED_A is the first ledger touched in item order and throws immediately - no ledger balance changed.
        assertEquals(0L, fake.getLedgerById(companyId, "LED_A")!!.currentBalancePaise)
        assertEquals(500_00L, fake.getLedgerById(companyId, "LED_B")!!.currentBalancePaise)
    }

    @Test
    fun g29_AuditFailure_PropagatesException() = runBlocking {
        val (fake, fixture) = freshPostingFixture()
        val (voucher, items) = fixture
        val throwing = ThrowingAccountingDao(fake, failStep = "audit")
        try {
            VoucherPostingEngine.post(throwing, voucher, items, "IK-G29", "AUDITOR")
            fail("Expected injected audit-log failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("Injected failure: audit log insert", e.message)
        }
        assertNotNull(fake.getVoucherById(companyId, voucher.voucherId))
        assertEquals(2, fake.getJournalItemsForVoucherSync(voucher.voucherId).size)
        assertEquals(400_00L, fake.getLedgerById(companyId, "LED_B")!!.currentBalancePaise) // ledger effect already applied
        assertNull(fake.getOutboxByIdempotencyKey("IK-G29")) // outbox never reached
    }

    @Test
    fun g30_OutboxFailure_PropagatesException() = runBlocking {
        val (fake, fixture) = freshPostingFixture()
        val (voucher, items) = fixture
        val throwing = ThrowingAccountingDao(fake, failStep = "outbox")
        try {
            VoucherPostingEngine.post(throwing, voucher, items, "IK-G30", "AUDITOR")
            fail("Expected injected outbox failure to propagate")
        } catch (e: RuntimeException) {
            assertEquals("Injected failure: outbox insert", e.message)
        }
        assertNotNull(fake.getVoucherById(companyId, voucher.voucherId))
        assertEquals(2, fake.getJournalItemsForVoucherSync(voucher.voucherId).size)
        assertEquals(400_00L, fake.getLedgerById(companyId, "LED_B")!!.currentBalancePaise)
        assertNull("Outbox insert is the failing step - no outbox row must exist", fake.getOutboxByIdempotencyKey("IK-G30"))
    }

    // ==========================================
    // H. CANCELLATION / REVERSAL (new item only - rest covered by Phase2TestSuite)
    // ==========================================
    // h31-h35, h37, h38 -> Phase2TestSuite.testCancelVoucherAtomic_CompensatingReversal... /
    //                       testCancelVoucherAtomic_AlreadyCancelled_Rejected
    // h36 (cancellation in locked period rejected) -> covered above as b_Cancellation_RespectsSamePeriodLockRuleAsPosting

    // ==========================================
    // I. REVERSAL RELATIONSHIP - data model inspection
    // ==========================================
    /**
     * Documents and verifies HOW the original-voucher -> cancellation is actually represented.
     *
     * Step 3 live-device fix - supersedes this suite's own earlier "genuinely deleted" design: a
     * hard delete let a cancelled voucher's number be handed straight back out to the next voucher
     * of that type (reproduced live correcting a Receipt - two different real transactions ended up
     * sharing one voucher number), and made [VoucherDetailDialog]'s own CANCELLED badge and
     * `correctedByVoucher` link permanently unreachable, since the row they depend on no longer
     * existed. There is no dedicated `reversesItemId`/`reversalOfVoucherId` foreign key in the
     * schema, and none is added here either - the relationship is represented as follows (verified
     * below):
     *  - Voucher level (unambiguous): `VoucherEntity.isCancelled` flips to true on the SAME row -
     *    never deleted, never replaced by a second "reversal voucher".
     *  - Line level: the original `JournalItemEntity` rows are left exactly as posted - never
     *    deleted, never joined by a new offsetting/reversal row. A cancelled voucher's own detail
     *    view therefore still shows its one real set of Dr/Cr lines, now under a CANCELLED badge,
     *    which is also what keeps this safely distinct from the still-earlier "Rule 12" design this
     *    suite already rejected (that one risked a single voucher showing both its original lines
     *    AND a reversal of itself as if two real transactions had occurred).
     * Conclusion: the existing model represents this relationship SAFELY for accounting-integrity
     * purposes without a new schema field, per the audit instruction to only add one if the
     * current model genuinely cannot represent it safely.
     */
    @Test
    fun i_CancelledVoucher_IsSoftCancelled_HistoryPreservedOnTheSameRow() = runBlocking {
        // Step 3 live-device fix: a voucher's period never having been reported to the government
        // still makes it cancellable, but "cancelled" now means the isCancelled flag on this exact
        // row, never a delete - see this test's own KDoc above for why the hard-delete alternative
        // broke voucher-number uniqueness and the app's own CANCELLED-badge/audit-trail features.
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_A", openingPaise = 0L))
        dao.insertLedger(ledger("LED_B", openingPaise = 500_00L))
        val voucherId = "VCH_I_REVERSAL"
        val voucher = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-I1",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        )
        val originalItems = listOf(
            JournalItemEntity("ORIG_1", voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 100_00L, "", 1),
            JournalItemEntity("ORIG_2", voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 100_00L, "", 2)
        )
        VoucherPostingEngine.post(dao, voucher, originalItems, "IK-I-POST", "AUDITOR")
        VoucherPostingEngine.cancel(dao, companyId, fyId, voucherId, "IK-I-CANCEL", "AUDITOR")

        // The original journal items remain exactly as posted - no deletion, no offsetting/reversal
        // row appended alongside them.
        val allItems = dao.getJournalItemsForVoucherSync(voucherId)
        assertEquals(2, allItems.size)
        assertTrue(allItems.all { it.itemId == "ORIG_1" || it.itemId == "ORIG_2" })

        // The voucher row persists too, flagged isCancelled rather than gone.
        val cancelled = dao.getVoucherById(companyId, voucherId)
        assertNotNull(cancelled)
        assertTrue(cancelled!!.isCancelled)

        // Net effect per ledger is exactly zero (ledger balances are still correctly reversed).
        // The CANCEL_VOUCHER AuditLog record (see VoucherPostingEngine.cancel) is a second,
        // independent record of the same fact - the voucher row above is now the primary one.
        assertEquals(0L, dao.getLedgerById(companyId, "LED_A")!!.currentBalancePaise)
        assertEquals(500_00L, dao.getLedgerById(companyId, "LED_B")!!.currentBalancePaise)
    }

    // ==========================================
    // J. SUSPENSE (new item only - 39/40/41 covered by SuspenseControlArchitectureTest, Robolectric-blocked)
    // ==========================================
    @Test
    fun j42_SuspenseLedger_CannotBypassDebitEqualsCredit() {
        // An unbalanced voucher must be rejected by DoubleEntryValidator regardless of whether
        // a Suspense ledger is one of the lines - there is no special-casing anywhere in the
        // validator that exempts Suspense from the balance invariant.
        val suspenseId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyId"
        val voucherId = "VCH_J42"
        val voucher = Voucher(
            voucherId = voucherId,
            companyId = companyId,
            financialYearId = fyId,
            voucherNumber = "JRN-J42",
            voucherType = VoucherType.JOURNAL,
            date = LocalDate.of(2026, 4, 15),
            totalAmount = Money.fromPaise(100_00L),
            items = listOf(
                JournalItem("I1", voucherId, companyId, fyId, suspenseId, "Suspense", DrCr.DEBIT, Money.fromPaise(100_00L), lineOrder = 1),
                JournalItem("I2", voucherId, companyId, fyId, "LED_B", "LED_B", DrCr.CREDIT, Money.fromPaise(90_00L), lineOrder = 2) // deliberately unbalanced
            )
        )
        val result = DoubleEntryValidator.validate(voucher, null, null, setOf(suspenseId, "LED_B"))
        assertTrue("Suspense involvement must not bypass the Dr==Cr invariant", result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.UnbalancedVoucher)
    }

    // ==========================================
    // K. OFFLINE
    // ==========================================
    @Test
    fun k_PostingSucceedsWithZeroNetworkDependency() = runBlocking {
        // AccountingDao/FakeAccountingDao/VoucherPostingEngine have no network, HTTP, or
        // connectivity-check calls anywhere in their code paths - posting is purely local reads
        // and writes. This test posts successfully using only the local fake, demonstrating
        // offline-first posting works by construction.
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_A", openingPaise = 0L))
        dao.insertLedger(ledger("LED_B", openingPaise = 500_00L))
        val voucherId = "VCH_K_OFFLINE"
        val voucher = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-K1",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(JournalItemEntity("I1", voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 100_00L, "", 1), JournalItemEntity("I2", voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 100_00L, "", 2))

        VoucherPostingEngine.post(dao, voucher, items, "IK-K1", "AUDITOR")
        assertNotNull(dao.getVoucherById(companyId, voucherId))
    }

    @Test
    fun k_SuccessfulLocalPosting_CreatesPendingOutboxRecord() = runBlocking {
        val dao = FakeAccountingDao()
        dao.insertLedger(ledger("LED_A", openingPaise = 0L))
        dao.insertLedger(ledger("LED_B", openingPaise = 500_00L))
        val voucherId = "VCH_K_OUTBOX"
        val voucher = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = "PMT-K2",
            voucherType = VoucherType.PAYMENT, date = "2026-04-15", referenceNumber = "", narration = "",
            totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "AUDITOR", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(JournalItemEntity("I1", voucherId, companyId, fyId, "LED_A", DrCr.DEBIT, 100_00L, "", 1), JournalItemEntity("I2", voucherId, companyId, fyId, "LED_B", DrCr.CREDIT, 100_00L, "", 2))

        VoucherPostingEngine.post(dao, voucher, items, "IK-K2", "AUDITOR")

        val outboxEntry = dao.getOutboxByIdempotencyKey("IK-K2")
        assertNotNull("Local posting must queue a sync record for later network availability", outboxEntry)
        assertEquals(SyncState.PENDING, outboxEntry!!.syncState)
        assertEquals("INSERT", outboxEntry.operation)
    }
}
