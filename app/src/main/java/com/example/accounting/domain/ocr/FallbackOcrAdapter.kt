package com.example.accounting.domain.ocr

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.domain.rendering.BusinessProfile

/**
 * Tries [primary] first; if it fails, tries [secondary] (when one is configured) before giving up.
 * Exists so a second OCR engine - e.g. a future server-side adapter, for documents the on-device
 * engine ([com.example.accounting.data.ocr.MlKitOcrAdapter]) struggles with - can be added later
 * as a pure composition, with zero change to [com.example.accounting.application.ocr.OcrSuggestionService]
 * or anything above it. [secondary] is `null` today (no second engine exists yet); wiring one in
 * later is a one-line change at the single call site that constructs this adapter.
 *
 * Never a merge of two partial results - a "fallback" is exactly that: the second attempt only
 * runs if the first came back as [AccountingResult.Failure], and its own result (success or
 * failure) is returned as-is, never combined with the first attempt's output.
 */
class FallbackOcrAdapter(
    private val primary: OcrIngestionAdapter,
    private val secondary: OcrIngestionAdapter? = null
) : OcrIngestionAdapter {

    override suspend fun extractFromDocument(
        requestingCompany: BusinessProfile,
        documentAssetId: String,
        documentTypeHint: OcrDocumentType
    ): AccountingResult<OcrExtractionResult> {
        val primaryResult = primary.extractFromDocument(requestingCompany, documentAssetId, documentTypeHint)
        if (primaryResult is AccountingResult.Success) return primaryResult
        val fallback = secondary ?: return primaryResult
        return fallback.extractFromDocument(requestingCompany, documentAssetId, documentTypeHint)
    }
}
