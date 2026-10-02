package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Two shapes for the same [Gstr3bReturnData], like [Gstr1JsonMapping]'s split: [toSections] (readable
 * names, paise) is what a prepared return stores per statutory table, and [Gstr3bPortalJsonSerializer]
 * is the structure the GST portal's "Prepare Offline -> Upload" accepts for GSTR-3B.
 */

private fun Money.p(): Long = paise

internal fun Gstr3bAmounts.toTree(): Map<String, Any?> = linkedMapOf(
    "taxableValuePaise" to taxable.p(), "igstPaise" to igst.p(), "cgstPaise" to cgst.p(), "sgstPaise" to sgst.p(), "cessPaise" to cess.p()
)

internal fun Gstr3bHeads.toTree(): Map<String, Any?> = linkedMapOf(
    "igstPaise" to igst.p(), "cgstPaise" to cgst.p(), "sgstPaise" to sgst.p(), "cessPaise" to cess.p(), "totalPaise" to total.p()
)

private fun Gstr3bItcRow.toTree(): Map<String, Any?> = linkedMapOf("type" to type, "amounts" to amounts.toTree())

/** The statutory tables as separately-stored sections (keys `3_1`, `3_2`, `4`, `5`, `6_1`). */
fun Gstr3bReturnData.toSections(): Map<String, Map<String, Any?>> = linkedMapOf(
    "3_1" to linkedMapOf<String, Any?>(
        "a_outwardTaxable" to outwardTaxable.toTree(),
        "b_outwardZeroRated" to outwardZeroRated.toTree(),
        "c_outwardNilExempt" to outwardNilExempt.toTree(),
        "d_inwardReverseCharge" to inwardReverseCharge.toTree(),
        "e_outwardNonGst" to outwardNonGst.toTree()
    ),
    "3_2" to linkedMapOf<String, Any?>(
        "unregistered" to interStateUnregistered.map {
            linkedMapOf("posStateCode" to it.posStateCode, "taxableValuePaise" to it.taxable.p(), "igstPaise" to it.igst.p())
        }
    ),
    "4" to linkedMapOf<String, Any?>(
        "a_itcAvailable" to itcAvailable.map { it.toTree() },
        "b_itcReversed" to itcReversed.map { it.toTree() },
        "c_netItc" to itcNet.toTree(),
        "d_itcIneligible" to itcIneligible.map { it.toTree() }
    ),
    "5" to linkedMapOf<String, Any?>(
        "rows" to inwardExempt.map { linkedMapOf("type" to it.type, "interStatePaise" to it.interState.p(), "intraStatePaise" to it.intraState.p()) }
    ),
    "6_1" to linkedMapOf<String, Any?>(
        "outwardLiability" to payment.outwardLiability.toTree(),
        "reverseChargeLiability" to payment.reverseChargeLiability.toTree(),
        "itcAvailable" to payment.itcAvailable.toTree(),
        "paidByIgstCredit" to payment.paidByIgstCredit.toTree(),
        "paidByCgstCredit" to payment.paidByCgstCredit.toTree(),
        "paidBySgstCredit" to payment.paidBySgstCredit.toTree(),
        "paidByCessCredit" to payment.paidByCessCredit.toTree(),
        "cashPayable" to payment.cashPayable.toTree()
    )
)

/**
 * GSTR-3B upload JSON in the structure the GST portal's offline upload and the GSTN API use
 * (`gstin`, `ret_period`, `sup_details`, `inter_sup`, `itc_elg`, `inward_sup`). Money is rupees with
 * 2 decimals, converted only here. Per the portal's own rule that "negative values ... the system
 * will provide the value as zero", a table figure that nets below zero is written as 0.
 *
 * Deliberately NOT written (the portal produces or collects them itself): `intr_ltfee` - Table 5.1
 * interest and late fee are calculated by the system from the filing date - and `tx_pmt` - Table
 * 6.1 payment is made on the portal ("Offset Liability"), against its own credit/cash ledgers.
 * Table 3.1.1 (supplies notified under section 9(5), e-commerce operators) is not written either:
 * this domain records no e-commerce-operator supply.
 */
object Gstr3bPortalJsonSerializer {
    private fun Money.rs(): Double = maxOf(0L, paise) / 100.0

    private fun taxFields(a: Gstr3bAmounts, withTaxable: Boolean): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (withTaxable) m["txval"] = a.taxable.rs()
        m["iamt"] = a.igst.rs(); m["camt"] = a.cgst.rs(); m["samt"] = a.sgst.rs(); m["csamt"] = a.cess.rs()
        return m
    }

    // Table 4 categories the app never records. Writing them as 0.00 would claim "none"; they are omitted
    // (GSTR3B_ITC_GSTR2B_NOT_RECONCILED names them as NOT RECORDED). 4(B)(2)/4(D) rows are omitted likewise.
    private val UNRECORDED_ITC_AVAILABLE = setOf("IMPG", "IMPS", "ISD")
    private const val UNRECORDED_ITC_REVERSED = "RUL"

    private fun itcRow(row: Gstr3bItcRow): Map<String, Any?> =
        linkedMapOf<String, Any?>("ty" to row.type).apply { putAll(taxFields(row.amounts, withTaxable = false)) }

    fun serialize(data: Gstr3bReturnData): Map<String, Any?> = linkedMapOf(
        "gstin" to data.companyGstin,
        "ret_period" to Gstr1PortalJsonSerializer.toGstnReturnPeriod(data.periodKey),
        "sup_details" to linkedMapOf(
            "osup_det" to taxFields(data.outwardTaxable, withTaxable = true),
            "osup_zero" to linkedMapOf(
                "txval" to data.outwardZeroRated.taxable.rs(), "iamt" to data.outwardZeroRated.igst.rs(), "csamt" to data.outwardZeroRated.cess.rs()
            ),
            "osup_nil_exmp" to linkedMapOf("txval" to data.outwardNilExempt.taxable.rs()),
            "isup_rev" to taxFields(data.inwardReverseCharge, withTaxable = true),
            "osup_nongst" to linkedMapOf("txval" to data.outwardNonGst.taxable.rs())
        ),
        "inter_sup" to linkedMapOf(
            "unreg_details" to data.interStateUnregistered.map { linkedMapOf("pos" to it.posStateCode, "txval" to it.taxable.rs(), "iamt" to it.igst.rs()) },
            "comp_details" to emptyList<Any?>(),
            "uin_details" to emptyList<Any?>()
        ),
        "itc_elg" to linkedMapOf(
            "itc_avl" to data.itcAvailable.filter { it.type !in UNRECORDED_ITC_AVAILABLE }.map { itcRow(it) },
            "itc_rev" to data.itcReversed.filter { it.type != UNRECORDED_ITC_REVERSED }.map { itcRow(it) },
            "itc_net" to taxFields(data.itcNet, withTaxable = false)
        ),
        "inward_sup" to linkedMapOf(
            "isup_details" to data.inwardExempt.map { linkedMapOf("ty" to it.type, "inter" to it.interState.rs(), "intra" to it.intraState.rs()) }
        )
    )
}
