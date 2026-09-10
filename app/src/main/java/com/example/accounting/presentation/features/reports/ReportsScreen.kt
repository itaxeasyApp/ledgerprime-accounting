package com.example.accounting.presentation.features.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.reports.BalanceSheetReport
import com.example.accounting.domain.reports.GSTSummaryReport
import com.example.accounting.domain.reports.IncomeExpenditureReport
import com.example.accounting.domain.reports.ProfitAndLossReport
import com.example.accounting.domain.reports.TrialBalanceReport
import com.example.accounting.domain.reports.TrialBalanceRow
import com.example.accounting.presentation.viewmodel.AccountingUiState

@Composable
fun ReportsScreen(
    uiState: AccountingUiState,
    onRefreshReports: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedReportTab by remember { mutableIntStateOf(0) }
    val isService = uiState.currentCompany?.businessType == BusinessType.SERVICE
    val tabs = listOf("Trial Balance", if (isService) "Income & Expenditure" else "Profit & Loss", "Balance Sheet", "GST Center")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Report Type Tabs
        TabRow(
            selectedTabIndex = selectedReportTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedReportTab == index,
                    onClick = { selectedReportTab = index },
                    text = { Text(title, maxLines = 1, fontSize = 12.sp, fontWeight = if (selectedReportTab == index) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Document-style header (explicit design instruction) - report name centered, the
        // selected Business/User Profile name, the Financial Year, then a divider - identical
        // shape on every report tab, sourced from the same already-loaded uiState fields the rest
        // of this screen already uses (never a second company/FY lookup).
        com.example.accounting.presentation.components.ReportDocumentHeader(
            reportName = tabs[selectedReportTab],
            businessName = uiState.businessProfile?.businessName?.ifBlank { null } ?: uiState.currentCompany?.name ?: "My Business",
            financialYearLabel = uiState.currentFinancialYear?.fyCode?.let { "Financial Year: $it" } ?: "Financial Year: --"
        )

        when (selectedReportTab) {
            0 -> TrialBalanceView(report = uiState.trialBalance)
            1 -> if (isService) {
                IncomeAndExpenditureView(report = uiState.incomeAndExpenditure)
            } else {
                ProfitAndLossView(report = uiState.profitAndLoss)
            }
            2 -> BalanceSheetView(report = uiState.balanceSheet)
            3 -> GSTCenterView(report = uiState.gstSummary)
        }
    }
}

/** One renderable row of the Trial Balance's expandable group tree - a UI-only flattening of
 * [UiGroupTreeNode] (from `ReportUiModels.kt`) into what a `LazyColumn` can actually render, kept
 * in this file (not `ReportUiModels.kt`) since "how to flatten a tree for one specific screen's
 * LazyColumn" is that screen's own concern, not a general-purpose report-data-shaping concern. */
private sealed class TrialBalanceVisibleRow {
    abstract val key: String
    data class Group(val node: UiGroupTreeNode) : TrialBalanceVisibleRow() {
        override val key: String = "group_${node.groupId}"
    }
    data class Ledger(val row: TrialBalanceRow, val depthLevel: Int) : TrialBalanceVisibleRow() {
        override val key: String = "ledger_${row.ledgerId}"
    }
}

/** Recursively drops a group (and everything under it) once it has zero Debit AND zero Credit
 * across itself and every descendant - the same "hide empty" rule the old flat list already
 * applied per-ledger, now applied per-group too so an entirely-unused Primary Group never shows as
 * an empty, un-expandable row. */
private fun UiGroupTreeNode.isEffectivelyEmpty(): Boolean =
    totalDebit.paise == 0L && totalCredit.paise == 0L && ledgers.isEmpty() && children.all { it.isEffectivelyEmpty() }

private fun UiGroupTreeNode.flattenVisible(): List<TrialBalanceVisibleRow> {
    if (isEffectivelyEmpty()) return emptyList()
    val self = listOf<TrialBalanceVisibleRow>(TrialBalanceVisibleRow.Group(this))
    if (!isExpanded) return self
    val visibleLedgers = ledgers.filter { it.closingDebit.isPositive || it.closingCredit.isPositive }
        .map { TrialBalanceVisibleRow.Ledger(it, depthLevel + 1) }
    val visibleChildren = children.flatMap { it.flattenVisible() }
    return self + visibleLedgers + visibleChildren
}

/**
 * Trial Balance safety pass - [generateTrialBalance][com.example.accounting.data.repository.AccountingRepository.generateTrialBalance]
 * used to throw before ever returning a report when Debit != Credit, which meant the ONE report
 * whose entire purpose is to reveal such an imbalance could never actually be displayed - and
 * because [generateProfitAndLoss][com.example.accounting.data.repository.AccountingRepository.generateProfitAndLoss]
 * calls it internally, that imbalance took P&L/Sales/Purchases figures down with it too, even
 * though they have nothing to do with the unrelated ledger causing it. The repository now always
 * returns the report; this view now always shows a clear Balanced/Out of Balance status strip
 * (never just a banner that appears only on failure), so the difference is visible, not hidden.
 */
@Composable
fun TrialBalanceView(report: TrialBalanceReport?) {
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating Trial Balance...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // Zoom feature - scales only the dense row text (never the summary strip/title), same
    // ZOOM_STEPS scale the GST tables already use. Real reflow (font size, not a paint-only
    // transform) - LazyColumn accommodates the resulting taller rows natively, same as any other
    // variable-height item. Self-contained (own remembered state) so every existing caller of
    // this composable keeps compiling and rendering unchanged by default.
    var zoomIndex by remember { mutableIntStateOf(1) }
    val textScale = ZOOM_STEPS[zoomIndex]
    val rowNamePaint = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.bodySmall.fontSize * textScale)
    val rowGroupPaint = MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * textScale)
    val rowAmountPaint = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize * textScale)

    // Phase 7J Reports integration - real expandable group hierarchy (ReportUiModels.kt's
    // UiGroupTreeNode/toUiGroupTree(), built from this same report's own already-computed
    // groupHierarchy - never a second aggregation). Starts fully collapsed (top-level Primary
    // Groups only) to keep the same low-density first impression the old flat list had; expanding
    // a group reveals its own ledgers and child groups. Empty groups/ledgers (both zero) are
    // dropped entirely, matching the old flat list's own `isPositive` filter.
    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    val visibleRows = remember(report, expandedGroupIds) {
        report.toUiGroupTree(expandedGroupIds).flatMap { it.flattenVisible() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trial Balance", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(report.financialYearCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TrialBalanceStatusStrip(report)
        Spacer(modifier = Modifier.height(10.dp))

        ZoomControlRow(zoomIndex) { zoomIndex = it }

        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Ledger", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.5f))
            Text("Debit", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            Text("Credit", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(visibleRows, key = { it.key }) { visible ->
                when (visible) {
                    is TrialBalanceVisibleRow.Group -> {
                        val node = visible.node
                        val hasContent = node.children.isNotEmpty() || node.ledgers.isNotEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (node.depthLevel * 16).dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
                                .let { if (hasContent) it.clickable { expandedGroupIds = if (node.isExpanded) expandedGroupIds - node.groupId else expandedGroupIds + node.groupId } else it },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(modifier = Modifier.weight(1.5f), verticalAlignment = Alignment.CenterVertically) {
                                if (hasContent) {
                                    Icon(
                                        imageVector = if (node.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(node.groupName, style = rowNamePaint.copy(fontWeight = FontWeight.Bold))
                            }
                            Text(
                                text = if (node.totalDebit.isPositive) node.totalDebit.formatPlain() else "--",
                                style = rowAmountPaint.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (node.totalCredit.isPositive) node.totalCredit.formatPlain() else "--",
                                style = rowAmountPaint.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    is TrialBalanceVisibleRow.Ledger -> {
                        val row = visible.row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (visible.depthLevel * 16 + 16).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.ledgerName, style = rowNamePaint, modifier = Modifier.weight(1.5f))
                            Text(
                                text = if (row.closingDebit.isPositive) row.closingDebit.formatPlain() else "--",
                                style = rowAmountPaint,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (row.closingCredit.isPositive) row.closingCredit.formatPlain() else "--",
                                style = rowAmountPaint,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            item {
                HorizontalDivider(thickness = 2.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.5f))
                    Text(report.totalClosingDebit.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary), modifier = Modifier.weight(1f))
                    Text(report.totalClosingCredit.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Always-visible Balanced/Out-of-Balance status strip - Total Debit, Total Credit and the exact
 * Difference are shown regardless of outcome (never only surfaced on failure), so a bookkeeper can
 * see at a glance both that the books balance AND the actual figures behind that fact. */
@Composable
private fun TrialBalanceStatusStrip(report: TrialBalanceReport) {
    val (bg, fg, label) = if (report.isBalanced) {
        Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "Balanced")
    } else {
        Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Out of Balance")
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (report.isBalanced) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)
                )
                Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = fg, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Total Debit", style = MaterialTheme.typography.labelSmall, color = fg); Text(report.totalClosingDebit.formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace), color = fg) }
                Column { Text("Total Credit", style = MaterialTheme.typography.labelSmall, color = fg); Text(report.totalClosingCredit.formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace), color = fg) }
                Column { Text("Difference", style = MaterialTheme.typography.labelSmall, color = fg); Text(report.difference.formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace), color = fg) }
            }
            if (!report.isBalanced) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Review recent entries - this most often means an opening balance was entered on one side only.",
                    style = MaterialTheme.typography.bodySmall, color = fg
                )
            }
        }
    }
}

/**
 * Reusable warning banner for every OTHER financial statement (P&L, Income & Expenditure, Balance
 * Sheet, GST Summary) - shown whenever the underlying [TrialBalanceReport] is out of balance, since
 * a statement built on unreliable underlying figures should say so rather than present numbers with
 * silent, unstated confidence. Never blocks the statement from rendering (the numbers themselves may
 * still be entirely correct - most Income/Expense figures are unaffected by an unrelated Balance-
 * Sheet-side ledger's incomplete opening entry) - it is a disclosure, not a gate.
 */
@Composable
fun TrialBalanceWarningBanner(trialBalance: TrialBalanceReport?) {
    if (trialBalance == null || trialBalance.isBalanced) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Trial Balance is out of balance by ${trialBalance.difference.formatPlain()} - this statement may be unreliable until it's corrected.",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun ProfitAndLossView(report: ProfitAndLossReport?, trialBalance: TrialBalanceReport? = null) {
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating Profit & Loss...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrialBalanceWarningBanner(trialBalance)
        // Trading Account (Gross Profit) Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Trading Account", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Sales & Invoiced Revenue", amount = report.salesRevenue, isPositive = true)
                ReportLineItem(label = "Direct Incomes", amount = report.directIncomes, isPositive = true)
                if (report.isInventoryAware) {
                    ReportLineItem(label = "Opening Stock", amount = report.openingStock, isPositive = false)
                    ReportLineItem(label = "Purchases", amount = report.purchases, isPositive = false)
                    ReportLineItem(label = "Closing Stock", amount = report.closingStock, isPositive = true)
                    ReportLineItem(label = "Cost of Goods Sold", amount = report.cogs, isPositive = false)
                } else {
                    ReportLineItem(label = "Purchases", amount = report.purchases, isPositive = false)
                }
                ReportLineItem(label = "Direct Expenses", amount = report.directExpenses, isPositive = false)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Gross Profit / (Loss)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(report.grossProfit.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                }
            }
        }

        // Operating & Indirect P&L (Net Profit) Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Profit & Loss Account", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Gross Profit Brought Forward", amount = report.grossProfit, isPositive = true)
                ReportLineItem(label = "Indirect / Other Incomes", amount = report.indirectIncomes, isPositive = true)
                ReportLineItem(label = "Indirect Operating & Admin Expenses", amount = report.indirectExpenses, isPositive = false)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (report.netProfit.isNegative) "Net Loss for the Year" else "Net Profit for the Year", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = report.netProfit.abs().formatPlain(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun IncomeAndExpenditureView(report: IncomeExpenditureReport?, trialBalance: TrialBalanceReport? = null) {
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating Income & Expenditure...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrialBalanceWarningBanner(trialBalance)
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Income & Expenditure Account", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Income", amount = report.income, isPositive = true)
                ReportLineItem(label = "Expenditure", amount = report.expenditure, isPositive = false)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (report.isSurplus) "Surplus for the Year" else "Deficit for the Year", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = report.surplusOrDeficit.abs().formatPlain(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BalanceSheetView(report: BalanceSheetReport?, trialBalance: TrialBalanceReport? = null) {
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating Balance Sheet...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // A Balance Sheet still throws its OWN, independent imbalance check (a real Assets =
        // Liabilities + Equity failure never reaches this view) - this banner is for the DIFFERENT,
        // non-fatal case where the underlying Trial Balance is out of balance elsewhere (some other
        // ledger's incomplete opening entry) while THIS statement's own identity still happens to hold.
        TrialBalanceWarningBanner(trialBalance)
        // Context header - the engine enforces Assets = Liabilities + Equity unconditionally
        // (AppError.BalanceSheetNotBalanced would have been thrown before a report reaches here).
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "As on ${report.asOfDate} (${report.financialYearCode})",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(12.dp)
            )
        }

        // Liabilities & Equity Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Liabilities & Capital", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Capital Accounts", amount = report.capitalAccounts)
                if (report.reservesAndSurplus.isPositive) {
                    ReportLineItem(label = "Reserves & Surplus", amount = report.reservesAndSurplus)
                }
                ReportLineItem(label = if (report.netProfitForYear.isNegative) "Net Loss (from P&L)" else "Net Profit (from P&L)", amount = report.netProfitForYear.abs())
                ReportLineItem(label = "Loans & Borrowings", amount = report.loansLiabilities)
                if (report.branchDivisions.isPositive) {
                    ReportLineItem(label = "Branch / Divisions", amount = report.branchDivisions)
                }
                ReportLineItem(label = "Current Liabilities & Creditors", amount = report.currentLiabilities)
                ReportLineItem(label = "Duties & Taxes (GST Payable)", amount = report.dutiesAndTaxesLiability)
                if (report.suspenseCredit.isPositive) {
                    ReportLineItem(label = "Suspense A/c (Control Credit)", amount = report.suspenseCredit)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Liabilities", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(report.totalLiabilities.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary))
                }
            }
        }

        // Assets Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Assets", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Fixed Assets & Equipment", amount = report.fixedAssets)
                if (report.investments.isPositive) {
                    ReportLineItem(label = "Investments", amount = report.investments)
                }
                if (report.stockInHand.isPositive) {
                    ReportLineItem(label = "Stock-in-Hand", amount = report.stockInHand)
                }
                ReportLineItem(label = "Sundry Debtors (Receivables)", amount = report.sundryDebtors)
                ReportLineItem(label = "Bank Accounts", amount = report.bankAccounts)
                ReportLineItem(label = "Cash in Hand", amount = report.cashInHand)
                ReportLineItem(label = "Other Current Assets", amount = report.currentAssets)
                if (report.gstRecoverable.isPositive) {
                    ReportLineItem(label = "Net GST Recoverable (Input Tax Credit)", amount = report.gstRecoverable)
                }
                if (report.miscExpensesAsset.isPositive) {
                    ReportLineItem(label = "Misc. Expenses (Asset)", amount = report.miscExpensesAsset)
                }
                if (report.suspenseDebit.isPositive) {
                    ReportLineItem(label = "Suspense A/c (Control Debit)", amount = report.suspenseDebit)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Assets", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(report.totalAssets.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
fun GSTCenterView(report: GSTSummaryReport?, trialBalance: TrialBalanceReport? = null) {
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating GST Returns Summary...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrialBalanceWarningBanner(trialBalance)
        // GSTR-1 Outward Supplies Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GSTR-1: Outward Supplies & Tax Liability", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Total Taxable Turnover", amount = report.totalTaxableOutward)
                ReportLineItem(label = "Output CGST", amount = report.totalCGSTOutward)
                ReportLineItem(label = "Output SGST", amount = report.totalSGSTOutward)
                ReportLineItem(label = "Output IGST", amount = report.totalIGSTOutward)
                if (report.totalCess.isPositive) {
                    ReportLineItem(label = "CESS", amount = report.totalCess)
                }

                // 13-point correctness pass, item 6 - GSTR-1 category breakdown of the same
                // taxable turnover/tax liability totals above; only shown when at least one bucket
                // is non-zero, so a company with no GST-relevant Sales/Notes this period sees no
                // empty "0.00" rows.
                val hasBreakdown = report.b2bTaxableOutward.isPositive || report.b2cTaxableOutward.isPositive ||
                    report.creditNoteTaxableOutward.isPositive || report.debitNoteTaxableOutward.isPositive
                if (hasBreakdown) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("By Category", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (report.b2bTaxableOutward.isPositive) {
                        ReportLineItem(label = "B2B Taxable Value (Tax ${report.b2bTaxOutward.formatPlain()})", amount = report.b2bTaxableOutward)
                    }
                    if (report.b2cTaxableOutward.isPositive) {
                        ReportLineItem(label = "B2C Taxable Value (Tax ${report.b2cTaxOutward.formatPlain()})", amount = report.b2cTaxableOutward)
                    }
                    if (report.creditNoteTaxableOutward.isPositive) {
                        ReportLineItem(label = "Credit Notes (Tax ${report.creditNoteTaxOutward.formatPlain()})", amount = report.creditNoteTaxableOutward)
                    }
                    if (report.debitNoteTaxableOutward.isPositive) {
                        ReportLineItem(label = "Debit Notes (Tax ${report.debitNoteTaxOutward.formatPlain()})", amount = report.debitNoteTaxableOutward)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Outward GST Liability", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(report.totalTaxOutward.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error))
                }
            }
        }

        // GSTR-3B Inward Supplies / ITC Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GSTR-3B: Input Tax Credit (ITC Available)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(10.dp))

                ReportLineItem(label = "Total Taxable Inward Purchases", amount = report.totalTaxableInward)
                ReportLineItem(label = "Input CGST Credit", amount = report.totalCGSTInwardITC)
                ReportLineItem(label = "Input SGST Credit", amount = report.totalSGSTInwardITC)
                ReportLineItem(label = "Input IGST Credit", amount = report.totalIGSTInwardITC)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Eligible ITC", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(report.totalTaxInwardITC.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                }
            }
        }

        // Net GST Payable Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Net GST Payable", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Tax Liability - Input Tax Credit", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = report.netTaxPayable.formatPlain(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
            }
        }

        if (report.totalCess.isPositive) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Net CESS Payable", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(report.netCessPayable.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
fun ReportLineItem(
    label: String,
    amount: Money,
    isPositive: Boolean? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = amount.formatPlain(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}
