package com.example.accounting

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
import com.example.accounting.data.local.entity.GstFilingPeriodEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.PartyEntity
import com.example.accounting.data.local.entity.SettlementAllocationEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.VoucherStockLine
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.party.PartyValidation
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstLedgerIds
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.GstTransactionFacts
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.trading.LedgerRef
import com.example.accounting.domain.trading.TradingGstLedgers
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import com.example.accounting.domain.trading.TradingWorkflowResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 5 - GST & Statutory Accounting Engine test suite (full scope, per the user's 5.21
 * checklist). Pure JVM, following the exact Phase 2/4 pattern: [VoucherPostingEngine] is exercised
 * directly against a fake DAO (bypassing [AccountingRepository.postVoucher], which needs a real
 * Room `AppDatabase` for its atomic transaction and so is only usable here for its `db == null`
 * read/validation-only paths - generateGSTSummary, ensureGstLedgersExist, allocateSettlement,
 * updateLedger/deleteLedgerSafely protection checks).
 *
 * [Phase5AwareDao] wraps [Phase4TestSuite.InventoryAwareDao] (real stock backing, reused rather
 * than duplicated) and adds real in-memory backing for gst_transactions/settlement_allocations/
 * gst_filing_periods - [FakeAccountingDao] (Phase0TestSuite.kt) stubs all of these as no-ops,
 * harmless for Phase 0-4 (which never exercise them) but necessary here.
 */
class Phase5TestSuite {

    /** D1b - made non-private so [D1bGstOnlyTestSuite] can reuse this same real GST-transaction-
     * tracking DAO wrapper instead of duplicating it (this project's "reuse over duplication" rule) -
     * zero behavior change, visibility only. */
    class Phase5AwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val gstTransactions = mutableListOf<GstTransactionEntity>()
        private val allocations = mutableListOf<SettlementAllocationEntity>()
        private val filingPeriods = LinkedHashMap<String, GstFilingPeriodEntity>()
        // Rule 30 (Party/Customer/Supplier Data Validation) - [FakeAccountingDao] stubs every
        // Party method as a no-op/empty-return, harmless where no prior Phase exercised Party
        // creation but not here - same rationale as gstTransactions/allocations/filingPeriods above.
        private val parties = LinkedHashMap<String, PartyEntity>()

        override suspend fun getGstTransactionsForVoucher(voucherId: String) = gstTransactions.filter { it.voucherId == voucherId }
        override suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String) =
            gstTransactions.filter { it.companyId == companyId && it.financialYearId == fyId }
        // D1b - the only way to find a GST-only transaction's lines (voucherId is always null there).
        override suspend fun getGstTransactionsByGroupId(companyId: String, groupId: String) =
            gstTransactions.filter { it.companyId == companyId && it.transactionGroupId == groupId }.sortedBy { it.lineOrder }
        override suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>) { gstTransactions += transactions }
        override suspend fun deleteGstTransactionsByVoucher(voucherId: String) { gstTransactions.removeAll { it.voucherId == voucherId } }

        override suspend fun getAllocationsForInvoice(invoiceVoucherId: String) = allocations.filter { it.invoiceVoucherId == invoiceVoucherId }
        override suspend fun getAllocationsForSettlement(settlementVoucherId: String) = allocations.filter { it.settlementVoucherId == settlementVoucherId }
        override suspend fun insertSettlementAllocations(rows: List<SettlementAllocationEntity>) { allocations += rows }

        override fun getGstFilingPeriodsByCompany(companyId: String): Flow<List<GstFilingPeriodEntity>> = flowOf(filingPeriods.values.filter { it.companyId == companyId })
        override suspend fun insertGstFilingPeriod(period: GstFilingPeriodEntity) { filingPeriods[period.filingPeriodId] = period }
        override suspend fun setGstFilingPeriodLock(companyId: String, filingPeriodId: String, isLocked: Boolean, lockedAt: Long?, lockedBy: String?) {
            filingPeriods[filingPeriodId]?.let { filingPeriods[filingPeriodId] = it.copy(isLocked = isLocked, lockedAt = lockedAt, lockedBy = lockedBy) }
        }

        override fun getPartiesByCompany(companyId: String) = flowOf(parties.values.filter { it.companyId == companyId })
        override fun getPartiesByRole(companyId: String, role: PartyRole) = flowOf(parties.values.filter { it.companyId == companyId && it.role == role })
        override suspend fun getPartyById(companyId: String, partyId: String) = parties.values.firstOrNull { it.companyId == companyId && it.partyId == partyId }
        override suspend fun getPartyByLedgerId(companyId: String, ledgerId: String) = parties.values.firstOrNull { it.companyId == companyId && it.ledgerId == ledgerId }
        override suspend fun insertParty(party: PartyEntity) { parties[party.partyId] = party }
        override suspend fun updateParty(party: PartyEntity) { parties[party.partyId] = party }
    }

    private val companyId = "COMP_P5"
    private val fyId = "FY_P5_2026_27"

    private fun freshDao() = Phase5AwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private suspend fun AccountingDao.seedCompany(company: String = companyId, financialYearId: String = fyId) {
        insertCompany(CompanyEntity(
            companyId = company, name = "Company $company", tradeName = "Company $company", gstin = "27AAAAA0000A1Z5",
            pan = "AAAAA0000A", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
            currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
            accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY, businessType = BusinessType.TRADING
        ))
        insertFinancialYear(FinancialYearEntity(financialYearId, company, "FY 2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_${company}_1", company, financialYearId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(company).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
    }

    private fun ledger(id: String, company: String, groupBare: String, openingPaise: Long = 0L, openingType: DrCr = DrCr.DEBIT, stateCode: String = "27") =
        LedgerEntity(id, company, "${groupBare}_$company", id, id, openingPaise, openingType, openingPaise, openingType, "", "", stateCode, "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun AccountingDao.seedTradingLedgers(company: String = companyId) {
        insertLedger(ledger("LED_BANK", company, StandardSystemGroups.BANK_GROUP_ID, 1_00_00_000_00L))
        insertLedger(ledger("LED_CASH", company, StandardSystemGroups.CASH_GROUP_ID, 5_00_000_00L))
        insertLedger(ledger("LED_DEBTOR", company, StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger("LED_DEBTOR_INTERSTATE", company, StandardSystemGroups.DEBTORS_GROUP_ID, stateCode = "09"))
        insertLedger(ledger("LED_CREDITOR", company, StandardSystemGroups.CREDITORS_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_SALES", company, StandardSystemGroups.SALES_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_PURCHASE", company, StandardSystemGroups.PURCHASE_GROUP_ID))
        insertLedger(ledger("LED_CAPITAL", company, StandardSystemGroups.CAPITAL_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger(StandardSystemGroups.ROUND_OFF_LEDGER_ID + "_$company", company, StandardSystemGroups.ROUND_OFF_GROUP_ID))
    }

    private fun stockItem(itemId: String, company: String = companyId, gstRate: Double = 18.0, hsn: String = "8471", openingQty: Long = 0L) = StockItemEntity(
        itemId = itemId, companyId = company, name = itemId, sku = itemId, hsnCode = hsn, unit = "Pcs", gstRatePercent = gstRate,
        openingQuantity = openingQty, openingRatePaise = 100_00L, currentQuantity = openingQty, standardCostPaise = 100_00L, standardSellingPricePaise = 100_00L, currentAvgCostPaise = 100_00L
    )

    private fun gstLedgerRefs(company: String) = TradingGstLedgers(
        outputCgst = LedgerRef("${GstLedgerIds.OUTPUT_CGST_LEDGER_ID}_$company", "Output CGST A/c"),
        outputSgst = LedgerRef("${GstLedgerIds.OUTPUT_SGST_LEDGER_ID}_$company", "Output SGST A/c"),
        outputIgst = LedgerRef("${GstLedgerIds.OUTPUT_IGST_LEDGER_ID}_$company", "Output IGST A/c"),
        inputCgst = LedgerRef("${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$company", "Input CGST A/c"),
        inputSgst = LedgerRef("${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$company", "Input SGST A/c"),
        inputIgst = LedgerRef("${GstLedgerIds.INPUT_IGST_LEDGER_ID}_$company", "Input IGST A/c"),
        cess = LedgerRef("${GstLedgerIds.CESS_LEDGER_ID}_$company", "CESS A/c"),
        rcmLiabilityCgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID}_$company", "RCM Liability CGST A/c"),
        rcmLiabilitySgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_SGST_LEDGER_ID}_$company", "RCM Liability SGST A/c"),
        rcmLiabilityIgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_IGST_LEDGER_ID}_$company", "RCM Liability IGST A/c"),
        rcmInputCgst = LedgerRef("${GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID}_$company", "RCM Input CGST A/c"),
        rcmInputSgst = LedgerRef("${GstLedgerIds.RCM_INPUT_SGST_LEDGER_ID}_$company", "RCM Input SGST A/c"),
        rcmInputIgst = LedgerRef("${GstLedgerIds.RCM_INPUT_IGST_LEDGER_ID}_$company", "RCM Input IGST A/c")
    )

    private fun JournalItem.toEntity() = JournalItemEntity(itemId, voucherId, companyId, financialYearId, ledgerId, type, amount.paise, narration, lineOrder)
    private fun VoucherStockLine.toEntity() = VoucherStockLineEntity(lineId, voucherId, companyId, financialYearId, itemId, direction, quantity.rawValue, rate.paise, amount.paise, lineOrder)
    private fun GstTransaction.toEntity() = GstTransactionEntity(
        gstTransactionId, companyId, financialYearId, voucherId, voucherType, partyLedgerId, partyGstin, placeOfSupply,
        supplyType, itemId, hsnSacCode, quantity?.rawValue, taxableAmount.paise, gstRatePercent, cgst.paise, sgst.paise,
        igst.paise, cess.paise, direction, lineOrder, createdAt = 0L, chargeType = chargeType
    )

    private suspend fun postResult(
        dao: AccountingDao, voucherId: String, voucherType: VoucherType, result: TradingWorkflowResult,
        company: String = companyId, fy: String = fyId, refVoucherId: String? = null
    ) {
        val voucherEntity = VoucherEntity(
            voucherId = voucherId, companyId = company, financialYearId = fy, voucherNumber = voucherId, voucherType = voucherType,
            date = "2026-05-10", referenceNumber = "", narration = "", totalAmountPaise = result.totalAmount.paise,
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "TESTER", partyGstin = "", isGstApplicable = true, referenceVoucherId = refVoucherId, paymentMode = ""
        )
        VoucherPostingEngine.post(
            dao, voucherEntity, result.journalItems.map { it.toEntity() }, "IK_$voucherId", "TESTER",
            result.stockLines.map { it.toEntity() }, result.gstTransactions.map { it.toEntity() }
        )
    }

    private fun line(itemId: String, qty: Long, ratePaise: Long, gstRate: Double, hsn: String = "8471") = TradingLineInput(
        itemId = itemId, itemName = itemId, hsnSacCode = hsn, quantity = Quantity.fromLong(qty), rate = Money.fromPaise(ratePaise), gstRatePercent = gstRate
    )

    // ==========================================
    // ARCHITECTURE CORRECTION: Real Group hierarchy (StandardSystemGroups.isUnder)
    // ==========================================
    @Test
    fun hierarchy_isUnder_RecognizesLedgerFiledDirectlyUnderSystemGroup() {
        val bankGroupId = "${StandardSystemGroups.BANK_GROUP_ID}_$companyId"
        val groupsById = mapOf(
            bankGroupId to com.example.accounting.domain.accounting.AccountGroup(bankGroupId, companyId, "Bank Accounts", com.example.accounting.domain.accounting.PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true)
        )
        assertTrue(StandardSystemGroups.isUnder(bankGroupId, StandardSystemGroups.BANK_GROUP_ID, groupsById))
    }

    @Test
    fun hierarchy_isUnder_RecognizesLedgerFiledUnderUserGroupNestedUnderSystemGroup() {
        val bankGroupId = "${StandardSystemGroups.BANK_GROUP_ID}_$companyId"
        val userGroupId = "GRP_USER_HDFC_$companyId"
        val groupsById = mapOf(
            bankGroupId to com.example.accounting.domain.accounting.AccountGroup(bankGroupId, companyId, "Bank Accounts", com.example.accounting.domain.accounting.PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true),
            userGroupId to com.example.accounting.domain.accounting.AccountGroup(userGroupId, companyId, "HDFC Current A/c", com.example.accounting.domain.accounting.PrimaryGroup.ASSETS, bankGroupId, false)
        )
        // A ledger filed under the User Group "HDFC Current A/c" (itself nested under the System
        // "Bank Accounts" group) must still be recognized as a Bank ledger via ancestor walk - this
        // is exactly the case flat groupId.startsWith("GRP_BANK_") string matching would silently
        // misclassify (the user group's own ID doesn't start with "GRP_BANK").
        assertTrue(StandardSystemGroups.isUnder(userGroupId, StandardSystemGroups.BANK_GROUP_ID, groupsById))
        assertFalse(StandardSystemGroups.isUnder(userGroupId, StandardSystemGroups.CASH_GROUP_ID, groupsById))
    }

    @Test
    fun hierarchy_isUnder_UnrelatedGroup_ReturnsFalse() {
        val loansGroupId = "${StandardSystemGroups.LOANS_GROUP_ID}_$companyId"
        val groupsById = mapOf(
            loansGroupId to com.example.accounting.domain.accounting.AccountGroup(loansGroupId, companyId, "Loans (Liability)", com.example.accounting.domain.accounting.PrimaryGroup.LIABILITIES, null, true)
        )
        assertFalse(StandardSystemGroups.isUnder(loansGroupId, StandardSystemGroups.BANK_GROUP_ID, groupsById))
    }

    // ==========================================
    // A. GST ENGINE (CESS, per-component rates, supply nature, isolation)
    // ==========================================
    @Test
    fun a1_CalculateDetailed_IntraState_SplitsCgstSgst_WithCess() {
        val breakdown = GstCalculationEngine.calculateDetailed(
            GstTransactionFacts(Money.fromRupees(1000L), 18.0, cessRatePercent = 1.0, supplierStateCode = "27", placeOfSupply = "27")
        )
        assertEquals(90_00L, breakdown.cgstAmount.paise)
        assertEquals(90_00L, breakdown.sgstAmount.paise)
        assertEquals(0L, breakdown.igstAmount.paise)
        assertEquals(10_00L, breakdown.cessAmount.paise)
        assertEquals(9.0, breakdown.cgstRatePercent, 0.0001)
        assertEquals(9.0, breakdown.sgstRatePercent, 0.0001)
        assertEquals(190_00L, breakdown.totalTax.paise)
        assertEquals(1190_00L, breakdown.totalWithTax.paise)
    }

    @Test
    fun a2_CalculateDetailed_InterState_IgstOnly() {
        val breakdown = GstCalculationEngine.calculateDetailed(
            GstTransactionFacts(Money.fromRupees(1000L), 18.0, supplierStateCode = "27", placeOfSupply = "09")
        )
        assertEquals(0L, breakdown.cgstAmount.paise)
        assertEquals(0L, breakdown.sgstAmount.paise)
        assertEquals(180_00L, breakdown.igstAmount.paise)
        assertEquals(18.0, breakdown.igstRatePercent, 0.0001)
    }

    @Test
    fun a3_CalculateDetailed_ZeroRate_NoTax() {
        val breakdown = GstCalculationEngine.calculateDetailed(
            GstTransactionFacts(Money.fromRupees(1000L), 0.0, supplierStateCode = "27", placeOfSupply = "27")
        )
        assertEquals(0L, breakdown.totalTax.paise)
        assertEquals(1000_00L, breakdown.totalWithTax.paise)
    }

    @Test
    fun a4_CalculateDetailed_Export_ZeroTax_SupplyTypeExport() {
        val breakdown = GstCalculationEngine.calculateDetailed(
            GstTransactionFacts(Money.fromRupees(1000L), 18.0, supplierStateCode = "27", placeOfSupply = "09", supplyNature = GstSupplyNature.EXPORT)
        )
        assertEquals(SupplyType.EXPORT, breakdown.supplyType)
        assertEquals(0L, breakdown.totalTax.paise)
    }

    @Test
    fun a5_CalculateDetailed_Exempt_ZeroTax_SupplyTypeExempt() {
        val breakdown = GstCalculationEngine.calculateDetailed(
            GstTransactionFacts(Money.fromRupees(1000L), 18.0, supplierStateCode = "27", placeOfSupply = "27", supplyNature = GstSupplyNature.EXEMPT)
        )
        assertEquals(SupplyType.EXEMPT, breakdown.supplyType)
        assertEquals(0L, breakdown.totalTax.paise)
    }

    @Test
    fun a6_CalculateDetailed_MultipleRates_5And28Percent() {
        val at5 = GstCalculationEngine.calculateDetailed(GstTransactionFacts(Money.fromRupees(1000L), 5.0, supplierStateCode = "27", placeOfSupply = "27"))
        val at28 = GstCalculationEngine.calculateDetailed(GstTransactionFacts(Money.fromRupees(1000L), 28.0, supplierStateCode = "27", placeOfSupply = "27"))
        assertEquals(50_00L, at5.totalTax.paise)
        assertEquals(280_00L, at28.totalTax.paise)
    }

    @Test
    fun a7_TradingWorkflow_ItemDrivenRate_NeverHardcoded() {
        val result = TradingWorkflowEngine.buildSale(
            voucherId = "V1", companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerName = "Cust", customerGstin = "",
            salesLedgerId = "LED_SALES", salesLedgerName = "Sales",
            companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_A", 10, 100_00L, gstRate = 5.0), line("ITEM_B", 5, 200_00L, gstRate = 28.0)),
            gstLedgers = gstLedgerRefs(companyId), roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
        )
        // ITEM_A: taxable 1000.00 @ 5% = 50.00 tax. ITEM_B: taxable 1000.00 @ 28% = 280.00 tax.
        val taxLines = result.journalItems.filter { it.ledgerId.startsWith("LED_GST") }
        val totalTax = taxLines.fold(Money.ZERO) { acc, i -> acc + i.amount }
        assertEquals(330_00L, totalTax.paise)
        assertEquals(2, result.gstTransactions.size)
        assertEquals(5.0, result.gstTransactions.first { it.itemId == "ITEM_A" }.gstRatePercent, 0.0001)
        assertEquals(28.0, result.gstTransactions.first { it.itemId == "ITEM_B" }.gstRatePercent, 0.0001)
    }

    @Test
    fun a8_TradingWorkflow_Sale_GstTransactionsAreOutputDirection() {
        val result = TradingWorkflowEngine.buildSale(
            voucherId = "V1", companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerName = "Cust", customerGstin = "",
            salesLedgerId = "LED_SALES", salesLedgerName = "Sales", companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_A", 1, 100_00L, 18.0)), gstLedgers = gstLedgerRefs(companyId),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
        )
        assertTrue(result.gstTransactions.all { it.direction == GstDirection.OUTPUT })
    }

    @Test
    fun a9_TradingWorkflow_Purchase_GstTransactionsAreInputDirection() {
        val result = TradingWorkflowEngine.buildPurchase(
            voucherId = "V1", companyId = companyId, financialYearId = fyId,
            supplierLedgerId = "LED_CREDITOR", supplierName = "Supp", supplierGstin = "",
            purchaseLedgerId = "LED_PURCHASE", purchaseLedgerName = "Purchase", companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_A", 1, 100_00L, 18.0)), gstLedgers = gstLedgerRefs(companyId),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
        )
        assertTrue(result.gstTransactions.all { it.direction == GstDirection.INPUT })
    }

    /**
     * UI-06: per-line Tax Treatment (previously every line was always NORMAL, so intra/inter-state
     * geography resolution never varied within one invoice). Once a line can be EXEMPT/NIL_RATED/
     * EXPORT, [TradingWorkflowEngine.build] must not let that line's zero-tax [SupplyType] win the
     * ONE invoice-level decision of whether to post CGST+SGST or IGST for the OTHER, taxable lines -
     * otherwise a trailing exempt line would silently drop the tax lines a preceding taxable line
     * already accumulated, leaving the party's debit (which includes that tax) unmatched by any
     * credit and producing an unbalanced voucher.
     */
    @Test
    fun a9b_TradingWorkflow_TrailingExemptLine_DoesNotSuppressEarlierLineTax() {
        val taxableLine = line("ITEM_A", 1, 1000_00L, 18.0)
        val exemptLine = line("ITEM_B", 1, 500_00L, 0.0).copy(supplyNature = GstSupplyNature.EXEMPT)
        val result = TradingWorkflowEngine.buildSale(
            voucherId = "V1", companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerName = "Cust", customerGstin = "",
            salesLedgerId = "LED_SALES", salesLedgerName = "Sales", companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(taxableLine, exemptLine), gstLedgers = gstLedgerRefs(companyId),
            roundOffLedgerId = "LED_RO", roundOffLedgerName = "Round Off"
        )
        // ITEM_A: taxable 1000.00 @ 18% intra-state = 90.00 CGST + 90.00 SGST. ITEM_B contributes
        // zero tax (EXEMPT) but must not erase ITEM_A's already-accumulated CGST/SGST lines.
        val taxLines = result.journalItems.filter { it.ledgerId.startsWith("LED_GST") }
        val totalTax = taxLines.fold(Money.ZERO) { acc, i -> acc + i.amount }
        assertEquals(180_00L, totalTax.paise)
        assertTrue("Expected an Output CGST journal line", taxLines.any { it.ledgerId.contains("CGST") })
        assertTrue("Expected an Output SGST journal line", taxLines.any { it.ledgerId.contains("SGST") })
        val totalDebits = result.journalItems.filter { it.type == DrCr.DEBIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        val totalCredits = result.journalItems.filter { it.type == DrCr.CREDIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        assertEquals("Voucher must remain balanced", totalDebits.paise, totalCredits.paise)
    }

    // ==========================================
    // A2. GST-ONLY PATH (Architecture Checkpoint follow-up, Option B continuation) - pure domain
    // math for TradingWorkflowEngine.buildGstOnlySale, no Room/Repository involved. Proves the
    // SAME GstCalculationEngine call TradingWorkflowEngine.build() uses, never a second calculator,
    // and that every returned row's voucherId is null (there is no voucherId parameter to this
    // function at all - it cannot accidentally be supplied a real one).
    // ==========================================

    @Test
    fun a11_GstOnlySale_VoucherIdAlwaysNull_AndExplicitlyClassifiedAsSales() {
        val result = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = java.time.LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        assertEquals(1, result.size)
        assertNull(result.first().voucherId)
        // Transaction/Contract Hardening: the classification is a real, explicit VoucherType set
        // directly by buildGstOnlySale - never something a reader has to infer from `direction`.
        assertEquals(VoucherType.SALES, result.first().voucherType)
    }

    @Test
    fun a12_GstOnlySale_IntraState_SplitsCgstSgst_MatchesAccountingPathMath() {
        // Same inputs a1/a7 already exercise through buildSale - proves the GST-only path computes
        // byte-identical tax figures to the accounting path, since both call the same engine.
        val result = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27",
            lines = listOf(line("ITEM_A", 10, 100_00L, gstRate = 5.0), line("ITEM_B", 5, 200_00L, gstRate = 28.0)),
            date = java.time.LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val totalTax = result.fold(Money.ZERO) { acc, gt -> acc + gt.cgst + gt.sgst + gt.igst }
        assertEquals(330_00L, totalTax.paise)
        assertTrue(result.all { it.igst == Money.ZERO })
        assertTrue(result.all { it.direction == GstDirection.OUTPUT })
    }

    @Test
    fun a13_GstOnlySale_InterState_UsesIgstOnly() {
        val result = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerGstin = "29AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "29",
            lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0)),
            date = java.time.LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val gt = result.first()
        assertEquals(180_00L, gt.igst.paise)
        assertEquals(Money.ZERO, gt.cgst)
        assertEquals(Money.ZERO, gt.sgst)
        assertEquals(SupplyType.INTER_STATE, gt.supplyType)
    }

    @Test
    fun a14_GstOnlySale_TaxTreatments_NilRatedExemptZeroRated_ProduceZeroTax() {
        val lines = listOf(
            line("ITEM_TAXABLE", 1, 1000_00L, 18.0),
            line("ITEM_NIL", 1, 500_00L, 18.0).copy(supplyNature = GstSupplyNature.NIL_RATED),
            line("ITEM_EXEMPT", 1, 500_00L, 18.0).copy(supplyNature = GstSupplyNature.EXEMPT),
            line("ITEM_EXPORT", 1, 500_00L, 18.0).copy(supplyNature = GstSupplyNature.EXPORT)
        )
        val result = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerGstin = "27AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "27", lines = lines,
            date = java.time.LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        assertEquals(4, result.size)
        val taxable = result.first { it.itemId == "ITEM_TAXABLE" }
        assertEquals(90_00L, taxable.cgst.paise)
        assertEquals(90_00L, taxable.sgst.paise)
        listOf("ITEM_NIL", "ITEM_EXEMPT", "ITEM_EXPORT").forEach { id ->
            val gt = result.first { it.itemId == id }
            assertEquals("$id must carry zero CGST", Money.ZERO, gt.cgst)
            assertEquals("$id must carry zero SGST", Money.ZERO, gt.sgst)
            assertEquals("$id must carry zero IGST", Money.ZERO, gt.igst)
            // Taxable value itself is still recorded (a Nil Rated/Exempt/Zero Rated supply still
            // has a real taxable value for GST reporting purposes - only the tax computed on it is
            // zero, per GstCalculationEngine's own existing, unmodified behavior).
            assertEquals(500_00L, gt.taxableAmount.paise)
        }
        assertEquals(SupplyType.EXPORT, result.first { it.itemId == "ITEM_EXPORT" }.supplyType)
        assertEquals(SupplyType.EXEMPT, result.first { it.itemId == "ITEM_EXEMPT" }.supplyType)
    }

    @Test
    fun a15_GstOnlySale_PlaceOfSupplyAndHsn_ArePreserved() {
        val result = TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = fyId,
            customerLedgerId = "LED_DEBTOR", customerGstin = "29AAAAA0000A1Z5",
            companyStateCode = "27", placeOfSupply = "29",
            lines = listOf(line("ITEM_A", 1, 1000_00L, 18.0, hsn = "998313")),
            date = java.time.LocalDate.of(2026, 5, 10), partyGstRegistrationStatus = null
        )
        val gt = result.first()
        assertEquals("29", gt.placeOfSupply)
        assertEquals("998313", gt.hsnSacCode)
        assertEquals("29AAAAA0000A1Z5", gt.partyGstin)
        assertEquals("LED_DEBTOR", gt.partyLedgerId)
    }

    @Test
    fun a10_GstSummary_CompanyIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(company = "COMP_A", financialYearId = "FY_A")
        dao.seedCompany(company = "COMP_B", financialYearId = "FY_B")
        dao.seedTradingLedgers(company = "COMP_A")
        dao.seedTradingLedgers(company = "COMP_B")
        // Company-suffixed item IDs: the fake DAO's stock-item map is keyed only by itemId (same
        // limitation Phase4TestSuite's d1_CompanyIsolation hit and fixed the same way), so two
        // companies cannot share a bare "ITEM_A" id without one silently overwriting the other.
        dao.insertStockItem(stockItem("ITEM_A_COMP_A", company = "COMP_A", openingQty = 100_000L))
        dao.insertStockItem(stockItem("ITEM_A_COMP_B", company = "COMP_B", openingQty = 100_000L))

        val resultA = TradingWorkflowEngine.buildSale(
            "VA", "COMP_A", "FY_A", "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A_COMP_A", 1, 1000_00L, 18.0)), gstLedgerRefs("COMP_A"), "LED_RO", "Round Off"
        )
        postResult(dao, "VA", VoucherType.SALES, resultA, company = "COMP_A", fy = "FY_A")

        val resultB = TradingWorkflowEngine.buildSale(
            "VB", "COMP_B", "FY_B", "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A_COMP_B", 1, 5000_00L, 18.0)), gstLedgerRefs("COMP_B"), "LED_RO", "Round Off"
        )
        postResult(dao, "VB", VoucherType.SALES, resultB, company = "COMP_B", fy = "FY_B")

        val repo = AccountingRepository(dao)
        val summaryA = repo.generateGSTSummary("COMP_A", "FY_A")
        assertEquals(1000_00L, summaryA.totalTaxableOutward.paise)
    }

    @Test
    fun a11_GstSummary_FinancialYearIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))

        val resultFy1 = TradingWorkflowEngine.buildSale(
            "V1", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V1", VoucherType.SALES, resultFy1)

        val resultFy2 = TradingWorkflowEngine.buildSale(
            "V2", companyId, "FY_OTHER", "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 9000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V2", VoucherType.SALES, resultFy2, fy = "FY_OTHER")

        val repo = AccountingRepository(dao)
        val summary = repo.generateGSTSummary(companyId, fyId)
        assertEquals(1000_00L, summary.totalTaxableOutward.paise)
    }

    // 13-point correctness pass, item 6 - GSTR-1 B2B/B2C bucketing splits the same
    // totalTaxableOutward/totalTaxOutward figures by whether the Sale's party carried a GSTIN.
    @Test
    fun a12_GstSummary_B2bB2cBucketing() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))

        val b2bSale = TradingWorkflowEngine.buildSale(
            "V_B2B", companyId, fyId, "LED_DEBTOR", "Registered Buyer", "29AAAAA0000A1Z5", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_B2B", VoucherType.SALES, b2bSale)

        val b2cSale = TradingWorkflowEngine.buildSale(
            "V_B2C", companyId, fyId, "LED_DEBTOR", "Walk-in Buyer", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 400_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_B2C", VoucherType.SALES, b2cSale)

        val repo = AccountingRepository(dao)
        val summary = repo.generateGSTSummary(companyId, fyId)

        assertEquals(1000_00L, summary.b2bTaxableOutward.paise)
        assertEquals(400_00L, summary.b2cTaxableOutward.paise)
        assertEquals(
            "B2B + B2C taxable value must reconcile to the same total the un-bucketed figure already reported",
            summary.totalTaxableOutward.paise, summary.b2bTaxableOutward.paise + summary.b2cTaxableOutward.paise
        )
        assertEquals(
            summary.totalTaxOutward.paise, summary.b2bTaxOutward.paise + summary.b2cTaxOutward.paise
        )
        assertEquals(0L, summary.creditNoteTaxableOutward.paise)
        assertEquals(0L, summary.debitNoteTaxableOutward.paise)
    }

    // Architecture correction - Trial Balance's opening balance for a Balance-Sheet-nature ledger
    // must carry forward prior-FY activity, not just the ledger's raw lifetime openingBalancePaise.
    @Test
    fun a13_TrialBalance_CarriesForwardOpeningBalance_AcrossFinancialYears() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val fy2Id = "FY_P5_2027_28"
        dao.insertFinancialYear(FinancialYearEntity(fy2Id, companyId, "FY 2027-28", "2027-04-01", "2028-03-31", false, false, null, null))
        dao.insertPeriods(listOf(AccountingPeriodEntity("PER_${companyId}_FY2", companyId, fy2Id, "Full Year", "2027-04-01", "2028-03-31", PeriodStatus.OPEN, null, null)))

        dao.insertLedger(ledger("LED_ASSET", companyId, StandardSystemGroups.FIXED_ASSETS_GROUP_ID, openingPaise = 1000_00L))
        dao.insertLedger(ledger("LED_EQUITY", companyId, StandardSystemGroups.CAPITAL_GROUP_ID, openingPaise = 1000_00L, openingType = DrCr.CREDIT))

        // A Journal voucher dated within FY1 (2026-27): Dr LED_ASSET 500, Cr LED_EQUITY 500.
        val voucherEntity = VoucherEntity(
            voucherId = "V_JRN_FY1", companyId = companyId, financialYearId = fyId, voucherNumber = "V_JRN_FY1", voucherType = VoucherType.JOURNAL,
            date = "2026-05-10", referenceNumber = "", narration = "", totalAmountPaise = 500_00L,
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "TESTER", partyGstin = "", isGstApplicable = false, referenceVoucherId = null, paymentMode = ""
        )
        val items = listOf(
            JournalItemEntity("JI_1", "V_JRN_FY1", companyId, fyId, "LED_ASSET", DrCr.DEBIT, 500_00L, "", 1),
            JournalItemEntity("JI_2", "V_JRN_FY1", companyId, fyId, "LED_EQUITY", DrCr.CREDIT, 500_00L, "", 2)
        )
        VoucherPostingEngine.post(dao, voucherEntity, items, "IK_V_JRN_FY1", "TESTER")

        val repo = AccountingRepository(dao)

        // FY1 (the ledger's own first FY) - opening is still the raw stored value, unaffected.
        val tbFy1 = repo.generateTrialBalance(companyId, fyId)
        val assetRowFy1 = tbFy1.rows.first { it.ledgerId == "LED_ASSET" }
        assertEquals(1000_00L, assetRowFy1.openingDebit.paise)
        assertEquals(1500_00L, assetRowFy1.closingDebit.paise)

        // FY2 - opening must now be FY1's closing (1000 + 500 = 1500), not the raw stored 1000.
        val tbFy2 = repo.generateTrialBalance(companyId, fy2Id)
        val assetRowFy2 = tbFy2.rows.first { it.ledgerId == "LED_ASSET" }
        assertEquals(
            "FY2 opening balance must carry forward FY1's activity, not just the ledger's raw stored opening balance",
            1500_00L, assetRowFy2.openingDebit.paise
        )
        assertEquals(1500_00L, assetRowFy2.closingDebit.paise) // no FY2 activity yet
    }

    // ==========================================
    // B. SALE / PURCHASE TRADING FLOW
    // ==========================================
    @Test
    fun b1_Sale_CreatesReceivable_MovesStock_ChargesOutputGst() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))
        // Purchase first to establish stock + cost basis.
        val purchase = TradingWorkflowEngine.buildPurchase(
            "V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val sale = TradingWorkflowEngine.buildSale(
            "V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 4, 800_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val debtorLine = sale.journalItems.first { it.ledgerId == "LED_DEBTOR" }
        assertEquals(DrCr.DEBIT, debtorLine.type)
        assertTrue("Sale must charge Output GST", sale.journalItems.any { it.ledgerId.contains("OUTPUT") })

        val item = dao.getStockItemById(companyId, "ITEM_A")!!
        assertEquals(6_000L, item.currentQuantity) // 10 in - 4 out = 6 remaining (thousandths)
    }

    @Test
    fun b2_Purchase_CreatesPayable_MovesStock_ClaimsInputGst() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))

        val purchase = TradingWorkflowEngine.buildPurchase(
            "V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val creditorLine = purchase.journalItems.first { it.ledgerId == "LED_CREDITOR" }
        assertEquals(DrCr.CREDIT, creditorLine.type)
        assertTrue("Purchase must claim Input GST", purchase.journalItems.any { it.ledgerId.contains("INPUT") })

        val item = dao.getStockItemById(companyId, "ITEM_A")!!
        assertEquals(10_000L, item.currentQuantity)
    }

    @Test
    fun b3_Sale_ProducesCogsEligibleMovement() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))

        val purchase = TradingWorkflowEngine.buildPurchase(
            "V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val sale = TradingWorkflowEngine.buildSale(
            "V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 4, 800_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val movements = dao.getStockMovementsForVoucher("V_SAL")
        assertEquals(1, movements.size)
        assertEquals(StockDirection.OUT, movements.first().direction)
        // OUT movements cost at the weighted-average purchase cost, not the selling price -
        // directly usable as COGS (Phase 4 valuation rule, unaffected by Phase 5).
        assertEquals(500_00L, movements.first().ratePaise)
    }

    @Test
    fun b4_InvoiceNumberUniqueness_RejectsDuplicate() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale = TradingWorkflowEngine.buildSale(
            "V1", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 100_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        val v1 = VoucherEntity("V1", companyId, fyId, "INV-0001", VoucherType.SALES, "2026-05-10", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", true)
        VoucherPostingEngine.post(dao, v1, sale.journalItems.map { it.toEntity() }, "IK1", "TESTER", sale.stockLines.map { it.toEntity() }, sale.gstTransactions.map { it.toEntity() })

        val v2 = VoucherEntity("V2", companyId, fyId, "INV-0001", VoucherType.SALES, "2026-05-11", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", true)
        try {
            VoucherPostingEngine.post(dao, v2, sale.journalItems.map { it.toEntity() }, "IK2", "TESTER")
            fail("Expected duplicate voucher-number rejection")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.DuplicateVoucherNumber)
        }
    }

    // ==========================================
    // C. CREDIT NOTE / DEBIT NOTE
    // ==========================================
    @Test
    fun c1_CreditNote_ReversesSale_OutputGst_Inventory_KeepsOriginalImmutable() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))

        val purchase = TradingWorkflowEngine.buildPurchase(
            "V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val sale = TradingWorkflowEngine.buildSale(
            "V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 4, 800_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val originalJournalItemsSnapshot = dao.getJournalItemsForVoucherSync("V_SAL").map { it.copy() }
        val originalStockLines = dao.getStockLinesForVoucher("V_SAL")
        val originalGst = dao.getGstTransactionsForVoucher("V_SAL")

        val note = TradingWorkflowEngine.buildNote(
            noteVoucherId = "V_CRN",
            noteVoucherType = VoucherType.CREDIT_NOTE,
            originalJournalItems = originalJournalItemsSnapshot.map {
                JournalItem(it.itemId, it.voucherId, it.companyId, it.financialYearId, it.ledgerId, "", it.type, Money.fromPaise(it.amountPaise), it.narration, it.lineOrder)
            },
            originalStockLines = originalStockLines.map {
                VoucherStockLine(it.lineId, it.voucherId, it.companyId, it.financialYearId, it.itemId, "", it.direction, Quantity(it.quantityRaw), Money.fromPaise(it.ratePaise), Money.fromPaise(it.amountPaise), it.lineOrder)
            },
            originalGstTransactions = originalGst.map {
                GstTransaction(it.gstTransactionId, it.companyId, it.financialYearId, it.voucherId, it.voucherType, it.partyLedgerId, it.partyGstin, it.placeOfSupply, it.supplyType, it.itemId, it.hsnSacCode, it.quantityRaw?.let { q -> Quantity(q) }, Money.fromPaise(it.taxableAmountPaise), it.gstRatePercent, Money.fromPaise(it.cgstPaise), Money.fromPaise(it.sgstPaise), Money.fromPaise(it.igstPaise), Money.fromPaise(it.cessPaise), it.direction, it.lineOrder)
            }
        )
        postResult(dao, "V_CRN", VoucherType.CREDIT_NOTE, note, refVoucherId = "V_SAL")

        // Inventory reverses: 10 purchased - 4 sold + 4 returned = 10 back on hand.
        val item = dao.getStockItemById(companyId, "ITEM_A")!!
        assertEquals(10_000L, item.currentQuantity)

        // Output GST nets to zero across the Sale + Credit Note.
        val repo = AccountingRepository(dao)
        val summary = repo.generateGSTSummary(companyId, fyId)
        assertEquals(0L, summary.totalTaxableOutward.paise)
        assertEquals(0L, summary.totalTaxOutward.paise)

        // Original voucher/journal items are never modified (compared sorted by lineOrder - the
        // fake DAO's ConcurrentHashMap backing does not guarantee iteration order the way the real
        // Room query's `ORDER BY lineOrder ASC` does).
        val afterOriginal = dao.getJournalItemsForVoucherSync("V_SAL")
        assertEquals(
            originalJournalItemsSnapshot.sortedBy { it.lineOrder }.map { it.amountPaise to it.type },
            afterOriginal.sortedBy { it.lineOrder }.map { it.amountPaise to it.type }
        )
        assertFalse(dao.getVoucherById(companyId, "V_SAL")!!.isCancelled)
    }

    @Test
    fun c2_DebitNote_ReversesPurchase_InputGst_Inventory() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))

        val purchase = TradingWorkflowEngine.buildPurchase(
            "V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val originalJournalItemsSnapshot = dao.getJournalItemsForVoucherSync("V_PUR").map { it.copy() }
        val originalStockLines = dao.getStockLinesForVoucher("V_PUR")
        val originalGst = dao.getGstTransactionsForVoucher("V_PUR")

        val note = TradingWorkflowEngine.buildNote(
            noteVoucherId = "V_DRN",
            noteVoucherType = VoucherType.DEBIT_NOTE,
            originalJournalItems = originalJournalItemsSnapshot.map {
                JournalItem(it.itemId, it.voucherId, it.companyId, it.financialYearId, it.ledgerId, "", it.type, Money.fromPaise(it.amountPaise), it.narration, it.lineOrder)
            },
            originalStockLines = originalStockLines.map {
                VoucherStockLine(it.lineId, it.voucherId, it.companyId, it.financialYearId, it.itemId, "", it.direction, Quantity(it.quantityRaw), Money.fromPaise(it.ratePaise), Money.fromPaise(it.amountPaise), it.lineOrder)
            },
            originalGstTransactions = originalGst.map {
                GstTransaction(it.gstTransactionId, it.companyId, it.financialYearId, it.voucherId, it.voucherType, it.partyLedgerId, it.partyGstin, it.placeOfSupply, it.supplyType, it.itemId, it.hsnSacCode, it.quantityRaw?.let { q -> Quantity(q) }, Money.fromPaise(it.taxableAmountPaise), it.gstRatePercent, Money.fromPaise(it.cgstPaise), Money.fromPaise(it.sgstPaise), Money.fromPaise(it.igstPaise), Money.fromPaise(it.cessPaise), it.direction, it.lineOrder)
            }
        )
        postResult(dao, "V_DRN", VoucherType.DEBIT_NOTE, note, refVoucherId = "V_PUR")

        val item = dao.getStockItemById(companyId, "ITEM_A")!!
        assertEquals(0L, item.currentQuantity) // fully returned to supplier

        val repo = AccountingRepository(dao)
        val summary = repo.generateGSTSummary(companyId, fyId)
        assertEquals(0L, summary.totalTaxableInward.paise)
        assertEquals(0L, summary.totalTaxInwardITC.paise)
    }

    // ==========================================
    // C2. GST FACT VOUCHERTYPE HARDENING (Rule 32A) - buildNote() must stamp the NOTE's own
    // VoucherType onto its copied GST rows, never inherit the original Sale/Purchase's.
    // ==========================================
    @Test
    fun c3_SaleGstRow_CarriesVoucherTypeSales() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale = TradingWorkflowEngine.buildSale("V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 1, 100_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_SAL", VoucherType.SALES, sale)
        val rows = dao.getGstTransactionsForVoucher("V_SAL")
        assertTrue(rows.isNotEmpty())
        assertTrue("Every Sale GST row must carry voucherType SALES", rows.all { it.voucherType == VoucherType.SALES })
    }

    @Test
    fun c4_PurchaseGstRow_CarriesVoucherTypePurchase() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))
        val purchase = TradingWorkflowEngine.buildPurchase("V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27", listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)
        val rows = dao.getGstTransactionsForVoucher("V_PUR")
        assertTrue(rows.isNotEmpty())
        assertTrue("Every Purchase GST row must carry voucherType PURCHASE", rows.all { it.voucherType == VoucherType.PURCHASE })
    }

    @Test
    fun c5_CreditNoteGstRow_CarriesVoucherTypeCreditNote_NotSales() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale = TradingWorkflowEngine.buildSale("V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 4, 800_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val originalGst = dao.getGstTransactionsForVoucher("V_SAL")
        val note = TradingWorkflowEngine.buildNote(
            noteVoucherId = "V_CRN",
            noteVoucherType = VoucherType.CREDIT_NOTE,
            originalJournalItems = dao.getJournalItemsForVoucherSync("V_SAL").map {
                JournalItem(it.itemId, it.voucherId, it.companyId, it.financialYearId, it.ledgerId, "", it.type, Money.fromPaise(it.amountPaise), it.narration, it.lineOrder)
            },
            originalStockLines = dao.getStockLinesForVoucher("V_SAL").map {
                VoucherStockLine(it.lineId, it.voucherId, it.companyId, it.financialYearId, it.itemId, "", it.direction, Quantity(it.quantityRaw), Money.fromPaise(it.ratePaise), Money.fromPaise(it.amountPaise), it.lineOrder)
            },
            originalGstTransactions = originalGst.map {
                GstTransaction(it.gstTransactionId, it.companyId, it.financialYearId, it.voucherId, it.voucherType, it.partyLedgerId, it.partyGstin, it.placeOfSupply, it.supplyType, it.itemId, it.hsnSacCode, it.quantityRaw?.let { q -> Quantity(q) }, Money.fromPaise(it.taxableAmountPaise), it.gstRatePercent, Money.fromPaise(it.cgstPaise), Money.fromPaise(it.sgstPaise), Money.fromPaise(it.igstPaise), Money.fromPaise(it.cessPaise), it.direction, it.lineOrder)
            }
        )
        postResult(dao, "V_CRN", VoucherType.CREDIT_NOTE, note, refVoucherId = "V_SAL")

        val noteRows = dao.getGstTransactionsForVoucher("V_CRN")
        assertTrue(noteRows.isNotEmpty())
        assertTrue("A Credit Note's GST rows must carry voucherType CREDIT_NOTE, not the original Sale's SALES", noteRows.all { it.voucherType == VoucherType.CREDIT_NOTE })
        // Reversal amounts/direction must still be exactly negated, unchanged by this fix.
        assertEquals(originalGst.sumOf { it.taxableAmountPaise }, -noteRows.sumOf { it.taxableAmountPaise })
        assertTrue(noteRows.all { it.direction == originalGst.first().direction })
    }

    @Test
    fun c6_DebitNoteGstRow_CarriesVoucherTypeDebitNote_NotPurchase() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))
        val purchase = TradingWorkflowEngine.buildPurchase("V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27", listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val originalGst = dao.getGstTransactionsForVoucher("V_PUR")
        val note = TradingWorkflowEngine.buildNote(
            noteVoucherId = "V_DRN",
            noteVoucherType = VoucherType.DEBIT_NOTE,
            originalJournalItems = dao.getJournalItemsForVoucherSync("V_PUR").map {
                JournalItem(it.itemId, it.voucherId, it.companyId, it.financialYearId, it.ledgerId, "", it.type, Money.fromPaise(it.amountPaise), it.narration, it.lineOrder)
            },
            originalStockLines = dao.getStockLinesForVoucher("V_PUR").map {
                VoucherStockLine(it.lineId, it.voucherId, it.companyId, it.financialYearId, it.itemId, "", it.direction, Quantity(it.quantityRaw), Money.fromPaise(it.ratePaise), Money.fromPaise(it.amountPaise), it.lineOrder)
            },
            originalGstTransactions = originalGst.map {
                GstTransaction(it.gstTransactionId, it.companyId, it.financialYearId, it.voucherId, it.voucherType, it.partyLedgerId, it.partyGstin, it.placeOfSupply, it.supplyType, it.itemId, it.hsnSacCode, it.quantityRaw?.let { q -> Quantity(q) }, Money.fromPaise(it.taxableAmountPaise), it.gstRatePercent, Money.fromPaise(it.cgstPaise), Money.fromPaise(it.sgstPaise), Money.fromPaise(it.igstPaise), Money.fromPaise(it.cessPaise), it.direction, it.lineOrder)
            }
        )
        postResult(dao, "V_DRN", VoucherType.DEBIT_NOTE, note, refVoucherId = "V_PUR")

        val noteRows = dao.getGstTransactionsForVoucher("V_DRN")
        assertTrue(noteRows.isNotEmpty())
        assertTrue("A Debit Note's GST rows must carry voucherType DEBIT_NOTE, not the original Purchase's PURCHASE", noteRows.all { it.voucherType == VoucherType.DEBIT_NOTE })
        assertEquals(originalGst.sumOf { it.taxableAmountPaise }, -noteRows.sumOf { it.taxableAmountPaise })
        assertTrue(noteRows.all { it.direction == originalGst.first().direction })
    }

    // ==========================================
    // C3. GST BOUNDARY (Phase 32) - Receipt/Payment/Contra/Journal must never create a
    // GstTransaction, even though `seedCompany()` defaults Company.gstEnabled to true.
    // ==========================================
    @Test
    fun c7_Receipt_CreatesNoGstTransaction() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        assertTrue(dao.getCompanyById(companyId)!!.gstEnabled)
        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", 1000_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, 1000_00L, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, 1000_00L, "", 2)
        ), "IK_RCT", "TESTER")
        assertTrue("Receipt must never create a GstTransaction, even with gstEnabled=true", dao.getGstTransactionsForVoucher("V_RCT").isEmpty())
    }

    @Test
    fun c8_Payment_CreatesNoGstTransaction() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        assertTrue(dao.getCompanyById(companyId)!!.gstEnabled)
        val payment = VoucherEntity("V_PMT", companyId, fyId, "PMT-0001", VoucherType.PAYMENT, "2026-05-12", "", "", 1000_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, payment, listOf(
            JournalItemEntity("I1", "V_PMT", companyId, fyId, "LED_CREDITOR", DrCr.DEBIT, 1000_00L, "", 1),
            JournalItemEntity("I2", "V_PMT", companyId, fyId, "LED_BANK", DrCr.CREDIT, 1000_00L, "", 2)
        ), "IK_PMT", "TESTER")
        assertTrue("Payment must never create a GstTransaction, even with gstEnabled=true", dao.getGstTransactionsForVoucher("V_PMT").isEmpty())
    }

    @Test
    fun c9_Contra_CreatesNoGstTransaction() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        assertTrue(dao.getCompanyById(companyId)!!.gstEnabled)
        val contra = VoucherEntity("V_CTR", companyId, fyId, "CTR-0001", VoucherType.CONTRA, "2026-05-12", "", "", 500_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, contra, listOf(
            JournalItemEntity("I1", "V_CTR", companyId, fyId, "LED_CASH", DrCr.DEBIT, 500_00L, "", 1),
            JournalItemEntity("I2", "V_CTR", companyId, fyId, "LED_BANK", DrCr.CREDIT, 500_00L, "", 2)
        ), "IK_CTR", "TESTER")
        assertTrue("Contra must never create a GstTransaction, even with gstEnabled=true", dao.getGstTransactionsForVoucher("V_CTR").isEmpty())
    }

    @Test
    fun c10_Journal_CreatesNoGstTransaction() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        assertTrue(dao.getCompanyById(companyId)!!.gstEnabled)
        val journal = VoucherEntity("V_JRN", companyId, fyId, "JRN-0001", VoucherType.JOURNAL, "2026-05-12", "", "", 200_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, journal, listOf(
            JournalItemEntity("I1", "V_JRN", companyId, fyId, "LED_CAPITAL", DrCr.DEBIT, 200_00L, "", 1),
            JournalItemEntity("I2", "V_JRN", companyId, fyId, "LED_CASH", DrCr.CREDIT, 200_00L, "", 2)
        ), "IK_JRN", "TESTER")
        assertTrue("Journal must never create a GstTransaction, even with gstEnabled=true", dao.getGstTransactionsForVoucher("V_JRN").isEmpty())
    }

    // ==========================================
    // D. SETTLEMENT / ALLOCATION (Receipt / Payment)
    // ==========================================
    @Test
    fun d1_Receipt_FullAllocation_ClearsOutstanding() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))
        val purchase = TradingWorkflowEngine.buildPurchase("V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27", listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)
        val sale = TradingWorkflowEngine.buildSale("V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 4, 800_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, sale.totalAmount.paise, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, sale.totalAmount.paise, "", 2)
        ), "IK_RCT", "TESTER")

        val repo = AccountingRepository(dao)
        val alloc = repo.allocateSettlement(companyId, fyId, "V_RCT", listOf("V_SAL" to sale.totalAmount), Money.ZERO)
        assertTrue(alloc is com.example.accounting.core.common.AccountingResult.Success)

        val outstanding = repo.getOutstandingInvoices(companyId, "LED_DEBTOR")
        assertTrue("Fully allocated invoice must no longer be outstanding", outstanding.none { it.voucherId == "V_SAL" })
    }

    /**
     * Cancel/Reverse audit fix regression - cancelling the Receipt that fully settled a Sale must
     * restore the Sale's outstanding balance. Before this fix, [VoucherPostingEngine.cancel]
     * reversed the Receipt's own journal items/GST but left its `settlement_allocations` rows
     * untouched, so `computeOutstandingPaise` kept counting money that was never actually received.
     */
    @Test
    fun d1b_CancelReceipt_AfterFullAllocation_RestoresOutstanding() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale = TradingWorkflowEngine.buildSale("V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 4, 800_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, sale.totalAmount.paise, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, sale.totalAmount.paise, "", 2)
        ), "IK_RCT", "TESTER")

        val repo = AccountingRepository(dao)
        repo.allocateSettlement(companyId, fyId, "V_RCT", listOf("V_SAL" to sale.totalAmount), Money.ZERO)
        assertTrue("Sanity check - fully allocated before cancellation", repo.getOutstandingInvoices(companyId, "LED_DEBTOR").none { it.voucherId == "V_SAL" })

        VoucherPostingEngine.cancel(dao, companyId, fyId, "V_RCT", "IK_CANCEL_RCT", "TESTER")

        val outstanding = repo.getOutstandingInvoices(companyId, "LED_DEBTOR").first { it.voucherId == "V_SAL" }
        assertEquals("Cancelling the Receipt must restore the full Sale amount as outstanding", sale.totalAmount.paise, outstanding.outstandingAmount.paise)

        // A fresh Receipt must be able to re-allocate the same Sale in full - the stale allocation
        // must never block re-collection.
        val secondReceipt = VoucherEntity("V_RCT2", companyId, fyId, "RCT-0002", VoucherType.RECEIPT, "2026-05-13", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, secondReceipt, listOf(
            JournalItemEntity("I3", "V_RCT2", companyId, fyId, "LED_BANK", DrCr.DEBIT, sale.totalAmount.paise, "", 1),
            JournalItemEntity("I4", "V_RCT2", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, sale.totalAmount.paise, "", 2)
        ), "IK_RCT2", "TESTER")
        val secondAlloc = repo.allocateSettlement(companyId, fyId, "V_RCT2", listOf("V_SAL" to sale.totalAmount), Money.ZERO)
        assertTrue(secondAlloc is com.example.accounting.core.common.AccountingResult.Success)
        assertTrue(repo.getOutstandingInvoices(companyId, "LED_DEBTOR").none { it.voucherId == "V_SAL" })
    }

    @Test
    fun d2_Receipt_PartialAllocation_LeavesRemainderOutstanding() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale = TradingWorkflowEngine.buildSale("V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val half = Money.fromPaise(sale.totalAmount.paise / 2)
        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", half.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, half.paise, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, half.paise, "", 2)
        ), "IK_RCT", "TESTER")

        val repo = AccountingRepository(dao)
        repo.allocateSettlement(companyId, fyId, "V_RCT", listOf("V_SAL" to half), Money.ZERO)

        val outstanding = repo.getOutstandingInvoices(companyId, "LED_DEBTOR").first { it.voucherId == "V_SAL" }
        assertEquals(sale.totalAmount.paise - half.paise, outstanding.outstandingAmount.paise)
    }

    @Test
    fun d3_Receipt_MultiInvoiceAllocation() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale1 = TradingWorkflowEngine.buildSale("V_S1", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_S1", VoucherType.SALES, sale1)
        val sale2 = TradingWorkflowEngine.buildSale("V_S2", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 1, 2000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_S2", VoucherType.SALES, sale2)

        val total = sale1.totalAmount + sale2.totalAmount
        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", total.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, total.paise, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, total.paise, "", 2)
        ), "IK_RCT", "TESTER")

        val repo = AccountingRepository(dao)
        val result = repo.allocateSettlement(companyId, fyId, "V_RCT", listOf("V_S1" to sale1.totalAmount, "V_S2" to sale2.totalAmount), Money.ZERO)
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)
        val outstanding = repo.getOutstandingInvoices(companyId, "LED_DEBTOR")
        assertTrue(outstanding.none { it.voucherId == "V_S1" || it.voucherId == "V_S2" })
    }

    @Test
    fun d4_Receipt_AdvanceUnallocated_RecordedWithoutInvoice() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        val advance = Money.fromRupees(5000L)
        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", advance.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, advance.paise, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, advance.paise, "", 2)
        ), "IK_RCT", "TESTER")

        val repo = AccountingRepository(dao)
        val result = repo.allocateSettlement(companyId, fyId, "V_RCT", emptyList(), advance)
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)
        assertEquals(1, dao.getAllocationsForSettlement("V_RCT").size)
        assertNull(dao.getAllocationsForSettlement("V_RCT").first().invoiceVoucherId)
    }

    @Test
    fun d5_Payment_FullAllocation_ClearsOutstandingPayable() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A"))
        val purchase = TradingWorkflowEngine.buildPurchase("V_PUR", companyId, fyId, "LED_CREDITOR", "Supp", "", "LED_PURCHASE", "Purchase", "27", "27", listOf(line("ITEM_A", 10, 500_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_PUR", VoucherType.PURCHASE, purchase)

        val payment = VoucherEntity("V_PMT", companyId, fyId, "PMT-0001", VoucherType.PAYMENT, "2026-05-12", "", "", purchase.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, payment, listOf(
            JournalItemEntity("I1", "V_PMT", companyId, fyId, "LED_CREDITOR", DrCr.DEBIT, purchase.totalAmount.paise, "", 1),
            JournalItemEntity("I2", "V_PMT", companyId, fyId, "LED_BANK", DrCr.CREDIT, purchase.totalAmount.paise, "", 2)
        ), "IK_PMT", "TESTER")

        val repo = AccountingRepository(dao)
        repo.allocateSettlement(companyId, fyId, "V_PMT", listOf("V_PUR" to purchase.totalAmount), Money.ZERO)
        val outstanding = repo.getOutstandingInvoices(companyId, "LED_CREDITOR")
        assertTrue(outstanding.none { it.voucherId == "V_PUR" })
    }

    @Test
    fun d6_Settlement_NeverGeneratesGstTransactions() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        val receipt = VoucherEntity("V_RCT", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", 5000_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt, listOf(
            JournalItemEntity("I1", "V_RCT", companyId, fyId, "LED_BANK", DrCr.DEBIT, 5000_00L, "", 1),
            JournalItemEntity("I2", "V_RCT", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, 5000_00L, "", 2)
        ), "IK_RCT", "TESTER")
        assertTrue(dao.getGstTransactionsForVoucher("V_RCT").isEmpty())
    }

    @Test
    fun d7_Receipt_CannotOverAllocate_BeyondInvoiceOutstanding() = runBlocking {
        // Phase 5 final audit finding: allocateSettlement originally validated only the settlement
        // voucher's own total, never the target invoice's remaining outstanding - a second
        // settlement against an already-fully-paid invoice silently double-paid it. Two separate
        // Receipts, each individually valid on its own total, both fully allocated to the SAME
        // already-settled invoice - the second allocation must be rejected.
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        dao.insertStockItem(stockItem("ITEM_A", openingQty = 100_000L))
        val sale = TradingWorkflowEngine.buildSale("V_SAL", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off")
        postResult(dao, "V_SAL", VoucherType.SALES, sale)

        val repo = AccountingRepository(dao)

        val receipt1 = VoucherEntity("V_RCT1", companyId, fyId, "RCT-0001", VoucherType.RECEIPT, "2026-05-12", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt1, listOf(
            JournalItemEntity("I1", "V_RCT1", companyId, fyId, "LED_BANK", DrCr.DEBIT, sale.totalAmount.paise, "", 1),
            JournalItemEntity("I2", "V_RCT1", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, sale.totalAmount.paise, "", 2)
        ), "IK_RCT1", "TESTER")
        val firstAlloc = repo.allocateSettlement(companyId, fyId, "V_RCT1", listOf("V_SAL" to sale.totalAmount), Money.ZERO)
        assertTrue("First full allocation against an unpaid invoice must succeed", firstAlloc is com.example.accounting.core.common.AccountingResult.Success)

        val receipt2 = VoucherEntity("V_RCT2", companyId, fyId, "RCT-0002", VoucherType.RECEIPT, "2026-05-13", "", "", sale.totalAmount.paise, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, receipt2, listOf(
            JournalItemEntity("I1", "V_RCT2", companyId, fyId, "LED_BANK", DrCr.DEBIT, sale.totalAmount.paise, "", 1),
            JournalItemEntity("I2", "V_RCT2", companyId, fyId, "LED_DEBTOR", DrCr.CREDIT, sale.totalAmount.paise, "", 2)
        ), "IK_RCT2", "TESTER")
        val secondAlloc = repo.allocateSettlement(companyId, fyId, "V_RCT2", listOf("V_SAL" to sale.totalAmount), Money.ZERO)
        assertTrue(
            "A second allocation against an already-fully-paid invoice must be rejected, not silently double-paid",
            secondAlloc is com.example.accounting.core.common.AccountingResult.Failure
        )
    }

    // ==========================================
    // E. CONTRA / ROUND OFF
    // ==========================================
    @Test
    fun e1_Contra_CashBankOnly_Accepted() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        val contra = VoucherEntity("V_CTR", companyId, fyId, "CTR-0001", VoucherType.CONTRA, "2026-05-12", "", "", 1000_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        VoucherPostingEngine.post(dao, contra, listOf(
            JournalItemEntity("I1", "V_CTR", companyId, fyId, "LED_CASH", DrCr.DEBIT, 1000_00L, "", 1),
            JournalItemEntity("I2", "V_CTR", companyId, fyId, "LED_BANK", DrCr.CREDIT, 1000_00L, "", 2)
        ), "IK_CTR", "TESTER")
        assertNotNull(dao.getVoucherById(companyId, "V_CTR"))
    }

    @Test
    fun e2_Contra_InvalidLedger_RejectedAtEngineLevel() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        val contra = VoucherEntity("V_CTR", companyId, fyId, "CTR-0001", VoucherType.CONTRA, "2026-05-12", "", "", 1000_00L, true, false, SyncState.PENDING, 0L, 0L, "TESTER", "", false)
        try {
            VoucherPostingEngine.post(dao, contra, listOf(
                JournalItemEntity("I1", "V_CTR", companyId, fyId, "LED_SALES", DrCr.DEBIT, 1000_00L, "", 1),
                JournalItemEntity("I2", "V_CTR", companyId, fyId, "LED_BANK", DrCr.CREDIT, 1000_00L, "", 2)
            ), "IK_CTR", "TESTER")
            fail("Expected Contra-to-Sales-ledger rejection")
        } catch (e: AccountingTransactionException) {
            assertTrue(e.appError is AppError.InvalidContraLedger)
        }
    }

    @Test
    fun e3_RoundOffEngine_RoundsToNearestRupee() {
        val result = com.example.accounting.domain.accounting.RoundOffEngine.roundInvoiceTotal(Money.fromPaise(1000_37L))
        assertEquals(1000_00L, result.roundedTotal.paise)
        assertEquals(-37L, result.roundOffAmount.paise)

        val roundUp = com.example.accounting.domain.accounting.RoundOffEngine.roundInvoiceTotal(Money.fromPaise(1000_63L))
        assertEquals(1001_00L, roundUp.roundedTotal.paise)
        assertEquals(37L, roundUp.roundOffAmount.paise)

        val exact = com.example.accounting.domain.accounting.RoundOffEngine.roundInvoiceTotal(Money.fromRupees(1000L))
        assertEquals(0L, exact.roundOffAmount.paise)
    }

    @Test
    fun e4_TradingWorkflow_RoundOff_KeepsVoucherBalanced() {
        // A rate producing a non-rupee-exact raw total (1000 x 12.345 = 123.45, taxable ends on
        // paise cleanly but the 18% split across two rupee-rounding-sensitive halves can still
        // leave a paise remainder after Round Off is applied to the party line only).
        val result = TradingWorkflowEngine.buildSale(
            "V1", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 3, 333_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        val totalDebit = result.journalItems.filter { it.type == DrCr.DEBIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        val totalCredit = result.journalItems.filter { it.type == DrCr.CREDIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        assertEquals("Round Off must keep every posted voucher balanced", totalDebit.paise, totalCredit.paise)
    }

    @Test
    fun e5_RoundOffLedger_ProtectedLikeSuspense_CannotBeDeletedOrRenamed() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        val repo = AccountingRepository(dao)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val deleteResult = repo.deleteLedgerSafely(companyId, roundOffId)
        assertTrue(deleteResult is com.example.accounting.core.common.AccountingResult.Failure)

        val ledger = dao.getLedgerById(companyId, roundOffId)!!
        val renamed = com.example.accounting.domain.accounting.Ledger(
            ledgerId = ledger.ledgerId, companyId = companyId, groupId = ledger.groupId, name = "Renamed Round Off",
            openingBalance = Money.ZERO, openingBalanceType = DrCr.DEBIT, currentBalance = Money.ZERO, currentBalanceType = DrCr.DEBIT,
            isSystem = ledger.isSystem
        )
        val renameResult = repo.updateLedger(renamed)
        assertTrue(renameResult is com.example.accounting.core.common.AccountingResult.Failure)
    }

    @Test
    fun e6_RoundOff_And_Suspense_AreDistinctLedgers() {
        assertTrue(StandardSystemGroups.ROUND_OFF_LEDGER_ID != StandardSystemGroups.SUSPENSE_LEDGER_ID)
        assertTrue(StandardSystemGroups.ROUND_OFF_GROUP_ID != StandardSystemGroups.SUSPENSE_GROUP_ID)
    }

    @Test
    fun e7_EnsureGstLedgersExist_BackfillsCessLedger() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        repo.ensureGstLedgersExist(companyId)
        val cessId = "${GstLedgerIds.CESS_LEDGER_ID}_$companyId"
        assertNotNull(dao.getLedgerById(companyId, cessId))
    }

    @Test
    fun e8_EnsureRoundOffLedgerExists_IsIdempotent() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        repo.ensureRoundOffLedgerExists(companyId)
        repo.ensureRoundOffLedgerExists(companyId)
        val matches = dao.getLedgersByCompany(companyId).first().count { it.ledgerId == "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId" }
        assertEquals(1, matches)
    }

    // ==========================================
    // F. PERIOD GOVERNANCE (GST filing period isolation)
    // ==========================================
    @Test
    fun f1_GstFilingPeriod_LockDoesNotLockAccountingPeriod() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        repo.createGstFilingPeriod(companyId, "2026-04", "2026-04-01", "2026-04-30")
        val period = repo.getGstFilingPeriods(companyId).first().first()
        repo.setGstFilingPeriodLock(companyId, period.filingPeriodId, true)

        val lockedPeriod = repo.getGstFilingPeriods(companyId).first().first()
        assertTrue(lockedPeriod.isLocked)

        // The accounting period itself must remain untouched/open.
        val accountingPeriod = dao.getPeriodById("PER_${companyId}_1")
        assertEquals(PeriodStatus.OPEN, accountingPeriod!!.status)
    }

    @Test
    fun f2_GstFilingPeriod_CompanyIsolation() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(company = "COMP_A", financialYearId = "FY_A")
        dao.seedCompany(company = "COMP_B", financialYearId = "FY_B")
        val repo = AccountingRepository(dao)
        repo.createGstFilingPeriod("COMP_A", "2026-04", "2026-04-01", "2026-04-30")
        val periodsB = repo.getGstFilingPeriods("COMP_B").first()
        assertTrue(periodsB.isEmpty())
    }

    // ==========================================
    // G. PARTY / CUSTOMER / SUPPLIER DATA VALIDATION (Rule 30)
    // ==========================================

    @Test
    fun g1_PartyValidation_Individual_BlankGstin_Allowed() {
        val error = PartyValidation.validateGstFacts(PartyEntityType.INDIVIDUAL, null, "")
        assertNull("Individual with blank GSTIN must be allowed", error)
    }

    @Test
    fun g2_PartyValidation_Business_Registered_ValidGstin_Allowed() {
        val error = PartyValidation.validateGstFacts(
            PartyEntityType.BUSINESS, com.example.accounting.domain.accounting.GstRegistrationStatus.REGISTERED, "27AAAAA0000A1Z5"
        )
        assertNull("Business + REGISTERED + valid GSTIN must be allowed", error)
    }

    @Test
    fun g3_PartyValidation_Business_Registered_BlankGstin_Rejected() {
        val error = PartyValidation.validateGstFacts(
            PartyEntityType.BUSINESS, com.example.accounting.domain.accounting.GstRegistrationStatus.REGISTERED, ""
        )
        assertNotNull("Business + REGISTERED + blank GSTIN must be rejected", error)
    }

    @Test
    fun g4_PartyValidation_Business_Registered_InvalidGstin_Rejected() {
        val error = PartyValidation.validateGstFacts(
            PartyEntityType.BUSINESS, com.example.accounting.domain.accounting.GstRegistrationStatus.REGISTERED, "NOT-A-VALID-GSTIN"
        )
        assertNotNull("Business + REGISTERED + invalid GSTIN must be rejected", error)
    }

    @Test
    fun g5_PartyValidation_Business_Unregistered_BlankGstin_Allowed() {
        val error = PartyValidation.validateGstFacts(
            PartyEntityType.BUSINESS, com.example.accounting.domain.accounting.GstRegistrationStatus.UNREGISTERED, ""
        )
        assertNull("Business + UNREGISTERED + blank GSTIN must be allowed", error)
    }

    @Test
    fun g6_PartyValidation_UnknownStatus_NeverTreatedAsRegisteredOrUnregistered() = runBlocking {
        // Pure logic: UNKNOWN (null) must never be rejected the way REGISTERED-with-blank-GSTIN is.
        val error = PartyValidation.validateGstFacts(PartyEntityType.BUSINESS, null, "")
        assertNull("An UNKNOWN registration status must never be silently treated as REGISTERED", error)

        // Persistence: a Party created with no explicit registration status must read back as
        // exactly null (UNKNOWN) - never defaulted to UNREGISTERED.
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        val result = repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "Unknown GST Co"),
            com.example.accounting.domain.accounting.Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "Unknown GST Co")
        )
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)
        val ledgerId = (result as com.example.accounting.core.common.AccountingResult.Success).data.ledgerId
        val persisted = dao.getLedgerById(companyId, ledgerId)!!
        assertNull("gstRegistrationStatus must remain null (UNKNOWN), never guessed", persisted.gstRegistrationStatus)
    }

    @Test
    fun g7_PartyCreation_MissingState_IsNotReplacedWithCompanyState() = runBlocking {
        val dao = freshDao()
        dao.seedCompany() // company stateCode = "27"
        val repo = AccountingRepository(dao)
        val result = repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "No State Co"),
            com.example.accounting.domain.accounting.Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "No State Co", stateCode = "")
        )
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)
        val ledgerId = (result as com.example.accounting.core.common.AccountingResult.Success).data.ledgerId
        val persisted = dao.getLedgerById(companyId, ledgerId)!!
        assertEquals("A Party's missing State must stay blank, never silently become the company's own state", "", persisted.stateCode)
    }

    @Test
    fun g8_PartyCreation_ExistingDataRemainsCompatible() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        // Simulate a pre-migration Ledger row that predates gstRegistrationStatus/stateCode being
        // collected at all (a real "existing data" scenario, not a fabricated new field value).
        dao.insertLedger(
            LedgerEntity(
                "LED_LEGACY", companyId, "${StandardSystemGroups.DEBTORS_GROUP_ID}_$companyId", "Legacy Customer", "",
                0L, DrCr.DEBIT, 0L, DrCr.DEBIT, "", "", "", "", "", "", "", "", "", "", false, true, "", 0.0
            )
        )
        val repo = AccountingRepository(dao)
        val ledgers = repo.getLedgers(companyId).first()
        val legacy = ledgers.first { it.ledgerId == "LED_LEGACY" }
        assertNull("Pre-existing data with no recorded GST registration status must remain UNKNOWN, not mutated", legacy.gstRegistrationStatus)
        assertEquals("", legacy.stateCode)
        assertEquals("", legacy.gstin)
    }

    @Test
    fun g9_PartyCreation_DoesNotCreateVoucher() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.INDIVIDUAL, displayName = "Walk-in Customer"),
            com.example.accounting.domain.accounting.Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "Walk-in Customer")
        )
        assertTrue("Party creation must never post a Voucher", dao.getAllVouchersByCompany(companyId).first().isEmpty())
    }

    @Test
    fun g10_PartyCreation_DoesNotCreateJournalItem() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.SUPPLIER, entityType = PartyEntityType.INDIVIDUAL, displayName = "Walk-in Supplier"),
            com.example.accounting.domain.accounting.Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "Walk-in Supplier")
        )
        assertTrue("Party creation must never create a JournalItem", dao.getAllJournalItems(companyId, fyId).first().isEmpty())
    }

    @Test
    fun g11_PartyCreation_DoesNotChangeAnyLedgerBalance() = runBlocking {
        val dao = freshDao()
        dao.seedCompany()
        dao.seedTradingLedgers()
        val before = dao.getLedgersByCompany(companyId).first().associate { it.ledgerId to it.currentBalancePaise }
        val repo = AccountingRepository(dao)
        val result = repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "New Customer"),
            com.example.accounting.domain.accounting.Ledger(ledgerId = "", companyId = companyId, groupId = "", name = "New Customer")
        )
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)
        val newLedgerId = (result as com.example.accounting.core.common.AccountingResult.Success).data.ledgerId

        val after = dao.getLedgersByCompany(companyId).first().associate { it.ledgerId to it.currentBalancePaise }
        before.forEach { (ledgerId, balance) -> assertEquals("Existing ledger '$ledgerId' balance must be unchanged by Party creation", balance, after[ledgerId]) }
        assertEquals("A freshly created Party's ledger must open at zero balance", 0L, after[newLedgerId])
    }

    @Test
    fun g12_PartyCreation_CompanyBoundaryIsEnforced() = runBlocking {
        val dao = freshDao()
        dao.seedCompany(company = "COMP_PARTY_A", financialYearId = "FY_PARTY_A")
        dao.seedCompany(company = "COMP_PARTY_B", financialYearId = "FY_PARTY_B")
        val repo = AccountingRepository(dao)
        repo.createParty(
            Party(partyId = "", companyId = "COMP_PARTY_A", ledgerId = "", role = PartyRole.CUSTOMER, entityType = PartyEntityType.BUSINESS, displayName = "A's Customer"),
            com.example.accounting.domain.accounting.Ledger(ledgerId = "", companyId = "COMP_PARTY_A", groupId = "", name = "A's Customer")
        )
        val partiesInB = repo.getParties("COMP_PARTY_B").first()
        assertTrue("A Party created under Company A must never be visible under Company B", partiesInB.isEmpty())
        val ledgersInB = repo.getLedgers("COMP_PARTY_B").first()
        assertTrue("Company A's new Party ledger must never leak into Company B's ledgers", ledgersInB.none { it.name == "A's Customer" })
    }

    // ==========================================
    // H. PURCHASE / RCM FOUNDATION (Rule 31)
    // ==========================================

    @Test
    fun h1_Purchase_RegisteredSupplier_ForwardCharge_PostsOrdinaryInputTax() {
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "27AAAAA0000A1Z5", "LED_PURCHASE", "Purchase",
            "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId" })
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$companyId" })
        assertTrue("An ordinary forward-charge Purchase must never touch any RCM ledger", result.journalItems.none { it.ledgerId.contains("RCM") })
        assertEquals(GstChargeType.FORWARD_CHARGE, result.gstTransactions.first().chargeType)
    }

    @Test
    fun h2_Purchase_UnregisteredSupplier_DoesNotAutomaticallyBecomeRcm() {
        // "Unregistered supplier" is a Ledger-level fact this function doesn't even receive here -
        // only chargeType (per-line, explicit) decides RCM. Proves the two are never coupled: a
        // line with no chargeType specified always defaults to FORWARD_CHARGE, regardless of the
        // supplier's registration status.
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Unregistered Supplier", "", "LED_PURCHASE", "Purchase",
            "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        assertEquals(GstChargeType.FORWARD_CHARGE, result.gstTransactions.first().chargeType)
        assertTrue(result.journalItems.none { it.ledgerId.contains("RCM") })
    }

    @Test
    fun h3_Purchase_ExplicitRcmLine_TaxCalculatedViaSameEngine() {
        val fcLine = line("ITEM_A", 1, 1000_00L, 18.0)
        val rcmLine = line("ITEM_B", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase",
            "27", "27", listOf(fcLine, rcmLine), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        val rcmGt = result.gstTransactions.first { it.chargeType == GstChargeType.REVERSE_CHARGE }
        assertEquals(90_00L, rcmGt.cgst.paise)
        assertEquals(90_00L, rcmGt.sgst.paise)
    }

    @Test
    fun h4_Purchase_IntraStateRcm_PostsCgstAndSgstToRcmLedgers() {
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase",
            "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)),
            gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID}_$companyId" && it.type == DrCr.DEBIT })
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID}_$companyId" && it.type == DrCr.CREDIT })
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.RCM_INPUT_SGST_LEDGER_ID}_$companyId" && it.type == DrCr.DEBIT })
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.RCM_LIABILITY_SGST_LEDGER_ID}_$companyId" && it.type == DrCr.CREDIT })
        assertTrue("Intra-state RCM must never post IGST", result.journalItems.none { it.ledgerId.contains("IGST") })
    }

    @Test
    fun h5_Purchase_InterStateRcm_PostsIgstToRcmLedgers() {
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase",
            "27", "09",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)),
            gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.RCM_INPUT_IGST_LEDGER_ID}_$companyId" && it.type == DrCr.DEBIT })
        assertTrue(result.journalItems.any { it.ledgerId == "${GstLedgerIds.RCM_LIABILITY_IGST_LEDGER_ID}_$companyId" && it.type == DrCr.CREDIT })
        assertTrue("Inter-state RCM must never post CGST/SGST", result.journalItems.none { it.ledgerId.contains("CGST") || it.ledgerId.contains("SGST") })
    }

    @Test
    fun h6_Purchase_InvalidRcmCombination_Rejected() {
        try {
            TradingWorkflowEngine.buildSale(
                "V1", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
                listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)),
                gstLedgerRefs(companyId), "LED_RO", "Round Off"
            )
            fail("Expected rejection of Reverse Charge on a Sale")
        } catch (e: IllegalArgumentException) { /* expected */ }

        try {
            TradingWorkflowEngine.buildPurchase(
                "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
                listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE, supplyNature = GstSupplyNature.EXEMPT)),
                gstLedgerRefs(companyId), "LED_RO", "Round Off"
            )
            fail("Expected rejection of Reverse Charge combined with a non-Taxable line")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun h7_Purchase_RcmLiabilityPosting_RemainsBalanced() {
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(
                line("ITEM_A", 1, 1000_00L, 18.0),
                line("ITEM_B", 1, 500_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)
            ),
            gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        val totalDebit = result.journalItems.filter { it.type == DrCr.DEBIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        val totalCredit = result.journalItems.filter { it.type == DrCr.CREDIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        assertEquals("A Purchase with an RCM line must still balance", totalDebit.paise, totalCredit.paise)

        // Supplier payable must exclude the RCM line's tax - only taxable value (1000+500) plus the
        // forward-charge line's own tax (180) enters what is owed to the supplier.
        val supplierLine = result.journalItems.first { it.ledgerId == "LED_CREDITOR" }
        assertEquals(1500_00L + 180_00L, supplierLine.amount.paise)
    }

    @Test
    fun h8_Purchase_ExistingForwardChargeBehavior_Unchanged() {
        val result = TradingWorkflowEngine.buildPurchase(
            "V1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgerRefs(companyId), "LED_RO", "Round Off"
        )
        assertEquals(1180_00L, result.totalAmount.paise)
        assertTrue(result.journalItems.none { it.ledgerId.contains("RCM") })
    }

    @Test
    fun h9_Rule30_SupplierGstRegistrationStatus_RemainsIntact() = runBlocking {
        // A supplier's gstRegistrationStatus (Rule 30) is a Ledger fact, completely untouched by
        // Rule 31 - read/written the same way regardless of any purchase's chargeType.
        val dao = freshDao()
        dao.seedCompany()
        val repo = AccountingRepository(dao)
        val result = repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.SUPPLIER, entityType = PartyEntityType.BUSINESS, displayName = "RCM Test Supplier"),
            com.example.accounting.domain.accounting.Ledger(
                ledgerId = "", companyId = companyId, groupId = "", name = "RCM Test Supplier", stateCode = "27",
                gstRegistrationStatus = com.example.accounting.domain.accounting.GstRegistrationStatus.UNREGISTERED
            )
        )
        assertTrue(result is com.example.accounting.core.common.AccountingResult.Success)
        val ledgerId = (result as com.example.accounting.core.common.AccountingResult.Success).data.ledgerId
        val persisted = dao.getLedgerById(companyId, ledgerId)!!
        assertEquals("UNREGISTERED", persisted.gstRegistrationStatus)
    }

    // ==========================================
    // I. PURCHASE / RCM ACCOUNTING POSTING (Rule 32)
    // ==========================================
    // Rule 31's h-series tests only exercise the pure TradingWorkflowEngine.build() calculation -
    // none of them actually post through VoucherPostingEngine/real ledger balance updates/GST
    // transaction persistence. These i-series tests close that gap using the exact same
    // postResult()/VoucherPostingEngine.post() pattern this class already uses for every other
    // voucher type - no new posting engine, no new GST engine, reuse only.

    private fun purchaseGstSetup(): Pair<AccountingDao, AccountingRepository> {
        val dao = freshDao()
        runBlocking {
            dao.seedCompany()
            dao.seedTradingLedgers()
            // Opening quantity > 0 (raw value - Quantity's scale is x1000, so 100_000L = 100 real
            // units) so the i11 Sale scenario (stock OUT) has enough to sell - irrelevant to the
            // Purchase scenarios (stock IN), which never check availability.
            dao.insertStockItems(listOf(stockItem("ITEM_A", openingQty = 100_000L), stockItem("ITEM_B", openingQty = 100_000L)))
        }
        val repo = AccountingRepository(dao)
        return dao to repo
    }

    @Test
    fun i1_Purchase_RemainsBalanced_AfterRealPosting() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I1", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I1", VoucherType.PURCHASE, result)

        val items = dao.getJournalItemsForVoucherSync("V_I1")
        val debit = items.filter { it.type == DrCr.DEBIT }.fold(0L) { acc, i -> acc + i.amountPaise }
        val credit = items.filter { it.type == DrCr.CREDIT }.fold(0L) { acc, i -> acc + i.amountPaise }
        assertEquals("An ordinary posted Purchase must balance", debit, credit)
    }

    @Test
    fun i2_RegisteredSupplier_ForwardCharge_PostsCorrectly() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I2", companyId, fyId, "LED_CREDITOR", "Supplier", "27AAAAA0000A1Z5", "LED_PURCHASE", "Purchase",
            "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I2", VoucherType.PURCHASE, result)

        val inputCgst = dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId")!!
        val inputSgst = dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$companyId")!!
        assertEquals(90_00L, inputCgst.currentBalancePaise)
        assertEquals(90_00L, inputSgst.currentBalancePaise)
        assertEquals(DrCr.DEBIT, inputCgst.currentBalanceType)
    }

    @Test
    fun i3_IntraStatePurchase_PostsCgstSgst_NeverIgst() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I3", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase",
            "27", "27", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I3", VoucherType.PURCHASE, result)

        assertEquals(90_00L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        assertEquals(90_00L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        assertEquals(0L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_IGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
    }

    @Test
    fun i4_InterStatePurchase_PostsIgst_NeverCgstSgst() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I4", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase",
            "27", "09", listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I4", VoucherType.PURCHASE, result)

        assertEquals(180_00L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_IGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        assertEquals(0L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        assertEquals(0L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
    }

    @Test
    fun i5_ExplicitRcm_PostsRcmLiabilityAndInputTax_Correctly() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I5", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)),
            gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I5", VoucherType.PURCHASE, result)

        val rcmInputCgst = dao.getLedgerById(companyId, "${GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID}_$companyId")!!
        val rcmLiabilityCgst = dao.getLedgerById(companyId, "${GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID}_$companyId")!!
        assertEquals(90_00L, rcmInputCgst.currentBalancePaise)
        assertEquals(DrCr.DEBIT, rcmInputCgst.currentBalanceType)
        assertEquals(90_00L, rcmLiabilityCgst.currentBalancePaise)
        assertEquals(DrCr.CREDIT, rcmLiabilityCgst.currentBalanceType)
        // Ordinary Input CGST (forward-charge) must remain untouched by an all-RCM Purchase.
        assertEquals(0L, dao.getLedgerById(companyId, "${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
    }

    @Test
    fun i6_RcmTax_DoesNotIncreaseSupplierPayable_AfterRealPosting() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I6", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)),
            gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I6", VoucherType.PURCHASE, result)

        // Supplier owes only the taxable value (1000) - the self-assessed RCM tax (180) never
        // becomes part of what is payable to them.
        val supplier = dao.getLedgerById(companyId, "LED_CREDITOR")!!
        assertEquals(1000_00L, supplier.currentBalancePaise)
        assertEquals(DrCr.CREDIT, supplier.currentBalanceType)
    }

    @Test
    fun i7_RoundOff_RemainsCorrect_WithRcmLinePresent() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        // Same round-off-triggering rate as e4, plus one Reverse Charge line.
        val result = TradingWorkflowEngine.buildPurchase(
            "V_I7", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(
                line("ITEM_A", 3, 333_00L, 18.0),
                line("ITEM_B", 1, 500_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)
            ),
            gstLedgers, roundOffId, "Round Off"
        )
        val expectedRoundOffItem = result.journalItems.find { it.ledgerId == roundOffId }
        val roundOffBefore = dao.getLedgerById(companyId, roundOffId)!!.currentBalancePaise

        postResult(dao, "V_I7", VoucherType.PURCHASE, result)

        val roundOffAfter = dao.getLedgerById(companyId, roundOffId)!!.currentBalancePaise
        if (expectedRoundOffItem != null) {
            assertEquals(
                "The amount actually posted to the Round Off ledger must match what build() computed",
                expectedRoundOffItem.amount.paise, kotlin.math.abs(roundOffAfter - roundOffBefore)
            )
        }

        val items = dao.getJournalItemsForVoucherSync("V_I7")
        val debit = items.filter { it.type == DrCr.DEBIT }.fold(0L) { acc, i -> acc + i.amountPaise }
        val credit = items.filter { it.type == DrCr.CREDIT }.fold(0L) { acc, i -> acc + i.amountPaise }
        assertEquals("Posted voucher must balance even when Round Off and an RCM line both apply", debit, credit)
    }

    @Test
    fun i8_MixedForwardAndRcmLines_EveryPostedVoucher_DebitEqualsCredit() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I8", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "09",
            listOf(
                line("ITEM_A", 1, 1000_00L, 18.0),
                line("ITEM_B", 1, 500_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)
            ),
            gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I8", VoucherType.PURCHASE, result)

        val items = dao.getJournalItemsForVoucherSync("V_I8")
        val debit = items.filter { it.type == DrCr.DEBIT }.fold(0L) { acc, i -> acc + i.amountPaise }
        val credit = items.filter { it.type == DrCr.CREDIT }.fold(0L) { acc, i -> acc + i.amountPaise }
        assertEquals(debit, credit)
    }

    @Test
    fun i9_GstTransactionPersistence_MatchesPostedVoucher() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I9", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I9", VoucherType.PURCHASE, result)

        val persisted = dao.getGstTransactionsForVoucher("V_I9")
        assertEquals(1, persisted.size)
        assertEquals(result.gstTransactions.first().cgst.paise, persisted.first().cgstPaise)
        assertEquals(result.gstTransactions.first().sgst.paise, persisted.first().sgstPaise)
        assertEquals(result.gstTransactions.first().taxableAmount.paise, persisted.first().taxableAmountPaise)
        assertEquals(GstChargeType.FORWARD_CHARGE, persisted.first().chargeType)
    }

    @Test
    fun i10_ForwardAndReverseCharge_RemainDistinguishable_AfterPersistence() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I10", companyId, fyId, "LED_CREDITOR", "Supplier", "", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(
                line("ITEM_A", 1, 1000_00L, 18.0),
                line("ITEM_B", 1, 500_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)
            ),
            gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I10", VoucherType.PURCHASE, result)

        val persisted = dao.getGstTransactionsForVoucher("V_I10")
        assertEquals(2, persisted.size)
        assertEquals(1, persisted.count { it.chargeType == GstChargeType.FORWARD_CHARGE })
        assertEquals(1, persisted.count { it.chargeType == GstChargeType.REVERSE_CHARGE })
    }

    @Test
    fun i11_ExistingSalesBehavior_UnchangedByRcmAdditions() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val result = TradingWorkflowEngine.buildSale(
            "V_I11", companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0)), gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I11", VoucherType.SALES, result)

        assertEquals(90_00L, dao.getLedgerById(companyId, "${GstLedgerIds.OUTPUT_CGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        assertEquals(90_00L, dao.getLedgerById(companyId, "${GstLedgerIds.OUTPUT_SGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        // No RCM ledger is ever touched by an ordinary Sale.
        assertEquals(0L, dao.getLedgerById(companyId, "${GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
        assertEquals(0L, dao.getLedgerById(companyId, "${GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID}_$companyId")!!.currentBalancePaise)
    }

    @Test
    fun i12_Rule30_SupplierGstRegistrationStatus_UnchangedByPurchasePosting() = runBlocking {
        val (dao, repo) = purchaseGstSetup()
        repo.ensureGstLedgersExist(companyId)
        val gstLedgers = repo.resolveGstLedgerRefs(companyId)
        val roundOffId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"

        val partyResult = repo.createParty(
            Party(partyId = "", companyId = companyId, ledgerId = "", role = PartyRole.SUPPLIER, entityType = PartyEntityType.BUSINESS, displayName = "RCM Posting Supplier"),
            com.example.accounting.domain.accounting.Ledger(
                ledgerId = "", companyId = companyId, groupId = "", name = "RCM Posting Supplier", stateCode = "27", gstin = "27AAAAA0000A1Z5",
                gstRegistrationStatus = com.example.accounting.domain.accounting.GstRegistrationStatus.REGISTERED
            )
        )
        assertTrue(partyResult is com.example.accounting.core.common.AccountingResult.Success)
        val supplierLedgerId = (partyResult as com.example.accounting.core.common.AccountingResult.Success).data.ledgerId

        val result = TradingWorkflowEngine.buildPurchase(
            "V_I12", companyId, fyId, supplierLedgerId, "RCM Posting Supplier", "27AAAAA0000A1Z5", "LED_PURCHASE", "Purchase", "27", "27",
            listOf(line("ITEM_A", 1, 1000_00L, 18.0).copy(chargeType = GstChargeType.REVERSE_CHARGE)),
            gstLedgers, roundOffId, "Round Off"
        )
        postResult(dao, "V_I12", VoucherType.PURCHASE, result)

        val persistedSupplier = dao.getLedgerById(companyId, supplierLedgerId)!!
        assertEquals("REGISTERED", persistedSupplier.gstRegistrationStatus)
    }
}
