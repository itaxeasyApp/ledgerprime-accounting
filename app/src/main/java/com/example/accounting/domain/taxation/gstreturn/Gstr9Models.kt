package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Phase 8 - the real, statutorily-numbered GSTR-9 (Annual Return) tables. GSTR-9 is an ANNUAL
 * aggregation, not a second GST calculation nor a re-derivation of GSTR-1/GSTR-3B's own math -
 * [Gstr9ReturnBuilder] builds it by invoking [Gstr1ReturnBuilder]/[Gstr3bReturnBuilder] themselves
 * over the whole financial year's date range and re-packaging their already-computed totals into
 * GSTR-9's own table shape (Table 4/5/6/9/17/18), the same "regroup, never recompute" discipline
 * [Gstr1Models] established.
 *
 * Explicitly absent rather than fabricated (documented once here instead of repeated per field -
 * every one of these needs a source fact/classification this domain has no model for anywhere):
 * - Table 4D/4E (SEZ supply, Deemed Exports) - no SEZ/deemed-export party classification exists.
 * - Table 4F (Advances not yet invoiced) - no advance-receipt model, same GSTR-1 Table 11A/11B gap.
 * - Table 4J (Debit notes on outward supplies) - a Debit Note in this codebase always reverses a
 *   Purchase (inward), never a Sale - see [Gstr1ReturnBuilder]'s own KDoc for this exact boundary.
 * - Table 4K/4L, Part V (10-14) - amendment/prior-year-adjustment tracking does not exist anywhere.
 * - Table 6B's inputs/capital-goods/input-services split - [com.example.accounting.domain.taxation.gst.GstTransaction]
 *   has no such classification on an inward line.
 * - Table 7 (ITC reversed) / Table 8 (GSTR-2A/2B reconciliation, ITC lapsed) - same "no proportionate-
 *   reversal computation, no portal-fetched 2A/2B data" gap [Gstr3bModels] already documents.
 * - Table 15 (Demands and refunds), Table 16 (composition-supplier purchases, deemed supply, goods
 *   sent on approval), Table 19 (late fee) - no source data anywhere in this domain.
 */

/** Table 4 - taxable outward supplies and inward RCM liability declared during the year, the real
 * sub-rows this domain can source (see this file's top-level KDoc for the absent ones, always
 * zero/never fabricated). [subtotal] = A+B+C+G (4H); [netTaxPayable] = subtotal - I (4N, since J/K/L
 * are always zero here - see top-level KDoc). */
data class Gstr9OutwardSummary(
    /** 4A - Supplies made to unregistered persons (B2C = GSTR-1's B2CL + B2CS). */
    val b2c: Gstr3bTaxSummary,
    /** 4B - Supplies made to registered persons (B2B). */
    val b2b: Gstr3bTaxSummary,
    /** 4C - Zero rated supply (Export) - always without payment of tax in this domain (see
     * [Gstr1ExportInvoice]'s own KDoc: every export line already carries zero tax). */
    val zeroRatedExports: Money,
    /** 4G - Inward supplies on which tax is paid on reverse charge (this company's own liability). */
    val inwardReverseCharge: Gstr3bTaxSummary,
    /** 4I - Credit Notes issued in respect of B (GSTR-1's CDNR + CDNUR), a REDUCTION - always
     * subtracted, never added, when computing [netTaxPayable]. */
    val creditNotesIssued: Gstr3bTaxSummary
) {
    val subtotal: Gstr3bTaxSummary get() = Gstr3bTaxSummary(
        taxableValue = b2c.taxableValue + b2b.taxableValue + zeroRatedExports + inwardReverseCharge.taxableValue,
        igst = b2c.igst + b2b.igst + inwardReverseCharge.igst,
        cgst = b2c.cgst + b2b.cgst + inwardReverseCharge.cgst,
        sgst = b2c.sgst + b2b.sgst + inwardReverseCharge.sgst,
        cess = b2c.cess + b2b.cess + inwardReverseCharge.cess
    )
    val netTaxPayable: Gstr3bTaxSummary get() = Gstr3bTaxSummary(
        taxableValue = subtotal.taxableValue - creditNotesIssued.taxableValue,
        igst = subtotal.igst - creditNotesIssued.igst, cgst = subtotal.cgst - creditNotesIssued.cgst,
        sgst = subtotal.sgst - creditNotesIssued.sgst, cess = subtotal.cess - creditNotesIssued.cess
    )
}

/** Table 5 - outward supplies on which tax is not payable during the year. [nonGstSupply] is
 * always zero (see [Gstr3bModels]' own KDoc - no non-GST-supply concept exists in this domain). */
data class Gstr9ExemptOutwardSummary(
    val zeroRatedWithoutTax: Money,
    val nilRated: Money,
    val exempted: Money,
    val nonGstSupply: Money = Money.ZERO
) {
    val total: Money get() = zeroRatedWithoutTax + nilRated + exempted + nonGstSupply
}

/** Table 6 - ITC availed during the year, one combined bucket (see this file's top-level KDoc for
 * why the inputs/capital-goods/input-services split is never fabricated). */
data class Gstr9ItcSummary(
    val totalItcAvailed: Gstr3bTaxSummary,
    val itcOnInwardReverseCharge: Gstr3bTaxSummary
)

/** Table 9 - tax payable and paid, one row per tax head - "paid" here is [Gstr9OutwardSummary.netTaxPayable]
 * netted against [Gstr9ItcSummary.totalItcAvailed] (the same estimation [Gstr3bReturnData.estimatedCashLiability]
 * already performs per period, summed for the year) - a preview figure, never a statement of actual
 * cash ledger entries (this domain has no GST cash/credit ledger balance model). */
data class Gstr9TaxPaidRow(
    val head: String,
    val taxPayable: Money,
    val paidThroughItc: Money,
    val paidInCash: Money
)

/** Table 17/18 - HSN-wise summary, outward and inward, reusing [Gstr1HsnRow]'s exact shape for
 * outward (Table 17 = [Gstr1ReturnData.hsn] verbatim, the whole-year build) and a mirrored, new
 * inward aggregation for Table 18 (no equivalent existed before this pass - GSTR-3B never needed
 * HSN-level ITC detail, GSTR-9 does). */
data class Gstr9HsnInwardRow(
    val hsnSacCode: String,
    val gstRatePercent: Double,
    val taxableValue: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money
)

data class Gstr9ValidationIssue(val severity: Gstr1ValidationSeverity, val code: String, val message: String)

/** The complete GSTR-9 draft for one financial year - every field always present, matching
 * [Gstr1ReturnData]/[Gstr3bReturnData]'s own "always-present, possibly-empty/zero" discipline.
 * [periodKey] is the [com.example.accounting.domain.financialyear.FinancialYear.fyCode] itself
 * (e.g. "2026-27") - GSTR-9 has no month/quarter, it IS the year. */
data class Gstr9ReturnData(
    val companyGstin: String,
    val periodKey: String,
    val outward: Gstr9OutwardSummary,
    val exemptOutward: Gstr9ExemptOutwardSummary,
    val itc: Gstr9ItcSummary,
    val taxPaid: List<Gstr9TaxPaidRow> = emptyList(),
    val hsnOutward: List<Gstr1HsnRow> = emptyList(),
    val hsnInward: List<Gstr9HsnInwardRow> = emptyList(),
    val validationIssues: List<Gstr9ValidationIssue> = emptyList()
) {
    val hasBlockingErrors: Boolean get() = validationIssues.any { it.severity == Gstr1ValidationSeverity.ERROR }
}
