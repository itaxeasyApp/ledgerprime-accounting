package com.example.accounting.data.rendering

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.DocumentTemplate
import java.io.File
import java.io.FileOutputStream

/**
 * Renders [DocumentData] + [DocumentTemplate] to a PDF file using Android's built-in
 * `android.graphics.pdf.PdfDocument` (no third-party PDF library - avoids adding a new dependency
 * for a single-page, text/table layout). Needs an Android `Context` for private-file storage, so
 * (unlike [com.example.accounting.domain.rendering.JsonDocumentRenderer]) this lives in the `data`
 * layer, not `domain` - the accounting domain must never import an Android/PDF type (Section 14).
 *
 * Deliberately simple (Section 11: "do not overengineer a drag-and-drop editor yet") - draws a
 * header, the line-item table respecting [DocumentTemplate.visualConfig]'s `visibleColumns`, and a
 * totals/terms footer. Every value drawn comes from [DocumentData] - this class performs no
 * accounting/GST calculation of any kind.
 *
 * KNOWN TEST-ENVIRONMENT LIMITATION (see `docs/44_PDF_PRINT_SHARE.md`): `android.graphics.pdf.*` is
 * framework code with no real behavior under a plain JVM unit test (and this project's Robolectric
 * setup is already broken by an unrelated environment issue - the same `DefaultSdkProvider`
 * failure behind the 3 known pre-existing Robolectric failures). This class is therefore not
 * exercised by `Phase7DTestSuite` the way [com.example.accounting.domain.rendering.JsonDocumentRenderer]
 * is; the document-assembly and template-resolution logic it depends on (all in `AccountingRepository`)
 * IS fully unit-tested.
 */
object PdfDocumentRenderer {
    private const val PAGE_WIDTH_POINTS = 595 // A4 at 72dpi
    private const val PAGE_HEIGHT_POINTS = 842

    fun render(context: Context, data: DocumentData, template: DocumentTemplate): File {
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_POINTS, PAGE_HEIGHT_POINTS, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        val layout = template.visualConfig.layout
        val typography = template.visualConfig.typography
        val colors = template.visualConfig.colors

        val headingPaint = Paint().apply {
            color = Color.parseColor(colors.heading)
            textSize = typography.headingFontSizeSp.toFloat()
            isFakeBoldText = typography.boldHeadings
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor(colors.text)
            textSize = typography.baseFontSizeSp.toFloat()
        }

        var y = layout.marginPointsTop.toFloat()
        val left = layout.marginPointsLeft.toFloat()

        // GST-mandatory on a tax invoice (B2B and B2C alike) - Place of Supply/recipient state,
        // never omitted just because the rest of the party block (GSTIN/address) is also minimal
        // today (Section 11: this renderer stays deliberately simple).
        fun stateLabel(party: com.example.accounting.domain.rendering.DocumentPartySnapshot): String? =
            if (party.stateCode.isBlank()) null else "State: ${party.stateCode} - ${party.stateName.ifBlank { "Unknown" }}"

        if (layout.showHeader) {
            canvas.drawText(data.seller.name, left, y, headingPaint)
            y += typography.headingFontSizeSp + 4
            stateLabel(data.seller)?.let {
                canvas.drawText(it, left, y, bodyPaint)
                y += typography.baseFontSizeSp + 4
            }
            canvas.drawText(data.documentType.name.replace('_', ' '), left, y, bodyPaint)
            y += typography.baseFontSizeSp + 4
            canvas.drawText("No: ${data.documentNumber}  Date: ${data.documentDate}", left, y, bodyPaint)
            y += typography.baseFontSizeSp + 12
        }

        canvas.drawText("Bill To: ${data.buyer.name}", left, y, bodyPaint)
        y += typography.baseFontSizeSp + 4
        stateLabel(data.buyer)?.let {
            canvas.drawText(it, left, y, bodyPaint)
            y += typography.baseFontSizeSp + 4
        }
        y += 8

        for (line in data.items) {
            val parts = mutableListOf<String>()
            if ("description" in layout.visibleColumns) parts += line.description
            if ("hsnSacCode" in layout.visibleColumns) parts += line.hsnSacCode
            if ("quantity" in layout.visibleColumns) parts += (line.quantity?.let { "${it.rawValue / 1000.0}" } ?: "")
            if ("rate" in layout.visibleColumns) parts += line.rate.format()
            if ("taxableAmount" in layout.visibleColumns) parts += line.taxableAmount.format()
            if ("lineTotal" in layout.visibleColumns) parts += line.lineTotal.format()
            canvas.drawText(parts.joinToString("  |  "), left, y, bodyPaint)
            y += typography.baseFontSizeSp + 6
        }

        y += 8
        canvas.drawText("Grand Total: ${data.totals.grandTotal.format()}", left, y, headingPaint)
        y += typography.headingFontSizeSp + 8

        if (layout.showTermsAndConditions && data.terms.isNotBlank()) {
            canvas.drawText(data.terms, left, y, bodyPaint)
        }

        pdf.finishPage(page)

        val outputDir = File(context.filesDir, "documents").apply { mkdirs() }
        val outputFile = File(outputDir, "${data.documentType.prefix}${data.documentId}.pdf")
        FileOutputStream(outputFile).use { pdf.writeTo(it) }
        pdf.close()
        return outputFile
    }
}
