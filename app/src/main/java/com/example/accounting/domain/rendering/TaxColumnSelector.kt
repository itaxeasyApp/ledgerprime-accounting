package com.example.accounting.domain.rendering

/** Which GST columns an invoice should print - never both CGST/SGST and IGST together (a single
 * supply is always one or the other, per [com.example.accounting.domain.taxation.gst.SupplyType]). */
enum class TaxColumnMode { CGST_SGST, IGST, NONE }

/**
 * Decides [TaxColumnMode] purely from already-computed [DocumentTotals] - reads [DocumentTotals.cgst]/
 * [DocumentTotals.sgst]/[DocumentTotals.igst] (themselves sums of [DocumentLineData] rows already
 * populated by [com.example.accounting.domain.taxation.gst.GstCalculationEngine] at posting/assembly
 * time); performs no GST calculation of its own. Shared by [com.example.accounting.data.rendering.PdfDocumentRenderer]
 * and any Compose invoice preview so "which columns to print" is decided in exactly one place.
 */
object TaxColumnSelector {
    fun resolve(totals: DocumentTotals): TaxColumnMode = when {
        totals.igst.isPositive -> TaxColumnMode.IGST
        totals.cgst.isPositive || totals.sgst.isPositive -> TaxColumnMode.CGST_SGST
        else -> TaxColumnMode.NONE
    }
}
