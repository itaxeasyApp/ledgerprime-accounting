package com.example.accounting.presentation.features.dashboard

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.presentation.components.BusinessSnapshot
import com.example.accounting.presentation.components.DashboardCardBorder
import com.example.accounting.presentation.components.QuickActionSpec
import com.example.accounting.presentation.components.QuickActions
import com.example.accounting.presentation.theme.Radius
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
    /** Phase 8A, Part 2 - a one-tap, first-class Home entry point straight to the GST Return
     * Dashboard (Reports Center's own GST category still works too - this is additive, not a
     * replacement), matching [onViewGstSummary]'s exact `viewReport(...)` deep-link pattern. */
    onViewGstDashboard: () -> Unit,
    /** "Quick Report" section (docs/CORRECTIONS_LOG.md) - one-tap shortcuts to the reports a
     * business owner actually checks day-to-day, alongside the existing Receivables/Payables/P&L/
     * GST cards above. Each is a real, already-implemented report - never a tile with no
     * destination. */
    onViewTrialBalance: () -> Unit,
    onViewBalanceSheet: () -> Unit,
    onViewCashFlow: () -> Unit,
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
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Text("Quick Actions", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                // Phase UI-03: now via the shared QuickActions row wrapper instead of two hand-
                // rolled Rows of QuickAction calls - same items, same order, same colors/icons.
                QuickActions(
                    items = listOf(
                        // "treat Sale as Invoice" (docs/CORRECTIONS_LOG.md) - plain business
                        // language regardless of Inventory vs Non-Inventory mode; the destination
                        // (onOpenCreateVoucher(VoucherType.SALES) -> CreateVoucherDialog/TradingForm)
                        // already auto-adapts to item-level vs account-only based on the company's
                        // own isInventoryEnabled setting - confirmed live on-device, never a second
                        // routing decision made here.
                        QuickActionSpec(if (isService) "Income" else "Invoice", Icons.Default.ReceiptLong, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) { onOpenCreateVoucher(VoucherType.SALES) },
                        QuickActionSpec(if (isService) "Expenditure" else "Purchase", Icons.Default.ShoppingCart, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { onOpenCreateVoucher(VoucherType.PURCHASE) },
                        QuickActionSpec("Receive", Icons.Default.CallReceived, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) { onOpenCreateVoucher(VoucherType.RECEIPT) },
                        QuickActionSpec("Pay", Icons.Default.CallMade, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) { onOpenCreateVoucher(VoucherType.PAYMENT) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                    onViewGstSummary = onViewGstSummary,
                    onOpenGstDashboard = onViewGstDashboard
                )
            }
        }

        // Product correction (docs/CORRECTIONS_LOG.md) - "Recent Transactions" removed from the
        // Dashboard; every transaction is already reachable via Day Book/Ledger statements/each
        // module's own list (Sales/Purchases/Money), so this space is a real brand container
        // instead of a duplicate transaction list. Relabeled "Reports" with the app logo restored
        // (explicit follow-up) - "Ledger Prime" as a brand name now lives in the drawer instead.
        item {
            Card(
                shape = Radius.shapeLg,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = DashboardCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = com.example.R.drawable.ic_ledgerprime_brandmark),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Reports", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }

        item {
            Column {
                Text("Quick Report", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                // Collapsed from two rows of three into one row of four (explicit "too much
                // screen... make one line four column" follow-up) - Cash Flow and Day Book tiles
                // dropped from this specific quick-launcher only; both stay reachable elsewhere
                // (Cash Flow via Reports Center's Financial menu, Day Book via the Money tab's own
                // entry point) - nothing was actually removed from the app. "Trading" deep-links to
                // the same real Profit & Loss report - a Trading Account is the goods-trading
                // section within P&L (Sales - COGS = Gross Profit), never a separate report of its
                // own in this domain model. CMA Data/Project Report are deliberately not tiles here:
                // CMA has real domain logic (domain/cma/CmaReportGenerator.kt) but no UI/ViewModel
                // wiring at all yet, and Project Report does not exist anywhere in this codebase.
                QuickActions(
                    items = listOf(
                        QuickActionSpec("Trading", Icons.Default.Assessment, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onViewProfitLoss),
                        QuickActionSpec("Profit & Loss", Icons.Default.TrendingUp, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onViewProfitLoss),
                        QuickActionSpec("Balance Sheet", Icons.Default.AccountBalance, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onViewBalanceSheet),
                        QuickActionSpec("Trial Balance", Icons.AutoMirrored.Filled.Assignment, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onViewTrialBalance)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun VoucherSummaryCard(
    voucher: Voucher,
    onClick: () -> Unit,
    /** Payment-status badge (Sale/Purchase only) - the outstanding paise for this voucher from
     * [com.example.accounting.presentation.viewmodel.AccountingUiState.outstandingByVoucherId].
     * `null` (every existing call site, unchanged) simply shows no badge - see
     * [com.example.accounting.domain.invoice.InvoiceStatusEngine]. */
    outstandingPaise: Long? = null
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = voucher.totalDebits.formatPlain(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                )
                if (outstandingPaise != null && (voucher.voucherType == VoucherType.SALES || voucher.voucherType == VoucherType.PURCHASE)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val status = com.example.accounting.domain.invoice.InvoiceStatusEngine.deriveStatus(
                        voucherId = voucher.voucherId,
                        isCancelled = voucher.isCancelled,
                        totalAmountPaise = voucher.totalDebits.paise.coerceAtLeast(voucher.totalCredits.paise),
                        outstandingPaise = outstandingPaise,
                        dueDate = null
                    )
                    com.example.accounting.presentation.components.InvoiceStatusBadge(status)
                }
            }
        }
    }
}
