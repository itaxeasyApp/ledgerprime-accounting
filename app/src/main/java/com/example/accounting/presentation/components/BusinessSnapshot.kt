package com.example.accounting.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money
import com.example.ui.theme.IndigoTax

/**
 * Widget - the Dashboard's "Business Snapshot" section: one uniform 4-per-row grid for all 8
 * figures (Cash/Bank/Receivables/Payables/Sales-or-Income/Purchases-or-Expenditure/GST Payable/GST
 * Dashboard) - explicit follow-up ("take width of containers same on dashboard as they are showing
 * at quick action"): matches Quick Actions' own 4-per-row width exactly, and 8 real figures divide
 * into exactly two full rows with no empty filler slot needed. Every figure is a parameter - this
 * composable computes nothing; [DashboardScreen] still does that one-time filter/fold over
 * [com.example.accounting.domain.accounting.Ledger] balances and passes plain [Money] values in.
 * The standalone Profit/Loss (net profit) card was removed from this section entirely - Profit &
 * Loss remains a full report, reachable from Reports Center, never duplicated here.
 */
@Composable
fun BusinessSnapshot(
    cashBalance: Money,
    bankBalance: Money,
    receivables: Money,
    payables: Money,
    salesFigure: Money,
    purchasesFigure: Money,
    gstPayable: Money,
    income: Money?,
    expenditure: Money?,
    onOpenCash: () -> Unit,
    onOpenBank: () -> Unit,
    onOpenSales: () -> Unit,
    onOpenPurchases: () -> Unit,
    /** Dashboard-card-to-Report-Center deep link fix - each card now opens its OWN authoritative
     * Report Center report directly (see [AccountingViewModel.viewReport]'s report-menu keys),
     * never just the generic category menu a single shared "view reports" callback used to always
     * land every one of these cards on. */
    onViewReceivables: () -> Unit,
    onViewPayables: () -> Unit,
    onViewProfitLoss: () -> Unit,
    onViewGstSummary: () -> Unit,
    /** GST Dashboard's own entry point - moved here from its former standalone Quick Actions row
     * (per explicit instruction) so every dashboard destination lives in one place: a real figure
     * or a real destination, inside Business Snapshot's own container grid. */
    onOpenGstDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // One uniform 4-per-row grid, matching Quick Actions' own card width exactly - 8 real
        // figures split evenly into two full rows, no empty filler slot needed.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Cash", cashBalance, "Tap to view", Icons.Default.Payments, MaterialTheme.colorScheme.primary, Modifier.weight(1f).clickable { onOpenCash() })
            StatCard("Bank", bankBalance, "Tap to view", Icons.Default.AccountBalance, MaterialTheme.colorScheme.primary, Modifier.weight(1f).clickable { onOpenBank() })
            ReceiptSummary(receivables, Modifier.weight(1f)) { onViewReceivables() }
            PaymentSummary(payables, Modifier.weight(1f)) { onViewPayables() }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // SERVICE-mode audit fix - a SERVICE company has no Sales/Purchase concept (see
            // [com.example.accounting.domain.company.BusinessType]); [income]/[expenditure] are
            // only ever non-null for a SERVICE company (see DashboardScreen), so that alone
            // decides which pair of cards fills the trade-figure slot - never a second flag.
            if (income != null) IncomeSummary(income, Modifier.weight(1f), onViewProfitLoss) else SalesSummary(salesFigure, Modifier.weight(1f), onOpenSales)
            if (expenditure != null) ExpenditureSummary(expenditure, Modifier.weight(1f), onViewProfitLoss) else PurchaseSummary(purchasesFigure, Modifier.weight(1f), onOpenPurchases)
            StatCard("GST Payable", gstPayable, "Net position", Icons.Default.AccountBalance, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f).clickable { onViewGstSummary() })
            StatCard("GST Dashboard", gstPayable, "File & track returns", Icons.Default.Receipt, IndigoTax, Modifier.weight(1f).clickable { onOpenGstDashboard() })
        }
    }
}
