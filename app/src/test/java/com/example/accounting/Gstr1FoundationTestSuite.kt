package com.example.accounting

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.GstReturnArtifactEntity
import com.example.accounting.data.local.entity.GstReturnEntity
import com.example.accounting.data.local.entity.GstReturnSectionEntity
import com.example.accounting.data.local.entity.GstReturnSubmissionEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
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
import com.example.accounting.domain.export.ExportFormat
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.inventory.VoucherStockLine
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstLedgerIds
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstPeriod
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSectionStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import com.example.accounting.domain.taxation.gstreturn.GstinChecksum
import com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr1ValidationSeverity
import com.example.accounting.domain.taxation.gstreturn.Gstr1Validator
import com.example.accounting.domain.taxation.gstreturn.NoteType
import com.example.accounting.domain.trading.LedgerRef
import com.example.accounting.domain.trading.TradingGstLedgers
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 8A, Part 1 - GSTR-1 Foundation test suite. Follows the exact
 * [GstReturnDashboardTestSuite] pattern (real engine/repository logic against a fake-but-real-
 * backed DAO) - a separate file since this exercises the NEW statutory-table builder/validator/
 * automation, not the return lifecycle plumbing Rule 33 already covers.
 */
class Gstr1FoundationTestSuite {

    // ==========================================
    // Pure domain tests - no DAO needed
    // ==========================================

    @Test
    fun t1_GstinChecksum_ValidRealGstin_PassesChecksum() {
        // A real, publicly-documented example GSTIN with a correct check digit.
        assertTrue(GstinChecksum.isValidChecksum("27AAPFU0939F1ZV"))
    }

    @Test
    fun t2_GstinChecksum_TamperedLastCharacter_FailsChecksum() {
        assertFalse(GstinChecksum.isValidChecksum("27AAPFU0939F1ZZ"))
    }

    @Test
    fun t3_GstinChecksum_BlankGstin_IsNeverValid() {
        assertFalse(GstinChecksum.isValidChecksum(""))
    }

    private fun gt(
        voucherId: String, ledgerId: String, gstin: String, pos: String, supplyType: SupplyType,
        hsn: String = "8471", rate: Double = 18.0, taxable: Long = 100_000_00L,
        supplyNature: GstSupplyNature = GstSupplyNature.NORMAL, chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE
    ): GstTransaction {
        val taxableM = Money.fromPaise(taxable)
        val (cgst, sgst, igst) = when (supplyType) {
            SupplyType.INTRA_STATE -> Triple(taxableM.percentage(rate / 2), taxableM.percentage(rate / 2), Money.ZERO)
            SupplyType.INTER_STATE -> Triple(Money.ZERO, Money.ZERO, taxableM.percentage(rate))
            else -> Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }
        return GstTransaction(
            gstTransactionId = UUID.randomUUID().toString(), companyId = "C1", financialYearId = "FY1",
            voucherId = voucherId, voucherType = VoucherType.SALES, partyLedgerId = ledgerId, partyGstin = gstin,
            placeOfSupply = pos, supplyType = supplyType, itemId = null, hsnSacCode = hsn,
            quantity = Quantity.fromLong(1), taxableAmount = taxableM, gstRatePercent = rate,
            cgst = cgst, sgst = sgst, igst = igst, cess = Money.ZERO, direction = GstDirection.OUTPUT, lineOrder = 1,
            chargeType = chargeType, supplyNature = supplyNature
        )
    }

    private fun voucher(id: String, type: VoucherType, date: String, refId: String? = null, cancelled: Boolean = false) = Voucher(
        voucherId = id, companyId = "C1", financialYearId = "FY1", voucherNumber = id, voucherType = type,
        date = LocalDate.parse(date), isCancelled = cancelled, referenceVoucherId = refId
    )

    @Test
    fun t4_Builder_RegisteredInvoice_ClassifiedAsB2B() {
        val txn = gt("V1", "LED_A", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE)
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(txn), mapOf("V1" to voucher("V1", VoucherType.SALES, "2026-04-10")), listOf(voucher("V1", VoucherType.SALES, "2026-04-10")))
        }
        assertEquals(1, data.b2b.size)
        assertEquals("27AAPFU0939F1ZV", data.b2b.first().recipientGstin)
        assertTrue(data.b2cl.isEmpty())
        assertTrue(data.b2cs.isEmpty())
    }

    @Test
    fun t5_Builder_UnregisteredInterState_AboveThreshold_ClassifiedAsB2cl() {
        val txn = gt("V2", "LED_B", "", "09", SupplyType.INTER_STATE, taxable = 3_00_000_00L)
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(txn), mapOf("V2" to voucher("V2", VoucherType.SALES, "2026-04-10")), emptyList())
        }
        assertEquals(1, data.b2cl.size)
        assertTrue(data.b2cs.isEmpty())
    }

    @Test
    fun t6_Builder_UnregisteredInterState_BelowThreshold_ClassifiedAsB2cs() {
        val txn = gt("V3", "LED_C", "", "09", SupplyType.INTER_STATE, taxable = 50_000_00L)
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(txn), mapOf("V3" to voucher("V3", VoucherType.SALES, "2026-04-10")), emptyList())
        }
        assertTrue(data.b2cl.isEmpty())
        assertEquals(1, data.b2cs.size)
    }

    @Test
    fun t7_Builder_UnregisteredIntraState_AlwaysB2cs_RegardlessOfValue() {
        val txn = gt("V4", "LED_D", "", "27", SupplyType.INTRA_STATE, taxable = 10_00_000_00L)
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(txn), mapOf("V4" to voucher("V4", VoucherType.SALES, "2026-04-10")), emptyList())
        }
        assertTrue("Intra-state unregistered supply is never B2CL regardless of value", data.b2cl.isEmpty())
        assertEquals(1, data.b2cs.size)
    }

    @Test
    fun t8_Builder_ExportSupply_ClassifiedAsExport() {
        val txn = gt("V5", "LED_E", "", "96", SupplyType.EXPORT, supplyNature = GstSupplyNature.EXPORT)
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(txn), mapOf("V5" to voucher("V5", VoucherType.SALES, "2026-04-10")), emptyList())
        }
        assertEquals(1, data.exports.size)
    }

    @Test
    fun t9_Builder_ExemptSupply_ClassifiedAsNilRated() {
        val txn = gt("V6", "LED_F", "", "27", SupplyType.EXEMPT, supplyNature = GstSupplyNature.EXEMPT)
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(txn), mapOf("V6" to voucher("V6", VoucherType.SALES, "2026-04-10")), emptyList())
        }
        assertEquals(1, data.nilRated.size)
    }

    @Test
    fun t10_Builder_CreditNoteAgainstRegisteredParty_ClassifiedAsCdnr_WithOriginalInvoiceResolved() {
        val originalSale = gt("V7", "LED_G", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE)
        val note = originalSale.copy(
            gstTransactionId = UUID.randomUUID().toString(), voucherId = "V8", voucherType = VoucherType.CREDIT_NOTE,
            taxableAmount = -originalSale.taxableAmount, cgst = -originalSale.cgst, sgst = -originalSale.sgst
        )
        val vouchers = mapOf(
            "V7" to voucher("V7", VoucherType.SALES, "2026-04-05"),
            "V8" to voucher("V8", VoucherType.CREDIT_NOTE, "2026-04-15", refId = "V7")
        )
        val data = runBlocking {
            Gstr1ReturnBuilder.build("27COMPANY0000A1Z5", "202604", listOf(note), vouchers, emptyList())
        }
        assertEquals(1, data.cdnr.size)
        val resolvedNote = data.cdnr.first().notes.first()
        assertEquals("V7", resolvedNote.originalInvoiceNumber)
        assertEquals(NoteType.CREDIT, resolvedNote.noteType)
    }

    @Test
    fun t11_Builder_CreditNoteAgainstUnregisteredParty_ClassifiedAsCdnur() {
        val originalSale = gt("V9", "LED_H", "", "27", SupplyType.INTRA_STATE)
        val note = originalSale.copy(gstTransactionId = UUID.randomUUID().toString(), voucherId = "V10", voucherType = VoucherType.CREDIT_NOTE, taxableAmount = -originalSale.taxableAmount)
        val vouchers = mapOf(
            "V9" to voucher("V9", VoucherType.SALES, "2026-04-05"),
            "V10" to voucher("V10", VoucherType.CREDIT_NOTE, "2026-04-15", refId = "V9")
        )
        val data = runBlocking { Gstr1ReturnBuilder.build("G", "202604", listOf(note), vouchers, emptyList()) }
        assertEquals(1, data.cdnur.size)
        assertTrue(data.cdnr.isEmpty())
    }

    @Test
    fun t12_Builder_HsnSummary_AggregatesAcrossInvoicesForSameCodeAndRate() {
        val t1 = gt("V11", "LED_I", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE, hsn = "8471", rate = 18.0, taxable = 100_00L)
        val t2 = gt("V12", "LED_J", "", "27", SupplyType.INTRA_STATE, hsn = "8471", rate = 18.0, taxable = 200_00L)
        val vouchers = mapOf("V11" to voucher("V11", VoucherType.SALES, "2026-04-10"), "V12" to voucher("V12", VoucherType.SALES, "2026-04-11"))
        val data = runBlocking { Gstr1ReturnBuilder.build("G", "202604", listOf(t1, t2), vouchers, emptyList()) }
        assertEquals(1, data.hsn.size)
        assertEquals(300_00L, data.hsn.first().taxableValue.paise)
    }

    @Test
    fun t13_Builder_DocumentsIssuedSummary_IncludesCancelledVouchers_ButExcludesFromOtherTables() {
        val active = voucher("INV-0001", VoucherType.SALES, "2026-04-05")
        val cancelled = voucher("INV-0002", VoucherType.SALES, "2026-04-06", cancelled = true)
        val data = runBlocking { Gstr1ReturnBuilder.build("G", "202604", emptyList(), emptyMap(), listOf(active, cancelled)) }
        assertEquals(1, data.documentsIssued.size)
        val row = data.documentsIssued.first()
        assertEquals(2, row.totalCount)
        assertEquals(1, row.cancelledCount)
        assertEquals(1, row.netIssued)
    }

    @Test
    fun t14_Builder_AmendsFiledPeriod_TrueOnlyWhenLookupSaysSo() {
        val originalSale = gt("V13", "LED_K", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE)
        val note = originalSale.copy(gstTransactionId = UUID.randomUUID().toString(), voucherId = "V14", voucherType = VoucherType.CREDIT_NOTE, taxableAmount = -originalSale.taxableAmount)
        val vouchers = mapOf(
            "V13" to voucher("V13", VoucherType.SALES, "2026-04-05"),
            "V14" to voucher("V14", VoucherType.CREDIT_NOTE, "2026-05-15", refId = "V13")
        )
        val data = runBlocking {
            Gstr1ReturnBuilder.build("G", "202605", listOf(note), vouchers, emptyList(), originalInvoicePeriodFiled = { true })
        }
        assertTrue(data.cdnr.first().notes.first().amendsFiledPeriod)
    }

    // ==========================================
    // Validator tests
    // ==========================================

    @Test
    fun t15_Validator_DuplicateInvoiceNumber_FlaggedAsError() {
        val v1 = voucher("INV-DUP", VoucherType.SALES, "2026-04-05")
        val v2 = voucher("INV-DUP", VoucherType.SALES, "2026-04-06").copy(voucherId = "V-OTHER")
        val vouchers = mapOf(v1.voucherId to v1, "V-OTHER" to v2)
        val issues = Gstr1Validator.validate(com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData("G", "202604"), emptyList(), vouchers)
        assertTrue(issues.any { it.code == "DUPLICATE_INVOICE_NUMBER" && it.severity == Gstr1ValidationSeverity.ERROR })
    }

    @Test
    fun t16_Validator_InvalidPlaceOfSupplyCode_FlaggedAsError() {
        val txn = gt("V15", "LED_L", "27AAPFU0939F1ZV", "99", SupplyType.INTER_STATE)
        val issues = Gstr1Validator.validate(com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData("G", "202604"), listOf(txn), emptyMap())
        assertTrue(issues.any { it.code == "INVALID_PLACE_OF_SUPPLY" })
    }

    @Test
    fun t17_Validator_TaxRecomputationMismatch_Detected() {
        val txn = gt("V16", "LED_M", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE, taxable = 100_00L, rate = 18.0)
            .let { it.copy(cgst = Money.fromPaise(999_99L)) } // corrupted
        val issues = Gstr1Validator.validate(com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData("G", "202604"), listOf(txn), emptyMap())
        assertTrue(issues.any { it.code == "TAX_RECOMPUTATION_MISMATCH" })
    }

    @Test
    fun t18_Validator_CompositionScheme_BlocksGstr1() {
        val issues = Gstr1Validator.validate(com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData("G", "202604"), emptyList(), emptyMap(), companyScheme = GstScheme.COMPOSITION)
        assertTrue(issues.any { it.code == "SCHEME_DOES_NOT_FILE_GSTR1" && it.severity == Gstr1ValidationSeverity.ERROR })
    }

    @Test
    fun t19_Validator_InconsistentPartyGstin_FlaggedAsWarning() {
        val withGstin = gt("V17", "LED_N", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE)
        val withoutGstin = gt("V18", "LED_N", "", "27", SupplyType.INTRA_STATE)
        val issues = Gstr1Validator.validate(com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData("G", "202604"), listOf(withGstin, withoutGstin), emptyMap())
        assertTrue(issues.any { it.code == "INCONSISTENT_PARTY_GSTIN" })
    }

    @Test
    fun t20_Validator_SameGstinAcrossDifferentParties_FlaggedAsWarning() {
        val a = gt("V19", "LED_O1", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE)
        val b = gt("V20", "LED_O2", "27AAPFU0939F1ZV", "27", SupplyType.INTRA_STATE)
        val issues = Gstr1Validator.validate(com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData("G", "202604"), listOf(a, b), emptyMap())
        assertTrue(issues.any { it.code == "DUPLICATE_GSTIN_ACROSS_PARTIES" })
    }

    // ==========================================
    // Full repository integration - real DAO/engine/posting, mirroring GstReturnDashboardTestSuite
    // ==========================================

    private class GstReturnAwareDao(delegate: AccountingDao) : AccountingDao by delegate {
        private val gstTransactions = mutableListOf<GstTransactionEntity>()
        private val gstReturns = LinkedHashMap<String, GstReturnEntity>()
        private val artifacts = mutableListOf<GstReturnArtifactEntity>()
        private val sections = LinkedHashMap<String, GstReturnSectionEntity>()
        private val submissions = mutableListOf<GstReturnSubmissionEntity>()

        override suspend fun getGstTransactionsForVoucher(voucherId: String) = gstTransactions.filter { it.voucherId == voucherId }
        override suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String) =
            gstTransactions.filter { it.companyId == companyId && it.financialYearId == fyId }
        override suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>) { gstTransactions += transactions }

        override fun getGstReturnsForCompany(companyId: String) =
            kotlinx.coroutines.flow.flowOf(gstReturns.values.filter { it.companyId == companyId }.sortedByDescending { it.createdAt })
        override suspend fun getGstReturnById(companyId: String, gstReturnId: String) =
            gstReturns[gstReturnId]?.takeIf { it.companyId == companyId }
        override suspend fun findGstReturn(companyId: String, periodKey: String, returnType: String, scheme: String) =
            gstReturns.values.firstOrNull { it.companyId == companyId && it.periodKey == periodKey && it.returnType.name == returnType && it.scheme.name == scheme }
        override suspend fun insertGstReturn(gstReturn: GstReturnEntity) { gstReturns[gstReturn.gstReturnId] = gstReturn }
        override suspend fun updateGstReturn(gstReturn: GstReturnEntity) { gstReturns[gstReturn.gstReturnId] = gstReturn }

        override suspend fun getArtifactsForGstReturn(gstReturnId: String) = artifacts.filter { it.gstReturnId == gstReturnId }.sortedBy { it.createdAt }
        override suspend fun getGstReturnArtifactById(artifactId: String) = artifacts.firstOrNull { it.artifactId == artifactId }
        override suspend fun insertGstReturnArtifact(artifact: GstReturnArtifactEntity) { artifacts += artifact }

        override suspend fun getSectionsForGstReturn(gstReturnId: String) = sections.values.filter { it.gstReturnId == gstReturnId }.sortedBy { it.sectionKey }
        override suspend fun upsertGstReturnSection(section: GstReturnSectionEntity) { sections["${section.gstReturnId}|${section.sectionKey}"] = section }
        override suspend fun deleteGstReturnSectionsNotIn(gstReturnId: String, keepKeys: List<String>) {
            sections.keys.filter { it.startsWith("$gstReturnId|") && sections[it]?.sectionKey !in keepKeys }.forEach { sections.remove(it) }
        }

        override suspend fun getSubmissionsForGstReturn(gstReturnId: String) = submissions.filter { it.gstReturnId == gstReturnId }.sortedBy { it.attemptNumber }
        override suspend fun insertGstReturnSubmission(submission: GstReturnSubmissionEntity) { submissions += submission }
    }

    private val companyId = "COMP_G8A"
    private val fyId = "FY_G8A_2026_27"
    private val fy = FinancialYear.createIndianFY(fyId, companyId, 2026, isCurrent = true)

    private fun freshDao() = GstReturnAwareDao(Phase4TestSuite.InventoryAwareDao(FakeAccountingDao()))

    private suspend fun AccountingDao.seedCompany(scheme: GstScheme = GstScheme.REGULAR, gstEnabled: Boolean = true) {
        insertCompany(
            CompanyEntity(
                companyId = companyId, name = "Company $companyId", tradeName = "Company $companyId", gstin = "27AAPFU0939F1ZV",
                pan = "AAPFU0939F", stateCode = "27", stateName = "Maharashtra", email = "", phone = "", address = "",
                currency = "INR", financialYearStartMonth = 4, isDefault = true, createdAt = 0L,
                accountingMode = AccountingMode.ACCOUNT_WITH_INVENTORY, businessType = BusinessType.TRADING,
                gstScheme = scheme, gstEnabled = gstEnabled
            )
        )
        insertFinancialYear(FinancialYearEntity(fyId, companyId, "2026-27", "2026-04-01", "2027-03-31", true, false, null, null))
        insertPeriods(listOf(AccountingPeriodEntity("PER_$companyId", companyId, fyId, "Full Year", "2026-04-01", "2027-03-31", PeriodStatus.OPEN, null, null)))
        insertGroups(StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        })
    }

    private fun ledger(id: String, groupBare: String, openingType: DrCr = DrCr.DEBIT, stateCode: String = "27") =
        LedgerEntity(id, companyId, "${groupBare}_$companyId", id, id, 0L, openingType, 0L, openingType, "27AAPFU0939F1ZV", "", stateCode, "", "", "", "", "", "", "", false, true, "", 0.0)

    private suspend fun AccountingDao.seedTradingLedgers() {
        insertLedger(ledger("LED_DEBTOR", StandardSystemGroups.DEBTORS_GROUP_ID))
        insertLedger(ledger("LED_CREDITOR", StandardSystemGroups.CREDITORS_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_SALES", StandardSystemGroups.SALES_GROUP_ID, openingType = DrCr.CREDIT))
        insertLedger(ledger("LED_PURCHASE", StandardSystemGroups.PURCHASE_GROUP_ID))
        insertLedger(ledger(StandardSystemGroups.ROUND_OFF_LEDGER_ID + "_$companyId", StandardSystemGroups.ROUND_OFF_GROUP_ID))
    }

    private fun stockItem(itemId: String, openingQty: Long = 100_000L) = StockItemEntity(
        itemId = itemId, companyId = companyId, name = itemId, sku = itemId, hsnCode = "8471", unit = "Pcs", gstRatePercent = 18.0,
        openingQuantity = openingQty, openingRatePaise = 100_00L, currentQuantity = openingQty, standardCostPaise = 100_00L,
        standardSellingPricePaise = 100_00L, currentAvgCostPaise = 100_00L
    )

    private fun gstLedgerRefs() = TradingGstLedgers(
        outputCgst = LedgerRef("${GstLedgerIds.OUTPUT_CGST_LEDGER_ID}_$companyId", "Output CGST A/c"),
        outputSgst = LedgerRef("${GstLedgerIds.OUTPUT_SGST_LEDGER_ID}_$companyId", "Output SGST A/c"),
        outputIgst = LedgerRef("${GstLedgerIds.OUTPUT_IGST_LEDGER_ID}_$companyId", "Output IGST A/c"),
        inputCgst = LedgerRef("${GstLedgerIds.INPUT_CGST_LEDGER_ID}_$companyId", "Input CGST A/c"),
        inputSgst = LedgerRef("${GstLedgerIds.INPUT_SGST_LEDGER_ID}_$companyId", "Input SGST A/c"),
        inputIgst = LedgerRef("${GstLedgerIds.INPUT_IGST_LEDGER_ID}_$companyId", "Input IGST A/c"),
        cess = LedgerRef("${GstLedgerIds.CESS_LEDGER_ID}_$companyId", "CESS A/c"),
        rcmLiabilityCgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID}_$companyId", "RCM Liability CGST A/c"),
        rcmLiabilitySgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_SGST_LEDGER_ID}_$companyId", "RCM Liability SGST A/c"),
        rcmLiabilityIgst = LedgerRef("${GstLedgerIds.RCM_LIABILITY_IGST_LEDGER_ID}_$companyId", "RCM Liability IGST A/c"),
        rcmInputCgst = LedgerRef("${GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID}_$companyId", "RCM Input CGST A/c"),
        rcmInputSgst = LedgerRef("${GstLedgerIds.RCM_INPUT_SGST_LEDGER_ID}_$companyId", "RCM Input SGST A/c"),
        rcmInputIgst = LedgerRef("${GstLedgerIds.RCM_INPUT_IGST_LEDGER_ID}_$companyId", "RCM Input IGST A/c")
    )

    private fun JournalItem.toEntity() = JournalItemEntity(itemId, voucherId, companyId, financialYearId, ledgerId, type, amount.paise, narration, lineOrder)
    private fun VoucherStockLine.toEntity() = VoucherStockLineEntity(lineId, voucherId, companyId, financialYearId, itemId, direction, quantity.rawValue, rate.paise, amount.paise, lineOrder)
    private fun GstTransaction.toEntity() = GstTransactionEntity(
        gstTransactionId, companyId, financialYearId, voucherId, voucherType, partyLedgerId, partyGstin, placeOfSupply,
        supplyType, itemId, hsnSacCode, quantity?.rawValue, taxableAmount.paise, gstRatePercent, cgst.paise, sgst.paise,
        igst.paise, cess.paise, direction, lineOrder, createdAt = 0L, chargeType = chargeType
    )

    private fun line(itemId: String, qty: Long, ratePaise: Long, gstRate: Double) =
        TradingLineInput(itemId = itemId, itemName = itemId, hsnSacCode = "8471", quantity = Quantity.fromLong(qty), rate = Money.fromPaise(ratePaise), gstRatePercent = gstRate)

    private suspend fun postResult(dao: AccountingDao, voucherId: String, voucherType: VoucherType, result: com.example.accounting.domain.trading.TradingWorkflowResult, date: String) {
        val voucherEntity = VoucherEntity(
            voucherId = voucherId, companyId = companyId, financialYearId = fyId, voucherNumber = voucherId, voucherType = voucherType,
            date = date, referenceNumber = "", narration = "", totalAmountPaise = result.totalAmount.paise,
            isPosted = true, isCancelled = false, syncState = SyncState.PENDING, createdAt = 0L, updatedAt = 0L,
            createdBy = "TESTER", partyGstin = "", isGstApplicable = true, referenceVoucherId = null, paymentMode = ""
        )
        com.example.accounting.core.database.VoucherPostingEngine.post(
            dao, voucherEntity, result.journalItems.map { it.toEntity() }, "IK_$voucherId", "TESTER",
            result.stockLines.map { it.toEntity() }, result.gstTransactions.map { it.toEntity() }
        )
    }

    private suspend fun postSale(dao: AccountingDao, voucherId: String, date: String, amountPaise: Long = 100_000_00L) {
        val result = TradingWorkflowEngine.buildSale(
            voucherId, companyId, fyId, "LED_DEBTOR", "Cust", "", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, amountPaise, 18.0)), gstLedgerRefs(),
            "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId", "Round Off"
        )
        postResult(dao, voucherId, VoucherType.SALES, result, date)
    }

    private suspend fun setup(scheme: GstScheme = GstScheme.REGULAR, gstEnabled: Boolean = true): Pair<AccountingDao, AccountingRepository> {
        val dao = freshDao()
        dao.seedCompany(scheme, gstEnabled)
        dao.seedTradingLedgers()
        dao.insertStockItems(listOf(stockItem("ITEM_A")))
        val repo = AccountingRepository(dao)
        repo.ensureGstLedgersExist(companyId)
        return dao to repo
    }

    @Test
    fun t21_PrepareGstReturn_ProducesRealStatutorySections_NotGenericBuckets() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val sections = repo.getGstReturnSections(gr.gstReturnId)
        val keys = sections.map { it.sectionKey }.toSet()
        assertEquals(setOf("B2B", "B2CL", "B2CS", "CDNR", "CDNUR", "EXP", "NIL", "HSN", "DOC_ISSUED"), keys)
    }

    @Test
    fun t21b_PrepareGstReturn_RemovesStaleSectionKeys_FromAnOlderPreDataPart1Prepare() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V1b", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        // Simulate a return PREPAREd before the Part 1 rebuild - stale keys the current builder
        // no longer produces.
        dao.upsertGstReturnSection(
            GstReturnSectionEntity(
                sectionId = "stale-1", gstReturnId = gr.gstReturnId, sectionKey = "B2C",
                status = GstReturnSectionStatus.PREPARED, resultDataJson = "{}", errorsJson = null, updatedAt = 0L
            )
        )
        dao.upsertGstReturnSection(
            GstReturnSectionEntity(
                sectionId = "stale-2", gstReturnId = gr.gstReturnId, sectionKey = "NIL_EXEMPT",
                status = GstReturnSectionStatus.PREPARED, resultDataJson = "{}", errorsJson = null, updatedAt = 0L
            )
        )
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val keys = repo.getGstReturnSections(gr.gstReturnId).map { it.sectionKey }.toSet()
        assertFalse("Stale pre-Part-1 'B2C' key must be removed by a fresh Prepare", "B2C" in keys)
        assertFalse("Stale pre-Part-1 'NIL_EXEMPT' key must be removed by a fresh Prepare", "NIL_EXEMPT" in keys)
        assertEquals(setOf("B2B", "B2CL", "B2CS", "CDNR", "CDNUR", "EXP", "NIL", "HSN", "DOC_ISSUED"), keys)
    }

    @Test
    fun t22_ValidateGstReturn_SurfacesGstr1SpecificIssues() = runBlocking {
        val (dao, repo) = setup()
        // A registered sale with an invalid/tampered GSTIN should fail checksum validation.
        val result = TradingWorkflowEngine.buildSale(
            "V2", companyId, fyId, "LED_DEBTOR", "Cust", "27AAPFU0939F1ZZ", "LED_SALES", "Sales", "27", "27",
            listOf(line("ITEM_A", 1, 100_000_00L, 18.0)), gstLedgerRefs(), "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId", "Round Off"
        )
        // Force the party GSTIN onto the resulting GST transactions the way a real Sale voucher would.
        val withGstin = result.copy(gstTransactions = result.gstTransactions.map { it.copy(partyGstin = "27AAPFU0939F1ZZ") })
        postResult(dao, "V2", VoucherType.SALES, withGstin, "2026-04-10")

        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val validated = repo.validateGstReturn(companyId, gr.gstReturnId, fy)
        assertTrue(validated is AccountingResult.Success)
        val afterValidate = (validated as AccountingResult.Success).data
        assertEquals(GstReturnStatus.VALIDATION_FAILED, afterValidate.status)
        assertNotNull(afterValidate.errorMessage)
        assertTrue(afterValidate.errorMessage!!.contains("INVALID_GSTIN_CHECKSUM"))
    }

    @Test
    fun t23_ExportGstReturn_JsonAndCsvAndGstrJson_AllSucceed() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V3", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)

        val json = repo.exportGstReturnAs(companyId, gr.gstReturnId, fy, ExportFormat.JSON)
        val csv = repo.exportGstReturnAs(companyId, gr.gstReturnId, fy, ExportFormat.CSV)
        val gstrJson = repo.exportGstReturnAs(companyId, gr.gstReturnId, fy, ExportFormat.GSTR_JSON)
        assertTrue(json is AccountingResult.Success)
        assertTrue(csv is AccountingResult.Success)
        assertTrue(gstrJson is AccountingResult.Success)
        // postSale's partyGstin is "" (an unregistered/B2C sale), so "ctin" (B2B-only) never
        // appears - assert on the always-present top-level company GSTIN field instead.
        assertTrue((gstrJson as AccountingResult.Success).data.content.contains("27AAPFU0939F1ZV"))
    }

    @Test
    fun t24_ImportGstReturnDraftJson_RoundTripsExportedContent() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V4", "2026-04-10")
        val gr = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q1, 4, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        repo.prepareGstReturn(companyId, gr.gstReturnId, fy)
        val exported = (repo.exportGstReturnAs(companyId, gr.gstReturnId, fy, ExportFormat.JSON) as AccountingResult.Success).data

        val gr2 = repo.getOrCreateGstReturn(companyId, fy, GstQuarter.Q2, 7, GstScheme.REGULAR, GstReturnType.GSTR1, GstReturnPeriodicity.MONTHLY, GstFilingMode.OFFLINE)
        val imported = repo.importGstReturnDraftJson(companyId, gr2.gstReturnId, exported.content)
        assertTrue(imported is AccountingResult.Success)
        val sections = repo.getGstReturnSections(gr2.gstReturnId)
        assertTrue(sections.isNotEmpty())
    }

    // ==========================================
    // Automation
    // ==========================================

    @Test
    fun t25_Automation_CompositionScheme_SkipsDraftPreparation() = runBlocking {
        val (dao, repo) = setup(scheme = GstScheme.COMPOSITION)
        val checker = com.example.accounting.automation.compliance.GstReturnAutomationChecker(dao, repo)
        val result = checker.prepareCurrentPeriodDraft(companyId)
        assertEquals(com.example.accounting.automation.tasks.TaskExecutionStatus.SKIPPED, result.status)
    }

    @Test
    fun t26_Automation_GstDisabled_SkipsDraftPreparation() = runBlocking {
        val (dao, repo) = setup(gstEnabled = false)
        val checker = com.example.accounting.automation.compliance.GstReturnAutomationChecker(dao, repo)
        val result = checker.prepareCurrentPeriodDraft(companyId)
        assertEquals(com.example.accounting.automation.tasks.TaskExecutionStatus.SKIPPED, result.status)
    }

    @Test
    fun t27_Automation_FilingReminder_OverdueReturn_EmitsCriticalNotification() = runBlocking {
        val (dao, repo) = setup()
        postSale(dao, "V5", "2026-04-10")
        val checker = com.example.accounting.automation.compliance.GstReturnAutomationChecker(dao, repo)
        // "Today" = June 20, so the completed MONTHLY period (May) was due June 11 - well overdue.
        val result = checker.checkFilingReminder(companyId, LocalDate.of(2026, 6, 20))
        assertEquals(com.example.accounting.automation.tasks.TaskExecutionStatus.WARNING, result.status)
    }

    @Test
    fun t28_Automation_NeverCallsMarkFiledOrSubmitOnline() = runBlocking {
        // A structural guarantee, not a behavioral one: GstReturnAutomationChecker's source has no
        // call to either method - verified by the fact that running every automation entry point
        // never moves a return past READY/VALIDATION_FAILED/DRAFT.
        val (dao, repo) = setup()
        postSale(dao, "V6", "2026-04-10")
        val checker = com.example.accounting.automation.compliance.GstReturnAutomationChecker(dao, repo)
        checker.prepareCurrentPeriodDraft(companyId)
        checker.runValidationCheck(companyId)
        checker.checkFilingReminder(companyId)
        // "Today" is whatever the real clock reads when this test runs, so query whichever
        // return prepareCurrentPeriodDraft actually created rather than assuming a fixed period.
        val allReturns = dao.getGstReturnsForCompany(companyId).first()
        assertTrue("prepareCurrentPeriodDraft should have created exactly one GstReturn", allReturns.size == 1)
        val gr = allReturns.first()
        assertTrue(gr.status == GstReturnStatus.READY || gr.status == GstReturnStatus.VALIDATION_FAILED || gr.status == GstReturnStatus.DRAFT)
    }
}
