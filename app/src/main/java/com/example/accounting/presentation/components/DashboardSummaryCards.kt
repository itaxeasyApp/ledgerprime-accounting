package com.example.accounting.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.accounting.core.common.Money

/**
 * Phase UI-03 (Dashboard decomposition): named, single-purpose [StatCard] wrappers replacing the
 * Business Snapshot section's previously inline `StatCard(...)` calls in `DashboardScreen.kt`.
 * Each wrapper fixes only its own icon/color/subtitle identity - the [amount] itself is always a
 * parameter, always an already-engine-computed figure the caller already has (`profitAndLoss`/
 * `incomeAndExpenditure`/`receivablesReport`/etc. on `AccountingUiState`); nothing here sums or
 * recalculates anything.
 *
 * [ReceiptSummary]/[PaymentSummary] naming note: no report anywhere computes a "total Receipt/
 * Payment vouchers this year" figure today - building that sum here would be exactly the
 * UI-side accounting calculation this app's own rules forbid, and adding it to the engine is a
 * separate, bigger change than a components-only phase should make. These two therefore display
 * the closest already-computed figures that exist - Receivables/Payables balances - labeled
 * accordingly on screen (business-correct terms), while keeping the function names requested.
 */
@Composable
fun SalesSummary(amount: Money, modifier: Modifier = Modifier, onClick: () -> Unit) = StatCard(
    // "container width height are different... two are small" - StatCard skips the subtitle
    // line entirely (no reserved height) when blank, so Sales/Purchases rendered visibly shorter
    // than every other Business Snapshot card. "Tap to view" matches Cash/Bank's own wording -
    // same kind of drill-down card - restoring the grid's uniform card height.
    title = "Sales", amount = amount, subtitle = "Tap to view",
    icon = Icons.Default.ReceiptLong, iconTint = MaterialTheme.colorScheme.primary,
    modifier = modifier.clickable(onClick = onClick)
)

@Composable
fun PurchaseSummary(amount: Money, modifier: Modifier = Modifier, onClick: () -> Unit) = StatCard(
    title = "Purchases", amount = amount, subtitle = "Tap to view",
    icon = Icons.Default.ShoppingCart, iconTint = MaterialTheme.colorScheme.primary,
    modifier = modifier.clickable(onClick = onClick)
)

@Composable
fun ReceiptSummary(amount: Money, modifier: Modifier = Modifier, onClick: () -> Unit) = StatCard(
    title = "Receivables", amount = amount, subtitle = "You are owed",
    icon = Icons.Default.ArrowDownward, iconTint = MaterialTheme.colorScheme.secondary,
    modifier = modifier.clickable(onClick = onClick)
)

@Composable
fun PaymentSummary(amount: Money, modifier: Modifier = Modifier, onClick: () -> Unit) = StatCard(
    title = "Payables", amount = amount, subtitle = "You owe",
    icon = Icons.Default.ArrowUpward, iconTint = MaterialTheme.colorScheme.error,
    modifier = modifier.clickable(onClick = onClick)
)

/** Only meaningful for [com.example.accounting.domain.company.BusinessType.SERVICE] companies -
 * the gross figure behind `IncomeExpenditureReport.income`, already computed by the engine,
 * previously never displayed anywhere (only the net `surplusOrDeficit` was shown). */
@Composable
fun IncomeSummary(amount: Money, modifier: Modifier = Modifier, onClick: () -> Unit) = StatCard(
    title = "Income", amount = amount, subtitle = "Tap to view",
    icon = Icons.Default.ArrowDownward, iconTint = MaterialTheme.colorScheme.secondary,
    modifier = modifier.clickable(onClick = onClick)
)

@Composable
fun ExpenditureSummary(amount: Money, modifier: Modifier = Modifier, onClick: () -> Unit) = StatCard(
    title = "Expenditure", amount = amount, subtitle = "Tap to view",
    icon = Icons.Default.ArrowUpward, iconTint = MaterialTheme.colorScheme.error,
    modifier = modifier.clickable(onClick = onClick)
)
