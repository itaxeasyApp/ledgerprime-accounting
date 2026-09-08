package com.example.accounting.presentation.features.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.accounting.application.reports.HsnSacSummaryRow
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.reports.AgingBucket
import com.example.accounting.domain.reports.CashFlowReport
import com.example.accounting.domain.reports.OutstandingReport
import com.example.accounting.domain.reports.RatioAnalysisReport
import com.example.accounting.presentation.components.Amount
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.components.TableRow
import com.example.accounting.presentation.features.dashboard.VoucherSummaryCard
import com.example.accounting.presentation.viewmodel.AccountingUiState

private enum class ReportCategory(val label: String) { FINANCIAL("Financial"), SALES_PURCHASE("Sales/Purchase"), ACCOUNTS("Accounts"), GST("GST"), ANALYSIS("Analysis") }

/** Dashboard-card-to-Report-Center deep link fix - the one place that knows which category a
 * given report-menu key lives under, so a caller (Dashboard) only ever has to name the report
 * itself, never duplicate this screen's own category layout. */
private fun reportCategoryForKey(reportKey: String): ReportCategory? = when (reportKey) {
    "Trial Balance", "Profit & Loss", "Balance Sheet", "Cash Flow" -> ReportCategory.FINANCIAL
    "Sales Register", "Purchase Register", "Outstanding Receivables", "Outstanding Payables" -> ReportCategory.SALES_PURCHASE
    "Cash Book", "Bank Book", "Receipt Register", "Payment Register" -> ReportCategory.ACCOUNTS
    "GST Summary", "HSN/SAC Summary", "GST Return Dashboard" -> ReportCategory.GST
    "Ratio Analysis" -> ReportCategory.ANALYSIS
    else -> null
}

/**
 * Phase 7J UI: the Reports Center (bottom-nav item #5) - a category-selector landing screen per
 * the UX spec's Section 13, not a flat tab bar. Every figure shown comes from an existing
 * `ReportManagementService`/`AccountingRepository` call, already loaded into [uiState] - this
 * screen never recomputes anything; Sales/Purchase Register and Cash/Bank Book/Receipt/Payment
 * Register are UI-side *filters* over already-fetched voucher/invoice lists, not new calculations.
 */
@Composable
fun ReportsCenterScreen(
    uiState: AccountingUiState,
    onOpenDayBook: () -> Unit,
    onOpenAllLedgers: () -> Unit,
    /** Export/Share only ever fire for a [reportKey] this screen itself decided is genuinely
     * backed by [com.example.accounting.application.export.ExportManagementService] - Ledger
     * Statement/Day Book/Income & Expenditure have no `ExportType` today and never reach here. */
    onExportReport: (reportKey: String) -> Unit = {},
    onShareReport: (reportKey: String) -> Unit = {},
    onPrintReport: (reportKey: String) -> Unit = {},
    /** Refresh feature - re-runs the same [com.example.accounting.presentation.viewmodel.AccountingViewModel.refreshFinancialReports]
     * every ledger/voucher change already triggers reactively; this just gives the user their own
     * direct way to force it (e.g. right before Print/Download, for confidence the figures are
     * current) rather than only firing on data changes. */
    onRefreshReport: () -> Unit = {},
    gstReturnActions: GstReturnDashboardActions,
    /** Dashboard-card-to-Report-Center deep link fix - [AccountingUiState.reportsDeepLink], a
     * report-menu key to jump straight into (e.g. "Outstanding Receivables") instead of leaving
     * the user on the generic category menu a Dashboard card used to always land on. */
    deepLinkReportKey: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var category by remember { mutableStateOf<ReportCategory?>(null) }
    var pendingReportKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deepLinkReportKey) {
        val key = deepLinkReportKey ?: return@LaunchedEffect
        val targetCategory = reportCategoryForKey(key)
        if (targetCategory != null) {
            category = targetCategory
            pendingReportKey = key
        }
        onDeepLinkConsumed()
    }

    if (category == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { Text("Reports Center", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) }
            items(ReportCategory.entries) { cat ->
                SectionCard(onClick = { category = cat }, title = cat.label, trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }) {}
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { category = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(4.dp))
            Text(category!!.label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }

        // Consumed exactly once per category entry (each category composable seeds its own
        // internal reportKey state from this) - `remember(category)` re-evaluates only when the
        // category itself changes, so navigating back to this same category's menu by hand
        // afterward starts fresh instead of re-jumping to the same report forever.
        val initialReportKey = remember(category) { pendingReportKey.also { pendingReportKey = null } }

        when (category) {
            ReportCategory.FINANCIAL -> FinancialCategory(uiState, onExportReport, onShareReport, onPrintReport, onRefreshReport, initialReportKey)
            ReportCategory.SALES_PURCHASE -> SalesPurchaseCategory(uiState, initialReportKey)
            ReportCategory.ACCOUNTS -> AccountsCategory(uiState, onOpenDayBook, onOpenAllLedgers)
            ReportCategory.GST -> GstCategory(uiState, gstReturnActions, initialReportKey)
            ReportCategory.ANALYSIS -> AnalysisCategory(uiState)
            null -> {}
        }
    }
}

@Composable
private fun FinancialCategory(uiState: AccountingUiState, onExportReport: (String) -> Unit, onShareReport: (String) -> Unit, onPrintReport: (String) -> Unit, onRefreshReport: () -> Unit, initialReportKey: String? = null) {
    var reportKey by remember { mutableStateOf(initialReportKey) }
    if (reportKey == null) {
        ReportMenu(
            listOf("Trial Balance" to true, "Profit & Loss" to true, "Balance Sheet" to true, "Cash Flow" to true, "Fund Flow" to false)
        ) { reportKey = it }
        return
    }
    // Export/Share exist only for ExportManagementService.exportTrialBalance/exportProfitAndLoss/
    // exportBalanceSheet - Cash Flow has no ExportType, and a SERVICE company's "Profit & Loss"
    // menu item renders IncomeAndExpenditureView (below), for which no exportIncomeAndExpenditure
    // method exists either - so that combination is excluded too, never a button for an
    // unimplemented operation.
    val isServiceCompany = uiState.currentCompany?.businessType == BusinessType.SERVICE
    val supportsExportShare = reportKey == "Trial Balance" || reportKey == "Balance Sheet" || (reportKey == "Profit & Loss" && !isServiceCompany)
    // Print/PDF is a self-contained path (TabularPdfRenderer, never ExportManagementService), so it
    // covers the Service-company Income & Expenditure case too, unlike Export/Share above (JSON/CSV
    // only exist for the Trading-company Profit & Loss shape).
    val supportsPrint = reportKey == "Trial Balance" || reportKey == "Balance Sheet" || reportKey == "Profit & Loss"
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackRow(
            reportKey!!,
            onBack = { reportKey = null },
            actions = {
                IconButton(onClick = onRefreshReport) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                if (supportsPrint) {
                    IconButton(onClick = { onPrintReport(reportKey!!) }) {
                        Icon(Icons.Default.Print, contentDescription = "Print")
                    }
                }
                if (supportsExportShare) {
                    IconButton(onClick = { onExportReport(reportKey!!) }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export")
                    }
                    IconButton(onClick = { onShareReport(reportKey!!) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                }
            }
        )
        when (reportKey) {
            "Trial Balance" -> TrialBalanceView(report = uiState.trialBalance)
            "Profit & Loss" -> if (uiState.currentCompany?.businessType == BusinessType.SERVICE) {
                IncomeAndExpenditureView(report = uiState.incomeAndExpenditure, trialBalance = uiState.trialBalance)
            } else {
                ProfitAndLossView(report = uiState.profitAndLoss, trialBalance = uiState.trialBalance)
            }
            "Balance Sheet" -> BalanceSheetView(report = uiState.balanceSheet, trialBalance = uiState.trialBalance)
            "Cash Flow" -> CashFlowView(report = uiState.cashFlowReport)
        }
    }
}

@Composable
private fun CashFlowView(report: CashFlowReport?) {
    if (report == null) { EmptyReportState(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TableRow("Net Profit", money = report.netProfit) }
        item { TableRow("Change in Current Assets (excl. Cash)", money = report.changeInCurrentAssetsExcludingCash) }
        item { TableRow("Change in Current Liabilities", money = report.changeInCurrentLiabilities) }
        item { TableRow("Net Cash from Operating Activities", money = report.netCashFromOperatingActivities, emphasize = true) }
        item { TableRow("Opening Cash & Bank", money = report.openingCashAndBank) }
        item { TableRow("Closing Cash & Bank", money = report.closingCashAndBank) }
        item { TableRow("Net Change in Cash & Bank", money = report.netChangeInCashAndBank, emphasize = true) }
    }
}

@Composable
private fun SalesPurchaseCategory(uiState: AccountingUiState, initialReportKey: String? = null) {
    var reportKey by remember { mutableStateOf(initialReportKey) }
    if (reportKey == null) {
        ReportMenu(listOf("Sales Register" to true, "Purchase Register" to true, "Outstanding Receivables" to true, "Outstanding Payables" to true)) { reportKey = it }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackRow(reportKey!!, onBack = { reportKey = null })
        when (reportKey) {
            "Sales Register" -> VoucherRegisterList(uiState.vouchers.filter { it.voucherType == VoucherType.SALES })
            "Purchase Register" -> VoucherRegisterList(uiState.vouchers.filter { it.voucherType == VoucherType.PURCHASE })
            "Outstanding Receivables" -> OutstandingList(uiState.receivablesReport)
            "Outstanding Payables" -> OutstandingList(uiState.payablesReport)
        }
    }
}

@Composable
private fun AccountsCategory(uiState: AccountingUiState, onOpenDayBook: () -> Unit, onOpenAllLedgers: () -> Unit) {
    var reportKey by remember { mutableStateOf<String?>(null) }
    if (reportKey == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item { SectionCard(onClick = onOpenAllLedgers, title = "All Ledgers") {} }
            item { SectionCard(onClick = onOpenDayBook, title = "Day Book") {} }
            item { SectionCard(onClick = { reportKey = "Cash Book" }, title = "Cash Book") {} }
            item { SectionCard(onClick = { reportKey = "Bank Book" }, title = "Bank Book") {} }
            item { SectionCard(onClick = { reportKey = "Receipt Register" }, title = "Receipt Register") {} }
            item { SectionCard(onClick = { reportKey = "Payment Register" }, title = "Payment Register") {} }
        }
        return
    }
    val cashLedgerIds = remember(uiState.ledgers, uiState.groups) {
        val groupsById = uiState.groups.associateBy { it.groupId }
        uiState.ledgers.filter {
            StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
        }.map { it.ledgerId }.toSet()
    }
    val bankLedgerIds = remember(uiState.ledgers, uiState.groups) {
        val groupsById = uiState.groups.associateBy { it.groupId }
        uiState.ledgers.filter {
            StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById)
        }.map { it.ledgerId }.toSet()
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackRow(reportKey!!, onBack = { reportKey = null })
        when (reportKey) {
            "Cash Book" -> VoucherRegisterList(uiState.vouchers.filter { v -> v.items.any { it.ledgerId in cashLedgerIds } })
            "Bank Book" -> VoucherRegisterList(uiState.vouchers.filter { v -> v.items.any { it.ledgerId in bankLedgerIds } })
            "Receipt Register" -> VoucherRegisterList(uiState.vouchers.filter { it.voucherType == VoucherType.RECEIPT })
            "Payment Register" -> VoucherRegisterList(uiState.vouchers.filter { it.voucherType == VoucherType.PAYMENT })
        }
    }
}

@Composable
private fun GstCategory(uiState: AccountingUiState, gstReturnActions: GstReturnDashboardActions, initialReportKey: String? = null) {
    var reportKey by remember { mutableStateOf(initialReportKey) }
    if (reportKey == null) {
        // GST Return filing is a statutory GSTN concept - a company with no registered GSTIN
        // (Unregistered/Composition-without-a-number, same status this screen's own header
        // already shows) has nothing to file, so the Dashboard never has anything real for this
        // to open. GST Summary/HSN-SAC stay available regardless - they reflect this company's
        // own GST-rated Sale/Purchase activity, which can exist even without a formal GSTIN.
        val hasGstin = !uiState.currentCompany?.gstin.isNullOrBlank()
        ReportMenu(
            listOf("GST Summary" to true, "HSN/SAC Summary" to true, "GST Return Dashboard" to hasGstin),
            unavailableSubtitle = { "Requires a registered GSTIN" }
        ) { reportKey = it }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackRow(
            if (reportKey == "GST Return Dashboard" && uiState.selectedGstReturn != null)
                "${uiState.selectedGstReturn!!.returnType} - ${uiState.selectedGstReturn!!.periodKey}"
            else reportKey!!,
            onBack = {
                if (reportKey == "GST Return Dashboard" && uiState.selectedGstReturn != null) gstReturnActions.onClearSelection()
                else reportKey = null
            }
        )
        when (reportKey) {
            "GST Summary" -> GSTCenterView(report = uiState.gstSummary, trialBalance = uiState.trialBalance)
            "HSN/SAC Summary" -> HsnSacSummaryView(uiState.hsnSacSummary)
            "GST Return Dashboard" -> GstReturnDashboardView(
                uiState = uiState,
                onSelectPeriod = gstReturnActions.onSelectPeriod,
                onOpenReturn = gstReturnActions.onOpenReturn,
                onClearSelection = gstReturnActions.onClearSelection,
                onPrepare = gstReturnActions.onPrepare,
                onValidate = gstReturnActions.onValidate,
                onGenerateJson = gstReturnActions.onGenerateJson,
                onShareArtifact = gstReturnActions.onShareArtifact,
                onImportResponseFile = gstReturnActions.onImportResponseFile,
                onMarkFiled = gstReturnActions.onMarkFiled,
                onSubmitOnline = gstReturnActions.onSubmitOnline,
                onUpdateGstEnabled = gstReturnActions.onUpdateGstEnabled,
                onUpdateGstScheme = gstReturnActions.onUpdateGstScheme,
                onUpdateGstFilingFrequency = gstReturnActions.onUpdateGstFilingFrequency,
                onUpdateGstReturnPeriod = gstReturnActions.onUpdateGstReturnPeriod,
                onFinancialYearSelected = gstReturnActions.onFinancialYearSelected,
                onExportCsv = gstReturnActions.onExportCsv,
                onExportGstrJson = gstReturnActions.onExportGstrJson,
                onSetNilReturn = gstReturnActions.onSetNilReturn,
                onExportJson = gstReturnActions.onExportJson,
                onPreviewPdf = gstReturnActions.onPreviewPdf,
                onDownloadPdf = gstReturnActions.onDownloadPdf,
                onPrintPdf = gstReturnActions.onPrintPdf,
                onSharePdfSummary = gstReturnActions.onSharePdfSummary,
                onUpdateGstReminderEnabled = gstReturnActions.onUpdateGstReminderEnabled,
                onSaveProviderUsername = gstReturnActions.onSaveProviderUsername,
                getProviderUsername = gstReturnActions.getProviderUsername,
                onOpenSalesRegister = gstReturnActions.onOpenSalesRegister,
                onOpenLedgers = gstReturnActions.onOpenLedgers,
                onFixNow = gstReturnActions.onFixNow,
                onMarkProcessingManually = gstReturnActions.onMarkProcessingManually
            )
        }
    }
}

/** Groups the GST Return Dashboard's callbacks (Rule 33) so [ReportsCenterScreen]'s own signature
 * doesn't grow by nine individual parameters most callers never touch. */
data class GstReturnDashboardActions(
    val onSelectPeriod: (
        com.example.accounting.domain.taxation.gstreturn.GstQuarter,
        Int?,
        com.example.accounting.domain.taxation.gstreturn.GstReturnType,
        com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity,
        com.example.accounting.domain.taxation.gstreturn.GstFilingMode
    ) -> Unit,
    val onOpenReturn: (String) -> Unit,
    val onClearSelection: () -> Unit,
    val onPrepare: () -> Unit,
    val onValidate: () -> Unit,
    val onGenerateJson: () -> Unit,
    val onShareArtifact: (String) -> Unit,
    val onImportResponseFile: () -> Unit,
    val onMarkFiled: (String) -> Unit,
    val onSubmitOnline: () -> Unit,
    val onUpdateGstEnabled: (Boolean) -> Unit,
    val onUpdateGstScheme: (com.example.accounting.domain.taxation.gstreturn.GstScheme) -> Unit,
    val onUpdateGstFilingFrequency: (com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity) -> Unit,
    /** GST Settings refactor - Top GST Period Row. See
     * [com.example.accounting.presentation.viewmodel.AccountingViewModel.updateGstReturnPeriod]. */
    val onUpdateGstReturnPeriod: (Int?, com.example.accounting.domain.taxation.gstreturn.GstQuarter?) -> Unit = { _, _ -> },
    /** GST Settings refactor - the same app-wide Financial Year switch the top bar's own FY
     * dropdown already uses ([com.example.accounting.presentation.viewmodel.AccountingViewModel.switchFinancialYear]) -
     * never a second, independent "which FY is GST Settings looking at" concept. */
    val onFinancialYearSelected: (com.example.accounting.domain.financialyear.FinancialYear) -> Unit = {},
    /** Phase 8A, Part 2 - CSV/GST JSON export+share, alongside the existing plain-JSON
     * onGenerateJson/onShareArtifact pair. Both build and share a file the same way
     * [AccountingViewModel.exportReportAndShare] already does for Trial Balance/P&L/Balance Sheet. */
    val onExportCsv: () -> Unit = {},
    val onExportGstrJson: () -> Unit = {},
    /** Phase 8A, Part 2 - see [com.example.accounting.presentation.viewmodel.AccountingViewModel.setSelectedGstReturnNil]. */
    val onSetNilReturn: (Boolean) -> Unit = {},
    /** Phase 8A, Part 2 - plain readable-JSON export, alongside [onExportCsv]/[onExportGstrJson]. */
    val onExportJson: () -> Unit = {},
    /** Phase 8A, Part 2 - PDF preview/download/print/share for the selected return's section-wise
     * summary. See [com.example.accounting.presentation.viewmodel.AccountingViewModel.renderGstReturnPdf]'s
     * own KDoc for why these are four genuinely distinct actions, not duplicates of each other. */
    val onPreviewPdf: () -> Unit = {},
    val onDownloadPdf: () -> Unit = {},
    val onPrintPdf: () -> Unit = {},
    val onSharePdfSummary: () -> Unit = {},
    /** Phase 8A, Part 2 - see [com.example.accounting.presentation.viewmodel.AccountingViewModel.updateGstReminderEnabled]. */
    val onUpdateGstReminderEnabled: (Boolean) -> Unit = {},
    /** Reference-flow Authenticate step - the user's own GST-provider username, stored/read via
     * the existing per-company [com.example.accounting.core.security.SecureStorage], never a
     * shared or hardcoded credential. See
     * [com.example.accounting.presentation.viewmodel.AccountingViewModel.saveGstProviderUsername]. */
    val onSaveProviderUsername: (String) -> Unit = {},
    val getProviderUsername: () -> String = { "" },
    /** Dashboard step's Quick Actions - real existing destinations, mirroring
     * [com.example.accounting.presentation.viewmodel.AccountingViewModel.viewReport]/`navigateTo`'s
     * own pattern, never a tile with no real target. */
    val onOpenSalesRegister: () -> Unit = {},
    val onOpenLedgers: () -> Unit = {},
    /** Error Details' "Fix Now" - see [com.example.accounting.presentation.features.reports.GstReturnDashboardView]'s own KDoc on this same param. */
    val onFixNow: (String) -> Unit = {},
    /** Online mode, filed manually at gst.gov.in - see
     * [com.example.accounting.presentation.viewmodel.AccountingViewModel.markSelectedGstReturnProcessingManually]'s own KDoc. */
    val onMarkProcessingManually: () -> Unit = {},
    /** More tab (screen 8) - real existing app-level destinations, never a new screen. */
    val onNavigateToProfile: () -> Unit = {},
    val onNavigateToSettings: () -> Unit = {},
    val onNavigateToSupport: () -> Unit = {},
    val onLogoutCloudSync: () -> Unit = {},
    val onActiveBottomTabChanged: (String) -> Unit = {}
)

@Composable
private fun HsnSacSummaryView(rows: List<HsnSacSummaryRow>) {
    if (rows.isEmpty()) { EmptyReportState(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        items(rows, key = { it.hsnSacCode }) { row ->
            SectionCard(title = row.hsnSacCode, subtitle = "${row.transactionCount} transaction(s)") {
                TableRow("Outward Taxable", money = row.outwardTaxableAmount)
                TableRow("Inward Taxable", money = row.inwardTaxableAmount)
                TableRow("CGST + SGST + IGST", money = row.totalCgst + row.totalSgst + row.totalIgst)
                if (row.totalCess.isPositive) TableRow("CESS", money = row.totalCess)
            }
        }
    }
}

@Composable
private fun AnalysisCategory(uiState: AccountingUiState) {
    var reportKey by remember { mutableStateOf<String?>(null) }
    if (reportKey == null) {
        ReportMenu(listOf("Ratio Analysis" to true, "CMA" to false, "Advanced Reports" to false)) { reportKey = it }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackRow(reportKey!!, onBack = { reportKey = null })
        if (reportKey == "Ratio Analysis") RatioAnalysisView(uiState.ratioAnalysisReport)
    }
}

@Composable
private fun RatioAnalysisView(report: RatioAnalysisReport?) {
    if (report == null) { EmptyReportState(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TableRow("Current Ratio", "%.2f".format(report.currentRatio)) }
        item { TableRow("Quick Ratio", "%.2f".format(report.quickRatio)) }
        item { TableRow("Debt-Equity Ratio", "%.2f".format(report.debtEquityRatio)) }
        item { TableRow("Gross Profit Ratio", "%.2f%%".format(report.grossProfitRatioPercent)) }
        item { TableRow("Net Profit Ratio", "%.2f%%".format(report.netProfitRatioPercent)) }
        item { TableRow("Operating Ratio", "%.2f%%".format(report.operatingRatioPercent)) }
        item { TableRow("Return on Capital Employed", "%.2f%%".format(report.returnOnCapitalEmployedPercent)) }
    }
}

private fun AgingBucket.displayLabel(): String = when (this) {
    AgingBucket.CURRENT -> "Not yet due"
    AgingBucket.DAYS_1_30 -> "1-30 days overdue"
    AgingBucket.DAYS_31_60 -> "31-60 days overdue"
    AgingBucket.DAYS_61_90 -> "61-90 days overdue"
    AgingBucket.DAYS_90_PLUS -> "Over 90 days overdue"
}

@Composable
private fun OutstandingList(report: OutstandingReport?) {
    if (report == null || report.rows.isEmpty()) { EmptyReportState(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TableRow("Total Outstanding", money = report.totalOutstanding, emphasize = true) }
        items(report.rows, key = { it.invoiceId }) { row ->
            SectionCard(
                title = row.partyName,
                subtitle = "${row.voucherNumber} • ${row.agingBucket.displayLabel()}",
                trailing = { Amount(row.outstandingAmount, style = MaterialTheme.typography.titleSmall, emphasize = true) }
            ) {}
        }
    }
}

@Composable
private fun VoucherRegisterList(vouchers: List<Voucher>) {
    if (vouchers.isEmpty()) { EmptyReportState(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        items(vouchers.sortedByDescending { it.date }, key = { it.voucherId }) { voucher ->
            VoucherSummaryCard(voucher = voucher, onClick = {})
        }
    }
}

@Composable
private fun ReportMenu(reports: List<Pair<String, Boolean>>, unavailableSubtitle: (String) -> String = { "Coming soon" }, onSelect: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        items(reports) { (name, available) ->
            SectionCard(
                onClick = if (available) ({ onSelect(name) }) else null,
                title = name,
                subtitle = if (!available) unavailableSubtitle(name) else null
            ) {}
        }
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // weight(1f) + ellipsis: a long report title must never push the trailing action icons
        // (when present) off a narrow (320dp-360dp) screen - the same overflow class of bug found
        // and fixed in AppTopBar.kt earlier this session.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        actions()
    }
}

@Composable
private fun EmptyReportState() {
    Text("No data yet for this report.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}
