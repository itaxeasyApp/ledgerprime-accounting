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
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.engine.StockValuationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 8, P0-2 - stock carries across financial years: FY2's opening stock is FY1's closing stock (the item's
 * original opening plus every prior-year movement), so FY2 COGS, Stock-in-Hand and the Balance Sheet are right.
 */
class PriorYearStockCarryForwardTest {

    private val company = "COMP_PS"
    private val fy1 = "FY_PS_1"
    private val fy2 = "FY_PS_2"

    private fun freshDao() = Phase5TestSuite.Phase5AwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private fun ledger(id: String, groupBare: String, type: DrCr = DrCr.DEBIT) =
        LedgerEntity(id, company, "${groupBare}_$company", id, id, 0L, type, 0L, type, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun AccountingDao.seed(openingQty: Long, openingRate: Long) {
        insertCompany(CompanyEntity(
            companyId = company, name = "Company", tradeName = "Company", gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A",
            stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "", currency = "INR",
            financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(fy1, company, "FY 2026-27", "2026-04-01", "2027-03-31", false, false, null, null))
        insertFinancialYear(FinancialYearEntity(fy2, company, "FY 2027-28", "2027-04-01", "2028-03-31", true, false, null, null))
        insertPeriods(listOf(
            AccountingPeriodEntity("PER_1", company, fy1, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null),
            AccountingPeriodEntity("PER_2", company, fy2, "Full Year", "2027-04-01", "2028-03-31", PeriodStatus.OPEN, null, null)
        ))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(company).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
        insertLedger(ledger("LED_BANK", StandardSystemGroups.BANK_GROUP_ID))
        insertLedger(ledger("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger("LED_PURCH", StandardSystemGroups.PURCHASE_GROUP_ID))
        insertStockItem(StockItemEntity(
            itemId = "ITEM", companyId = company, name = "Item", sku = "ITEM", hsnCode = "8471", unit = "Pcs", gstRatePercent = 18.0,
            openingQuantity = openingQty, openingRatePaise = openingRate, currentQuantity = openingQty,
            standardCostPaise = openingRate, standardSellingPricePaise = openingRate, currentAvgCostPaise = openingRate
        ))
    }

    private suspend fun AccountingDao.trade(fyId: String, id: String, date: String, type: VoucherType, dr: String, cr: String, amount: Long, dir: StockDirection, qty: Long, rate: Long) {
        val v = VoucherEntity(
            voucherId = id, companyId = company, financialYearId = fyId, voucherNumber = id, voucherType = type,
            date = date, referenceNumber = "", narration = "", totalAmountPaise = 0L, isPosted = true, isCancelled = false,
            syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = false
        )
        val journal = listOf(
            JournalItemEntity("${id}_D", id, company, fyId, dr, DrCr.DEBIT, amount, "", 1),
            JournalItemEntity("${id}_C", id, company, fyId, cr, DrCr.CREDIT, amount, "", 2)
        )
        val line = VoucherStockLineEntity(
            lineId = UUID.randomUUID().toString(), voucherId = id, companyId = company, financialYearId = fyId, itemId = "ITEM",
            direction = dir, quantityRaw = qty * 1000L, ratePaise = rate, amountPaise = StockValuationEngine.amountFor(qty * 1000L, rate), lineOrder = 1
        )
        VoucherPostingEngine.post(this, v, journal, "IK_$id", "TESTER", listOf(line))
    }

    /** Opening 10 @ 100 (1,000). FY1: buy 10 @ 100 (1,000), sell 5 for 1,500 (cost 500) -> closing 15 @ 100 = 1,500; profit 1,000. */
    private suspend fun AccountingDao.fy1Trading() {
        seed(10_000L, 100_00L)
        trade(fy1, "P1", "2026-05-01", VoucherType.PURCHASE, "LED_PURCH", "LED_BANK", 1000_00L, StockDirection.IN, 10, 100_00L)
        trade(fy1, "S1", "2026-06-01", VoucherType.SALES, "LED_BANK", "LED_SALES", 1500_00L, StockDirection.OUT, 5, 300_00L)
    }

    @Test fun fy2_openingStockIsFy1ClosingStock_notTheItemsOriginalOpening() = runBlocking {
        val dao = freshDao(); dao.fy1Trading()
        dao.trade(fy2, "S2", "2027-05-01", VoucherType.SALES, "LED_BANK", "LED_SALES", 900_00L, StockDirection.OUT, 3, 300_00L)
        val pnl = AccountingRepository(dao).generateProfitAndLoss(company, fy2)
        assertEquals("FY1 closing stock: 15 x 100", 1500_00L, pnl.openingStock.paise)
        assertEquals("15 - 3 = 12 units x 100", 1200_00L, pnl.closingStock.paise)
        assertEquals("FY2 profit: sales 900 - cost 300", 600_00L, pnl.netProfit.paise)
    }

    @Test fun fy2_balanceSheetBalances_withStockAndPriorYearResultCarried() = runBlocking {
        val dao = freshDao(); dao.fy1Trading()
        dao.trade(fy2, "S2", "2027-05-01", VoucherType.SALES, "LED_BANK", "LED_SALES", 900_00L, StockDirection.OUT, 3, 300_00L)
        val bs = AccountingRepository(dao).generateBalanceSheet(company, fy2)
        assertTrue(bs.isBalanced)
        assertEquals(1200_00L, bs.stockInHand.paise)
        assertEquals("FY1 profit 1,000 (journal result 500 + stock 500)", 1000_00L, bs.reservesAndSurplus.paise)
        assertEquals(600_00L, bs.netProfitForYear.paise)
        assertEquals(1400_00L, bs.bankAccounts.paise)
    }

    @Test fun fy2_withNoActivity_stillCarriesTheStock_andBalances() = runBlocking {
        val dao = freshDao(); dao.fy1Trading()
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss(company, fy2)
        assertEquals(1500_00L, pnl.openingStock.paise); assertEquals(1500_00L, pnl.closingStock.paise)
        assertEquals(0L, pnl.netProfit.paise)
        val bs = repo.generateBalanceSheet(company, fy2)
        assertTrue(bs.isBalanced); assertEquals(1500_00L, bs.stockInHand.paise)
    }

    @Test fun firstFinancialYear_isUnchanged() = runBlocking {
        val dao = freshDao(); dao.fy1Trading()
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss(company, fy1)
        assertEquals("FY1 opening stock is the item's original opening", 1000_00L, pnl.openingStock.paise)
        assertEquals(1500_00L, pnl.closingStock.paise)
        assertEquals(1000_00L, pnl.netProfit.paise)
        val bs = repo.generateBalanceSheet(company, fy1)
        assertTrue(bs.isBalanced); assertEquals(0L, bs.reservesAndSurplus.paise)
    }

    @Test fun fy2_whenNothingMovedInFy1_openingStockIsTheItemsOpening() = runBlocking {
        val dao = freshDao(); dao.seed(10_000L, 100_00L)
        dao.trade(fy2, "S2", "2027-05-01", VoucherType.SALES, "LED_BANK", "LED_SALES", 900_00L, StockDirection.OUT, 3, 300_00L)
        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss(company, fy2)
        assertEquals(1000_00L, pnl.openingStock.paise); assertEquals(700_00L, pnl.closingStock.paise)
        assertTrue(repo.generateBalanceSheet(company, fy2).isBalanced)
    }

    @Test fun fy1ReportIsNotAffectedByLaterYearMovements() = runBlocking {
        val dao = freshDao(); dao.fy1Trading()
        dao.trade(fy2, "S2", "2027-05-01", VoucherType.SALES, "LED_BANK", "LED_SALES", 900_00L, StockDirection.OUT, 3, 300_00L)
        val pnl = AccountingRepository(dao).generateProfitAndLoss(company, fy1)
        assertEquals(1000_00L, pnl.openingStock.paise); assertEquals(1500_00L, pnl.closingStock.paise)
    }
}
