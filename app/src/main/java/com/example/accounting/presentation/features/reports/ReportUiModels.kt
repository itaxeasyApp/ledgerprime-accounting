package com.example.accounting.presentation.features.reports

import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.reports.FinancialStatementItem
import com.example.accounting.domain.reports.GroupBalanceNode
import com.example.accounting.domain.reports.LedgerStatementRow
import com.example.accounting.domain.reports.RatioAnalysisReport
import com.example.accounting.domain.reports.TrialBalanceReport
import com.example.accounting.domain.reports.TrialBalanceRow

/**
 * Presentation-layer bridge between domain report models and Compose UI (tree expand/collapse,
 * bulk-select, ratio health color) - kept out of `domain/reports/ReportModels.kt` deliberately:
 * `isExpanded`/`isSelected` are ephemeral UI state, never a business fact, and mixing them into the
 * domain report models would mean a report's own equality/identity changes just because the user
 * tapped a chevron - the exact "single source of truth, no second independently-mutable copy"
 * mistake this codebase has avoided everywhere else. One small, purpose-named file so it's easy to
 * find again, not folded into an existing one.
 */

/** [LedgerStatementRow] already carries a real [VoucherType] (fixed in the domain model itself -
 * it used to be a throwaway display `String`, `docs/CORRECTIONS_LOG.md`) - [voucherTypeEnum] here
 * is a direct passthrough, kept as its own named field only so a Compose call site can destructure/
 * navigate on it without also reaching into [row] for everything else. [formattedRunningBalance] is
 * the one real UI convenience this wrapper adds: `"<amount> <Dr/Cr>"`, e.g. "1,000.00 Dr". */
data class LedgerStatementRowUi(
    val row: LedgerStatementRow,
    val voucherTypeEnum: VoucherType = row.voucherType
) {
    val formattedRunningBalance: String
        get() = "${row.runningBalance.formatPlain()} ${if (row.balanceType == com.example.accounting.core.common.DrCr.DEBIT) "Dr" else "Cr"}"
}

/** Generic bulk-selection wrapper for a table screen that needs multi-row selection before a bulk
 * action/export (Day Book, Outstanding Receivables/Payables) - wraps any row type [T] without that
 * row's own domain model needing to know selection exists. */
data class SelectableItem<T>(
    val data: T,
    val isSelected: Boolean = false
)

/**
 * Expandable Trial Balance tree node - real Debit/Credit group subtotals ([totalDebit]/
 * [totalCredit], summed straight from [GroupBalanceNode]'s own already-computed paise figures,
 * never recomputed) plus the individual ledger rows filed directly under this group ([ledgers] -
 * the [TrialBalanceReport.rows] whose own `groupId` matches this node, never a descendant group's
 * ledgers, so a ledger is never double-counted across two expanded nodes at once).
 *
 * P&L/Balance Sheet deliberately do NOT reuse this exact type: their own [FinancialStatementItem]
 * tree has a single signed [Money] amount per line, not a real Debit/Credit split, and no ledger
 * rows underneath a line - forcing `totalDebit`/`totalCredit`/`ledgers` onto that shape would mean
 * fabricating a Dr/Cr split that doesn't exist for a P&L line. [UiStatementNode] below is the
 * structurally-parallel (same depth/expand idea) but honest equivalent for that tree instead.
 */
data class UiGroupTreeNode(
    val groupId: String,
    val groupName: String,
    val depthLevel: Int,
    val totalDebit: Money,
    val totalCredit: Money,
    val isExpanded: Boolean = false,
    val children: List<UiGroupTreeNode> = emptyList(),
    val ledgers: List<TrialBalanceRow> = emptyList()
)

/** Builds the full [UiGroupTreeNode] tree from a [TrialBalanceReport] - call once per report load,
 * not per recomposition; toggling a node's expansion is a UI-state concern handled by whatever
 * screen renders this (e.g. a `Set<String>` of expanded group IDs it re-passes in). */
fun TrialBalanceReport.toUiGroupTree(expandedGroupIds: Set<String> = emptySet()): List<UiGroupTreeNode> {
    val ledgersByGroup = rows.groupBy { it.groupId }
    fun build(node: GroupBalanceNode, depthLevel: Int): UiGroupTreeNode = UiGroupTreeNode(
        groupId = node.groupId,
        groupName = node.groupName,
        depthLevel = depthLevel,
        totalDebit = Money.fromPaise(node.totalDebitPaise),
        totalCredit = Money.fromPaise(node.totalCreditPaise),
        isExpanded = node.groupId in expandedGroupIds,
        children = node.children.map { build(it, depthLevel + 1) },
        ledgers = ledgersByGroup[node.groupId].orEmpty()
    )
    return groupHierarchy.map { build(it, 0) }
}

/** [FinancialStatementItem] tree equivalent of [UiGroupTreeNode] for P&L/Balance Sheet - a single
 * signed [amount] per line (that report family's real shape), never a fabricated Dr/Cr split. */
data class UiStatementNode(
    val title: String,
    val amount: Money,
    val depthLevel: Int,
    val isHighlight: Boolean,
    val isExpanded: Boolean = false,
    val children: List<UiStatementNode> = emptyList()
)

fun FinancialStatementItem.toUiStatementTree(depthLevel: Int = 0, expandedTitles: Set<String> = emptySet()): UiStatementNode = UiStatementNode(
    title = title,
    amount = amount,
    depthLevel = depthLevel,
    isHighlight = isHighlight,
    isExpanded = title in expandedTitles,
    children = subItems.map { it.toUiStatementTree(depthLevel + 1, expandedTitles) }
)

/** Qualitative health of a ratio figure for a colored status card (Green/Orange/Red) - a UI
 * convenience over [RatioAnalysisReport]'s own plain [Double] figures, never a second calculation
 * of the ratio itself. Thresholds are standard, widely-used financial-analysis rules of thumb, not
 * this app's own invention - always secondary to the real number, which every ratio card must still
 * show alongside the color. */
enum class RatioStatus { HEALTHY, WARNING, CRITICAL }

/** Current Ratio: >=1.5 healthy, 1.0-1.5 watch, <1.0 (can't comfortably cover current liabilities)
 * critical. */
val RatioAnalysisReport.currentRatioStatus: RatioStatus
    get() = when {
        currentRatio >= 1.5 -> RatioStatus.HEALTHY
        currentRatio >= 1.0 -> RatioStatus.WARNING
        else -> RatioStatus.CRITICAL
    }

/** Quick Ratio (acid-test): >=1.0 healthy, 0.5-1.0 watch, <0.5 critical. */
val RatioAnalysisReport.quickRatioStatus: RatioStatus
    get() = when {
        quickRatio >= 1.0 -> RatioStatus.HEALTHY
        quickRatio >= 0.5 -> RatioStatus.WARNING
        else -> RatioStatus.CRITICAL
    }

/** Debt-Equity: <=1.0 conservative/healthy, 1.0-2.0 watch, >2.0 highly leveraged/critical. */
val RatioAnalysisReport.debtEquityRatioStatus: RatioStatus
    get() = when {
        debtEquityRatio <= 1.0 -> RatioStatus.HEALTHY
        debtEquityRatio <= 2.0 -> RatioStatus.WARNING
        else -> RatioStatus.CRITICAL
    }

/** Gross/Net Profit margin percentages and Return on Capital Employed - a shared >=15% / 5-15% /
 * <5% band, the same generic "healthy/watch/thin" convention across all three; never a per-ratio
 * bespoke threshold this app can't justify. */
private fun marginStatus(percent: Double): RatioStatus = when {
    percent >= 15.0 -> RatioStatus.HEALTHY
    percent >= 5.0 -> RatioStatus.WARNING
    else -> RatioStatus.CRITICAL
}

val RatioAnalysisReport.grossProfitRatioStatus: RatioStatus get() = marginStatus(grossProfitRatioPercent)
val RatioAnalysisReport.netProfitRatioStatus: RatioStatus get() = marginStatus(netProfitRatioPercent)
val RatioAnalysisReport.returnOnCapitalEmployedStatus: RatioStatus get() = marginStatus(returnOnCapitalEmployedPercent)

/** Operating Ratio is a COST ratio (operating cost as % of revenue) - lower is better, the opposite
 * direction from the margin ratios above, so it gets its own thresholds: <=75% efficient/healthy,
 * 75-90% watch, >90% thin/no margin left. */
val RatioAnalysisReport.operatingRatioStatus: RatioStatus
    get() = when {
        operatingRatioPercent <= 75.0 -> RatioStatus.HEALTHY
        operatingRatioPercent <= 90.0 -> RatioStatus.WARNING
        else -> RatioStatus.CRITICAL
    }
