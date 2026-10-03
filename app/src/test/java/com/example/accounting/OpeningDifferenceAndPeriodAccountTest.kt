package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8, P2-1..P2-4: an opening difference is SHOWN (not thrown, not Suspense); stock opening is no longer
 * auto-adjusted into Suspense; Income/Expense ledgers carry no opening balance; the Ledger Statement is FY-scoped.
 */
class OpeningDifferenceAndPeriodAccountTest {

    private val c = "COMP_OD"
    private val fy1 = "FY_OD_1"
    private val fy2 = "FY_OD_2"

    private fun freshDao() = Phase5TestSuite.Phase5AwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private fun led(id: String, groupBare: String, opening: Long = 0L, type: DrCr = DrCr.DEBIT) =
        LedgerEntity(id, c, "${groupBare}_$c", id, id, opening, type, opening, type, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun AccountingDao.seed(mode: AccountingMode = AccountingMode.ACCOUNT_ONLY) {
        insertCompany(CompanyEntity(
            companyId = c, name = "Company", tradeName = "Company", gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A",
            stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "", currency = "INR",
            financialYearStartMonth = 4, isDefault = true, createdAt = 0L, accountingMode = mode, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fy1, c, "FY 2026-27", "2026-04-01", "2027-03-31", false, false, null, null))
        insertFinancialYear(FinancialYearEntity(fy2, c, "FY 2027-28", "2027-04-01", "2028-03-31", true, false, null, null))
        insertPeriods(listOf(
            AccountingPeriodEntity("PER_1", c, fy1, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null),
            AccountingPeriodEntity("PER_2", c, fy2, "Full Year", "2027-04-01", "2028-03-31", PeriodStatus.OPEN, null, null)
        ))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(c).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
        insertLedger(led("LED_BANK", StandardSystemGroups.BANK_GROUP_ID))
        insertLedger(led("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, type = DrCr.CREDIT))
    }

    private suspend fun AccountingDao.post(fyId: String, id: String, date: String, dr: String, cr: String, amount: Long) {
        val v = VoucherEntity(
            voucherId = id, companyId = c, financialYearId = fyId, voucherNumber = id, voucherType = VoucherType.JOURNAL,
            date = date, referenceNumber = "", narration = "", totalAmountPaise = amount, isPosted = true, isCancelled = false,
            syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = false
        )
        VoucherPostingEngine.post(this, v, listOf(
            JournalItemEntity("${id}_D", id, c, fyId, dr, DrCr.DEBIT, amount, "", 1),
            JournalItemEntity("${id}_C", id, c, fyId, cr, DrCr.CREDIT, amount, "", 2)
        ), "IK_$id", "TESTER")
    }

    private fun domainLedger(id: String, groupBare: String, opening: Long) = Ledger(
        ledgerId = id, companyId = c, groupId = "${groupBare}_$c", name = id, openingBalance = Money.fromPaise(opening), openingBalanceType = DrCr.DEBIT
    )

    // ---------------- P2-1 ----------------

    @Test fun oneSidedDebitOpening_balanceSheetShowsTheDifference_insteadOfThrowing() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID, 250_00L, DrCr.DEBIT))
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet(c, fy1)
        assertEquals("shown on the liabilities side", 250_00L, bs.openingDifferenceCredit.paise)
        assertEquals(0L, bs.openingDifferenceDebit.paise)
        assertEquals("never Suspense", 0L, bs.suspenseCredit.paise + bs.suspenseDebit.paise)
        assertEquals(250_00L, bs.sundryDebtors.paise)
        assertTrue(bs.isBalanced)
        assertTrue("the Trial Balance still reports the same difference", !repo.generateTrialBalance(c, fy1).isBalanced)
        assertEquals(250_00L, repo.generateTrialBalance(c, fy1).difference.paise)
    }

    @Test fun oneSidedCreditOpening_isShownOnTheAssetsSide() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_CREDITOR", StandardSystemGroups.CREDITORS_GROUP_ID, 400_00L, DrCr.CREDIT))
        val bs = AccountingRepository(dao).generateBalanceSheet(c, fy1)
        assertEquals(400_00L, bs.openingDifferenceDebit.paise); assertEquals(0L, bs.openingDifferenceCredit.paise)
        assertTrue(bs.isBalanced)
    }

    @Test fun balancedOpenings_showNoDifference() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID, 250_00L, DrCr.DEBIT))
        dao.insertLedger(led("LED_CAP", StandardSystemGroups.CAPITAL_GROUP_ID, 250_00L, DrCr.CREDIT))
        val bs = AccountingRepository(dao).generateBalanceSheet(c, fy1)
        assertEquals(0L, bs.openingDifferenceCredit.paise + bs.openingDifferenceDebit.paise)
        assertTrue(bs.isBalanced)
    }

    @Test fun openingDifference_staysCorrectAfterPostingsAndInTheNextYear() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID, 250_00L, DrCr.DEBIT))
        dao.post(fy1, "S1", "2026-05-01", "LED_BANK", "LED_SALES", 1000_00L)
        val repo = AccountingRepository(dao)
        assertEquals(250_00L, repo.generateBalanceSheet(c, fy1).openingDifferenceCredit.paise)
        val bs2 = repo.generateBalanceSheet(c, fy2)
        assertEquals(250_00L, bs2.openingDifferenceCredit.paise)
        assertTrue(bs2.isBalanced)
    }

    // ---------------- P2-2 ----------------

    @Test fun stockOpeningValue_isShownAsTheOpeningDifference_notAsSuspense() = runBlocking {
        val dao = freshDao(); dao.seed(AccountingMode.ACCOUNT_WITH_INVENTORY)
        dao.insertStockItem(StockItemEntity(
            itemId = "ITEM", companyId = c, name = "Item", sku = "I", hsnCode = "8471", unit = "Pcs", gstRatePercent = 18.0,
            openingQuantity = 10_000L, openingRatePaise = 100_00L, currentQuantity = 10_000L,
            standardCostPaise = 100_00L, standardSellingPricePaise = 100_00L, currentAvgCostPaise = 100_00L
        ))
        val bs = AccountingRepository(dao).generateBalanceSheet(c, fy1)
        assertEquals(1000_00L, bs.stockInHand.paise)
        assertEquals(0L, bs.suspenseCredit.paise)
        assertEquals(1000_00L, bs.openingDifferenceCredit.paise)
        assertTrue(bs.isBalanced)
    }

    @Test fun stockOpeningValue_isCoveredByAnEnteredCapitalOpening_leavingNoDifference() = runBlocking {
        val dao = freshDao(); dao.seed(AccountingMode.ACCOUNT_WITH_INVENTORY)
        dao.insertStockItem(StockItemEntity(
            itemId = "ITEM", companyId = c, name = "Item", sku = "I", hsnCode = "8471", unit = "Pcs", gstRatePercent = 18.0,
            openingQuantity = 10_000L, openingRatePaise = 100_00L, currentQuantity = 10_000L,
            standardCostPaise = 100_00L, standardSellingPricePaise = 100_00L, currentAvgCostPaise = 100_00L
        ))
        dao.insertLedger(led("LED_CAP", StandardSystemGroups.CAPITAL_GROUP_ID, 1000_00L, DrCr.CREDIT))
        val bs = AccountingRepository(dao).generateBalanceSheet(c, fy1)
        assertEquals(0L, bs.openingDifferenceCredit.paise + bs.openingDifferenceDebit.paise)
        assertEquals(1000_00L, bs.capitalAccounts.paise)
        assertTrue(bs.isBalanced)
    }

    @Test fun aRealSuspenseBalance_isStillShownAsSuspense() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_SUS", StandardSystemGroups.SUSPENSE_GROUP_ID))
        dao.insertLedger(led("LED_CAP", StandardSystemGroups.CAPITAL_GROUP_ID, type = DrCr.CREDIT))
        dao.post(fy1, "J1", "2026-05-01", "LED_SUS", "LED_CAP", 300_00L)
        val bs = AccountingRepository(dao).generateBalanceSheet(c, fy1)
        assertEquals(300_00L, bs.suspenseDebit.paise)
        assertEquals(0L, bs.openingDifferenceCredit.paise + bs.openingDifferenceDebit.paise)
    }

    // ---------------- P2-3 ----------------

    @Test fun createLedger_underExpenseOrIncome_withOpening_isRejected_andNothingIsSaved() = runBlocking {
        val dao = freshDao(); dao.seed()
        val repo = AccountingRepository(dao)
        val exp = repo.createLedger(domainLedger("LED_RENT", StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID, 1000_00L))
        val inc = repo.createLedger(domainLedger("LED_FEES", StandardSystemGroups.INDIRECT_INCOME_GROUP_ID, 1000_00L))
        assertTrue(exp is AccountingResult.Failure); assertTrue(inc is AccountingResult.Failure)
        assertEquals(null, dao.getLedgerById(c, "LED_RENT")); assertEquals(null, dao.getLedgerById(c, "LED_FEES"))
    }

    @Test fun createLedger_underExpense_withZeroOpening_andUnderBalanceSheetGroup_withOpening_succeed() = runBlocking {
        val dao = freshDao(); dao.seed()
        val repo = AccountingRepository(dao)
        assertTrue(repo.createLedger(domainLedger("LED_RENT", StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID, 0L)) is AccountingResult.Success)
        assertTrue(repo.createLedger(domainLedger("LED_PROV", StandardSystemGroups.PROVISIONS_GROUP_ID, 500_00L)) is AccountingResult.Success)
        assertEquals(500_00L, dao.getLedgerById(c, "LED_PROV")!!.openingBalancePaise)
    }

    @Test fun updateLedger_givingAnExpenseLedgerAnOpening_isRejected_andNothingChanges() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_RENT", StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID))
        val before = dao.getLedgerById(c, "LED_RENT")
        val r = AccountingRepository(dao).updateLedger(domainLedger("LED_RENT", StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID, 700_00L).copy(name = "Renamed"))
        assertTrue(r is AccountingResult.Failure)
        assertEquals(before, dao.getLedgerById(c, "LED_RENT"))
    }

    @Test fun anExistingExpenseLedgerWithAStoredOpening_isIgnoredByTheReports_soTheBalanceSheetStillBalances() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_RENT", StandardSystemGroups.INDIRECT_EXPENSE_GROUP_ID, 1000_00L, DrCr.DEBIT))
        dao.insertLedger(led("LED_CAP", StandardSystemGroups.CAPITAL_GROUP_ID, 1000_00L, DrCr.CREDIT))
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance(c, fy1)
        assertEquals("period account: no opening", 0L, tb.rows.first { it.ledgerId == "LED_RENT" }.openingDebit.paise)
        // the Capital opening is now an unmatched entered opening and is shown, not thrown
        val bs = repo.generateBalanceSheet(c, fy1)
        assertEquals(1000_00L, bs.openingDifferenceDebit.paise)
        assertTrue(bs.isBalanced)
    }

    // ---------------- P2-4 ----------------

    @Test fun ledgerStatement_isScopedToTheFinancialYear_forABalanceSheetLedger() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.post(fy1, "S1", "2026-05-01", "LED_BANK", "LED_SALES", 1000_00L)
        dao.post(fy2, "S2", "2027-05-01", "LED_BANK", "LED_SALES", 400_00L)
        val repo = AccountingRepository(dao)
        val s2 = repo.generateLedgerStatement(c, "LED_BANK", fy2)
        assertEquals("FY2 opening = FY1 closing", 1000_00L, s2.openingBalance.paise)
        assertEquals(DrCr.DEBIT, s2.openingType)
        assertEquals(1, s2.rows.size)
        assertEquals(400_00L, s2.totalDebit.paise)
        assertEquals(1400_00L, s2.closingBalance.paise)
        val s1 = repo.generateLedgerStatement(c, "LED_BANK", fy1)
        assertEquals(0L, s1.openingBalance.paise); assertEquals(1, s1.rows.size); assertEquals(1000_00L, s1.closingBalance.paise)
    }

    @Test fun ledgerStatement_forAnIncomeLedger_inFy2_opensAtZero_andShowsOnlyFy2Rows_matchingTheTrialBalance() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.post(fy1, "S1", "2026-05-01", "LED_BANK", "LED_SALES", 1000_00L)
        dao.post(fy2, "S2", "2027-05-01", "LED_BANK", "LED_SALES", 400_00L)
        val repo = AccountingRepository(dao)
        val st = repo.generateLedgerStatement(c, "LED_SALES", fy2)
        assertEquals(0L, st.openingBalance.paise)
        assertEquals(1, st.rows.size)
        assertEquals(400_00L, st.closingBalance.paise); assertEquals(DrCr.CREDIT, st.closingType)
        val tbRow = repo.generateTrialBalance(c, fy2).rows.first { it.ledgerId == "LED_SALES" }
        assertEquals(tbRow.closingCredit.paise, st.closingBalance.paise)
    }

    @Test fun ledgerStatement_closingMatchesTheTrialBalance_forEveryLedger_inEveryYear() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.insertLedger(led("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID, 250_00L, DrCr.DEBIT))
        dao.post(fy1, "S1", "2026-05-01", "LED_BANK", "LED_SALES", 1000_00L)
        dao.post(fy2, "S2", "2027-05-01", "LED_BANK", "LED_SALES", 400_00L)
        val repo = AccountingRepository(dao)
        for (fy in listOf(fy1, fy2)) {
            val tb = repo.generateTrialBalance(c, fy)
            for (row in tb.rows) {
                val st = repo.generateLedgerStatement(c, row.ledgerId, fy)
                val tbSigned = row.closingDebit.paise - row.closingCredit.paise
                val stSigned = if (st.closingType == DrCr.DEBIT) st.closingBalance.paise else -st.closingBalance.paise
                assertEquals("${row.ledgerId} in $fy", tbSigned, stSigned)
            }
        }
    }

    @Test fun ledgerStatement_withoutAFinancialYear_keepsTheLifetimeBehaviour() = runBlocking {
        val dao = freshDao(); dao.seed()
        dao.post(fy1, "S1", "2026-05-01", "LED_BANK", "LED_SALES", 1000_00L)
        dao.post(fy2, "S2", "2027-05-01", "LED_BANK", "LED_SALES", 400_00L)
        val st = AccountingRepository(dao).generateLedgerStatement(c, "LED_BANK")
        assertEquals(2, st.rows.size); assertEquals(1400_00L, st.closingBalance.paise)
    }
}
