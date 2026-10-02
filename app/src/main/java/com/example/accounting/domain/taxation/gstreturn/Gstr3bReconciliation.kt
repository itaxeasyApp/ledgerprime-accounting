package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType

/**
 * Cross-checks a built [Gstr3bReturnData] against the other records it must agree with:
 *
 *  - **GSTR-1** (ERROR): the GST portal fills Tables 3.1(a,b,c) from GSTR-1, so the outward figures
 *    here must equal the GSTR-1 tables built from the same period. A difference means a supply
 *    reached one return and not the other (for example a GST-only sale to a registered party, which
 *    has no invoice number for GSTR-1);
 *  - **GST transactions** (ERROR): every outward row of the period must land in exactly one of
 *    3.1(a)/(b)/(c), and the reverse-charge rows must equal 3.1(d);
 *  - **accounting records** (WARNING): the net movement of the GST ledgers for the period should equal
 *    the same tax per the GST rows. Manual journals can legitimately move a GST ledger, so this one
 *    informs and does not block.
 *
 * Not reconciled, because the app has no such data: GSTR-2B (which fills 3.1(d), 4(A) and 4(D)(2) on
 * the portal).
 */
object Gstr3bReconciliation {

    /** Net movement of the GST ledgers over the period, positive when in their natural direction. */
    data class LedgerTotals(
        val outputTax: Gstr3bHeads,
        val forwardInputTax: Gstr3bHeads,
        val reverseChargeLiability: Gstr3bHeads
    )

    const val ITC_NOT_RECONCILED_CODE = "GSTR3B_ITC_GSTR2B_NOT_RECONCILED"

    /**
     * Table 4 here is a sum of the books' own purchase rows. The portal fills 4(A) and 4(D)(2) from the
     * taxpayer's GSTR-2B, and this app holds no GSTR-2B data, so that ITC can never be checked against it.
     * Always an ERROR for GSTR-3B (never for GSTR-9, which does not call this): the return must not become
     * READY, nor produce upload JSON, while its ITC is unreconciled. Lifts only when a 2B reconciliation exists.
     */
    fun itcNotReconciledWithGstr2b(): Gstr1ValidationIssue = Gstr1ValidationIssue(
        Gstr1ValidationSeverity.ERROR, ITC_NOT_RECONCILED_CODE,
        "GSTR-2B reconciliation is unavailable: Table 4 ITC is taken from this app's purchase records only and has not been reconciled with GSTR-2B " +
            "(no GSTR-2B data is recorded). Imports (IMPG/IMPS), ISD credit, ITC reversals under rules 37/39/42/43/section 17(5) and ineligible ITC " +
            "are NOT RECORDED. Reconcile ITC with GSTR-2B on the GST portal before filing."
    )

    private fun gstr1Outward(d: Gstr1ReturnData): Gstr3bAmounts {
        fun Gstr1RateLine.a() = Gstr3bAmounts(taxableValue, igst, cgst, sgst, cess)
        val rateLines = d.b2b.flatMap { it.invoices }.flatMap { it.rateLines } +
            d.b2cl.flatMap { it.rateLines } +
            d.cdnr.flatMap { it.notes }.flatMap { it.rateLines } +
            d.cdnur.flatMap { it.rateLines }
        var total = rateLines.fold(Gstr3bAmounts.ZERO) { acc, l -> acc + l.a() }
        total += d.b2cs.fold(Gstr3bAmounts.ZERO) { acc, r -> acc + Gstr3bAmounts(r.taxableValue, r.igst, r.cgst, r.sgst, r.cess) }
        total += d.exports.fold(Gstr3bAmounts.ZERO) { acc, e -> acc + Gstr3bAmounts(e.taxableValue, e.igst, Money.ZERO, Money.ZERO, Money.ZERO) }
        total += d.nilRated.fold(Gstr3bAmounts.ZERO) { acc, n -> acc + Gstr3bAmounts(taxable = n.taxableValue) }
        return total
    }

    private fun differences(label: String, left: String, a: Gstr3bAmounts, right: String, b: Gstr3bAmounts): String? {
        val parts = listOf(
            Triple("taxable value", a.taxable, b.taxable), Triple("IGST", a.igst, b.igst), Triple("CGST", a.cgst, b.cgst),
            Triple("SGST", a.sgst, b.sgst), Triple("CESS", a.cess, b.cess)
        ).filter { it.second.paise != it.third.paise }
            .map { "${it.first}: $left ${it.second.formatPlain()} vs $right ${it.third.formatPlain()}" }
        return if (parts.isEmpty()) null else "$label - ${parts.joinToString("; ")}"
    }

    fun reconcile(
        gstr3b: Gstr3bReturnData,
        gstr1: Gstr1ReturnData,
        transactions: List<GstTransaction>,
        ledgers: LedgerTotals? = null
    ): List<Gstr1ValidationIssue> {
        val issues = mutableListOf<Gstr1ValidationIssue>()
        val outward3b = gstr3b.outwardTaxable + gstr3b.outwardZeroRated + gstr3b.outwardNilExempt

        differences("Table 3.1(a)+(b)+(c) differs from GSTR-1", "GSTR-3B", outward3b, "GSTR-1", gstr1Outward(gstr1))?.let {
            issues += Gstr1ValidationIssue(Gstr1ValidationSeverity.ERROR, "GSTR3B_GSTR1_MISMATCH", it)
        }

        val outwardRows = transactions.filter { it.direction == GstDirection.OUTPUT }
        val rowsTotal = outwardRows.fold(Gstr3bAmounts.ZERO) { acc, t -> acc + Gstr3bAmounts(t.taxableAmount, t.igst, t.cgst, t.sgst, t.cess) }
        differences("Table 3.1(a)+(b)+(c) differs from the period's GST transactions", "GSTR-3B", outward3b, "transactions", rowsTotal)?.let {
            issues += Gstr1ValidationIssue(Gstr1ValidationSeverity.ERROR, "GSTR3B_TRANSACTION_MISMATCH", it)
        }

        val rcmRows = transactions.filter {
            it.direction == GstDirection.INPUT && it.chargeType == GstChargeType.REVERSE_CHARGE &&
                !(it.supplyNature == GstSupplyNature.EXEMPT || it.supplyNature == GstSupplyNature.NIL_RATED || it.supplyType == SupplyType.EXEMPT)
        }
        val rcmTotal = rcmRows.fold(Gstr3bAmounts.ZERO) { acc, t -> acc + Gstr3bAmounts(t.taxableAmount, t.igst, t.cgst, t.sgst, t.cess) }
        differences("Table 3.1(d) differs from the period's reverse-charge transactions", "GSTR-3B", gstr3b.inwardReverseCharge, "transactions", rcmTotal)?.let {
            issues += Gstr1ValidationIssue(Gstr1ValidationSeverity.ERROR, "GSTR3B_RCM_MISMATCH", it)
        }

        if (ledgers != null) {
            val forwardItc = transactions.filter {
                it.direction == GstDirection.INPUT && it.chargeType == GstChargeType.FORWARD_CHARGE
            }.fold(Gstr3bHeads()) { acc, t -> Gstr3bHeads(acc.igst + t.igst, acc.cgst + t.cgst, acc.sgst + t.sgst, acc.cess + t.cess) }

            fun headDiff(name: String, rows: Money, ledger: Money) =
                if (rows.paise != ledger.paise) "$name: GST rows ${rows.formatPlain()} vs ledger ${ledger.formatPlain()}" else null
            val ledgerFindings = listOfNotNull(
                headDiff("Output IGST", outward3b.igst, ledgers.outputTax.igst),
                headDiff("Output CGST", outward3b.cgst, ledgers.outputTax.cgst),
                headDiff("Output SGST", outward3b.sgst, ledgers.outputTax.sgst),
                headDiff("Input IGST", forwardItc.igst, ledgers.forwardInputTax.igst),
                headDiff("Input CGST", forwardItc.cgst, ledgers.forwardInputTax.cgst),
                headDiff("Input SGST", forwardItc.sgst, ledgers.forwardInputTax.sgst),
                headDiff("RCM liability IGST", gstr3b.inwardReverseCharge.igst, ledgers.reverseChargeLiability.igst),
                headDiff("RCM liability CGST", gstr3b.inwardReverseCharge.cgst, ledgers.reverseChargeLiability.cgst),
                headDiff("RCM liability SGST", gstr3b.inwardReverseCharge.sgst, ledgers.reverseChargeLiability.sgst)
            )
            if (ledgerFindings.isNotEmpty()) {
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.WARNING, "GSTR3B_LEDGER_MISMATCH",
                    "GST ledgers differ from the GST transactions for this period (a manual journal can cause this) - " + ledgerFindings.joinToString("; ")
                )
            }
        }
        return issues
    }
}
