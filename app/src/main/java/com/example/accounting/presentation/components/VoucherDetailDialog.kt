package com.example.accounting.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Voucher types [onCorrectVoucher] supports - deliberately Contra/Journal only. Both are always a
 * flat, unconditional 2-line debit/credit voucher (no invoice allocation, no GST, no stock),
 * exactly what [CreateVoucherDialog]'s prefill can always reconstruct byte-for-byte from
 * [Voucher.items]. Receipt/Payment build through the Settlement form instead (invoice allocation
 * against outstanding invoices) - correctly prefilling a REPOST of that allocation state is a
 * meaningfully harder, higher-risk problem than this pass takes on, so those keep today's plain
 * "Delete & Reverse then re-enter" flow unchanged; Sale/Purchase/Notes similarly keep their
 * existing correction mechanism (Credit/Debit Note, or cancel+repost from scratch). */
val VOUCHER_CORRECTION_ELIGIBLE_TYPES = setOf(
    com.example.accounting.domain.accounting.VoucherType.CONTRA,
    com.example.accounting.domain.accounting.VoucherType.JOURNAL
)

@Composable
fun VoucherDetailDialog(
    voucher: Voucher,
    onDismiss: () -> Unit,
    onDeleteVoucher: ((Voucher) -> Unit)? = null,
    /** Architecture correction (Voucher Correct workflow) - only ever shown for
     * [VOUCHER_CORRECTION_ELIGIBLE_TYPES]; `null` (the default) hides the affordance entirely for
     * callers that don't wire it. */
    onCorrectVoucher: ((Voucher) -> Unit)? = null,
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
    onRemoveAttachment: (VoucherAttachmentRow) -> Unit = {}
) {
    // Architecture correction - "Corrects X" (this voucher has a referenceVoucherId pointing at an
    // earlier one of the SAME type) / "Corrected by Y" (some other same-type voucher points back at
    // this one) - same-type-only so this never collides with the unrelated Credit/Debit Note
    // referenceVoucherId usage (a Note is always a different voucherType than its original).
    val correctsOriginal = voucher.referenceVoucherId
        ?.let { refId -> allVouchers.firstOrNull { it.voucherId == refId && it.voucherType == voucher.voucherType } }
    val correctedByVoucher = allVouchers.firstOrNull { it.referenceVoucherId == voucher.voucherId && it.voucherType == voucher.voucherType }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        }
                        Text(
                            text = "Date: ${voucher.date} | Ref: ${voucher.referenceNumber.ifBlank { "N/A" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

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

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(voucher.items) { item ->
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

                Spacer(modifier = Modifier.height(14.dp))

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
                val canCorrect = onCorrectVoucher != null && !voucher.isCancelled && correctedByVoucher == null &&
                    voucher.voucherType in VOUCHER_CORRECTION_ELIGIBLE_TYPES

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onDeleteVoucher != null) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    onDeleteVoucher(voucher)
                                    onDismiss()
                                },
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Delete & Reverse")
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
                    }

                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
