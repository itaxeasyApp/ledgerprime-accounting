package com.example.accounting.presentation.features.sales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.presentation.components.EmptyState
import com.example.accounting.presentation.components.ReceiptSummary
import com.example.accounting.presentation.components.SalesSummary
import com.example.accounting.presentation.components.ScanTypePickerDialog
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.features.dashboard.VoucherSummaryCard
import com.example.accounting.presentation.features.party.PartiesScreen
import com.example.accounting.presentation.theme.Spacing

/**
 * Phase UI-05: the Sales Workspace (bottom-nav "Sales") - Summary / Sales / Returns & Credit
 * Notes / Customers. Creation always reuses the existing, tested `CreateVoucherDialog` flow
 * (`TradingForm` for a Sale, `NoteForm` for a Credit Note) - this screen never posts anything
 * itself, and every figure shown is already computed by the engine/report layer, never summed
 * here.
 *
 * Note on scope (see docs/54_UI_UX_ARCHITECTURE.md-style reasoning, kept here since this is the
 * one screen it applies to): the originally-requested "Sales / Credit Sale / Sales Return /
 * Credit Note" four-way split does not correspond to four distinct things in the domain model.
 * Every [VoucherType.SALES] voucher already posts Dr Customer (a Debtors ledger) / Cr Sales - it
 * is inherently a credit sale; there is no separate "cash sale" voucher shape, and no field
 * anywhere distinguishes one. Likewise [VoucherType.CREDIT_NOTE] IS how a Sales Return is
 * recorded (its own `displayName` is "Credit Note", and `NoteForm` labels the exact same flow
 * "Credit Note - Sales Return / Adjustment") - splitting them would require inventing a new
 * sub-classification field the domain doesn't have, which risks a second, divergent "return
 * engine" this phase explicitly rules out. So "Sales" covers Sales/Credit Sale, and "Returns &
 * Credit Notes" covers Sales Return/Credit Note - four business names, two real underlying lists.
 */
@Composable
fun SalesScreen(
    vouchers: List<Voucher>,
    parties: List<Party>,
    ledgers: List<Ledger>,
    salesRevenue: Money,
    receivables: Money,
    onNewSale: () -> Unit,
    onNewCreditNote: () -> Unit,
    onVoucherClick: (Voucher) -> Unit,
    onAddCustomer: () -> Unit,
    onPartyClick: (Party) -> Unit,
    onToggleFavoriteParty: (Party) -> Unit = {},
    /** Payment-status badge - see [com.example.accounting.presentation.viewmodel.AccountingUiState.outstandingByVoucherId]. */
    outstandingByVoucherId: Map<String, Long> = emptyMap(),
    /** Contextual OCR entry point (docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md) - opens the Photo
     * Picker with the type the user picked from this screen's own compact Sales Invoice/UPI
     * Payment dialog already known, skipping the fully-generic "which document type?" dialog
     * (retired entirely - docs/CORRECTIONS_LOG.md). Shown on the Sales tab only (not Returns/
     * Credit Notes - no contextual OCR was requested there). */
    onScanDocument: (OcrDocumentType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val salesInvoices = remember(vouchers) {
        vouchers.filter { it.voucherType == VoucherType.SALES }.sortedByDescending { it.date }
    }
    val creditNotes = remember(vouchers) {
        vouchers.filter { it.voucherType == VoucherType.CREDIT_NOTE }.sortedByDescending { it.date }
    }
    val customerCount = remember(parties) { parties.count { it.role == PartyRole.CUSTOMER } }

    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = Spacing.md) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Summary") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Sales (${salesInvoices.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Returns (${creditNotes.size})") })
            Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("Customers ($customerCount)") })
        }

        when (tabIndex) {
            0 -> SalesSummaryTab(
                salesRevenue = salesRevenue,
                receivables = receivables,
                salesCount = salesInvoices.size,
                creditNoteCount = creditNotes.size,
                onOpenSales = { tabIndex = 1 },
                onOpenCustomers = { tabIndex = 3 }
            )
            1 -> SalesVoucherList(
                vouchers = salesInvoices,
                emptyMessage = "No sales yet. Record your first sale to a customer.",
                onVoucherClick = onVoucherClick,
                onNew = onNewSale,
                fabDescription = "New Sale",
                outstandingByVoucherId = outstandingByVoucherId,
                onScanDocument = onScanDocument
            )
            2 -> SalesVoucherList(
                vouchers = creditNotes,
                emptyMessage = "No Sales Returns or Credit Notes yet.",
                onVoucherClick = onVoucherClick,
                onNew = onNewCreditNote,
                fabDescription = "New Credit Note",
                outstandingByVoucherId = outstandingByVoucherId
            )
            3 -> PartiesScreen(
                role = PartyRole.CUSTOMER,
                parties = parties,
                ledgers = ledgers,
                onAddParty = onAddCustomer,
                onPartyClick = onPartyClick,
                onToggleFavorite = onToggleFavoriteParty
            )
        }
    }
}

@Composable
private fun SalesSummaryTab(
    salesRevenue: Money,
    receivables: Money,
    salesCount: Int,
    creditNoteCount: Int,
    onOpenSales: () -> Unit,
    onOpenCustomers: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SalesSummary(salesRevenue, Modifier.weight(1f), onOpenSales)
            ReceiptSummary(receivables, Modifier.weight(1f), onOpenCustomers)
        }
        SectionCard(title = "This Period") {
            Text(
                "Sales Invoices: $salesCount  •  Returns & Credit Notes: $creditNoteCount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SalesVoucherList(
    vouchers: List<Voucher>,
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
            options = listOf("Sales Invoice" to OcrDocumentType.SALES_INVOICE, "UPI Payment" to OcrDocumentType.UPI_PAYMENT),
            onDismiss = { isScanPickerOpen = false },
            onSelect = { type -> isScanPickerOpen = false; onScanDocument(type) }
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (vouchers.isEmpty()) {
            EmptyState(message = emptyMessage, icon = Icons.AutoMirrored.Filled.ReceiptLong)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md, vertical = Spacing.sm + Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = Spacing.lg - Spacing.xs, start = Spacing.lg - Spacing.xs)
            ) { Icon(Icons.Default.DocumentScanner, contentDescription = "Scan Document") }
        }
        FloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = Spacing.lg - Spacing.xs, end = Spacing.lg - Spacing.xs)
        ) { Icon(Icons.Default.Add, contentDescription = fabDescription) }
    }
}
