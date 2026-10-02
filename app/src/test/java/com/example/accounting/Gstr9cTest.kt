package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.Gstr1ValidationSeverity
import com.example.accounting.domain.taxation.gstreturn.Gstr3bBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr3bHeads
import com.example.accounting.domain.taxation.gstreturn.Gstr3bReconciliation
import com.example.accounting.domain.taxation.gstreturn.Gstr9Builder
import com.example.accounting.domain.taxation.gstreturn.Gstr9CoverageStatus
import com.example.accounting.domain.taxation.gstreturn.Gstr9cApplicabilityStatus
import com.example.accounting.domain.taxation.gstreturn.Gstr9cBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr9cReconciliation
import com.example.accounting.domain.taxation.gstreturn.Gstr9cReturnData
import com.example.accounting.domain.taxation.gstreturn.toSections
import com.example.accounting.domain.taxation.gstreturn.toWorkingPaperTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8, Step 9 - GSTR-9C: applicability, the books-vs-GSTR-9 reconciliations, coverage and the working paper. */
@Suppress("UNCHECKED_CAST")
class Gstr9cTest {

    private val gstin = "27AAAAA0000A1Z5"

    private fun gt(
        voucherId: String, supplyType: SupplyType, taxable: Long, rate: Double = 18.0, partyGstin: String = "",
        nature: GstSupplyNature = GstSupplyNature.NORMAL, type: VoucherType = VoucherType.SALES,
        direction: GstDirection = GstDirection.OUTPUT, charge: GstChargeType = GstChargeType.FORWARD_CHARGE
    ): GstTransaction {
        val t = Money.fromPaise(taxable)
        val (c, s, i) = when (supplyType) {
            SupplyType.INTRA_STATE -> Triple(t.percentage(rate / 2), t.percentage(rate / 2), Money.ZERO)
            SupplyType.INTER_STATE -> Triple(Money.ZERO, Money.ZERO, t.percentage(rate))
            else -> Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }
        return GstTransaction(
            gstTransactionId = "T${System.nanoTime()}", companyId = "C", financialYearId = "FY", voucherId = voucherId, voucherType = type,
            partyLedgerId = "L", partyGstin = partyGstin, placeOfSupply = "27", supplyType = supplyType, itemId = null, hsnSacCode = "8471",
            quantity = Quantity.fromLong(1), taxableAmount = t, gstRatePercent = rate, cgst = c, sgst = s, igst = i, cess = Money.ZERO,
            direction = direction, lineOrder = 1, chargeType = charge, supplyNature = nature
        )
    }

    private fun p(v: Long) = Money.fromPaise(v)
    private fun heads(i: Long = 0, c: Long = 0, s: Long = 0) = Gstr3bHeads(p(i), p(c), p(s), p(0))

    private fun ledgers(output: Gstr3bHeads = heads(), rcm: Gstr3bHeads = heads()) =
        Gstr3bReconciliation.LedgerTotals(outputTax = output, forwardInputTax = heads(), reverseChargeLiability = rcm)

    /** Builds GSTR-9C for [rows], with [books] as the books' turnover and ledgers matching the rows unless overridden. */
    private fun build(rows: List<GstTransaction>, books: Money, ledgers: Gstr3bReconciliation.LedgerTotals? = null): Gstr9cReturnData {
        val g3b = Gstr3bBuilder.build(gstin, "2026-27-ANNUAL", rows)
        val d9 = Gstr9Builder.build(gstin, "2026-27", 2027, rows, g3b, emptyList())
        val matching = ledgers ?: ledgers(
            output = heads(i = g3b.payment.outwardLiability.igst.paise, c = g3b.payment.outwardLiability.cgst.paise, s = g3b.payment.outwardLiability.sgst.paise),
            rcm = heads(i = g3b.payment.reverseChargeLiability.igst.paise, c = g3b.payment.reverseChargeLiability.cgst.paise, s = g3b.payment.reverseChargeLiability.sgst.paise)
        )
        return Gstr9cBuilder.build(gstin, "2026-27", d9, books, matching)
    }

    // ---------------------------------------------------------------- applicability

    @Test
    fun applicability_isRequiredOnlyAboveRs5Crore_onThisGstinsTurnover() {
        val above = build(listOf(gt("S1", SupplyType.INTRA_STATE, 5_00_00_001_00L)), p(5_00_00_001_00L))
        assertEquals(Gstr9cApplicabilityStatus.REQUIRED_ON_THIS_GSTIN, above.applicability.status)
        val exactly = build(listOf(gt("S1", SupplyType.INTRA_STATE, 5_00_00_000_00L)), p(5_00_00_000_00L))
        assertEquals("the threshold is 'exceeds', so exactly Rs 5 crore is not required", Gstr9cApplicabilityStatus.NOT_REQUIRED_ON_THIS_GSTIN, exactly.applicability.status)
        assertEquals(5_00_00_000_00L, Gstr9cBuilder.TURNOVER_THRESHOLD.paise)
    }

    @Test
    fun applicability_neverClaimsCertainty_theThresholdIsPanWide() {
        val d = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1000_00L))
        assertEquals(Gstr9cApplicabilityStatus.NOT_REQUIRED_ON_THIS_GSTIN, d.applicability.status)
        assertTrue(d.applicability.note.contains("PAN"))
        assertEquals("turnover is GSTR-9's net outward turnover", 1000_00L, d.applicability.turnoverOfThisGstin.paise)
    }

    // ---------------------------------------------------------------- Table 5 / Table 7

    @Test
    fun table5_unreconciledIsGstr9MinusBooks_theManualsQMinusP() {
        val rows = listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L))
        assertEquals(0L, build(rows, p(1000_00L)).turnover.unreconciled.paise)
        assertEquals("books higher than the return: 5R is negative", -500_00L, build(rows, p(1500_00L)).turnover.unreconciled.paise)
        assertEquals("books lower than the return: 5R is positive", 200_00L, build(rows, p(800_00L)).turnover.unreconciled.paise)
    }

    @Test
    fun table5_turnoverPerGstr9_includesTable4And5_netOfCreditNotes_andExcludesInwardReverseCharge() {
        val sale = gt("S1", SupplyType.INTRA_STATE, 1000_00L)
        val d = build(
            listOf(
                sale,
                gt("S2", SupplyType.EXEMPT, 300_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT),
                gt("S3", SupplyType.EXPORT, 2000_00L, rate = 0.0, nature = GstSupplyNature.EXPORT),
                sale.copy(voucherId = "CN1", voucherType = VoucherType.CREDIT_NOTE, taxableAmount = p(-400_00L), cgst = p(-36_00L), sgst = p(-36_00L)),
                gt("P1", SupplyType.INTRA_STATE, 900_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
            ),
            p(2900_00L)
        )
        assertEquals("1000 - 400 + 300 + 2000", 2900_00L, d.turnover.turnoverPerGstr9.paise)
        assertEquals(0L, d.turnover.unreconciled.paise)
    }

    @Test
    fun table7_deductsExemptNilAndZeroRatedWithoutPayment_andUsesGstr9Table4AsTheReturnSide() {
        val d = build(
            listOf(
                gt("S1", SupplyType.INTRA_STATE, 1000_00L),
                gt("S2", SupplyType.EXEMPT, 300_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT),
                gt("S3", SupplyType.EXEMPT, 200_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED),
                gt("S4", SupplyType.EXPORT, 2000_00L, rate = 0.0, nature = GstSupplyNature.EXPORT)
            ),
            p(3500_00L)
        )
        val t = d.taxableTurnover
        assertEquals(500_00L, t.exemptedAndNilRated.paise)
        assertEquals(2000_00L, t.zeroRatedWithoutPayment.paise)
        assertEquals("7E = 7A - 7B - 7C", 1000_00L, t.taxableTurnoverPerBooks.paise)
        assertEquals("7F = GSTR-9 Table 4", 1000_00L, t.taxableTurnoverPerGstr9.paise)
        assertEquals("7G = 7F - 7E", 0L, t.unreconciled.paise)
    }

    @Test
    fun table7_aBooksDifferenceShowsAsUnreconciled_neverSilentlyAbsorbed() {
        val d = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1300_00L))
        assertEquals(-300_00L, d.taxableTurnover.unreconciled.paise)
        assertEquals(-300_00L, d.turnover.unreconciled.paise)
    }

    // ---------------------------------------------------------------- Table 9 / Table 11

    @Test
    fun table9_comparesGstLedgersToGstr9Payable_perHead_includingReverseCharge() {
        val rows = listOf(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),                                                                                                          // 90 + 90
            gt("P1", SupplyType.INTRA_STATE, 300_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)         // RCM 27 + 27
        )
        val d = build(rows, p(1000_00L))
        val byHead = d.taxPaid.associateBy { it.head }
        assertEquals(setOf("IGST", "CGST", "SGST"), byHead.keys)
        assertEquals("books = output 90 + RCM 27", 117_00L, byHead.getValue("CGST").perBooks.paise)
        assertEquals(117_00L, byHead.getValue("CGST").perGstr9.paise)
        assertEquals(0L, byHead.getValue("CGST").unreconciled.paise)
        assertEquals(0L, d.additionalLiability.total.paise)
    }

    @Test
    fun table11_additionalLiabilityIsOnlyThePositiveShortfall_oftheBooksAboveGstr9() {
        val rows = listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L))   // GSTR-9 payable CGST 90, SGST 90
        val d = build(rows, p(1000_00L), ledgers(output = heads(i = 40_00L, c = 130_00L, s = 60_00L)))
        assertEquals("IGST 40 in the books, 0 in GSTR-9", 40_00L, d.additionalLiability.igst.paise)
        assertEquals("CGST 130 in the books vs 90 in GSTR-9", 40_00L, d.additionalLiability.cgst.paise)
        assertEquals("SGST 60 in the books is LESS than GSTR-9's 90: no additional liability", 0L, d.additionalLiability.sgst.paise)
        assertEquals(80_00L, d.additionalLiability.total.paise)
        assertEquals("the sign is GSTR-9 minus books", -40_00L, d.taxPaid.first { it.head == "CGST" }.unreconciled.paise)
    }

    // ---------------------------------------------------------------- findings

    @Test
    fun findings_areAllWarnings_neverBlocking_andCarryTheirCodes() {
        val d = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1500_00L), ledgers(output = heads(c = 130_00L, s = 90_00L)))
        val issues = Gstr9cReconciliation.issues(d)
        val codes = issues.map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("GSTR9C_NOT_REQUIRED", "GSTR9C_TURNOVER_UNRECONCILED", "GSTR9C_TAXABLE_TURNOVER_UNRECONCILED", "GSTR9C_TAX_UNRECONCILED", "GSTR9C_ADDITIONAL_LIABILITY")))
        assertTrue("a difference is a disclosure to explain, not a block", issues.all { it.severity == Gstr1ValidationSeverity.WARNING })
    }

    @Test
    fun findings_areEmptyWhenRequiredAndEverythingReconciles() {
        val big = 6_00_00_000_00L
        val d = build(listOf(gt("S1", SupplyType.INTRA_STATE, big)), p(big))
        assertEquals(Gstr9cApplicabilityStatus.REQUIRED_ON_THIS_GSTIN, d.applicability.status)
        assertTrue(Gstr9cReconciliation.issues(d).joinToString { it.message }.isEmpty())
    }

    // ---------------------------------------------------------------- Part B, coverage, working paper

    @Test
    fun partB_holdsOnlyWhatTheAppKnows_theCertificationIsNeverFilledIn() {
        val b = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1000_00L)).partB
        assertEquals(gstin, b.gstin)
        assertEquals("2026-27", b.financialYear)
        assertTrue(b.inputsRequiredFromTaxpayer.any { it.contains("designation") })
        assertTrue(b.inputsRequiredFromTaxpayer.any { it.contains("declaration") })
        assertTrue(b.inputsRequiredFromTaxpayer.any { it.contains("Part V") })
    }

    @Test
    fun coverage_declaresEveryUnsupportedTable_withTheOfficialNumbering() {
        val byTable = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1000_00L)).coverage.associateBy { it.table }
        listOf("6", "8", "10", "12-16", "Part V", "17", "JSON").forEach {
            assertEquals("$it must be declared unsupported", Gstr9CoverageStatus.NOT_SUPPORTED, byTable.getValue(it).status)
            assertTrue("$it needs a reason", byTable.getValue(it).note.isNotBlank())
        }
        listOf("Applicability", "5", "7", "9", "11", "Part B").forEach { assertEquals(Gstr9CoverageStatus.PARTIAL, byTable.getValue(it).status) }
        val t5 = byTable.getValue("5").note
        listOf("5B", "5O", "unbilled revenue", "foreign exchange", "BOOKS", "audited").forEach { assertTrue("Table 5 note must mention $it", t5.contains(it)) }
        assertTrue(byTable.getValue("12-16").note.contains("GSTR-2A/2B"))
        assertTrue("late fee and interest are the portal's", byTable.getValue("17").note.contains("portal") && byTable.getValue("11").note.contains("portal"))
        assertTrue("no GSTN upload file is produced", byTable.getValue("JSON").note.contains("No upload file is produced"))
    }

    @Test
    fun sections_carryEveryTable_andNothingOfTheCertification() {
        val s = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1000_00L)).toSections()
        assertEquals(setOf("APPLICABILITY", "5", "7", "9", "11", "PART_B", "COVERAGE"), s.keys)
        assertTrue((s.getValue("5")["basis"] as String).contains("not an audited figure"))
        assertTrue(s.getValue("11")["interest"].toString().contains("portal"))
        assertFalse(s.getValue("PART_B").keys.any { it.contains("signator") || it.contains("declaration") })
    }

    @Test
    fun workingPaper_isLabelledNotAGstnUploadFile_andHasNoGstnKeys() {
        val tree = build(listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L)), p(1000_00L)).toWorkingPaperTree()
        assertTrue((tree["document"] as String).contains("NOT a GSTN upload file"))
        assertEquals(gstin, tree["gstin"])
        listOf("fp", "table5", "table7", "table9").forEach { assertFalse("$it would imitate the GSTN schema", tree.containsKey(it)) }
    }
}
