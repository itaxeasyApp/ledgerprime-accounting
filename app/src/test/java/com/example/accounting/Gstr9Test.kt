package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.Gstr1HsnRow
import com.example.accounting.domain.taxation.gstreturn.Gstr1ValidationSeverity
import com.example.accounting.domain.taxation.gstreturn.Gstr3bBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr9Builder
import com.example.accounting.domain.taxation.gstreturn.Gstr9CoverageStatus
import com.example.accounting.domain.taxation.gstreturn.Gstr9PortalJsonSerializer
import com.example.accounting.domain.taxation.gstreturn.Gstr9Reconciliation
import com.example.accounting.domain.taxation.gstreturn.Gstr9ReturnData
import com.example.accounting.domain.taxation.gstreturn.toSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8, Step 8 - GSTR-9 annual tables, reconciliation, coverage statement and portal JSON. */
@Suppress("UNCHECKED_CAST")
class Gstr9Test {

    private val gstin = "27AAAAA0000A1Z5"

    private fun gt(
        voucherId: String, supplyType: SupplyType, taxable: Long = 1000_00L, rate: Double = 18.0, partyGstin: String = "",
        nature: GstSupplyNature = GstSupplyNature.NORMAL, type: VoucherType = VoucherType.SALES,
        direction: GstDirection = GstDirection.OUTPUT, charge: GstChargeType = GstChargeType.FORWARD_CHARGE, hsn: String = "8471"
    ): GstTransaction {
        val t = Money.fromPaise(taxable)
        val (c, s, i) = when (supplyType) {
            SupplyType.INTRA_STATE -> Triple(t.percentage(rate / 2), t.percentage(rate / 2), Money.ZERO)
            SupplyType.INTER_STATE -> Triple(Money.ZERO, Money.ZERO, t.percentage(rate))
            else -> Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }
        return GstTransaction(
            gstTransactionId = "T${System.nanoTime()}", companyId = "C", financialYearId = "FY", voucherId = voucherId, voucherType = type,
            partyLedgerId = "L", partyGstin = partyGstin, placeOfSupply = "27", supplyType = supplyType, itemId = null, hsnSacCode = hsn,
            quantity = Quantity.fromLong(1), taxableAmount = t, gstRatePercent = rate, cgst = c, sgst = s, igst = i, cess = Money.ZERO,
            direction = direction, lineOrder = 1, chargeType = charge, supplyNature = nature
        )
    }

    private fun negated(g: GstTransaction, type: VoucherType) =
        g.copy(voucherType = type, taxableAmount = -g.taxableAmount, cgst = -g.cgst, sgst = -g.sgst, igst = -g.igst, cess = -g.cess)

    private fun hsnRows(rows: List<GstTransaction>) = rows.filter { it.direction == GstDirection.OUTPUT }.groupBy { it.hsnSacCode to it.gstRatePercent }.map { (k, r) ->
        Gstr1HsnRow(
            k.first, k.second, null, r.fold(Money.ZERO) { a, t -> a + t.taxableAmount }, r.fold(Money.ZERO) { a, t -> a + t.cgst },
            r.fold(Money.ZERO) { a, t -> a + t.sgst }, r.fold(Money.ZERO) { a, t -> a + t.igst }, r.fold(Money.ZERO) { a, t -> a + t.cess }, "NOS"
        )
    }

    private fun build(vararg rows: GstTransaction, hsn: List<Gstr1HsnRow>? = null): Gstr9ReturnData {
        val list = rows.toList()
        return Gstr9Builder.build(gstin, "2026-27", 2027, list, Gstr3bBuilder.build(gstin, "2026-27-ANNUAL", list), hsn ?: hsnRows(list))
    }

    private fun p(v: Long) = Money.fromPaise(v)

    // ---------------------------------------------------------------- Table 4

    @Test
    fun table4_splitsB2cB2bExportsWithPaymentAndReverseCharge() {
        val d = build(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),
            gt("S2", SupplyType.INTRA_STATE, 2000_00L, partyGstin = "27AAPFU0939F1ZV"),
            gt("S3", SupplyType.EXPORT, 3000_00L, rate = 18.0, nature = GstSupplyNature.EXPORT).copy(igst = p(540_00L)),
            gt("P1", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        assertEquals(1000_00L, d.table4.b2c.taxable.paise)
        assertEquals(90_00L, d.table4.b2c.cgst.paise)
        assertEquals(2000_00L, d.table4.b2b.taxable.paise)
        assertEquals("4C - exports on which IGST was paid", 3000_00L, d.table4.exportsWithPayment.taxable.paise)
        assertEquals(540_00L, d.table4.exportsWithPayment.igst.paise)
        assertEquals("4G - inward reverse charge", 500_00L, d.table4.inwardReverseCharge.taxable.paise)
        assertEquals(45_00L, d.table4.inwardReverseCharge.sgst.paise)
    }

    @Test
    fun table4_creditNotesAreAPositive4I_andNetOutwardSubtractsThem() {
        val sale = gt("S1", SupplyType.INTRA_STATE, 1000_00L, partyGstin = "27AAPFU0939F1ZV")
        val d = build(sale, negated(sale.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE).copy(taxableAmount = p(-400_00L), cgst = p(-36_00L), sgst = p(-36_00L)))
        assertEquals("4I carries the note as a positive amount", 400_00L, d.table4.creditNotes.taxable.paise)
        assertEquals(36_00L, d.table4.creditNotes.cgst.paise)
        assertEquals(600_00L, d.table4.netOutward.taxable.paise)
        assertEquals(54_00L, d.table4.netOutward.cgst.paise)
    }

    @Test
    fun table4_aDebitNoteIsAPositive4J() {
        val d = build(gt("D1", SupplyType.INTRA_STATE, 300_00L, type = VoucherType.DEBIT_NOTE))
        assertEquals(300_00L, d.table4.debitNotes.taxable.paise)
        assertEquals(300_00L, d.table4.netOutward.taxable.paise)
    }

    // ---------------------------------------------------------------- Table 5

    @Test
    fun table5_exportsWithoutPayment_exempted_nilRated_andTheirCreditNotes() {
        val exportNoPay = gt("S1", SupplyType.EXPORT, 2000_00L, rate = 0.0, nature = GstSupplyNature.EXPORT)
        val d = build(
            exportNoPay,
            gt("S2", SupplyType.EXEMPT, 300_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT),
            gt("S3", SupplyType.EXEMPT, 400_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED),
            negated(exportNoPay.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE).copy(taxableAmount = p(-500_00L))
        )
        assertEquals(2000_00L, d.table5.zeroRatedWithoutPayment.paise)
        assertEquals(300_00L, d.table5.exempted.paise)
        assertEquals(400_00L, d.table5.nilRated.paise)
        assertEquals("5H", 500_00L, d.table5.creditNotes.paise)
        assertEquals(2200_00L, d.table5.net.paise)
    }

    @Test
    fun aMixedInvoiceIsSplitLineByLine_acrossTables4And5() {
        val d = build(
            gt("V1", SupplyType.INTRA_STATE, 1000_00L),
            gt("V1", SupplyType.EXEMPT, 500_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT)
        )
        assertEquals(1000_00L, d.table4.b2c.taxable.paise)
        assertEquals(500_00L, d.table5.exempted.paise)
    }

    // ---------------------------------------------------------------- Table 9, 6A

    @Test
    fun table9_taxPayableIsOutwardTaxPlusReverseChargeTax_perHead() {
        val d = build(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L),                                                                                          // 90 + 90
            gt("S2", SupplyType.INTER_STATE, 500_00L),                                                                                           // IGST 90
            gt("P1", SupplyType.INTRA_STATE, 300_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE) // RCM 27 + 27
        )
        assertEquals(90_00L, d.taxPayable.igst.paise)
        assertEquals(90_00L + 27_00L, d.taxPayable.cgst.paise)
        assertEquals(90_00L + 27_00L, d.taxPayable.sgst.paise)
    }

    @Test
    fun table6a_isTheYearsGstr3bItcAvailedTotal_forReconciliationOnly() {
        val d = build(
            gt("P1", SupplyType.INTRA_STATE, 1000_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT),
            gt("P2", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        assertEquals(90_00L + 45_00L, d.itcAvailedPerGstr3b.cgst.paise)
        assertFalse("6A is filled by the portal and is not written into the upload", Gstr9PortalJsonSerializer.serialize(d).containsKey("table6"))
    }

    // ---------------------------------------------------------------- reconciliation

    @Test
    fun tablesAgreeWithGstr3b_whenBuiltFromTheSameRows() {
        val rows = listOf(
            gt("S1", SupplyType.INTRA_STATE, 1000_00L), gt("S2", SupplyType.EXPORT, 2000_00L, rate = 0.0, nature = GstSupplyNature.EXPORT),
            gt("S3", SupplyType.EXEMPT, 300_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT),
            gt("P1", SupplyType.INTRA_STATE, 500_00L, type = VoucherType.PURCHASE, direction = GstDirection.INPUT, charge = GstChargeType.REVERSE_CHARGE)
        )
        val g3b = Gstr3bBuilder.build(gstin, "k", rows)
        val d = Gstr9Builder.build(gstin, "2026-27", 2027, rows, g3b, hsnRows(rows))
        assertTrue(Gstr9Reconciliation.tablesAgreeWithGstr3b(d, g3b).joinToString { it.message }.isEmpty())
    }

    @Test
    fun tablesAgreeWithGstr3b_flagsATamperedTable() {
        val rows = listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L), gt("S2", SupplyType.EXEMPT, 300_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT))
        val g3b = Gstr3bBuilder.build(gstin, "k", rows)
        val d = Gstr9Builder.build(gstin, "2026-27", 2027, rows, g3b, hsnRows(rows))
        val taxTampered = d.copy(table4 = d.table4.copy(b2c = d.table4.b2c.copy(cgst = p(100_00L))))
        assertTrue("a wrong tax figure in Table 4", "GSTR9_TABLE4_GSTR3B_MISMATCH" in Gstr9Reconciliation.tablesAgreeWithGstr3b(taxTampered, g3b).map { it.code })
        val valueTampered = d.copy(table5 = d.table5.copy(exempted = p(1L)))
        assertTrue("a wrong taxable value across Tables 4+5", "GSTR9_TABLE5_GSTR3B_MISMATCH" in Gstr9Reconciliation.tablesAgreeWithGstr3b(valueTampered, g3b).map { it.code })
        val tampered = d.copy(table4 = d.table4.copy(b2c = d.table4.b2c.copy(cgst = p(100_00L))), table5 = d.table5.copy(exempted = p(1L)))
        assertTrue(Gstr9Reconciliation.tablesAgreeWithGstr3b(tampered, g3b).all { it.severity == Gstr1ValidationSeverity.ERROR })
    }

    @Test
    fun hsnTable17_usesThePortalsRs10ToleranceOnTax() {
        val rows = listOf(gt("S1", SupplyType.INTRA_STATE, 1000_00L))   // tax 180.00
        val g3b = Gstr3bBuilder.build(gstin, "k", rows)
        fun withHsnTax(delta: Long): Gstr9ReturnData {
            val base = hsnRows(rows).single()
            return Gstr9Builder.build(gstin, "2026-27", 2027, rows, g3b, listOf(base.copy(cgst = base.cgst + p(delta))))
        }
        assertTrue("exactly Rs 10 apart is within tolerance", Gstr9Reconciliation.hsnAgreesWithTables(withHsnTax(10_00L)).isEmpty())
        assertTrue("one paisa beyond Rs 10 is not", Gstr9Reconciliation.hsnAgreesWithTables(withHsnTax(10_01L)).single().code == "GSTR9_HSN_MISMATCH")
        assertTrue(Gstr9Reconciliation.hsnAgreesWithTables(withHsnTax(0L)).isEmpty())
    }

    @Test
    fun hsnTable17_isNotRequiredWhenThereIsNothingToReport() {
        assertTrue(Gstr9Reconciliation.hsnAgreesWithTables(build()).isEmpty())
    }

    // ---------------------------------------------------------------- coverage and JSON

    @Test
    fun coverage_saysExactlyWhichTablesTheAppCannotProduce_neverImplyingZero() {
        val byTable = build().coverage.associateBy { it.table }
        listOf("6", "7", "8", "10-13", "14", "15", "16", "18", "19").forEach {
            assertEquals("Table $it must be declared unsupported", Gstr9CoverageStatus.NOT_SUPPORTED, byTable.getValue(it).status)
            assertTrue("Table $it needs a reason", byTable.getValue(it).note.isNotBlank())
        }
        assertEquals(Gstr9CoverageStatus.PARTIAL, byTable.getValue("4").status)
        assertEquals(Gstr9CoverageStatus.PARTIAL, byTable.getValue("5").status)
        assertEquals(Gstr9CoverageStatus.PARTIAL, byTable.getValue("9").status)
        assertEquals(Gstr9CoverageStatus.SUPPORTED, byTable.getValue("17").status)
        assertTrue("SEZ, deemed exports and advances are named as not recorded", byTable.getValue("4").note.let { it.contains("SEZ") && it.contains("deemed") && it.contains("advances") })
    }

    @Test
    fun portalJson_hasFpAndOnlyTheSupportedTables_andNoGenericEnvelope() {
        val j = Gstr9PortalJsonSerializer.serialize(build(gt("S1", SupplyType.INTRA_STATE, 1000_00L)))
        assertEquals(gstin, j["gstin"])
        assertEquals("032027", j["fp"])
        assertEquals(setOf("gstin", "fp", "table4", "table5", "table9", "table17"), j.keys)
        listOf("table6", "table7", "table8", "table10", "table14", "table15", "table16", "table18").forEach { assertFalse("$it must be left out, not zeroed", j.containsKey(it)) }
        listOf("schemaVersion", "exportType", "generatedAt", "data").forEach { assertFalse(j.containsKey(it)) }
        val b2c = (j["table4"] as Map<String, Any?>)["b2c"] as Map<String, Any?>
        assertEquals(1000.0, b2c["txval"]); assertEquals(90.0, b2c["camt"]); assertEquals(90.0, b2c["samt"])
    }

    @Test
    fun portalJson_leavesOutEveryUnrecordedCategory_insteadOfWritingZero() {
        val j = Gstr9PortalJsonSerializer.serialize(build(gt("S1", SupplyType.INTRA_STATE, 1000_00L)))
        val t4 = j["table4"] as Map<String, Any?>
        val t5 = j["table5"] as Map<String, Any?>
        assertEquals(setOf("b2c", "b2b", "exp", "rchrg", "cr_nt", "dr_nt"), t4.keys)
        assertEquals(setOf("zero_rtd", "exmt", "nil", "cr_nt"), t5.keys)
        listOf("sez", "deemed", "at", "amd_pos", "amd_neg").forEach { assertFalse("table4.$it is not recorded", t4.containsKey(it)) }
        listOf("sez", "rchrg", "non_gst", "dr_nt", "amd_pos", "amd_neg").forEach { assertFalse("table5.$it is not recorded", t5.containsKey(it)) }
        val t9 = j["table9"] as Map<String, Any?>
        assertEquals(setOf("iamt", "camt", "samt", "csamt"), t9.keys)
        assertEquals("only the payable column is supplied; the paid columns belong to the portal", setOf("txpyble"), (t9["camt"] as Map<String, Any?>).keys)
    }

    @Test
    fun portalJson_table17_hasTheHsnRowsWithUqc_andNegativeNetsAreWrittenAsZero() {
        val sale = gt("S1", SupplyType.INTRA_STATE, 1000_00L, hsn = "8471")
        val bigReturn = negated(sale.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE).copy(taxableAmount = p(-2000_00L), cgst = p(-180_00L), sgst = p(-180_00L))
        val j = Gstr9PortalJsonSerializer.serialize(build(sale, bigReturn))
        val item = ((j["table17"] as Map<String, Any?>)["items"] as List<Map<String, Any?>>).single()
        assertEquals("8471", item["hsn_sc"]); assertEquals("NOS", item["uqc"])
        listOf("txval", "iamt", "camt", "samt", "csamt", "qty").forEach { assertTrue("$it must not be negative", (item[it] as Double) >= 0.0) }
        val t4 = j["table4"] as Map<String, Any?>
        listOf("b2c", "cr_nt").forEach { k -> (t4[k] as Map<String, Any?>).values.forEach { assertTrue((it as Double) >= 0.0) } }
    }

    @Test
    fun sections_carryEveryReportedTable_andTheCoverageStatement() {
        val s = build(gt("S1", SupplyType.INTRA_STATE, 1000_00L)).toSections()
        assertEquals(setOf("4", "5", "6A", "9", "17", "COVERAGE"), s.keys)
        assertEquals(13, (s.getValue("COVERAGE")["rows"] as List<*>).size)
    }
}
