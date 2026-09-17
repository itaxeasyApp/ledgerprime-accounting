package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import com.example.accounting.domain.taxation.gst.GstTransaction

/**
 * Phase 8 - builds the real GSTR-9 annual tables by regrouping two ALREADY-BUILT structures -
 * [Gstr1ReturnData] and [Gstr3bReturnData], both built by their own frozen builders over the WHOLE
 * financial year's date range (the repository's job, mirroring [Gstr1ReturnBuilder.build]'s own
 * "caller supplies already period-filtered facts" contract) - never a third GST calculation. Table
 * 18 (HSN-wise inward summary) is the one genuinely new aggregation this builder performs, since
 * neither GSTR-1 (outward-only) nor GSTR-3B (rate-invariant summary) needed HSN-level inward detail
 * before GSTR-9.
 */
object Gstr9ReturnBuilder {

    fun build(
        companyGstin: String,
        fyCode: String,
        gstr1: Gstr1ReturnData,
        gstr3b: Gstr3bReturnData,
        inwardTransactionsForYear: List<GstTransaction>
    ): Gstr9ReturnData {
        val b2c = sum(gstr1.b2cl.flatMap { it.rateLines } + gstr1.b2cs.map { it.toRateLineLike() })
        val b2b = sum(gstr1.b2b.flatMap { party -> party.invoices.flatMap { it.rateLines } })
        val zeroRatedExports = gstr1.exports.fold(Money.ZERO) { acc, e -> acc + e.taxableValue }
        val creditNotes = sum(
            gstr1.cdnr.flatMap { party -> party.notes.flatMap { it.rateLines } } +
                gstr1.cdnur.flatMap { it.rateLines }
        )

        val outward = Gstr9OutwardSummary(
            b2c = b2c, b2b = b2b, zeroRatedExports = zeroRatedExports,
            inwardReverseCharge = gstr3b.outward.reverseChargeInward, creditNotesIssued = creditNotes
        )

        val exemptOutward = Gstr9ExemptOutwardSummary(
            zeroRatedWithoutTax = Money.ZERO, // GSTR-1's zero-rated exports already counted in Table 4C above - Table 5's own zero-rated row is for a distinct without-tax-declaration case this domain does not separately track.
            nilRated = gstr1.nilRated.filter { it.bucket == SupplyNatureBucket.NIL_RATED }.fold(Money.ZERO) { acc, r -> acc + r.taxableValue },
            exempted = gstr1.nilRated.filter { it.bucket == SupplyNatureBucket.EXEMPT }.fold(Money.ZERO) { acc, r -> acc + r.taxableValue }
        )

        val itc = Gstr9ItcSummary(
            totalItcAvailed = gstr3b.itc.netItc,
            itcOnInwardReverseCharge = gstr3b.itc.available.inwardReverseCharge
        )

        val taxPaid = listOf(
            taxPaidRow("IGST", outward.netTaxPayable.igst, itc.totalItcAvailed.igst),
            taxPaidRow("CGST", outward.netTaxPayable.cgst, itc.totalItcAvailed.cgst),
            taxPaidRow("SGST", outward.netTaxPayable.sgst, itc.totalItcAvailed.sgst),
            taxPaidRow("CESS", outward.netTaxPayable.cess, itc.totalItcAvailed.cess)
        )

        val hsnInward = inwardTransactionsForYear
            .groupBy { it.hsnSacCode to it.gstRatePercent }
            .map { (key, rows) ->
                Gstr9HsnInwardRow(
                    hsnSacCode = key.first, gstRatePercent = key.second,
                    taxableValue = rows.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
                    cgst = rows.fold(Money.ZERO) { acc, l -> acc + l.cgst },
                    sgst = rows.fold(Money.ZERO) { acc, l -> acc + l.sgst },
                    igst = rows.fold(Money.ZERO) { acc, l -> acc + l.igst },
                    cess = rows.fold(Money.ZERO) { acc, l -> acc + l.cess }
                )
            }

        return Gstr9ReturnData(
            companyGstin = companyGstin, periodKey = fyCode,
            outward = outward, exemptOutward = exemptOutward, itc = itc,
            taxPaid = taxPaid, hsnOutward = gstr1.hsn, hsnInward = hsnInward
        )
    }

    /** [Gstr1B2csRow] already carries its own tax-head totals (never invoice-level) - this just
     * lets it fold into the same [sum] helper as a genuine [Gstr1RateLine] would, zero re-computation. */
    private fun Gstr1B2csRow.toRateLineLike(): Gstr1RateLine = Gstr1RateLine(gstRatePercent, taxableValue, cgst, sgst, igst, cess)

    private fun sum(lines: List<Gstr1RateLine>): Gstr3bTaxSummary = Gstr3bTaxSummary(
        taxableValue = lines.fold(Money.ZERO) { acc, l -> acc + l.taxableValue },
        igst = lines.fold(Money.ZERO) { acc, l -> acc + l.igst },
        cgst = lines.fold(Money.ZERO) { acc, l -> acc + l.cgst },
        sgst = lines.fold(Money.ZERO) { acc, l -> acc + l.sgst },
        cess = lines.fold(Money.ZERO) { acc, l -> acc + l.cess }
    )

    private fun taxPaidRow(head: String, payable: Money, itcAvailable: Money): Gstr9TaxPaidRow {
        val throughItc = if (itcAvailable > payable) payable else itcAvailable
        val throughItcFloored = if (throughItc.paise < 0L) Money.ZERO else throughItc
        val cash = payable - throughItcFloored
        val cashFloored = if (cash.paise < 0L) Money.ZERO else cash
        return Gstr9TaxPaidRow(head = head, taxPayable = payable, paidThroughItc = throughItcFloored, paidInCash = cashFloored)
    }
}
