package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.Gstr1PortalJsonSerializer
import com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnBuilder
import com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData
import com.example.accounting.domain.taxation.gstreturn.GstnUqc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 8, Step 5 - GSTR-1 builder classification and GSTN JSON conventions, one focused test per
 * rule (the Phase 8 audit tests cover the end-to-end fixture; these pin the details).
 */
@Suppress("UNCHECKED_CAST")
class Gstr1GstnJsonTest {

    private val companyGstin = "27AAAAA0000A1Z5"

    private fun gt(
        voucherId: String?, gstin: String, supplyType: SupplyType, taxable: Long = 1000_00L, rate: Double = 18.0,
        nature: GstSupplyNature = GstSupplyNature.NORMAL, type: VoucherType = VoucherType.SALES, pos: String = "27",
        hsn: String = "8471", unit: String? = "Nos", group: String = ""
    ): GstTransaction {
        val t = Money.fromPaise(taxable)
        val (c, s, i) = when (supplyType) {
            SupplyType.INTRA_STATE -> Triple(t.percentage(rate / 2), t.percentage(rate / 2), Money.ZERO)
            SupplyType.INTER_STATE -> Triple(Money.ZERO, Money.ZERO, t.percentage(rate))
            else -> Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }
        return GstTransaction(
            gstTransactionId = "T${System.nanoTime()}", companyId = "C", financialYearId = "FY", voucherId = voucherId, voucherType = type,
            partyLedgerId = "L", partyGstin = gstin, placeOfSupply = pos, supplyType = supplyType, itemId = null, hsnSacCode = hsn,
            quantity = unit?.let { Quantity.fromLong(1).copy(unit = it) }, taxableAmount = t, gstRatePercent = rate, cgst = c, sgst = s, igst = i,
            cess = Money.ZERO, direction = GstDirection.OUTPUT, lineOrder = 1, supplyNature = nature, transactionGroupId = group
        )
    }

    private fun negated(g: GstTransaction, type: VoucherType) =
        g.copy(voucherType = type, taxableAmount = -g.taxableAmount, cgst = -g.cgst, sgst = -g.sgst, igst = -g.igst, cess = -g.cess)

    private fun v(id: String, type: VoucherType, date: String = "2026-04-10", ref: String? = null, total: Long = 0L) = Voucher(
        voucherId = id, companyId = "C", financialYearId = "FY", voucherNumber = id, voucherType = type,
        date = LocalDate.parse(date), referenceVoucherId = ref, totalAmount = Money.fromPaise(total)
    )

    private fun build(txns: List<GstTransaction>, vouchers: List<Voucher>, periodKey: String = "202604"): Gstr1ReturnData = runBlocking {
        Gstr1ReturnBuilder.build(companyGstin, periodKey, txns, vouchers.associateBy { it.voucherId }, vouchers)
    }

    private fun json(data: Gstr1ReturnData) = Gstr1PortalJsonSerializer.serialize(data)
    private fun list(m: Map<String, Any?>, k: String) = (m[k] as? List<Map<String, Any?>>) ?: emptyList()

    // ---- GST-only rows

    @Test
    fun gstOnlyUnregisteredSale_reachesB2csAndHsn_evenWithNoGroupId() {
        val data = build(listOf(gt(null, "", SupplyType.INTRA_STATE)), emptyList())
        assertEquals(1, data.b2cs.size)
        assertEquals(1, data.hsn.size)
    }

    @Test
    fun gstOnlyRowsOfOneGroup_areOneDocument_notCountedTwice() {
        val a = gt(null, "", SupplyType.INTRA_STATE, taxable = 100_00L, group = "G1")
        val b = gt(null, "", SupplyType.INTRA_STATE, taxable = 200_00L, group = "G1")
        val data = build(listOf(a, b), emptyList())
        assertEquals(1, data.b2cs.size)
        assertEquals(300_00L, data.b2cs.first().taxableValue.paise)
    }

    @Test
    fun gstOnlyNilRatedSale_reachesNilTable() {
        val data = build(listOf(gt(null, "", SupplyType.EXEMPT, taxable = 400_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED)), emptyList())
        assertEquals(400_00L, data.nilRated.sumOf { it.taxableValue.paise })
    }

    @Test
    fun gstOnlyRegisteredSale_hasNoInvoiceNumber_soIsNotPlacedInB2b() {
        val data = build(listOf(gt(null, "27AAPFU0939F1ZV", SupplyType.INTRA_STATE)), emptyList())
        assertTrue(data.b2b.isEmpty())
        assertEquals("its HSN line is still reported", 1, data.hsn.size)
    }

    // ---- mixed taxable / exempt

    @Test
    fun mixedInvoice_eachLineClassifiedOnItsOwn_inEitherOrder() {
        val taxable = gt("V1", "", SupplyType.INTRA_STATE, taxable = 1000_00L)
        val exempt = gt("V1", "", SupplyType.EXEMPT, taxable = 500_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT)
        listOf(listOf(taxable, exempt), listOf(exempt, taxable)).forEach { order ->
            val data = build(order, listOf(v("V1", VoucherType.SALES)))
            assertEquals(500_00L, data.nilRated.sumOf { it.taxableValue.paise })
            assertEquals(1000_00L, data.b2cs.sumOf { it.taxableValue.paise })
        }
    }

    @Test
    fun nilRows_carryRegistrationAndGeography() {
        val interReg = gt("V1", "27AAPFU0939F1ZV", SupplyType.EXEMPT, taxable = 100_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT, pos = "09")
        val intraUnreg = gt("V2", "", SupplyType.EXEMPT, taxable = 200_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED, pos = "27")
        val data = build(listOf(interReg, intraUnreg), listOf(v("V1", VoucherType.SALES), v("V2", VoucherType.SALES)))
        val rows = list(json(data)["nil"] as Map<String, Any?>, "inv").associateBy { it["sply_ty"] }
        assertEquals(100.0, rows.getValue("INTRB2B")["expt_amt"])
        assertEquals(200.0, rows.getValue("INTRAB2C")["nil_amt"])
    }

    @Test
    fun nilRowsWithTheSameSplyTy_areMergedIntoOneRow() {
        val exempt = gt("V1", "", SupplyType.EXEMPT, taxable = 100_00L, rate = 0.0, nature = GstSupplyNature.EXEMPT)
        val nilRated = gt("V2", "", SupplyType.EXEMPT, taxable = 200_00L, rate = 0.0, nature = GstSupplyNature.NIL_RATED)
        val data = build(listOf(exempt, nilRated), listOf(v("V1", VoucherType.SALES), v("V2", VoucherType.SALES)))
        val rows = list(json(data)["nil"] as Map<String, Any?>, "inv")
        assertEquals(1, rows.size)
        assertEquals(100.0, rows.single()["expt_amt"])
        assertEquals(200.0, rows.single()["nil_amt"])
    }

    // ---- credit notes

    @Test
    fun exportCreditNote_goesToCdnurAsExportNote_typedByTaxPaid() {
        val withoutPayment = gt("V1", "", SupplyType.EXPORT, rate = 0.0, nature = GstSupplyNature.EXPORT)
        val data = build(
            listOf(negated(withoutPayment.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE)),
            listOf(v("V1", VoucherType.SALES), v("CN1", VoucherType.CREDIT_NOTE, ref = "V1"))
        )
        assertEquals(1, data.cdnur.size)
        assertTrue(data.cdnur.single().isExport)
        assertEquals("EXPWOP", list(json(data), "cdnur").single()["typ"])
    }

    @Test
    fun unregisteredInterStateNote_belowThreshold_netsIntoB2cs_aboveThreshold_isCdnurB2cl() {
        val small = negated(gt("V1", "", SupplyType.INTER_STATE, taxable = 1000_00L, pos = "09").copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE)
        val dataSmall = build(listOf(small), listOf(v("V1", VoucherType.SALES, total = 1180_00L), v("CN1", VoucherType.CREDIT_NOTE, ref = "V1")))
        assertTrue(dataSmall.cdnur.isEmpty())
        assertEquals(1, dataSmall.b2cs.size)

        val big = negated(gt("V2", "", SupplyType.INTER_STATE, taxable = 1000_00L, pos = "09").copy(voucherId = "CN2"), VoucherType.CREDIT_NOTE)
        val dataBig = build(listOf(big), listOf(v("V2", VoucherType.SALES, total = 3_00_000_00L), v("CN2", VoucherType.CREDIT_NOTE, ref = "V2")))
        assertEquals("the ORIGINAL invoice was above the B2CL threshold", 1, dataBig.cdnur.size)
        assertEquals("B2CL", list(json(dataBig), "cdnur").single()["typ"])
    }

    @Test
    fun creditNoteValuesAreSerializedPositive_inCdnrAndCdnur_whileNtTyGivesTheDirection() {
        val reg = gt("V1", "27AAPFU0939F1ZV", SupplyType.INTRA_STATE)
        val note = negated(reg.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE)
        val data = build(listOf(note), listOf(v("V1", VoucherType.SALES), v("CN1", VoucherType.CREDIT_NOTE, ref = "V1")))
        val nt = list(list(json(data), "cdnr").single(), "nt").single()
        assertEquals("C", nt["ntty"])
        assertTrue((nt["val"] as Double) > 0.0)
        val det = ((nt["itms"] as List<Map<String, Any?>>).single()["itm_det"]) as Map<String, Any?>
        listOf("txval", "camt", "samt", "iamt", "csamt").forEach { assertTrue("$it must not be negative", (det[it] as Double) >= 0.0) }
    }

    // ---- exports

    @Test
    fun exportInvoices_carryItms_andWpayWopayFollowsTaxPaid() {
        val lut = gt("V1", "", SupplyType.EXPORT, rate = 0.0, nature = GstSupplyNature.EXPORT)
        val data = build(listOf(lut), listOf(v("V1", VoucherType.SALES)))
        val exp = list(json(data), "exp").single()
        assertEquals("WOPAY", exp["exp_typ"])
        val inv = list(exp, "inv").single()
        val itm = (inv["itms"] as List<Map<String, Any?>>).single()
        assertEquals(1000.0, itm["txval"])
        assertTrue(itm.containsKey("rt") && itm.containsKey("iamt"))
    }

    // ---- dates, period, turnover, B2CS, HSN, Table 13

    @Test
    fun returnPeriod_isMmYyyy_andAQuarterIsItsLastMonth() {
        assertEquals("042026", Gstr1PortalJsonSerializer.toGstnReturnPeriod("202604"))
        assertEquals("032027", Gstr1PortalJsonSerializer.toGstnReturnPeriod("202703"))
        assertEquals("062026", Gstr1PortalJsonSerializer.toGstnReturnPeriod("2026-27-Q1"))
        assertEquals("092026", Gstr1PortalJsonSerializer.toGstnReturnPeriod("2026-27-Q2"))
        assertEquals("122026", Gstr1PortalJsonSerializer.toGstnReturnPeriod("2026-27-Q3"))
        assertEquals("032027", Gstr1PortalJsonSerializer.toGstnReturnPeriod("2026-27-Q4"))
    }

    @Test
    fun allDates_areDdMmYyyy_inInvoicesAndNotes() {
        val reg = gt("V1", "27AAPFU0939F1ZV", SupplyType.INTRA_STATE)
        val note = negated(reg.copy(voucherId = "CN1"), VoucherType.CREDIT_NOTE)
        val data = build(listOf(reg, note), listOf(v("V1", VoucherType.SALES, "2026-04-05"), v("CN1", VoucherType.CREDIT_NOTE, "2026-04-15", ref = "V1")))
        val j = json(data)
        assertEquals("05-04-2026", list(list(j, "b2b").single(), "inv").single()["idt"])
        assertEquals("15-04-2026", list(list(j, "cdnr").single(), "nt").single()["nt_dt"])
    }

    @Test
    fun aggregateTurnoverFields_areAlwaysPresent_andCarryTheComputedValues() {
        val data = build(emptyList(), emptyList()).copy(
            aggregateTurnoverPrevFy = Money.fromPaise(12_345_67L), cumulativeTurnoverCurrentFy = Money.fromPaise(7_890_12L)
        )
        val j = json(data)
        assertEquals(12345.67, j["gt"])
        assertEquals(7890.12, j["cur_gt"])
    }

    @Test
    fun b2csSplyTy_isIntraWhenPosIsTheCompanyState_elseInter() {
        val intra = gt("V1", "", SupplyType.INTRA_STATE, pos = "27")
        val inter = gt("V2", "", SupplyType.INTER_STATE, pos = "09", taxable = 500_00L)
        val data = build(listOf(intra, inter), listOf(v("V1", VoucherType.SALES), v("V2", VoucherType.SALES)))
        val rows = list(json(data), "b2cs").associateBy { it["pos"] }
        assertEquals("INTRA", rows.getValue("27")["sply_ty"])
        assertEquals("INTER", rows.getValue("09")["sply_ty"])
    }

    @Test
    fun hsnUqc_comesFromTheLineUnit_NaForServices_OthForUnknown() {
        val goods = gt("V1", "", SupplyType.INTRA_STATE, hsn = "8471", unit = "Kg")
        val service = gt("V2", "", SupplyType.INTRA_STATE, hsn = "9983", unit = null)
        val odd = gt("V3", "", SupplyType.INTRA_STATE, hsn = "1111", unit = "furlongs")
        val data = build(listOf(goods, service, odd), listOf(v("V1", VoucherType.SALES), v("V2", VoucherType.SALES), v("V3", VoucherType.SALES)))
        val rows = list(json(data)["hsn"] as Map<String, Any?>, "data").associateBy { it["hsn_sc"] }
        assertEquals("KGS", rows.getValue("8471")["uqc"])
        assertEquals("NA", rows.getValue("9983")["uqc"])
        assertEquals("OTH", rows.getValue("1111")["uqc"])
    }

    @Test
    fun gstnUqc_onlyEverReturnsMembersOfTheOfficialList() {
        listOf("Nos", "pcs", "KGS", "Litre", "box", "dozen", "unknown unit", "", null, "  Mtr ").forEach {
            val code = GstnUqc.fromUnit(it)
            assertTrue("$it -> $code", code == "NA" || code in GstnUqc.CODES)
        }
        assertEquals("NOS", GstnUqc.fromUnit("Nos"))
        assertEquals("MTR", GstnUqc.fromUnit("  Mtr "))
        assertEquals("NA", GstnUqc.fromUnit(null))
    }

    @Test
    fun docIssue_docNumFollowsGstnNatureCodes_notListPosition() {
        val vouchers = listOf(
            v("INV1", VoucherType.SALES), v("DN1", VoucherType.DEBIT_NOTE), v("CN1", VoucherType.CREDIT_NOTE)
        )
        val rows = list(json(build(emptyList(), vouchers))["doc_issue"] as Map<String, Any?>, "doc_det")
        val codeByFrom = rows.associate { (list(it, "docs").single()["from"] as String) to (it["doc_num"] as Number).toInt() }
        assertEquals(1, codeByFrom["INV1"])
        assertEquals(4, codeByFrom["DN1"])
        assertEquals(5, codeByFrom["CN1"])
    }

    @Test
    fun portalJson_hasGstinAndFpAtTopLevel_andNoGenericEnvelopeKeys() {
        val j = json(build(listOf(gt("V1", "", SupplyType.INTRA_STATE)), listOf(v("V1", VoucherType.SALES))))
        assertEquals(companyGstin, j["gstin"])
        assertTrue(j.containsKey("fp") && j.containsKey("b2cs"))
        listOf("schemaVersion", "exportType", "generatedAt", "data").forEach { assertFalse("$it belongs to the generic envelope", j.containsKey(it)) }
    }
}
