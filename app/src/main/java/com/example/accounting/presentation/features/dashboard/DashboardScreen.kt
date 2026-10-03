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
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.netDebitBalance
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.presentation.components.BusinessSnapshot
import com.example.accounting.presentation.components.DashboardCardBorder
import com.example.accounting.presentation.components.QuickActionSpec
import com.example.accounting.presentation.components.QuickActions
import com.example.accounting.presentation.components.ReportCardGrid
import com.example.accounting.presentation.components.ReportCardSpec
import com.example.accounting.presentation.theme.Radius
import com.example.accounting.presentation.theme.Spacing
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
    onViewTrading: () -> Unit,
    onViewCma: () -> Unit,
    onViewRatioAnalysis: () -> Unit,
    onViewFundFlow: () -> Unit,
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
    // Debit-minus-credit (Ledger.debitMinusCredit), not the bare magnitude: the same sign the Trial
    // Balance and Balance Sheet use, so an overdrawn (Credit) bank shows negative here too.
    val cashBalance = uiState.ledgers.filter {
        StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
            StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
    }.netDebitBalance()
    val bankBalance = uiState.ledgers.filter {
        StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
            StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById)
    }.netDebitBalance()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md),
        contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column {
                Text("Quick Actions", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(Spacing.sm))
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
                        QuickActionSpec(if (isService) "Income" else "Invoice", Icons.AutoMirrored.Filled.ReceiptLong, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) { onOpenCreateVoucher(VoucherType.SALES) },
                        QuickActionSpec(if (isService) "Expenditure" else "Purchase", Icons.Default.ShoppingCart, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { onOpenCreateVoucher(VoucherType.PURCHASE) },
                        QuickActionSpec("Receive", Icons.AutoMirrored.Filled.CallReceived, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) { onOpenCreateVoucher(VoucherType.RECEIPT) },
                        QuickActionSpec("Pay", Icons.AutoMirrored.Filled.CallMade, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) { onOpenCreateVoucher(VoucherType.PAYMENT) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                QuickActions(
                    items = listOf(
                        QuickActionSpec("Transfer", Icons.AutoMirrored.Filled.CompareArrows, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) { onOpenCreateVoucher(VoucherType.CONTRA) },
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
                Spacer(modifier = Modifier.height(Spacing.sm))
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

        // Disabled 2026-09-10 (explicit request) - this "Reports" brand card was redundant with
        // both the bottom-nav Reports tab and the "Quick Report" section immediately below it, and
        // left a dead gap on the Home dashboard (confirmed live on tablet). Commented out rather
        // than deleted so the brand-container pattern is easy to bring back if a real dashboard
        // header/brand slot is wanted later. Re-enabling requires restoring the
        // `androidx.compose.foundation.Image` and `androidx.compose.ui.res.painterResource` imports
        // at the top of this file.
        // item {
        //     Card(
        //         shape = Radius.shapeLg,
        //         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        //         border = DashboardCardBorder(),
        //         modifier = Modifier.fillMaxWidth()
        //     ) {
        //         Row(
        //             modifier = Modifier.fillMaxWidth().padding(14.dp),
        //             verticalAlignment = Alignment.CenterVertically
        //         ) {
        //             Image(
        //                 painter = painterResource(id = com.example.R.drawable.ic_ledgerprime_brandmark),
        //                 contentDescription = null,
        //                 modifier = Modifier.size(28.dp)
        //             )
        //             Spacer(modifier = Modifier.width(10.dp))
        //             Text("Reports", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        //         }
        //     }
        // }

        item {
            Column {
                Text("Quick Report", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(Spacing.sm))
                // Eight report tiles in exactly two rows of four (ReportCardGrid). Fund Flow and CMA have no
                // report yet - their cards open that category's menu, where they show as "Coming soon".
                ReportCardGrid(
                    items = listOf(
                        ReportCardSpec("Trading", Icons.Default.Assessment, onViewTrading),
                        ReportCardSpec("Profit & Loss", Icons.AutoMirrored.Filled.TrendingUp, onViewProfitLoss),
                        ReportCardSpec("Balance Sheet", Icons.Default.AccountBalance, onViewBalanceSheet),
                        ReportCardSpec("Trial Balance", Icons.AutoMirrored.Filled.Assignment, onViewTrialBalance),
                        ReportCardSpec("Cash Flow", Icons.Default.CurrencyExchange, onViewCashFlow),
                        ReportCardSpec("CMA", Icons.Default.Calculate, onViewCma),
                        ReportCardSpec("Ratio Analysis", Icons.Default.Insights, onViewRatioAnalysis),
                        ReportCardSpec("Fund Flow", Icons.Default.SwapVert, onViewFundFlow)
                    )
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
    outstandingPaise: Long? = null,
    /** No-mock-data audit fix - the real linked Invoice's due date, from
     * [com.example.accounting.presentation.viewmodel.AccountingUiState.dueDateByVoucherId]. Was
     * previously always hardcoded `null` at every call site, which made
     * [com.example.accounting.domain.invoice.InvoiceStatus.OVERDUE] structurally unreachable - a
     * genuinely overdue invoice always showed "Unpaid" instead. `null` (no linked invoice, or no
     * due date set) still simply skips the OVERDUE check, same as before. */
    dueDate: java.time.LocalDate? = null
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
                        dueDate = dueDate
                    )
                    com.example.accounting.presentation.components.InvoiceStatusBadge(status)
                }
            }
        }
    }
}
