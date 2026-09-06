package com.example.accounting.application.export

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.export.ExportFormat
import com.example.accounting.domain.export.ExportResult
import java.time.LocalDate

/**
 * Application-service facade unifying every export entry point (Phase 7J-B, Android-only per this
 * phase's explicit scope decision - `server/app/api/routes/exports.py` is untouched, Python already
 * exposes its own equivalent GET routes). Every method is a direct, pure delegation to the
 * existing, unmodified [AccountingRepository.export*As] function (Phase 7E) - this class never
 * recomputes a figure or re-derives a serialization itself.
 */
class ExportManagementService(private val repository: AccountingRepository) {

    suspend fun exportVoucher(companyId: String, voucherId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportVoucherAs(companyId, voucherId, format)

    suspend fun exportParty(companyId: String, partyId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportPartyAs(companyId, partyId, format)

    suspend fun exportLedger(companyId: String, ledgerId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportLedgerAs(companyId, ledgerId, format)

    suspend fun exportInvoice(companyId: String, documentType: DocumentType, documentId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportInvoiceAs(companyId, documentType, documentId, format)

    suspend fun exportTrialBalance(companyId: String, financialYearId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportTrialBalanceAs(companyId, financialYearId, format)

    suspend fun exportProfitAndLoss(companyId: String, financialYearId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportProfitAndLossAs(companyId, financialYearId, format)

    suspend fun exportBalanceSheet(companyId: String, financialYearId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportBalanceSheetAs(companyId, financialYearId, format)

    suspend fun exportOutstanding(companyId: String, format: ExportFormat, today: LocalDate = LocalDate.now()): AccountingResult<ExportResult> =
        repository.exportOutstandingAs(companyId, format, today)

    suspend fun exportGstSummary(companyId: String, financialYearId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportGstSummaryAs(companyId, financialYearId, format)

    suspend fun exportGstTransactions(companyId: String, financialYearId: String, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportGstTransactionsAs(companyId, financialYearId, format)

    suspend fun exportGstReturn(companyId: String, gstReturnId: String, fy: com.example.accounting.domain.financialyear.FinancialYear, format: ExportFormat): AccountingResult<ExportResult> =
        repository.exportGstReturnAs(companyId, gstReturnId, fy, format)

    suspend fun importGstReturnDraft(companyId: String, gstReturnId: String, jsonContent: String): AccountingResult<com.example.accounting.domain.taxation.gstreturn.GstReturn> =
        repository.importGstReturnDraftJson(companyId, gstReturnId, jsonContent)
}
