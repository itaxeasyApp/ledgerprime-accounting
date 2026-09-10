package com.example.accounting.domain.taxation.gst

import com.example.accounting.core.common.Money

enum class SupplyType {
    INTRA_STATE, // CGST + SGST
    INTER_STATE, // IGST
    EXPORT,
    EXEMPT
}

/**
 * Whether an entered line amount already includes GST (Inclusive) or excludes it (Exclusive,
 * every existing caller before this field existed). This is the ONE place the distinction is
 * resolved into a taxable value - see [GSTRules.extractTaxableFromInclusive] - so
 * UI live-preview, [com.example.accounting.domain.trading.TradingWorkflowEngine] posting, Room
 * persistence, and PDF rendering all read the same already-computed [TaxBreakdown]/
 * [com.example.accounting.domain.taxation.gst.GstTransaction] figures rather than each
 * re-deriving taxable-vs-inclusive themselves.
 */
enum class GstPricingMode { EXCLUSIVE, INCLUSIVE }

data class TaxBreakdown(
    val taxableAmount: Money,
    val taxRatePercent: Double,
    val supplyType: SupplyType,
    val cgstAmount: Money = Money.ZERO,
    val sgstAmount: Money = Money.ZERO,
    val igstAmount: Money = Money.ZERO,
    val totalTax: Money = Money.ZERO,
    val totalWithTax: Money = Money.ZERO,
    // Phase 5, Priority 3 - per-component rates (derived, not user-entered) and CESS. Defaulted so
    // every existing named-arg TaxBreakdown construction site keeps compiling unchanged.
    val cgstRatePercent: Double = 0.0,
    val sgstRatePercent: Double = 0.0,
    val igstRatePercent: Double = 0.0,
    val cessRatePercent: Double = 0.0,
    val cessAmount: Money = Money.ZERO
)

object GSTRules {

    fun determineSupplyType(companyStateCode: String, partyStateCode: String): SupplyType {
        if (companyStateCode.isBlank() || partyStateCode.isBlank()) return SupplyType.INTRA_STATE
        return if (companyStateCode.trim() == partyStateCode.trim()) {
            SupplyType.INTRA_STATE
        } else {
            SupplyType.INTER_STATE
        }
    }

    fun calculateTax(taxableAmount: Money, ratePercent: Double, supplyType: SupplyType): TaxBreakdown {
        if (ratePercent <= 0.0) {
            return TaxBreakdown(
                taxableAmount = taxableAmount,
                taxRatePercent = 0.0,
                supplyType = supplyType,
                totalTax = Money.ZERO,
                totalWithTax = taxableAmount
            )
        }

        return when (supplyType) {
            SupplyType.INTRA_STATE -> {
                val halfRate = ratePercent / 2.0
                val cgst = taxableAmount.percentage(halfRate)
                val sgst = taxableAmount.percentage(halfRate)
                val totalTax = cgst + sgst
                TaxBreakdown(
                    taxableAmount = taxableAmount,
                    taxRatePercent = ratePercent,
                    supplyType = supplyType,
                    cgstAmount = cgst,
                    sgstAmount = sgst,
                    igstAmount = Money.ZERO,
                    totalTax = totalTax,
                    totalWithTax = taxableAmount + totalTax,
                    cgstRatePercent = halfRate,
                    sgstRatePercent = halfRate
                )
            }
            SupplyType.INTER_STATE -> {
                val igst = taxableAmount.percentage(ratePercent)
                TaxBreakdown(
                    taxableAmount = taxableAmount,
                    taxRatePercent = ratePercent,
                    supplyType = supplyType,
                    cgstAmount = Money.ZERO,
                    sgstAmount = Money.ZERO,
                    igstAmount = igst,
                    totalTax = igst,
                    totalWithTax = taxableAmount + igst,
                    igstRatePercent = ratePercent
                )
            }
            SupplyType.EXPORT, SupplyType.EXEMPT -> {
                TaxBreakdown(
                    taxableAmount = taxableAmount,
                    taxRatePercent = 0.0,
                    supplyType = supplyType,
                    totalTax = Money.ZERO,
                    totalWithTax = taxableAmount
                )
            }
        }
    }

    /**
     * `Taxable Amount = Inclusive Amount x 100 / (100 + GST Rate)`, `GST = Inclusive - Taxable`
     * (the latter is just subtraction at the call site, never a second formula). Rounds to the
     * nearest paisa with HALF_EVEN, the same convention [Money.percentage] already uses - so an
     * inclusive-mode line and an exclusive-mode line at the same net rate settle to the same
     * paisa via two different, individually-exact paths, never silently drifting apart. A 0%
     * rate has nothing to extract - the inclusive amount already equals the taxable amount.
     */
    fun extractTaxableFromInclusive(inclusiveAmount: Money, gstRatePercent: Double): Money {
        if (gstRatePercent <= 0.0) return inclusiveAmount
        val divisor = java.math.BigDecimal.valueOf(100.0 + gstRatePercent)
        val numerator = java.math.BigDecimal(inclusiveAmount.paise).multiply(java.math.BigDecimal.valueOf(100.0))
        val taxablePaise = numerator.divide(divisor, 0, java.math.RoundingMode.HALF_EVEN).toLong()
        return Money.fromPaise(taxablePaise)
    }

    fun isValidGSTIN(gstin: String): Boolean {
        if (gstin.isBlank()) return true // Optional for non-GST parties
        val regex = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$")
        return regex.matches(gstin.trim().uppercase())
    }
}
