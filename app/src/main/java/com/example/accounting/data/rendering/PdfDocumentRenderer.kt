package com.example.accounting.data.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.accounting.core.common.Money
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.DocumentLineData
import com.example.accounting.domain.rendering.DocumentPartySnapshot
import com.example.accounting.domain.rendering.DocumentTemplate
import com.example.accounting.domain.rendering.IndianCurrencyWords
import com.example.accounting.domain.rendering.InvoiceSummaryCalculator
import com.example.accounting.domain.rendering.InvoiceTemplateStyle
import com.example.accounting.domain.rendering.LogoPosition
import com.example.accounting.domain.rendering.TaxColumnMode
import com.example.accounting.domain.rendering.TemplateVisualConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Renders [DocumentData] + [DocumentTemplate] to a professional, single-invoice-shaped PDF using
 * Android's built-in `android.graphics.pdf.PdfDocument` (no third-party PDF library). "5 Invoice
 * PDF Templates" task - upgraded from the original bare text-line dump into a real header/Bill-To/
 * item-table/totals/signature layout, with 5 structurally different presets
 * ([com.example.accounting.domain.rendering.InvoiceTemplatePresets]) selected via
 * [TemplateVisualConfig.style]. Every number drawn is read straight off [DocumentData]/
 * [InvoiceSummaryCalculator] (itself pure summation of already-computed figures) - this class
 * performs no GST/discount/rounding calculation of any kind, matching [DocumentData]'s own
 * "single source of truth" contract.
 *
 * KNOWN TEST-ENVIRONMENT LIMITATION (see `docs/44_PDF_PRINT_SHARE.md`): `android.graphics.pdf.*` is
 * framework code with no real behavior under a plain JVM unit test (and this project's Robolectric
 * setup is already broken by an unrelated environment issue). This class is therefore verified by
 * running it on a real device (see the `run-app` skill), not by a JVM test; every piece of "what
 * number/column to show" logic it depends on ([InvoiceSummaryCalculator], [com.example.accounting.domain.rendering.TaxColumnSelector],
 * [IndianCurrencyWords], [com.example.accounting.domain.rendering.InvoiceTemplatePresets]) is pure
 * and fully unit-tested in `InvoiceTemplateTestSuite`.
 */
object PdfDocumentRenderer {
    private const val PAGE_WIDTH_POINTS = 595 // A4 at 72dpi
    private const val PAGE_HEIGHT_POINTS = 842
    private const val LOGO_BOX_POINTS = 60f
    private const val SIGNATURE_BOX_POINTS = 50f
    private const val BRAND_MARK_POINTS = 26f

    fun render(context: Context, data: DocumentData, template: DocumentTemplate): File {
        val config = template.visualConfig
        val summary = InvoiceSummaryCalculator.from(data)
        val paints = Paints(config)

        var pdf = PdfDocument()
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_POINTS, PAGE_HEIGHT_POINTS, 1).create()
        var page = pdf.startPage(pageInfo)
        var canvas = page.canvas
        val left = config.layout.marginPointsLeft.toFloat()
        val right = PAGE_WIDTH_POINTS - config.layout.marginPointsRight.toFloat()
        val bottomLimit = PAGE_HEIGHT_POINTS - config.layout.marginPointsBottom.toFloat()
        var pageNumber = 1
        var y = 0f

        fun newPage() {
            pdf.finishPage(page)
            pageNumber += 1
            pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_POINTS, PAGE_HEIGHT_POINTS, pageNumber).create()
            page = pdf.startPage(pageInfo)
            canvas = page.canvas
            y = config.layout.marginPointsTop.toFloat()
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > bottomLimit) newPage()
        }

        y = config.layout.marginPointsTop.toFloat()

        // ---- Header (per-style structure) ----
        val logoBitmap = if (config.layout.showLogo) decodeAsset(data.branding.logoStorageReference) else null
        y = when (config.style) {
            InvoiceTemplateStyle.MODERN_BANNER -> drawBannerHeader(canvas, config, paints, data, logoBitmap, left, right, y)
            InvoiceTemplateStyle.ELEGANT_SERIF -> drawCenteredHeader(canvas, config, paints, data, logoBitmap, left, right, y)
            else -> drawPlainHeader(canvas, config, paints, data, logoBitmap, left, right, y)
        }

        // ---- LedgerPrime product brand mark + "Tax Invoice" title (user request, docs/
        // CORRECTIONS_LOG.md: "make a logo for this project and implant it in this project on all
        // view preview print share files ... in the middle the ledger prime logo for branding ...
        // after header in the middle 'Tax Invoice'"). This is the PRODUCT's own brand (the app that
        // generated the document), never the user's business branding - that's [data.seller] above,
        // already drawn upper-right by [drawPlainHeader]/shown per-style elsewhere. Never drawn for
        // the on-screen Compose preview (`InvoicePreviewScreen`) or the JSON export, only PDF/Print/
        // Share, per the explicit "and not json file" instruction.
        loadBrandMark(context)?.let { mark ->
            val markX = (left + right) / 2 - BRAND_MARK_POINTS / 2
            canvas.drawBitmap(scaleBitmap(mark, BRAND_MARK_POINTS, BRAND_MARK_POINTS), markX, y, null)
        }
        y += BRAND_MARK_POINTS + 2f
        val brandCaption = "LedgerPrime"
        canvas.drawText(brandCaption, (left + right) / 2 - paints.small.measureText(brandCaption) / 2, y + paints.small.textSize, paints.small)
        y += paints.small.textSize + 8f

        val titleText = if (data.documentType == com.example.accounting.domain.document.DocumentType.SALES_INVOICE ||
            data.documentType == com.example.accounting.domain.document.DocumentType.PURCHASE_BILL
        ) "TAX INVOICE" else data.documentType.name.replace('_', ' ')
        canvas.drawText(titleText, (left + right) / 2 - paints.heading.measureText(titleText) / 2, y + paints.headingSize, paints.heading)
        y += paints.headingSize + 8f

        // Ship/Dispatch dates - real extension points on [DocumentData] (see its own KDoc: no
        // underlying Invoice/TradeDocument field feeds them yet, so they stay null until a future
        // phase adds one) - shown only when actually present, never a blank/placeholder date.
        if (data.shipDate != null || data.dispatchDate != null) {
            val parts = mutableListOf<String>()
            data.shipDate?.let { parts += "Shipped On: $it" }
            data.dispatchDate?.let { parts += "Dispatched On: $it" }
            val shipLine = parts.joinToString("   ")
            canvas.drawText(shipLine, left, y + paints.baseSize, paints.small)
            y += paints.baseSize + 8f
        }
        y += 4f

        // ---- Bill To ----
        canvas.drawText("Bill To:", left, y, paints.label)
        y += paints.baseSize + 2
        y = drawPartyBlock(canvas, paints, data.buyer, left, y)
        y += 10f

        // ---- Item table ----
        val taxMode = summary.taxColumnMode
        val columns = buildColumns(taxMode)
        y = drawTableHeader(canvas, paints, config, columns, left, right, y)

        data.items.forEachIndexed { index, line ->
            ensureSpace(paints.baseSize + 10)
            if (y <= config.layout.marginPointsTop.toFloat() + 1f) {
                // We just started a fresh page inside ensureSpace - redraw the table header there.
                y = drawTableHeader(canvas, paints, config, columns, left, right, y)
            }
            y = drawTableRow(canvas, paints, config, columns, line, index + 1, left, right, y, taxMode)
        }
        y += 6f
        if (config.style == InvoiceTemplateStyle.BOXED_GRID) {
            canvas.drawLine(left, y, right, y, paints.border)
            y += 8f
        }

        // ---- Totals block ----
        ensureSpace(160f)
        y = drawTotalsBlock(canvas, paints, summary, taxMode, left, right, y)
        y += 10f

        // ---- Amount in words ----
        ensureSpace(paints.baseSize * 2 + 10)
        canvas.drawText("Amount in Words:", left, y, paints.label)
        y += paints.baseSize + 2
        canvas.drawText(summary.amountInWords, left, y, paints.body)
        y += paints.baseSize + 12

        // ---- Bank details ----
        if (config.layout.showBankDetails && data.paymentInformation.bankName.isNotBlank()) {
            ensureSpace(paints.baseSize * 4)
            canvas.drawText("Bank Details", left, y, paints.label)
            y += paints.baseSize + 4
            canvas.drawText("Bank: ${data.paymentInformation.bankName}  A/c: ${data.paymentInformation.bankAccountNumber}", left, y, paints.body)
            y += paints.baseSize + 4
            if (data.paymentInformation.bankIfsc.isNotBlank()) {
                canvas.drawText("IFSC: ${data.paymentInformation.bankIfsc}  Branch: ${data.paymentInformation.bankBranch}", left, y, paints.body)
                y += paints.baseSize + 4
            }
            y += 8f
        }

        // ---- Scan to Pay (UPI) - auto-generated from the business's own UPI ID, real
        // documents.rendering.md correction: previously never drawn even when the profile had a
        // UPI ID on file (only qrCodeStorageReference - a manually-uploaded image - was ever
        // checked, so this section was always blank in practice). Never a placeholder/fake QR -
        // encodes a real `upi://pay` deep link any UPI app can scan and pre-fill from, including
        // this specific invoice's own real grand total.
        if (data.paymentInformation.upiId.isNotBlank()) {
            ensureSpace(90f)
            val qrBitmap = generateUpiPaymentQrBitmap(
                payeeName = data.seller.name, upiId = data.paymentInformation.upiId,
                amount = summary.grandTotal, documentNumber = data.documentNumber
            )
            if (qrBitmap != null) {
                canvas.drawText("Scan to Pay (UPI)", left, y, paints.label)
                y += paints.baseSize + 4
                val qrSize = 70f
                canvas.drawBitmap(qrBitmap, null, android.graphics.RectF(left, y, left + qrSize, y + qrSize), null)
                canvas.drawText(data.paymentInformation.upiId, left + qrSize + 10f, y + qrSize / 2, paints.body)
                y += qrSize + 10f
            }
        }

        // ---- Signature ----
        if (config.layout.showSignature) {
            ensureSpace(SIGNATURE_BOX_POINTS + paints.baseSize * 2 + 20)
            val sigBitmap = decodeAsset(data.branding.signatureStorageReference)
            val sigLeft = right - SIGNATURE_BOX_POINTS * 2
            if (sigBitmap != null) {
                canvas.drawBitmap(scaleBitmap(sigBitmap, SIGNATURE_BOX_POINTS * 2, SIGNATURE_BOX_POINTS), sigLeft, y, null)
            }
            y += SIGNATURE_BOX_POINTS + 4
            canvas.drawLine(sigLeft, y, right, y, paints.border)
            y += paints.baseSize
            if (data.branding.signatoryName.isNotBlank()) {
                canvas.drawText(data.branding.signatoryName, sigLeft, y, paints.small)
                y += paints.baseSize
            }
            canvas.drawText("Authorised Signatory", sigLeft, y, paints.small)
            y += paints.baseSize + 10
        }

        // ---- Terms ----
        if (config.layout.showTermsAndConditions && data.terms.isNotBlank()) {
            ensureSpace(paints.baseSize * 2)
            canvas.drawText("Terms & Conditions:", left, y, paints.small)
            y += paints.baseSize + 2
            canvas.drawText(data.terms, left, y, paints.small)
            y += paints.baseSize + 4
        }

        // ---- Jurisdiction ----  ("...and in the last term and condition jurisdiction" - user
        // request). Sourced from the seller's own state (already resolved on every [DocumentData]
        // for CGST/SGST-vs-IGST classification) - never a fabricated city, since no separate
        // "jurisdiction city" field exists on [DocumentPartySnapshot] yet.
        if (data.seller.stateName.isNotBlank()) {
            ensureSpace(paints.baseSize + 4)
            canvas.drawText("Subject to ${data.seller.stateName} Jurisdiction Only.", left, y, paints.small)
        }

        pdf.finishPage(page)

        val outputDir = File(context.filesDir, "documents").apply { mkdirs() }
        val outputFile = File(outputDir, "${data.documentType.prefix}${data.documentId}.pdf")
        FileOutputStream(outputFile).use { pdf.writeTo(it) }
        pdf.close()
        return outputFile
    }

    // ---------------- Style-specific headers ----------------

    private fun drawPlainHeader(
        canvas: android.graphics.Canvas, config: TemplateVisualConfig, paints: Paints, data: DocumentData,
        logo: Bitmap?, left: Float, right: Float, startY: Float
    ): Float {
        // User request (docs/CORRECTIONS_LOG.md): the user's own business branding (name/GSTIN)
        // sits upper-RIGHT of the header - the LedgerPrime product mark drawn just below this
        // function's return value takes the center, and the seller's own uploaded logo image (a
        // different thing - their literal logo file, not their name) stays upper-left where it was.
        var y = startY
        if (logo != null && config.layout.logoPosition == LogoPosition.TOP_LEFT) {
            canvas.drawBitmap(scaleBitmap(logo, LOGO_BOX_POINTS, LOGO_BOX_POINTS), left, y, null)
        }
        fun rightText(text: String, paint: Paint, textY: Float) = canvas.drawText(text, right - paint.measureText(text), textY, paint)
        rightText(data.seller.name, paints.heading, y + paints.headingSize)
        y += paints.headingSize + 6
        stateLabel(data.seller)?.let { rightText(it, paints.body, y + paints.baseSize); y += paints.baseSize + 4 }
        if (data.seller.gstin.isNotBlank()) { rightText("GSTIN: ${data.seller.gstin}", paints.body, y + paints.baseSize); y += paints.baseSize + 4 }
        y = maxOf(y, if (logo != null) startY + LOGO_BOX_POINTS else y)
        y += 6f
        canvas.drawLine(left, y, right, y, paints.border)
        y += 10f
        canvas.drawText("No: ${data.documentNumber}   Date: ${data.documentDate}", left, y + paints.baseSize, paints.body)
        y += paints.baseSize + 10
        return y
    }

    private fun drawBannerHeader(
        canvas: android.graphics.Canvas, config: TemplateVisualConfig, paints: Paints, data: DocumentData,
        logo: Bitmap?, left: Float, right: Float, startY: Float
    ): Float {
        val bannerHeight = 70f
        val rect = android.graphics.RectF(0f, 0f, PAGE_WIDTH_POINTS.toFloat(), bannerHeight)
        canvas.drawRect(rect, paints.bannerFill)
        canvas.drawText(data.seller.name, left, 30f, paints.headingOnBanner)
        stateLabel(data.seller)?.let { canvas.drawText(it, left, 48f, paints.bodyOnBanner) }
        if (data.seller.gstin.isNotBlank()) canvas.drawText("GSTIN: ${data.seller.gstin}", left, 64f, paints.bodyOnBanner)
        canvas.drawText("No: ${data.documentNumber}", right - 200, 40f, paints.bodyOnBanner)
        canvas.drawText("Date: ${data.documentDate}", right - 200, 56f, paints.bodyOnBanner)
        if (logo != null) {
            val logoY = (bannerHeight - LOGO_BOX_POINTS) / 2
            val logoX = if (config.layout.logoPosition == LogoPosition.TOP_RIGHT) right - LOGO_BOX_POINTS - 210 else left
            canvas.drawBitmap(scaleBitmap(logo, LOGO_BOX_POINTS, LOGO_BOX_POINTS), logoX, logoY, null)
        }
        return bannerHeight + 16f
    }

    private fun drawCenteredHeader(
        canvas: android.graphics.Canvas, config: TemplateVisualConfig, paints: Paints, data: DocumentData,
        logo: Bitmap?, left: Float, right: Float, startY: Float
    ): Float {
        var y = startY
        val centerX = (left + right) / 2
        if (logo != null) {
            canvas.drawBitmap(scaleBitmap(logo, LOGO_BOX_POINTS, LOGO_BOX_POINTS), centerX - LOGO_BOX_POINTS / 2, y, null)
            y += LOGO_BOX_POINTS + 4
        }
        canvas.drawText(data.seller.name, centerX - paints.heading.measureText(data.seller.name) / 2, y + paints.headingSize, paints.heading)
        y += paints.headingSize + 6
        stateLabel(data.seller)?.let {
            canvas.drawText(it, centerX - paints.body.measureText(it) / 2, y + paints.baseSize, paints.body); y += paints.baseSize + 4
        }
        if (data.seller.gstin.isNotBlank()) {
            val text = "GSTIN: ${data.seller.gstin}"
            canvas.drawText(text, centerX - paints.body.measureText(text) / 2, y + paints.baseSize, paints.body)
            y += paints.baseSize + 4
        }
        y += 4f
        canvas.drawLine(left, y, right, y, paints.accentLine)
        y += 12f
        val docLine = "No: ${data.documentNumber}  |  Date: ${data.documentDate}"
        canvas.drawText(docLine, centerX - paints.body.measureText(docLine) / 2, y + paints.baseSize, paints.label)
        y += paints.baseSize + 10
        return y
    }

    private fun drawPartyBlock(canvas: android.graphics.Canvas, paints: Paints, party: DocumentPartySnapshot, left: Float, startY: Float): Float {
        var y = startY
        canvas.drawText(party.name, left, y + paints.baseSize, paints.bodyBold)
        y += paints.baseSize + 4
        if (party.address.isNotBlank()) { canvas.drawText(party.address, left, y + paints.baseSize, paints.body); y += paints.baseSize + 4 }
        if (party.gstin.isNotBlank()) { canvas.drawText("GSTIN: ${party.gstin}", left, y + paints.baseSize, paints.body); y += paints.baseSize + 4 }
        stateLabel(party)?.let { canvas.drawText(it, left, y + paints.baseSize, paints.body); y += paints.baseSize + 4 }
        return y
    }

    private fun stateLabel(party: DocumentPartySnapshot): String? =
        if (party.stateCode.isBlank()) null else "State: ${party.stateCode} - ${party.stateName.ifBlank { "Unknown" }}"

    // ---------------- Item table ----------------

    private data class Column(val label: String, val weight: Float, val extractor: (DocumentLineData) -> String)

    private fun buildColumns(taxMode: TaxColumnMode): List<Column> {
        val base = mutableListOf(
            Column("#", 0.4f) { "" },
            Column("Description", 2.2f) { it.description },
            Column("HSN", 0.8f) { it.hsnSacCode },
            Column("Qty", 0.7f) { it.quantity?.let { q -> "%.2f".format(q.rawValue / 1000.0) } ?: "-" },
            Column("Rate", 0.9f) { it.rate.formatPlain() },
            Column("Disc", 0.8f) { if (it.discount.isPositive) it.discount.formatPlain() else "-" },
            Column("Taxable", 1.0f) { it.taxableAmount.formatPlain() },
            Column("GST%", 0.6f) { "${it.gstRatePercent}" }
        )
        when (taxMode) {
            TaxColumnMode.CGST_SGST -> {
                base += Column("CGST", 0.8f) { it.cgst.formatPlain() }
                base += Column("SGST", 0.8f) { it.sgst.formatPlain() }
            }
            TaxColumnMode.IGST -> base += Column("IGST", 0.9f) { it.igst.formatPlain() }
            TaxColumnMode.NONE -> {}
        }
        base += Column("Amount", 1.0f) { it.lineTotal.formatPlain() }
        return base
    }

    private fun columnX(columns: List<Column>, left: Float, right: Float): List<Pair<Float, Float>> {
        val totalWeight = columns.sumOf { it.weight.toDouble() }.toFloat()
        val available = right - left
        var cursor = left
        return columns.map { col ->
            val width = available * (col.weight / totalWeight)
            val start = cursor
            cursor += width
            start to width
        }
    }

    private fun drawTableHeader(
        canvas: android.graphics.Canvas, paints: Paints, config: TemplateVisualConfig, columns: List<Column>, left: Float, right: Float, startY: Float
    ): Float {
        val y = startY
        val xs = columnX(columns, left, right)
        if (config.style == InvoiceTemplateStyle.BOXED_GRID) {
            canvas.drawRect(left, y, right, y + paints.baseSize + 8, paints.headerFill)
        }
        // Real-device QA fix - Modern Banner's `heading` color is white (correct for the
        // text drawn on its dark banner block), but this table header sits on the plain
        // white page body further down, so reusing that same color made the column labels
        // invisible (white-on-white). `label` (colors.secondary) is dark for every preset
        // and was already used elsewhere against a white background, so it's a safe swap
        // scoped to just this one style.
        val headerTextPaint = if (config.style == InvoiceTemplateStyle.MODERN_BANNER) paints.label else paints.tableHeader
        columns.forEachIndexed { i, col -> canvas.drawText(col.label, xs[i].first + 2, y + paints.baseSize + 2, headerTextPaint) }
        val bottom = y + paints.baseSize + 8
        canvas.drawLine(left, bottom, right, bottom, paints.border)
        if (config.style == InvoiceTemplateStyle.BOXED_GRID) {
            xs.forEach { (x, _) -> canvas.drawLine(x, y, x, bottom, paints.border) }
            canvas.drawLine(right, y, right, bottom, paints.border)
        }
        return bottom + 4f
    }

    private fun drawTableRow(
        canvas: android.graphics.Canvas, paints: Paints, config: TemplateVisualConfig, columns: List<Column>,
        line: DocumentLineData, srNo: Int, left: Float, right: Float, startY: Float, taxMode: TaxColumnMode
    ): Float {
        val xs = columnX(columns, left, right)
        val rowHeight = paints.baseSize + 8
        columns.forEachIndexed { i, col ->
            val text = if (i == 0) srNo.toString() else col.extractor(line)
            canvas.drawText(text, xs[i].first + 2, startY + paints.baseSize, paints.body)
        }
        val bottom = startY + rowHeight
        if (config.style == InvoiceTemplateStyle.BOXED_GRID) {
            xs.forEach { (x, _) -> canvas.drawLine(x, startY - 4, x, bottom, paints.border) }
            canvas.drawLine(right, startY - 4, right, bottom, paints.border)
            canvas.drawLine(left, bottom, right, bottom, paints.border)
        } else {
            canvas.drawLine(left, bottom, right, bottom, paints.hairline)
        }
        return bottom + 4f
    }

    // ---------------- Totals ----------------

    private fun drawTotalsBlock(
        canvas: android.graphics.Canvas, paints: Paints,
        summary: com.example.accounting.domain.rendering.InvoiceSummaryTotals, taxMode: TaxColumnMode,
        left: Float, right: Float, startY: Float
    ): Float {
        var y = startY
        val labelX = right - 220
        val valueX = right

        fun row(label: String, value: String, bold: Boolean = false) {
            canvas.drawText(label, labelX, y + paints.baseSize, if (bold) paints.bodyBold else paints.body)
            val paint = if (bold) paints.bodyBold else paints.body
            canvas.drawText(value, valueX - paint.measureText(value), y + paints.baseSize, paint)
            y += paints.baseSize + 6
        }

        row("Total Quantity", "%.2f".format(summary.totalQuantity))
        row("Item Amount", summary.itemAmount.formatPlain())
        if (summary.totalDiscount.isPositive) row("Discount", summary.totalDiscount.formatPlain())
        row("Taxable Amount", summary.taxableAmount.formatPlain())
        when (taxMode) {
            TaxColumnMode.CGST_SGST -> {
                row("CGST", summary.cgst.formatPlain())
                row("SGST", summary.sgst.formatPlain())
            }
            TaxColumnMode.IGST -> row("IGST", summary.igst.formatPlain())
            TaxColumnMode.NONE -> {}
        }
        if (summary.cess.isPositive) row("CESS", summary.cess.formatPlain())
        row("Total GST", summary.totalGst.formatPlain())
        if (summary.roundOff.paise != 0L) row("Round Off", summary.roundOff.formatPlain())
        canvas.drawLine(labelX, y, right, y, paints.border)
        y += 4f
        row("Grand Total", summary.grandTotal.formatPlain(), bold = true)
        return y
    }

    // ---------------- Assets ----------------

    /** Real UPI payment QR (Play Store readiness correction, docs/CORRECTIONS_LOG.md) - encodes a
     * standard `upi://pay` deep link (the same URI scheme GPay/PhonePe/Paytm all already handle)
     * with this specific document's real payee/amount, via the same zxing `QRCodeWriter` the app's
     * on-screen [com.example.accounting.presentation.components.QrCodeImage] already uses (a small
     * duplicate here rather than a `presentation/`-layer import, to keep this `data/`-layer
     * renderer's own dependency direction unchanged). Returns null (never a blank/placeholder
     * image) if [upiId] is blank or the string somehow fails to encode. */
    private fun generateUpiPaymentQrBitmap(payeeName: String, upiId: String, amount: Money, documentNumber: String): Bitmap? {
        if (upiId.isBlank()) return null
        val encode: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val upiUri = "upi://pay?pa=${encode(upiId)}&pn=${encode(payeeName)}&am=${amount.formatPlain()}&cu=INR&tn=${encode("Invoice $documentNumber")}"
        return try {
            val sizePx = 300
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                upiUri, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
                mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, com.google.zxing.EncodeHintType.MARGIN to 1)
            )
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /** The LedgerPrime product logo (`res/drawable/ic_ledgerprime_brandmark.xml`) rasterized to a
     * [Bitmap] - a vector app resource, not a user-uploaded asset, so this reads via
     * [android.content.res.Resources]/[android.graphics.drawable.Drawable], never [decodeAsset]'s
     * file-path lookup. Returns null only if the resource itself somehow fails to inflate (never
     * happens in practice - it ships with the app), same defensive shape every other bitmap loader
     * here already uses. */
    private fun loadBrandMark(context: Context): Bitmap? = try {
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, com.example.R.drawable.ic_ledgerprime_brandmark)
        drawable?.let {
            val sizePx = 108
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            it.setBounds(0, 0, sizePx, sizePx)
            it.draw(canvas)
            bitmap
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeAsset(storageReference: String?): Bitmap? {
        if (storageReference.isNullOrBlank()) return null
        return try {
            BitmapFactory.decodeFile(File(storageReference).absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Float, maxHeight: Float): Bitmap {
        val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    // ---------------- Paints ----------------

    private class Paints(config: TemplateVisualConfig) {
        val baseSize = config.typography.baseFontSizeSp.toFloat()
        val headingSize = config.typography.headingFontSizeSp.toFloat()
        private val typeface = safeTypeface(config.typography.fontFamily, config.typography.boldHeadings)
        private val plainTypeface = safeTypeface(config.typography.fontFamily, false)

        val heading = Paint().apply { color = Color.parseColor(config.colors.heading); textSize = headingSize; typeface = this@Paints.typeface }
        val label = Paint().apply { color = Color.parseColor(config.colors.secondary); textSize = baseSize; typeface = this@Paints.typeface }
        val body = Paint().apply { color = Color.parseColor(config.colors.text); textSize = baseSize; typeface = plainTypeface }
        val bodyBold = Paint().apply { color = Color.parseColor(config.colors.text); textSize = baseSize; typeface = this@Paints.typeface }
        val small = Paint().apply { color = Color.parseColor(config.colors.secondary); textSize = (baseSize - 1).coerceAtLeast(7f); typeface = plainTypeface }
        val tableHeader = Paint().apply { color = Color.parseColor(config.colors.heading); textSize = baseSize; typeface = this@Paints.typeface }
        val border = Paint().apply { color = Color.parseColor(config.colors.border); strokeWidth = 1f }
        val hairline = Paint().apply { color = Color.parseColor(config.colors.border); strokeWidth = 0.5f }
        val accentLine = Paint().apply { color = Color.parseColor(config.colors.accent); strokeWidth = 2f }
        val bannerFill = Paint().apply { color = Color.parseColor(config.colors.primary) }
        val headerFill = Paint().apply { color = Color.parseColor(config.colors.border); alpha = 60 }
        val headingOnBanner = Paint().apply { color = Color.WHITE; textSize = headingSize; typeface = this@Paints.typeface }
        val bodyOnBanner = Paint().apply { color = Color.WHITE; textSize = baseSize; typeface = plainTypeface }

        private fun safeTypeface(fontFamily: String, bold: Boolean): Typeface = try {
            Typeface.create(fontFamily, if (bold) Typeface.BOLD else Typeface.NORMAL)
        } catch (e: Exception) {
            Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
