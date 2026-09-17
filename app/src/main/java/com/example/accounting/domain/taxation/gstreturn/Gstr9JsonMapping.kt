package com.example.accounting.domain.taxation.gstreturn

/**
 * Phase 8 - two JSON shapes for [Gstr9ReturnData], mirroring [Gstr1JsonMapping]/[Gstr3bJsonMapping]'s
 * own split. [toTree] - readable, for [GstReturnSection.resultDataJson]/plain JSON export.
 * [Gstr9PortalJsonSerializer] - a real, GSTN-table-numbered JSON (table4/table5/table6/table9/
 * table17/table18, per the general GSTN offline-tool convention) for the GSTR_JSON export format.
 * Unlike GSTR-1/GSTR-3B's own cryptic-but-precisely-documented field abbreviations (`ctin`/`txval`/
 * `osup_det`, taken directly from GSTN's published offline-tool schema), GSTR-9's real offline JSON
 * schema is less commonly publicly documented at that level of precision - this serializer uses
 * clear statutory table-number keys instead of guessing at exact GSTN internal field names it
 * cannot verify, matching this project's own "never fabricate precision it doesn't have" discipline.
 */

private fun Gstr3bTaxSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "taxableValuePaise" to taxableValue.paise, "igstPaise" to igst.paise,
    "cgstPaise" to cgst.paise, "sgstPaise" to sgst.paise, "cessPaise" to cess.paise
)

/** Same fields as [toTree] but under a [prefix]ed key name - see [Gstr3bJsonMapping]'s own
 * `toDerivedTree` for why: [Gstr9OutwardSummary.subtotal]/[Gstr9OutwardSummary.netTaxPayable] are
 * DERIVED from b2c/b2b/inwardReverseCharge/creditNotesIssued already in this same tree, and
 * [sumDeep] (`GstReturnDashboardScreen.kt`) sums every bare `cgstPaise`-style key it finds
 * anywhere in a section's tree - reusing [toTree]'s key names here would double-count. */
private fun Gstr3bTaxSummary.toDerivedTree(prefix: String): Map<String, Any?> = linkedMapOf(
    "${prefix}TaxableValuePaise" to taxableValue.paise, "${prefix}IgstPaise" to igst.paise,
    "${prefix}CgstPaise" to cgst.paise, "${prefix}SgstPaise" to sgst.paise, "${prefix}CessPaise" to cess.paise
)

fun Gstr9OutwardSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "b2c" to b2c.toTree(), "b2b" to b2b.toTree(), "zeroRatedExportsPaise" to zeroRatedExports.paise,
    "inwardReverseCharge" to inwardReverseCharge.toTree(), "creditNotesIssued" to creditNotesIssued.toTree(),
    "subtotal" to subtotal.toDerivedTree("subtotal"), "netTaxPayable" to netTaxPayable.toDerivedTree("netTaxPayable")
)

fun Gstr9ExemptOutwardSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "zeroRatedWithoutTaxPaise" to zeroRatedWithoutTax.paise, "nilRatedPaise" to nilRated.paise,
    "exemptedPaise" to exempted.paise, "nonGstSupplyPaise" to nonGstSupply.paise, "totalPaise" to total.paise
)

fun Gstr9ItcSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "totalItcAvailed" to totalItcAvailed.toTree(), "itcOnInwardReverseCharge" to itcOnInwardReverseCharge.toTree()
)

fun Gstr9TaxPaidRow.toTree(): Map<String, Any?> = linkedMapOf(
    "head" to head, "taxPayablePaise" to taxPayable.paise, "paidThroughItcPaise" to paidThroughItc.paise, "paidInCashPaise" to paidInCash.paise
)

fun Gstr9HsnInwardRow.toTree(): Map<String, Any?> = linkedMapOf(
    "hsnSacCode" to hsnSacCode, "gstRatePercent" to gstRatePercent, "taxableValuePaise" to taxableValue.paise,
    "cgstPaise" to cgst.paise, "sgstPaise" to sgst.paise, "igstPaise" to igst.paise, "cessPaise" to cess.paise
)

fun Gstr9ValidationIssue.toTree(): Map<String, Any?> = linkedMapOf("severity" to severity.name, "code" to code, "message" to message)

fun Gstr9ReturnData.toTree(): Map<String, Any?> = linkedMapOf(
    "companyGstin" to companyGstin, "periodKey" to periodKey,
    "outward" to outward.toTree(), "exemptOutward" to exemptOutward.toTree(), "itc" to itc.toTree(),
    "taxPaid" to taxPaid.map { it.toTree() }, "hsnOutward" to hsnOutward.map { it.toTree() },
    "hsnInward" to hsnInward.map { it.toTree() }, "validationIssues" to validationIssues.map { it.toTree() },
    "hasBlockingErrors" to hasBlockingErrors
)

object Gstr9PortalJsonSerializer {
    private fun Long.toRupees(): Double = this / 100.0
    private fun Gstr3bTaxSummary.toAmt(): Map<String, Any?> = linkedMapOf(
        "txval" to taxableValue.paise.toRupees(), "iamt" to igst.paise.toRupees(),
        "camt" to cgst.paise.toRupees(), "samt" to sgst.paise.toRupees(), "csamt" to cess.paise.toRupees()
    )

    fun serialize(data: Gstr9ReturnData): Map<String, Any?> = linkedMapOf(
        "gstin" to data.companyGstin,
        "fy" to data.periodKey,
        "table4" to linkedMapOf(
            "b2c" to data.outward.b2c.toAmt(), "b2b" to data.outward.b2b.toAmt(),
            "zero_rated_exports_txval" to data.outward.zeroRatedExports.paise.toRupees(),
            "inward_reverse_charge" to data.outward.inwardReverseCharge.toAmt(),
            "credit_notes_issued" to data.outward.creditNotesIssued.toAmt(),
            "subtotal" to data.outward.subtotal.toAmt(), "net_tax_payable" to data.outward.netTaxPayable.toAmt()
        ),
        "table5" to linkedMapOf(
            "zero_rated_without_tax_txval" to data.exemptOutward.zeroRatedWithoutTax.paise.toRupees(),
            "nil_rated_txval" to data.exemptOutward.nilRated.paise.toRupees(),
            "exempted_txval" to data.exemptOutward.exempted.paise.toRupees(),
            "non_gst_supply_txval" to data.exemptOutward.nonGstSupply.paise.toRupees()
        ),
        "table6" to linkedMapOf(
            "total_itc_availed" to data.itc.totalItcAvailed.toAmt(),
            "itc_on_inward_reverse_charge" to data.itc.itcOnInwardReverseCharge.toAmt()
        ),
        "table9" to data.taxPaid.map { row ->
            linkedMapOf(
                "head" to row.head, "tax_payable" to row.taxPayable.paise.toRupees(),
                "paid_through_itc" to row.paidThroughItc.paise.toRupees(), "paid_in_cash" to row.paidInCash.paise.toRupees()
            )
        },
        "table17_hsn_outward" to data.hsnOutward.mapIndexed { index, row ->
            linkedMapOf(
                "num" to index + 1, "hsn_sc" to row.hsnSacCode, "qty" to row.totalQuantity, "rt" to row.gstRatePercent,
                "txval" to row.taxableValue.paise.toRupees(), "camt" to row.cgst.paise.toRupees(),
                "samt" to row.sgst.paise.toRupees(), "iamt" to row.igst.paise.toRupees(), "csamt" to row.cess.paise.toRupees()
            )
        },
        "table18_hsn_inward" to data.hsnInward.mapIndexed { index, row ->
            linkedMapOf(
                "num" to index + 1, "hsn_sc" to row.hsnSacCode, "rt" to row.gstRatePercent,
                "txval" to row.taxableValue.paise.toRupees(), "camt" to row.cgst.paise.toRupees(),
                "samt" to row.sgst.paise.toRupees(), "iamt" to row.igst.paise.toRupees(), "csamt" to row.cess.paise.toRupees()
            )
        }
    )
}
