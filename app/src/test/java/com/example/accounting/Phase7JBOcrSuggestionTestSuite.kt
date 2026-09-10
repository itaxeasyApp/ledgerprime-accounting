package com.example.accounting

import com.example.accounting.Phase7JBFixtures.seedCompanyAndFy
import com.example.accounting.application.ocr.OcrSuggestionService
import com.example.accounting.application.voucher.VoucherDraftStatus
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.Money
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.ocr.OcrExtractionResult
import com.example.accounting.domain.ocr.OcrIngestionAdapter
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.ConstitutionType
import com.example.accounting.domain.accounting.VoucherType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 7J-B - [OcrSuggestionService]: [OcrIngestionAdapter] itself stays deliberately
 * **unimplemented** this phase (no vision/ML library was in scope) - [requestExtraction] fails
 * gracefully (never a crash) when no adapter is configured, and behaves as a thin pass-through when
 * a (test-only fake) adapter is supplied. [reviewAndPrefillVoucherDraft] is the one piece of real
 * new logic: it produces a `PENDING_REVIEW` `voucher_drafts` row with zero lines (never a fabricated
 * ledger assignment) and zero journal/ledger effect.
 */
class Phase7JBOcrSuggestionTestSuite {

    private val companyId = Phase7JBFixtures.COMPANY_ID
    private val fyId = Phase7JBFixtures.FY_ID

    private fun freshDao() = Phase7JBAwareDao(FakeAccountingDao())

    private fun profile() = BusinessProfile(
        businessProfileId = "BP_1", companyId = companyId, businessName = "Test Co",
        constitutionType = ConstitutionType.PROPRIETORSHIP
    )

    private class FakeOcrAdapter(private val result: AccountingResult<OcrExtractionResult>) : OcrIngestionAdapter {
        override suspend fun extractFromDocument(
            requestingCompany: BusinessProfile,
            documentAssetId: String,
            documentTypeHint: com.example.accounting.domain.ocr.OcrDocumentType
        ) = result
    }

    @Test
    fun testRequestExtraction_noAdapterConfigured_failsGracefully_notACrash() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val service = OcrSuggestionService(adapter = null, dao = dao)

        val result = service.requestExtraction(profile(), "ASSET_1")
        assertTrue(result is AccountingResult.Failure)
    }

    @Test
    fun testRequestExtraction_adapterConfigured_thinPassThrough() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val extraction = OcrExtractionResult(
            sourceAssetId = "ASSET_1", documentType = OcrDocumentType.PURCHASE_BILL, confidenceScore = 0.8,
            vendorNameGuess = "ACME Supplies", totalAmountGuess = Money.fromPaise(4_500_00L)
        )
        val service = OcrSuggestionService(adapter = FakeOcrAdapter(AccountingResult.Success(extraction)), dao = dao)

        val result = service.requestExtraction(profile(), "ASSET_1")
        val data = (result as AccountingResult.Success).data
        assertEquals("ACME Supplies", data.vendorNameGuess)
    }

    @Test
    fun testReviewAndPrefillVoucherDraft_producesPendingReviewDraft_withZeroLinesAndZeroLedgerEffect() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val service = OcrSuggestionService(adapter = null, dao = dao)

        val extraction = OcrExtractionResult(
            sourceAssetId = "ASSET_1", documentType = OcrDocumentType.PURCHASE_BILL, confidenceScore = 0.75,
            vendorNameGuess = "ACME Supplies", totalAmountGuess = Money.fromPaise(4_500_00L),
            documentDateGuess = LocalDate.of(2026, 6, 10)
        )

        val draftId = service.reviewAndPrefillVoucherDraft(companyId, fyId, extraction)
        val draft = dao.getVoucherDraftById(companyId, draftId)

        assertEquals(VoucherDraftStatus.PENDING_REVIEW, draft?.status)
        assertEquals("2026-06-10", draft?.date)
        assertTrue(draft?.narration?.contains("ACME Supplies") == true)
        assertTrue("OCR must never fabricate a ledger-mapped line", dao.getLinesForVoucherDraft(draftId).isEmpty())
        assertTrue(dao.getVouchersByCompany(companyId).first().isEmpty())
    }

    /** Step 7 live-device fix - [VoucherDraftEditorScreen] has no voucher-type selector at all, so
     * whichever [VoucherType] this draft is created with is exactly what gets posted.
     * `AccountingViewModel.scanDocument` now maps a scanned Purchase Bill/Sales Invoice's already-
     * correctly-classified [OcrExtractionResult.documentType] onto this parameter (PURCHASE_BILL ->
     * PURCHASE, SALES_INVOICE -> SALES) instead of always taking the JOURNAL default - this pins
     * the service-level mechanism that fix depends on: the draft actually persists whatever
     * [VoucherType] it's given, not a hardcoded one. */
    @Test
    fun testReviewAndPrefillVoucherDraft_explicitVoucherType_isPersistedOnTheDraft() = runBlocking {
        val dao = freshDao()
        dao.seedCompanyAndFy()
        val service = OcrSuggestionService(adapter = null, dao = dao)

        val purchaseExtraction = OcrExtractionResult(
            sourceAssetId = "ASSET_2", documentType = OcrDocumentType.PURCHASE_BILL, confidenceScore = 0.75,
            vendorNameGuess = "ACME Supplies", totalAmountGuess = Money.fromPaise(4_500_00L)
        )
        val purchaseDraftId = service.reviewAndPrefillVoucherDraft(companyId, fyId, purchaseExtraction, VoucherType.PURCHASE)
        assertEquals(VoucherType.PURCHASE, dao.getVoucherDraftById(companyId, purchaseDraftId)?.voucherType)

        val salesExtraction = OcrExtractionResult(
            sourceAssetId = "ASSET_3", documentType = OcrDocumentType.SALES_INVOICE, confidenceScore = 0.75,
            vendorNameGuess = "Retail Customer", totalAmountGuess = Money.fromPaise(1_180_00L)
        )
        val salesDraftId = service.reviewAndPrefillVoucherDraft(companyId, fyId, salesExtraction, VoucherType.SALES)
        assertEquals(VoucherType.SALES, dao.getVoucherDraftById(companyId, salesDraftId)?.voucherType)

        // Unchanged default for the genuinely ambiguous document types (Expense Receipt/Bank
        // Statement/UPI Payment) - direction is never known from OCR alone.
        val bankExtraction = OcrExtractionResult(sourceAssetId = "ASSET_4", documentType = OcrDocumentType.BANK_STATEMENT, confidenceScore = 0.4)
        val bankDraftId = service.reviewAndPrefillVoucherDraft(companyId, fyId, bankExtraction)
        assertEquals(VoucherType.JOURNAL, dao.getVoucherDraftById(companyId, bankDraftId)?.voucherType)
    }
}
