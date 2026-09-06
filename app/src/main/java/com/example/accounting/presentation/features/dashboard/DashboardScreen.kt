package com.example.accounting.presentation.features.dashboard

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.presentation.components.BusinessSnapshot
import com.example.accounting.presentation.components.QuickActionSpec
import com.example.accounting.presentation.components.QuickActions
import com.example.accounting.presentation.components.recentTransactionsSection
import com.example.accounting.presentation.viewmodel.AccountingUiState

/**
 * Phase 7J UI: the Home tab (bottom-nav item #1) rebuilt as the "Business Cockpit" per the UX
 * spec's Section 2 - a compact reflection of business state via actionable widgets, never a report
 * dump. Every widget amount comes straight from an already-loaded engine report/ledger balance in
 * [uiState] - zero UI-side summation anywhere in this file.
 */
@Composable
fun DashboardScreen(
    uiState: AccountingUiState,
    onOpenCreateVoucher: (VoucherType) -> Unit,
    onVoucherClick: (Voucher) -> Unit,
    onViewAllDayBook: () -> Unit,
    /** Dashboard-card-to-Report-Center deep link fix - replaces the single, generic
     * `onViewReports` every card used to share (which always landed on the Report Center's
     * top-level category menu, never the specific report) with one callback per authoritative
     * report, each invoking [com.example.accounting.presentation.viewmodel.AccountingViewModel.viewReport]
     * with that report's own Report Center menu key. */
    onViewReceivables: () -> Unit,
    onViewPayables: () -> Unit,
    onViewProfitLoss: () -> Unit,
    onViewGstSummary: () -> Unit,
    onOpenCash: () -> Unit,
    onOpenBank: () -> Unit,
    onOpenSales: () -> Unit,
    onOpenPurchases: () -> Unit,
    onAddCustomer: () -> Unit,
    onAddSupplier: () -> Unit,
    onAddItem: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isService = uiState.currentCompany?.businessType == BusinessType.SERVICE
    val salesFigure = uiState.profitAndLoss?.salesRevenue ?: Money.ZERO
    val purchasesFigure = uiState.profitAndLoss?.purchases ?: Money.ZERO
    val receivables = uiState.receivablesReport?.totalOutstanding ?: (uiState.balanceSheet?.sundryDebtors ?: Money.ZERO)
    val payables = uiState.payablesReport?.totalOutstanding ?: (uiState.balanceSheet?.currentLiabilities ?: Money.ZERO)
    val gstPayable = uiState.gstSummary?.netTaxPayable ?: Money.ZERO
    val groupsById = uiState.groups.associateBy { it.groupId }
    val cashBalance = uiState.ledgers.filter {
        StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
            StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
    }.fold(Money.ZERO) { acc, l -> acc + l.currentBalance }
    val bankBalance = uiState.ledgers.filter {
        StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
            StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById)
    }.fold(Money.ZERO) { acc, l -> acc + l.currentBalance }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text("Quick Actions", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                // Phase UI-03: now via the shared QuickActions row wrapper instead of two hand-
                // rolled Rows of QuickAction calls - same items, same order, same colors/icons.
                QuickActions(
                    items = listOf(
                        QuickActionSpec(if (isService) "Income" else "Sale", Icons.Default.ReceiptLong, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) { onOpenCreateVoucher(VoucherType.SALES) },
                        QuickActionSpec(if (isService) "Expenditure" else "Purchase", Icons.Default.ShoppingCart, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { onOpenCreateVoucher(VoucherType.PURCHASE) },
                        QuickActionSpec("Receive", Icons.Default.CallReceived, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) { onOpenCreateVoucher(VoucherType.RECEIPT) },
                        QuickActionSpec("Pay", Icons.Default.CallMade, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) { onOpenCreateVoucher(VoucherType.PAYMENT) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                QuickActions(
                    items = listOf(
                        QuickActionSpec("Transfer", Icons.Default.CompareArrows, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) { onOpenCreateVoucher(VoucherType.CONTRA) },
                        QuickActionSpec("Customer", Icons.Default.PersonAdd, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { onAddCustomer() },
                        QuickActionSpec("Supplier", Icons.Default.PersonAdd, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { onAddSupplier() },
                        QuickActionSpec("Item", Icons.Default.Add, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { onAddItem() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Column {
                Text("Business Snapshot", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                // Phase UI-04: extracted into its own composable (BusinessSnapshot.kt) so this
                // screen's body is a composition of named sections; income/expenditure is only
                // passed when the current company is a SERVICE business (IncomeExpenditureReport
                // has no meaning otherwise).
                BusinessSnapshot(
                    cashBalance = cashBalance,
                    bankBalance = bankBalance,
                    receivables = receivables,
                    payables = payables,
                    salesFigure = salesFigure,
                    purchasesFigure = purchasesFigure,
                    gstPayable = gstPayable,
                    income = if (isService) uiState.incomeAndExpenditure?.income else null,
                    expenditure = if (isService) uiState.incomeAndExpenditure?.expenditure else null,
                    onOpenCash = onOpenCash,
                    onOpenBank = onOpenBank,
                    onOpenSales = onOpenSales,
                    onOpenPurchases = onOpenPurchases,
                    onViewReceivables = onViewReceivables,
                    onViewPayables = onViewPayables,
                    onViewProfitLoss = onViewProfitLoss,
                    onViewGstSummary = onViewGstSummary
                )
            }
        }

        recentTransactionsSection(
            vouchers = uiState.vouchers.take(6),
            onVoucherClick = onVoucherClick,
            onViewAll = onViewAllDayBook
        )
    }
}

@Composable
fun VoucherSummaryCard(
    voucher: Voucher,
    onClick: () -> Unit
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.testTag("voucher_item_${voucher.voucherNumber}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (voucher.voucherType) {
                        VoucherType.PAYMENT -> MaterialTheme.colorScheme.errorContainer
                        VoucherType.RECEIPT -> MaterialTheme.colorScheme.secondaryContainer
                        VoucherType.SALES -> MaterialTheme.colorScheme.primaryContainer
                        VoucherType.PURCHASE -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = voucher.voucherType.code,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = voucher.voucherNumber, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = "${voucher.date} | ${voucher.narration.ifBlank { voucher.voucherType.displayName }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = voucher.totalDebits.formatPlain(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            )
        }
    }
}
