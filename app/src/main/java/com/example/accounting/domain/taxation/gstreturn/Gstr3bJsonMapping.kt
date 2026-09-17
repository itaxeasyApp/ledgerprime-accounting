package com.example.accounting.domain.taxation.gstreturn

/**
 * Phase 8 - two distinct JSON shapes for the same [Gstr3bReturnData], mirroring
 * [Gstr1JsonMapping]'s own "readable internal tree vs. statutory portal tree" split exactly.
 * [toTree] - readable field names, for [GstReturnSection.resultDataJson] and the plain JSON export
 * format. [Gstr3bPortalJsonSerializer] - the real GST Network GSTR-3B JSON field names (`sup_details`,
 * `osup_det`/`osup_zero`/`osup_nil_exmp`/`isup_rev`/`osup_nongst`, `inter_sup`, `itc_elg`), used for
 * the GSTR_JSON offline-upload export format. Both read the exact same already-computed
 * [Gstr3bReturnData] - never recalculates.
 */

private fun Gstr3bTaxSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "taxableValuePaise" to taxableValue.paise, "igstPaise" to igst.paise,
    "cgstPaise" to cgst.paise, "sgstPaise" to sgst.paise, "cessPaise" to cess.paise, "totalTaxPaise" to totalTax.paise
)

/** Same fields as [toTree] but under a [prefix]ed key name - for a DERIVED total (e.g.
 * [Gstr3bItcAvailable.total], [Gstr3bItcSummary.netItc]) that sits alongside the raw rows it was
 * computed from inside the same JSON tree. [sumDeep] (`GstReturnDashboardScreen.kt`) recursively
 * sums every occurrence of a bare key like `cgstPaise` anywhere in a section's tree - reusing
 * [toTree]'s plain key names here would make it add this derived total on top of the raw rows it
 * already summed, double- or triple-counting the same rupees (confirmed live: a single ₹5,000/18%
 * purchase showed as ₹15,000/₹2,700 "Eligible ITC" before this fix). Prefixing the key names is
 * the fix, not touching [sumDeep] itself - GSTR-1's sections still rely on its documented
 * leaf-only-key invariant exactly as before. */
private fun Gstr3bTaxSummary.toDerivedTree(prefix: String): Map<String, Any?> = linkedMapOf(
    "${prefix}TaxableValuePaise" to taxableValue.paise, "${prefix}IgstPaise" to igst.paise,
    "${prefix}CgstPaise" to cgst.paise, "${prefix}SgstPaise" to sgst.paise,
    "${prefix}CessPaise" to cess.paise, "${prefix}TotalTaxPaise" to totalTax.paise
)

fun Gstr3bOutwardSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "taxableOutward" to taxableOutward.toTree(), "zeroRatedOutward" to zeroRatedOutward.toTree(),
    "nilExemptOutward" to nilExemptOutward.toTree(), "reverseChargeInward" to reverseChargeInward.toTree(),
    "nonGstOutwardPaise" to nonGstOutward.paise
)

fun Gstr3bInterStateUnregisteredRow.toTree(): Map<String, Any?> = linkedMapOf(
    "posStateCode" to posStateCode, "taxableValuePaise" to taxableValue.paise, "igstPaise" to igst.paise
)

fun Gstr3bItcAvailable.toTree(): Map<String, Any?> = linkedMapOf(
    "inwardReverseCharge" to inwardReverseCharge.toTree(), "allOtherItc" to allOtherItc.toTree(), "total" to total.toDerivedTree("total")
)

fun Gstr3bItcSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "available" to available.toTree(), "reversedItc" to reversedItc.toTree(),
    "ineligibleItc" to ineligibleItc.toTree(), "netItc" to netItc.toDerivedTree("net")
)

fun Gstr3bExemptInwardSummary.toTree(): Map<String, Any?> = linkedMapOf(
    "fromCompositionPaise" to fromComposition.paise, "fromOthersPaise" to fromOthers.paise
)

fun Gstr3bValidationIssue.toTree(): Map<String, Any?> = linkedMapOf("severity" to severity.name, "code" to code, "message" to message)

fun Gstr3bReturnData.toTree(): Map<String, Any?> = linkedMapOf(
    "companyGstin" to companyGstin, "periodKey" to periodKey,
    "outward" to outward.toTree(), "interStateUnregistered" to interStateUnregistered.map { it.toTree() },
    "itc" to itc.toTree(), "exemptInward" to exemptInward.toTree(),
    "estimatedCashLiability" to estimatedCashLiability.toTree(),
    "validationIssues" to validationIssues.map { it.toTree() }, "hasBlockingErrors" to hasBlockingErrors
)

/**
 * The real GST Network GSTR-3B JSON field convention (publicly documented GSTN offline-tool
 * schema). Money fields are rupees with 2 decimals (GSTN's own convention), converted only at this
 * final boundary - same precedent as [Gstr1PortalJsonSerializer]. Buckets this domain has no source
 * data for (IMPG/IMPS/ISD under itc_avl, itc_rev, itc_inelg, comp_details/uin_details under
 * inter_sup - see [Gstr3bModels]' top-level KDoc) are emitted as real, present, honestly-zeroed
 * entries - the portal schema itself always expects these keys to exist, so omitting them would be
 * a schema violation, not a corresponding accuracy gain; zero is the truthful value for "this
 * domain has no data here," never a guess at a nonzero figure.
 */
object Gstr3bPortalJsonSerializer {
    private fun Long.toRupees(): Double = this / 100.0

    private fun Gstr3bTaxSummary.toSupDet(): Map<String, Any?> = linkedMapOf(
        "txval" to taxableValue.paise.toRupees(), "iamt" to igst.paise.toRupees(),
        "camt" to cgst.paise.toRupees(), "samt" to sgst.paise.toRupees(), "csamt" to cess.paise.toRupees()
    )

    private fun itcRow(type: String, summary: Gstr3bTaxSummary): Map<String, Any?> = linkedMapOf(
        "ty" to type, "iamt" to summary.igst.paise.toRupees(), "camt" to summary.cgst.paise.toRupees(),
        "samt" to summary.sgst.paise.toRupees(), "csamt" to summary.cess.paise.toRupees()
    )

    fun serialize(data: Gstr3bReturnData): Map<String, Any?> = linkedMapOf(
        "gstin" to data.companyGstin,
        "ret_period" to data.periodKey,
        "sup_details" to linkedMapOf(
            "osup_det" to data.outward.taxableOutward.toSupDet(),
            "osup_zero" to linkedMapOf("txval" to data.outward.zeroRatedOutward.taxableValue.paise.toRupees(), "iamt" to data.outward.zeroRatedOutward.igst.paise.toRupees(), "csamt" to data.outward.zeroRatedOutward.cess.paise.toRupees()),
            "osup_nil_exmp" to linkedMapOf("txval" to data.outward.nilExemptOutward.taxableValue.paise.toRupees()),
            "isup_rev" to data.outward.reverseChargeInward.toSupDet(),
            "osup_nongst" to linkedMapOf("txval" to data.outward.nonGstOutward.paise.toRupees())
        ),
        "inter_sup" to linkedMapOf(
            "unreg_details" to data.interStateUnregistered.map { row ->
                linkedMapOf("pos" to row.posStateCode, "txval" to row.taxableValue.paise.toRupees(), "iamt" to row.igst.paise.toRupees())
            },
            "comp_details" to emptyList<Map<String, Any?>>(),
            "uin_details" to emptyList<Map<String, Any?>>()
        ),
        "itc_elg" to linkedMapOf(
            "itc_avl" to listOf(
                itcRow("IMPG", Gstr3bTaxSummary.ZERO), itcRow("IMPS", Gstr3bTaxSummary.ZERO),
                itcRow("ISRC", data.itc.available.inwardReverseCharge), itcRow("ISD", Gstr3bTaxSummary.ZERO),
                itcRow("OTH", data.itc.available.allOtherItc)
            ),
            "itc_rev" to listOf(itcRow("RUL", Gstr3bTaxSummary.ZERO), itcRow("OTH", data.itc.reversedItc)),
            "itc_net" to itcRow("NET", data.itc.netItc),
            "itc_inelg" to listOf(itcRow("RUL", Gstr3bTaxSummary.ZERO), itcRow("OTH", data.itc.ineligibleItc))
        ),
        "inward_sup" to linkedMapOf(
            "isup_details" to listOf(
                linkedMapOf("ty" to "GST", "inter" to 0.0, "intra" to data.exemptInward.fromOthers.paise.toRupees()),
                linkedMapOf("ty" to "NONGST", "inter" to 0.0, "intra" to 0.0)
            )
        )
    )
}
