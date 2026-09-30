package com.example.accounting.application.reports

import com.example.accounting.core.common.Money
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.reports.BalanceSheetReport
import com.example.accounting.domain.reports.CashFlowReport
import com.example.accounting.domain.reports.DayBookReport
import com.example.accounting.domain.reports.GSTSummaryReport
import com.example.accounting.domain.reports.OutstandingReport
import com.example.accounting.domain.reports.ProfitAndLossReport
import com.example.accounting.domain.reports.RatioAnalysisReport
import com.example.accounting.domain.reports.TrialBalanceReport
import com.example.accounting.domain.reports.VoucherRegisterReport
import com.example.accounting.domain.reports.VoucherRegisterType
import com.example.accounting.domain.reports.buildVoucherRegister
import com.example.accounting.domain.taxation.gst.GstDirection
import java.time.LocalDate

/**
 * Application-service facade unifying every financial report behind one entry point (Phase 7J-B) -
 * every method is a direct, pure delegation to the existing, unmodified `AccountingRepository.generate*`
 * function (or, for [ratioAnalysis], [RatioAnalysisEngine.compute] via the repository's own
 * `generateRatioAnalysis` wrapper) - this class never recalculates a balance, tax figure, or ratio
 * itself, matching the "reports consume engine output, never recalculate" rule already structurally
 * enforced since Phase 7C. Every method takes an explicit `companyId`/`financialYearId` where the
 * underlying report is FY-scoped - never an implicit current company/FY.
 */
class ReportManagementService(private val repository: AccountingRepository) {

    suspend fun trialBalance(
        companyId: String,
        financialYearId: String,
        dateRange: ClosedRange<LocalDate>? = null,
        includeZeroBalance: Boolean = true
    ): TrialBalanceReport =
        repository.generateTrialBalance(companyId, financialYearId, dateRange, includeZeroBalance)

    suspend fun profitAndLoss(
        companyId: String,
        financialYearId: String,
        dateRange: ClosedRange<LocalDate>? = null
    ): ProfitAndLossReport =
        repository.generateProfitAndLoss(companyId, financialYearId, dateRange)

    suspend fun balanceSheet(
        companyId: String,
        financialYearId: String,
        dateRange: ClosedRange<LocalDate>? = null
    ): BalanceSheetReport =
        repository.generateBalanceSheet(companyId, financialYearId, dateRange)

    suspend fun gstSummary(companyId: String, financialYearId: String): GSTSummaryReport =
        repository.generateGSTSummary(companyId, financialYearId)

    suspend fun dayBook(companyId: String, dateRange: ClosedRange<LocalDate>): DayBookReport =
        repository.generateDayBook(companyId, dateRange)

    /** Sales/Purchase/Credit Note/Debit Note register for one financial year - a pure regroup of
     * [dayBook] over the FY's own date range (cancelled vouchers excluded), never a second query. */
    suspend fun voucherRegister(companyId: String, financialYear: FinancialYear, type: VoucherRegisterType): VoucherRegisterReport =
        buildVoucherRegister(
            type = type,
            dayBook = dayBook(companyId, financialYear.startDate..financialYear.endDate),
            financialYearCode = financialYear.fyCode,
            fyStart = financialYear.startDate,
            fyEnd = financialYear.endDate
        )

    suspend fun outstanding(companyId: String, role: PartyRole? = null, today: LocalDate = LocalDate.now()): OutstandingReport =
        repository.generateOutstandingReport(companyId, role, today)

    suspend fun receivables(companyId: String, today: LocalDate = LocalDate.now()): OutstandingReport =
        repository.generateReceivablesReport(companyId, today)

    suspend fun payables(companyId: String, today: LocalDate = LocalDate.now()): OutstandingReport =
        repository.generatePayablesReport(companyId, today)

    suspend fun cashFlow(companyId: String, financialYearId: String, dateRange: ClosedRange<LocalDate>): CashFlowReport =
        repository.generateCashFlow(companyId, financialYearId, dateRange)

    suspend fun ratioAnalysis(
        companyId: String,
        financialYearId: String,
        dateRange: ClosedRange<LocalDate>? = null
    ): RatioAnalysisReport =
        repository.generateRatioAnalysis(companyId, financialYearId, dateRange)

    /**
     * HSN/SAC-wise GST breakdown (Phase 7J UI, the one pre-approved additive grouping method) -
     * pure aggregation of already-computed [com.example.accounting.domain.taxation.gst.GstTransaction]
     * rows (via the new [AccountingRepository.getGstTransactionsForCompanyFY]) by HSN/SAC code -
     * never a new tax calculation, matching this class's own "reports consume engine output, never
     * recalculate" rule.
     */
    suspend fun hsnSacSummary(companyId: String, financialYearId: String): List<HsnSacSummaryRow> =
        repository.getGstTransactionsForCompanyFY(companyId, financialYearId)
            .groupBy { it.hsnSacCode.ifBlank { "(unclassified)" } }
            .map { (code, txns) ->
                HsnSacSummaryRow(
                    hsnSacCode = code,
                    outwardTaxableAmount = txns.filter { it.direction == GstDirection.OUTPUT }.fold(Money.ZERO) { acc, t -> acc + t.taxableAmount },
                    inwardTaxableAmount = txns.filter { it.direction == GstDirection.INPUT }.fold(Money.ZERO) { acc, t -> acc + t.taxableAmount },
                    totalCgst = txns.fold(Money.ZERO) { acc, t -> acc + t.cgst },
                    totalSgst = txns.fold(Money.ZERO) { acc, t -> acc + t.sgst },
                    totalIgst = txns.fold(Money.ZERO) { acc, t -> acc + t.igst },
                    totalCess = txns.fold(Money.ZERO) { acc, t -> acc + t.cess },
                    transactionCount = txns.size
                )
            }
            .sortedByDescending { it.outwardTaxableAmount.paise + it.inwardTaxableAmount.paise }
}

/** One HSN/SAC code's aggregated GST facts (Phase 7J UI) - a pure grouping view, never a source of
 * tax truth; [com.example.accounting.domain.taxation.gst.GstTransaction.gstRatePercent] remains
 * the only authoritative per-transaction rate. */
data class HsnSacSummaryRow(
    val hsnSacCode: String,
    val outwardTaxableAmount: Money,
    val inwardTaxableAmount: Money,
    val totalCgst: Money,
    val totalSgst: Money,
    val totalIgst: Money,
    val totalCess: Money,
    val transactionCount: Int
)
