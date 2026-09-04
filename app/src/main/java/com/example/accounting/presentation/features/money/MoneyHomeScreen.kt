package com.example.accounting.presentation.features.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.application.voucher.VoucherDraft
import com.example.accounting.application.voucher.VoucherDraftLine
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.presentation.components.Amount
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.theme.Spacing
import com.example.accounting.presentation.viewmodel.AccountingUiState
import java.time.LocalDate

/**
 * Phase 7J UI: the Money tab (bottom-nav item #4) - a sub-menu, per the UX spec's exact list
 * (Receive Money/Pay Money/Cash/Bank/UPI/Transfer). "Receive Money"/"Pay Money"/"Transfer" open
 * [MoneyVoucherEntryScreen] (a simple single-amount form with an opt-in Round Off toggle),
 * ultimately posted via `AccountingViewModel.postQuickVoucherWithRoundOff` -> the same, single,
 * unmodified `AccountingRepository.postVoucher` path every other voucher already uses - never a
 * second posting mechanism. The richer allocation-capable Receipt/Payment flow (against
 * outstanding invoices) stays on the existing `CreateVoucherDialog`, reachable from Home's Quick
 * Actions and Day Book, untouched by this screen. Journal stays reachable only from here, as an
 * advanced action, per the spec's "never a primary action" rule.
 */
@Composable
fun MoneyHomeScreen(
    totalCash: Money,
    totalBank: Money,
    pendingDraftsCount: Int,
    onReceiveMoney: () -> Unit,
    onPayMoney: () -> Unit,
    onTransfer: () -> Unit,
    onOpenCash: () -> Unit,
    onOpenBank: () -> Unit,
    onOpenUpiProfiles: () -> Unit,
    onOpenPendingReviews: () -> Unit,
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.md),
        contentPadding = PaddingValues(top = Spacing.sm + Spacing.xs, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs)
    ) {
        item {
            SectionCard(elevated = true, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Cash", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Amount(totalCash, style = MaterialTheme.typography.titleMedium, emphasize = true)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Bank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Amount(totalBank, style = MaterialTheme.typography.titleMedium, emphasize = true)
                    }
                }
            }
        }

        item { MoneyTile("Receive Money", Icons.Default.CallReceived, onReceiveMoney) }
        item { MoneyTile("Pay Money", Icons.Default.CallMade, onPayMoney) }
        item { MoneyTile("Transfer (Cash ↔ Bank)", Icons.Default.CompareArrows, onTransfer) }
        item { MoneyTile("Cash", Icons.Default.Payments, onOpenCash) }
        item { MoneyTile("Bank", Icons.Default.AccountBalance, onOpenBank) }
        item { MoneyTile("UPI Details", Icons.Default.CreditCard, onOpenUpiProfiles) }
        item {
            MoneyTile(
                title = if (pendingDraftsCount > 0) "Pending Reviews ($pendingDraftsCount)" else "Pending Reviews",
                icon = Icons.Default.Assignment,
                onClick = onOpenPendingReviews
            )
        }
        item {
            Text(
                "Advanced",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
        item {
            SectionCard(onClick = onOpenJournal) {
                Text("Journal Entry", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Manual accounting adjustment - for users who understand debit and credit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MoneyTile(title: String, icon: ImageVector, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.padding(Spacing.xs))
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        }
    }
}

private sealed class MoneySubScreen {
    object Home : MoneySubScreen()
    object Cash : MoneySubScreen()
    object Bank : MoneySubScreen()
    object Upi : MoneySubScreen()
    object PendingReviews : MoneySubScreen()
    data class Entry(val voucherType: VoucherType) : MoneySubScreen()
}

/**
 * Phase 7J UI: owns the Money tab's internal sub-navigation (Home/Cash/Bank/UPI/Pending
 * Reviews/Entry form) so [com.example.accounting.presentation.MainAppScreen] only ever needs one
 * dispatch case for `AppRoute.Money` - mirrors [com.example.accounting.presentation.features.ledgers.ChartOfAccountsScreen]'s
 * own internal list/statement sub-navigation pattern.
 */
@Composable
fun MoneyTabContent(
    uiState: AccountingUiState,
    onOpenCreateVoucher: (VoucherType) -> Unit,
    onLedgerClick: (Ledger) -> Unit,
    onAddBankUpiProfile: () -> Unit,
    onDeleteBankUpiProfile: (String) -> Unit,
    onSaveDraftLines: (VoucherDraft, List<VoucherDraftLine>) -> Unit,
    onPostDraft: (VoucherDraft) -> Unit,
    onDiscardDraft: (String) -> Unit,
    onSubmitMoneyVoucher: (VoucherType, LocalDate, String, String, Money, String, String, Boolean) -> Unit,
    onAddParty: (PartyRole) -> Unit,
    /** Bug #3 fix - QR/Barcode scan for Receive Payment, threaded down to
     * [MoneyVoucherEntryScreen] only while its Entry sub-screen is a RECEIPT. Never gated by
     * Inventory Mode/Items tab. */
    onScanBarcode: (() -> Unit)? = null,
    scannedBarcodeValue: String? = null,
    onScannedValueConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var sub by remember { mutableStateOf<MoneySubScreen>(MoneySubScreen.Home) }
    val cashLedgers = uiState.ledgers.filter { it.groupId.startsWith(StandardSystemGroups.CASH_GROUP_ID) }
    val bankLedgers = uiState.ledgers.filter { it.groupId.startsWith(StandardSystemGroups.BANK_GROUP_ID) }
    val totalCash = cashLedgers.fold(Money.ZERO) { acc, l -> acc + l.currentBalance }
    val totalBank = bankLedgers.fold(Money.ZERO) { acc, l -> acc + l.currentBalance }

    when (val s = sub) {
        MoneySubScreen.Home -> MoneyHomeScreen(
            totalCash = totalCash,
            totalBank = totalBank,
            pendingDraftsCount = uiState.voucherDraftsPendingReview.size,
            onReceiveMoney = { sub = MoneySubScreen.Entry(VoucherType.RECEIPT) },
            onPayMoney = { sub = MoneySubScreen.Entry(VoucherType.PAYMENT) },
            onTransfer = { sub = MoneySubScreen.Entry(VoucherType.CONTRA) },
            onOpenCash = { sub = MoneySubScreen.Cash },
            onOpenBank = { sub = MoneySubScreen.Bank },
            onOpenUpiProfiles = { sub = MoneySubScreen.Upi },
            onOpenPendingReviews = { sub = MoneySubScreen.PendingReviews },
            onOpenJournal = { onOpenCreateVoucher(VoucherType.JOURNAL) },
            modifier = modifier
        )
        MoneySubScreen.Cash -> CashOrBankLedgerListScreen("Cash", cashLedgers, onLedgerClick, modifier)
        MoneySubScreen.Bank -> CashOrBankLedgerListScreen("Bank", bankLedgers, onLedgerClick, modifier)
        MoneySubScreen.Upi -> UpiProfilesScreen(uiState.bankUpiProfiles, onAddBankUpiProfile, onDeleteBankUpiProfile, modifier)
        MoneySubScreen.PendingReviews -> {
            val pending = uiState.voucherDraftsPendingReview
            var selectedDraft by remember { mutableStateOf<VoucherDraft?>(null) }
            val active = selectedDraft
            if (active == null) {
                VoucherDraftReviewScreen(pending, onBack = { sub = MoneySubScreen.Home }, onSelect = { selectedDraft = it }, modifier = modifier)
            } else {
                VoucherDraftEditorScreen(
                    draft = active,
                    ledgers = uiState.ledgers,
                    onBack = { selectedDraft = null },
                    onSaveLines = onSaveDraftLines,
                    onPost = { onPostDraft(it); sub = MoneySubScreen.Home },
                    onDiscard = { onDiscardDraft(it); selectedDraft = null },
                    modifier = modifier
                )
            }
        }
        is MoneySubScreen.Entry -> MoneyVoucherEntryScreen(
            voucherType = s.voucherType,
            ledgers = uiState.ledgers,
            onBack = { sub = MoneySubScreen.Home },
            onSubmit = { type, date, debitId, creditId, amount, narration, ref, roundOff ->
                onSubmitMoneyVoucher(type, date, debitId, creditId, amount, narration, ref, roundOff)
                sub = MoneySubScreen.Home
            },
            onAddParty = onAddParty,
            onScanBarcode = onScanBarcode,
            scannedBarcodeValue = scannedBarcodeValue,
            onScannedValueConsumed = onScannedValueConsumed,
            modifier = modifier
        )
    }
}

/** Read-only Cash or Bank ledger list, reached from [MoneyHomeScreen] - tapping a ledger reuses
 * the existing ledger-statement navigation, never a second statement view. */
@Composable
fun CashOrBankLedgerListScreen(
    title: String,
    ledgers: List<Ledger>,
    onLedgerClick: (Ledger) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(Spacing.md)) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.padding(Spacing.xs))
        if (ledgers.isEmpty()) {
            Text("No $title accounts yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm), contentPadding = PaddingValues(bottom = 60.dp)) {
                items(ledgers, key = { it.ledgerId }) { ledger ->
                    SectionCard(
                        onClick = { onLedgerClick(ledger) },
                        title = ledger.name,
                        trailing = { Amount(ledger.currentBalance, style = MaterialTheme.typography.titleSmall, emphasize = true) }
                    )
                }
            }
        }
    }
}
