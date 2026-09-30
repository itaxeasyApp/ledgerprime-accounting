package com.example.accounting.data.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.accounting.core.common.Money
import com.example.accounting.domain.rendering.TabularReportChrome
import com.example.accounting.domain.rendering.TabularReportData
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a [TabularReportData] to a PDF file using Android's built-in `android.graphics.pdf.PdfDocument`
 * (no third-party PDF library, matching [PdfDocumentRenderer]'s own reasoning) - a sibling to that
 * renderer, not a replacement: [PdfDocumentRenderer] stays the only renderer for
 * [com.example.accounting.domain.rendering.DocumentData] (Sale/Purchase/Note trade documents);
 * this one is the only renderer for tabular financial reports (Trial Balance/P&L/Balance
 * Sheet/Day Book/Voucher Registers). Paginates automatically when rows overflow one page - a real
 * Trial Balance/Day Book can run well past what fits on a single A4 page, unlike a single-invoice
 * document. A report that sets [TabularReportData.chrome] gets the branded header/footer layout
 * ([renderWithChrome]); every other report keeps the original plain layout unchanged.
 */
object TabularPdfRenderer {
    private const val A4_SHORT_SIDE = 595 // A4 at 72dpi
    private const val A4_LONG_SIDE = 842
    private const val MARGIN = 36f
    private const val ROW_HEIGHT = 16f
    private const val CELL_PADDING = 4f
    /** Beyond this many columns, a portrait page can't give each column enough width to stay
     * readable (Section 4: "portrait/landscape suitability") - landscape roughly doubles the
     * usable width for the same margins. Trial Balance (8 columns) and Day Book (7 columns) need
     * this; Profit & Loss/Balance Sheet (2-4 columns) stay portrait. */
    private const val LANDSCAPE_COLUMN_THRESHOLD = 5

    fun render(context: Context, data: TabularReportData): File {
        val landscape = data.columnHeaders.size >= LANDSCAPE_COLUMN_THRESHOLD
        val pageWidth = if (landscape) A4_LONG_SIDE else A4_SHORT_SIDE
        val pageHeight = if (landscape) A4_SHORT_SIDE else A4_LONG_SIDE

        val pdf = PdfDocument()
        data.chrome?.let { chrome ->
            renderWithChrome(pdf, data, chrome, pageWidth, pageHeight)
            return writeToCache(context, pdf, data.title)
        }

        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 14f; isFakeBoldText = true }
        val subtitlePaint = Paint().apply { color = Color.DKGRAY; textSize = 10f }
        val headerPaint = Paint().apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true }
        val cellPaint = Paint().apply { color = Color.BLACK; textSize = 9f }
        val totalsPaint = Paint().apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true }

        val columnCount = data.columnHeaders.size.coerceAtLeast(1)
        val usableWidth = pageWidth - 2 * MARGIN
        val colWidth = usableWidth / columnCount

        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        fun drawRow(values: List<String>, paint: Paint) {
            values.forEachIndexed { index, value ->
                canvas.drawText(fitText(value, paint, colWidth - CELL_PADDING), MARGIN + index * colWidth, y, paint)
            }
            y += ROW_HEIGHT
        }

        fun newPage() {
            pdf.finishPage(page)
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
            drawRow(data.columnHeaders, headerPaint)
            y += 4
        }

        canvas.drawText(data.title, MARGIN, y, titlePaint)
        y += 18f
        if (data.subtitle.isNotBlank()) {
            canvas.drawText(fitText(data.subtitle, subtitlePaint, usableWidth), MARGIN, y, subtitlePaint)
            y += 16f
        }
        y += 6f
        drawRow(data.columnHeaders, headerPaint)
        y += 4

        if (data.rows.isEmpty()) {
            canvas.drawText("No data for this period.", MARGIN, y, cellPaint)
            y += ROW_HEIGHT
        }
        for (row in data.rows) {
            if (y > pageHeight - MARGIN - ROW_HEIGHT) newPage()
            drawRow(row, cellPaint)
        }

        data.totalsRow?.let {
            if (y > pageHeight - MARGIN - ROW_HEIGHT) newPage()
            y += 4
            drawRow(it, totalsPaint)
        }

        pdf.finishPage(page)
        return writeToCache(context, pdf, data.title)
    }

    private fun writeToCache(context: Context, pdf: PdfDocument, title: String): File {
        val file = File(context.cacheDir, "${title.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    /** Truncates [value] with an ellipsis if it would overflow [maxWidth] - `Canvas.drawText`
     * never wraps or clips on its own, so a long ledger/account name would otherwise overlap
     * the next column's text (Section 4: "long ledger/account names"). */
    private fun fitText(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var truncated = value
        while (truncated.isNotEmpty() && paint.measureText("$truncated…") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated…"
    }

    // ---------------- Branded (chrome) layout ----------------

    private const val LOGO_BOX = 44f
    private const val FIRST_HEADER_HEIGHT = 84f
    private const val CONTINUATION_HEADER_HEIGHT = 30f
    private const val FOOTER_HEIGHT = 26f
    private const val COLUMN_HEADER_HEIGHT = 20f
    private const val TOTALS_GAP = 6f

    /** Row index ranges per page, planned before drawing so every footer can print "Page X of Y"
     * and every non-last page reserves room for its "Carried forward" line. */
    internal fun planPages(rowCount: Int, pageHeight: Int, hasCarryForward: Boolean): List<IntRange> {
        val bottom = pageHeight - MARGIN - FOOTER_HEIGHT
        // Room for the closing line (Carried forward on a full page, Totals on the last page).
        val reserve = ROW_HEIGHT + TOTALS_GAP
        val pages = mutableListOf<IntRange>()
        var index = 0
        do {
            val first = pages.isEmpty()
            var y = MARGIN + (if (first) FIRST_HEADER_HEIGHT else CONTINUATION_HEADER_HEIGHT) + COLUMN_HEADER_HEIGHT
            if (!first && hasCarryForward) y += ROW_HEIGHT
            val start = index
            while (index < rowCount && y + ROW_HEIGHT <= bottom - reserve) {
                y += ROW_HEIGHT
                index++
            }
            // A page too short for even one row would loop forever; always progress.
            if (index == start && index < rowCount) index++
            pages += start until index
        } while (index < rowCount)
        return pages
    }

    private fun renderWithChrome(pdf: PdfDocument, data: TabularReportData, chrome: TabularReportChrome, pageWidth: Int, pageHeight: Int) {
        val left = MARGIN
        val right = pageWidth - MARGIN
        val usableWidth = right - left

        val companyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 15f; isFakeBoldText = true }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 13f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        val metaRightPaint = Paint(metaPaint).apply { textAlign = Paint.Align.RIGHT }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 8f }
        val footerRightPaint = Paint(footerPaint).apply { textAlign = Paint.Align.RIGHT }
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.8f }
        val bandPaint = Paint().apply { color = Color.rgb(238, 238, 245) }

        val columnCount = data.columnHeaders.size.coerceAtLeast(1)
        val weights = chrome.columnWeights?.takeIf { it.size == columnCount } ?: List(columnCount) { 1f }
        val weightSum = weights.sum()
        val colWidths = weights.map { usableWidth * it / weightSum }
        val colStarts = colWidths.runningFold(left) { acc, w -> acc + w }

        val carryColumns = chrome.carryForwardColumnPaise.filterValues { it.size == data.rows.size }
        val hasCarryForward = carryColumns.isNotEmpty()
        val pages = planPages(data.rows.size, pageHeight, hasCarryForward)
        val logo = decodeLogo(chrome.logoPath)

        fun drawCells(canvas: Canvas, values: List<String>, paint: Paint, y: Float) {
            values.forEachIndexed { index, value ->
                if (index >= columnCount) return@forEachIndexed
                val text = fitText(value, paint, colWidths[index] - 2 * CELL_PADDING)
                if (index in chrome.rightAlignedColumns) {
                    val p = Paint(paint).apply { textAlign = Paint.Align.RIGHT }
                    canvas.drawText(text, colStarts[index] + colWidths[index] - CELL_PADDING, y, p)
                } else {
                    canvas.drawText(text, colStarts[index] + CELL_PADDING, y, paint)
                }
            }
        }

        /** "Brought/Carried forward" line: label in column 0, running totals in their columns. */
        fun drawCarryLine(canvas: Canvas, label: String, throughRowExclusive: Int, y: Float) {
            val values = MutableList(columnCount) { "" }
            values[0] = label
            carryColumns.forEach { (col, paise) ->
                if (col in 0 until columnCount) values[col] = Money.fromPaise(paise.take(throughRowExclusive).sum()).formatPlain()
            }
            drawCells(canvas, values, boldPaint, y)
        }

        pages.forEachIndexed { pageIndex, range ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
            val canvas = page.canvas
            var y = MARGIN

            if (pageIndex == 0) {
                // Logo (if any) upper-left, company identity beside it, report identity right.
                var textLeft = left
                if (logo != null) {
                    canvas.drawBitmap(logo, left, y, null)
                    textLeft = left + LOGO_BOX + 10f
                }
                val identityWidth = usableWidth * 0.55f - (textLeft - left)
                canvas.drawText(fitText(chrome.companyName, companyPaint, identityWidth), textLeft, y + 14f, companyPaint)
                if (!chrome.gstin.isNullOrBlank()) canvas.drawText("GSTIN: ${chrome.gstin}", textLeft, y + 28f, metaPaint)
                canvas.drawText(data.title, right, y + 14f, titlePaint)
                canvas.drawText(chrome.financialYearLabel, right, y + 28f, metaRightPaint)
                if (chrome.periodLabel.isNotBlank()) canvas.drawText("Period: ${chrome.periodLabel}", right, y + 40f, metaRightPaint)
                y += maxOf(LOGO_BOX, 44f) + 6f
                canvas.drawLine(left, y, right, y, linePaint)
                y += 14f
                if (data.subtitle.isNotBlank()) canvas.drawText(fitText(data.subtitle, metaPaint, usableWidth), left, y, metaPaint)
                y = MARGIN + FIRST_HEADER_HEIGHT
            } else {
                canvas.drawText(fitText("${chrome.companyName} - ${data.title} (continued)", boldPaint, usableWidth), left, y + 10f, boldPaint)
                y += 16f
                canvas.drawLine(left, y, right, y, linePaint)
                y = MARGIN + CONTINUATION_HEADER_HEIGHT
            }

            // Column header band, repeated on every page.
            canvas.drawRect(left, y, right, y + COLUMN_HEADER_HEIGHT - 4f, bandPaint)
            drawCells(canvas, data.columnHeaders, headerPaint, y + 11f)
            y += COLUMN_HEADER_HEIGHT

            if (pageIndex > 0 && hasCarryForward) {
                y += ROW_HEIGHT
                drawCarryLine(canvas, "Brought forward", range.first, y - 4f)
            }

            if (data.rows.isEmpty()) {
                y += ROW_HEIGHT
                canvas.drawText("No data for this period.", left + CELL_PADDING, y - 4f, cellPaint)
            }
            for (rowIndex in range) {
                y += ROW_HEIGHT
                drawCells(canvas, data.rows[rowIndex], cellPaint, y - 4f)
            }

            val isLastPage = pageIndex == pages.lastIndex
            if (!isLastPage && hasCarryForward) {
                y += TOTALS_GAP
                canvas.drawLine(left, y - 2f, right, y - 2f, linePaint)
                y += ROW_HEIGHT
                drawCarryLine(canvas, "Carried forward", range.last + 1, y - 4f)
            }
            if (isLastPage) {
                data.totalsRow?.let { totals ->
                    y += TOTALS_GAP
                    canvas.drawLine(left, y - 2f, right, y - 2f, linePaint)
                    y += ROW_HEIGHT
                    drawCells(canvas, totals, boldPaint, y - 4f)
                    canvas.drawLine(left, y, right, y, linePaint)
                }
            }

            // Footer on every page.
            val footerTop = pageHeight - MARGIN - FOOTER_HEIGHT + 8f
            canvas.drawLine(left, footerTop, right, footerTop, linePaint)
            val generated = if (chrome.generatedOn.isBlank()) "Generated by LedgerPrime" else "Generated by LedgerPrime on ${chrome.generatedOn}"
            canvas.drawText(fitText("$generated  |  ${chrome.companyName}", footerPaint, usableWidth * 0.7f), left, footerTop + 12f, footerPaint)
            canvas.drawText("Page ${pageIndex + 1} of ${pages.size}", right, footerTop + 12f, footerRightPaint)

            pdf.finishPage(page)
        }
    }

    private fun decodeLogo(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val bitmap = try {
            BitmapFactory.decodeFile(File(path).absolutePath)
        } catch (e: Exception) {
            null
        } ?: return null
        val scale = minOf(LOGO_BOX / bitmap.width, LOGO_BOX / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
