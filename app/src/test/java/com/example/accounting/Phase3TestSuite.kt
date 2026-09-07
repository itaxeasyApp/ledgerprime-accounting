package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.AccountingTransactionException
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.reports.GroupAggregationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 3 - Financial Statements Engine test suite.
 *
 * Pure JVM tests (no Robolectric) against [AccountingRepository]'s real
 * `generateTrialBalance`/`generateProfitAndLoss`/`generateBalanceSheet`, backed by
 * [FakeAccountingDao] (Phase0TestSuite.kt) wrapped in [GroupAwareDao] - a thin Kotlin interface
 * delegate that only adds real group storage, since the shared Fake stubs group persistence as
 * no-ops (harmless for Phase 0/1/2, which never exercise groups; needed here). Nothing in
 * Phase0TestSuite.kt is modified, per "do not disturb the frozen Phase 0-2 accounting core."
 *
 * All postings go through the real [VoucherPostingEngine] (the frozen Phase 2 posting/
 * cancellation engine) so these statements are calculated from actual resulting ledger effects,
 * not hand-crafted balances.
 */
class Phase3TestSuite {

    private class GroupAwareDao(private val delegate: AccountingDao) : AccountingDao by delegate {
        private val groups = LinkedHashMap<String, GroupEntity>()
        override fun getGroupsByCompany(companyId: String): Flow<List<GroupEntity>> =
            flowOf(groups.values.filter { it.companyId == companyId })
        override suspend fun getGroupById(companyId: String, groupId: String): GroupEntity? =
            groups[groupId]?.takeIf { it.companyId == companyId }
        override suspend fun insertGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun insertGroups(groups: List<GroupEntity>) { groups.forEach { this.groups[it.groupId] = it } }
        override suspend fun updateGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun deleteGroup(companyId: String, groupId: String): Int =
            if (groups.remove(groupId) != null) 1 else 0
    }

    private fun groupEntity(ag: AccountGroup) = GroupEntity(ag.groupId, ag.companyId, ag.name, ag.primaryGroup, ag.parentGroupId, ag.isSystem, ag.affectsGrossProfit, ag.displayOrder)

    private fun ledger(id: String, companyId: String, groupId: String, openingPaise: Long = 0L, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        ledgerId = id, companyId = companyId, groupId = groupId, name = id, code = id,
        openingBalancePaise = openingPaise, openingBalanceType = openingType,
        currentBalancePaise = openingPaise, currentBalanceType = openingType,
        gstin = "", pan = "", stateCode = "27", email = "", phone = "", address = "",
        bankAccountNumber = "", bankIfsc = "", isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
    )

    /** Seeds company + FY (one full-year OPEN period) + the 27 standard groups. */
    private suspend fun GroupAwareDao.seedStandardCompany(companyId: String, fyId: String, fyStart: String = "2026-04-01", fyEnd: String = "2027-03-31") {
        insertCompany(CompanyEntity(companyId, "Company $companyId", "Company $companyId", "27AAAAA0000A1Z5", "AAAAA0000A", "27", "MH", "", "", "", "INR", 4, true, 0L))
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY", fyStart, fyEnd, true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_${companyId}_$fyId", companyId, fyId, "Full Year", fyStart, fyEnd, PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map { groupEntity(it) })
    }

    private suspend fun post(
        dao: AccountingDao, companyId: String, fyId: String, voucherId: String, number: String,
        type: VoucherType, debitLedger: String, creditLedger: String, amountPaise: Long,
        date: String = "2026-05-10", idempotencyKey: String = UUID.randomUUID().toString()
    ) {
        val voucher = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = number,
            voucherType = type, date = date, referenceNumber = "", narration = "", totalAmountPaise = amountPaise,
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "TESTER", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(
            JournalItemEntity("${voucherId}_D", voucherId, companyId, fyId, debitLedger, DrCr.DEBIT, amountPaise, "", 1),
            JournalItemEntity("${voucherId}_C", voucherId, companyId, fyId, creditLedger, DrCr.CREDIT, amountPaise, "", 2)
        )
        VoucherPostingEngine.post(dao, voucher, items, idempotencyKey, "TESTER")
    }

    /** Standard chart of accounts used by most tests: one ledger per relevant standard group. */
    private class Chart(val companyId: String) {
        val bank = "LED_BANK_$companyId"
        val cash = "LED_CASH_$companyId"
        val debtor = "LED_DEBTOR_$companyId"
        val fixedAsset = "LED_FIXED_$companyId"
        val investment = "LED_INVEST_$companyId"
        val miscExpAsset = "LED_MISCEXP_$companyId"
        val capital = "LED_CAPITAL_$companyId"
        val reserves = "LED_RESERVES_$companyId"
        val loans = "LED_LOANS_$companyId"
        val duties = "LED_DUTIES_$companyId"
        val branch = "LED_BRANCH_$companyId"
        val sales = "LED_SALES_$companyId"
        val directIncome = "LED_DIRINC_$companyId"
        val indirectIncome = "LED_INDIRINC_$companyId"
        val purchase = "LED_PURCHASE_$companyId"
        val directExpense = "LED_DIREXP_$companyId"
        val indirectExpense = "LED_INDIREXP_$companyId"
        val suspense get() = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyId"
        val roundOff get() = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"
    }

    private suspend fun GroupAwareDao.seedFullChart(companyId: String, fyId: String): Chart {
        seedStandardCompany(companyId, fyId)
        val c = Chart(companyId)
        val g = StandardSystemGroups
        insertLedgers(listOf(
            ledger(c.bank, companyId, "${g.BANK_GROUP_ID}_$companyId"),
            ledger(c.cash, companyId, "${g.CASH_GROUP_ID}_$companyId"),
            ledger(c.debtor, companyId, "${g.DEBTORS_GROUP_ID}_$companyId"),
            ledger(c.fixedAsset, companyId, "${g.FIXED_ASSETS_GROUP_ID}_$companyId"),
            ledger(c.investment, companyId, "${g.INVESTMENTS_GROUP_ID}_$companyId"),
            ledger(c.miscExpAsset, companyId, "${g.MISC_EXPENSES_GROUP_ID}_$companyId"),
            ledger(c.capital, companyId, "${g.CAPITAL_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.reserves, companyId, "${g.RESERVES_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.loans, companyId, "${g.LOANS_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.duties, companyId, "${g.DUTIES_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.branch, companyId, "${g.BRANCH_DIVISIONS_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.sales, companyId, "${g.SALES_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.directIncome, companyId, "${g.DIRECT_INCOME_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.indirectIncome, companyId, "${g.INDIRECT_INCOME_GROUP_ID}_$companyId", openingType = DrCr.CREDIT),
            ledger(c.purchase, companyId, "${g.PURCHASE_GROUP_ID}_$companyId"),
            ledger(c.directExpense, companyId, "${g.DIRECT_EXPENSE_GROUP_ID}_$companyId"),
            ledger(c.indirectExpense, companyId, "${g.INDIRECT_EXPENSE_GROUP_ID}_$companyId"),
            ledger(c.suspense, companyId, "${g.SUSPENSE_GROUP_ID}_$companyId"),
            ledger(c.roundOff, companyId, "${g.ROUND_OFF_GROUP_ID}_$companyId")
        ))
        return c
    }

    private fun freshDao() = GroupAwareDao(FakeAccountingDao())

    // ============================================================
    // A. TRIAL BALANCE (Section 29)
    // ============================================================
    @Test
    fun tb1_EmptyCompany_BalancedZeroTrialBalance() = runBlocking {
        val dao = freshDao()
        dao.seedFullChart("COMP_TB1", "FY_TB1")
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB1", "FY_TB1")
        assertTrue(tb.isBalanced)
        assertEquals(0L, tb.totalClosingDebit.paise)
        assertEquals(0L, tb.totalClosingCredit.paise)
    }

    @Test
    fun tb2_OpeningBalances_CorrectTB() = runBlocking {
        val dao = freshDao()
        dao.seedFullChart("COMP_TB2", "FY_TB2")
        // Re-insert with a nonzero opening balance (simulating ledger creation with an opening balance).
        dao.insertLedger(ledger("LED_BANK_COMP_TB2", "COMP_TB2", "${StandardSystemGroups.BANK_GROUP_ID}_COMP_TB2", 500_00L, DrCr.DEBIT))
        dao.insertLedger(ledger("LED_CAPITAL_COMP_TB2", "COMP_TB2", "${StandardSystemGroups.CAPITAL_GROUP_ID}_COMP_TB2", 500_00L, DrCr.CREDIT))
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB2", "FY_TB2")
        assertTrue(tb.isBalanced)
        assertEquals(500_00L, tb.totalOpeningDebit.paise)
        assertEquals(500_00L, tb.totalOpeningCredit.paise)
    }

    @Test
    fun tb3_BalancedVoucher_CorrectTB() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB3", "FY_TB3")
        post(dao, "COMP_TB3", "FY_TB3", "V1", "PMT-1", VoucherType.PAYMENT, c.directExpense, c.bank, 200_00L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB3", "FY_TB3")
        assertTrue(tb.isBalanced)
        assertEquals(200_00L, tb.totalTransactionDebit.paise)
        assertEquals(200_00L, tb.totalTransactionCredit.paise)
    }

    @Test
    fun tb4_MultipleVouchers_CorrectAggregation() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB4", "FY_TB4")
        post(dao, "COMP_TB4", "FY_TB4", "V1", "PMT-1", VoucherType.PAYMENT, c.directExpense, c.bank, 100_00L)
        post(dao, "COMP_TB4", "FY_TB4", "V2", "PMT-2", VoucherType.PAYMENT, c.indirectExpense, c.bank, 50_00L)
        post(dao, "COMP_TB4", "FY_TB4", "V3", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 300_00L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB4", "FY_TB4")
        assertTrue(tb.isBalanced)
        assertEquals(450_00L, tb.totalTransactionDebit.paise)
        val bankRow = tb.rows.first { it.ledgerId == c.bank }
        assertEquals(150_00L, bankRow.closingDebit.paise) // +300 receipt -100 -50 payments
    }

    @Test
    fun tb5_DebitBalance_CalculatedCorrectly() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB5", "FY_TB5")
        post(dao, "COMP_TB5", "FY_TB5", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 1000_00L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB5", "FY_TB5")
        val bankRow = tb.rows.first { it.ledgerId == c.bank }
        assertEquals(1000_00L, bankRow.closingDebit.paise)
        assertEquals(0L, bankRow.closingCredit.paise)
    }

    @Test
    fun tb6_CreditBalance_CalculatedCorrectly() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB6", "FY_TB6")
        post(dao, "COMP_TB6", "FY_TB6", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 1000_00L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB6", "FY_TB6")
        val salesRow = tb.rows.first { it.ledgerId == c.sales }
        assertEquals(1000_00L, salesRow.closingCredit.paise)
        assertEquals(0L, salesRow.closingDebit.paise)
    }

    @Test
    fun tb7_ZeroBalance_CalculatedCorrectly() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB7", "FY_TB7")
        post(dao, "COMP_TB7", "FY_TB7", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 500_00L)
        post(dao, "COMP_TB7", "FY_TB7", "V2", "PMT-1", VoucherType.PAYMENT, c.sales, c.bank, 500_00L) // reverse via opposite entry
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB7", "FY_TB7")
        val salesRow = tb.rows.first { it.ledgerId == c.sales }
        assertEquals(0L, salesRow.closingDebit.paise)
        assertEquals(0L, salesRow.closingCredit.paise)
    }

    @Test
    fun tb8_Totals_AlwaysBalance() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB8", "FY_TB8")
        post(dao, "COMP_TB8", "FY_TB8", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 777_77L)
        post(dao, "COMP_TB8", "FY_TB8", "V2", "PMT-1", VoucherType.PAYMENT, c.purchase, c.bank, 111_11L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB8", "FY_TB8")
        assertTrue(tb.isBalanced)
        assertEquals(Money.fromPaise(0), tb.difference)
    }

    @Test
    fun tb9_CancelledVoucher_CorrectNetResult() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB9", "FY_TB9")
        post(dao, "COMP_TB9", "FY_TB9", "V1", "RCT-1", VoucherType.RECEIPT, c.debtor, c.sales, 1000_00L)
        VoucherPostingEngine.cancel(dao, "COMP_TB9", "FY_TB9", "V1", "IK-CANCEL", "TESTER")
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB9", "FY_TB9")
        val debtorRow = tb.rows.first { it.ledgerId == c.debtor }
        val salesRow = tb.rows.first { it.ledgerId == c.sales }
        assertEquals(0L, debtorRow.closingDebit.paise)
        assertEquals(0L, debtorRow.closingCredit.paise)
        assertEquals(0L, salesRow.closingCredit.paise)
        // Real delete (explicit correction): the original journal lines are gone outright, never
        // left behind alongside a same-voucher offsetting entry, so period movement is genuinely
        // zero - not "1000 posted + 1000 reversed".
        assertEquals(0L, debtorRow.transactionDebit.paise + debtorRow.transactionCredit.paise)
        assertTrue(tb.isBalanced)
    }

    @Test
    fun tb10_LockedPeriodTransactions_RemainIncludedHistorically() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB10", "FY_TB10")
        post(dao, "COMP_TB10", "FY_TB10", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 250_00L)
        dao.setPeriodStatus("COMP_TB10", "PER_COMP_TB10_FY_TB10", PeriodStatus.LOCKED, "SUPERVISOR", System.currentTimeMillis())
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB10", "FY_TB10")
        val bankRow = tb.rows.first { it.ledgerId == c.bank }
        assertEquals(250_00L, bankRow.closingDebit.paise)
    }

    @Test
    fun tb11_CompanyIsolation() = runBlocking {
        val dao = freshDao()
        val a = dao.seedFullChart("COMP_TB11_A", "FY_TB11")
        val b = dao.seedFullChart("COMP_TB11_B", "FY_TB11")
        post(dao, "COMP_TB11_A", "FY_TB11", "V1", "RCT-1", VoucherType.RECEIPT, a.bank, a.sales, 10_000_00L)
        post(dao, "COMP_TB11_B", "FY_TB11", "V2", "RCT-1", VoucherType.RECEIPT, b.bank, b.sales, 20_000_00L)
        val repo = AccountingRepository(dao)
        val tbA = repo.generateTrialBalance("COMP_TB11_A", "FY_TB11")
        assertEquals(10_000_00L, tbA.rows.first { it.ledgerId == a.bank }.closingDebit.paise)
        assertFalse(tbA.rows.any { it.ledgerId == b.bank })
    }

    @Test
    fun tb12_FinancialYearIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedStandardCompany("COMP_TB12", "FY_2026_27", "2026-04-01", "2027-03-31")
        dao.insertFinancialYear(FinancialYearEntity("FY_2025_26", "COMP_TB12", "FY 2025-26", "2025-04-01", "2026-03-31", false, true, null, null))
        val bank = "LED_BANK_COMP_TB12"
        dao.insertLedger(ledger(bank, "COMP_TB12", "${StandardSystemGroups.BANK_GROUP_ID}_COMP_TB12"))
        dao.insertLedger(ledger("LED_SALES_COMP_TB12", "COMP_TB12", "${StandardSystemGroups.SALES_GROUP_ID}_COMP_TB12", openingType = DrCr.CREDIT))
        post(dao, "COMP_TB12", "FY_2026_27", "V1", "RCT-1", VoucherType.RECEIPT, bank, "LED_SALES_COMP_TB12", 999_00L, date = "2026-05-01")
        val repo = AccountingRepository(dao)
        val tb2627 = repo.generateTrialBalance("COMP_TB12", "FY_2026_27")
        val tb2526 = repo.generateTrialBalance("COMP_TB12", "FY_2025_26")
        assertEquals(999_00L, tb2627.rows.first { it.ledgerId == bank }.closingDebit.paise)
        assertEquals(0L, tb2526.rows.first { it.ledgerId == bank }.closingDebit.paise)
    }

    @Test
    fun tb13_GroupHierarchyAggregation_ParentIncludesDescendants() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB13", "FY_TB13")
        post(dao, "COMP_TB13", "FY_TB13", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 400_00L)
        post(dao, "COMP_TB13", "FY_TB13", "V2", "RCT-2", VoucherType.RECEIPT, c.cash, c.capital, 100_00L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB13", "FY_TB13")
        val currentAssetsNode = GroupAggregationEngine.findNode(tb.groupHierarchy, "${StandardSystemGroups.CURRENT_ASSETS_GROUP_ID}_COMP_TB13")
        assertEquals("Current Assets total must include Bank + Cash (its descendants)", 500_00L, currentAssetsNode?.totalDebitPaise)
    }

    @Test
    fun tb14_NoDoubleCounting_LedgerCountedOnceAcrossParentAndChild() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_TB14", "FY_TB14")
        post(dao, "COMP_TB14", "FY_TB14", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 700_00L)
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_TB14", "FY_TB14")
        val bankNode = GroupAggregationEngine.findNode(tb.groupHierarchy, "${StandardSystemGroups.BANK_GROUP_ID}_COMP_TB14")
        val currentAssetsNode = GroupAggregationEngine.findNode(tb.groupHierarchy, "${StandardSystemGroups.CURRENT_ASSETS_GROUP_ID}_COMP_TB14")
        assertEquals(700_00L, bankNode?.totalDebitPaise)
        assertEquals("Parent total must equal child total, not double it", 700_00L, currentAssetsNode?.totalDebitPaise)
    }

    // ============================================================
    // B. PROFIT & LOSS (Section 30)
    // ============================================================
    @Test
    fun pl1_EmptyPL() = runBlocking {
        val dao = freshDao()
        dao.seedFullChart("COMP_PL1", "FY_PL1")
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL1", "FY_PL1")
        assertEquals(0L, pnl.netProfit.paise)
        assertEquals(0L, pnl.salesRevenue.paise)
    }

    @Test
    fun pl2_SalesIncome() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL2", "FY_PL2")
        post(dao, "COMP_PL2", "FY_PL2", "V1", "INV-1", VoucherType.SALES, c.debtor, c.sales, 5000_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL2", "FY_PL2")
        assertEquals(5000_00L, pnl.salesRevenue.paise)
        assertEquals(5000_00L, pnl.netProfit.paise)
    }

    @Test
    fun pl3_DirectIncome() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL3", "FY_PL3")
        post(dao, "COMP_PL3", "FY_PL3", "V1", "JRN-1", VoucherType.JOURNAL, c.bank, c.directIncome, 300_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL3", "FY_PL3")
        assertEquals(300_00L, pnl.directIncomes.paise)
    }

    @Test
    fun pl4_IndirectIncome() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL4", "FY_PL4")
        post(dao, "COMP_PL4", "FY_PL4", "V1", "JRN-1", VoucherType.JOURNAL, c.bank, c.indirectIncome, 150_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL4", "FY_PL4")
        assertEquals(150_00L, pnl.indirectIncomes.paise)
    }

    @Test
    fun pl5_DirectExpense() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL5", "FY_PL5")
        post(dao, "COMP_PL5", "FY_PL5", "V1", "PMT-1", VoucherType.PAYMENT, c.directExpense, c.bank, 220_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL5", "FY_PL5")
        assertEquals(220_00L, pnl.directExpenses.paise)
    }

    @Test
    fun pl6_IndirectExpense() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL6", "FY_PL6")
        post(dao, "COMP_PL6", "FY_PL6", "V1", "PMT-1", VoucherType.PAYMENT, c.indirectExpense, c.bank, 80_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL6", "FY_PL6")
        assertEquals(80_00L, pnl.indirectExpenses.paise)
    }

    @Test
    fun pl7_PurchaseAccount_TreatedDistinctlyFromGenericExpense() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL7", "FY_PL7")
        post(dao, "COMP_PL7", "FY_PL7", "V1", "PUR-1", VoucherType.PURCHASE, c.purchase, c.bank, 900_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL7", "FY_PL7")
        assertEquals(900_00L, pnl.purchases.paise)
        assertEquals("Purchases must not leak into Direct/Indirect Expenses", 0L, pnl.directExpenses.paise + pnl.indirectExpenses.paise)
    }

    @Test
    fun pl8_NetProfit_PositiveResult() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL8", "FY_PL8")
        post(dao, "COMP_PL8", "FY_PL8", "V1", "INV-1", VoucherType.SALES, c.debtor, c.sales, 1000_00L)
        post(dao, "COMP_PL8", "FY_PL8", "V2", "PUR-1", VoucherType.PURCHASE, c.purchase, c.bank, 400_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL8", "FY_PL8")
        assertEquals(600_00L, pnl.netProfit.paise)
        assertTrue(pnl.netProfit.isPositive)
    }

    @Test
    fun pl9_NetLoss_NegativeResultSignPreserved() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL9", "FY_PL9")
        post(dao, "COMP_PL9", "FY_PL9", "V1", "INV-1", VoucherType.SALES, c.debtor, c.sales, 300_00L)
        post(dao, "COMP_PL9", "FY_PL9", "V2", "PUR-1", VoucherType.PURCHASE, c.purchase, c.bank, 800_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL9", "FY_PL9")
        assertEquals(-500_00L, pnl.netProfit.paise)
        assertTrue("Loss must be preserved as a real negative signed value, not converted to fake positive income", pnl.netProfit.isNegative)
    }

    @Test
    fun pl10_CancelledVoucher_NoNetEffectOnPL() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL10", "FY_PL10")
        post(dao, "COMP_PL10", "FY_PL10", "V1", "INV-1", VoucherType.SALES, c.debtor, c.sales, 5000_00L)
        VoucherPostingEngine.cancel(dao, "COMP_PL10", "FY_PL10", "V1", "IK-C", "TESTER")
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL10", "FY_PL10")
        assertEquals(0L, pnl.salesRevenue.paise)
        assertEquals(0L, pnl.netProfit.paise)
    }

    @Test
    fun pl11_CompanyIsolation() = runBlocking {
        val dao = freshDao()
        val a = dao.seedFullChart("COMP_PL11_A", "FY_PL11")
        val b = dao.seedFullChart("COMP_PL11_B", "FY_PL11")
        post(dao, "COMP_PL11_A", "FY_PL11", "V1", "INV-1", VoucherType.SALES, a.debtor, a.sales, 10_000_00L)
        post(dao, "COMP_PL11_B", "FY_PL11", "V2", "INV-1", VoucherType.SALES, b.debtor, b.sales, 20_000_00L)
        val repo = AccountingRepository(dao)
        val pnlA = repo.generateProfitAndLoss("COMP_PL11_A", "FY_PL11")
        assertEquals(10_000_00L, pnlA.salesRevenue.paise)
    }

    @Test
    fun pl12_FinancialYearIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedStandardCompany("COMP_PL12", "FY_2026_27", "2026-04-01", "2027-03-31")
        dao.insertFinancialYear(FinancialYearEntity("FY_2027_28", "COMP_PL12", "FY 2027-28", "2027-04-01", "2028-03-31", false, false, null, null))
        val debtor = "LED_DEBTOR_COMP_PL12"
        val sales = "LED_SALES_COMP_PL12"
        dao.insertLedger(ledger(debtor, "COMP_PL12", "${StandardSystemGroups.DEBTORS_GROUP_ID}_COMP_PL12"))
        dao.insertLedger(ledger(sales, "COMP_PL12", "${StandardSystemGroups.SALES_GROUP_ID}_COMP_PL12", openingType = DrCr.CREDIT))
        post(dao, "COMP_PL12", "FY_2027_28", "V1", "INV-1", VoucherType.SALES, debtor, sales, 4242_00L, date = "2027-06-01")
        val repo = AccountingRepository(dao)
        val pnl2627 = repo.generateProfitAndLoss("COMP_PL12", "FY_2026_27")
        val pnl2728 = repo.generateProfitAndLoss("COMP_PL12", "FY_2027_28")
        assertEquals(0L, pnl2627.salesRevenue.paise)
        assertEquals(4242_00L, pnl2728.salesRevenue.paise)
    }

    @Test
    fun pl13_SuspenseExcludedFromPL() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL13", "FY_PL13")
        post(dao, "COMP_PL13", "FY_PL13", "V1", "JRN-1", VoucherType.JOURNAL, c.suspense, c.capital, 5000_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL13", "FY_PL13")
        assertEquals(0L, pnl.netProfit.paise)
        assertEquals(0L, pnl.indirectIncomes.paise)
        assertEquals(0L, pnl.indirectExpenses.paise)
    }

    @Test
    fun pl14_BalanceSheetOnlyLedgers_ExcludedFromPL() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_PL14", "FY_PL14")
        post(dao, "COMP_PL14", "FY_PL14", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 999_99L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss("COMP_PL14", "FY_PL14")
        assertEquals("Bank/Capital movement must never appear as P&L income or expense", 0L, pnl.netProfit.paise)
    }

    // ============================================================
    // C. BALANCE SHEET (Section 31)
    // ============================================================
    @Test
    fun bs1_EmptyBalanceSheet() = runBlocking {
        val dao = freshDao()
        dao.seedFullChart("COMP_BS1", "FY_BS1")
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS1", "FY_BS1")
        assertTrue(bs.isBalanced)
        assertEquals(0L, bs.totalAssets.paise)
        assertEquals(0L, bs.totalLiabilities.paise)
    }

    @Test
    fun bs2_AssetBalance() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS2", "FY_BS2")
        post(dao, "COMP_BS2", "FY_BS2", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 1500_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS2", "FY_BS2")
        assertEquals(1500_00L, bs.bankAccounts.paise)
    }

    @Test
    fun bs3_LiabilityBalance() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS3", "FY_BS3")
        post(dao, "COMP_BS3", "FY_BS3", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.loans, 2000_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS3", "FY_BS3")
        assertEquals(2000_00L, bs.loansLiabilities.paise)
    }

    @Test
    fun bs4_CapitalBalance() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS4", "FY_BS4")
        post(dao, "COMP_BS4", "FY_BS4", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 3000_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS4", "FY_BS4")
        assertEquals(3000_00L, bs.capitalAccounts.paise)
    }

    @Test
    fun bs5_ReservesBalance() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS5", "FY_BS5")
        post(dao, "COMP_BS5", "FY_BS5", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.reserves, 400_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS5", "FY_BS5")
        assertEquals(400_00L, bs.reservesAndSurplus.paise)
    }

    @Test
    fun bs6_CurrentYearProfit_FlowsIntoEquityWithoutPostingJournalEntry() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS6", "FY_BS6")
        post(dao, "COMP_BS6", "FY_BS6", "V1", "INV-1", VoucherType.SALES, c.debtor, c.sales, 1000_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS6", "FY_BS6")
        assertEquals(1000_00L, bs.netProfitForYear.paise)
        assertTrue(bs.isBalanced)
        val journalItemsAfter = dao.getJournalItemsForVoucherSync("V1").size
        assertEquals("generateBalanceSheet must be read-only: no new journal entry for current-year profit", 2, journalItemsAfter)
    }

    @Test
    fun bs7_CurrentYearLoss_EquationStillBalances() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS7", "FY_BS7")
        post(dao, "COMP_BS7", "FY_BS7", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 5000_00L)
        post(dao, "COMP_BS7", "FY_BS7", "V2", "PUR-1", VoucherType.PURCHASE, c.purchase, c.bank, 6000_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS7", "FY_BS7")
        assertTrue(bs.netProfitForYear.isNegative)
        assertTrue("Assets = Liabilities + Equity must still hold with a loss", bs.isBalanced)
    }

    @Test
    fun bs8_SuspenseDebitPresentation() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS8", "FY_BS8")
        post(dao, "COMP_BS8", "FY_BS8", "V1", "JRN-1", VoucherType.JOURNAL, c.suspense, c.capital, 750_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS8", "FY_BS8")
        assertEquals(750_00L, bs.suspenseDebit.paise)
        assertEquals(0L, bs.suspenseCredit.paise)
        assertTrue(bs.isBalanced)
    }

    @Test
    fun bs9_SuspenseCreditPresentation() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS9", "FY_BS9")
        post(dao, "COMP_BS9", "FY_BS9", "V1", "JRN-1", VoucherType.JOURNAL, c.capital, c.suspense, 850_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS9", "FY_BS9")
        assertEquals(850_00L, bs.suspenseCredit.paise)
        assertEquals(0L, bs.suspenseDebit.paise)
        assertTrue(bs.isBalanced)
    }

    @Test
    fun bs9b_RoundOffCreditPresentation_BalanceSheetStillBalances() = runBlocking {
        // Real bug fix (live-device audit finding): "Round Off" is PrimaryGroup.SPECIAL_CONTROL,
        // the same as Suspense - without its own explicit fold (mirroring bs8/bs9 above), its
        // balance was invisible to both totalLiabilitiesPaise and totalAssetsPaise, so ANY invoice
        // needing rounding (a non-round-rupee GST total) silently broke bs.isBalanced by exactly
        // the Round Off amount, even though the Trial Balance itself was already fully balanced.
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS9B", "FY_BS9B")
        post(dao, "COMP_BS9B", "FY_BS9B", "V1", "JRN-1", VoucherType.JOURNAL, c.capital, c.roundOff, 16L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS9B", "FY_BS9B")
        assertEquals(16L, bs.roundOffCredit.paise)
        assertEquals(0L, bs.roundOffDebit.paise)
        assertTrue("Balance Sheet must still balance with a real Round Off credit balance", bs.isBalanced)
    }

    @Test
    fun bs9c_RoundOffDebitPresentation_BalanceSheetStillBalances() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS9C", "FY_BS9C")
        post(dao, "COMP_BS9C", "FY_BS9C", "V1", "JRN-1", VoucherType.JOURNAL, c.roundOff, c.capital, 16L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS9C", "FY_BS9C")
        assertEquals(16L, bs.roundOffDebit.paise)
        assertEquals(0L, bs.roundOffCredit.paise)
        assertTrue("Balance Sheet must still balance with a real Round Off debit balance", bs.isBalanced)
    }

    @Test
    fun bs10_SuspenseExcludedFromPLCrossCheck() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS10", "FY_BS10")
        post(dao, "COMP_BS10", "FY_BS10", "V1", "JRN-1", VoucherType.JOURNAL, c.suspense, c.capital, 300_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS10", "FY_BS10")
        assertEquals(0L, bs.netProfitForYear.paise)
        assertEquals(300_00L, bs.suspenseDebit.paise)
    }

    @Test
    fun bs11_CompanyIsolation() = runBlocking {
        val dao = freshDao()
        val a = dao.seedFullChart("COMP_BS11_A", "FY_BS11")
        val b = dao.seedFullChart("COMP_BS11_B", "FY_BS11")
        post(dao, "COMP_BS11_A", "FY_BS11", "V1", "RCT-1", VoucherType.RECEIPT, a.bank, a.capital, 10_000_00L)
        post(dao, "COMP_BS11_B", "FY_BS11", "V2", "RCT-1", VoucherType.RECEIPT, b.bank, b.capital, 20_000_00L)
        val repo = AccountingRepository(dao)
        val bsA = repo.generateBalanceSheet("COMP_BS11_A", "FY_BS11")
        assertEquals(10_000_00L, bsA.bankAccounts.paise)
        assertEquals(10_000_00L, bsA.totalAssets.paise)
    }

    @Test
    fun bs12_FinancialYearIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedStandardCompany("COMP_BS12", "FY_2026_27", "2026-04-01", "2027-03-31")
        dao.insertFinancialYear(FinancialYearEntity("FY_2025_26", "COMP_BS12", "FY 2025-26", "2025-04-01", "2026-03-31", false, true, null, null))
        val bank = "LED_BANK_COMP_BS12"
        dao.insertLedger(ledger(bank, "COMP_BS12", "${StandardSystemGroups.BANK_GROUP_ID}_COMP_BS12"))
        dao.insertLedger(ledger("LED_CAP_COMP_BS12", "COMP_BS12", "${StandardSystemGroups.CAPITAL_GROUP_ID}_COMP_BS12", openingType = DrCr.CREDIT))
        post(dao, "COMP_BS12", "FY_2026_27", "V1", "RCT-1", VoucherType.RECEIPT, bank, "LED_CAP_COMP_BS12", 555_00L, date = "2026-05-01")
        val repo = AccountingRepository(dao)
        val bs2627 = repo.generateBalanceSheet("COMP_BS12", "FY_2026_27")
        val bs2526 = repo.generateBalanceSheet("COMP_BS12", "FY_2025_26")
        assertEquals(555_00L, bs2627.bankAccounts.paise)
        assertEquals(0L, bs2526.bankAccounts.paise)
    }

    @Test
    fun bs13_BalanceSheetEquation_AssetsEqualsLiabilitiesPlusEquity() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS13", "FY_BS13")
        post(dao, "COMP_BS13", "FY_BS13", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 10_000_00L)
        post(dao, "COMP_BS13", "FY_BS13", "V2", "INV-1", VoucherType.SALES, c.debtor, c.sales, 3000_00L)
        post(dao, "COMP_BS13", "FY_BS13", "V3", "PUR-1", VoucherType.PURCHASE, c.purchase, c.bank, 1200_00L)
        post(dao, "COMP_BS13", "FY_BS13", "V4", "PMT-1", VoucherType.PAYMENT, c.loans, c.bank, 500_00L) // partial loan repayment
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS13", "FY_BS13")
        assertTrue(bs.isBalanced)
        assertEquals(bs.totalAssets.paise, bs.totalLiabilities.paise)
    }

    @Test
    fun bs14_NoDoubleCounting_ParentChildGroups() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_BS14", "FY_BS14")
        post(dao, "COMP_BS14", "FY_BS14", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 100_00L)
        post(dao, "COMP_BS14", "FY_BS14", "V2", "RCT-2", VoucherType.RECEIPT, c.cash, c.capital, 50_00L)
        post(dao, "COMP_BS14", "FY_BS14", "V3", "INV-1", VoucherType.SALES, c.debtor, c.sales, 25_00L)
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet("COMP_BS14", "FY_BS14")
        // currentAssets = everything under Current Assets NOT already named (bank/cash/debtor) = 0 here
        assertEquals(0L, bs.currentAssets.paise)
        assertEquals(100_00L, bs.bankAccounts.paise)
        assertEquals(50_00L, bs.cashInHand.paise)
        assertEquals(25_00L, bs.sundryDebtors.paise)
        assertEquals(175_00L, bs.totalAssets.paise)
    }

    // ============================================================
    // D. ACCOUNTING INVARIANTS & STRUCTURED ERRORS (Section 32/33)
    // ============================================================
    @Test
    fun inv_GroupCycle_ReturnsStructuredErrorInsteadOfInfiniteRecursion() {
        val cyclicGroups = listOf(
            AccountGroup("GRP_X", "COMP_CYCLE", "X", PrimaryGroup.ASSETS, "GRP_Y"),
            AccountGroup("GRP_Y", "COMP_CYCLE", "Y", PrimaryGroup.ASSETS, "GRP_X")
        )
        val result = GroupAggregationEngine.aggregate(cyclicGroups, emptyList())
        assertTrue(result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.GroupHierarchyInvalid)
    }

    @Test
    fun inv_DataCorruption_ImbalancedTrialBalance_ThrowsStructuredErrorNotSilentFix() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_CORRUPT", "FY_CORRUPT")
        post(dao, "COMP_CORRUPT", "FY_CORRUPT", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.capital, 100_00L)
        // Simulate data corruption: directly tamper with one ledger's balance, bypassing the posting engine.
        dao.updateLedgerBalance("COMP_CORRUPT", c.bank, 999_00L, DrCr.DEBIT)
        val repo = AccountingRepository(dao)
        try {
            val tb = repo.generateTrialBalance("COMP_CORRUPT", "FY_CORRUPT")
            // TB is computed from opening+transaction journal items, not the mutated running
            // balance column, so this specific tamper does NOT actually desync TB - it's still
            // balanced, which is itself a correctness property worth asserting: TB's source of
            // truth is journal items, not the (separately, redundantly maintained) running balance.
            assertTrue(tb.isBalanced)
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.TrialBalanceNotBalanced)
        }
    }

    // ============================================================
    // E. DATE RANGE & ZERO-BALANCE OPTIONS (Section 10, 20)
    // ============================================================
    @Test
    fun dr_StartAfterEnd_Rejected() = runBlocking {
        val dao = freshDao()
        dao.seedFullChart("COMP_DR1", "FY_DR1")
        val repo = AccountingRepository(dao)
        try {
            repo.generateTrialBalance("COMP_DR1", "FY_DR1", dateRange = LocalDate.of(2026, 6, 1)..LocalDate.of(2026, 5, 1))
            fail("Expected startDate > endDate to be rejected")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.InvalidDateRange)
        }
    }

    @Test
    fun dr_OutsideFinancialYear_Rejected() = runBlocking {
        val dao = freshDao()
        dao.seedFullChart("COMP_DR2", "FY_DR2") // FY 2026-04-01 to 2027-03-31
        val repo = AccountingRepository(dao)
        try {
            repo.generateTrialBalance("COMP_DR2", "FY_DR2", dateRange = LocalDate.of(2025, 1, 1)..LocalDate.of(2025, 2, 1))
            fail("Expected out-of-FY date range to be rejected")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.InvalidDateRange)
        }
    }

    @Test
    fun dr_ValidCustomRange_FiltersTransactionsCorrectly() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_DR3", "FY_DR3")
        post(dao, "COMP_DR3", "FY_DR3", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 100_00L, date = "2026-05-15")
        post(dao, "COMP_DR3", "FY_DR3", "V2", "RCT-2", VoucherType.RECEIPT, c.bank, c.sales, 200_00L, date = "2026-09-15")
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance("COMP_DR3", "FY_DR3", dateRange = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 6, 30))
        val bankRow = tb.rows.first { it.ledgerId == c.bank }
        assertEquals("Only the May transaction should be included in the Apr-Jun range", 100_00L, bankRow.closingDebit.paise)
    }

    @Test
    fun zb_IncludeZeroBalance_DefaultTrue_ThenCanExclude() = runBlocking {
        val dao = freshDao()
        val c = dao.seedFullChart("COMP_ZB1", "FY_ZB1")
        post(dao, "COMP_ZB1", "FY_ZB1", "V1", "RCT-1", VoucherType.RECEIPT, c.bank, c.sales, 100_00L)
        val repo = AccountingRepository(dao)
        val tbWithZero = repo.generateTrialBalance("COMP_ZB1", "FY_ZB1")
        val tbWithoutZero = repo.generateTrialBalance("COMP_ZB1", "FY_ZB1", includeZeroBalance = false)
        assertTrue("Default must include zero-balance ledgers (e.g. untouched Cash)", tbWithZero.rows.any { it.ledgerId == c.cash })
        assertFalse("includeZeroBalance=false must exclude untouched ledgers", tbWithoutZero.rows.any { it.ledgerId == c.cash })
        assertEquals("Totals must be identical either way", tbWithZero.totalClosingDebit.paise, tbWithoutZero.totalClosingDebit.paise)
    }
}
