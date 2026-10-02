package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Two shapes for the same [Gstr9ReturnData]: [toSections] (readable names, paise) is what a
 * prepared GSTR-9 stores per table, and [Gstr9PortalJsonSerializer] is the structure for the GST
 * portal's GSTR-9 "Prepare Offline -> Upload".
 */

/** The prepared return's stored sections - keys `4`, `5`, `6A`, `9`, `17` and `COVERAGE`. */
fun Gstr9ReturnData.toSections(): Map<String, Map<String, Any?>> = linkedMapOf(
    "4" to linkedMapOf<String, Any?>(
        "a_b2c" to table4.b2c.toTree(),
        "b_b2b" to table4.b2b.toTree(),
        "c_exportsWithPayment" to table4.exportsWithPayment.toTree(),
        "g_inwardReverseCharge" to table4.inwardReverseCharge.toTree(),
        "i_creditNotes" to table4.creditNotes.toTree(),
        "j_debitNotes" to table4.debitNotes.toTree(),
        "net_outward" to table4.netOutward.toTree()
    ),
    "5" to linkedMapOf<String, Any?>(
        "a_zeroRatedWithoutPaymentPaise" to table5.zeroRatedWithoutPayment.paise,
        "d_exemptedPaise" to table5.exempted.paise,
        "e_nilRatedPaise" to table5.nilRated.paise,
        "h_creditNotesPaise" to table5.creditNotes.paise,
        "netPaise" to table5.net.paise
    ),
    "6A" to linkedMapOf<String, Any?>("itcAvailedPerGstr3b" to itcAvailedPerGstr3b.toTree()),
    "9" to linkedMapOf<String, Any?>("taxPayable" to taxPayable.toTree()),
    "17" to linkedMapOf<String, Any?>("count" to hsnOutward.size, "rows" to hsnOutward.map { it.toTree() }),
    "COVERAGE" to linkedMapOf<String, Any?>(
        "rows" to coverage.map { linkedMapOf("table" to it.table, "status" to it.status.name, "note" to it.note) }
    )
)

/**
 * GSTR-9 upload JSON (`fp`, `table4`, `table5`, `table9`, `table17`), money in rupees with 2
 * decimals. ONLY what the domain supports is written - the keys for SEZ, deemed exports, advances,
 * amendments, non-GST, reverse-charge outward, the paid columns of Table 9, interest/fee/penalty and
 * the whole of Tables 6, 7, 8, 10-16 and 18 are deliberately absent, never written as zero: a zero
 * would claim "nothing to report", which this app cannot know. (See [Gstr9ReturnData.coverage].)
 * Negative nets are written as 0, like the portal's own auto-population.
 *
 * Verification level: the field names follow the GSTR-9 save-request schema as published by a GST
 * API provider's documentation (Sandbox.co.in); GSTN's own JSON specification could not be read, so
 * upload the file on the portal's "Prepare Offline" before relying on it.
 */
object Gstr9PortalJsonSerializer {
    private fun Money.rs(): Double = maxOf(0L, paise) / 100.0

    private fun supplyFields(a: Gstr3bAmounts): Map<String, Any?> = linkedMapOf(
        "txval" to a.taxable.rs(), "iamt" to a.igst.rs(), "camt" to a.cgst.rs(), "samt" to a.sgst.rs(), "csamt" to a.cess.rs()
    )

    private fun taxableOnly(m: Money): Map<String, Any?> = linkedMapOf("txval" to m.rs())

    private fun payable(m: Money): Map<String, Any?> = linkedMapOf("txpyble" to m.rs())

    fun serialize(data: Gstr9ReturnData): Map<String, Any?> = linkedMapOf(
        "gstin" to data.companyGstin,
        "fp" to "03${data.fyEndYear}",
        "table4" to linkedMapOf(
            "b2c" to supplyFields(data.table4.b2c),
            "b2b" to supplyFields(data.table4.b2b),
            "exp" to supplyFields(data.table4.exportsWithPayment),
            "rchrg" to supplyFields(data.table4.inwardReverseCharge),
            "cr_nt" to supplyFields(data.table4.creditNotes),
            "dr_nt" to supplyFields(data.table4.debitNotes)
        ),
        "table5" to linkedMapOf(
            "zero_rtd" to taxableOnly(data.table5.zeroRatedWithoutPayment),
            "exmt" to taxableOnly(data.table5.exempted),
            "nil" to taxableOnly(data.table5.nilRated),
            "cr_nt" to taxableOnly(data.table5.creditNotes)
        ),
        "table9" to linkedMapOf(
            "iamt" to payable(data.taxPayable.igst),
            "camt" to payable(data.taxPayable.cgst),
            "samt" to payable(data.taxPayable.sgst),
            "csamt" to payable(data.taxPayable.cess)
        ),
        "table17" to linkedMapOf(
            "items" to data.hsnOutward.map { row ->
                linkedMapOf<String, Any?>(
                    "hsn_sc" to row.hsnSacCode, "uqc" to row.uqc, "qty" to maxOf(0.0, row.totalQuantity ?: 0.0),
                    "rt" to row.gstRatePercent, "txval" to row.taxableValue.rs(), "isconcesstional" to "N",
                    "iamt" to row.igst.rs(), "camt" to row.cgst.rs(), "samt" to row.sgst.rs(), "csamt" to row.cess.rs()
                )
            }
        )
    )
}
