package com.example.accounting.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.core.common.DrCr
import com.example.accounting.data.local.dao.VoucherAttachmentRow
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherBillSummary
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.invoice.InvoiceStatusEngine
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.InvoiceSummaryCalculator
import com.example.accounting.domain.rendering.TaxColumnMode

/** Voucher types [onCorrectVoucher] supports. Contra/Journal are always a flat, unconditional
 * 2-line debit/credit voucher (no invoice allocation, no GST, no stock) - [CreateVoucherDialog]'s
 * prefill reconstructs them byte-for-byte from [Voucher.items].
 *
 * Extend-correction-to-all-types fix - Sale/Purchase and Receipt/Payment are now included too:
 * - Sale/Purchase: only the ACCOUNT-ONLY shape is safely reconstructible this pass (party ledger,
 *   trade ledger, amount, GST rate/HSN via a lookup at correction time). An inventory-tracked
 *   Sale/Purchase additionally carries stock lines whose running-average-cost reconstruction is a
 *   materially higher-risk problem, deliberately left for a dedicated pass - see the caller-side
 *   `isInventoryEnabled` guard on [canCorrect] below, which this set alone does not express.
 * - Receipt/Payment: prefills party ledger, cash/bank ledger, amount, narration, reference, date -
 *   never the original invoice allocation. Cancelling the original settlement already makes its
 *   invoice(s) outstanding again automatically, so the user re-allocates on repost against the
 *   now-current outstanding list - a correct step, not a stopgap.
 *
 * Credit/Debit Notes are deliberately excluded - they already have their own correction mechanism
 * (issuing another note), unchanged by this. */
val VOUCHER_CORRECTION_ELIGIBLE_TYPES = setOf(
    com.example.accounting.domain.accounting.VoucherType.CONTRA,
    com.example.accounting.domain.accounting.VoucherType.JOURNAL,
    com.example.accounting.domain.accounting.VoucherType.SALES,
    com.example.accounting.domain.accounting.VoucherType.PURCHASE,
    com.example.accounting.domain.accounting.VoucherType.RECEIPT,
    com.example.accounting.domain.accounting.VoucherType.PAYMENT
)

@Composable
fun VoucherDetailDialog(
    voucher: Voucher,
    onDismiss: () -> Unit,
    onDeleteVoucher: ((Voucher) -> Unit)? = null,
    /** True in-place edit (live-device audit finding) - narration/reference number only, the one
     * genuinely safe thing to mutate directly (see [com.example.accounting.data.repository.AccountingRepository.updateVoucherMetadata]'s
     * doc comment for why amount/ledger/date stay on the [onCorrectVoucher] reversal path instead).
     * `null` (the default) hides the Edit affordance entirely for callers that don't wire it. */
    onUpdateVoucherMetadata: ((voucherId: String, narration: String, referenceNumber: String) -> Unit)? = null,
    /** Architecture correction (Voucher Correct workflow) - only ever shown for
     * [VOUCHER_CORRECTION_ELIGIBLE_TYPES]; `null` (the default) hides the affordance entirely for
     * callers that don't wire it. */
    onCorrectVoucher: ((Voucher) -> Unit)? = null,
    /** Extend-correction-to-all-types fix - gates Sale/Purchase correction eligibility to
     * account-only companies (see [VOUCHER_CORRECTION_ELIGIBLE_TYPES]'s doc comment for why
     * inventory-tracked Sale/Purchase correction stays out of scope this pass). Irrelevant for
     * every other voucher type. Defaults to `false` (the safer default: hides Sale/Purchase
     * correction) so a caller that doesn't pass this explicitly never over-offers it. */
    isInventoryEnabled: Boolean = false,
    /** Every voucher in the company, used only to resolve the two-way "Corrects .../Corrected by
     * ..." link display below - never mutated, never posted from here. Defaulted empty so every
     * existing call site keeps compiling with the link display simply not shown. */
    allVouchers: List<Voucher> = emptyList(),
    /** Phase 7J-B.2 (Slice 2) attachment plumbing - all optional/no-op-defaulted so every other
     * existing call site of this dialog keeps compiling untouched. */
    attachments: List<VoucherAttachmentRow> = emptyList(),
    isAttachmentsLoading: Boolean = false,
    isAttaching: Boolean = false,
    removingAttachmentReferenceId: String? = null,
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (VoucherAttachmentRow) -> Unit = {},
    /** Payment-status badge (Sale/Purchase only) - see [InvoiceStatusEngine] and
     * [com.example.accounting.presentation.viewmodel.AccountingUiState.outstandingByVoucherId].
     * `null` (the default) hides the badge for a caller that doesn't wire it. */
    outstandingPaise: Long? = null,
    /** The company's own real GSTIN, for the Sale invoice-identity QR below - blank hides the QR
     * section entirely rather than printing an incomplete/misleading one. Never a government IRN -
     * this app has no e-invoicing API integration, so the QR only ever encodes the same real
     * invoice number/date/amount/GSTIN already shown in this dialog, for the business's own
     * scanning/lookup convenience. */
    companyGstin: String = "",
    /** "5 Invoice PDF Templates" task - opens the Invoice Preview screen (Sale/Purchase only).
     * `null` (the default) hides the button for a caller that doesn't wire it. */
    onPreviewInvoice: ((Voucher) -> Unit)? = null,
    /** Product correction ("THIS APPLICATION IS NOT AN ERP") - real item-level Bill data for this
     * voucher (Sale/Purchase only, when it has actual stock lines), from the exact same
     * [AccountingRepository.assembleDocumentDataFromVoucher] Invoice Preview uses - never a second,
     * independent calculation. `null` while loading or when it doesn't apply (see [billSummary]). */
    documentData: DocumentData? = null,
    /** Fallback plain-Bill summary (party/GST/total, no item breakdown) for every case
     * [documentData] doesn't cover - an account-only Sale/Purchase, or any non-trading voucher type.
     * Ignored when [documentData] is non-null. */
    billSummary: VoucherBillSummary? = null
) {
    // Architecture correction - "Corrects X" (this voucher has a referenceVoucherId pointing at an
    // earlier one of the SAME type) / "Corrected by Y" (some other same-type voucher points back at
    // this one) - same-type-only so this never collides with the unrelated Credit/Debit Note
    // referenceVoucherId usage (a Note is always a different voucherType than its original).
    val correctsOriginal = voucher.referenceVoucherId
        ?.let { refId -> allVouchers.firstOrNull { it.voucherId == refId && it.voucherType == voucher.voucherType } }
    val correctedByVoucher = allVouchers.firstOrNull { it.referenceVoucherId == voucher.voucherId && it.voucherType == voucher.voucherType }
    // True in-place edit (live-device audit finding) - local-only draft state for narration/
    // reference number, keyed on the voucher so switching to a different voucher (dialog stays
    // open, new `voucher` passed in) always starts from that voucher's own current values, never a
    // stale edit carried over. Date is never editable here (see onUpdateVoucherMetadata's doc
    // comment) - only shown.
    var isEditingMetadata by remember(voucher.voucherId) { mutableStateOf(false) }
    var editedNarration by remember(voucher.voucherId) { mutableStateOf(voucher.narration) }
    var editedReference by remember(voucher.voucherId) { mutableStateOf(voucher.referenceNumber) }
    // Real confirmation, not a silent one-tap action - this now genuinely deletes the voucher
    // (never a same-voucher offsetting entry left behind), so it is irreversible in a way the old
    // "Delete & Reverse" (which at least left a visible, auditable trail) was not.
    var showDeleteConfirm by remember(voucher.voucherId) { mutableStateOf(false) }
    // Product correction ("THIS APPLICATION IS NOT AN ERP", docs/CORRECTIONS_LOG.md) - the
    // journal-line Debit/Credit table is no longer shown by default; it only appears once the user
    // explicitly asks via "Advanced > View Accounting Entries" below. Always starts collapsed for a
    // freshly-opened voucher.
    var showAccountingEntries by remember(voucher.voucherId) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        // Mobile Workflow Correction - Voucher Detail is a major workflow (view + edit metadata +
        // delete + correct + preview + attachments), not a small confirmation, so it must read as a
        // proper mobile screen rather than a card floating over a dimmed dashboard. Same full-bleed
        // Dialog(usePlatformDefaultWidth = false) + fillMaxSize() Surface [QuickInvoiceEntryScreen]
        // ("New Sale Invoice", the reference pattern) and [CreateVoucherDialog] already use - still a
        // Dialog window mechanically, but now a real full screen visually, with a fixed top app bar
        // (back/type/number/status/Edit) instead of that header scrolling away with the content.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            // Real-device QA finding (see QuickInvoiceEntryScreen's fuller note) - with
            // decorFitsSystemWindows = false, a raw Dialog window's fillMaxSize() measures against
            // the full edge-to-edge window, not the visible area between the status and navigation
            // bars. systemBarsPadding() keeps the fixed Top App Bar below the status bar and an
            // explicit 48dp floor (Android's own standard 3-button nav bar height) keeps the
            // bottom-of-scroll "Close" button reachable on this OEM's 3-button nav (MIUI/HyperOS),
            // where systemBarsPadding() alone measured ZERO bottom inset.
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 48.dp)) {
                // Top App Bar - fixed, never scrolls away, matching every other converted workflow
                // screen's convention (back navigation always reachable).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = voucher.voucherType.displayName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = voucher.voucherNumber,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            if (voucher.isCancelled) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = "CANCELLED",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else if (outstandingPaise != null && (voucher.voucherType == VoucherType.SALES || voucher.voucherType == VoucherType.PURCHASE)) {
                                Spacer(modifier = Modifier.width(8.dp))
                                val status = InvoiceStatusEngine.deriveStatus(
                                    voucherId = voucher.voucherId,
                                    isCancelled = false,
                                    totalAmountPaise = voucher.totalDebits.paise.coerceAtLeast(voucher.totalCredits.paise),
                                    outstandingPaise = outstandingPaise,
                                    dueDate = null
                                )
                                InvoiceStatusBadge(status)
                            }
                        }
                        if (!isEditingMetadata) {
                            Text(
                                text = "Date: ${voucher.date} | Ref: ${voucher.referenceNumber.ifBlank { "N/A" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // "automate ... received in bank or cash or via UPI" - a plain read of
                            // Voucher.paymentMode, set automatically at entry time (Receive/Pay
                            // Money) from which ledger + whether the UPI QR was shown; blank for
                            // every voucher predating this field, so nothing shows for those.
                            if (voucher.paymentMode.isNotBlank() &&
                                (voucher.voucherType == VoucherType.RECEIPT || voucher.voucherType == VoucherType.PAYMENT)
                            ) {
                                val partyLine = voucher.items.firstOrNull {
                                    if (voucher.voucherType == VoucherType.RECEIPT) it.type == DrCr.CREDIT else it.type == DrCr.DEBIT
                                }
                                val verb = if (voucher.voucherType == VoucherType.RECEIPT) "Received from" else "Paid to"
                                Text(
                                    text = buildString {
                                        append(verb)
                                        partyLine?.let { append(" ${it.ledgerName}") }
                                        append(" via ${voucher.paymentMode}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    }
                    // Trailing action - Edit only. Close/dismiss now lives solely in the Back arrow
                    // above (Top App Bar convention), never a second redundant dismiss icon.
                    if (onUpdateVoucherMetadata != null && !voucher.isCancelled && !isEditingMetadata) {
                        IconButton(onClick = {
                            editedNarration = voucher.narration
                            editedReference = voucher.referenceNumber
                            isEditingMetadata = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit narration/reference")
                        }
                    }
                }
                HorizontalDivider()

                // Main Content - normal vertical scrolling, matching every other converted
                // workflow screen. The line-items table below used to sit in a LazyColumn sized
                // with Modifier.weight(1f, fill = false) inside a non-scrolling Column - once the
                // narration/correction-link banner/attachments/buttons around it already filled the
                // available height (routine on a phone screen), Column's weight distribution had
                // zero space left to give the only weighted child, so the table silently rendered
                // zero rows even though voucher.items (and the Total row's correct sum below it)
                // were never empty. Scrolling this whole section instead guarantees every part -
                // including the table - always gets the space it actually needs.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                Spacer(modifier = Modifier.height(14.dp))

                if (isEditingMetadata) {
                    Text(
                        text = "Date: ${voucher.date} (not editable - see Correct Voucher for a date fix)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editedReference,
                        onValueChange = { editedReference = it },
                        label = { Text("Reference Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editedNarration,
                        onValueChange = { editedNarration = it },
                        label = { Text("Narration") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { isEditingMetadata = false }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            onUpdateVoucherMetadata?.invoke(voucher.voucherId, editedNarration, editedReference)
                            isEditingMetadata = false
                        }) {
                            Text("Save")
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (correctsOriginal != null || correctedByVoucher != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = when {
                                correctsOriginal != null -> "Corrects ${correctsOriginal.voucherNumber}"
                                else -> "Corrected by ${correctedByVoucher!!.voucherNumber}"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Narration
                if (voucher.narration.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Narration: ${voucher.narration}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Product correction ("THIS APPLICATION IS NOT AN ERP") - default view is a plain
                // business Bill (Party/Items/GST/Total), never the Debit/Credit journal table. The
                // real double-entry postings are unchanged internally and stay reachable, just no
                // longer shown by default - see the "Advanced" toggle below.
                if (!showAccountingEntries) {
                    BillSummarySection(voucher = voucher, documentData = documentData, billSummary = billSummary)
                } else {
                    // Table Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Particulars / Ledger", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.8f))
                        Text("Debit", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                        Text("Credit", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider()

                    Column {
                        voucher.items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1.8f)) {
                                    Text(item.ledgerName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    if (item.narration.isNotBlank()) {
                                        Text(item.narration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(
                                    text = if (item.type == DrCr.DEBIT) item.amount.formatPlain() else "--",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (item.type == DrCr.CREDIT) item.amount.formatPlain() else "--",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }

                    HorizontalDivider(thickness = 2.dp)

                    // Totals
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(2.4f))
                        Text(voucher.totalDebits.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary), modifier = Modifier.weight(1f))
                        Text(voucher.totalCredits.formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary), modifier = Modifier.weight(1f))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showAccountingEntries = !showAccountingEntries }) {
                        Text(if (showAccountingEntries) "Hide Accounting Entries" else "Advanced ▸ View Accounting Entries")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // "one barcode more ... on invoice" - a real QR encoding this Sale's own
                // identifying facts (invoice number, date, amount, seller/buyer GSTIN), all
                // already shown elsewhere in this same dialog - never a government e-invoice IRN
                // (this app has no such API integration), just a scannable, offline way to pull up
                // or verify this exact invoice later. Sale only, and only once the company's own
                // GSTIN is known - an incomplete QR would be worse than none.
                if (voucher.voucherType == VoucherType.SALES && !voucher.isCancelled && companyGstin.isNotBlank()) {
                    InvoiceQrSection(voucher = voucher, companyGstin = companyGstin)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                AttachmentSection(
                    attachments = attachments,
                    isLoading = isAttachmentsLoading,
                    isAttaching = isAttaching,
                    removingReferenceId = removingAttachmentReferenceId,
                    onAttachClick = onAttachClick,
                    onRemoveAttachment = onRemoveAttachment
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Correct Voucher is only offered for a still-live (not already cancelled), not
                // already-corrected, correction-eligible voucher - correcting an already-corrected
                // one would leave two "corrected by" links pointing at the same original.
                // Extend-correction-to-all-types fix - Sale/Purchase additionally requires
                // account-only mode (see VOUCHER_CORRECTION_ELIGIBLE_TYPES's doc comment); every
                // other eligible type is ungated by isInventoryEnabled.
                val isSaleOrPurchase = voucher.voucherType == com.example.accounting.domain.accounting.VoucherType.SALES ||
                    voucher.voucherType == com.example.accounting.domain.accounting.VoucherType.PURCHASE
                val canCorrect = onCorrectVoucher != null && !voucher.isCancelled && correctedByVoucher == null &&
                    voucher.voucherType in VOUCHER_CORRECTION_ELIGIBLE_TYPES &&
                    (!isSaleOrPurchase || !isInventoryEnabled)

                Column(modifier = Modifier.fillMaxWidth()) {
                    // Real-device QA fix - up to three OutlinedButtons (Delete/Correct/Preview &
                    // Share) plus Close never fit on a narrow phone in one row; squeezing them all
                    // into a single Modifier.weight(fill = false) + horizontalScroll row (the first
                    // attempt) silently dropped the third button from layout entirely. Two simple
                    // rows - a scrollable action-button row, Close on its own row below - avoids
                    // that weight/scroll interaction and is far more predictable.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // An already-cancelled voucher has nothing left to cancel - the repository's
                        // own idempotency guard already rejects a second call, but hiding the button
                        // here is the real fix: a cancelled voucher must never look like a live one
                        // with an available action, which is what the CANCELLED badge above exists
                        // to prevent from being missed.
                        if (onDeleteVoucher != null && !voucher.isCancelled) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Delete Voucher")
                            }
                        }
                        if (canCorrect) {
                            Spacer(modifier = Modifier.width(8.dp))
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    onCorrectVoucher!!(voucher)
                                    onDismiss()
                                }
                            ) {
                                Text("Correct Voucher")
                            }
                        }
                        if (onPreviewInvoice != null && !voucher.isCancelled &&
                            (voucher.voucherType == VoucherType.SALES || voucher.voucherType == VoucherType.PURCHASE)
                        ) {
                            Spacer(modifier = Modifier.width(8.dp))
                            androidx.compose.material3.OutlinedButton(onClick = { onPreviewInvoice(voucher) }) {
                                Text("Preview & Share")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this voucher?") },
            text = {
                Text(
                    "This removes ${voucher.voucherNumber} and its accounting entries entirely - " +
                        "ledger balances are reversed correctly, but nothing about this voucher will be " +
                        "visible anywhere afterward. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteVoucher?.invoke(voucher)
                        onDismiss()
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * "one barcode more ... on invoice" - a real, locally-encoded QR ([QrCodeImage], zxing) over a
 * plain, self-describing invoice-identity string built entirely from data already shown in this
 * dialog: invoice number, date, amount, seller GSTIN, buyer GSTIN (when known). This is NOT a GST
 * e-invoice IRN QR (that requires the government IRP API, which this app does not integrate with,
 * per the app's honest-gateway rule) - it exists purely so this specific invoice can be scanned
 * back up later (e.g. for a customer's own record, or the business's own filing), never sent
 * anywhere or relied on by any accounting/GST calculation.
 */
@Composable
private fun InvoiceQrSection(voucher: Voucher, companyGstin: String) {
    var expanded by remember(voucher.voucherId) { mutableStateOf(false) }
    val content = remember(voucher.voucherId, companyGstin) {
        buildString {
            append("INVOICE|NO:").append(voucher.voucherNumber)
            append("|DATE:").append(voucher.date)
            append("|AMT:").append(voucher.totalDebits.coerceMaxOf(voucher.totalCredits).formatPlain())
            append("|SELLER_GSTIN:").append(companyGstin)
            if (voucher.partyGstin.isNotBlank()) append("|BUYER_GSTIN:").append(voucher.partyGstin)
        }
    }
    androidx.compose.material3.Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invoice QR", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCodeImage(content = content, modifier = Modifier.size(180.dp))
                }
            }
        }
    }
}

private fun com.example.accounting.core.common.Money.coerceMaxOf(other: com.example.accounting.core.common.Money) =
    if (this.paise >= other.paise) this else other

/**
 * Product correction ("THIS APPLICATION IS NOT AN ERP") - the plain business Bill/Invoice a
 * non-accountant recognizes: Party, Items/description, Qty/Rate/Discount only when applicable,
 * GST, Total. Never "Ledger"/"Dr"/"Cr"/"Debit"/"Credit" - that table still exists, just behind the
 * explicit "Advanced > View Accounting Entries" toggle in [VoucherDetailDialog].
 *
 * Prefers [documentData] (real item-level data - the exact same [DocumentData]/
 * [InvoiceSummaryCalculator] Invoice Preview and the PDF both already use, so this view can never
 * disagree with either) when present; otherwise falls back to [billSummary] (party/GST/total, no
 * item breakdown - an account-only Sale/Purchase, or any non-trading voucher type); shows a plain
 * loading line only if neither has arrived yet (the async load this dialog kicks off on open).
 */
@Composable
private fun BillSummarySection(voucher: Voucher, documentData: DocumentData?, billSummary: VoucherBillSummary?) {
    if (documentData != null) {
        val summary = InvoiceSummaryCalculator.from(documentData)
        val counterparty = if (documentData.documentType == DocumentType.PURCHASE_BILL) documentData.seller else documentData.buyer
        val partyLabel = if (documentData.documentType == DocumentType.PURCHASE_BILL) "Supplier" else "Customer"

        Text(partyLabel, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(counterparty.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        if (counterparty.gstin.isNotBlank()) {
            Text("GSTIN: ${counterparty.gstin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Item / Service", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(2f))
            Text("Amount", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        documentData.items.forEach { line ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(line.description, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(2f))
                    Text(line.lineTotal.formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                }
                val qtyRate = line.quantity?.let { "Qty ${"%.2f".format(it.rawValue / 1000.0)} x ${line.rate.formatPlain()}" } ?: "Rate ${line.rate.formatPlain()}"
                val discount = if (line.discount.isPositive) "  Disc ${line.discount.formatPlain()}" else ""
                val hsn = line.hsnSacCode.ifBlank { "-" }
                Text(
                    "HSN $hsn  $qtyRate$discount  GST ${line.gstRatePercent}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        BillTotalsRows("Taxable Amount", summary.taxableAmount.formatPlain())
        when (summary.taxColumnMode) {
            TaxColumnMode.CGST_SGST -> { BillTotalsRows("CGST", summary.cgst.formatPlain()); BillTotalsRows("SGST", summary.sgst.formatPlain()) }
            TaxColumnMode.IGST -> BillTotalsRows("IGST", summary.igst.formatPlain())
            TaxColumnMode.NONE -> {}
        }
        if (summary.cess.isPositive) BillTotalsRows("CESS", summary.cess.formatPlain())
        HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(vertical = 6.dp))
        BillTotalsRows("Total", summary.grandTotal.formatPlain(), emphasize = true)
    } else if (billSummary != null) {
        if (billSummary.partyLabel.isNotBlank()) {
            Text(billSummary.partyLabel, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(billSummary.partyName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(modifier = Modifier.height(10.dp))
        }
        billSummary.taxableAmount?.let { BillTotalsRows("Amount", it.formatPlain()) }
        if (billSummary.hasGst) {
            if (billSummary.cgst.isPositive) BillTotalsRows("CGST", billSummary.cgst.formatPlain())
            if (billSummary.sgst.isPositive) BillTotalsRows("SGST", billSummary.sgst.formatPlain())
            if (billSummary.igst.isPositive) BillTotalsRows("IGST", billSummary.igst.formatPlain())
            if (billSummary.cess.isPositive) BillTotalsRows("CESS", billSummary.cess.formatPlain())
        }
        HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(vertical = 6.dp))
        BillTotalsRows("Total", billSummary.totalAmount.formatPlain(), emphasize = true)
    } else {
        Text("Loading bill...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        BillTotalsRows("Total", voucher.totalDebits.coerceMaxOf(voucher.totalCredits).formatPlain(), emphasize = true)
    }
}

@Composable
private fun BillTotalsRows(label: String, value: String, emphasize: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            style = (if (emphasize) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium)
                .copy(fontFamily = FontFamily.Monospace),
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
