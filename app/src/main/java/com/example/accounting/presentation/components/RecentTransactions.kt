package com.example.accounting.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.presentation.features.dashboard.VoucherSummaryCard
import com.example.accounting.presentation.theme.Radius
import com.example.accounting.presentation.theme.Spacing

/**
 * Ledger-grouped Recent Transactions (audit fix) - the caller's own already-filtered/limited
 * [vouchers] list is flattened to (ledger, voucher) pairs via each voucher's own
 * [com.example.accounting.domain.accounting.JournalItem.ledgerId]/`ledgerName` (already
 * denormalized there, so no separate ledger lookup is needed), grouped by ledger, and rendered as
 * one collapsible card per ledger - "each ledger showing its own transaction lines dynamically"
 * per the user's own framing, replacing the previous single flat cross-ledger list. Reuses the
 * exact expand/collapse idiom [com.example.accounting.presentation.features.ledgers.PrimaryGroupCard]/
 * `GroupRowItem` already established for this in `ChartOfAccountsScreen.kt`, rather than inventing
 * a second pattern. Tapping a voucher row still calls the caller's own [onVoucherClick] - the
 * `VoucherDetailDialog` it opens is completely unchanged by this restructuring.
 */
fun LazyListScope.recentTransactionsSection(
    vouchers: List<Voucher>,
    onVoucherClick: (Voucher) -> Unit,
    onViewAll: () -> Unit
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Transactions", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                "View All",
                style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold),
                modifier = Modifier.clip(Radius.shapeSm).clickable(onClick = onViewAll).padding(Spacing.xs)
            )
        }
    }

    if (vouchers.isEmpty()) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Radius.shapeMd,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text("No transactions in this period yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        // One (ledgerId, ledgerName, voucher) entry per journal line the ledger appears on - a
        // voucher with both a debit and a credit line among these vouchers shows up under both its
        // ledgers, exactly matching "each ledger showing its own transaction lines". Not `remember`d
        // - this extension function has no Composable context of its own (it's a LazyListScope
        // builder, same as the `item {}`/`items {}` calls around it), and the list is always small
        // (the caller already capped `vouchers` before calling this), so recomputing on every
        // recomposition is cheap.
        val ledgerGroups = vouchers
            .flatMap { voucher -> voucher.items.map { item -> Triple(item.ledgerId, item.ledgerName, voucher) } }
            .groupBy({ it.first }, { it.second to it.third })
            .map { (ledgerId, nameAndVouchers) ->
                val ledgerName = nameAndVouchers.first().first
                val ledgerVouchers = nameAndVouchers.map { it.second }.distinctBy { it.voucherId }.sortedByDescending { it.date }
                Triple(ledgerId, ledgerName, ledgerVouchers)
            }
            .sortedByDescending { (_, _, ledgerVouchers) -> ledgerVouchers.first().date }

        items(ledgerGroups, key = { it.first }) { (ledgerId, ledgerName, ledgerVouchers) ->
            LedgerTransactionGroupCard(
                ledgerName = ledgerName,
                vouchers = ledgerVouchers.take(5),
                onVoucherClick = onVoucherClick
            )
        }
    }
}

@Composable
private fun LedgerTransactionGroupCard(
    ledgerName: String,
    vouchers: List<Voucher>,
    onVoucherClick: (Voucher) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    OutlinedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(ledgerName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "${vouchers.size} recent transaction(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    vouchers.forEach { voucher ->
                        VoucherSummaryCard(voucher = voucher, onClick = { onVoucherClick(voucher) })
                    }
                }
            }
        }
    }
}
