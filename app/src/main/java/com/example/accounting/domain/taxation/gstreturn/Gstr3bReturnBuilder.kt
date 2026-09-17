package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType

/**
 * Phase 8 - builds the real, statutorily-numbered GSTR-3B tables from already-persisted
 * [GstTransaction] facts (never a second GST calculation), mirroring [Gstr1ReturnBuilder]'s own
 * "regroup/aggregate, never recompute" discipline exactly. Unlike GSTR-1 (outward-only), GSTR-3B
 * needs BOTH directions - callers pass the period's full active transaction set, unfiltered by
 * direction; this builder splits OUTPUT/INPUT itself.
 */
object Gstr3bReturnBuilder {

    fun build(companyGstin: String, periodKey: String, transactions: List<GstTransaction>): Gstr3bReturnData {
        val outward = transactions.filter { it.direction == GstDirection.OUTPUT }
        val inward = transactions.filter { it.direction == GstDirection.INPUT }

        // ---- Table 3.1 ----
        val taxableOutward = sum(outward.filter {
            it.supplyNature == GstSupplyNature.NORMAL && (it.supplyType == SupplyType.INTRA_STATE || it.supplyType == SupplyType.INTER_STATE)
        })
        val zeroRatedOutward = sum(outward.filter { it.supplyNature == GstSupplyNature.EXPORT || it.supplyType == SupplyType.EXPORT })
        val nilExemptOutward = sum(outward.filter {
            it.supplyNature == GstSupplyNature.EXEMPT || it.supplyNature == GstSupplyNature.NIL_RATED || it.supplyType == SupplyType.EXEMPT
        })
        // 3.1(d) - THIS company's own reverse-charge liability as the recipient of an inward
        // REVERSE_CHARGE supply (never the outward side - an outward RCM line's tax is the
        // recipient's liability, not reported as this company's own output tax here).
        val reverseChargeInward = sum(inward.filter { it.chargeType == GstChargeType.REVERSE_CHARGE })

        val outwardSummary = Gstr3bOutwardSummary(
            taxableOutward = taxableOutward,
            zeroRatedOutward = zeroRatedOutward,
            nilExemptOutward = nilExemptOutward,
            reverseChargeInward = reverseChargeInward
        )

        // ---- Table 3.2 - inter-state to unregistered, POS-wise (see Gstr3bModels' KDoc for why
        // Composition/UIN rows are never fabricated) ----
        val interStateUnregistered = outward
            .filter { it.supplyNature == GstSupplyNature.NORMAL && it.supplyType == SupplyType.INTER_STATE && it.partyGstin.isBlank() }
            .groupBy { it.placeOfSupply }
            .map { (pos, rows) ->
                Gstr3bInterStateUnregisteredRow(
                    posStateCode = pos,
                    taxableValue = rows.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
                    igst = rows.fold(Money.ZERO) { acc, l -> acc + l.igst }
                )
            }
            .sortedBy { it.posStateCode }

        // ---- Table 4 - Eligible ITC ----
        val itc = Gstr3bItcSummary(
            available = Gstr3bItcAvailable(
                inwardReverseCharge = sum(inward.filter { it.chargeType == GstChargeType.REVERSE_CHARGE }),
                allOtherItc = sum(inward.filter { it.chargeType == GstChargeType.FORWARD_CHARGE && it.supplyNature == GstSupplyNature.NORMAL })
            )
        )

        // ---- Table 5 - exempt/Nil-rated/non-GST inward ----
        val exemptInward = Gstr3bExemptInwardSummary(
            fromOthers = inward.filter { it.supplyNature == GstSupplyNature.EXEMPT || it.supplyNature == GstSupplyNature.NIL_RATED }
                .fold(Money.ZERO) { acc, l -> acc + l.taxableAmount }
        )

        return Gstr3bReturnData(
            companyGstin = companyGstin,
            periodKey = periodKey,
            outward = outwardSummary,
            interStateUnregistered = interStateUnregistered,
            itc = itc,
            exemptInward = exemptInward
        )
    }

    private fun sum(rows: List<GstTransaction>): Gstr3bTaxSummary = Gstr3bTaxSummary(
        taxableValue = rows.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
        igst = rows.fold(Money.ZERO) { acc, l -> acc + l.igst },
        cgst = rows.fold(Money.ZERO) { acc, l -> acc + l.cgst },
        sgst = rows.fold(Money.ZERO) { acc, l -> acc + l.sgst },
        cess = rows.fold(Money.ZERO) { acc, l -> acc + l.cess }
    )
}
