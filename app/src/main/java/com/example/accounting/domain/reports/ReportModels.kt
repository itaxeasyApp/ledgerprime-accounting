package com.example.accounting.domain.reports

import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.invoice.InvoiceStatus
import com.example.accounting.domain.invoice.InvoiceType
import java.time.LocalDate

data class TrialBalanceRow(
    val ledgerId: String,
    val ledgerName: String,
    val groupId: String,
    val groupName: String,
    val primaryGroup: PrimaryGroup,
    val openingDebit: Money = Money.ZERO,
    val openingCredit: Money = Money.ZERO,
    val transactionDebit: Money = Money.ZERO,
    val transactionCredit: Money = Money.ZERO,
    val closingDebit: Money = Money.ZERO,
    val closingCredit: Money = Money.ZERO
)

data class TrialBalanceReport(
    val companyName: String,
    val financialYearCode: String,
    val asOfDate: LocalDate,
    val rows: List<TrialBalanceRow>,
    val totalOpeningDebit: Money,
    val totalOpeningCredit: Money,
    val totalTransactionDebit: Money,
    val totalTransactionCredit: Money,
    val totalClosingDebit: Money,
    val totalClosingCredit: Money,
    /** Recursive group-hierarchy totals (Section 21/22) - each group's total already includes all descendants. */
    val groupHierarchy: List<GroupBalanceNode> = emptyList()
) {
    val isBalanced: Boolean get() = totalClosingDebit.paise == totalClosingCredit.paise
    val difference: Money get() = (totalClosingDebit - totalClosingCredit).abs()
}

data class FinancialStatementItem(
    val title: String,
    val amount: Money,
    val subItems: List<FinancialStatementItem> = emptyList(),
    val isHighlight: Boolean = false
)

data class ProfitAndLossReport(
    val companyName: String,
    val financialYearCode: String,
    val dateRange: String,
    val salesRevenue: Money,
    val directIncomes: Money,
    val purchases: Money,
    val directExpenses: Money,
    val grossProfit: Money, // (Sales + Direct Incomes) - (COGS or Purchases + Direct Expenses)
    val indirectIncomes: Money,
    val indirectExpenses: Money,
    val netProfit: Money, // Gross Profit + Indirect Incomes - Indirect Expenses
    /**
     * Phase 4 (Account + Inventory only): Opening Stock + Purchases - Purchase Returns - Closing
     * Stock, replacing plain [purchases] in the Gross Profit formula. Zero for
     * [com.example.accounting.domain.company.AccountingMode.ACCOUNT_ONLY] companies, where
     * Gross Profit continues to use [purchases] exactly as before Phase 4.
     */
    val cogs: Money = Money.ZERO,
    val openingStock: Money = Money.ZERO,
    val closingStock: Money = Money.ZERO,
    val isInventoryAware: Boolean = false
)

/**
 * Income & Expenditure statement for [com.example.accounting.domain.company.BusinessType.SERVICE]
 * companies (Section: business-type toggle) - same underlying group-hierarchy data as
 * [ProfitAndLossReport] but presented without a Trading/COGS section, since a service business
 * has no goods being sold.
 */
data class IncomeExpenditureReport(
    val companyName: String,
    val financialYearCode: String,
    val dateRange: String,
    val income: Money,
    val expenditure: Money,
    val surplusOrDeficit: Money // Income - Expenditure; negative = Deficit, sign preserved
) {
    val isSurplus: Boolean get() = surplusOrDeficit.paise >= 0
}

data class BalanceSheetReport(
    val companyName: String,
    val financialYearCode: String,
    val asOfDate: LocalDate,
    // Liabilities side
    val capitalAccounts: Money,
    val reservesAndSurplus: Money = Money.ZERO,
    val netProfitForYear: Money,
    val loansLiabilities: Money,
    val currentLiabilities: Money,
    val dutiesAndTaxesLiability: Money,
    val branchDivisions: Money = Money.ZERO,
    val suspenseCredit: Money = Money.ZERO,
    /** The invoice-rounding "Round Off" ledger's net credit balance - same PrimaryGroup.SPECIAL_CONTROL
     * control-account treatment as Suspense (see [suspenseCredit]/[suspenseDebit]'s own doc), and
     * the same reason it needs its own explicit fold here: unlike every named Liabilities/Assets
     * bucket above, a SPECIAL_CONTROL ledger's balance is invisible to `totalLiabilitiesPrimaryPaise`/
     * `totalAssetsPrimaryPaise` (they filter by PrimaryGroup.LIABILITIES/ASSETS) unless explicitly
     * looked up and added in, same as `AccountingRepository.generateBalanceSheet` already did for
     * Suspense - Round Off was missing that same fold, which silently dropped it from both sides of
     * the Balance Sheet identity whenever an invoice actually needed rounding. */
    val roundOffCredit: Money = Money.ZERO,
    val totalLiabilities: Money,
    // Assets side
    val fixedAssets: Money,
    val investments: Money = Money.ZERO,
    val currentAssets: Money,
    val sundryDebtors: Money,
    val bankAccounts: Money,
    val cashInHand: Money,
    val miscExpensesAsset: Money = Money.ZERO,
    /** Phase 4 (Account + Inventory only): closing stock valuation, folded into Current Assets. */
    val stockInHand: Money = Money.ZERO,
    /**
     * Net Input Tax Credit still recoverable (Input GST > Output GST for the period) - a real
     * Current Asset, never a negative "Duties & Taxes" liability. Both Input and Output GST
     * ledgers live under the same Tally-style "Duties & Taxes" group, so a naive net-credit read
     * of that whole group goes negative whenever ITC exceeds output liability; that negative
     * value belongs on the Assets side (money recoverable from the government), not displayed as
     * a liability with a debit-natured balance. [dutiesAndTaxesLiability] is floored at zero and
     * this field carries the flipped, positive remainder so nothing is lost - see
     * `AccountingRepository.generateBalanceSheet`.
     */
    val gstRecoverable: Money = Money.ZERO,
    val suspenseDebit: Money = Money.ZERO,
    /** See [roundOffCredit]'s own doc - the same Round Off control-account balance, Debit-natured
     * side. */
    val roundOffDebit: Money = Money.ZERO,
    val totalAssets: Money
) {
    val isBalanced: Boolean get() = totalLiabilities.paise == totalAssets.paise
    val difference: Money get() = (totalLiabilities - totalAssets).abs()
}

data class LedgerStatementRow(
    val voucherId: String,
    val voucherNumber: String,
    val voucherType: String,
    val date: LocalDate,
    val particulars: String,
    val debitAmount: Money,
    val creditAmount: Money,
    val runningBalance: Money,
    val balanceType: DrCr
)

data class LedgerStatementReport(
    val ledgerId: String,
    val ledgerName: String,
    val openingBalance: Money,
    val openingType: DrCr,
    val rows: List<LedgerStatementRow>,
    val totalDebit: Money,
    val totalCredit: Money,
    val closingBalance: Money,
    val closingType: DrCr
)

data class GSTSummaryReport(
    val companyName: String,
    val gstin: String,
    val period: String,
    // Outward Supplies (GSTR-1)
    val totalTaxableOutward: Money,
    val totalCGSTOutward: Money,
    val totalSGSTOutward: Money,
    val totalIGSTOutward: Money,
    val totalTaxOutward: Money,
    // Inward Supplies / ITC (GSTR-3B)
    val totalTaxableInward: Money,
    val totalCGSTInwardITC: Money,
    val totalSGSTInwardITC: Money,
    val totalIGSTInwardITC: Money,
    val totalTaxInwardITC: Money,
    // Net Payable
    val netTaxPayable: Money,
    // CESS (Phase 5, Priority 4/5) - one ledger, not Output/Input split; net is simply the sum,
    // never coerced against outward/inward the way CGST/SGST/IGST payable is.
    val totalCess: Money = Money.ZERO,
    val netCessPayable: Money = Money.ZERO,
    /** 13-point correctness pass, item 6 (GSTR-1 B2B/B2C/CDN bucketing) - a breakdown of the same
     * [totalTaxableOutward]/[totalTaxOutward] figures above by GSTR-1 category, computed from the
     * same underlying `gst_transactions` rows this whole report already reads
     * ([com.example.accounting.data.repository.AccountingRepository.generateGSTSummary]); B2B is
     * `SALES` with a non-blank `partyGstin`, B2C is `SALES` with a blank one, Credit/Debit Note are
     * their own voucher types. All default `Money.ZERO` so no pre-existing call site or test that
     * constructs a [GSTSummaryReport] without them is affected. */
    val b2bTaxableOutward: Money = Money.ZERO,
    val b2bTaxOutward: Money = Money.ZERO,
    val b2cTaxableOutward: Money = Money.ZERO,
    val b2cTaxOutward: Money = Money.ZERO,
    val creditNoteTaxableOutward: Money = Money.ZERO,
    val creditNoteTaxOutward: Money = Money.ZERO,
    val debitNoteTaxableOutward: Money = Money.ZERO,
    val debitNoteTaxOutward: Money = Money.ZERO
)

// ==================== PHASE 7C: REPORT MANAGEMENT ====================

/** A Day Book row's posting status (Phase 7C) - a non-posting document (Quotation/Sales Order/
 * etc, Phase 7B's `TradeDocument`) can never appear here at all: Day Book reads exclusively from
 * the `vouchers` table, which only ever contains real posted/cancelled accounting transactions -
 * this is a structural guarantee (a different table entirely), not a runtime filter. */
enum class DayBookEntryStatus { POSTED, CANCELLED }

data class DayBookRow(
    val voucherId: String,
    val voucherNumber: String,
    val voucherType: VoucherType,
    val date: LocalDate,
    /** Resolved via the existing Invoice<->Voucher link (7A) when this voucher is a Sales
     * Invoice/Purchase Bill/Credit Note/Debit Note; null for Receipt/Payment/Contra/Journal,
     * which carry no party linkage in the current domain. */
    val partyName: String?,
    val narration: String,
    val totalAmount: Money,
    val status: DayBookEntryStatus
)

data class DayBookReport(
    val dateRangeLabel: String,
    val rows: List<DayBookRow>,
    val totalAmount: Money
)

/** Standard receivables/payables ageing buckets (Phase 7C) - `CURRENT` means not yet past its due
 * date (or no due date at all); the numbered buckets are days PAST the due date. Computed once
 * here, in the domain layer, so a future UI/PDF/CSV consumer never has to re-derive it. */
enum class AgingBucket { CURRENT, DAYS_1_30, DAYS_31_60, DAYS_61_90, DAYS_90_PLUS }

data class OutstandingReportRow(
    val invoiceId: String,
    val invoiceNumber: String?,
    val invoiceType: InvoiceType,
    val partyId: String,
    val partyName: String,
    val voucherId: String,
    val voucherNumber: String,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val totalAmount: Money,
    val outstandingAmount: Money,
    /** Derived exclusively via the existing, unmodified `InvoiceStatusEngine.deriveStatus` (7A) -
     * never recomputed here. */
    val status: InvoiceStatus,
    val daysOutstanding: Int,
    val agingBucket: AgingBucket
)

data class AgingBucketTotal(val bucket: AgingBucket, val totalOutstanding: Money, val invoiceCount: Int)

data class OutstandingReport(
    val rows: List<OutstandingReportRow>,
    val totalOutstanding: Money,
    val agingSummary: List<AgingBucketTotal>
)

/**
 * Cash Flow (Phase 7C) - Operating Activities computed via the standard indirect method (net
 * profit adjusted for the period's change in Current Assets-excluding-cash and Current
 * Liabilities), sourced entirely from the existing, unmodified [ProfitAndLossReport]/
 * [BalanceSheetReport]. [netCashFromInvestingActivities]/[netCashFromFinancingActivities] are
 * explicit extension points, deliberately left null: no Fixed-Asset-purchase/disposal or
 * Loan-drawn/repaid transaction categorization exists anywhere in the current domain to safely
 * attribute those sections, and inventing one would violate "never invent accounting behavior."
 * [netChangeInCashAndBank] is the *factual* opening-to-closing Cash & Bank movement (read, not
 * derived) - it is not forced to reconcile against [netCashFromOperatingActivities] alone, since
 * that would silently misattribute whatever the Investing/Financing sections would have explained.
 */
data class CashFlowReport(
    val companyName: String,
    val financialYearCode: String,
    val dateRangeLabel: String,
    val netProfit: Money,
    val changeInCurrentAssetsExcludingCash: Money,
    val changeInCurrentLiabilities: Money,
    val netCashFromOperatingActivities: Money,
    val netCashFromInvestingActivities: Money? = null,
    val netCashFromFinancingActivities: Money? = null,
    val openingCashAndBank: Money,
    val closingCashAndBank: Money,
    val netChangeInCashAndBank: Money
)

/**
 * ACTUAL ratios only (Phase 7C), derived purely from an already-generated [BalanceSheetReport]/
 * [ProfitAndLossReport] - never recalculated from raw ledgers. Future CMA Estimated/Projected
 * ratio variants are a separate report type that will consume this one as input; no such
 * assumption is built into this model, and no CMA-specific logic exists here.
 */
data class RatioAnalysisReport(
    val companyName: String,
    val financialYearCode: String,
    val asOfDate: LocalDate,
    val currentRatio: Double,
    val quickRatio: Double,
    val debtEquityRatio: Double,
    val grossProfitRatioPercent: Double,
    val netProfitRatioPercent: Double,
    val operatingRatioPercent: Double,
    val returnOnCapitalEmployedPercent: Double
)
