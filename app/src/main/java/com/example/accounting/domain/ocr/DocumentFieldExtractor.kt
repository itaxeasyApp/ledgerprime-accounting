package com.example.accounting.domain.ocr

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure Kotlin, no Android/ML dependency - turns the plain text an OCR engine already recognized
 * into an [OcrExtractionResult] via regex/keyword heuristics. Deliberately separate from whichever
 * adapter actually runs the OCR engine (see `data/ocr/MlKitOcrAdapter.kt`) - this class only ever
 * sees a `String`, so it is directly unit-testable without a device/emulator/Robolectric, and a
 * future second OCR engine (e.g. a server-side adapter) can reuse this exact extraction logic by
 * calling [extract] with whatever text it recognized.
 *
 * Every extracted field is a best-effort *guess* - real-world scans are noisy, and OCR line order
 * rarely matches visual layout - so [OcrExtractionResult.confidenceScore] is always deliberately
 * conservative (this never claims high confidence), and every guess is meant to be corrected by a
 * human, never trusted as-is. This is explicitly NOT a table-layout-aware parser: line-item
 * extraction for invoices/bank statements is a best-effort single-line-pattern match, not real
 * column/table detection - documented here rather than silently overclaiming accuracy.
 */
object DocumentFieldExtractor {

    private val gstinRegex = Regex("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}\\b")
    private val panRegex = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
    private val aadhaarRegex = Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b")
    private val upiVpaRegex = Regex("\\b[a-zA-Z0-9.\\-_]{2,}@[a-zA-Z][a-zA-Z0-9]{2,}\\b")
    private val amountRegex = Regex("₹?\\s?([0-9][0-9,]*\\.[0-9]{2})")
    private val amountLooseRegex = Regex("₹?\\s?([0-9][0-9,]{2,})(?:\\.[0-9]{1,2})?")
    private val dateRegexNumeric = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")
    private val dateRegexTextMonth = Regex("\\b(\\d{1,2})\\s+([A-Za-z]{3,9})\\s+(\\d{2,4})\\b")
    private val hsnRegex = Regex("\\b\\d{4,8}\\b")

    /** [hint] is what the user told the app they're scanning ([OcrDocumentType.UNKNOWN] if they
     * didn't say); the returned [OcrExtractionResult.documentType] is this function's own final
     * answer, which may differ from [hint] if the recognized text clearly disagrees with it. */
    fun extract(sourceAssetId: String, rawText: String, hint: OcrDocumentType): OcrExtractionResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val documentType = resolveDocumentType(hint, rawText, lines)

        return when (documentType) {
            OcrDocumentType.PAN_CARD -> extractIdentityDocument(sourceAssetId, rawText, lines, documentType, panRegex)
            OcrDocumentType.AADHAAR_CARD -> extractIdentityDocument(sourceAssetId, rawText, lines, documentType, aadhaarRegex)
            OcrDocumentType.GST_CERTIFICATE -> extractIdentityDocument(sourceAssetId, rawText, lines, documentType, gstinRegex)
            OcrDocumentType.UPI_PAYMENT -> extractUpiPayment(sourceAssetId, rawText, lines)
            OcrDocumentType.BANK_STATEMENT -> extractBankStatement(sourceAssetId, rawText, lines)
            OcrDocumentType.PURCHASE_BILL, OcrDocumentType.SALES_INVOICE, OcrDocumentType.EXPENSE_RECEIPT ->
                extractInvoiceLike(sourceAssetId, rawText, lines, documentType)
            OcrDocumentType.OTHER_DOCUMENT, OcrDocumentType.UNKNOWN ->
                OcrExtractionResult(sourceAssetId = sourceAssetId, documentType = OcrDocumentType.OTHER_DOCUMENT, confidenceScore = 0.1, rawText = rawText)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Document-type resolution
    // ---------------------------------------------------------------------------------------

    private fun resolveDocumentType(hint: OcrDocumentType, rawText: String, lines: List<String>): OcrDocumentType {
        if (hint != OcrDocumentType.UNKNOWN) return hint
        val upper = rawText.uppercase(Locale.ROOT)
        return when {
            upper.contains("UIDAI") || upper.contains("AADHAAR") || upper.contains("UNIQUE IDENTIFICATION") ->
                OcrDocumentType.AADHAAR_CARD
            panRegex.containsMatchIn(rawText) && (upper.contains("INCOME TAX") || upper.contains("PERMANENT ACCOUNT")) ->
                OcrDocumentType.PAN_CARD
            gstinRegex.containsMatchIn(rawText) && (upper.contains("CERTIFICATE OF REGISTRATION") || upper.contains("GOODS AND SERVICES TAX")) ->
                OcrDocumentType.GST_CERTIFICATE
            upiVpaLikely(upper) -> OcrDocumentType.UPI_PAYMENT
            (upper.contains("STATEMENT OF ACCOUNT") || upper.contains("BANK STATEMENT") || upper.contains("A/C STATEMENT")) ->
                OcrDocumentType.BANK_STATEMENT
            gstinRegex.containsMatchIn(rawText) && (upper.contains("INVOICE") || upper.contains("BILL")) ->
                OcrDocumentType.PURCHASE_BILL
            upper.contains("RECEIPT") -> OcrDocumentType.EXPENSE_RECEIPT
            else -> OcrDocumentType.OTHER_DOCUMENT
        }
    }

    private fun upiVpaLikely(upper: String): Boolean =
        upper.contains("UPI") || upper.contains("VPA") || upper.contains("GOOGLE PAY") || upper.contains("PHONEPE") || upper.contains("PAYTM")

    // ---------------------------------------------------------------------------------------
    // PAN / Aadhaar - identity documents, feed a Profile Draft only
    // ---------------------------------------------------------------------------------------

    private fun extractIdentityDocument(
        sourceAssetId: String, rawText: String, lines: List<String>, documentType: OcrDocumentType, numberRegex: Regex
    ): OcrExtractionResult {
        val numberMatch = numberRegex.find(rawText)?.value?.replace(" ", "")
        val name = guessPersonName(lines)
        val dob = findLabeledDate(lines, listOf("DOB", "DATE OF BIRTH", "BIRTH"))  ?: findAnyDate(rawText)
        val confidence = listOfNotNull(numberMatch, name, dob).size / 3.0 * 0.7
        return OcrExtractionResult(
            sourceAssetId = sourceAssetId, documentType = documentType, confidenceScore = confidence, rawText = rawText,
            personNameGuess = name, documentNumberGuess = numberMatch, dateOfBirthGuess = dob
        )
    }

    /** No layout information survives plain-text OCR, so this is a genuine guess: the longest
     * all-letters-and-spaces line that isn't one of the fixed boilerplate phrases every PAN/Aadhaar
     * card prints - real cards vary in whether the name comes before or after that boilerplate, so
     * this can and will be wrong sometimes; it is a starting point for the reviewer, never treated
     * as reliable. */
    private fun guessPersonName(lines: List<String>): String? {
        val boilerplate = listOf(
            "INCOME TAX DEPARTMENT", "GOVT OF INDIA", "GOVERNMENT OF INDIA", "PERMANENT ACCOUNT NUMBER",
            "UNIQUE IDENTIFICATION AUTHORITY", "UIDAI", "MERA AADHAAR", "MERI PEHCHAN", "आधार",
            "GOODS AND SERVICES TAX", "CERTIFICATE OF REGISTRATION", "REGISTRATION NUMBER", "GSTIN"
        )
        return lines
            .filter { line -> line.all { it.isLetter() || it.isWhitespace() || it == '.' } && line.length in 4..40 }
            .filterNot { line -> boilerplate.any { line.uppercase(Locale.ROOT).contains(it) } }
            .maxByOrNull { it.length }
    }

    private fun findLabeledDate(lines: List<String>, labels: List<String>): LocalDate? {
        for (line in lines) {
            val upper = line.uppercase(Locale.ROOT)
            if (labels.any { upper.contains(it) }) {
                parseDateToken(line)?.let { return it }
            }
        }
        return null
    }

    private fun findAnyDate(text: String): LocalDate? = parseDateToken(text)

    private fun parseDateToken(text: String): LocalDate? {
        dateRegexNumeric.find(text)?.let { m ->
            val (d, mo, y) = m.destructured
            runCatching {
                val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
                LocalDate.of(year, mo.toInt(), d.toInt())
            }.getOrNull()?.let { return it }
        }
        dateRegexTextMonth.find(text)?.let { m ->
            val (d, monthName, y) = m.destructured
            runCatching {
                val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
                val month = parseMonthName(monthName) ?: return@runCatching null
                LocalDate.of(year, month, d.toInt())
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun parseMonthName(name: String): Int? {
        val normalized = name.take(3).lowercase(Locale.ROOT)
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val idx = months.indexOf(normalized)
        return if (idx >= 0) idx + 1 else null
    }

    // ---------------------------------------------------------------------------------------
    // Invoice / Purchase Bill / Expense Receipt
    // ---------------------------------------------------------------------------------------

    private fun extractInvoiceLike(sourceAssetId: String, rawText: String, lines: List<String>, documentType: OcrDocumentType): OcrExtractionResult {
        val gstin = gstinRegex.find(rawText)?.value
        val invoiceNumber = findLabeledValue(lines, listOf("INVOICE NO", "INVOICE #", "BILL NO", "RECEIPT NO", "INV NO"))
        val date = findLabeledDate(lines, listOf("DATE", "DT.", "DT:")) ?: findAnyDate(rawText)
        val total = findLabeledAmount(lines, listOf("GRAND TOTAL", "TOTAL AMOUNT", "NET AMOUNT", "AMOUNT PAYABLE", "TOTAL"))
        val cgst = findLabeledAmount(lines, listOf("CGST"))
        val sgst = findLabeledAmount(lines, listOf("SGST"))
        val igst = findLabeledAmount(lines, listOf("IGST"))
        val cess = findLabeledAmount(lines, listOf("CESS"))
        val vendorName = guessVendorName(lines)
        val lineItems = guessLineItems(lines)

        val fieldsFound = listOfNotNull(gstin, invoiceNumber, date, total, vendorName).size
        val confidence = (fieldsFound / 5.0 * 0.6) + (if (lineItems.isNotEmpty()) 0.1 else 0.0)

        return OcrExtractionResult(
            sourceAssetId = sourceAssetId, documentType = documentType, confidenceScore = confidence, rawText = rawText,
            vendorNameGuess = vendorName, vendorGstinGuess = gstin, invoiceNumberGuess = invoiceNumber,
            documentDateGuess = date, totalAmountGuess = total, totalCgstGuess = cgst, totalSgstGuess = sgst,
            totalIgstGuess = igst, totalCessGuess = cess, lineItems = lineItems
        )
    }

    /** First non-boilerplate line, on the theory that a business's own name/letterhead is almost
     * always printed at the very top of an invoice - a real heuristic, not a guarantee. */
    private fun guessVendorName(lines: List<String>): String? =
        lines.firstOrNull { line ->
            line.length in 3..50 && !gstinRegex.containsMatchIn(line) && !line.any { it.isDigit() && line.count { c -> c.isDigit() } > 4 }
        }

    private fun findLabeledValue(lines: List<String>, labels: List<String>): String? {
        for (line in lines) {
            val upper = line.uppercase(Locale.ROOT)
            val matchedLabel = labels.firstOrNull { upper.contains(it) } ?: continue
            val afterLabel = line.substring(upper.indexOf(matchedLabel) + matchedLabel.length)
                .trim().trimStart(':', '-', '.', ' ')
            if (afterLabel.isNotBlank()) return afterLabel.take(30)
        }
        return null
    }

    private fun findLabeledAmount(lines: List<String>, labels: List<String>): Money? {
        var lastMatch: Money? = null
        for (line in lines) {
            val upper = line.uppercase(Locale.ROOT)
            if (labels.any { upper.contains(it) }) {
                val amountText = amountRegex.find(line)?.groupValues?.get(1) ?: amountLooseRegex.find(line)?.groupValues?.get(1)
                amountText?.let { lastMatch = Money.parse(it) }
            }
        }
        return lastMatch
    }

    /** Best-effort only, explicitly documented as such (see class KDoc) - a line is treated as an
     * item row if it ends with two or three numeric-looking tokens (qty/rate/amount or just
     * amount) after a text description. Plain-text OCR output has no reliable column boundaries,
     * so this cannot and does not attempt real table parsing. */
    private fun guessLineItems(lines: List<String>): List<OcrLineItemSuggestion> {
        val rowPattern = Regex("^(.{3,40}?)\\s+(\\d+(?:\\.\\d+)?)\\s+([0-9][0-9,]*\\.[0-9]{2})\\s+([0-9][0-9,]*\\.[0-9]{2})$")
        val results = mutableListOf<OcrLineItemSuggestion>()
        for (line in lines) {
            val match = rowPattern.find(line) ?: continue
            val (description, qtyText, rateText, amountText) = match.destructured
            val qty = qtyText.toDoubleOrNull() ?: continue
            val hsn = hsnRegex.find(description)?.value
            results.add(
                OcrLineItemSuggestion(
                    description = description.trim(),
                    quantity = Quantity.fromDouble(qty),
                    rate = Money.parse(rateText),
                    amount = Money.parse(amountText),
                    hsnSacCode = hsn
                )
            )
        }
        return results
    }

    // ---------------------------------------------------------------------------------------
    // Bank Statement - suggested transactions only, never an accounting entry
    // ---------------------------------------------------------------------------------------

    private fun extractBankStatement(sourceAssetId: String, rawText: String, lines: List<String>): OcrExtractionResult {
        val rowPattern = Regex("^(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})\\s+(.{3,50}?)\\s+([0-9][0-9,]*\\.[0-9]{2})$")
        val transactions = mutableListOf<OcrLineItemSuggestion>()
        for (line in lines) {
            val match = rowPattern.find(line) ?: continue
            val (dateText, description, amountText) = match.destructured
            transactions.add(OcrLineItemSuggestion(description = description.trim(), amount = Money.parse(amountText), dateText = dateText))
        }
        val confidence = if (transactions.isNotEmpty()) 0.4 else 0.1
        return OcrExtractionResult(
            sourceAssetId = sourceAssetId, documentType = OcrDocumentType.BANK_STATEMENT, confidenceScore = confidence,
            rawText = rawText, lineItems = transactions
        )
    }

    // ---------------------------------------------------------------------------------------
    // UPI / payment screenshot
    // ---------------------------------------------------------------------------------------

    private fun extractUpiPayment(sourceAssetId: String, rawText: String, lines: List<String>): OcrExtractionResult {
        val vpa = upiVpaRegex.find(rawText)?.value
        val ref = findLabeledValue(lines, listOf("UTR", "TXN ID", "TRANSACTION ID", "REF NO", "REFERENCE NO"))
        val amount = amountRegex.find(rawText)?.groupValues?.get(1)?.let { Money.parse(it) }
        val date = findAnyDate(rawText)
        val confidence = listOfNotNull(vpa, ref, amount).size / 3.0 * 0.6
        return OcrExtractionResult(
            sourceAssetId = sourceAssetId, documentType = OcrDocumentType.UPI_PAYMENT, confidenceScore = confidence,
            rawText = rawText, upiVpaGuess = vpa, upiTransactionRefGuess = ref, totalAmountGuess = amount, documentDateGuess = date
        )
    }
}
