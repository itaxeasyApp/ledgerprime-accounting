package com.example.accounting.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money

/**
 * Widget - the Dashboard's "Business Snapshot" section: a prominent Cash/Bank row plus a compact
 * 3-per-row grid of the remaining figures (Receivables/Payables/Sales-or-Income/Purchases-or-
 * Expenditure/GST Payable) - every card smaller/tighter than the previous 2-per-row layout, per the
 * dashboard-density pass this section went through. Every figure is a parameter - this composable
 * computes nothing; [DashboardScreen] still does that one-time filter/fold over
 * [com.example.accounting.domain.accounting.Ledger] balances and passes plain [Money] values in.
 * The standalone Profit/Loss (net profit) card was removed from this section entirely - Profit &
 * Loss remains a full report, reachable from Reports Center, just no longer duplicated here as a
 * dashboard tile.
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Hero row - the two liquid-funds figures stay full-width/2-column, the most important
        // numbers on the whole dashboard.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Cash", cashBalance, "Tap to view", Icons.Default.Payments, MaterialTheme.colorScheme.primary, Modifier.weight(1f).clickable { onOpenCash() })
            StatCard("Bank", bankBalance, "Tap to view", Icons.Default.AccountBalance, MaterialTheme.colorScheme.primary, Modifier.weight(1f).clickable { onOpenBank() })
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Everything else is secondary detail - a compact 3-per-row grid keeps each container small,
        // matching this pass's "smaller containers" requirement.
        // SERVICE-mode audit fix - a SERVICE company has no Sales/Purchase concept (see
        // [com.example.accounting.domain.company.BusinessType]); [income]/[expenditure] are only
        // ever non-null for a SERVICE company (see DashboardScreen), so that alone decides which
        // pair of cards fills the trade-figure slot - never a second flag.
        val tradeFirst: @Composable () -> Unit = if (income != null) { { IncomeSummary(income, Modifier.weight(1f), onViewProfitLoss) } } else { { SalesSummary(salesFigure, Modifier.weight(1f), onOpenSales) } }
        val tradeSecond: @Composable () -> Unit = if (expenditure != null) { { ExpenditureSummary(expenditure, Modifier.weight(1f), onViewProfitLoss) } } else { { PurchaseSummary(purchasesFigure, Modifier.weight(1f), onOpenPurchases) } }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReceiptSummary(receivables, Modifier.weight(1f)) { onViewReceivables() }
            PaymentSummary(payables, Modifier.weight(1f)) { onViewPayables() }
            tradeFirst()
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tradeSecond()
            StatCard("GST Payable", gstPayable, "Net position", Icons.Default.AccountBalance, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f).clickable { onViewGstSummary() })
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
