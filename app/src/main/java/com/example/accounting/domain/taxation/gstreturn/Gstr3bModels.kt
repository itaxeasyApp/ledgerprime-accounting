package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Phase 8 - the real, statutorily-numbered GSTR-3B tables, built ONLY from facts
 * [com.example.accounting.domain.taxation.gst.GstTransaction] already carries (see
 * [Gstr3bReturnBuilder]) - never a second GST calculation, mirroring [Gstr1Models]' own discipline
 * exactly. GSTR-3B is a SUMMARY return (rate-invariant totals, not invoice-level) covering BOTH
 * outward (Table 3.1/3.2) and inward/ITC (Table 4/5) facts, unlike GSTR-1 which is outward-only.
 *
 * Explicitly absent rather than fabricated (every one of these needs a source fact this domain has
 * no model for anywhere - documented here once instead of repeated per field):
 * - Table 3.1.1 (e-commerce supplies u/s 9(5)) - no e-commerce-operator concept exists.
 * - Table 3.2's Composition/UIN-holder sub-rows - a party's [com.example.accounting.domain.accounting.GstRegistrationStatus]
 *   only ever distinguishes REGISTERED/UNREGISTERED, never "registered under Composition" or "is a
 *   UIN holder" - only the inter-state-to-unregistered row is real.
 * - Table 4(A)(1)/(2)/(4) (Import of goods, Import of services, ISD credit) - [GstChargeType] only
 *   distinguishes FORWARD_CHARGE/REVERSE_CHARGE; there is no import-vs-domestic or ISD-sourced fact.
 * - Table 4(B) ITC Reversed (CGST Rules 42/43) - a proportionate-reversal computation this domain
 *   does not perform (no exempt-turnover-ratio tracking).
 * - Table 4(D) Ineligible ITC (Section 17(5)) - no blocked-credit classification on GstTransaction.
 * - Table 5.1 (Interest & late fee payable) - no filing-delay/due-date-vs-actual computation exists.
 */

/** One statutory tax-head total - Table 3.1's five rows, and Table 3.2's inter-state summary, all
 * share this exact shape (taxable value + the applicable tax heads). */
data class Gstr3bTaxSummary(
    val taxableValue: Money,
    val igst: Money,
    val cgst: Money,
    val sgst: Money,
    val cess: Money
) {
    val totalTax: Money get() = igst + cgst + sgst + cess

    companion object {
        val ZERO = Gstr3bTaxSummary(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO)
    }
}

/** Table 3.1 - outward supplies and inward supplies liable to reverse charge, one row per
 * statutory letter (a)-(e). (d)'s IGST/CGST/SGST/CESS is the RECIPIENT's own reverse-charge tax
 * liability (this company owes it as the buyer), never conflated with (a)-(c)'s outward liability. */
data class Gstr3bOutwardSummary(
    /** (a) Outward taxable supplies (other than zero rated, nil rated and exempted). */
    val taxableOutward: Gstr3bTaxSummary,
    /** (b) Outward taxable supplies (zero rated) - exports. */
    val zeroRatedOutward: Gstr3bTaxSummary,
    /** (c) Other outward supplies (Nil rated, exempted). */
    val nilExemptOutward: Gstr3bTaxSummary,
    /** (d) Inward supplies (liable to reverse charge) - this company's own RCM tax liability. */
    val reverseChargeInward: Gstr3bTaxSummary,
    /** (e) Non-GST outward supplies - always zero (no non-GST-supply concept/ledger flag exists in
     * this codebase to source a real figure from; kept as an explicit, honest zero rather than an
     * omitted row, matching the statutory form's own fixed five-row shape). */
    val nonGstOutward: Money = Money.ZERO
)

/** Table 3.2 - the inter-state-to-unregistered-persons portion of 3.1(a)/(b), broken out by Place
 * of Supply (the one sub-classification this domain can actually source - see this file's own
 * top-level KDoc for why Composition/UIN rows are never fabricated). */
data class Gstr3bInterStateUnregisteredRow(
    val posStateCode: String,
    val taxableValue: Money,
    val igst: Money
)

/** Table 4(A) - ITC available, by the two real sub-buckets this domain can source (see this file's
 * top-level KDoc for why Import/ISD rows are absent). */
data class Gstr3bItcAvailable(
    /** 4(A)(3) - Inward supplies liable to reverse charge (other than import of goods/services). */
    val inwardReverseCharge: Gstr3bTaxSummary,
    /** 4(A)(5) - All other ITC (ordinary forward-charge purchases). */
    val allOtherItc: Gstr3bTaxSummary
) {
    val total: Gstr3bTaxSummary get() = Gstr3bTaxSummary(
        taxableValue = inwardReverseCharge.taxableValue + allOtherItc.taxableValue,
        igst = inwardReverseCharge.igst + allOtherItc.igst,
        cgst = inwardReverseCharge.cgst + allOtherItc.cgst,
        sgst = inwardReverseCharge.sgst + allOtherItc.sgst,
        cess = inwardReverseCharge.cess + allOtherItc.cess
    )
}

/** Table 4 - Eligible ITC. [reversedItc]/[ineligibleItc] are always zero (see this file's top-level
 * KDoc) - present as real, explicit fields (never omitted) so a future pass that adds the missing
 * source data (exempt-turnover tracking, Section 17(5) classification) only has to populate them,
 * never restructure this shape. [netItc] = [Gstr3bItcAvailable.total] - [reversedItc], the one
 * honest computation this domain can perform today (never a fabricated post-reversal figure). */
data class Gstr3bItcSummary(
    val available: Gstr3bItcAvailable,
    val reversedItc: Gstr3bTaxSummary = Gstr3bTaxSummary.ZERO,
    val ineligibleItc: Gstr3bTaxSummary = Gstr3bTaxSummary.ZERO
) {
    val netItc: Gstr3bTaxSummary get() = Gstr3bTaxSummary(
        taxableValue = available.total.taxableValue - reversedItc.taxableValue,
        igst = available.total.igst - reversedItc.igst,
        cgst = available.total.cgst - reversedItc.cgst,
        sgst = available.total.sgst - reversedItc.sgst,
        cess = available.total.cess - reversedItc.cess
    )
}

/** Table 5 - values of exempt, Nil-rated, and non-GST inward supplies, split Composition-scheme-
 * supplier vs. other (the one axis GSTR-3B's real form uses) - this domain has no
 * supplier-is-a-Composition-dealer fact (see this file's top-level KDoc), so [fromComposition] is
 * always zero and [fromOthers] carries the real total. */
data class Gstr3bExemptInwardSummary(
    val fromComposition: Money = Money.ZERO,
    val fromOthers: Money
)

data class Gstr3bValidationIssue(
    val severity: Gstr1ValidationSeverity,
    val code: String,
    val message: String
)

/** The complete GSTR-3B draft for one period - every field always present (never omitted), matching
 * [Gstr1ReturnData]'s own "always-present, possibly-empty/zero" export discipline. */
data class Gstr3bReturnData(
    val companyGstin: String,
    val periodKey: String,
    val outward: Gstr3bOutwardSummary,
    val interStateUnregistered: List<Gstr3bInterStateUnregisteredRow> = emptyList(),
    val itc: Gstr3bItcSummary,
    val exemptInward: Gstr3bExemptInwardSummary,
    val validationIssues: List<Gstr3bValidationIssue> = emptyList()
) {
    val hasBlockingErrors: Boolean get() = validationIssues.any { it.severity == Gstr1ValidationSeverity.ERROR }

    /** Table 6.1 payable-in-cash preview (a convenience, not a payment instruction) - Output tax
     * (3.1 a+b+c's tax heads, (e) never carries tax by definition) plus this company's own
     * reverse-charge liability (3.1(d), which is never offsettable against ITC by law - paid in
     * cash always) minus Net ITC, set off in the order CGST Rule 88A/Section 49B actually mandates
     * (never a same-head-only net - see [netSetOff]), floored at zero per head (GST law never nets
     * a head negative - a genuine ITC carry-forward is out of scope, same "never fabricate"
     * boundary as this file's other absent rows). */
    val estimatedCashLiability: Gstr3bTaxSummary get() {
        // RCM liability (3.1(d)) is always cash-paid by law - it is never netted against ITC,
        // even though the ITC that RCM payment itself generates (already inside itc.netItc via
        // Gstr3bItcAvailable.inwardReverseCharge) IS available to net against ordinary output tax.
        val igst = outward.taxableOutward.igst + outward.zeroRatedOutward.igst
        val cgst = outward.taxableOutward.cgst + outward.zeroRatedOutward.cgst
        val sgst = outward.taxableOutward.sgst + outward.zeroRatedOutward.sgst
        val cess = outward.taxableOutward.cess + outward.zeroRatedOutward.cess
        val (netIgst, netCgst, netSgst) = netSetOff(igst, cgst, sgst, itc.netItc.igst, itc.netItc.cgst, itc.netItc.sgst)
        fun floorAtZero(amount: Money): Money = if (amount.paise < 0L) Money.ZERO else amount
        return Gstr3bTaxSummary(
            taxableValue = Money.ZERO,
            igst = netIgst + outward.reverseChargeInward.igst,
            cgst = netCgst + outward.reverseChargeInward.cgst,
            sgst = netSgst + outward.reverseChargeInward.sgst,
            // Cess ITC only ever offsets Cess liability - the law grants it no cross-utilization
            // with IGST/CGST/SGST at all, so a same-head-only net is the statutorily correct one.
            cess = floorAtZero(cess - itc.netItc.cess) + outward.reverseChargeInward.cess
        )
    }

    private data class SetOffResult(val igst: Money, val cgst: Money, val sgst: Money)

    /** CGST Rule 88A / Section 49B's mandatory ITC set-off order - IGST ITC first fully absorbs
     * IGST liability, then any leftover reduces CGST liability, then SGST; CGST ITC next absorbs
     * CGST liability then any leftover IGST liability; SGST ITC absorbs SGST liability then any
     * leftover IGST liability. CGST ITC and SGST ITC can NEVER offset each other - that cross-head
     * restriction is the one part of this order every implementation must get right. A same-head-
     * only net (this function's precursor) understates real ITC utilization whenever the tax
     * heads outward and inward supplies fall under don't match - e.g. an inter-state sale (IGST
     * liability) paid for with an intra-state purchase's ITC (CGST+SGST) - which live device
     * testing surfaced directly: a ₹1,800 IGST sale against a ₹900 CGST+SGST purchase showed
     * ₹1,800 cash liability instead of the correct, dashboard-matching ₹900. Each head is floored
     * at zero (no negative liability / no carry-forward modelled, same boundary as this file's
     * other absent rows). */
    private fun netSetOff(igstLiability: Money, cgstLiability: Money, sgstLiability: Money, igstItc: Money, cgstItc: Money, sgstItc: Money): SetOffResult {
        var igstRem = igstLiability; var cgstRem = cgstLiability; var sgstRem = sgstLiability
        fun use(itc: Money, liability: Money): Pair<Money, Money> {
            val used = if (itc.paise < liability.paise) itc else liability
            return used to (itc - used)
        }
        // IGST ITC: IGST liability, then CGST, then SGST.
        val (usedIgstOnIgst, igstItcAfterIgst) = use(igstItc, igstRem); igstRem -= usedIgstOnIgst
        val (usedIgstOnCgst, igstItcAfterCgst) = use(igstItcAfterIgst, cgstRem); cgstRem -= usedIgstOnCgst
        val (usedIgstOnSgst, _) = use(igstItcAfterCgst, sgstRem); sgstRem -= usedIgstOnSgst
        // CGST ITC: CGST liability first, then any leftover against IGST liability.
        val (usedCgstOnCgst, cgstItcLeftover) = use(cgstItc, cgstRem); cgstRem -= usedCgstOnCgst
        val (usedCgstOnIgst, _) = use(cgstItcLeftover, igstRem); igstRem -= usedCgstOnIgst
        // SGST ITC: SGST liability first, then any leftover against IGST liability.
        val (usedSgstOnSgst, sgstItcLeftover) = use(sgstItc, sgstRem); sgstRem -= usedSgstOnSgst
        val (usedSgstOnIgst, _) = use(sgstItcLeftover, igstRem); igstRem -= usedSgstOnIgst
        fun floorAtZero(amount: Money): Money = if (amount.paise < 0L) Money.ZERO else amount
        return SetOffResult(floorAtZero(igstRem), floorAtZero(cgstRem), floorAtZero(sgstRem))
    }
}
