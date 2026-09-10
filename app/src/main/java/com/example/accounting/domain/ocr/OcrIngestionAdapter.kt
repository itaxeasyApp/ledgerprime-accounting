package com.example.accounting.domain.ocr

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.rendering.BusinessProfile
import java.time.LocalDate

/**
 * What kind of source document OCR extraction believes it's looking at - a guess to help a human
 * reviewer pick the right entry screen, never used to auto-select a posting path. [PURCHASE_BILL]/
 * [SALES_INVOICE]/[EXPENSE_RECEIPT] all route to the existing Voucher Draft review queue;
 * [PAN_CARD]/[AADHAAR_CARD] route to a reviewable Individual Profile Draft; [GST_CERTIFICATE]
 * routes to a reviewable **Business** Profile Draft (trade name/GSTIN) - none of the three ever a
 * direct master-data write, and [GST_CERTIFICATE] never touches [com.example.accounting.domain.company.Company]'s
 * own statutory GSTIN (see docs/57_BUSINESS_IDENTITY_DISPLAY.md) - only Settings > My Business >
 * GST Details can change that, by deliberate human action outside OCR entirely;
 * [BANK_STATEMENT]/[UPI_PAYMENT] route to a list of suggested transactions for manual
 * reconciliation (never an automatic accounting entry); [OTHER_DOCUMENT] surfaces only the raw
 * recognized text with no structured field guesses.
 */
enum class OcrDocumentType {
    PURCHASE_BILL, EXPENSE_RECEIPT, SALES_INVOICE,
    PAN_CARD, AADHAAR_CARD, GST_CERTIFICATE, BANK_STATEMENT, UPI_PAYMENT, OTHER_DOCUMENT,
    UNKNOWN
}

/** One extracted line item, every field a *suggestion* the human reviewer confirms or corrects -
 * never written anywhere until they do. Reused for both an invoice's item rows and a bank
 * statement's transaction rows (same "description + amount, everything else optional" shape - a
 * statement row simply leaves quantity/rate/hsnSacCode/gst fields null). */
data class OcrLineItemSuggestion(
    val description: String,
    val quantity: Quantity? = null,
    val rate: Money? = null,
    val discountAmount: Money? = null,
    val taxableValue: Money? = null,
    val gstRatePercent: Double? = null,
    val cgst: Money? = null,
    val sgst: Money? = null,
    val igst: Money? = null,
    val cess: Money? = null,
    val amount: Money,
    val hsnSacCode: String? = null,
    /** Bank statement rows only - the raw date text as printed on the statement (never parsed
     * into a [LocalDate] here; format varies too much across banks to guess safely - the human
     * reviewer confirms the real date when turning this into a Receipt/Payment voucher). */
    val dateText: String? = null
)

/**
 * Result of running OCR extraction against an already-uploaded
 * [com.example.accounting.domain.rendering.DocumentAsset] (referenced by [sourceAssetId] - the
 * scanned image/PDF itself is never duplicated or re-modeled here). Every field is a best-effort
 * *guess* - [confidenceScore] makes that explicit - meant to pre-fill a review screen for a human
 * to correct and explicitly act on. Nothing in this type is ever posted, saved to master data, or
 * turned into an accounting entry automatically.
 */
data class OcrExtractionResult(
    val sourceAssetId: String,
    val documentType: OcrDocumentType,
    val confidenceScore: Double,
    /** Full recognized text, always populated - the one field every document type can fall back
     * to for manual reading when structured extraction below guesses wrong or comes up empty. */
    val rawText: String = "",
    // ---- Invoice/bill fields (PURCHASE_BILL/SALES_INVOICE/EXPENSE_RECEIPT) ----
    val vendorNameGuess: String? = null,
    val vendorGstinGuess: String? = null,
    val invoiceNumberGuess: String? = null,
    val documentDateGuess: LocalDate? = null,
    val totalAmountGuess: Money? = null,
    val totalCgstGuess: Money? = null,
    val totalSgstGuess: Money? = null,
    val totalIgstGuess: Money? = null,
    val totalCessGuess: Money? = null,
    val lineItems: List<OcrLineItemSuggestion> = emptyList(),
    // ---- Identity-document fields (PAN_CARD/AADHAAR_CARD) - populate a Profile Draft only ----
    val personNameGuess: String? = null,
    val documentNumberGuess: String? = null,
    val dateOfBirthGuess: LocalDate? = null,
    // ---- UPI/payment fields (UPI_PAYMENT) ----
    val upiVpaGuess: String? = null,
    val upiTransactionRefGuess: String? = null
)

/**
 * Adapter boundary for OCR/bill-scanning ingestion (Phase 7I) - a pure Kotlin interface only, no
 * implementation, no OCR/ML library dependency, no Android dependency. Mirrors
 * [com.example.accounting.domain.sandbox.SandboxProviderAdapter]'s shape exactly: an interface
 * plus minimal typed models, `AccountingResult<T>` return convention,
 * [com.example.accounting.domain.rendering.BusinessProfile] as the tenant/caller context.
 *
 * **This adapter can never create, edit, or post a [com.example.accounting.domain.accounting.Voucher]
 * or touch any [com.example.accounting.domain.accounting.Ledger] balance, and can never write
 * directly to a company/individual profile or any master data record.** It only ever turns an
 * already-uploaded document image into a suggested, human-reviewable structure:
 * - Invoice/bill types -> the existing voucher-entry path (`DoubleEntryValidator` -> `postVoucher`)
 *   is the only way anything it produces ever reaches the books, and that always requires a human
 *   to review and submit it first.
 * - PAN/Aadhaar -> a Profile Draft the human must explicitly apply before it touches
 *   `BusinessProfile`/`IndividualProfile`.
 * - Bank statement/UPI -> a list of suggested transactions for reconciliation, never an
 *   automatic accounting entry.
 *
 * [documentTypeHint] lets the caller tell the adapter what kind of document this is (from an
 * explicit picker the user sees before scanning) - [OcrDocumentType.UNKNOWN] (the default) means
 * "not told, do your own best-effort guess." An implementation is always free to override the hint
 * if the recognized text clearly disagrees with it (e.g. a PAN-shaped number found on a document
 * hinted as an invoice) - [OcrExtractionResult.documentType] is always the adapter's own final
 * answer, never required to just echo the hint back.
 */
interface OcrIngestionAdapter {
    suspend fun extractFromDocument(
        requestingCompany: BusinessProfile,
        documentAssetId: String,
        documentTypeHint: OcrDocumentType = OcrDocumentType.UNKNOWN
    ): AccountingResult<OcrExtractionResult>
}
