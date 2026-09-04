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
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money

/**
 * Widget - the Dashboard's "Business Snapshot" section: cash/bank balances plus the
 * [SalesSummary]/[PurchaseSummary]/[ReceiptSummary]/[PaymentSummary]/[IncomeSummary]/
 * [ExpenditureSummary] widgets, composed in one place. Every figure is a parameter - this
 * composable computes nothing; [DashboardScreen] still does that one-time filter/fold over
 * [com.example.accounting.domain.accounting.Ledger] balances and passes plain [Money] values in.
 * Extracted from `DashboardScreen` (Phase UI-04) so the screen's body reads as a composition of
 * named sections rather than one long inline `Column`.
 */
@Composable
fun BusinessSnapshot(
    cashBalance: Money,
    bankBalance: Money,
    receivables: Money,
    payables: Money,
    salesFigure: Money,
    purchasesFigure: Money,
    netProfit: Money,
    netProfitLabel: String,
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Cash", cashBalance, "Tap to view", Icons.Default.Payments, MaterialTheme.colorScheme.primary, Modifier.weight(1f).clickable { onOpenCash() })
            StatCard("Bank", bankBalance, "Tap to view", Icons.Default.AccountBalance, MaterialTheme.colorScheme.primary, Modifier.weight(1f).clickable { onOpenBank() })
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReceiptSummary(receivables, Modifier.weight(1f)) { onViewReceivables() }
            PaymentSummary(payables, Modifier.weight(1f)) { onViewPayables() }
        }
        // SERVICE-mode audit fix - a SERVICE company has no Sales/Purchase concept (see
        // [com.example.accounting.domain.company.BusinessType]); showing this row alongside the
        // Income/Expenditure row below it would duplicate the same figures under contradictory
        // labels. [income]/[expenditure] are only ever non-null for a SERVICE company (see
        // DashboardScreen), so that alone decides which row this card shows - never a second flag.
        if (income == null && expenditure == null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SalesSummary(salesFigure, Modifier.weight(1f)) { onOpenSales() }
                PurchaseSummary(purchasesFigure, Modifier.weight(1f)) { onOpenPurchases() }
            }
        }
        if (income != null && expenditure != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IncomeSummary(income, Modifier.weight(1f)) { onViewProfitLoss() }
                ExpenditureSummary(expenditure, Modifier.weight(1f)) { onViewProfitLoss() }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(netProfitLabel, netProfit, "Current Financial Year", Icons.Default.TrendingUp, MaterialTheme.colorScheme.secondary, Modifier.weight(1f).clickable { onViewProfitLoss() })
            StatCard("GST Payable", gstPayable, "Net position", Icons.Default.AccountBalance, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f).clickable { onViewGstSummary() })
        }
    }
}
