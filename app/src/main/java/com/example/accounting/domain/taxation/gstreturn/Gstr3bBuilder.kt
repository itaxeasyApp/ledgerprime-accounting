package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType

/**
 * Builds the GSTR-3B tables (3.1(a)-(e), 3.2, 4, 5 and the 6.1 payment advice) from the period's
 * already-persisted [GstTransaction] rows - the same rows [Gstr1ReturnBuilder] reads, so the two
 * returns can never disagree about a supply (see [Gstr3bReconciliation]). Pure; no tax is ever
 * recomputed here - every figure is a regroup/sum of numbers posting already calculated.
 *
 * What the portal does instead of this app (per the GST portal's own GSTR-3B manual): Tables 3.1(a,b,c,e)
 * and 3.2 are auto-populated from GSTR-1/1A; 3.1(d), 4(A) and 4(D)(2) from GSTR-2B; Table 5.1
 * (interest and late fee) is calculated by the system from the filing date; Table 6.1 payment and
 * the challan are done on the portal. This builder therefore produces the figures a taxpayer would
 * enter or check against those, and an informational 6.1 advice - never the portal's own calculations.
 */
object Gstr3bBuilder {

    // internal: GSTR-9 classifies each line with exactly these rules, never a second copy of them.
    internal fun isZeroRated(t: GstTransaction) = t.supplyNature == GstSupplyNature.EXPORT || t.supplyType == SupplyType.EXPORT

    internal fun isNilOrExempt(t: GstTransaction) =
        !isZeroRated(t) && (t.supplyNature == GstSupplyNature.EXEMPT || t.supplyNature == GstSupplyNature.NIL_RATED || t.supplyType == SupplyType.EXEMPT)

    private fun GstTransaction.amounts() = Gstr3bAmounts(taxableAmount, igst, cgst, sgst, cess)

    private fun GstTransaction.taxOnly() = Gstr3bAmounts(Money.ZERO, igst, cgst, sgst, cess)

    private fun List<GstTransaction>.sumAmounts() = fold(Gstr3bAmounts.ZERO) { acc, t -> acc + t.amounts() }

    private fun List<GstTransaction>.sumTax() = fold(Gstr3bAmounts.ZERO) { acc, t -> acc + t.taxOnly() }

    fun build(companyGstin: String, periodKey: String, transactions: List<GstTransaction>): Gstr3bReturnData {
        val companyState = companyGstin.trim().take(2)
        val outward = transactions.filter { it.direction == GstDirection.OUTPUT }
        val inward = transactions.filter { it.direction == GstDirection.INPUT }

        // ---- 3.1 (each LINE is classified on its own, like GSTR-1)
        val outwardTaxableRows = outward.filter { !isZeroRated(it) && !isNilOrExempt(it) }
        val outwardTaxable = outwardTaxableRows.sumAmounts()
        val outwardZeroRated = outward.filter { isZeroRated(it) }.sumAmounts()
        val outwardNilExempt = Gstr3bAmounts(taxable = outward.filter { isNilOrExempt(it) }.fold(Money.ZERO) { acc, t -> acc + t.taxableAmount })
        val reverseChargeRows = inward.filter { it.chargeType == GstChargeType.REVERSE_CHARGE && !isNilOrExempt(it) }
        val inwardReverseCharge = reverseChargeRows.sumAmounts()

        // ---- 3.2 inter-state supplies to unregistered persons, by place of supply. Composition
        // dealers and UIN holders are not a recipient status this domain records.
        val interStateUnregistered = outwardTaxableRows
            .filter { it.supplyType == SupplyType.INTER_STATE && it.partyGstin.isBlank() }
            .groupBy { it.placeOfSupply }
            .map { (pos, rows) -> Gstr3bInterStateRow(pos, rows.fold(Money.ZERO) { a, t -> a + t.taxableAmount }, rows.fold(Money.ZERO) { a, t -> a + t.igst }) }
            .filter { it.taxable.paise != 0L || it.igst.paise != 0L }

        // ---- 4 ITC. A positive inward row is ITC availed; a negative one (a purchase return / debit
        // note adjusting an earlier purchase) is a reversal. Imports, ISD credit and anything ineligible
        // under section 17(5)/rule 38 are not recorded in this domain and stay zero.
        val itcRows = inward.filter { !isNilOrExempt(it) }
        val availedRows = itcRows.filter { it.taxableAmount.paise >= 0L }
        val reversedRows = itcRows.filter { it.taxableAmount.paise < 0L }
        val itcAvailable = listOf(
            Gstr3bItcRow("IMPG"),
            Gstr3bItcRow("IMPS"),
            Gstr3bItcRow("ISRC", availedRows.filter { it.chargeType == GstChargeType.REVERSE_CHARGE }.sumTax()),
            Gstr3bItcRow("ISD"),
            Gstr3bItcRow("OTH", availedRows.filter { it.chargeType == GstChargeType.FORWARD_CHARGE }.sumTax())
        )
        val itcReversed = listOf(
            Gstr3bItcRow("RUL"),
            Gstr3bItcRow("OTH", reversedRows.sumTax().abs())
        )
        val itcNet = itcAvailable.fold(Gstr3bAmounts.ZERO) { a, r -> a + r.amounts } -
            itcReversed.fold(Gstr3bAmounts.ZERO) { a, r -> a + r.amounts }
        val itcIneligible = listOf(Gstr3bItcRow("RUL"), Gstr3bItcRow("OTH"))

        // ---- 5 inward supplies that carry no tax (exempt, nil-rated, composition suppliers), split by
        // the supplier's state - known only when the supplier has a GSTIN; an unregistered supplier's
        // state is not recorded, so it is counted intra-state rather than guessed inter-state.
        val exemptInward = inward.filter { isNilOrExempt(it) }
        fun supplierIsOtherState(t: GstTransaction): Boolean {
            val supplierState = t.partyGstin.trim().take(2)
            return companyState.length == 2 && supplierState.length == 2 && supplierState != companyState
        }
        val inwardExempt = listOf(
            Gstr3bInwardExemptRow(
                "GST",
                interState = exemptInward.filter { supplierIsOtherState(it) }.fold(Money.ZERO) { a, t -> a + t.taxableAmount },
                intraState = exemptInward.filter { !supplierIsOtherState(it) }.fold(Money.ZERO) { a, t -> a + t.taxableAmount }
            ),
            Gstr3bInwardExemptRow("NONGST", Money.ZERO, Money.ZERO)
        )

        // ---- 6.1 advice
        val outwardLiability = Gstr3bHeads(
            igst = (outwardTaxable.igst + outwardZeroRated.igst), cgst = outwardTaxable.cgst,
            sgst = outwardTaxable.sgst, cess = (outwardTaxable.cess + outwardZeroRated.cess)
        )
        val payment = Gstr3bPaymentCalculator.compute(
            outwardLiability = outwardLiability,
            reverseChargeLiability = Gstr3bHeads(inwardReverseCharge.igst, inwardReverseCharge.cgst, inwardReverseCharge.sgst, inwardReverseCharge.cess),
            itcAvailable = Gstr3bHeads(itcNet.igst, itcNet.cgst, itcNet.sgst, itcNet.cess)
        )

        return Gstr3bReturnData(
            companyGstin = companyGstin, periodKey = periodKey,
            outwardTaxable = outwardTaxable, outwardZeroRated = outwardZeroRated,
            outwardNilExempt = outwardNilExempt, inwardReverseCharge = inwardReverseCharge,
            interStateUnregistered = interStateUnregistered,
            itcAvailable = itcAvailable, itcReversed = itcReversed, itcNet = itcNet, itcIneligible = itcIneligible,
            inwardExempt = inwardExempt, payment = payment
        )
    }
}

/**
 * The informational Table 6.1 advice: how this period's net ITC discharges this period's outward
 * liability, and what is left to pay in cash. Order (the rules the GST portal's own manual gives):
 * IGST credit first against IGST, then CGST, then SGST; CGST credit against CGST, then IGST; SGST
 * credit against SGST, then IGST; CGST and SGST credit never cross; cess credit only against cess.
 * Tax liable to reverse charge is payable in cash only. Negative heads are treated as zero. The real
 * offset is done by the taxpayer on the portal ("Offset Liability"), which also applies the opening
 * credit-ledger and cash-ledger balances this app does not hold.
 */
object Gstr3bPaymentCalculator {

    fun compute(outwardLiability: Gstr3bHeads, reverseChargeLiability: Gstr3bHeads, itcAvailable: Gstr3bHeads): Gstr3bPayment {
        fun floor(m: Money) = maxOf(0L, m.paise)
        var igstLiab = floor(outwardLiability.igst)
        var cgstLiab = floor(outwardLiability.cgst)
        var sgstLiab = floor(outwardLiability.sgst)
        var cessLiab = floor(outwardLiability.cess)
        var igstCredit = floor(itcAvailable.igst)
        var cgstCredit = floor(itcAvailable.cgst)
        var sgstCredit = floor(itcAvailable.sgst)
        var cessCredit = floor(itcAvailable.cess)

        // paid[creditHead][liabilityHead]
        var igstToIgst = 0L; var igstToCgst = 0L; var igstToSgst = 0L
        var cgstToCgst = 0L; var cgstToIgst = 0L
        var sgstToSgst = 0L; var sgstToIgst = 0L
        var cessToCess = 0L

        var x = minOf(igstCredit, igstLiab); igstToIgst += x; igstCredit -= x; igstLiab -= x
        x = minOf(igstCredit, cgstLiab); igstToCgst += x; igstCredit -= x; cgstLiab -= x
        x = minOf(igstCredit, sgstLiab); igstToSgst += x; igstCredit -= x; sgstLiab -= x
        x = minOf(cgstCredit, cgstLiab); cgstToCgst += x; cgstCredit -= x; cgstLiab -= x
        x = minOf(cgstCredit, igstLiab); cgstToIgst += x; cgstCredit -= x; igstLiab -= x
        x = minOf(sgstCredit, sgstLiab); sgstToSgst += x; sgstCredit -= x; sgstLiab -= x
        x = minOf(sgstCredit, igstLiab); sgstToIgst += x; sgstCredit -= x; igstLiab -= x
        x = minOf(cessCredit, cessLiab); cessToCess += x; cessCredit -= x; cessLiab -= x

        fun m(paise: Long) = Money.fromPaise(paise)
        return Gstr3bPayment(
            outwardLiability = Gstr3bHeads(m(floor(outwardLiability.igst)), m(floor(outwardLiability.cgst)), m(floor(outwardLiability.sgst)), m(floor(outwardLiability.cess))),
            reverseChargeLiability = Gstr3bHeads(m(floor(reverseChargeLiability.igst)), m(floor(reverseChargeLiability.cgst)), m(floor(reverseChargeLiability.sgst)), m(floor(reverseChargeLiability.cess))),
            itcAvailable = Gstr3bHeads(m(floor(itcAvailable.igst)), m(floor(itcAvailable.cgst)), m(floor(itcAvailable.sgst)), m(floor(itcAvailable.cess))),
            paidByIgstCredit = Gstr3bHeads(igst = m(igstToIgst), cgst = m(igstToCgst), sgst = m(igstToSgst)),
            paidByCgstCredit = Gstr3bHeads(igst = m(cgstToIgst), cgst = m(cgstToCgst)),
            paidBySgstCredit = Gstr3bHeads(igst = m(sgstToIgst), sgst = m(sgstToSgst)),
            paidByCessCredit = Gstr3bHeads(cess = m(cessToCess)),
            cashPayable = Gstr3bHeads(
                igst = m(igstLiab + floor(reverseChargeLiability.igst)),
                cgst = m(cgstLiab + floor(reverseChargeLiability.cgst)),
                sgst = m(sgstLiab + floor(reverseChargeLiability.sgst)),
                cess = m(cessLiab + floor(reverseChargeLiability.cess))
            )
        )
    }
}
