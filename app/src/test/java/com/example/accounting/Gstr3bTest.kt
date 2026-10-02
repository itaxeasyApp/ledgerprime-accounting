package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr1ValidationSeverity
import com.example.accounting.domain.taxation.gstreturn.Gstr3bBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr3bHeads
import com.example.accounting.domain.taxation.gstreturn.Gstr3bPaymentCalculator
import com.example.accounting.domain.taxation.gstreturn.Gstr3bPortalJsonSerializer
import com.example.accounting.domain.taxation.gstreturn.Gstr3bReconciliation
import com.example.accounting.domain.taxation.gstreturn.Gstr3bReturnData
import com.example.accounting.domain.taxation.gstreturn.toSections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Phase 8, Step 6 - GSTR-3B tables, payment advice, portal JSON and reconciliation. */
@Suppress("UNCHECKED_CAST")
class Gstr3bTest {

    private val companyGstin = "27AAAAA0000A1Z5"

    private fun gt(
        voucherId: String?, supplyType: SupplyType, taxable: Long = 1000_00L, rate: Double = 18.0,
        gstin: String = "", nature: GstSupplyNature = GstSupplyNature.NORMAL, type: VoucherType = VoucherType.SALES,
        direction: GstDirection = GstDirection.OUTPUT, charge: GstChargeType = GstChargeType.FORWARD_CHARGE, pos: String = "27"
    ): GstTransaction {
        val t = Money.fromPaise(taxable)
        val (c, s, i) = when (supplyType) {
            SupplyType.INTRA_STATE -> Triple(t.percentage(rate / 2), t.percentage(rate / 2), Money.ZERO)
            SupplyType.INTER_STATE -> Triple(Money.ZERO, Money.ZERO, t.percentage(rate))
            else -> Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }
        return GstTransaction(
            gstTransactionId = "T${System.nanoTime()}", companyId = "C", financialYearId = "FY", voucherId = voucherId, voucherType = type,
            partyLedgerId = "L", partyGstin = gstin, placeOfSupply = pos, supplyType = supplyType, itemId = null, hsnSacCode = "8471",
            quantity = Quantity.fromLong(1), taxableAmount = t, gstRatePercent = rate, cgst = c, sgst = s, igst = i, cess = Money.ZERO,
            direction = direction, lineOrder = 1, chargeType = charge, supplyNature = nature
        )
    }

    private fun negated(g: GstTransaction, type: VoucherType) =
        g.copy(voucherType = type, taxableAmount = -g.taxableAmount, cgst = -g.cgst, sgst = -g.sgst, igst = -g.igst, cess = -g.cess)

    private fun v(id: String, type: VoucherType, ref: String? = null) = Voucher(
        voucherId = id, companyId = "C", financialYearId = "FY", voucherNumber = id, voucherType = type,
        date = LocalDate.of(2026, 4, 10), referenceVoucherId = ref
    )

    private fun build(vararg rows: GstTransaction): Gstr3bReturnData = Gstr3bBuilder.build(companyGstin, "202604", rows.toList())

    private fun p(v: Long) = Money.fromPaise(v)

    // ---------------------------------------------------------------- 3.1

    @Test
    fun table31_splitsTaxableZeroRatedNilAndReverseCharge() {
        val d = build(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),
            gt("S2", SupplyType.EXPORT, 2000_00L, rate = 0.0, nature = GstSupplyNature.EXPORT),
            gt("S3", SupplyType.EXEMPT, 300_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT),
            gt("S4", SupplyType.EXEMPT, 400_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED),
            gt("P1", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        assertEquals(1000_00L, d.outwardTaxable.taxable.paise)
        assertEquals(90_00L, d.outwardTaxable.cgst.paise)
        assertEquals(2000_00L, d.outwardZeroRated.taxable.paise)
        assertEquals("3.1(c) is nil-rated + exempt taxable value only", 700_00L, d.outwardNilExempt.taxable.paise)
        assertEquals(500_00L, d.inwardReverseCharge.taxable.paise)
        assertEquals(45_00L, d.inwardReverseCharge.sgst.paise)
        assertEquals("no non-GST outward supply is recorded", 0L, d.outwardNonGst.taxable.paise)
    }

    @Test
    fun table31_eachLineOfAMixedInvoiceIsClassifiedOnItsOwn() {
        val taxable = gt("V1", SupplyType.INTRA_STATE, 1000_00L)
        val exempt = gt("V1", SupplyType.EXEMPT, 500_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT)
        listOf(build(taxable, exempt), build(exempt, taxable)).forEach {
            assertEquals(1000_00L, it.outwardTaxable.taxable.paise)
            assertEquals(500_00L, it.outwardNilExempt.taxable.paise)
        }
    }

    @Test
    fun creditNotesNetAgainstTheOutwardTable() {
        val sale = gt("S1", SupplyType.INTRA_STATE, 1000_00L)
        val d = build(sale, negated(sale.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE))
        assertEquals(0L, d.outwardTaxable.taxable.paise)
        assertEquals(0L, d.outwardTaxable.cgst.paise)
    }

    // ---------------------------------------------------------------- 3.2

    @Test
    fun table32_isInterStateToUnregisteredByPlaceOfSupply_only() {
        val d = build(
            gt("S1", SupplyType.INTER_STATE, 1000_00L, pos = "09"),
            gt("S2", SupplyType.INTER_STATE, 500_00L, pos = "09"),
            gt("S3", SupplyType.INTER_STATE, 700_00L, pos = "29", gstin = "29AAPFU0939F1ZV"),   // registered: excluded
            gt("S4", SupplyType.INTRA_STATE, 800_00L)                                          // intra-state: excluded
        )
        assertEquals(1, d.interStateUnregistered.size)
        val row = d.interStateUnregistered.single()
        assertEquals("09", row.posStateCode)
        assertEquals(1500_00L, row.taxable.paise)
        assertEquals(270_00L, row.igst.paise)
    }

    // ---------------------------------------------------------------- 4

    @Test
    fun table4a_forwardChargeIsOth_reverseChargeIsIsrc_importsAndIsdAreZero() {
        val d = build(
            gt("P1", SupplyType.INTRA_STATE, 1000_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT),
            gt("P2", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        val byTy = d.itcAvailable.associateBy { it.type }
        assertEquals(setOf("IMPG", "IMPS", "ISRC", "ISD", "OTH"), byTy.keys)
        assertEquals(90_00L, byTy.getValue("OTH").amounts.cgst.paise)
        assertEquals(45_00L, byTy.getValue("ISRC").amounts.cgst.paise)
        listOf("IMPG", "IMPS", "ISD").forEach { assertEquals(0L, byTy.getValue(it).amounts.totalTax.paise) }
    }

    @Test
    fun table4b_aPurchaseReturnIsAReversal_and4cIsAvailableMinusReversed() {
        val purchase = gt("P1", SupplyType.INTRA_STATE, 1000_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT)
        val ret = negated(purchase.copy(voucherId = "DN1"), VoucherType.DEBIT_NOTE).copy(taxableAmount = p(-400_00L), cgst = p(-36_00L), sgst = p(-36_00L))
        val d = build(purchase, ret)
        assertEquals("the return is a reversal, not a smaller purchase", 90_00L, d.itcAvailable.first { it.type == "OTH" }.amounts.cgst.paise)
        assertEquals(36_00L, d.itcReversed.first { it.type == "OTH" }.amounts.cgst.paise)
        assertEquals(54_00L, d.itcNet.cgst.paise)
        assertEquals(54_00L, d.itcNet.sgst.paise)
    }

    @Test
    fun table4d_andRuleReversals_areZero_theDomainRecordsNoIneligibleItc() {
        val d = build(gt("P1", SupplyType.INTRA_STATE, 1000_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT))
        assertEquals(setOf("RUL", "OTH"), d.itcIneligible.map { it.type }.toSet())
        assertTrue(d.itcIneligible.all { it.amounts.totalTax.paise == 0L })
        assertEquals(0L, d.itcReversed.first { it.type == "RUL" }.amounts.totalTax.paise)
    }

    // ---------------------------------------------------------------- 5

    @Test
    fun table5_exemptInwardIsSplitBySupplierState_unregisteredSupplierCountsIntra() {
        val d = build(
            gt("P1", SupplyType.EXEMPT, 100_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, gstin = "29AAPFU0939F1ZV"),
            gt("P2", SupplyType.EXEMPT, 200_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, gstin = "27AAPFU0939F1ZV"),
            gt("P3", SupplyType.EXEMPT, 50_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT, type = VoucherType.PURCHASE, direction = GstDirection.INPUT)
        )
        val gstRow = d.inwardExempt.first { it.type == "GST" }
        assertEquals(100_00L, gstRow.interState.paise)
        assertEquals(250_00L, gstRow.intraState.paise)
        assertEquals(0L, d.inwardExempt.first { it.type == "NONGST" }.let { it.interState.paise + it.intraState.paise })
    }

    // ---------------------------------------------------------------- 6.1 payment advice

    private fun heads(i: Long = 0, c: Long = 0, s: Long = 0, cess: Long = 0) = Gstr3bHeads(p(i), p(c), p(s), p(cess))

    @Test
    fun payment_igstCreditPaysIgstThenCgstThenSgst() {
        val pay = Gstr3bPaymentCalculator.compute(
            outwardLiability = heads(i = 100, c = 60, s = 60), reverseChargeLiability = heads(), itcAvailable = heads(i = 200)
        )
        assertEquals(100L, pay.paidByIgstCredit.igst.paise)
        assertEquals(60L, pay.paidByIgstCredit.cgst.paise)
        assertEquals(40L, pay.paidByIgstCredit.sgst.paise)
        assertEquals(heads(s = 20), pay.cashPayable.copy())
    }

    @Test
    fun payment_cgstCreditNeverPaysSgst_andPaysIgstOnlyAfterItsOwnHead() {
        val pay = Gstr3bPaymentCalculator.compute(
            outwardLiability = heads(i = 30, c = 40, s = 50), reverseChargeLiability = heads(), itcAvailable = heads(c = 100)
        )
        assertEquals(40L, pay.paidByCgstCredit.cgst.paise)
        assertEquals(30L, pay.paidByCgstCredit.igst.paise)
        assertEquals("CGST credit must never cross to SGST", 0L, pay.paidByCgstCredit.sgst.paise)
        assertEquals(50L, pay.cashPayable.sgst.paise)
        assertEquals(0L, pay.cashPayable.igst.paise)
    }

    @Test
    fun payment_sgstCreditPaysSgstThenIgst_neverCgst() {
        val pay = Gstr3bPaymentCalculator.compute(
            outwardLiability = heads(i = 20, c = 40, s = 10), reverseChargeLiability = heads(), itcAvailable = heads(s = 100)
        )
        assertEquals(10L, pay.paidBySgstCredit.sgst.paise)
        assertEquals(20L, pay.paidBySgstCredit.igst.paise)
        assertEquals(40L, pay.cashPayable.cgst.paise)
    }

    @Test
    fun payment_reverseChargeIsCashOnly_evenWithPlentyOfCredit() {
        val pay = Gstr3bPaymentCalculator.compute(
            outwardLiability = heads(), reverseChargeLiability = heads(c = 90, s = 90), itcAvailable = heads(i = 1000, c = 1000, s = 1000)
        )
        assertEquals(90L, pay.cashPayable.cgst.paise)
        assertEquals(90L, pay.cashPayable.sgst.paise)
        assertEquals(0L, pay.paidByIgstCredit.total.paise + pay.paidByCgstCredit.total.paise + pay.paidBySgstCredit.total.paise)
    }

    @Test
    fun payment_cessCreditOnlyPaysCess_andNegativeHeadsAreTreatedAsZero() {
        val pay = Gstr3bPaymentCalculator.compute(
            outwardLiability = heads(i = -50, c = 10, cess = 30), reverseChargeLiability = heads(), itcAvailable = heads(i = 100, cess = 20)
        )
        assertEquals(0L, pay.outwardLiability.igst.paise)
        assertEquals(20L, pay.paidByCessCredit.cess.paise)
        assertEquals(10L, pay.cashPayable.cess.paise)
        assertEquals("IGST credit then pays the CGST liability", 10L, pay.paidByIgstCredit.cgst.paise)
    }

    @Test
    fun payment_isBuiltFromTheReturn_rcmGoesToCashAndForwardItcOffsetsOutward() {
        val d = build(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),                                                                      // 90 + 90
            gt("P1", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT),             // ITC 45 + 45
            gt("P2", SupplyType.INTRA_STATE, 300_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE) // RCM 27 + 27
        )
        // net ITC 72 + 72 (forward 45 + RCM-ITC 27); outward 90 + 90 -> credit pays 72 each; cash = 18 + 27 per head
        assertEquals(27_00L, d.payment.reverseChargeLiability.cgst.paise)
        assertEquals(72_00L, d.payment.paidByCgstCredit.cgst.paise)
        assertEquals(18_00L + 27_00L, d.payment.cashPayable.cgst.paise)
    }

    // ---------------------------------------------------------------- portal JSON

    @Test
    fun portalJson_hasTheGstnStructure_andNoGenericEnvelope_noPortalComputedTables() {
        val j = Gstr3bPortalJsonSerializer.serialize(build(gt("S1", SupplyType.INTRA_STATE, 1000_00L)))
        assertEquals(companyGstin, j["gstin"])
        assertEquals("042026", j["ret_period"])
        listOf("sup_details", "inter_sup", "itc_elg", "inward_sup").forEach { assertTrue("missing $it", j.containsKey(it)) }
        listOf("schemaVersion", "exportType", "generatedAt", "data").forEach { assertFalse("$it belongs to the generic envelope", j.containsKey(it)) }
        assertFalse("interest/late fee are calculated by the portal", j.containsKey("intr_ltfee"))
        assertFalse("payment is made on the portal", j.containsKey("tx_pmt"))
        val sup = j["sup_details"] as Map<String, Any?>
        assertEquals(setOf("osup_det", "osup_zero", "osup_nil_exmp", "isup_rev", "osup_nongst"), sup.keys)
        val det = sup["osup_det"] as Map<String, Any?>
        assertEquals(1000.0, det["txval"]); assertEquals(90.0, det["camt"]); assertEquals(90.0, det["samt"]); assertEquals(0.0, det["iamt"])
    }

    @Test
    fun portalJson_ityCodes_followTheGstnLists_andCompUinAreEmpty() {
        val j = Gstr3bPortalJsonSerializer.serialize(build(gt("S1", SupplyType.INTER_STATE, 1000_00L, pos = "09")))
        val itc = j["itc_elg"] as Map<String, Any?>
        // IMPG/IMPS/ISD, RUL reversals and ineligible ITC are not recorded, so they are omitted, never written as 0.00.
        assertEquals(listOf("ISRC", "OTH"), (itc["itc_avl"] as List<Map<String, Any?>>).map { it["ty"] })
        assertEquals(listOf("OTH"), (itc["itc_rev"] as List<Map<String, Any?>>).map { it["ty"] })
        assertFalse(itc.containsKey("itc_inelg"))
        assertEquals(setOf("iamt", "camt", "samt", "csamt"), (itc["itc_net"] as Map<String, Any?>).keys)
        val inter = j["inter_sup"] as Map<String, Any?>
        val unreg = (inter["unreg_details"] as List<Map<String, Any?>>).single()
        assertEquals("09", unreg["pos"]); assertEquals(1000.0, unreg["txval"]); assertEquals(180.0, unreg["iamt"])
        assertTrue((inter["comp_details"] as List<*>).isEmpty() && (inter["uin_details"] as List<*>).isEmpty())
        val inward = (j["inward_sup"] as Map<String, Any?>)["isup_details"] as List<Map<String, Any?>>
        assertEquals(listOf("GST", "NONGST"), inward.map { it["ty"] })
    }

    @Test
    fun portalJson_aNegativeNetIsWrittenAsZero_likeThePortalsOwnAutoPopulation() {
        val sale = gt("S1", SupplyType.INTRA_STATE, 1000_00L)
        val bigReturn = negated(sale.copy(voucherId = "CN1", taxableAmount = p(2000_00L), cgst = p(180_00L), sgst = p(180_00L)), VoucherType.CREDIT_NOTE)
        val j = Gstr3bPortalJsonSerializer.serialize(build(sale, bigReturn))
        val det = (j["sup_details"] as Map<String, Any?>)["osup_det"] as Map<String, Any?>
        listOf("txval", "iamt", "camt", "samt", "csamt").forEach { assertTrue("$it must not be negative", (det[it] as Double) >= 0.0) }
    }

    @Test
    fun sections_carryTheStatutoryTables() {
        val s = build(gt("S1", SupplyType.INTRA_STATE, 1000_00L)).toSections()
        assertEquals(setOf("3_1", "3_2", "4", "5", "6_1"), s.keys)
        assertEquals(setOf("a_outwardTaxable", "b_outwardZeroRated", "c_outwardNilExempt", "d_inwardReverseCharge", "e_outwardNonGst"), s.getValue("3_1").keys)
        assertEquals(setOf("a_itcAvailable", "b_itcReversed", "c_netItc", "d_itcIneligible"), s.getValue("4").keys)
    }

    // ---------------------------------------------------------------- reconciliation

    private fun gstr1(rows: List<GstTransaction>, vouchers: List<Voucher>) =
        runBlocking { Gstr1ReturnBuilder.build(companyGstin, "202604", rows, vouchers.associateBy { it.voucherId }, vouchers) }

    @Test
    fun reconciliation_agreesWhenBothReturnsReadTheSameRows() {
        val rows = listOf(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L), gt("S2", SupplyType.INTRA_STATE, 2000_00L, gstin = "27AAPFU0939F1ZV"),
            gt("S3", SupplyType.EXPORT, 500_00L, rate = 0.0, nature = GstSupplyNature.EXPORT), gt("S4", SupplyType.EXEMPT, 100_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT)
        )
        val vouchers = listOf("S1", "S2", "S3", "S4").map { v(it, VoucherType.SALES) }
        val issues = Gstr3bReconciliation.reconcile(Gstr3bBuilder.build(companyGstin, "202604", rows), gstr1(rows, vouchers), rows)
        assertTrue(issues.joinToString { it.message }, issues.isEmpty())
    }

    @Test
    fun reconciliation_withNotesAndAnExportNote_stillAgrees() {
        val sale = gt("S1", SupplyType.INTRA_STATE, 1000_00L, gstin = "27AAPFU0939F1ZV")
        val exp = gt("S2", SupplyType.EXPORT, 500_00L, rate = 0.0, nature = GstSupplyNature.EXPORT)
        val rows = listOf(
            sale, negated(sale.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE), exp, negated(exp.copy(voucherId = "CN2"), VoucherType.CREDIT_NOTE)
        )
        val vouchers = listOf(v("S1", VoucherType.SALES), v("S2", VoucherType.SALES), v("CN1", VoucherType.CREDIT_NOTE, "S1"), v("CN2", VoucherType.CREDIT_NOTE, "S2"))
        val issues = Gstr3bReconciliation.reconcile(Gstr3bBuilder.build(companyGstin, "202604", rows), gstr1(rows, vouchers), rows)
        assertTrue(issues.joinToString { it.message }, issues.isEmpty())
    }

    @Test
    fun reconciliation_flagsASupplyThatReachedGstr3bButNotGstr1() {
        // A GST-only sale to a registered party has no invoice number, so GSTR-1 cannot place it in B2B.
        val rows = listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L), gt(null, SupplyType.INTRA_STATE, 700_00L, gstin = "27AAPFU0939F1ZV"))
        val issues = Gstr3bReconciliation.reconcile(Gstr3bBuilder.build(companyGstin, "202604", rows), gstr1(rows, listOf(v("S1", VoucherType.SALES))), rows)
        val mismatch = issues.single { it.code == "GSTR3B_GSTR1_MISMATCH" }
        assertEquals(Gstr1ValidationSeverity.ERROR, mismatch.severity)
        assertTrue(mismatch.message, mismatch.message.contains("taxable value"))
    }

    @Test
    fun reconciliation_flagsRowsThatDoNotMatchTheTable_andRcm() {
        val rows = listOf(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),
            gt("P1", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        val built = Gstr3bBuilder.build(companyGstin, "202604", rows)
        val tampered = built.copy(
            outwardTaxable = built.outwardTaxable.copy(taxable = p(1100_00L)),
            inwardReverseCharge = built.inwardReverseCharge.copy(cgst = p(1L))
        )
        val codes = Gstr3bReconciliation.reconcile(tampered, gstr1(rows, listOf(v("S1", VoucherType.SALES))), rows).map { it.code }
        assertTrue("GSTR3B_TRANSACTION_MISMATCH" in codes)
        assertTrue("GSTR3B_RCM_MISMATCH" in codes)
    }

    @Test
    fun reconciliation_comparesGstLedgersToTheGstRows_asAWarningOnly() {
        val rows = listOf(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),
            gt("P1", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT),
            gt("P2", SupplyType.INTRA_STATE, 300_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        val d3b = Gstr3bBuilder.build(companyGstin, "202604", rows)
        val d1 = gstr1(rows, listOf(v("S1", VoucherType.SALES)))
        val matching = Gstr3bReconciliation.LedgerTotals(
            outputTax = heads(c = 90_00, s = 90_00), forwardInputTax = heads(c = 45_00, s = 45_00), reverseChargeLiability = heads(c = 27_00, s = 27_00)
        )
        assertTrue(Gstr3bReconciliation.reconcile(d3b, d1, rows, matching).isEmpty())

        val drifted = matching.copy(outputTax = heads(c = 95_00, s = 90_00))
        val issue = Gstr3bReconciliation.reconcile(d3b, d1, rows, drifted).single()
        assertEquals("GSTR3B_LEDGER_MISMATCH", issue.code)
        assertEquals("a manual journal can move a GST ledger, so this informs and does not block", Gstr1ValidationSeverity.WARNING, issue.severity)
        assertTrue(issue.message.contains("Output CGST"))
    }
}
