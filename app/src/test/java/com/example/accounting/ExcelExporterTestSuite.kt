package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.rendering.DocumentBrandingSnapshot
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.DocumentLineData
import com.example.accounting.domain.rendering.DocumentPartySnapshot
import com.example.accounting.domain.rendering.DocumentPaymentInfo
import com.example.accounting.domain.rendering.DocumentReferenceInfo
import com.example.accounting.domain.rendering.DocumentTotals
import com.example.accounting.domain.rendering.ExcelExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Week 2 (Play Store update plan, "pdf excel csv in a proper professional manner") - real
 * structural verification of [ExcelExporter]'s xlsx output. Nothing in this Android project can
 * actually open the file in Excel/Sheets to eyeball it, so this unzips the produced bytes and
 * XML-parses every part: a malformed styles.xml/worksheet would make the real file fail to open in
 * Excel with an opaque "unreadable content" repair prompt, and a plain `DocumentBuilder.parse`
 * catches exactly that class of mistake immediately, in a fast pure-JVM test.
 */
class ExcelExporterTestSuite {

    private fun sampleDocumentData(): DocumentData {
        val line1 = DocumentLineData(
            itemId = "ITEM_A", description = "Widget", hsnSacCode = "8471",
            quantity = Quantity.fromLong(2), rate = Money.fromRupees(600L), discount = Money.fromRupees(20L),
            taxableAmount = Money.fromRupees(1000L), gstRatePercent = 18.0,
            cgst = Money.fromRupees(90L), sgst = Money.fromRupees(90L), igst = Money.ZERO, cess = Money.ZERO,
            lineTotal = Money.fromRupees(1180L)
        )
        val totals = DocumentTotals(
            taxableAmount = Money.fromRupees(1000L), cgst = Money.fromRupees(90L), sgst = Money.fromRupees(90L),
            igst = Money.ZERO, cess = Money.ZERO, roundOff = Money.ZERO, grandTotal = Money.fromRupees(1180L)
        )
        return DocumentData(
            documentId = "V1", companyId = "C1", documentType = DocumentType.SALES_INVOICE,
            documentNumber = "SI-0001", documentDate = LocalDate.of(2026, 1, 1),
            seller = DocumentPartySnapshot(name = "Seller Co"), buyer = DocumentPartySnapshot(name = "Buyer Co"),
            items = listOf(line1), totals = totals, paymentInformation = DocumentPaymentInfo(),
            references = DocumentReferenceInfo(), branding = DocumentBrandingSnapshot(), isPosted = true
        )
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun assertWellFormedXml(xml: String, label: String) {
        try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw AssertionError("$label is not well-formed XML (Excel would fail to open this file): ${e.message}", e)
        }
    }

    @Test
    fun export_producesAllRequiredOoxmlParts() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue("styles.xml must exist - Week 2 added real bold/currency styling", entries.containsKey("xl/styles.xml"))
    }

    @Test
    fun export_everyXmlPartIsWellFormed() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        entries.forEach { (name, content) -> assertWellFormedXml(content, name) }
    }

    @Test
    fun export_stylesXmlDeclaresAllSixCellFormats() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        val styles = entries.getValue("xl/styles.xml")
        assertTrue(styles.contains("count=\"6\""))
        // The ₹-prefixed currency format must actually be present, not just a plain "0.00".
        assertTrue(styles.contains("₹"))
    }

    @Test
    fun export_contentTypesReferencesStylesPart() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        assertTrue(entries.getValue("[Content_Types].xml").contains("/xl/styles.xml"))
        assertTrue(entries.getValue("xl/_rels/workbook.xml.rels").contains("styles.xml"))
    }

    @Test
    fun export_headerRowCellsUseBoldStyle() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        // Row 5 is the column-header row (doc-type/no/date, Seller, Buyer, blank, then headers).
        assertTrue("The 'Description' header cell must use the bold style (s=\"1\")", sheet.contains("<is><t xml:space=\"preserve\">Description</t></is>") && sheet.contains("t=\"inlineStr\"><is><t xml:space=\"preserve\">Description"))
        assertTrue(sheet.contains("s=\"1\""))
    }

    @Test
    fun export_moneyCellsUseCurrencyStyle() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue("At least one money cell must use the plain currency style (s=\"4\")", sheet.contains("s=\"4\""))
        assertTrue("The Grand Total cell must use the bold currency style (s=\"5\")", sheet.contains("s=\"5\""))
    }

    @Test
    fun export_columnWidthsAreExplicitlySet() {
        val entries = unzip(ExcelExporter.exportDocumentLines(sampleDocumentData()))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue("Columns must have an explicit customWidth - the old unstyled sheet left every column at Excel's default ~8-char width", sheet.contains("customWidth=\"1\""))
    }
}
