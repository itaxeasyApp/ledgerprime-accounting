package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.core.database.AccountingTransactionException
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.StockMovementEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.StockMovementType
import com.example.accounting.domain.inventory.engine.CogsEngine
import com.example.accounting.domain.inventory.engine.InventoryEngine
import com.example.accounting.domain.inventory.engine.StockValuationEngine
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.SupplyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 4 - Inventory & COGS test suite.
 *
 * Pure JVM (no Robolectric), following the exact pattern established in Phase 2/3: engine logic
 * ([InventoryEngine], [StockValuationEngine], [CogsEngine]) is Room-independent and tested
 * directly against a fake DAO; [AccountingRepository] is tested with `db = null` where its
 * pre-validation runs before touching the transaction.
 *
 * [InventoryAwareDao] backs groups, stock items, voucher stock lines, and stock movements with
 * real in-memory maps - [FakeAccountingDao] (Phase0TestSuite.kt) stubs all of these as no-ops,
 * harmless for Phase 0-2 (which never exercise them) but necessary here. Phase0TestSuite.kt
 * itself was touched only to add 8 mechanical no-op stub overrides so it still satisfies the
 * now-larger `AccountingDao` interface - zero behavior change to any existing test.
 */
class Phase4TestSuite {

    // Not private: Phase5TestSuite.kt reuses this real stock backing (it also needs item-driven
    // GST/stock lines) rather than duplicating the same in-memory maps - a mechanical visibility
    // change only, no logic touched.
    class InventoryAwareDao(private val delegate: AccountingDao) : AccountingDao by delegate {
        private val groups = LinkedHashMap<String, GroupEntity>()
        private val stockItems = LinkedHashMap<String, StockItemEntity>()
        private val stockLines = mutableListOf<VoucherStockLineEntity>()
        private val movements = mutableListOf<StockMovementEntity>()

        override fun getGroupsByCompany(companyId: String): Flow<List<GroupEntity>> = flowOf(groups.values.filter { it.companyId == companyId })
        override suspend fun getGroupById(companyId: String, groupId: String) = groups[groupId]?.takeIf { it.companyId == companyId }
        override suspend fun insertGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun insertGroups(groups: List<GroupEntity>) { groups.forEach { this.groups[it.groupId] = it } }
        override suspend fun updateGroup(group: GroupEntity) { groups[group.groupId] = group }
        override suspend fun deleteGroup(companyId: String, groupId: String) = if (groups.remove(groupId) != null) 1 else 0

        override fun getStockItemsByCompany(companyId: String): Flow<List<StockItemEntity>> = flowOf(stockItems.values.filter { it.companyId == companyId })
        override suspend fun getStockItemById(companyId: String, itemId: String) = stockItems[itemId]?.takeIf { it.companyId == companyId }
        override suspend fun insertStockItem(stockItem: StockItemEntity) { stockItems[stockItem.itemId] = stockItem }
        override suspend fun insertStockItems(items: List<StockItemEntity>) { items.forEach { stockItems[it.itemId] = it } }
        override suspend fun updateStockQuantity(companyId: String, itemId: String, newQuantity: Long) {
            stockItems[itemId]?.let { stockItems[itemId] = it.copy(currentQuantity = newQuantity) }
        }
        override suspend fun updateStockCache(companyId: String, itemId: String, newQuantity: Long, newAvgCostPaise: Long) {
            stockItems[itemId]?.let { stockItems[itemId] = it.copy(currentQuantity = newQuantity, currentAvgCostPaise = newAvgCostPaise) }
        }

        override suspend fun getStockLinesForVoucher(voucherId: String) = stockLines.filter { it.voucherId == voucherId }
        override suspend fun insertVoucherStockLines(lines: List<VoucherStockLineEntity>) { stockLines += lines }

        override suspend fun getStockMovementsForItem(companyId: String, itemId: String) =
            movements.filter { it.companyId == companyId && it.itemId == itemId }.sortedWith(compareBy({ it.date }, { it.createdAt }))
        override suspend fun getStockMovementsForVoucher(voucherId: String) = movements.filter { it.voucherId == voucherId }
        override suspend fun getStockMovementsForCompanyFY(companyId: String, fyId: String) =
            movements.filter { it.companyId == companyId && it.financialYearId == fyId }.sortedWith(compareBy({ it.date }, { it.createdAt }))
        override suspend fun insertStockMovement(movement: StockMovementEntity) { movements += movement }
        override suspend fun insertStockMovements(movementsToAdd: List<StockMovementEntity>) { movements += movementsToAdd }
    }

    private val companyId = "COMP_P4"
    private val fyId = "FY_P4_2026_27"

    private fun freshDao() = InventoryAwareDao(FakeAccountingDao())

    private suspend fun InventoryAwareDao.seedCompany(
        accountingMode: AccountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY,
        businessType: BusinessType = BusinessType.TRADING,
        company: String = companyId,
        financialYearId: String = fyId
    ) {
        insertCompany(CompanyEntity(
            companyId = company, name = "Company $company", tradeName = "Company $company", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = accountingMode, businessType = businessType
        ))
        insertFinancialYear(FinancialYearEntity(financialYearId, company, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_${company}_1", company, financialYearId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(company).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
    }

    private fun stockItem(itemId: String, company: String = companyId, openingQty: Long = 0L, openingRatePaise: Long = 0L) = StockItemEntity(
        itemId = itemId, companyId = company, name = itemId, sku = itemId, hsnCode = "8471", unit = "Pcs", gstRatePercent = 18.0,
        openingQuantity = openingQty, openingRatePaise = openingRatePaise, currentQuantity = openingQty,
        standardCostPaise = openingRatePaise, standardSellingPricePaise = openingRatePaise, currentAvgCostPaise = openingRatePaise
    )

    private fun voucherEntity(voucherId: String, number: String, type: VoucherType, date: String = "2026-05-10", company: String = companyId, financialYearId: String = fyId) = VoucherEntity(
        voucherId = voucherId, companyId = company, financialYearId = financialYearId, voucherNumber = number, voucherType = type,
        date = date, referenceNumber = "", narration = "", totalAmountPaise = 0L, isPosted = true, isCancelled = false,
        syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = false
    )

    private fun stockLine(voucherId: String, itemId: String, direction: StockDirection, qty: Long, ratePaise: Long, order: Int = 1, company: String = companyId, financialYearId: String = fyId) = VoucherStockLineEntity(
        lineId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = company, financialYearId = financialYearId,
        itemId = itemId, direction = direction, quantityRaw = qty * 1000L, ratePaise = ratePaise,
        amountPaise = StockValuationEngine.amountFor(qty * 1000L, ratePaise), lineOrder = order
    )

    /** Inserts real LedgerEntity rows under the correct standard groups so TrialBalance (which
     * iterates dao.getLedgersByCompany, not raw journal items) actually attributes their amounts. */
    private suspend fun InventoryAwareDao.seedTradingLedgers(company: String = companyId) {
        fun ledger(id: String, groupBare: String, openingPaise: Long = 0L, openingType: DrCr = DrCr.DEBIT) =
            com.example.accounting.data.local.entity.LedgerEntity(
                id, company, "${groupBare}_$company", id, id, openingPaise, openingType, openingPaise, openingType,
                "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
            )
        insertLedger(ledger("LED_PUR", StandardSystemGroups.PURCHASE_GROUP_ID))
        insertLedger(ledger("LED_BANK", StandardSystemGroups.BANK_GROUP_ID))
        insertLedger(ledger("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger("LED_CAPITAL", StandardSystemGroups.CAPITAL_GROUP_ID, openingType = DrCr.CREDIT))
    }

    private fun simpleJournal(voucherId: String, debitLedger: String, creditLedger: String, amountPaise: Long, company: String = companyId, financialYearId: String = fyId) = listOf(
        JournalItemEntity("${voucherId}_D", voucherId, company, financialYearId, debitLedger, DrCr.DEBIT, amountPaise, "", 1),
        JournalItemEntity("${voucherId}_C", voucherId, company, financialYearId, creditLedger, DrCr.CREDIT, amountPaise, "", 2)
    )

    // ==========================================
    // A. STOCK MOVEMENT MECHANICS
    // ==========================================
    @Test
    fun a1_Purchase_IncreasesStock() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L))
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK1", "TESTER", lines)

        val item = dao.getStockItemById(companyId, "ITEM_1")!!
        assertEquals(10_000L, item.currentQuantity) // 10.000 in thousandths
        assertEquals(500_00L, item.currentAvgCostPaise)
    }

    @Test
    fun a2_Sale_DecreasesStock() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "SAL-1", VoucherType.SALES)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 4, 800_00L)) // selling at 800, cost stays 500
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_DEBTOR", "LED_SALES", 3200_00L), "IK1", "TESTER", lines)

        val item = dao.getStockItemById(companyId, "ITEM_1")!!
        assertEquals(6_000L, item.currentQuantity)
        assertEquals(500_00L, item.currentAvgCostPaise) // unchanged by an OUT movement

        val movement = dao.getStockMovementsForVoucher("V1").single()
        assertEquals(StockMovementType.SALE, movement.movementType)
        assertEquals("Movement cost must be the weighted-average COST (500), not the selling price (800)", 500_00L, movement.ratePaise)
        assertEquals(2000_00L, movement.amountPaise) // 4 units x 500 cost, not 3200 (selling)
    }

    @Test
    fun a3_PurchaseReturn_DecreasesStock() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "PUR-RET-1", VoucherType.PURCHASE)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 3, 500_00L))
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_BANK", "LED_PUR", 1500_00L), "IK1", "TESTER", lines)

        assertEquals(7_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(StockMovementType.PURCHASE_RETURN, dao.getStockMovementsForVoucher("V1").single().movementType)
    }

    @Test
    fun a4_SalesReturn_IncreasesStock() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 6_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "CRN-1", VoucherType.CREDIT_NOTE)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 2, 500_00L))
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_SALES", "LED_DEBTOR", 1000_00L), "IK1", "TESTER", lines)

        assertEquals(8_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(StockMovementType.SALES_RETURN, dao.getStockMovementsForVoucher("V1").single().movementType)
    }

    @Test
    fun a5_StockAdjustment_JournalVoucherType() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "JRN-1", VoucherType.JOURNAL)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 1, 500_00L)) // e.g. damage write-off
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_INDIR_EXP", "LED_STOCK_ADJ", 500_00L), "IK1", "TESTER", lines)

        assertEquals(9_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(StockMovementType.ADJUSTMENT_OUT, dao.getStockMovementsForVoucher("V1").single().movementType)
    }

    @Test
    fun a6_ZeroQuantity_Rejected() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        val badLine = stockLine("V1", "ITEM_1", StockDirection.IN, 0, 500_00L)
        try {
            VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 0L), "IK1", "TESTER", listOf(badLine))
            fail("Expected zero quantity to be rejected")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.InvalidStockQuantity)
        }
    }

    @Test
    fun a7_NegativeQuantity_Rejected() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        val badLine = stockLine("V1", "ITEM_1", StockDirection.IN, -5, 500_00L)
        try {
            VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 0L), "IK1", "TESTER", listOf(badLine))
            fail("Expected negative quantity to be rejected")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.InvalidStockQuantity)
        }
    }

    @Test
    fun a8_InsufficientStock_OutRejected() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 2_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "SAL-1", VoucherType.SALES)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 5, 800_00L)) // only 2 on hand
        try {
            VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_DEBTOR", "LED_SALES", 4000_00L), "IK1", "TESTER", lines)
            fail("Expected insufficient stock to be rejected")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.InsufficientStock)
        }
        // Nothing should have moved
        assertEquals(2_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
    }

    // ==========================================
    // B. VALUATION
    // ==========================================
    @Test
    fun b1_WeightedAverageCost_ComputedCorrectly() {
        // 10 units @ 500 then 10 units @ 700 -> avg (10*500 + 10*700)/20 = 600
        val newAvg = StockValuationEngine.weightedAverageCostAfterReceipt(10_000L, 500_00L, 10_000L, 700_00L)
        assertEquals(600_00L, newAvg)
    }

    @Test
    fun b2_MultiplePurchases_ThenSale_UsesRunningWeightedAverage() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v1 = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v1, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L)))
        val v2 = voucherEntity("V2", "PUR-2", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v2, simpleJournal("V2", "LED_PUR", "LED_BANK", 7000_00L), "IK2", "TESTER", listOf(stockLine("V2", "ITEM_1", StockDirection.IN, 10, 700_00L)))

        assertEquals(600_00L, dao.getStockItemById(companyId, "ITEM_1")!!.currentAvgCostPaise)

        val v3 = voucherEntity("V3", "SAL-1", VoucherType.SALES)
        VoucherPostingEngine.post(dao, v3, simpleJournal("V3", "LED_DEBTOR", "LED_SALES", 6000_00L), "IK3", "TESTER", listOf(stockLine("V3", "ITEM_1", StockDirection.OUT, 5, 1000_00L)))
        val saleMovement = dao.getStockMovementsForVoucher("V3").single()
        assertEquals(600_00L, saleMovement.ratePaise) // cost basis, not the 1000 selling rate
        assertEquals(3000_00L, saleMovement.amountPaise) // 5 x 600
    }

    // ==========================================
    // C. COGS / OPENING / CLOSING STOCK
    // ==========================================
    @Test
    fun c1_CogsEngine_OpeningPlusPurchasesMinusClosing() {
        // Opening 10 @ 500 = 5000. Purchase 10 @ 700 = 7000. Sell 12 units (cost basis moves with avg).
        val purchase = StockMovementEntity(
            "M1", companyId, fyId, "ITEM_1", "V1", "2026-05-01", StockDirection.IN, StockMovementType.PURCHASE,
            10_000L, 700_00L, 7000_00L, 600_00L, "PUR-1", "", 0L, "TESTER"
        )
        val sale = StockMovementEntity(
            "M2", companyId, fyId, "ITEM_1", "V2", "2026-06-01", StockDirection.OUT, StockMovementType.SALE,
            12_000L, 600_00L, 7200_00L, 600_00L, "SAL-1", "", 0L, "TESTER"
        )
        val result = CogsEngine.computeForItem(10_000L, 500_00L, emptyList(), listOf(purchase, sale))
        // Opening 5000 + Purchases 7000 - Returns 0 - Closing(8 units @ 600 = 4800) = 7200
        assertEquals(5000_00L, result.openingStockPaise)
        assertEquals(7000_00L, result.purchasesAtCostPaise)
        assertEquals(4800_00L, result.closingStockPaise)
        assertEquals(7200_00L, result.cogsPaise)
    }

    @Test
    fun c2_PnL_AccountWithInventory_UsesCogsInGrossProfit() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY)
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v1 = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v1, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L)))
        val v2 = voucherEntity("V2", "SAL-1", VoucherType.SALES)
        VoucherPostingEngine.post(dao, v2, simpleJournal("V2", "LED_SALES", "LED_DEBTOR", 12000_00L).let {
            listOf(it[1].copy(itemId = "V2_D", ledgerId = "LED_DEBTOR", type = DrCr.DEBIT), it[0].copy(itemId = "V2_C", ledgerId = "LED_SALES", type = DrCr.CREDIT))
        }, "IK2", "TESTER", listOf(stockLine("V2", "ITEM_1", StockDirection.OUT, 15, 800_00L)))

        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss(companyId, fyId)
        assertTrue(pnl.isInventoryAware)
        // Opening 0 (item had no opening in this test setup beyond what's on the item itself: 10@500=5000)
        // Purchases at cost = 5000 (10@500). Closing = 5 units left @ 500 = 2500.
        // COGS = 5000(opening) + 5000(purchase) - 0 - 2500(closing) = 7500
        assertEquals(7500_00L, pnl.cogs.paise)
        assertEquals(12000_00L, pnl.salesRevenue.paise)
    }

    @Test
    fun c3_PnL_AccountOnly_UnaffectedByInventory() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(accountingMode = AccountingMode.ACCOUNT_ONLY)
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v1 = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v1, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L)))

        val repo = AccountingRepository(dao)
        val pnl = repo.generateProfitAndLoss(companyId, fyId)
        assertFalse("ACCOUNT_ONLY companies must never become inventory-aware", pnl.isInventoryAware)
        assertEquals(0L, pnl.cogs.paise)
        assertEquals(5000_00L, pnl.purchases.paise) // falls back to plain Purchases group total, as before Phase 4
    }

    @Test
    fun c4_BalanceSheet_StockInHand_Included() = runBlocking {
        // Stock-in-Hand is a computed figure with no ledger of its own (periodic inventory - see
        // class doc), so it must be reached via a REAL purchase posting (which correctly debits
        // an expense and credits Bank) rather than a bare item.openingQuantity with nothing
        // offsetting it in the real ledger system - proves the whole chain balances end to end.
        val dao = freshDao()
        dao.seedCompany(accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY)
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v1 = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v1, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L)))

        val repo = AccountingRepository(dao)
        val bs = repo.generateBalanceSheet(companyId, fyId)
        assertEquals(5000_00L, bs.stockInHand.paise) // all 10 units still on hand, none sold
        assertTrue("Cash spent on unsold inventory is an asset-for-asset swap (Bank down, Stock up) - must still balance", bs.isBalanced)
    }

    // ==========================================
    // D. ISOLATION
    // ==========================================
    @Test
    fun d1_CompanyIsolation() = runBlocking {
        // itemId is the entity's global @PrimaryKey (like ledgerId elsewhere in the project), so
        // distinct companies must use distinct item IDs - "ITEM_1_COMP_A" / "ITEM_1_COMP_B" -
        // exactly the suffixing convention already used for groups/ledgers throughout the project.
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1_COMP_A", company = "COMP_A"))
        dao.insertStockItem(stockItem("ITEM_1_COMP_B", company = "COMP_B", openingQty = 999_000L))
        val vA = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE, company = "COMP_A")
        VoucherPostingEngine.post(dao, vA, simpleJournal("V1", "LED_PUR", "LED_BANK", 500_00L, company = "COMP_A"), "IK1", "TESTER",
            listOf(stockLine("V1", "ITEM_1_COMP_A", StockDirection.IN, 1, 500_00L, company = "COMP_A")))

        assertEquals(1_000L, dao.getStockItemById("COMP_A", "ITEM_1_COMP_A")!!.currentQuantity)
        assertEquals("Company B's item must be untouched by Company A's posting", 999_000L, dao.getStockItemById("COMP_B", "ITEM_1_COMP_B")!!.currentQuantity)
    }

    @Test
    fun d2_FinancialYearIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(financialYearId = "FY_A")
        dao.insertFinancialYear(FinancialYearEntity("FY_B", companyId, "FY 2027-28", "2027-04-01", "2028-03-31", false, false, null, null))
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE, financialYearId = "FY_A")
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 500_00L, financialYearId = "FY_A"), "IK1", "TESTER",
            listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 1, 500_00L, financialYearId = "FY_A")))

        assertEquals(1, dao.getStockMovementsForCompanyFY(companyId, "FY_A").size)
        assertEquals("A different FY must show zero movements", 0, dao.getStockMovementsForCompanyFY(companyId, "FY_B").size)
    }

    @Test
    fun d3_LockedPeriod_RejectsInventoryPosting() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.setPeriodStatus(companyId, "PER_${companyId}_1", PeriodStatus.LOCKED, "SUPERVISOR", System.currentTimeMillis())
        dao.insertStockItem(stockItem("ITEM_1"))
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity(
            "LED_PUR", companyId, "GRP_PURCHASE_$companyId", "Purchases", "P1", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT,
            "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
        ))
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity(
            "LED_BANK", companyId, "GRP_BANK_$companyId", "Bank", "B1", 100000_00L, DrCr.DEBIT, 100000_00L, DrCr.DEBIT,
            "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
        ))

        val repo = AccountingRepository(dao)
        val voucher = com.example.accounting.domain.accounting.Voucher(
            voucherId = "V1", companyId = companyId, financialYearId = fyId, voucherNumber = "PUR-1",
            voucherType = VoucherType.PURCHASE, date = java.time.LocalDate.of(2026, 5, 10),
            totalAmount = Money.fromPaise(500_00L),
            items = listOf(
                com.example.accounting.domain.accounting.JournalItem("I1", "V1", companyId, fyId, "LED_PUR", "Purchases", DrCr.DEBIT, Money.fromPaise(500_00L), lineOrder = 1),
                com.example.accounting.domain.accounting.JournalItem("I2", "V1", companyId, fyId, "LED_BANK", "Bank", DrCr.CREDIT, Money.fromPaise(500_00L), lineOrder = 2)
            )
        )
        val result = repo.postVoucher(voucher, stockLines = listOf(
            com.example.accounting.domain.inventory.VoucherStockLine("L1", "V1", companyId, fyId, "ITEM_1", direction = StockDirection.IN, quantity = Quantity.fromLong(1), rate = Money.fromPaise(500_00L), amount = Money.fromPaise(500_00L))
        ))
        assertTrue("Locked period must reject posting even when stock lines are present", result is AccountingResult.Failure)
        assertTrue((result as AccountingResult.Failure).error is AppError.PeriodLocked)
        assertEquals("No stock movement should have been created", 0, dao.getStockMovementsForCompanyFY(companyId, fyId).size)
    }

    // ==========================================
    // E. CANCELLATION
    // ==========================================
    @Test
    fun e1_Cancellation_ReversesStock_OriginalPreserved() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "SAL-1", VoucherType.SALES)
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_DEBTOR", "LED_SALES", 4000_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 4, 1000_00L)))
        assertEquals(6_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)

        VoucherPostingEngine.cancel(dao, companyId, fyId, "V1", "IK-CANCEL", "TESTER")

        assertEquals("Stock must return to pre-sale quantity", 10_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        val allMovements = dao.getStockMovementsForVoucher("V1")
        assertEquals("Original OUT movement + compensating IN reversal, nothing deleted", 2, allMovements.size)
        assertTrue(allMovements.any { it.movementType == StockMovementType.SALE && it.direction == StockDirection.OUT })
        assertTrue(allMovements.any { it.movementType == StockMovementType.CANCELLATION_REVERSAL && it.direction == StockDirection.IN })
    }

    @Test
    fun e2_DoubleCancellation_Rejected_StockNotDoubleReversed() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "SAL-1", VoucherType.SALES)
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_DEBTOR", "LED_SALES", 4000_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 4, 1000_00L)))
        VoucherPostingEngine.cancel(dao, companyId, fyId, "V1", "IK-C1", "TESTER")

        // Real delete (explicit correction): the voucher row is genuinely gone after the first
        // cancel, so a second attempt (a different idempotency key) now fails "not found", not the
        // old "already cancelled" business-rule rejection.
        try {
            VoucherPostingEngine.cancel(dao, companyId, fyId, "V1", "IK-C2", "TESTER")
            fail("Expected cancelling an already-deleted voucher to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("V1") == true)
        }
        assertEquals(10_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(2, dao.getStockMovementsForVoucher("V1").size) // still just original + one reversal
    }

    // ==========================================
    // F. AUDIT / OUTBOX / IDEMPOTENCY
    // ==========================================
    @Test
    fun f1_IdempotentReplay_NoDoubleStockMutation() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        val lines = listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L))
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK-REPLAY", "TESTER", lines)
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK-REPLAY", "TESTER", lines)

        assertEquals("Replay with the same idempotency key must not double-apply the stock effect", 10_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(1, dao.getStockMovementsForVoucher("V1").size)
    }

    @Test
    fun f2_OutboxCreated_ForInventoryVoucher() = runBlocking {
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1"))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK-OUTBOX", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 10, 500_00L)))
        assertNotNull("Inventory voucher posting must still enqueue the normal outbox record", dao.getOutboxByIdempotencyKey("IK-OUTBOX"))
    }

    // ==========================================
    // G. DOCUMENT TYPES
    // ==========================================
    @Test
    fun g1_DeliveryNote_MovesStock_EngineAcceptsNoFinancialLines() = runBlocking {
        // DELIVERY_NOTE is DocumentPostingType.INVENTORY_ONLY - no ledger effect. The engine itself
        // has no double-entry requirement (that check lives in DoubleEntryValidator, only invoked
        // by AccountingRepository.postVoucher for financial-posting flows), so it accepts an empty
        // journal list here, proving the mechanism supports inventory-only documents.
        val dao = freshDao()
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "DC-1", VoucherType.DELIVERY_NOTE)
        VoucherPostingEngine.post(dao, v, emptyList(), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.OUT, 2, 500_00L)))

        assertEquals(8_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(StockMovementType.STOCK_JOURNAL_OUT, dao.getStockMovementsForVoucher("V1").single().movementType)
        assertTrue("No journal items must have been created for a pure inventory document", dao.getJournalItemsForVoucherSync("V1").isEmpty())
    }

    // ==========================================
    // H. GST ENGINE
    // ==========================================
    @Test
    fun h1_GstCalculationEngine_IntraState_SplitsCgstSgst() {
        val result = GstCalculationEngine.calculate(Money.fromPaise(10000_00L), 18.0, "27", "27")
        assertEquals(SupplyType.INTRA_STATE, result.supplyType)
        assertEquals(900_00L, result.cgstAmount.paise)
        assertEquals(900_00L, result.sgstAmount.paise)
        assertEquals(0L, result.igstAmount.paise)
    }

    @Test
    fun h2_GstCalculationEngine_InterState_UsesIgst() {
        val result = GstCalculationEngine.calculate(Money.fromPaise(10000_00L), 18.0, "27", "29")
        assertEquals(SupplyType.INTER_STATE, result.supplyType)
        assertEquals(0L, result.cgstAmount.paise)
        assertEquals(0L, result.sgstAmount.paise)
        assertEquals(1800_00L, result.igstAmount.paise)
    }

    @Test
    fun h3_GstCalculationEngine_FallbackWhenPartyStateUnknown() {
        val interstate = GstCalculationEngine.calculateWithFallback(Money.fromPaise(1000_00L), 18.0, "27", "", fallbackInterState = true)
        assertEquals(SupplyType.INTER_STATE, interstate.supplyType)
        val intrastate = GstCalculationEngine.calculateWithFallback(Money.fromPaise(1000_00L), 18.0, "27", "", fallbackInterState = false)
        assertEquals(SupplyType.INTRA_STATE, intrastate.supplyType)
    }

    // ==========================================
    // I. ACCOUNTING MODE / BUSINESS TYPE
    // ==========================================
    @Test
    fun i1_SwitchingModes_NeverDeletesData() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY)
        dao.insertStockItem(stockItem("ITEM_1", openingQty = 10_000L, openingRatePaise = 500_00L))
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 500_00L), "IK1", "TESTER", listOf(stockLine("V1", "ITEM_1", StockDirection.IN, 1, 500_00L)))

        val repo = AccountingRepository(dao)
        repo.updateAccountingConfiguration(companyId, accountingMode = AccountingMode.ACCOUNT_ONLY)

        // Underlying data must survive the switch untouched.
        assertEquals(11_000L, dao.getStockItemById(companyId, "ITEM_1")!!.currentQuantity)
        assertEquals(1, dao.getStockMovementsForVoucher("V1").size)

        val pnlAfterSwitch = repo.generateProfitAndLoss(companyId, fyId)
        assertFalse("P&L must stop being inventory-aware once switched to ACCOUNT_ONLY", pnlAfterSwitch.isInventoryAware)

        repo.updateAccountingConfiguration(companyId, accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY)
        val pnlAfterSwitchBack = repo.generateProfitAndLoss(companyId, fyId)
        assertTrue("Switching back must recompute from the (never-deleted) stock history", pnlAfterSwitchBack.isInventoryAware)
    }

    /**
     * Balance Sheet crash regression (real-device audit finding) - a Purchase posted through the
     * item-free Account-Only path (no [StockItem]/[VoucherStockLineEntity] involved at all, the
     * same shape `buildAccountOnlyPurchase` produces) must not vanish from Gross/Net Profit just
     * because the company is later switched to ACCOUNT_WITH_INVENTORY with zero stock items ever
     * created - that used to fabricate a real, all-zero [CogsEngine.CogsResult] instead of falling
     * back to the plain ledger-based Purchases figure, silently discarding the real expense and
     * making [AccountingRepository.generateBalanceSheet] throw [AppError.BalanceSheetNotBalanced].
     */
    @Test
    fun i3_AccountOnlyPurchase_ThenSwitchToInventoryModeWithNoItems_BalanceSheetStillBalances() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(accountingMode = AccountingMode.ACCOUNT_ONLY)
        dao.seedTradingLedgers()
        val v = voucherEntity("V1", "PUR-1", VoucherType.PURCHASE)
        VoucherPostingEngine.post(dao, v, simpleJournal("V1", "LED_PUR", "LED_BANK", 5000_00L), "IK1", "TESTER")

        val repo = AccountingRepository(dao)
        repo.updateAccountingConfiguration(companyId, accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY)

        val pnl = repo.generateProfitAndLoss(companyId, fyId)
        assertFalse("Zero stock items ever created means nothing to compute a real COGS from - never fabricate one", pnl.isInventoryAware)
        assertEquals("The real Account-Only Purchase must still be reflected, not silently zeroed by the mode switch", 5000_00L, pnl.purchases.paise)

        // Must not throw AccountingTransactionException(BalanceSheetNotBalanced).
        val balanceSheet = repo.generateBalanceSheet(companyId, fyId)
        assertTrue(balanceSheet.isBalanced)
    }

    @Test
    fun i2_IncomeAndExpenditure_ServiceBusinessType_Surplus() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(businessType = BusinessType.SERVICE, accountingMode = AccountingMode.ACCOUNT_ONLY)
        val v1 = voucherEntity("V1", "RCT-1", VoucherType.RECEIPT)
        VoucherPostingEngine.post(dao, v1, listOf(
            JournalItemEntity("I1", "V1", companyId, fyId, "LED_BANK", DrCr.DEBIT, 5000_00L, "", 1),
            JournalItemEntity("I2", "V1", companyId, fyId, "GRP_INDIR_INCOME_$companyId".let { "LED_FEES" }, DrCr.CREDIT, 5000_00L, "", 2)
        ), "IK1", "TESTER")
        val v2 = voucherEntity("V2", "PMT-1", VoucherType.PAYMENT)
        VoucherPostingEngine.post(dao, v2, listOf(
            JournalItemEntity("I3", "V2", companyId, fyId, "LED_INDIR_EXP", DrCr.DEBIT, 2000_00L, "", 1),
            JournalItemEntity("I4", "V2", companyId, fyId, "LED_BANK", DrCr.CREDIT, 2000_00L, "", 2)
        ), "IK2", "TESTER")

        // Ledgers referenced above only need to exist for group classification (via groupId lookup
        // in generateTrialBalance) - insert them directly under the correct standard groups.
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity("LED_BANK", companyId, "GRP_BANK_$companyId", "Bank", "B1", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0))
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity("LED_FEES", companyId, "GRP_INDIR_INCOME_$companyId", "Professional Fees", "F1", 0L, DrCr.CREDIT, 0L, DrCr.CREDIT, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0))
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity("LED_INDIR_EXP", companyId, "GRP_INDIR_EXP_$companyId", "Office Expenses", "E1", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0))

        val repo = AccountingRepository(dao)
        val report = repo.generateIncomeAndExpenditure(companyId, fyId)
        assertEquals(5000_00L, report.income.paise)
        assertEquals(2000_00L, report.expenditure.paise)
        assertEquals(3000_00L, report.surplusOrDeficit.paise)
        assertTrue(report.isSurplus)
    }

    @Test
    fun i3_IncomeAndExpenditure_Deficit_SignPreserved() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(businessType = BusinessType.SERVICE, accountingMode = AccountingMode.ACCOUNT_ONLY)
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity("LED_BANK", companyId, "GRP_BANK_$companyId", "Bank", "B1", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0))
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity("LED_INDIR_EXP", companyId, "GRP_INDIR_EXP_$companyId", "Office Expenses", "E1", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT, "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0))

        val v = voucherEntity("V1", "PMT-1", VoucherType.PAYMENT)
        VoucherPostingEngine.post(dao, v, listOf(
            JournalItemEntity("I1", "V1", companyId, fyId, "LED_INDIR_EXP", DrCr.DEBIT, 3000_00L, "", 1),
            JournalItemEntity("I2", "V1", companyId, fyId, "LED_BANK", DrCr.CREDIT, 3000_00L, "", 2)
        ), "IK1", "TESTER")

        val repo = AccountingRepository(dao)
        val report = repo.generateIncomeAndExpenditure(companyId, fyId)
        assertEquals(-3000_00L, report.surplusOrDeficit.paise)
        assertFalse(report.isSurplus)
    }

    // ==========================================
    // J. GST OUTPUT/INPUT LEDGER SPLIT (Phase 4.5)
    // ==========================================
    @Test
    fun j1_EnsureGstLedgersExist_CreatesAllSixUnderDutiesAndTaxes() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)

        repo.ensureGstLedgersExist(companyId)

        val ledgers = dao.getLedgersByCompany(companyId).first()
        val bareIds = com.example.accounting.domain.taxation.gst.GstLedgerIds.ALL_BARE_IDS
        bareIds.forEach { bareId ->
            val created = ledgers.firstOrNull { it.ledgerId == "${bareId}_$companyId" }
            assertNotNull("Expected GST ledger $bareId to be created", created)
            assertEquals("GRP_DUTIES_$companyId", created!!.groupId)
        }

        val outputIds = setOf(
            com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_CGST_LEDGER_ID,
            com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_SGST_LEDGER_ID,
            com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_IGST_LEDGER_ID
        )
        val inputIds = setOf(
            com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_CGST_LEDGER_ID,
            com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_SGST_LEDGER_ID,
            com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_IGST_LEDGER_ID
        )
        outputIds.forEach { bareId ->
            val led = ledgers.first { it.ledgerId == "${bareId}_$companyId" }
            assertEquals("Output GST ledgers accrue as a liability (Credit nature)", DrCr.CREDIT, led.openingBalanceType)
        }
        inputIds.forEach { bareId ->
            val led = ledgers.first { it.ledgerId == "${bareId}_$companyId" }
            assertEquals("Input GST ledgers accrue as a claimable asset (Debit nature)", DrCr.DEBIT, led.openingBalanceType)
        }
    }

    @Test
    fun j2_EnsureGstLedgersExist_IsIdempotent_NeverDuplicates() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)

        repo.ensureGstLedgersExist(companyId)
        val afterFirst = dao.getLedgersByCompany(companyId).first().size

        repo.ensureGstLedgersExist(companyId)
        repo.ensureGstLedgersExist(companyId)
        val afterRepeat = dao.getLedgersByCompany(companyId).first().size

        assertEquals("Calling ensureGstLedgersExist repeatedly must not create duplicate ledgers", afterFirst, afterRepeat)
    }

    @Test
    fun j3_EnsureGstLedgersExist_BackfillsOnlyMissingOnes_PreservesExistingBalance() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)

        // Simulate a company seeded before Phase 4.5 that already has an Output CGST ledger
        // with a non-zero posted balance - backfill must not clobber it.
        val outputCgstId = "${com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_CGST_LEDGER_ID}_$companyId"
        dao.insertLedger(com.example.accounting.data.local.entity.LedgerEntity(
            outputCgstId, companyId, "GRP_DUTIES_$companyId", "Output CGST A/c", "5001",
            1000_00L, DrCr.CREDIT, 1000_00L, DrCr.CREDIT, "", "", "27", "", "", "", "", "", "", "", true, true, "", 0.0
        ))

        repo.ensureGstLedgersExist(companyId)

        val ledgers = dao.getLedgersByCompany(companyId).first()
        val preserved = ledgers.first { it.ledgerId == outputCgstId }
        assertEquals("Backfill must not overwrite an already-existing ledger's balance", 1000_00L, preserved.currentBalancePaise)

        val inputCgstId = "${com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId"
        assertNotNull("The missing Input CGST ledger must be backfilled", ledgers.firstOrNull { it.ledgerId == inputCgstId })
    }

    @Test
    fun j4_GstCalculationEngine_OutputAndInputLedgerIds_AreDistinctPerTaxType() {
        val allIds = com.example.accounting.domain.taxation.gst.GstLedgerIds.ALL_BARE_IDS
        // Thirteen distinct GST ledgers as of Rule 31: Output x3 + Input x3 + CESS x1 (Phase 5) +
        // RCM Liability x3 + RCM Input x3 (Rule 31), never conflated.
        assertEquals("Thirteen distinct GST ledgers - Output x3 + Input x3 + CESS + RCM Liability x3 + RCM Input x3, never conflated", 13, allIds.toSet().size)

        assertTrue(com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_CGST_LEDGER_ID != com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_CGST_LEDGER_ID)
        assertTrue(com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_SGST_LEDGER_ID != com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_SGST_LEDGER_ID)
        assertTrue(com.example.accounting.domain.taxation.gst.GstLedgerIds.OUTPUT_IGST_LEDGER_ID != com.example.accounting.domain.taxation.gst.GstLedgerIds.INPUT_IGST_LEDGER_ID)
    }

    @Test
    fun j5_GstCalculationEngine_CalculateWithFallback_IntraStateSplitsCgstSgst() {
        val breakdown = GstCalculationEngine.calculateWithFallback(
            taxableAmount = Money.fromRupees(1000L),
            taxRatePercent = 18.0,
            companyStateCode = "27",
            partyStateCode = "27",
            fallbackInterState = false
        )
        assertEquals(90_00L, breakdown.cgstAmount.paise)
        assertEquals(90_00L, breakdown.sgstAmount.paise)
        assertEquals(0L, breakdown.igstAmount.paise)
    }

    @Test
    fun j6_GstCalculationEngine_CalculateWithFallback_InterStateUsesIgstOnly() {
        val breakdown = GstCalculationEngine.calculateWithFallback(
            taxableAmount = Money.fromRupees(1000L),
            taxRatePercent = 18.0,
            companyStateCode = "27",
            partyStateCode = "",
            fallbackInterState = true
        )
        assertEquals(0L, breakdown.cgstAmount.paise)
        assertEquals(0L, breakdown.sgstAmount.paise)
        assertEquals(180_00L, breakdown.igstAmount.paise)
    }
}
