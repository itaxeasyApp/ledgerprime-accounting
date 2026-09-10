package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.TaxColumnMode
import com.example.accounting.domain.rendering.TaxColumnSelector
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.SupplyType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "5 Invoice PDF Templates" task - [AccountingRepository.assembleDocumentDataFromVoucher] is the
 * bridge that makes a real, already-posted Sale/Purchase voucher (the only kind the Sales/
 * Purchases tabs actually create) renderable at all, since [AccountingRepository.assembleDocumentData]
 * only ever reads the separate, Phase 7A draft-Invoice flow. These tests insert the exact rows a
 * real posting would produce (bypassing `postVoucher`'s own validation, which is already covered by
 * `Phase5TestSuite`) and assert the resulting [DocumentData] reads them back byte-for-byte - no
 * value here is recalculated, only joined and re-shaped.
 */
class AssembleDocumentDataFromVoucherTestSuite {

    /** [FakeAccountingDao]'s own `getStockLinesForVoucher`/`getGstTransactionsForVoucher` (and
     * their inserts) are permanent no-op stubs (same convention [Phase4TestSuite.InventoryAwareDao]
     * exists to fix for stock lines alone) - this adds real in-memory backing for both, composed
     * on top of [Phase7JBAwareDao] exactly the way this project already chains dao decorators
     * (e.g. `Phase7FTestSuite` reusing `Phase7BTestSuite.Phase7BAwareDao`). */
    private class InvoiceAwareDao(private val delegate: AccountingDao) : AccountingDao by delegate {
        private val stockLines = mutableListOf<VoucherStockLineEntity>()
        private val gstTransactions = mutableListOf<GstTransactionEntity>()

        override suspend fun getStockLinesForVoucher(voucherId: String) = stockLines.filter { it.voucherId == voucherId }
        override suspend fun insertVoucherStockLines(lines: List<VoucherStockLineEntity>) { stockLines += lines }
        override suspend fun getGstTransactionsForVoucher(voucherId: String) = gstTransactions.filter { it.voucherId == voucherId }
        override suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>) { gstTransactions += transactions }
    }

    private val companyId = Phase7JBFixtures.COMPANY_ID
    private val fyId = Phase7JBFixtures.FY_ID

    private fun freshDao(): AccountingDao = InvoiceAwareDao(Phase7JBAwareDao(FakeAccountingDao()))

    private fun stockItem(itemId: String) = StockItemEntity(
        itemId = itemId, companyId = companyId, name = "Widget", sku = "SKU1", hsnCode = "8471", unit = "Nos",
        gstRatePercent = 18.0, openingQuantity = 0L, openingRatePaise = 0L, currentQuantity = 0L,
        standardCostPaise = 0L, standardSellingPricePaise = 0L
    )

    @Test
    fun intraState_sale_showsCgstSgstOnly_andRealDiscount() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)

        val customerLedger = LedgerEntity(
            "LED_CUST", companyId, "GRP_DEBTORS_$companyId", "Acme Traders", "", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT,
            "27AAAAA1111A1Z5", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
        )
        dao.insertLedger(customerLedger)
        dao.insertLedger(Phase7JBFixtures.ledgerEntity("LED_SALES", companyId, "GRP_SALES"))
        dao.insertStockItem(stockItem("ITEM_A"))

        dao.insertVoucher(
            VoucherEntity(
                voucherId = "V1", companyId = companyId, financialYearId = fyId, voucherNumber = "SI-0001",
                voucherType = VoucherType.SALES, date = "2026-04-10", referenceNumber = "", narration = "",
                totalAmountPaise = 1062_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
                createdAt = 0L, updatedAt = 0L, createdBy = "TEST", partyGstin = "27AAAAA1111A1Z5", isGstApplicable = true
            )
        )
        dao.insertJournalItems(
            listOf(
                JournalItemEntity("J1", "V1", companyId, fyId, "LED_CUST", DrCr.DEBIT, 1062_00L, "", 1),
                JournalItemEntity("J2", "V1", companyId, fyId, "LED_SALES", DrCr.CREDIT, 900_00L, "", 2)
            )
        )
        dao.insertVoucherStockLines(
            listOf(
                VoucherStockLineEntity(
                    lineId = "SL1", voucherId = "V1", companyId = companyId, financialYearId = fyId, itemId = "ITEM_A",
                    direction = StockDirection.OUT, quantityRaw = 1000L, ratePaise = 1000_00L, amountPaise = 900_00L,
                    lineOrder = 1, discountPaise = 100_00L
                )
            )
        )
        dao.insertGstTransactions(
            listOf(
                GstTransactionEntity(
                    gstTransactionId = "G1", companyId = companyId, financialYearId = fyId, voucherId = "V1",
                    voucherType = VoucherType.SALES, partyLedgerId = "LED_CUST", partyGstin = "27AAAAA1111A1Z5",
                    placeOfSupply = "27", supplyType = SupplyType.INTRA_STATE, itemId = "ITEM_A", hsnSacCode = "8471",
                    quantityRaw = 1000L, taxableAmountPaise = 900_00L, gstRatePercent = 18.0,
                    cgstPaise = 81_00L, sgstPaise = 81_00L, igstPaise = 0L, cessPaise = 0L,
                    direction = GstDirection.OUTPUT, lineOrder = 1, createdAt = 0L
                )
            )
        )

        val result = repository.assembleDocumentDataFromVoucher(companyId, "V1")
        assertTrue(result is AccountingResult.Success)
        val data = (result as AccountingResult.Success).data

        assertEquals(1, data.items.size)
        val line = data.items.single()
        assertEquals(1000_00L, line.rate.paise)
        assertEquals(100_00L, line.discount.paise)
        assertEquals(900_00L, line.taxableAmount.paise)
        assertEquals(81_00L, line.cgst.paise)
        assertEquals(81_00L, line.sgst.paise)
        assertEquals(0L, line.igst.paise)
        assertEquals(1062_00L, data.totals.grandTotal.paise)
        assertEquals("Acme Traders", data.buyer.name)
        assertEquals("Company", data.seller.name)
        assertTrue(data.isPosted)
        assertEquals(TaxColumnMode.CGST_SGST, TaxColumnSelector.resolve(data.totals))
    }

    @Test
    fun interState_sale_showsIgstOnly() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)

        val customerLedger = LedgerEntity(
            "LED_CUST", companyId, "GRP_DEBTORS_$companyId", "Delhi Buyer", "", 0L, DrCr.DEBIT, 0L, DrCr.DEBIT,
            "07AAAAA2222A1Z5", "", "07", "", "", "", "", "", "", "", false, true, "", 0.0
        )
        dao.insertLedger(customerLedger)
        dao.insertStockItem(stockItem("ITEM_A"))
        dao.insertVoucher(
            VoucherEntity(
                voucherId = "V2", companyId = companyId, financialYearId = fyId, voucherNumber = "SI-0002",
                voucherType = VoucherType.SALES, date = "2026-04-11", referenceNumber = "", narration = "",
                totalAmountPaise = 1180_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
                createdAt = 0L, updatedAt = 0L, createdBy = "TEST", partyGstin = "07AAAAA2222A1Z5", isGstApplicable = true
            )
        )
        dao.insertVoucherStockLines(
            listOf(
                VoucherStockLineEntity(
                    lineId = "SL2", voucherId = "V2", companyId = companyId, financialYearId = fyId, itemId = "ITEM_A",
                    direction = StockDirection.OUT, quantityRaw = 1000L, ratePaise = 1000_00L, amountPaise = 1000_00L,
                    lineOrder = 1, discountPaise = 0L
                )
            )
        )
        dao.insertGstTransactions(
            listOf(
                GstTransactionEntity(
                    gstTransactionId = "G2", companyId = companyId, financialYearId = fyId, voucherId = "V2",
                    voucherType = VoucherType.SALES, partyLedgerId = "LED_CUST", partyGstin = "07AAAAA2222A1Z5",
                    placeOfSupply = "07", supplyType = SupplyType.INTER_STATE, itemId = "ITEM_A", hsnSacCode = "8471",
                    quantityRaw = 1000L, taxableAmountPaise = 1000_00L, gstRatePercent = 18.0,
                    cgstPaise = 0L, sgstPaise = 0L, igstPaise = 180_00L, cessPaise = 0L,
                    direction = GstDirection.OUTPUT, lineOrder = 1, createdAt = 0L
                )
            )
        )

        val result = repository.assembleDocumentDataFromVoucher(companyId, "V2")
        assertTrue(result is AccountingResult.Success)
        val data = (result as AccountingResult.Success).data
        val line = data.items.single()
        assertEquals(0L, line.cgst.paise)
        assertEquals(0L, line.sgst.paise)
        assertEquals(180_00L, line.igst.paise)
        assertEquals(TaxColumnMode.IGST, TaxColumnSelector.resolve(data.totals))
    }

    @Test
    fun accountOnlySale_withNoStockLines_failsGracefully() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        dao.insertVoucher(
            VoucherEntity(
                voucherId = "V3", companyId = companyId, financialYearId = fyId, voucherNumber = "SI-0003",
                voucherType = VoucherType.SALES, date = "2026-04-12", referenceNumber = "", narration = "",
                totalAmountPaise = 1180_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
                createdAt = 0L, updatedAt = 0L, createdBy = "TEST", partyGstin = "", isGstApplicable = true
            )
        )
        val result = repository.assembleDocumentDataFromVoucher(companyId, "V3")
        assertTrue("An Account-Only Sale (no stock lines) must fail gracefully, never crash or fabricate items", result is AccountingResult.Failure)
    }

    @Test
    fun nonTradingVoucher_isRejected() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val repository = AccountingRepository(dao, db = null)
        dao.insertVoucher(
            VoucherEntity(
                voucherId = "V4", companyId = companyId, financialYearId = fyId, voucherNumber = "JV-0001",
                voucherType = VoucherType.JOURNAL, date = "2026-04-12", referenceNumber = "", narration = "",
                totalAmountPaise = 100_00L, isPosted = true, isCancelled = false, syncState = SyncState.SYNCED,
                createdAt = 0L, updatedAt = 0L, createdBy = "TEST", partyGstin = "", isGstApplicable = false
            )
        )
        val result = repository.assembleDocumentDataFromVoucher(companyId, "V4")
        assertTrue(result is AccountingResult.Failure)
    }
}
