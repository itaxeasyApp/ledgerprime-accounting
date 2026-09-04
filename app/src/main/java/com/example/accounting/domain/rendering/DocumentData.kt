package com.example.accounting.domain.rendering

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.document.DocumentType
import java.time.LocalDate

/**
 * A clean, Compose/PDF-library-independent representation of one renderable document (Phase 7D).
 * Every value here is already authoritative when this object is built - [assembleDocumentData]
 * (see `AccountingRepository`) is the ONLY place that reads Invoice/TradeDocument/GstTransaction/
 * Party/Ledger/Company data and it never recomputes GST, totals, or ledger balances itself: for a
 * posted [Invoice], tax/total figures come straight from the already-persisted `GstTransaction`/
 * `Voucher` rows; for a draft Invoice or a non-posting [TradeDocument] (neither of which has a
 * `GstTransaction` yet), it calls the existing, unmodified `GstCalculationEngine` per line - it
 * never reimplements GST math. No renderer (JSON/PDF/Print/Share/CSV) may re-derive any of these
 * fields from anything other than this object.
 */
data class DocumentData(
    val documentId: String,
    val companyId: String,
    val documentType: DocumentType,
    /** The user-facing document number (`Invoice.invoiceNumber`/`TradeDocument.documentNumber`),
     * displayed verbatim - never re-derived, reformatted, or recomputed from `DocumentType`'s
     * prefix by a renderer. */
    val documentNumber: String,
    val documentDate: LocalDate,
    val dueDate: LocalDate? = null,
    /** When goods shipped/were dispatched - explicit extension points (Phase 7J), additive and
     * always `null` today, the same "documented, not fabricated" pattern `docs/40_CASH_FLOW.md`
     * already used for `netCashFromInvestingActivities`: neither `Invoice` nor `TradeDocument` has
     * an underlying ship/dispatch-date field to source this from yet, so [assembleDocumentData]
     * cannot populate it without inventing a value - a future phase that adds the real field to
     * those frozen types (and a migration for it) is what would make this non-null. A renderer
     * must hide the corresponding label entirely when `null`, never show a blank/placeholder date. */
    val shipDate: LocalDate? = null,
    val dispatchDate: LocalDate? = null,
    val seller: DocumentPartySnapshot,
    val buyer: DocumentPartySnapshot,
    val items: List<DocumentLineData>,
    val totals: DocumentTotals,
    val paymentInformation: DocumentPaymentInfo,
    val references: DocumentReferenceInfo,
    val terms: String = "",
    val branding: DocumentBrandingSnapshot,
    /** True once this document is posted (`Invoice.voucherId != null`) - a non-posting
     * [TradeDocument] is always `false`. Tells a renderer whether it is showing a final,
     * accounting-backed document or a draft/preview - it must never be used to gate whether tax
     * math is "recalculated," since neither path ever recalculates independently (see class doc). */
    val isPosted: Boolean,
    /** `Voucher.voucherNumber` for a posted document - present alongside [documentNumber] so a
     * renderer CAN show it (e.g. "Ref: INV-2026-0001") but must never conflate the two; null for
     * an unposted draft or any non-posting TradeDocument. */
    val accountingVoucherNumber: String? = null
)

/** Seller or buyer identity as it should appear on a rendered document - assembled from
 * [com.example.accounting.domain.party.Party] + [com.example.accounting.domain.accounting.Ledger]
 * (buyer) or [BusinessProfile]/[com.example.accounting.domain.company.Company] (seller). Never a
 * second copy of authoritative Party/Ledger/Company data maintained independently - always
 * assembled fresh at render time. */
data class DocumentPartySnapshot(
    val name: String,
    val address: String = "",
    val gstin: String = "",
    val pan: String = "",
    val phone: String = "",
    val email: String = "",
    /** GST-mandatory on a tax invoice (Place of Supply/recipient state) for both B2B and B2C -
     * [stateCode] is the raw two-digit code already used for CGST/SGST-vs-IGST classification
     * ([com.example.accounting.data.repository.AccountingRepository.computeLineTax]); [stateName]
     * is the same [com.example.accounting.core.common.Constants.GST_STATE_CODES] lookup Company/
     * Party/Ledger setup already use for display - never a second, independently-typed state name. */
    val stateCode: String = "",
    val stateName: String = ""
)

data class DocumentLineData(
    val itemId: String,
    val description: String,
    val hsnSacCode: String,
    /** Null when the domain rules permit an optional quantity (Section 4: service documents must
     * not have inventory concepts forced onto them) - a renderer must hide the quantity/unit
     * columns entirely rather than display a fabricated value when this is null. */
    val quantity: Quantity?,
    val unit: String = "",
    val rate: Money,
    val discount: Money = Money.ZERO,
    val taxableAmount: Money,
    val gstRatePercent: Double,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money,
    val lineTotal: Money
)

/** Every figure here is sourced from an existing engine/persisted row (`GstCalculationEngine`,
 * `GstTransaction`, `Voucher.totalAmountPaise`, `RoundOffEngine`'s already-applied result) -
 * never re-derived by summing [DocumentLineData] rows a second, independent way. */
data class DocumentTotals(
    val taxableAmount: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money,
    val roundOff: Money,
    val grandTotal: Money
)

data class DocumentPaymentInfo(
    val bankName: String = "",
    val bankAccountNumber: String = "",
    val bankIfsc: String = "",
    val bankBranch: String = "",
    val upiId: String = ""
)

/** Reuses Phase 7B's already-established relationships (Section 25) - never invents a
 * relationship. [originalDocumentNumber] is populated only for a Credit/Debit Note (its
 * `referenceInvoiceId`) or a converted document (its `sourceTradeDocumentId`); both null for a
 * document created directly, which Phase 7B explicitly guarantees is always possible. */
data class DocumentReferenceInfo(
    val referenceDocumentId: String? = null,
    val referenceDocumentNumber: String? = null,
    val referenceDocumentDate: LocalDate? = null
)

/** What a renderer needs from [BusinessProfile]/[IndividualProfile] plus their [DocumentAsset]
 * references - resolved once at assembly time so a renderer never has to look up an asset itself. */
data class DocumentBrandingSnapshot(
    val logoStorageReference: String? = null,
    val signatureStorageReference: String? = null,
    val qrCodeStorageReference: String? = null
)

/** The output of a [com.example.accounting.domain.rendering.DocumentRenderer] - a thin, typed
 * wrapper so callers don't need to know which renderer produced the artifact. */
sealed class RenderedDocument {
    data class Json(val schemaVersion: Int, val content: String) : RenderedDocument()
    data class Pdf(val filePath: String) : RenderedDocument()
}

/**
 * A log entry recorded every time a document is rendered (Phase 7D, Section 10/21) - captures
 * exactly which [DocumentTemplate] (`templateId` + `version`) produced a given artifact, without
 * adding any field to the frozen `Invoice`/`TradeDocument` entities. This is what makes "an
 * already-generated document must remain reproducible using the template/version associated with
 * it" checkable: even after a template is edited (a new version becomes ACTIVE), this record still
 * points at the exact, unchanged, still-`ARCHIVED`-but-present version row that was used.
 */
data class RenderedDocumentRecord(
    val recordId: String,
    val companyId: String,
    val documentId: String,
    val documentType: DocumentType,
    val templateId: String,
    val templateVersion: Int,
    val format: String, // "PDF" | "JSON"
    val storageReference: String? = null,
    val generatedAt: Long = System.currentTimeMillis()
)
