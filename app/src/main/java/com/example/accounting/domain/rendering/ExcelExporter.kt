package com.example.accounting.domain.rendering

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real minimal `.xlsx` writer for [DocumentData] - a genuine Excel-format package (ECMA-376 "Office
 * Open XML": a Zip of a handful of XML parts), never a CSV renamed with an `.xlsx` extension.
 * Deliberately hand-rolled instead of pulling in Apache POI (a many-MB, desktop/server-oriented
 * dependency that is awkward on Android) - only the small set of parts a single-sheet workbook
 * actually needs (no shared-strings table either: every text cell is written as `t="inlineStr"`,
 * valid per the same spec, just simpler to generate for a document this size).
 * Every value comes straight from [DocumentData]/[InvoiceSummaryCalculator], the same "single source
 * of truth" contract [CsvExporter]/[JsonDocumentRenderer] already use - this object performs no
 * GST/discount/rounding calculation of its own.
 *
 * Week 2 (Play Store update plan, "pdf excel csv in a proper professional manner") - was a fully
 * unstyled workbook (this file's own prior doc comment said so): every column the same default
 * ~8-character width, no bold anywhere, raw unformatted doubles for money. Added a real (if
 * minimal) `styles.xml` part - bold header/label rows, a ₹-prefixed 2-decimal number format for
 * money cells (plain 2-decimal for quantity/percent), and wider default columns - the smallest
 * styling vocabulary that makes the sheet readable without a full Apache-POI-sized style engine.
 */
object ExcelExporter {

    private sealed class Cell {
        data class Text(val value: String, val bold: Boolean = false) : Cell()
        data class Number(val value: Double, val currency: Boolean = true, val bold: Boolean = false) : Cell()
    }

    private fun t(value: String, bold: Boolean = false): Cell = Cell.Text(value, bold)
    private fun n(value: Double, currency: Boolean = true, bold: Boolean = false): Cell = Cell.Number(value, currency, bold)

    fun exportDocumentLines(data: DocumentData): ByteArray {
        val summary = InvoiceSummaryCalculator.from(data)
        val rows = mutableListOf<List<Cell>>()

        rows += listOf(t(data.documentType.name.replace('_', ' '), bold = true), t("No: ${data.documentNumber}"), t("Date: ${data.documentDate}"))
        rows += listOf(t("Seller", bold = true), t(data.seller.name), t(data.seller.gstin), t(data.seller.address))
        rows += listOf(t("Buyer", bold = true), t(data.buyer.name), t(data.buyer.gstin), t(data.buyer.address))
        // "Wire Individual Profile into ... csv" (docs/CORRECTIONS_LOG.md) - the proprietor's name
        // (same field the invoice PDF's signature line now prints) is the one piece of Individual
        // Profile data that has a real, well-defined meaning in a document export: who signed for
        // the seller. Only written when one is actually on file - never a fabricated row.
        if (data.branding.signatoryName.isNotBlank()) {
            rows += listOf(t("Proprietor / Authorised Signatory", bold = true), t(data.branding.signatoryName))
        }
        rows.add(emptyList())
        rows += listOf(
            t("Description", bold = true), t("HSN/SAC", bold = true), t("Quantity", bold = true), t("Unit", bold = true),
            t("Rate", bold = true), t("Discount", bold = true), t("Taxable Amount", bold = true), t("GST Rate %", bold = true),
            t("CGST", bold = true), t("SGST", bold = true), t("IGST", bold = true), t("CESS", bold = true), t("Line Total", bold = true)
        )
        data.items.forEach { line ->
            rows += listOf(
                t(line.description), t(line.hsnSacCode),
                line.quantity?.let { n(it.rawValue / 1000.0, currency = false) } ?: t(""),
                t(line.unit), n(line.rate.toRupeesDouble()), n(line.discount.toRupeesDouble()),
                n(line.taxableAmount.toRupeesDouble()), n(line.gstRatePercent, currency = false),
                n(line.cgst.toRupeesDouble()), n(line.sgst.toRupeesDouble()), n(line.igst.toRupeesDouble()),
                n(line.cess.toRupeesDouble()), n(line.lineTotal.toRupeesDouble())
            )
        }
        rows.add(emptyList())
        rows += listOf(
            t("Grand Total", bold = true), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""),
            n(summary.grandTotal.toRupeesDouble(), bold = true)
        )

        return buildWorkbook(rows)
    }

    // Style indices into <cellXfs> (built in buildStylesXml, in this exact order):
    // 0 = default, 1 = bold text, 2 = plain number (2dp), 3 = bold number (2dp),
    // 4 = currency (₹, 2dp), 5 = bold currency.
    private fun styleIndexFor(cell: Cell): Int = when (cell) {
        is Cell.Text -> if (cell.bold) 1 else 0
        is Cell.Number -> when {
            cell.currency && cell.bold -> 5
            cell.currency -> 4
            cell.bold -> 3
            else -> 2
        }
    }

    private fun buildWorkbook(rows: List<List<Cell>>): ByteArray {
        val columnCount = rows.maxOfOrNull { it.size } ?: 0
        val sheetXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            if (columnCount > 0) {
                append("<cols>")
                append("<col min=\"1\" max=\"$columnCount\" width=\"16\" customWidth=\"1\"/>")
                append("</cols>")
            }
            append("<sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { colIndex, cell ->
                    val ref = "${columnLetter(colIndex)}${rowIndex + 1}"
                    val style = styleIndexFor(cell)
                    when (cell) {
                        is Cell.Text -> if (cell.value.isNotEmpty()) {
                            append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(cell.value)}</t></is></c>")
                        }
                        is Cell.Number -> append("<c r=\"$ref\" s=\"$style\"><v>${cell.value}</v></c>")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }

        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"

        val rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

        val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Invoice\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "</workbook>"

        val workbookRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun writeEntry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            writeEntry("[Content_Types].xml", contentTypes)
            writeEntry("_rels/.rels", rootRels)
            writeEntry("xl/workbook.xml", workbookXml)
            writeEntry("xl/_rels/workbook.xml.rels", workbookRels)
            writeEntry("xl/worksheets/sheet1.xml", sheetXml)
            writeEntry("xl/styles.xml", buildStylesXml())
        }
        return out.toByteArray()
    }

    /** Six cellXfs entries, indices matching [styleIndexFor]'s comment exactly - 0 default, 1 bold
     * text, 2 plain number, 3 bold number, 4 currency, 5 bold currency. Custom numFmtIds start at
     * 164 (0-163 are reserved built-ins per the OOXML spec). */
    private fun buildStylesXml(): String = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"2\">" +
        "<numFmt numFmtId=\"164\" formatCode=\"0.00\"/>" +
        "<numFmt numFmtId=\"165\" formatCode=\"&quot;₹&quot;#,##0.00\"/>" +
        "</numFmts>" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "</fonts>" +
        "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"6\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" + // 0 default
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" + // 1 bold text
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" + // 2 number
        "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" + // 3 bold number
        "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" + // 4 currency
        "<xf numFmtId=\"165\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>" + // 5 bold currency
        "</cellXfs>" +
        "</styleSheet>"

    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
