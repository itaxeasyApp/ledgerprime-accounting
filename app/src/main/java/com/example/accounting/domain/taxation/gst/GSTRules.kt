package com.example.accounting.domain.taxation.gst

import com.example.accounting.core.common.Money

enum class SupplyType {
    INTRA_STATE, // CGST + SGST
    INTER_STATE, // IGST
    EXPORT,
    EXEMPT
}

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

    fun isValidGSTIN(gstin: String): Boolean {
        if (gstin.isBlank()) return true // Optional for non-GST parties
        val regex = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$")
        return regex.matches(gstin.trim().uppercase())
    }
}
