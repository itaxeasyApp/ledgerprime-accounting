package com.example.accounting

import com.example.accounting.core.common.DrCr
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
 * Phase 8, P0-1 - the previous financial years' profit/loss must reach the next year's opening position
 * (Reserves & Surplus on the Balance Sheet), so FY2+ opening/closing Trial Balance and the Balance Sheet balance
 * after a profitable or loss-making FY1. Income/Expense stay period-only; nothing is stored, posted or sent to Suspense.
 */
class PriorYearResultCarryForwardTest {

    private val company = "COMP_PY"
    private val fy1 = "FY_PY_1"
    private val fy2 = "FY_PY_2"

    private fun freshDao() = Phase5TestSuite.Phase5AwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private fun ledger(id: String, comp: String, groupBare: String, opening: Long = 0L, type: DrCr = DrCr.DEBIT) =
        LedgerEntity(id, comp, "${groupBare}_$comp", id, id, opening, type, opening, type, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun AccountingDao.seed(comp: String, f1: String, f2: String) {
        insertCompany(CompanyEntity(
            companyId = comp, name = "Company $comp", tradeName = "Company $comp", gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A",
            stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "", currency = "INR",
            financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(f1, comp, "FY 2026-27", "2026-04-01", "2027-03-31", false, false, null, null))
        insertFinancialYear(FinancialYearEntity(f2, comp, "FY 2027-28", "2027-04-01", "2028-03-31", true, false, null, null))
        insertPeriods(listOf(
            AccountingPeriodEntity("PER_${comp}_1", comp, f1, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null),
            AccountingPeriodEntity("PER_${comp}_2", comp, f2, "Full Year", "2027-04-01", "2028-03-31", PeriodStatus.OPEN, null, null)
        ))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(comp).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
        insertLedger(ledger("LED_BANK_$comp", comp, StandardSystemGroups.BANK_GROUP_ID))
        insertLedger(ledger("LED_SALES_$comp", comp, StandardSystemGroups.SALES_GROUP_ID, type = DrCr.CREDIT))
        insertLedger(ledger("LED_PURCH_$comp", comp, StandardSystemGroups.PURCHASE_GROUP_ID))
        insertLedger(ledger("LED_CAP_$comp", comp, StandardSystemGroups.CAPITAL_GROUP_ID, type = DrCr.CREDIT))
        insertLedger(ledger("LED_RO_$comp", comp, StandardSystemGroups.ROUND_OFF_GROUP_ID))
    }

    private suspend fun AccountingDao.post(comp: String, fyId: String, id: String, date: String, vararg lines: Triple<String, DrCr, Long>) {
        val v = VoucherEntity(
            voucherId = id, companyId = comp, financialYearId = fyId, voucherNumber = id, voucherType = VoucherType.JOURNAL,
            date = date, referenceNumber = "", narration = "", totalAmountPaise = lines.filter { it.second == DrCr.DEBIT }.sumOf { it.third },
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "TESTER", partyGstin = "", isGstApplicable = false, referenceVoucherId = null, paymentMode = ""
        )
        val items = lines.mapIndexed { i, l -> JournalItemEntity("${id}_$i", id, comp, fyId, l.first, l.second, l.third, "", i + 1) }
        VoucherPostingEngine.post(this, v, items, "IK_$id", "TESTER")
    }

    private fun bank(c: String = company) = "LED_BANK_$c"
    private fun sales(c: String = company) = "LED_SALES_$c"
    private fun purch(c: String = company) = "LED_PURCH_$c"

    private suspend fun AccountingDao.profitableFy1(c: String = company, f1: String = fy1) {
        post(c, f1, "S1_$c", "2026-05-01", Triple(bank(c), DrCr.DEBIT, 1000_00L), Triple(sales(c), DrCr.CREDIT, 1000_00L))
        post(c, f1, "P1_$c", "2026-06-01", Triple(purch(c), DrCr.DEBIT, 300_00L), Triple(bank(c), DrCr.CREDIT, 300_00L))
    }

    @Test fun fy2_afterProfitableFy1_trialBalanceBalances_andCarriesTheResultAsACredit() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2); dao.profitableFy1()
        val tb = AccountingRepository(dao).generateTrialBalance(company, fy2)
        assertEquals(700_00L, tb.priorYearsResultCredit.paise); assertEquals(0L, tb.priorYearsResultDebit.paise)
        assertEquals("opening totals must balance", tb.totalOpeningDebit.paise, tb.totalOpeningCredit.paise)
        assertTrue("closing totals must balance", tb.isBalanced)
        assertEquals(700_00L, tb.rows.first { it.ledgerId == bank() }.openingDebit.paise)
        assertEquals("Income/Expense stay period-only", 0L, tb.rows.first { it.ledgerId == sales() }.openingCredit.paise)
    }

    @Test fun fy2_afterProfitableFy1_balanceSheetBalances_withTheResultInReserves() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2); dao.profitableFy1()
        val bs = AccountingRepository(dao).generateBalanceSheet(company, fy2)
        assertTrue(bs.isBalanced)
        assertEquals(700_00L, bs.reservesAndSurplus.paise)
        assertEquals("FY1's profit is not FY2's profit", 0L, bs.netProfitForYear.paise)
        assertEquals(700_00L, bs.totalAssets.paise)
    }

    @Test fun fy2_afterLossMakingFy1_balanceSheetBalances_withTheLossInReserves() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2)
        dao.post(company, fy1, "C1", "2026-04-02", Triple(bank(), DrCr.DEBIT, 5000_00L), Triple("LED_CAP_$company", DrCr.CREDIT, 5000_00L))
        dao.post(company, fy1, "P1", "2026-05-01", Triple(purch(), DrCr.DEBIT, 800_00L), Triple(bank(), DrCr.CREDIT, 800_00L))
        dao.post(company, fy1, "S1", "2026-06-01", Triple(bank(), DrCr.DEBIT, 300_00L), Triple(sales(), DrCr.CREDIT, 300_00L))
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance(company, fy2)
        assertEquals(500_00L, tb.priorYearsResultDebit.paise)
        assertTrue(tb.isBalanced)
        val bs = repo.generateBalanceSheet(company, fy2)
        assertTrue(bs.isBalanced)
        assertEquals(-500_00L, bs.reservesAndSurplus.paise)
        assertEquals(5000_00L, bs.capitalAccounts.paise)
    }

    @Test fun fy2_withItsOwnActivity_balances_andKeepsFy1ResultSeparateFromFy2Profit() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2); dao.profitableFy1()
        dao.post(company, fy2, "S2", "2027-05-01", Triple(bank(), DrCr.DEBIT, 400_00L), Triple(sales(), DrCr.CREDIT, 400_00L))
        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet(company, fy2)
        assertTrue(bs.isBalanced)
        assertEquals(700_00L, bs.reservesAndSurplus.paise)
        assertEquals(400_00L, bs.netProfitForYear.paise)
        assertEquals(1100_00L, bs.totalAssets.paise)
        assertEquals(400_00L, repo.generateProfitAndLoss(company, fy2).netProfit.paise)
    }

    @Test fun firstFinancialYear_isUnchanged_noCarriedResult() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2); dao.profitableFy1()
        val repo = AccountingRepository(dao)
        val tb = repo.generateTrialBalance(company, fy1)
        assertEquals(0L, tb.priorYearsResultCredit.paise); assertEquals(0L, tb.priorYearsResultDebit.paise)
        assertTrue(tb.isBalanced)
        val bs = repo.generateBalanceSheet(company, fy1)
        assertTrue(bs.isBalanced); assertEquals(700_00L, bs.netProfitForYear.paise); assertEquals(0L, bs.reservesAndSurplus.paise)
    }

    @Test fun roundOffPostedInFy1_isCarriedLikeABalanceSheetPosition_soFy2Balances() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2)
        dao.post(company, fy1, "S1", "2026-05-01",
            Triple(bank(), DrCr.DEBIT, 1000_00L), Triple(sales(), DrCr.CREDIT, 999_60L), Triple("LED_RO_$company", DrCr.CREDIT, 40L))
        val repo = AccountingRepository(dao)
        assertTrue(repo.generateTrialBalance(company, fy2).isBalanced)
        assertTrue(repo.generateBalanceSheet(company, fy2).isBalanced)
    }

    @Test fun anotherCompanysFy1Result_neverLeaksIntoThisCompanysFy2() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2)
        dao.seed("COMP_OTHER", "FY_O_1", "FY_O_2"); dao.profitableFy1("COMP_OTHER", "FY_O_1")
        val tb = AccountingRepository(dao).generateTrialBalance(company, fy2)
        assertEquals(0L, tb.priorYearsResultCredit.paise); assertEquals(0L, tb.priorYearsResultDebit.paise)
        assertTrue(tb.isBalanced)
    }

    @Test fun enteredOpeningDifference_isStillShown_notAbsorbedByTheCarriedResult() = runBlocking {
        val dao = freshDao(); dao.seed(company, fy1, fy2); dao.profitableFy1()
        dao.insertLedger(ledger("LED_DEBTOR_X", company, StandardSystemGroups.DEBTORS_GROUP_ID, opening = 250_00L))
        val tb = AccountingRepository(dao).generateTrialBalance(company, fy2)
        assertTrue("a one-sided entered opening must still show as a difference", !tb.isBalanced)
        assertEquals(250_00L, tb.difference.paise)
    }
}
