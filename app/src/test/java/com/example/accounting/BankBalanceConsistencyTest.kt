package com.example.accounting

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
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.accounting.netDebitBalance
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Phase 8, Step 10 - the Bank balance must be ONE number across the layers:
 * bank ledger (stored currentBalance + side) -> journal -> Trial Balance -> group aggregation ->
 * Balance Sheet -> Home / Money (which show [netDebitBalance] of the same ledgers).
 *
 * The defect this pins: Home and Money summed [Ledger.currentBalance] - a bare magnitude whose side
 * lives in `currentBalanceType` - so an overdrawn (Credit) bank showed +110,000.00 while the Trial
 * Balance (Credit 110,000.00) and the Balance Sheet (-110,000.00) were right.
 */
class BankBalanceConsistencyTest {

    private val companyId = "COMP_BANK"
    private val fyId = "FY_BANK_2026_27"

    private fun freshDao(): AccountingDao =
        Phase5TestSuite.Phase5AwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private fun ledger(id: String, groupBare: String, openingType: DrCr = DrCr.DEBIT) =
        LedgerEntity(id, companyId, "${groupBare}_$companyId", id, id, 0L, openingType, 0L, openingType, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun seed(vararg extraBankLedgers: String): Pair<AccountingDao, AccountingRepository> {
        val dao = freshDao()
        dao.insertCompany(
            CompanyEntity(
                companyId = companyId, name = "Company $companyId", tradeName = "Company $companyId", gstin = "27AAAAA0000A1Z5",
                pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
                currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
                accountingMode = AccountingMode.ACCOUNT_ONLY, businessType = BusinessType.TRADING
            )
        )
        dao.insertFinancialYear(FinancialYearEntity(fyId, companyId, "2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        dao.insertPeriods(listOf(AccountingPeriodEntity("PER_$companyId", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        dao.insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
        dao.insertLedger(ledger("LED_CASH", StandardSystemGroups.CASH_GROUP_ID))
        dao.insertLedger(ledger("LED_BANK", StandardSystemGroups.BANK_GROUP_ID))
        extraBankLedgers.forEach { dao.insertLedger(ledger(it, StandardSystemGroups.BANK_GROUP_ID)) }
        dao.insertLedger(ledger("LED_CREDITOR", StandardSystemGroups.CREDITORS_GROUP_ID, DrCr.CREDIT))
        dao.insertLedger(ledger("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        val repo = AccountingRepository(dao)
        repo.ensureGstLedgersExist(companyId)
        return dao to repo
    }

    /** One balanced two-line voucher: Dr [debitLedger] / Cr [creditLedger]. */
    private suspend fun AccountingDao.post(id: String, type: VoucherType, debitLedger: String, creditLedger: String, paise: Long, date: String = "2026-05-10") {
        val voucher = VoucherEntity(
            voucherId = id, companyId = companyId, financialYearId = fyId, voucherNumber = id, voucherType = type, date = date,
            referenceNumber = "", narration = "Test $id", totalAmountPaise = paise, isPosted = true, isCancelled = false,
            syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = false
        )
        val items = listOf(
            JournalItemEntity("$id-1", id, companyId, fyId, debitLedger, DrCr.DEBIT, paise, "", 1),
            JournalItemEntity("$id-2", id, companyId, fyId, creditLedger, DrCr.CREDIT, paise, "", 2)
        )
        VoucherPostingEngine.post(this, voucher, items, "IK_$id", "TESTER")
    }

    private fun isBank(l: Ledger) = l.groupId.startsWith(StandardSystemGroups.BANK_GROUP_ID)
    private fun isCash(l: Ledger) = l.groupId.startsWith(StandardSystemGroups.CASH_GROUP_ID)

    /** The bank figure as every layer reports it, in paise (debit-positive). */
    private data class BankByLayer(val stored: Long, val trialBalance: Long, val balanceSheet: Long, val homeAndMoney: Long, val unsignedMagnitude: Long)

    private suspend fun bankByLayer(dao: AccountingDao, repo: AccountingRepository): BankByLayer {
        val bankLedgers = repo.getLedgers(companyId).first().filter { isBank(it) }
        val tbRows = repo.generateTrialBalance(companyId, fyId).rows.filter { it.groupId.startsWith(StandardSystemGroups.BANK_GROUP_ID) }
        return BankByLayer(
            stored = dao.getLedgersByCompany(companyId).first().filter { it.groupId.startsWith(StandardSystemGroups.BANK_GROUP_ID) }
                .sumOf { if (it.currentBalanceType == DrCr.DEBIT) it.currentBalancePaise else -it.currentBalancePaise },
            trialBalance = tbRows.sumOf { it.closingDebit.paise - it.closingCredit.paise },
            balanceSheet = repo.generateBalanceSheet(companyId, fyId).bankAccounts.paise,
            homeAndMoney = bankLedgers.netDebitBalance().paise,
            unsignedMagnitude = bankLedgers.fold(Money.ZERO) { acc, l -> acc + l.currentBalance }.paise
        )
    }

    // ---------------------------------------------------------------- the device scenario

    @Test
    fun overdrawnBank_isTheSameNegativeNumber_inTheLedger_TrialBalance_BalanceSheet_andHomeAndMoney() = runBlocking {
        val (dao, repo) = seed()
        dao.post("V_SALE", VoucherType.RECEIPT, "LED_CASH", "LED_SALES", 119_000_00L)          // cash in hand 119,000.00
        dao.post("V_PAY", VoucherType.PAYMENT, "LED_CREDITOR", "LED_BANK", 110_000_00L)        // paid from the bank with no deposits

        val b = bankByLayer(dao, repo)
        assertEquals("stored ledger: Credit 110,000.00", -110_000_00L, b.stored)
        assertEquals("Trial Balance (journal-derived): Credit 110,000.00", -110_000_00L, b.trialBalance)
        assertEquals("Balance Sheet: -110,000.00", -110_000_00L, b.balanceSheet)
        assertEquals("Home / Money must show the same -110,000.00", -110_000_00L, b.homeAndMoney)
        assertEquals("the bare magnitude alone is +110,000.00 - the defect", 110_000_00L, b.unsignedMagnitude)
        assertNotEquals("the sign is what the old Home tile lost", b.unsignedMagnitude, b.homeAndMoney)
    }

    @Test
    fun theTrialBalanceStaysBalanced_andTheBalanceSheetBalances_withAnOverdrawnBank() = runBlocking {
        val (dao, repo) = seed()
        dao.post("V_SALE", VoucherType.RECEIPT, "LED_CASH", "LED_SALES", 119_000_00L)
        dao.post("V_PAY", VoucherType.PAYMENT, "LED_CREDITOR", "LED_BANK", 110_000_00L)
        val tb = repo.generateTrialBalance(companyId, fyId)
        assertEquals(tb.totalClosingDebit.paise, tb.totalClosingCredit.paise)
        val bs = repo.generateBalanceSheet(companyId, fyId)
        assertEquals("the accounting is sound: only the Home presentation was wrong", bs.totalAssets.paise, bs.totalLiabilities.paise)
    }

    @Test
    fun cashAndBankAreEachConsistent_whenBothAreOnTheirNaturalSide_andWhenBankIsInCredit() = runBlocking {
        val (dao, repo) = seed()
        dao.post("V_SALE", VoucherType.RECEIPT, "LED_CASH", "LED_SALES", 119_000_00L)
        val cash = repo.getLedgers(companyId).first().filter { isCash(it) }.netDebitBalance().paise
        assertEquals(119_000_00L, cash)
        assertEquals("Balance Sheet cash equals the Home cash tile", repo.generateBalanceSheet(companyId, fyId).cashInHand.paise, cash)
    }

    // ---------------------------------------------------------------- other shapes

    @Test
    fun aBankInDebit_isPositiveEverywhere() = runBlocking {
        val (dao, repo) = seed()
        dao.post("V_DEP", VoucherType.RECEIPT, "LED_BANK", "LED_SALES", 50_000_00L)
        val b = bankByLayer(dao, repo)
        listOf(b.stored, b.trialBalance, b.balanceSheet, b.homeAndMoney).forEach { assertEquals(50_000_00L, it) }
        assertEquals("for a debit bank the magnitude and the signed value agree", b.unsignedMagnitude, b.homeAndMoney)
    }

    @Test
    fun severalBankLedgers_sumTheirSignedBalances_notTheirMagnitudes() = runBlocking {
        val (dao, repo) = seed("LED_BANK2")
        dao.post("V_DEP", VoucherType.RECEIPT, "LED_BANK", "LED_SALES", 50_000_00L)          // Bank 1: Dr 50,000
        dao.post("V_PAY", VoucherType.PAYMENT, "LED_CREDITOR", "LED_BANK2", 160_000_00L)     // Bank 2: Cr 160,000
        val b = bankByLayer(dao, repo)
        assertEquals("50,000 Dr + 160,000 Cr = 110,000 Cr in total", -110_000_00L, b.balanceSheet)
        assertEquals(b.balanceSheet, b.homeAndMoney)
        assertEquals(b.balanceSheet, b.trialBalance)
        assertEquals("the old magnitude sum would have said +210,000.00", 210_000_00L, b.unsignedMagnitude)
    }

    @Test
    fun aSettledBank_isZeroEverywhere() = runBlocking {
        val (dao, repo) = seed()
        dao.post("V_PAY", VoucherType.PAYMENT, "LED_CREDITOR", "LED_BANK", 30_000_00L)
        dao.post("V_DEP", VoucherType.RECEIPT, "LED_BANK", "LED_SALES", 30_000_00L)
        val b = bankByLayer(dao, repo)
        listOf(b.stored, b.trialBalance, b.balanceSheet, b.homeAndMoney).forEach { assertEquals(0L, it) }
    }

    // ---------------------------------------------------------------- the rule itself

    @Test
    fun ledgerDebitMinusCredit_keepsTheSide_andNetDebitBalanceSumsIt() {
        fun l(paise: Long, type: DrCr) = Ledger(ledgerId = "L", companyId = "C", groupId = "G", name = "L", currentBalance = Money.fromPaise(paise), currentBalanceType = type)
        assertEquals(100_00L, l(100_00L, DrCr.DEBIT).debitMinusCredit().paise)
        assertEquals(-100_00L, l(100_00L, DrCr.CREDIT).debitMinusCredit().paise)
        assertEquals(0L, l(0L, DrCr.CREDIT).debitMinusCredit().paise)
        assertEquals(40_00L, listOf(l(100_00L, DrCr.DEBIT), l(60_00L, DrCr.CREDIT)).netDebitBalance().paise)
        assertEquals(0L, emptyList<Ledger>().netDebitBalance().paise)
    }
}
