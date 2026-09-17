package com.example.accounting.data.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.accounting.domain.rendering.TabularReportData
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a [TabularReportData] to a PDF file using Android's built-in `android.graphics.pdf.PdfDocument`
 * (no third-party PDF library, matching [PdfDocumentRenderer]'s own reasoning) - a sibling to that
 * renderer, not a replacement: [PdfDocumentRenderer] stays the only renderer for
 * [com.example.accounting.domain.rendering.DocumentData] (Sale/Purchase/Note trade documents);
 * this one is the only renderer for tabular financial reports (Trial Balance/P&L/Balance
 * Sheet/Day Book). Paginates automatically when rows overflow one page - a real Trial
 * Balance/Day Book can run well past what fits on a single A4 page, unlike a single-invoice
 * document.
 *
 * Week 2 (Play Store update plan, "pdf excel csv in a proper professional manner") - was plain
 * black-on-white text with no branding, no header separator, and a page number tracked internally
 * but never actually drawn anywhere on the page. Brought up to the same visual bar
 * [PdfDocumentRenderer] already has for invoices: the LedgerPrime brand mark, a tinted header band,
 * alternating row shading for readability, a ruled-off totals row, and a real page-number footer.
 */
object TabularPdfRenderer {
    private const val A4_SHORT_SIDE = 595 // A4 at 72dpi
    private const val A4_LONG_SIDE = 842
    private const val MARGIN = 36f
    private const val ROW_HEIGHT = 16f
    private const val CELL_PADDING = 4f
    private const val BRAND_MARK_POINTS = 20f
    private const val FOOTER_HEIGHT = 20f
    /** Beyond this many columns, a portrait page can't give each column enough width to stay
     * readable (Section 4: "portrait/landscape suitability") - landscape roughly doubles the
     * usable width for the same margins. Trial Balance (8 columns) and Day Book (7 columns) need
     * this; Profit & Loss/Balance Sheet (2-4 columns) stay portrait. */
    private const val LANDSCAPE_COLUMN_THRESHOLD = 5

    // Matches the app's own Royal Purple theme (com.example.ui.theme.Color.kt) - hardcoded here
    // rather than imported, same layering reason PdfDocumentRenderer reads raw hex color strings
    // from TemplateVisualConfig instead of a presentation-layer theme reference (data/ never
    // depends on presentation/).
    private const val COLOR_HEADER_FILL = 0xFFEDE9FE.toInt() // RoyalPurpleContainer
    private const val COLOR_HEADER_TEXT = 0xFF2E1065.toInt() // RoyalPurpleOnContainer
    private const val COLOR_ROW_ALT = 0xFFF7F5FB.toInt() // faint lavender-tinted off-white
    private const val COLOR_BORDER = 0xFFC9C2D6.toInt() // PurpleGrayOutline
    private const val COLOR_TOTALS_FILL = 0xFFEDE9FE.toInt()
    private const val COLOR_ACCENT = 0xFF4C1D95.toInt() // RoyalPurple

    fun render(context: Context, data: TabularReportData): File {
        val landscape = data.columnHeaders.size >= LANDSCAPE_COLUMN_THRESHOLD
        val pageWidth = if (landscape) A4_LONG_SIDE else A4_SHORT_SIDE
        val pageHeight = if (landscape) A4_SHORT_SIDE else A4_LONG_SIDE
        val bottomLimit = pageHeight - MARGIN - FOOTER_HEIGHT

        val pdf = PdfDocument()
        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 14f; isFakeBoldText = true }
        val subtitlePaint = Paint().apply { color = Color.DKGRAY; textSize = 10f }
        val headerPaint = Paint().apply { color = COLOR_HEADER_TEXT; textSize = 9f; isFakeBoldText = true }
        val cellPaint = Paint().apply { color = Color.BLACK; textSize = 9f }
        val totalsPaint = Paint().apply { color = COLOR_ACCENT; textSize = 9f; isFakeBoldText = true }
        val borderPaint = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.75f }
        val fillPaint = Paint()
        val footerPaint = Paint().apply { color = Color.GRAY; textSize = 7.5f }
        val brandCaptionPaint = Paint().apply { color = Color.DKGRAY; textSize = 8f }

        val columnCount = data.columnHeaders.size.coerceAtLeast(1)
        val usableWidth = pageWidth - 2 * MARGIN
        val colWidth = usableWidth / columnCount
        val brandMark = loadBrandMark(context)

        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN
        var rowIndex = 0

        /** Truncates [value] with an ellipsis if it would overflow [maxWidth] - `Canvas.drawText`
         * never wraps or clips on its own, so a long ledger/account name would otherwise overlap
         * the next column's text (Section 4: "long ledger/account names"). */
        fun fitText(value: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(value) <= maxWidth) return value
            var truncated = value
            while (truncated.isNotEmpty() && paint.measureText("$truncated…") > maxWidth) {
                truncated = truncated.dropLast(1)
            }
            return "$truncated…"
        }

        fun drawFooter() {
            canvas.drawLine(MARGIN, pageHeight - FOOTER_HEIGHT, pageWidth - MARGIN, pageHeight - FOOTER_HEIGHT, borderPaint)
            val generated = "Generated by LedgerPrime - ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"
            canvas.drawText(generated, MARGIN, pageHeight - FOOTER_HEIGHT + 12f, footerPaint)
            val pageLabel = "Page $pageNumber"
            canvas.drawText(pageLabel, pageWidth - MARGIN - footerPaint.measureText(pageLabel), pageHeight - FOOTER_HEIGHT + 12f, footerPaint)
        }

        fun drawRow(values: List<String>, paint: Paint, shaded: Boolean, isTotals: Boolean = false) {
            if (shaded || isTotals) {
                fillPaint.color = if (isTotals) COLOR_TOTALS_FILL else COLOR_ROW_ALT
                canvas.drawRect(MARGIN, y - ROW_HEIGHT + 4f, pageWidth - MARGIN, y + 4f, fillPaint)
            }
            values.forEachIndexed { index, value ->
                val fitted = fitText(value, paint, colWidth - CELL_PADDING)
                val x = if (index in data.rightAlignColumnIndices) {
                    MARGIN + (index + 1) * colWidth - CELL_PADDING - paint.measureText(fitted)
                } else {
                    MARGIN + index * colWidth
                }
                canvas.drawText(fitted, x, y, paint)
            }
            if (isTotals) canvas.drawLine(MARGIN, y - ROW_HEIGHT + 4f, pageWidth - MARGIN, y - ROW_HEIGHT + 4f, borderPaint)
            y += ROW_HEIGHT
        }

        /** Multi-page Ledger printing (Week 3) - builds a "Carried Over"/"Brought Forward" row for
         * [data.pageBreakCarryForward], sized to the real column count so it draws through the same
         * [drawRow] every other row uses. `asOfRowIndex` is the last data-row index actually printed
         * before the break - its own Balance cell is what B/F and C/O both restate, and its matching
         * entry in `cumulativeDebitFormatted`/`cumulativeCreditFormatted` is what the two Debit/Credit
         * cells restate, so a Brought Forward on the next page always exactly matches the Carried
         * Over that preceded it. */
        fun buildCarryForwardRow(spec: com.example.accounting.domain.rendering.PageBreakCarryForward, label: String, asOfRowIndex: Int): List<String> {
            val cells = MutableList(columnCount) { "" }
            cells[spec.particularsColumnIndex] = label
            cells[spec.debitColumnIndex] = spec.cumulativeDebitFormatted[asOfRowIndex]
            cells[spec.creditColumnIndex] = spec.cumulativeCreditFormatted[asOfRowIndex]
            cells[spec.balanceColumnIndex] = data.rows[asOfRowIndex][spec.balanceColumnIndex]
            return cells
        }

        fun drawColumnHeader() {
            fillPaint.color = COLOR_HEADER_FILL
            canvas.drawRect(MARGIN, y - ROW_HEIGHT + 4f, pageWidth - MARGIN, y + 5f, fillPaint)
            drawRow(data.columnHeaders, headerPaint, shaded = false)
            canvas.drawLine(MARGIN, y + 1f, pageWidth - MARGIN, y + 1f, borderPaint.apply { strokeWidth = 1.2f })
            borderPaint.strokeWidth = 0.75f
            y += 5f
        }

        fun newPage() {
            drawFooter()
            pdf.finishPage(page)
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
            drawColumnHeader()
        }

        // ---- Brand header: LedgerPrime mark + report title/subtitle ----
        if (brandMark != null) {
            canvas.drawBitmap(brandMark, MARGIN, y - BRAND_MARK_POINTS + 4f, null)
            canvas.drawText(data.title, MARGIN + BRAND_MARK_POINTS + 8f, y, titlePaint)
        } else {
            canvas.drawText(data.title, MARGIN, y, titlePaint)
        }
        val brandCaption = "LedgerPrime"
        canvas.drawText(brandCaption, pageWidth - MARGIN - brandCaptionPaint.measureText(brandCaption), y, brandCaptionPaint)
        y += 18f
        if (data.subtitle.isNotBlank()) {
            canvas.drawText(fitText(data.subtitle, subtitlePaint, usableWidth), MARGIN, y, subtitlePaint)
            y += 16f
        }
        y += 6f
        drawColumnHeader()

        if (data.rows.isEmpty()) {
            canvas.drawText("No data for this period.", MARGIN, y, cellPaint)
            y += ROW_HEIGHT
        }
        val carryForward = data.pageBreakCarryForward
        // When a carry-forward spec is present, a row is only drawn while at least 2 row-heights
        // of space remain - one for the row itself, one held in reserve so the "Carried Over" row
        // this same page break needs is always guaranteed to fit above the footer, never spilling
        // into it. This costs at most one row of density on the last page of density per sheet.
        val reserve = if (carryForward != null) ROW_HEIGHT else 0f
        data.rows.forEachIndexed { index, row ->
            if (y > bottomLimit - ROW_HEIGHT - reserve) {
                if (carryForward != null && index > 0) {
                    drawRow(buildCarryForwardRow(carryForward, "Carried Over (C/O)", index - 1), totalsPaint, shaded = false, isTotals = true)
                    newPage()
                    drawRow(buildCarryForwardRow(carryForward, "Brought Forward (B/F)", index - 1), totalsPaint, shaded = false, isTotals = true)
                } else {
                    newPage()
                }
            }
            drawRow(row, cellPaint, shaded = rowIndex % 2 == 1)
            rowIndex += 1
        }

        data.totalsRow?.let {
            if (y > bottomLimit - ROW_HEIGHT) newPage()
            y += 4
            drawRow(it, totalsPaint, shaded = false, isTotals = true)
        }

        drawFooter()
        pdf.finishPage(page)

        val file = File(context.cacheDir, "${data.title.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    /** The LedgerPrime product logo, rasterized small for the report header - same asset/approach
     * [PdfDocumentRenderer.loadBrandMark] uses for invoices, duplicated here (not extracted to a
     * shared helper) since it's a 10-line, single-call, no-state utility - matches this codebase's
     * own established "small controlled duplication over a premature shared abstraction" pattern. */
    private fun loadBrandMark(context: Context): Bitmap? = try {
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, com.example.R.drawable.ic_ledgerprime_brandmark)
        drawable?.let {
            val sizePx = BRAND_MARK_POINTS.toInt()
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            it.setBounds(0, 0, sizePx, sizePx)
            it.draw(canvas)
            bitmap
        }
    } catch (e: Exception) {
        null
    }
}
