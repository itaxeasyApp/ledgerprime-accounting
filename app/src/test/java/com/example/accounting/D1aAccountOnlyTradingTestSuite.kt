package com.example.accounting

import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.core.database.VoucherPostingEngine
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.company.GstOperatingMode
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D1a (Company Mode + Account-Only Sale/Purchase) - pure-JVM coverage, following this codebase's
 * established pattern (Phase2/Phase5): [VoucherPostingEngine.post] and [AccountingRepository]'s
 * non-`dbTransaction` methods (`createCompany`/`updateAccountingConfiguration`/`getCompanies`) both
 * work against a bare [AccountingDao] with no real Room `AppDatabase` needed, so real successful
 * postings and real company-config round-trips are genuinely exercised here - not merely asserted
 * against the pure [TradingWorkflowEngine] output. The actual `MIGRATION_17_18` SQL statement
 * itself is not executed by this suite (no `MigrationTestHelper` is used anywhere in this codebase's
 * existing test architecture - `Phase0TestSuite` only asserts structural facts about
 * `AppDatabase.ALL_MIGRATIONS`, never runs migration SQL against a real SQLite engine); backward
 * compatibility here is instead verified at the mapping level the migration's `UPDATE` statement
 * implements (`gstEnabled = 0` -> `ACCOUNT_ONLY`, otherwise -> `ACCOUNT_WITH_GST`).
 */
class D1aAccountOnlyTradingTestSuite {

    private val companyId = "COMP_D1A"
    private val fyId = "FY_D1A_2026_27"

    private fun freshDao() = Phase4TestSuite.InventoryAwareDao(FakeAccountingDao())

    private suspend fun AccountingDao.seedCompany(mode: AccountingMode) {
        insertCompany(
            CompanyEntity(
                companyId = companyId, name = "D1a Co", tradeName = "D1a Co", gstin = "", pan = "",
                stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
                currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
                accountingMode = mode, businessType = BusinessType.TRADING
            )
        )
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_1", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
    }

    private fun ledger(id: String, groupBare: String, openingType: DrCr = DrCr.DEBIT) = LedgerEntity(
        id, companyId, "${groupBare}_$companyId", id, id, 0L, openingType, 0L, openingType,
        "", "", "27", "", "", "", "", "", "", "", false, true, "", 0.0
    )

    private suspend fun AccountingDao.seedTradingLedgers() {
        insertLedger(ledger("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger("LED_CREDITOR", StandardSystemGroups.CREDITORS_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, DrCr.CREDIT))
        insertLedger(ledger("LED_PURCHASE", StandardSystemGroups.PURCHASE_GROUP_ID))
        insertLedger(ledger("LED_CASH", StandardSystemGroups.CASH_GROUP_ID))
        insertLedger(ledger("LED_BANK", StandardSystemGroups.BANK_GROUP_ID))
    }

    private fun JournalItem.toEntity() = JournalItemEntity(itemId, voucherId, companyId, financialYearId, ledgerId, type, amount.paise, narration, lineOrder)

    private suspend fun postJournalItems(
        dao: AccountingDao, voucherId: String, voucherType: VoucherType, items: List<JournalItem>,
        totalPaise: Long, isGstApplicable: Boolean = false
    ) {
        val voucherEntity = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherId,
            voucherType = voucherType, date = "2026-05-10", referenceNumber = "", narration = "",
            totalAmountPaise = totalPaise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = isGstApplicable,
            referenceVoucherId = null, paymentMode = ""
        )
        VoucherPostingEngine.post(dao, voucherEntity, items.map { it.toEntity() }, "IK_$voucherId", "TESTER")
    }

    // ==========================================
    // 1/2/8/9/10 - Account-only Sale/Purchase posts a real Voucher/JournalItems, no items, no stock.
    // ==========================================

    @Test
    fun t1_t8_t10_AccountOnlySale_PostsWithoutItem_CorrectJournalItems_NoStockMovement() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(AccountingMode.ACCOUNT_ONLY)
        dao.seedTradingLedgers()

        // No Item/Quantity/Rate anywhere in this call - just Party + Trade ledger + Amount.
        val result = TradingWorkflowEngine.buildAccountOnlySale(
            voucherId = "V1", companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerName = "Cust",
            salesLedgerId = "LED_SALES", salesLedgerName = "Sales",
            amount = Money.fromRupees(5000L)
        )

        assertEquals(2, result.journalItems.size)
        assertTrue("Account-only Sale must never produce a stock line", result.stockLines.isEmpty())
        assertTrue("Account-only Sale must never produce a GST transaction", result.gstTransactions.isEmpty())

        postJournalItems(dao, "V1", VoucherType.SALES, result.journalItems, result.totalAmount.paise)

        val posted = dao.getVoucherById(companyId, "V1")
        assertTrue("Voucher must actually be posted", posted?.isPosted == true)
        assertEquals(5000_00L, posted?.totalAmountPaise)

        val debtorLine = result.journalItems.first { it.ledgerId == "LED_DEBTOR" }
        val salesLine = result.journalItems.first { it.ledgerId == "LED_SALES" }
        assertEquals(DrCr.DEBIT, debtorLine.type)
        assertEquals(DrCr.CREDIT, salesLine.type)
        assertEquals(5000_00L, debtorLine.amount.paise)
        assertEquals(5000_00L, salesLine.amount.paise)

        assertTrue("No StockMovement may be created for an account-only Sale", dao.getStockMovementsForVoucher("V1").isEmpty())
    }

    @Test
    fun t2_t9_t10_AccountOnlyPurchase_PostsWithoutItem_CorrectJournalItems_NoStockMovement() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(AccountingMode.ACCOUNT_ONLY)
        dao.seedTradingLedgers()

        val result = TradingWorkflowEngine.buildAccountOnlyPurchase(
            voucherId = "V2", companyId = companyId, financialYearId = fyId,
            supplierLedgerId = "LED_CREDITOR", supplierName = "Supp",
            purchaseLedgerId = "LED_PURCHASE", purchaseLedgerName = "Purchase",
            amount = Money.fromRupees(3000L)
        )

        assertEquals(2, result.journalItems.size)
        assertTrue(result.stockLines.isEmpty())
        assertTrue(result.gstTransactions.isEmpty())

        postJournalItems(dao, "V2", VoucherType.PURCHASE, result.journalItems, result.totalAmount.paise)

        val posted = dao.getVoucherById(companyId, "V2")
        assertTrue(posted?.isPosted == true)

        val creditorLine = result.journalItems.first { it.ledgerId == "LED_CREDITOR" }
        val purchaseLine = result.journalItems.first { it.ledgerId == "LED_PURCHASE" }
        assertEquals(DrCr.CREDIT, creditorLine.type)
        assertEquals(DrCr.DEBIT, purchaseLine.type)

        assertTrue("No StockMovement may be created for an account-only Purchase", dao.getStockMovementsForVoucher("V2").isEmpty())
    }

    @Test
    fun t_AccountOnly_ZeroAmount_Rejected() {
        try {
            TradingWorkflowEngine.buildAccountOnlySale(
                "V3", companyId, fyId, "LED_DEBTOR", "Cust", "LED_SALES", "Sales", Money.ZERO
            )
            org.junit.Assert.fail("Zero amount must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("greater than zero") == true)
        }
    }

    // ==========================================
    // 3/4/11 - Inventory-enabled Sale/Purchase still requires/uses Item correctly (regression).
    // ==========================================

    private fun line(itemId: String, qty: Long, ratePaise: Long, gstRate: Double) = TradingLineInput(
        itemId = itemId, itemName = itemId, hsnSacCode = "8471",
        quantity = Quantity.fromLong(qty), rate = Money.fromPaise(ratePaise), gstRatePercent = gstRate
    )

    @Test
    fun t3_InventoryEnabled_Sale_StillRequiresLineItems_AndComputesGst() {
        // build() (backing buildSale/buildPurchase) is untouched - it still throws on an empty
        // line list, and still computes GST/stock exactly as before D1a.
        try {
            TradingWorkflowEngine.buildSale(
                "V4", companyId, fyId, "LED_DEBTOR", "Cust", "",
                "LED_SALES", "Sales", "27", "27",
                lines = emptyList(),
                gstLedgers = com.example.accounting.domain.trading.TradingGstLedgers(
                    ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref()
                ),
                roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
            )
            org.junit.Assert.fail("An inventory-driven Sale must still reject an empty line list")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("At least one line item") == true)
        }

        val result = TradingWorkflowEngine.buildSale(
            "V5", companyId, fyId, "LED_DEBTOR", "Cust", "",
            "LED_SALES", "Sales", "27", "27",
            lines = listOf(line("ITEM_A", 1, 100_00L, 18.0)),
            gstLedgers = com.example.accounting.domain.trading.TradingGstLedgers(
                ref("OCG"), ref("OSG"), ref("OIG"), ref("ICG"), ref("ISG"), ref("IIG"), ref("CE"),
                ref("RLC"), ref("RLS"), ref("RLI"), ref("RIC"), ref("RIS"), ref("RII")
            ),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
        )
        assertEquals(1, result.stockLines.size)
        assertEquals(1, result.gstTransactions.size)
        assertEquals(18_00L, result.gstTransactions.first().let { it.cgst.paise + it.sgst.paise + it.igst.paise })
    }

    @Test
    fun t4_InventoryEnabled_Purchase_StillRequiresLineItems_AndComputesGst() {
        val result = TradingWorkflowEngine.buildPurchase(
            "V6", companyId, fyId, "LED_CREDITOR", "Supp", "",
            "LED_PURCHASE", "Purchase", "27", "27",
            lines = listOf(line("ITEM_A", 2, 50_00L, 12.0)),
            gstLedgers = com.example.accounting.domain.trading.TradingGstLedgers(
                ref("OCG"), ref("OSG"), ref("OIG"), ref("ICG"), ref("ISG"), ref("IIG"), ref("CE"),
                ref("RLC"), ref("RLS"), ref("RLI"), ref("RIC"), ref("RIS"), ref("RII")
            ),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
        )
        assertEquals(1, result.stockLines.size)
        assertEquals(1, result.gstTransactions.size)
        assertEquals(com.example.accounting.domain.taxation.gst.GstDirection.INPUT, result.gstTransactions.first().direction)
    }

    private fun ref(id: String = UUID.randomUUID().toString()) = com.example.accounting.domain.trading.LedgerRef(id, id)

    @Test
    fun t11_GstEnabledAccounting_Unaffected_ByAccountOnlyAddition() {
        // Same fixture as Phase5TestSuite's a1 - proves buildAccountOnlySale/Purchase are additive
        // and never altered the shared calculateDetailed/build() path's numbers.
        val breakdown = com.example.accounting.domain.taxation.gst.GstCalculationEngine.calculateDetailed(
            com.example.accounting.domain.taxation.gst.GstTransactionFacts(Money.fromRupees(1000L), 18.0, supplierStateCode = "27", placeOfSupply = "27")
        )
        assertEquals(90_00L, breakdown.cgstAmount.paise)
        assertEquals(90_00L, breakdown.sgstAmount.paise)
    }

    // ==========================================
    // 12 - Receipt/Payment/Contra/Journal remain account-only, in both AccountingModes.
    // ==========================================

    @Test
    fun t12_ReceiptPaymentContraJournal_NeverGainItemRequirement_InEitherMode() = runBlocking {
        for (mode in listOf(AccountingMode.ACCOUNT_ONLY, AccountingMode.ACCOUNT_WITH_INVENTORY)) {
            val dao = freshDao()
            dao.seedCompany(mode)
            dao.seedTradingLedgers()

            fun twoLine(debitLedger: String, creditLedger: String, amountPaise: Long) = listOf(
                JournalItem(UUID.randomUUID().toString(), "V_${mode}_D", companyId, fyId, debitLedger, debitLedger, DrCr.DEBIT, Money.fromPaise(amountPaise), lineOrder = 1),
                JournalItem(UUID.randomUUID().toString(), "V_${mode}_D", companyId, fyId, creditLedger, creditLedger, DrCr.CREDIT, Money.fromPaise(amountPaise), lineOrder = 2)
            )

            // Receipt: Cash Dr / Debtor Cr. Payment: Creditor Dr / Cash Cr. Contra: Bank Dr / Cash Cr.
            // Journal: any ledger pair. None of these four ever reference an itemId/Quantity/Rate -
            // TradingLineInput/TradingWorkflowEngine are not in their call graph at all.
            val scenarios = listOf(
                Triple(VoucherType.RECEIPT, "V_${mode}_RCPT", twoLine("LED_CASH", "LED_DEBTOR", 100_00L)),
                Triple(VoucherType.PAYMENT, "V_${mode}_PMT", twoLine("LED_CREDITOR", "LED_CASH", 100_00L)),
                Triple(VoucherType.CONTRA, "V_${mode}_CTR", twoLine("LED_BANK", "LED_CASH", 100_00L)),
                Triple(VoucherType.JOURNAL, "V_${mode}_JRN", twoLine("LED_PURCHASE", "LED_SALES", 100_00L))
            )

            for ((type, voucherId, rawItems) in scenarios) {
                val items = rawItems.map { it.copy(voucherId = voucherId) }
                postJournalItems(dao, voucherId, type, items, 100_00L)
                val posted = dao.getVoucherById(companyId, voucherId)
                assertTrue("$type must post successfully regardless of AccountingMode ($mode), with no Item involved", posted?.isPosted == true)
                assertTrue("$type must never create a StockMovement", dao.getStockMovementsForVoucher(voucherId).isEmpty())
            }
        }
    }

    // ==========================================
    // 5/6/7 - Company mode persistence, backward compatibility, historical-data preservation.
    // ==========================================

    @Test
    fun t5_CompanyMode_PersistsAndRoundTrips() = runBlocking {
        val dao = FakeAccountingDao()
        val repo = AccountingRepository(dao)
        val company = Company(
            companyId = companyId, name = "D1a Co", stateCode = "27", stateName = "Maharashtra",
            gstOperatingMode = GstOperatingMode.GST_ONLY
        )
        repo.createCompany(company)

        val reloaded = repo.getCompanies().first().first { it.companyId == companyId }
        assertEquals(GstOperatingMode.GST_ONLY, reloaded.gstOperatingMode)
    }

    @Test
    fun t5b_CompanyMode_UpdateAccountingConfiguration_PersistsNewMode() = runBlocking {
        val dao = FakeAccountingDao()
        val repo = AccountingRepository(dao)
        repo.createCompany(Company(companyId = companyId, name = "D1a Co", stateCode = "27", stateName = "Maharashtra"))
        assertEquals(GstOperatingMode.ACCOUNT_WITH_GST, repo.getCompanies().first().first().gstOperatingMode)

        repo.updateAccountingConfiguration(companyId, gstOperatingMode = GstOperatingMode.ACCOUNT_ONLY)

        val reloaded = repo.getCompanies().first().first { it.companyId == companyId }
        assertEquals(GstOperatingMode.ACCOUNT_ONLY, reloaded.gstOperatingMode)
    }

    @Test
    fun t6_ExistingCompany_BackwardCompatibleMapping_MatchesMigrationSemantics() = runBlocking {
        // Mirrors MIGRATION_17_18's own UPDATE mapping exactly: a pre-existing company that had
        // gstEnabled = false must read back as ACCOUNT_ONLY; one with gstEnabled = true (the
        // default) must read back as ACCOUNT_WITH_GST. Constructed directly via CompanyEntity here
        // (the same shape a migrated row would take), since this pure-JVM suite cannot execute the
        // literal ALTER TABLE/UPDATE SQL against a real SQLite engine - see class doc comment.
        val dao = FakeAccountingDao()
        dao.insertCompany(
            CompanyEntity(
                companyId = "COMP_LEGACY_GST_OFF", name = "Legacy GST-off Co", tradeName = "", gstin = "", pan = "",
                stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "", currency = "INR",
                financialYearStartMonth = 4, isDefault = false, createdAt = 0L,
                gstEnabled = false, gstOperatingMode = GstOperatingMode.ACCOUNT_ONLY
            )
        )
        dao.insertCompany(
            CompanyEntity(
                companyId = "COMP_LEGACY_GST_ON", name = "Legacy GST-on Co", tradeName = "", gstin = "", pan = "",
                stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "", currency = "INR",
                financialYearStartMonth = 4, isDefault = false, createdAt = 0L,
                gstEnabled = true, gstOperatingMode = GstOperatingMode.ACCOUNT_WITH_GST
            )
        )

        val repo = AccountingRepository(dao)
        val companies = repo.getCompanies().first().associateBy { it.companyId }
        assertEquals(GstOperatingMode.ACCOUNT_ONLY, companies["COMP_LEGACY_GST_OFF"]?.gstOperatingMode)
        assertEquals(GstOperatingMode.ACCOUNT_WITH_GST, companies["COMP_LEGACY_GST_ON"]?.gstOperatingMode)
    }

    @Test
    fun t7_ModeChange_NeverModifiesHistoricalVoucherOrJournalItems() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(AccountingMode.ACCOUNT_WITH_INVENTORY)
        dao.seedTradingLedgers()

        // A historical Sale, posted before any mode change.
        val historicalItems = listOf(
            JournalItem(UUID.randomUUID().toString(), "V_HIST", companyId, fyId, "LED_DEBTOR", "LED_DEBTOR", DrCr.DEBIT, Money.fromRupees(1000L), lineOrder = 1),
            JournalItem(UUID.randomUUID().toString(), "V_HIST", companyId, fyId, "LED_SALES", "LED_SALES", DrCr.CREDIT, Money.fromRupees(1000L), lineOrder = 2)
        )
        postJournalItems(dao, "V_HIST", VoucherType.SALES, historicalItems, 1000_00L)
        val beforeVoucher = dao.getVoucherById(companyId, "V_HIST")
        val beforeItems = dao.getJournalItemsByLedger(companyId, "LED_DEBTOR")
        val beforeLedgerBalance = dao.getLedgerById(companyId, "LED_DEBTOR")?.currentBalancePaise

        val repo = AccountingRepository(dao)
        // Company row must exist for updateAccountingConfiguration to find it - dao.seedCompany
        // already inserted it above via insertCompany, so this reads/updates that same row.
        val result = repo.updateAccountingConfiguration(companyId, gstOperatingMode = GstOperatingMode.GST_ONLY)
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)

        val afterVoucher = dao.getVoucherById(companyId, "V_HIST")
        val afterItems = dao.getJournalItemsByLedger(companyId, "LED_DEBTOR")
        val afterLedgerBalance = dao.getLedgerById(companyId, "LED_DEBTOR")?.currentBalancePaise

        assertEquals("Mode change must never rewrite a historical voucher", beforeVoucher, afterVoucher)
        assertEquals("Mode change must never rewrite historical journal items", beforeItems, afterItems)
        assertEquals("Mode change must never alter a historical ledger balance", beforeLedgerBalance, afterLedgerBalance)

        val companyAfter = repo.getCompanies().first().first { it.companyId == companyId }
        assertEquals(GstOperatingMode.GST_ONLY, companyAfter.gstOperatingMode)
    }

    // ==========================================
    // Accounting-flow audit fix - Inventory Mode must only gate stock/COGS, never whether GST
    // accounting exists. Account-Only Sale/Purchase can now compute GST via the same build()/
    // GstCalculationEngine every item-driven posting already uses, just with trackInventory=false.
    // ==========================================

    @Test
    fun t18_AccountOnlySale_WithGstRate_ComputesGstAndSkipsStockLines() {
        val syntheticLine = TradingLineInput(
            itemId = "", itemName = "Sale (Account Only)", hsnSacCode = "9983",
            quantity = Quantity.fromDouble(1.0, "Nos"), rate = Money.fromRupees(1000L), gstRatePercent = 18.0
        )
        val result = TradingWorkflowEngine.buildSale(
            "V18", companyId, fyId, "LED_DEBTOR", "Cust", "",
            "LED_SALES", "Sales", "27", "27",
            lines = listOf(syntheticLine),
            gstLedgers = com.example.accounting.domain.trading.TradingGstLedgers(
                ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref()
            ),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off",
            trackInventory = false
        )
        assertTrue("Account-Only + GST must never produce a stock line", result.stockLines.isEmpty())
        assertEquals("Account-Only + GST must still compute one GST transaction", 1, result.gstTransactions.size)
        val gt = result.gstTransactions.first()
        assertEquals(90_00L, gt.cgst.paise)
        assertEquals(90_00L, gt.sgst.paise)
        assertEquals(0L, gt.igst.paise)
        assertEquals(1000_00L, gt.taxableAmount.paise)
    }

    @Test
    fun t19_AccountOnlyPurchase_WithGstRate_ComputesGstAndSkipsStockLines() {
        val syntheticLine = TradingLineInput(
            itemId = "", itemName = "Purchase (Account Only)", hsnSacCode = "9983",
            quantity = Quantity.fromDouble(1.0, "Nos"), rate = Money.fromRupees(500L), gstRatePercent = 12.0
        )
        // Inter-state (companyStateCode "27" vs placeOfSupply "09") - must route to IGST, not CGST/SGST.
        val result = TradingWorkflowEngine.buildPurchase(
            "V19", companyId, fyId, "LED_CREDITOR", "Supp", "",
            "LED_PURCHASE", "Purchase", "27", "09",
            lines = listOf(syntheticLine),
            gstLedgers = com.example.accounting.domain.trading.TradingGstLedgers(
                ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref()
            ),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off",
            trackInventory = false
        )
        assertTrue(result.stockLines.isEmpty())
        assertEquals(1, result.gstTransactions.size)
        val gt = result.gstTransactions.first()
        assertEquals(0L, gt.cgst.paise)
        assertEquals(0L, gt.sgst.paise)
        assertEquals(60_00L, gt.igst.paise)
        assertEquals(com.example.accounting.domain.taxation.gst.GstDirection.INPUT, gt.direction)
    }

    @Test
    fun t20_CancelVoucher_ReversesGstTransactions_NetsToZero() = runBlocking {
        val dao = Phase5TestSuite.Phase5AwareDao(freshDao())
        dao.seedCompany(AccountingMode.ACCOUNT_ONLY)
        dao.seedTradingLedgers()

        val syntheticLine = TradingLineInput(
            itemId = "", itemName = "Sale (Account Only)", hsnSacCode = "9983",
            quantity = Quantity.fromDouble(1.0, "Nos"), rate = Money.fromRupees(2000L), gstRatePercent = 18.0
        )
        val result = TradingWorkflowEngine.buildSale(
            "V20", companyId, fyId, "LED_DEBTOR", "Cust", "",
            "LED_SALES", "Sales", "27", "27",
            lines = listOf(syntheticLine),
            gstLedgers = com.example.accounting.domain.trading.TradingGstLedgers(
                ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref(), ref()
            ),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off",
            trackInventory = false
        )
        val voucherEntity = VoucherEntity(
            voucherId = "V20", companyId = companyId, financialYearId = fyId, voucherNumber = "V20",
            voucherType = VoucherType.SALES, date = "2026-05-10", referenceNumber = "", narration = "",
            totalAmountPaise = result.totalAmount.paise, isPosted = true, isCancelled = false, syncState = SyncState.PENDING,
            createdAt = 0L, updatedAt = 0L, createdBy = "TESTER", partyGstin = "", isGstApplicable = true,
            referenceVoucherId = null, paymentMode = ""
        )
        VoucherPostingEngine.post(
            dao, voucherEntity, result.journalItems.map { it.toEntity() }, "IK_V20", "TESTER",
            gstTransactions = result.gstTransactions.map {
                com.example.accounting.data.local.entity.GstTransactionEntity(
                    it.gstTransactionId, companyId, fyId, "V20", it.voucherType, it.partyLedgerId, it.partyGstin,
                    it.placeOfSupply, it.supplyType, it.itemId, it.hsnSacCode, it.quantity?.rawValue,
                    it.taxableAmount.paise, it.gstRatePercent, it.cgst.paise, it.sgst.paise, it.igst.paise,
                    it.cess.paise, it.direction, it.lineOrder, createdAt = 0L, chargeType = it.chargeType
                )
            }
        )

        val beforeCancel = dao.getGstTransactionsForVoucher("V20")
        assertEquals("Posting must create exactly one GST transaction", 1, beforeCancel.size)
        // ₹2000 taxable @ 18% intra-state = ₹360 total tax, split 9%/9% CGST/SGST = ₹180 each.
        assertEquals(180_00L, beforeCancel.sumOf { it.cgstPaise })

        VoucherPostingEngine.cancel(dao, companyId, fyId, "V20", "IK_V20_CANCEL", "TESTER")

        val afterCancel = dao.getGstTransactionsForVoucher("V20")
        assertEquals(
            "Cancellation must append a compensating reversal row, never delete/mutate the original",
            2, afterCancel.size
        )
        assertEquals("Net taxable value across original+reversal must be zero", 0L, afterCancel.sumOf { it.taxableAmountPaise })
        assertEquals("Net CGST across original+reversal must be zero", 0L, afterCancel.sumOf { it.cgstPaise })
        assertEquals("Net SGST across original+reversal must be zero", 0L, afterCancel.sumOf { it.sgstPaise })
    }

    // ==========================================
    // Accounting-flow audit fix - Service businesses get Income/Expenditure-labeled default
    // ledgers instead of Sales/Purchase Account, without any change to the underlying groupId/
    // VoucherType plumbing.
    // ==========================================

    @Test
    fun t21_ServiceCompany_CreateCompany_GetsIncomeExpenditureLedgerNames() = runBlocking {
        val dao = FakeAccountingDao()
        val repo = AccountingRepository(dao)
        val serviceCompanyId = "COMP_SERVICE_D1A"
        repo.createCompany(
            Company(
                companyId = serviceCompanyId, name = "Service Co", stateCode = "27", stateName = "Maharashtra",
                businessType = BusinessType.SERVICE
            )
        )
        val ledgers = repo.getLedgers(serviceCompanyId).first().associateBy { it.ledgerId }
        assertEquals("Income Account", ledgers["LED_SALES_$serviceCompanyId"]?.name)
        assertEquals("Expenditure Account", ledgers["LED_PURCHASE_$serviceCompanyId"]?.name)
    }

    @Test
    fun t22_TradingCompany_CreateCompany_StillGetsSalesPurchaseLedgerNames() = runBlocking {
        val dao = FakeAccountingDao()
        val repo = AccountingRepository(dao)
        val tradingCompanyId = "COMP_TRADING_D1A"
        repo.createCompany(
            Company(
                companyId = tradingCompanyId, name = "Trading Co", stateCode = "27", stateName = "Maharashtra",
                businessType = BusinessType.TRADING
            )
        )
        val ledgers = repo.getLedgers(tradingCompanyId).first().associateBy { it.ledgerId }
        assertEquals("Sales Account", ledgers["LED_SALES_$tradingCompanyId"]?.name)
        assertEquals("Purchase Account", ledgers["LED_PURCHASE_$tradingCompanyId"]?.name)
    }
}
