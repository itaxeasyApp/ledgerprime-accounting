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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
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

@Composable
fun TrialBalanceView(report: TrialBalanceReport?) {
    if (report == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating Trial Balance...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Context header - the engine enforces Total Debit = Total Credit unconditionally
        // (throwing a structured error before a report ever reaches here if it doesn't - see
        // AppError.TrialBalanceNotBalanced); the UI presents figures, not the internal invariant.
        // An imbalance banner only appears in the (should-never-happen) case one slips through.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trial Balance", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(report.financialYearCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!report.isBalanced) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Imbalance detected - difference ${report.difference.formatPlain()}. Please review recent entries.",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

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
            items(report.rows.filter { it.closingDebit.isPositive || it.closingCredit.isPositive }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.5f)) {
                        Text(row.ledgerName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(row.groupName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = if (row.closingDebit.isPositive) row.closingDebit.formatPlain() else "--",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (row.closingCredit.isPositive) row.closingCredit.formatPlain() else "--",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

@Composable
fun ProfitAndLossView(report: ProfitAndLossReport?) {
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
fun IncomeAndExpenditureView(report: IncomeExpenditureReport?) {
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
fun BalanceSheetView(report: BalanceSheetReport?) {
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
fun GSTCenterView(report: GSTSummaryReport?) {
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
