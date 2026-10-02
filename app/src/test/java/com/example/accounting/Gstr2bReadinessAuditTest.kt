package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.Gstr3bBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr3bPortalJsonSerializer
import com.example.accounting.domain.taxation.gstreturn.Gstr9Builder
import com.example.accounting.domain.taxation.gstreturn.Gstr9CoverageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 Step 11 - GSTR-2B readiness audit. Pins down what the app really supports on the inward/ITC
 * side. AUDIT_ tests describe the current (books-only) behaviour and pass; BROKEN_ tests state a
 * required behaviour that is not met yet and fail on purpose (same convention as Phase8BrokenLinkAuditTest).
 */
class Gstr2bReadinessAuditTest {

    private fun purchase(
        id: String, taxable: Long, igst: Long = 0, cgst: Long = 0, sgst: Long = 0, gstin: String = "29ABCDE1234F1Z5",
        type: VoucherType = VoucherType.PURCHASE, charge: GstChargeType = GstChargeType.FORWARD_CHARGE
    ) = GstTransaction(
        gstTransactionId = id, companyId = "C", financialYearId = "FY", voucherId = id, voucherType = type,
        partyLedgerId = "L", partyGstin = gstin, placeOfSupply = "27",
        supplyType = if (igst != 0L) SupplyType.INTER_STATE else SupplyType.INTRA_STATE, itemId = null, hsnSacCode = "8471",
        quantity = Quantity.fromLong(1), taxableAmount = Money.fromPaise(taxable), gstRatePercent = 18.0,
        cgst = Money.fromPaise(cgst), sgst = Money.fromPaise(sgst), igst = Money.fromPaise(igst), cess = Money.ZERO,
        direction = GstDirection.INPUT, lineOrder = 1, chargeType = charge
    )

    private fun build(vararg rows: GstTransaction) = Gstr3bBuilder.build("27AAAAA0000A1Z5", "2026-04", rows.toList())

    private fun classExists(name: String) = try { Class.forName(name); true } catch (_: ClassNotFoundException) { false }

    // ---- what exists -------------------------------------------------------------------------

    @Test fun AUDIT_noGstr2bDomainModelOrImporterExists() {
        val pkg = "com.example.accounting.domain.taxation.gstreturn."
        val candidates = listOf("Gstr2bModels", "Gstr2bImporter", "Gstr2bParser", "Gstr2bReconciliation", "Gstr2bDocument", "Gstr2bReturnData", "Gstr2aModels")
        assertTrue("no GSTR-2B type may exist yet: ${candidates.filter { classExists(pkg + it) }}", candidates.none { classExists(pkg + it) })
    }

    @Test fun AUDIT_table4IsBuiltFromBooksOnly_forwardPurchaseIsOthRow() {
        val d = build(purchase("P1", 1000_00, cgst = 90_00, sgst = 90_00))
        val oth = d.itcAvailable.first { it.type == "OTH" }.amounts
        assertEquals(90_00L, oth.cgst.paise)
        assertEquals(90_00L, oth.sgst.paise)
        assertEquals(180_00L, d.itcNet.totalTax.paise)
    }

    @Test fun AUDIT_rcmInwardIsIsrcRow_andForwardOnlyGoesToOth() {
        val d = build(purchase("R1", 1000_00, igst = 180_00, charge = GstChargeType.REVERSE_CHARGE))
        assertEquals(180_00L, d.itcAvailable.first { it.type == "ISRC" }.amounts.igst.paise)
        assertEquals(0L, d.itcAvailable.first { it.type == "OTH" }.amounts.totalTax.paise)
    }

    @Test fun AUDIT_purchaseReturnIsTheOnlyReversalRecorded() {
        val d = build(
            purchase("P1", 1000_00, cgst = 90_00, sgst = 90_00),
            purchase("DN1", -400_00, cgst = -36_00, sgst = -36_00, type = VoucherType.DEBIT_NOTE)
        )
        assertEquals(36_00L, d.itcReversed.first { it.type == "OTH" }.amounts.cgst.paise)
        assertEquals(0L, d.itcReversed.first { it.type == "RUL" }.amounts.totalTax.paise)
        assertEquals(108_00L, d.itcNet.totalTax.paise)
    }

    @Test fun AUDIT_gstr9CoverageStatesTables6_7_8AreNotSupported() {
        val cov = Gstr9Builder.coverage().associateBy { it.table }
        listOf("6", "7", "8").forEach { assertEquals("Table $it", Gstr9CoverageStatus.NOT_SUPPORTED, cov.getValue(it).status) }
        assertTrue(cov.getValue("8").note.contains("GSTR-2A/2B"))
    }

    /** Was AUDIT_gstTransactionCarriesNoSupplierDocumentNumberOrDate until Step 13 added the identity fields. */
    @Test fun FIXED_S13_gstTransactionCarriesSupplierDocumentNumberAndDate_nullMeansNotRecorded() {
        val names = GstTransaction::class.java.declaredFields.map { it.name }
        assertTrue(names.containsAll(listOf("partyGstin", "supplierDocumentNumber", "supplierDocumentDate")))
        val t = purchase("P0", 1000_00, cgst = 90_00, sgst = 90_00)
        assertEquals(null, t.supplierDocumentNumber)
        assertEquals(null, t.supplierDocumentDate)
    }

    // ---- required, not yet met ---------------------------------------------------------------

    /** Every ITC figure here is "all eligible, as booked". Nothing says so, and rows for unrecorded data are 0.00. */
    @Test fun FIXED_G2B_table4_unrecordedCategoriesAreNotWrittenAsZeroInUploadJson() {
        val json = Gstr3bPortalJsonSerializer.serialize(build(purchase("P1", 1000_00, cgst = 90_00, sgst = 90_00)))
        @Suppress("UNCHECKED_CAST")
        val itc = json["itc_elg"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val avl = itc["itc_avl"] as List<Map<String, Any?>>
        val written = avl.map { it["ty"] }
        // IMPG/IMPS/ISD are data the app never records; writing them as 0.00 asserts "no imports, no ISD credit".
        assertTrue("unrecorded ITC types must not be written as zero, found $written", written.none { it in setOf("IMPG", "IMPS", "ISD") })
        assertEquals(listOf("ISRC", "OTH"), written)
        @Suppress("UNCHECKED_CAST")
        val rev = (itc["itc_rev"] as List<Map<String, Any?>>).map { it["ty"] }
        assertEquals("RUL (rule reversals) is not recorded, only purchase returns (OTH) are", listOf("OTH"), rev)
        assertFalse("ineligible ITC is not recorded, so it is not claimed as zero", itc.containsKey("itc_inelg"))
    }

    @Test fun FIXED_G2B_unreconciledFindingIsAnError_namesTheUnavailableReconciliation_andListsWhatIsNotRecorded() {
        val issue = com.example.accounting.domain.taxation.gstreturn.Gstr3bReconciliation.itcNotReconciledWithGstr2b()
        assertEquals(com.example.accounting.domain.taxation.gstreturn.Gstr1ValidationSeverity.ERROR, issue.severity)
        assertEquals("GSTR3B_ITC_GSTR2B_NOT_RECONCILED", issue.code)
        assertTrue(issue.message.contains("GSTR-2B reconciliation is unavailable"))
        listOf("IMPG/IMPS", "ISD", "ineligible").forEach { assertTrue("must list $it as not recorded", issue.message.contains(it)) }
        assertTrue(issue.message.contains("NOT RECORDED"))
    }

    @Test fun AUDIT_stillNoGstr2bReconciliationEngineExists_soTheBlockCannotLiftYet() {
        assertFalse(classExists("com.example.accounting.domain.taxation.gstreturn.Gstr2bReconciliation"))
    }
}
