package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Phase 8, Step 6 (GSTR-3B) - the statutory GSTR-3B tables, built ONLY from facts
 * [com.example.accounting.domain.taxation.gst.GstTransaction] already carries (see [Gstr3bBuilder]);
 * never a second GST calculation. Anything this domain has no source of truth for is reported as an
 * explicit zero with the reason documented where it is built - never a guessed figure:
 * non-GST outward supplies (3.1(e)), imports of goods/services, ISD credit, composition/UIN
 * recipients (3.2), ITC ineligible under section 17(5)/rule 38 (4B(1), 4D), non-GST inward supplies
 * (5), and interest/late fee (5.1 - needs the real filing date and the turnover-based fee caps).
 */

/** One row of tax figures: taxable value plus the four heads. Credit/Debit-Note rows are signed. */
data class Gstr3bAmounts(
    val taxable: Money = Money.ZERO,
    val igst: Money = Money.ZERO,
    val cgst: Money = Money.ZERO,
    val sgst: Money = Money.ZERO,
    val cess: Money = Money.ZERO
) {
    operator fun plus(o: Gstr3bAmounts) = Gstr3bAmounts(taxable + o.taxable, igst + o.igst, cgst + o.cgst, sgst + o.sgst, cess + o.cess)
    operator fun minus(o: Gstr3bAmounts) = Gstr3bAmounts(taxable - o.taxable, igst - o.igst, cgst - o.cgst, sgst - o.sgst, cess - o.cess)
    val totalTax: Money get() = igst + cgst + sgst + cess

    fun abs() = Gstr3bAmounts(taxable.abs(), igst.abs(), cgst.abs(), sgst.abs(), cess.abs())

    companion object { val ZERO = Gstr3bAmounts() }
}

/** One Table 4 row keyed by its GSTN `ty` code (IMPG/IMPS/ISRC/ISD/OTH for 4A, RUL/OTH for 4B and 4D). */
data class Gstr3bItcRow(val type: String, val amounts: Gstr3bAmounts = Gstr3bAmounts.ZERO)

/** Table 3.2 - inter-state supplies made to unregistered persons, one row per place of supply. */
data class Gstr3bInterStateRow(val posStateCode: String, val taxable: Money, val igst: Money)

/** Table 5 - inward supplies that carry no tax, split by whether the supplier is in another state. */
data class Gstr3bInwardExemptRow(val type: String, val interState: Money, val intraState: Money)

/** The four tax heads, used for Table 6.1. */
data class Gstr3bHeads(
    val igst: Money = Money.ZERO,
    val cgst: Money = Money.ZERO,
    val sgst: Money = Money.ZERO,
    val cess: Money = Money.ZERO
) {
    val total: Money get() = igst + cgst + sgst + cess
}

/**
 * Table 6.1 - what is payable for the period and how it is settled, from THIS period's figures only
 * (the opening credit-ledger and cash-ledger balances are not stored in this app):
 *  - [outwardLiability]: tax on outward supplies (3.1(a) and the IGST on 3.1(b)), floored at zero;
 *  - [reverseChargeLiability]: tax on inward supplies liable to reverse charge (3.1(d)) - payable in
 *    CASH only, never through the credit ledger;
 *  - [itcAvailable]: net ITC from Table 4C, floored at zero;
 *  - the `paid*` amounts: how [itcAvailable] discharges [outwardLiability] in the statutory order
 *    (IGST credit first against IGST, then CGST, then SGST; CGST/SGST credit against their own head,
 *    then IGST; cess credit only against cess - CGST and SGST credit never cross);
 *  - [cashPayable]: the remainder plus the reverse-charge tax - the challan amounts.
 */
data class Gstr3bPayment(
    val outwardLiability: Gstr3bHeads,
    val reverseChargeLiability: Gstr3bHeads,
    val itcAvailable: Gstr3bHeads,
    val paidByIgstCredit: Gstr3bHeads,
    val paidByCgstCredit: Gstr3bHeads,
    val paidBySgstCredit: Gstr3bHeads,
    val paidByCessCredit: Gstr3bHeads,
    val cashPayable: Gstr3bHeads
)

data class Gstr3bReturnData(
    val companyGstin: String,
    val periodKey: String,
    /** 3.1(a) outward taxable supplies (other than zero rated, nil rated and exempted). */
    val outwardTaxable: Gstr3bAmounts,
    /** 3.1(b) outward taxable supplies (zero rated) - exports/SEZ. */
    val outwardZeroRated: Gstr3bAmounts,
    /** 3.1(c) other outward supplies (nil rated, exempted) - taxable value only. */
    val outwardNilExempt: Gstr3bAmounts,
    /** 3.1(d) inward supplies liable to reverse charge. */
    val inwardReverseCharge: Gstr3bAmounts,
    /** 3.1(e) non-GST outward supplies - no such supply is recorded in this domain, always zero. */
    val outwardNonGst: Gstr3bAmounts = Gstr3bAmounts.ZERO,
    /** 3.2 to unregistered persons. Composition/UIN holders are not modeled, so those lists are not carried. */
    val interStateUnregistered: List<Gstr3bInterStateRow>,
    val itcAvailable: List<Gstr3bItcRow>,
    val itcReversed: List<Gstr3bItcRow>,
    val itcNet: Gstr3bAmounts,
    val itcIneligible: List<Gstr3bItcRow>,
    val inwardExempt: List<Gstr3bInwardExemptRow>,
    val payment: Gstr3bPayment
)
