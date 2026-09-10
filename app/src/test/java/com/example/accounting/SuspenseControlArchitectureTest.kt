package com.example.accounting

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.AppDatabase
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SuspenseControlArchitectureTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: AccountingRepository
    private val companyA = "COMP_ALPHA_001"
    private val companyB = "COMP_BETA_002"

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AccountingRepository(db.accountingDao())

        repository.seedInitialDataForCompany(companyA, "Alpha Corp", "27AAAAA0000A1Z5")
        repository.seedInitialDataForCompany(companyB, "Beta LLC", "29BBBBB1111B1Z2")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun test1_SuspenseHierarchy_ParentedUnderSpecialControlGroup() = runBlocking {
        val groups = repository.getGroups(companyA).first()
        val suspenseGroup = groups.find { it.groupId == "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA" }
        assertNotNull("Suspense group must exist", suspenseGroup)
        assertEquals("Group name must be Suspense A/c", Constants.SYS_SUSPENSE_ACCOUNT, suspenseGroup?.name)
        assertEquals("Group must be SPECIAL_CONTROL", PrimaryGroup.SPECIAL_CONTROL, suspenseGroup?.primaryGroup)
        assertTrue("Suspense group isSpecialControl flag must be true", suspenseGroup?.primaryGroup?.isSpecialControl == true)
        assertTrue("Suspense group must be marked system", suspenseGroup?.isSystem == true)

        val ledgers = repository.getLedgers(companyA).first()
        val suspenseLedger = ledgers.find { it.ledgerId == "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA" }
        assertNotNull("Suspense ledger must exist", suspenseLedger)
        assertEquals("Suspense ledger parent must be GRP_SYS_SUSPENSE", "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA", suspenseLedger?.groupId)
        assertNotEquals("Suspense ledger must NOT belong to Current Liabilities", "GRP_CURRENT_LIAB_$companyA", suspenseLedger?.groupId)
    }

    @Test
    fun test2_SuspenseGroup_NonDeletable() = runBlocking {
        val deleteResult = repository.deleteGroup(companyA, "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA")
        assertTrue("Deleting Suspense group must be rejected", deleteResult is AccountingResult.Failure)
    }

    @Test
    fun test3_SuspenseGroup_NonRenamable() = runBlocking {
        val groups = repository.getGroups(companyA).first()
        val suspenseGroup = groups.first { it.groupId == "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA" }
        val renameAttempt = suspenseGroup.copy(name = "Modified Suspense Name")
        val result = repository.updateGroup(renameAttempt)
        assertTrue("Renaming Suspense group must be rejected", result is AccountingResult.Failure)
    }

    @Test
    fun test4_SuspenseGroup_NonReparentable() = runBlocking {
        val groups = repository.getGroups(companyA).first()
        val suspenseGroup = groups.first { it.groupId == "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA" }
        val reparentAttempt = suspenseGroup.copy(parentGroupId = "GRP_CURRENT_LIAB_$companyA")
        val result = repository.updateGroup(reparentAttempt)
        assertTrue("Reparenting Suspense group must be rejected", result is AccountingResult.Failure)
    }

    @Test
    fun test5_SuspenseGroup_NonReclassifiable() = runBlocking {
        val groups = repository.getGroups(companyA).first()
        val suspenseGroup = groups.first { it.groupId == "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA" }
        val reclassifyAttempt = suspenseGroup.copy(primaryGroup = PrimaryGroup.LIABILITIES)
        val result = repository.updateGroup(reclassifyAttempt)
        assertTrue("Reclassifying Suspense group must be rejected", result is AccountingResult.Failure)
    }

    @Test
    fun test6_SuspenseLedger_NonDeletable() = runBlocking {
        val deleteResult = repository.deleteLedgerSafely(companyA, "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA")
        assertTrue("Deleting Suspense ledger must be rejected", deleteResult is AccountingResult.Failure)
    }

    @Test
    fun test7_SuspenseLedger_NonRenamableAndNonReparentable() = runBlocking {
        val ledgers = repository.getLedgers(companyA).first()
        val suspenseLedger = ledgers.first { it.ledgerId == "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA" }

        val renameAttempt = suspenseLedger.copy(name = "Renamed Suspense")
        val res1 = repository.updateLedger(renameAttempt)
        assertTrue("Renaming Suspense ledger must be rejected", res1 is AccountingResult.Failure)

        val reparentAttempt = suspenseLedger.copy(groupId = "GRP_CURRENT_LIAB_$companyA")
        val res2 = repository.updateLedger(reparentAttempt)
        assertTrue("Reparenting Suspense ledger must be rejected", res2 is AccountingResult.Failure)
    }

    @Test
    fun test8_SuspenseBalanceFlexibility_SupportsDebitBalance() = runBlocking {
        val suspenseLedgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA"
        db.accountingDao().updateLedgerBalance(companyA, suspenseLedgerId, 250000L, DrCr.DEBIT)

        val ledger = db.accountingDao().getLedgerById(companyA, suspenseLedgerId)
        assertNotNull(ledger)
        assertEquals(250000L, ledger?.currentBalancePaise)
        assertEquals(DrCr.DEBIT, ledger?.currentBalanceType)
    }

    @Test
    fun test9_SuspenseBalanceFlexibility_SupportsCreditBalance() = runBlocking {
        val suspenseLedgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA"
        db.accountingDao().updateLedgerBalance(companyA, suspenseLedgerId, 750000L, DrCr.CREDIT)

        val ledger = db.accountingDao().getLedgerById(companyA, suspenseLedgerId)
        assertNotNull(ledger)
        assertEquals(750000L, ledger?.currentBalancePaise)
        assertEquals(DrCr.CREDIT, ledger?.currentBalanceType)
    }

    @Test
    fun test10_ProfitAndLossExclusion() = runBlocking {
        val fyList = repository.getFinancialYears(companyA).first()
        val currentFY = fyList.first { it.isCurrent }

        // Post a voucher with Suspense
        val vchId = "VCH_SUSPENSE_TEST_001"
        db.accountingDao().insertVoucher(
            VoucherEntity(
                voucherId = vchId,
                companyId = companyA,
                financialYearId = currentFY.financialYearId,
                voucherNumber = "JRN-001",
                voucherType = VoucherType.JOURNAL,
                date = "2026-04-15",
                referenceNumber = "REF-001",
                narration = "Opening discrepancy absorption",
                totalAmountPaise = 500000L,
                isPosted = true,
                isCancelled = false,
                syncState = com.example.accounting.domain.accounting.SyncState.SYNCED,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                createdBy = "ADMIN",
                partyGstin = "",
                isGstApplicable = false
            )
        )
        db.accountingDao().insertJournalItems(
            listOf(
                JournalItemEntity("ITEM_1", vchId, companyA, currentFY.financialYearId, "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA", DrCr.DEBIT, 500000L, "Suspense A/c", 1),
                JournalItemEntity("ITEM_2", vchId, companyA, currentFY.financialYearId, "LED_CAPITAL_$companyA", DrCr.CREDIT, 500000L, "Capital A/c", 2)
            )
        )

        val pnl = repository.generateProfitAndLoss(companyA, currentFY.financialYearId)
        assertEquals("Suspense must not affect P&L sales revenue", 0L, pnl.salesRevenue.paise)
        assertEquals("Suspense must not affect P&L direct income", 0L, pnl.directIncomes.paise)
        assertEquals("Suspense must not affect P&L purchases", 0L, pnl.purchases.paise)
        assertEquals("Suspense must not affect P&L direct expenses", 0L, pnl.directExpenses.paise)
        assertEquals("Suspense must not affect P&L net profit", 0L, pnl.netProfit.paise)
    }

    /** JDK 21/Robolectric fix follow-up - this test's original single-method form set up its
     * fixture via a raw `updateLedgerBalance` DAO column write, which [generateBalanceSheet]'s
     * suspense figures never read (they're computed from real posted journal items via
     * [com.example.accounting.domain.reports.GroupAggregationEngine], same as every other report -
     * see test10's own real-voucher posting for the established pattern). That call was previously
     * dead code (masked entirely by the pre-fix Robolectric class-level sandbox failure); now that
     * it actually runs, split into two independent tests (fresh `db`/`repository` per [Before]) each
     * posting one real, balanced Journal voucher to Suspense - avoids reasoning about a running net
     * balance across two postings sharing one method, and keeps the original 450000/850000 amounts. */
    @Test
    fun test11a_BalanceSheetDynamicPresentation_SuspenseDebit() = runBlocking {
        val fyList = repository.getFinancialYears(companyA).first()
        val currentFY = fyList.first { it.isCurrent }
        val suspenseLedgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA"

        val vchId = "VCH_BS_SUSPENSE_DEBIT"
        db.accountingDao().insertVoucher(
            VoucherEntity(
                voucherId = vchId, companyId = companyA, financialYearId = currentFY.financialYearId,
                voucherNumber = "JRN-BS-DR", voucherType = VoucherType.JOURNAL, date = "2026-04-15",
                referenceNumber = "REF-BS-DR", narration = "Suspense debit for Balance Sheet test",
                totalAmountPaise = 450000L, isPosted = true, isCancelled = false,
                syncState = com.example.accounting.domain.accounting.SyncState.SYNCED,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                createdBy = "ADMIN", partyGstin = "", isGstApplicable = false
            )
        )
        db.accountingDao().insertJournalItems(
            listOf(
                JournalItemEntity("ITEM_BS_DR_1", vchId, companyA, currentFY.financialYearId, suspenseLedgerId, DrCr.DEBIT, 450000L, "Suspense A/c", 1),
                JournalItemEntity("ITEM_BS_DR_2", vchId, companyA, currentFY.financialYearId, "LED_CAPITAL_$companyA", DrCr.CREDIT, 450000L, "Capital A/c", 2)
            )
        )

        val bsDebit = repository.generateBalanceSheet(companyA, currentFY.financialYearId)
        assertEquals(450000L, bsDebit.suspenseDebit.paise)
        assertEquals(0L, bsDebit.suspenseCredit.paise)
    }

    @Test
    fun test11b_BalanceSheetDynamicPresentation_SuspenseCredit() = runBlocking {
        val fyList = repository.getFinancialYears(companyA).first()
        val currentFY = fyList.first { it.isCurrent }
        val suspenseLedgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA"

        val vchId = "VCH_BS_SUSPENSE_CREDIT"
        db.accountingDao().insertVoucher(
            VoucherEntity(
                voucherId = vchId, companyId = companyA, financialYearId = currentFY.financialYearId,
                voucherNumber = "JRN-BS-CR", voucherType = VoucherType.JOURNAL, date = "2026-04-15",
                referenceNumber = "REF-BS-CR", narration = "Suspense credit for Balance Sheet test",
                totalAmountPaise = 850000L, isPosted = true, isCancelled = false,
                syncState = com.example.accounting.domain.accounting.SyncState.SYNCED,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                createdBy = "ADMIN", partyGstin = "", isGstApplicable = false
            )
        )
        db.accountingDao().insertJournalItems(
            listOf(
                JournalItemEntity("ITEM_BS_CR_1", vchId, companyA, currentFY.financialYearId, "LED_CAPITAL_$companyA", DrCr.DEBIT, 850000L, "Capital A/c", 1),
                JournalItemEntity("ITEM_BS_CR_2", vchId, companyA, currentFY.financialYearId, suspenseLedgerId, DrCr.CREDIT, 850000L, "Suspense A/c", 2)
            )
        )

        val bsCredit = repository.generateBalanceSheet(companyA, currentFY.financialYearId)
        assertEquals(0L, bsCredit.suspenseDebit.paise)
        assertEquals(850000L, bsCredit.suspenseCredit.paise)
    }

    @Test
    fun test12_CompanyIsolation_SuspenseScopedPerTenant() = runBlocking {
        val groupsA = repository.getGroups(companyA).first()
        val groupsB = repository.getGroups(companyB).first()

        val suspenseGroupA = groupsA.find { it.groupId == "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyA" }
        val suspenseGroupB = groupsB.find { it.groupId == "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyB" }

        assertNotNull(suspenseGroupA)
        assertNotNull(suspenseGroupB)
        assertNotEquals(suspenseGroupA?.groupId, suspenseGroupB?.groupId)
        assertEquals(companyA, suspenseGroupA?.companyId)
        assertEquals(companyB, suspenseGroupB?.companyId)

        val ledgersA = repository.getLedgers(companyA).first()
        val ledgersB = repository.getLedgers(companyB).first()
        val suspenseLedgerA = ledgersA.find { it.ledgerId == "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA" }
        val suspenseLedgerB = ledgersB.find { it.ledgerId == "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyB" }

        assertNotNull(suspenseLedgerA)
        assertNotNull(suspenseLedgerB)
        assertNotEquals(suspenseLedgerA?.ledgerId, suspenseLedgerB?.ledgerId)
        assertEquals(companyA, suspenseLedgerA?.companyId)
        assertEquals(companyB, suspenseLedgerB?.companyId)
    }

    @Test
    fun test13_PeriodLockingAllowedWithNonZeroSuspense() = runBlocking {
        val suspenseLedgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyA"
        db.accountingDao().updateLedgerBalance(companyA, suspenseLedgerId, 100000L, DrCr.DEBIT)

        val fyList = repository.getFinancialYears(companyA).first()
        val currentFY = fyList.first { it.isCurrent }
        val periods = repository.getPeriods(companyA, currentFY.financialYearId).first()
        val periodToLock = periods.first()

        val lockResult = repository.setPeriodStatus(companyA, periodToLock.periodId, PeriodStatus.LOCKED, "SUPERVISOR")
        assertTrue("Locking period with non-zero Suspense must succeed", lockResult is AccountingResult.Success)

        val updatedPeriod = db.accountingDao().getPeriodById(companyA, periodToLock.periodId)
        assertEquals(PeriodStatus.LOCKED, updatedPeriod?.status)
    }
}
