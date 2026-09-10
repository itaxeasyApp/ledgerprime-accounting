package com.example.accounting.domain.rendering

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real minimal `.xlsx` writer for [DocumentData] - a genuine Excel-format package (ECMA-376 "Office
 * Open XML": a Zip of a handful of XML parts), never a CSV renamed with an `.xlsx` extension.
 * Deliberately hand-rolled instead of pulling in Apache POI (a many-MB, desktop/server-oriented
 * dependency that is awkward on Android) - only the small set of parts a single-sheet, unstyled
 * workbook actually needs (no shared-strings table either: every text cell is written as
 * `t="inlineStr"`, valid per the same spec, just simpler to generate for a document this size).
 * Every value comes straight from [DocumentData]/[InvoiceSummaryCalculator], the same "single source
 * of truth" contract [CsvExporter]/[JsonDocumentRenderer] already use - this object performs no
 * GST/discount/rounding calculation of its own.
 */
object ExcelExporter {

    private sealed class Cell {
        data class Text(val value: String) : Cell()
        data class Number(val value: Double) : Cell()
    }

    private fun t(value: String): Cell = Cell.Text(value)
    private fun n(value: Double): Cell = Cell.Number(value)

    fun exportDocumentLines(data: DocumentData): ByteArray {
        val summary = InvoiceSummaryCalculator.from(data)
        val rows = mutableListOf<List<Cell>>()

        rows += listOf(t(data.documentType.name.replace('_', ' ')), t("No: ${data.documentNumber}"), t("Date: ${data.documentDate}"))
        rows += listOf(t("Seller"), t(data.seller.name), t(data.seller.gstin), t(data.seller.address))
        rows += listOf(t("Buyer"), t(data.buyer.name), t(data.buyer.gstin), t(data.buyer.address))
        // "Wire Individual Profile into ... csv" (docs/CORRECTIONS_LOG.md) - the proprietor's name
        // (same field the invoice PDF's signature line now prints) is the one piece of Individual
        // Profile data that has a real, well-defined meaning in a document export: who signed for
        // the seller. Only written when one is actually on file - never a fabricated row.
        if (data.branding.signatoryName.isNotBlank()) {
            rows += listOf(t("Proprietor / Authorised Signatory"), t(data.branding.signatoryName))
        }
        rows.add(emptyList())
        rows += listOf(
            t("Description"), t("HSN/SAC"), t("Quantity"), t("Unit"), t("Rate"), t("Discount"),
            t("Taxable Amount"), t("GST Rate %"), t("CGST"), t("SGST"), t("IGST"), t("CESS"), t("Line Total")
        )
        data.items.forEach { line ->
            rows += listOf(
                t(line.description), t(line.hsnSacCode),
                line.quantity?.let { n(it.rawValue / 1000.0) } ?: t(""),
                t(line.unit), n(line.rate.toRupeesDouble()), n(line.discount.toRupeesDouble()),
                n(line.taxableAmount.toRupeesDouble()), n(line.gstRatePercent),
                n(line.cgst.toRupeesDouble()), n(line.sgst.toRupeesDouble()), n(line.igst.toRupeesDouble()),
                n(line.cess.toRupeesDouble()), n(line.lineTotal.toRupeesDouble())
            )
        }
        rows.add(emptyList())
        rows += listOf(t("Grand Total"), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), t(""), n(summary.grandTotal.toRupeesDouble()))

        return buildWorkbook(rows)
    }

    private fun buildWorkbook(rows: List<List<Cell>>): ByteArray {
        val sheetXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { colIndex, cell ->
                    val ref = "${columnLetter(colIndex)}${rowIndex + 1}"
                    when (cell) {
                        is Cell.Text -> if (cell.value.isNotEmpty()) {
                            append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(cell.value)}</t></is></c>")
                        }
                        is Cell.Number -> append("<c r=\"$ref\"><v>${cell.value}</v></c>")
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
        }
        return out.toByteArray()
    }

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
