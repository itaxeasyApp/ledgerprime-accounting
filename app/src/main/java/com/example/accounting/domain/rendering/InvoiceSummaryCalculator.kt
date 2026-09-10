package com.example.accounting.domain.rendering

import com.example.accounting.core.common.Money

/**
 * Every figure an invoice footer needs, in one place - built by SUMMING/formatting values
 * [DocumentData] already carries (each already computed once, either by
 * [com.example.accounting.domain.taxation.gst.GstCalculationEngine] at posting time or by
 * [com.example.accounting.domain.trading.TradingWorkflowEngine]'s discount subtraction) - this
 * class performs no GST/discount/rounding calculation of its own, only addition and the
 * [IndianCurrencyWords] formatting. Shared by [com.example.accounting.data.rendering.PdfDocumentRenderer]
 * and any Compose invoice preview so both show the exact same numbers.
 */
data class InvoiceSummaryTotals(
    val totalQuantity: Double,
    /** Sum of Rate x Quantity per line, BEFORE discount/tax - the "sticker" line value. Genuinely
     * different from [taxableAmount] whenever a discount was applied or pricing was GST-inclusive;
     * that difference is expected, not an error - see [DocumentLineData.discount]/[taxableAmount]. */
    val itemAmount: Money,
    val totalDiscount: Money,
    val taxableAmount: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money,
    val totalGst: Money,
    val roundOff: Money,
    val grandTotal: Money,
    val taxColumnMode: TaxColumnMode,
    val amountInWords: String
)

object InvoiceSummaryCalculator {
    fun from(data: DocumentData): InvoiceSummaryTotals {
        val totalQuantity = data.items.sumOf { (it.quantity?.rawValue ?: 0L) / 1000.0 }
        val itemAmount = data.items.fold(Money.ZERO) { acc, line ->
            val qty = line.quantity?.rawValue?.let { it / 1000.0 } ?: 1.0
            acc + (line.rate * qty)
        }
        val totalDiscount = data.items.fold(Money.ZERO) { acc, line -> acc + line.discount }
        val totals = data.totals
        val totalGst = totals.cgst + totals.sgst + totals.igst + totals.cess

        return InvoiceSummaryTotals(
            totalQuantity = totalQuantity,
            itemAmount = itemAmount,
            totalDiscount = totalDiscount,
            taxableAmount = totals.taxableAmount,
            cgst = totals.cgst,
            sgst = totals.sgst,
            igst = totals.igst,
            cess = totals.cess,
            totalGst = totalGst,
            roundOff = totals.roundOff,
            grandTotal = totals.grandTotal,
            taxColumnMode = TaxColumnSelector.resolve(totals),
            amountInWords = IndianCurrencyWords.toWords(totals.grandTotal)
        )
    }
}
