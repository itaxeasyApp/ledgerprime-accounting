package com.example.accounting.domain.rendering

/**
 * CSV export adapter (Phase 7D, Section 19) - consumes an already-assembled [DocumentData] and
 * writes its identity/line items/totals as CSV. Deliberately dumb: it never computes a total, never
 * recalculates GST, never reads any DAO/engine itself - every value comes straight from the
 * [DocumentData] it's handed, same as [JsonDocumentRenderer]/[ExcelExporter].
 */
object CsvExporter {
    private const val COLUMNS = "Description,HSN/SAC,Quantity,Unit,Rate,Discount,TaxableAmount,GSTRate,CGST,SGST,IGST,CESS,LineTotal"

    fun exportDocumentLines(data: DocumentData): String {
        val sb = StringBuilder()
        // Document-branding correction (docs/CORRECTIONS_LOG.md, "not going into pdf and csv") -
        // a CSV of just line items had zero seller/buyer identity in it, unlike the PDF. These
        // header rows carry the same fields the PDF prints, plus the proprietor's name (from
        // Individual Profile, via `data.branding.signatoryName` - the one field with a real,
        // well-defined meaning in an export: who signed for the seller) whenever one is on file.
        sb.append(csvField(data.documentType.name.replace('_', ' '))).append(',')
            .append(csvField("No: ${data.documentNumber}")).append(',')
            .append(csvField("Date: ${data.documentDate}")).append('\n')
        sb.append(csvField("Seller")).append(',').append(csvField(data.seller.name)).append(',')
            .append(csvField(data.seller.gstin)).append(',').append(csvField(data.seller.address)).append('\n')
        sb.append(csvField("Buyer")).append(',').append(csvField(data.buyer.name)).append(',')
            .append(csvField(data.buyer.gstin)).append(',').append(csvField(data.buyer.address)).append('\n')
        if (data.branding.signatoryName.isNotBlank()) {
            sb.append(csvField("Proprietor / Authorised Signatory")).append(',').append(csvField(data.branding.signatoryName)).append('\n')
        }
        sb.append('\n')
        sb.append(COLUMNS).append('\n')
        for (line in data.items) {
            sb.append(csvField(line.description)).append(',')
                .append(csvField(line.hsnSacCode)).append(',')
                .append(line.quantity?.let { it.rawValue / 1000.0 } ?: "").append(',')
                .append(csvField(line.unit)).append(',')
                .append(line.rate.toRupeesDouble()).append(',')
                .append(line.discount.toRupeesDouble()).append(',')
                .append(line.taxableAmount.toRupeesDouble()).append(',')
                .append(line.gstRatePercent).append(',')
                .append(line.cgst.toRupeesDouble()).append(',')
                .append(line.sgst.toRupeesDouble()).append(',')
                .append(line.igst.toRupeesDouble()).append(',')
                .append(line.cess.toRupeesDouble()).append(',')
                .append(line.lineTotal.toRupeesDouble())
                .append('\n')
        }
        // 11 empty fields (Description..IGST), then a "Grand Total" label (CESS column), then the
        // value (LineTotal column) - built as a list to avoid hand-counting comma separators.
        val footerFields = List(11) { "" } + listOf("Grand Total", data.totals.grandTotal.toRupeesDouble().toString())
        sb.append(footerFields.joinToString(",")).append('\n')
        return sb.toString()
    }

    private fun csvField(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
}
