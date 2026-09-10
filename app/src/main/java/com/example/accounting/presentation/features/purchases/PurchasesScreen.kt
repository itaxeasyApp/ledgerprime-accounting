package com.example.accounting.presentation.features.purchases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.presentation.components.ScanTypePickerDialog
import com.example.accounting.presentation.features.dashboard.VoucherSummaryCard
import com.example.accounting.presentation.features.party.PartiesScreen

/**
 * Phase 7J UI: the Purchases tab (bottom-nav item #3) - mirrors [com.example.accounting.presentation.features.sales.SalesScreen]'s
 * exact shape. Creation reuses `CreateVoucherDialog(defaultVoucherType = VoucherType.PURCHASE)`.
 *
 * The "Returns (N)" tab mirrors Sales' "Returns & Credit Notes" tab exactly, one level down: a
 * [VoucherType.DEBIT_NOTE] is a Purchase Return the same way a [VoucherType.CREDIT_NOTE] is a
 * Sales Return - both already share the same `CreateVoucherDialog` / `NoteForm` / `postNote`
 * backend (see `AccountingViewModel.postDebitNote`), so this tab is purely the missing UI entry
 * point, not new domain logic.
 */
@Composable
fun PurchasesScreen(
    vouchers: List<Voucher>,
    parties: List<Party>,
    ledgers: List<Ledger>,
    onNewPurchase: () -> Unit,
    onNewDebitNote: () -> Unit,
    onVoucherClick: (Voucher) -> Unit,
    onAddSupplier: () -> Unit,
    onPartyClick: (Party) -> Unit,
    onToggleFavoriteParty: (Party) -> Unit = {},
    /** Contextual OCR entry point (docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md, docs/CORRECTIONS_LOG.md) -
     * opens the Photo Picker with the type chosen from this screen's own compact Purchase Bill/UPI
     * Payment dialog already known. Shown on the Purchases tab only. */
    onScanDocument: (OcrDocumentType) -> Unit = {},
    /** Payment-status badge - see [com.example.accounting.presentation.viewmodel.AccountingUiState.outstandingByVoucherId]. */
    outstandingByVoucherId: Map<String, Long> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val purchaseVouchers = remember(vouchers) {
        vouchers.filter { it.voucherType == VoucherType.PURCHASE }.sortedByDescending { it.date }
    }
    val debitNotes = remember(vouchers) {
        vouchers.filter { it.voucherType == VoucherType.DEBIT_NOTE }.sortedByDescending { it.date }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Purchases (${purchaseVouchers.size})") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Returns (${debitNotes.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Suppliers (${parties.count { it.role == PartyRole.SUPPLIER }})") })
        }

        when (tabIndex) {
            0 -> PurchaseVoucherList(
                vouchers = purchaseVouchers,
                emptyIcon = Icons.Default.ShoppingCart,
                emptyTitle = "No purchases yet",
                emptyMessage = "Record your first purchase from a supplier.",
                onVoucherClick = onVoucherClick,
                onNew = onNewPurchase,
                fabDescription = "New Purchase",
                outstandingByVoucherId = outstandingByVoucherId,
                onScanDocument = onScanDocument
            )
            1 -> PurchaseVoucherList(
                vouchers = debitNotes,
                emptyIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                emptyTitle = "No Purchase Returns or Debit Notes yet",
                emptyMessage = "Record a Debit Note against a supplier bill to adjust it.",
                onVoucherClick = onVoucherClick,
                onNew = onNewDebitNote,
                fabDescription = "New Debit Note",
                outstandingByVoucherId = outstandingByVoucherId
            )
            2 -> PartiesScreen(
                role = PartyRole.SUPPLIER,
                parties = parties,
                ledgers = ledgers,
                onAddParty = onAddSupplier,
                onPartyClick = onPartyClick,
                onToggleFavorite = onToggleFavoriteParty
            )
        }
    }
}

@Composable
private fun PurchaseVoucherList(
    vouchers: List<Voucher>,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptyMessage: String,
    onVoucherClick: (Voucher) -> Unit,
    onNew: () -> Unit,
    fabDescription: String,
    outstandingByVoucherId: Map<String, Long> = emptyMap(),
    onScanDocument: ((OcrDocumentType) -> Unit)? = null
) {
    var isScanPickerOpen by remember { mutableStateOf(false) }
    if (isScanPickerOpen && onScanDocument != null) {
        ScanTypePickerDialog(
            options = listOf("Purchase Bill" to OcrDocumentType.PURCHASE_BILL, "UPI Payment" to OcrDocumentType.UPI_PAYMENT),
            onDismiss = { isScanPickerOpen = false },
            onSelect = { type -> isScanPickerOpen = false; onScanDocument(type) }
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (vouchers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(emptyIcon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                Text(emptyMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(vouchers, key = { it.voucherId }) { voucher ->
                    VoucherSummaryCard(
                        voucher = voucher,
                        onClick = { onVoucherClick(voucher) },
                        outstandingPaise = outstandingByVoucherId[voucher.voucherId]
                    )
                }
            }
        }
        if (onScanDocument != null) {
            FloatingActionButton(
                onClick = { isScanPickerOpen = true },
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 20.dp, start = 20.dp)
            ) { Icon(Icons.Default.DocumentScanner, contentDescription = "Scan Document") }
        }
        FloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 20.dp)
        ) { Icon(Icons.Default.Add, contentDescription = fabDescription) }
    }
}
