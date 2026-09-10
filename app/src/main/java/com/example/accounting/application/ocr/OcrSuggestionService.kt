package com.example.accounting.application.ocr

import com.example.accounting.application.voucher.VoucherDraftStatus
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.VoucherDraftEntity
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.ocr.OcrExtractionResult
import com.example.accounting.domain.ocr.OcrIngestionAdapter
import com.example.accounting.domain.rendering.BusinessProfile
import java.time.LocalDate
import java.util.UUID

/**
 * Orchestration for the OCR Draft/Suggestion/Review workflow (Phase 7J-B) - [requestExtraction] is
 * a thin pass-through to the injected, nullable [adapter]; [OcrIngestionAdapter] itself is
 * deliberately left **unimplemented** this phase (no vision/ML library was in scope for 7J-B - a
 * documented, explicit boundary, not a silently-skipped gap) - a missing adapter fails gracefully
 * with a structured error, never a crash.
 *
 * [reviewAndPrefillVoucherDraft] is the one piece of real new logic: it turns an
 * [OcrExtractionResult] into a header-only `voucher_drafts` row (narration/date pre-filled from the
 * extraction's own best-effort guesses) with **deliberately zero lines** - OCR never identifies a
 * ledger id, and fabricating one here would be an accounting decision this service is not allowed
 * to make. A human adds real, ledger-mapped journal lines via
 * [com.example.accounting.application.voucher.VoucherManagementServiceImpl.editDraft] before the
 * draft can ever be posted - `postDraft` still only ever delegates to the existing, unmodified
 * `AccountingRepository.postVoucher`. Takes an explicit `companyId`/`financialYearId` - never an
 * implicit current company/FY.
 */
class OcrSuggestionService(
    private val adapter: OcrIngestionAdapter?,
    private val dao: AccountingDao
) {

    suspend fun requestExtraction(
        requestingCompany: BusinessProfile,
        documentAssetId: String,
        documentTypeHint: OcrDocumentType = OcrDocumentType.UNKNOWN
    ): AccountingResult<OcrExtractionResult> {
        val currentAdapter = adapter
            ?: return AccountingResult.Failure(AppError.SystemError("OCR extraction is not yet available - no OcrIngestionAdapter implementation is configured."))
        return currentAdapter.extractFromDocument(requestingCompany, documentAssetId, documentTypeHint)
    }

    /** Returns the new draft's id (a `voucher_drafts` row, `PENDING_REVIEW`, zero lines). */
    suspend fun reviewAndPrefillVoucherDraft(
        companyId: String,
        financialYearId: String,
        extraction: OcrExtractionResult,
        voucherType: VoucherType = VoucherType.JOURNAL
    ): String {
        val draftId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val narrationParts = mutableListOf<String>()
        extraction.vendorNameGuess?.let { narrationParts.add("Vendor: $it") }
        extraction.invoiceNumberGuess?.let { narrationParts.add("Inv#: $it") }
        extraction.vendorGstinGuess?.let { narrationParts.add("GSTIN: $it") }
        extraction.totalAmountGuess?.let { narrationParts.add("Amount: ${it.format()}") }
        val gstParts = mutableListOf<String>()
        extraction.totalCgstGuess?.let { gstParts.add("CGST ${it.format()}") }
        extraction.totalSgstGuess?.let { gstParts.add("SGST ${it.format()}") }
        extraction.totalIgstGuess?.let { gstParts.add("IGST ${it.format()}") }
        extraction.totalCessGuess?.let { gstParts.add("CESS ${it.format()}") }
        if (gstParts.isNotEmpty()) narrationParts.add(gstParts.joinToString(", "))
        if (extraction.lineItems.isNotEmpty()) narrationParts.add("${extraction.lineItems.size} line item(s) detected")
        extraction.upiVpaGuess?.let { narrationParts.add("UPI VPA: $it") }
        extraction.upiTransactionRefGuess?.let { narrationParts.add("Txn Ref: $it") }
        narrationParts.add("(OCR pre-fill, confidence ${(extraction.confidenceScore * 100).toInt()}% - review ledgers before posting)")

        dao.insertVoucherDraft(
            VoucherDraftEntity(
                draftId = draftId,
                companyId = companyId,
                financialYearId = financialYearId,
                voucherType = voucherType,
                date = (extraction.documentDateGuess ?: LocalDate.now()).toString(),
                referenceNumber = "",
                narration = narrationParts.joinToString(" "),
                status = VoucherDraftStatus.PENDING_REVIEW,
                postedVoucherId = null,
                createdAt = now,
                updatedAt = now
            )
        )
        return draftId
    }
}
