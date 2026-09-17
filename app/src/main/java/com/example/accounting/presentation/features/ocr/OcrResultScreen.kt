package com.example.accounting.presentation.features.ocr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.ocr.OcrExtractionResult

/**
 * Dashboard OCR workflow, step 2 of 2 (user-supplied correction) - a real navigation destination
 * ([com.example.accounting.presentation.navigation.AppRoute.OcrResult]), not a Dialog/AlertDialog.
 * Same content/branches [com.example.accounting.presentation.components.OcrReviewDialog] used to
 * render as a popup - this is that same logic moved into a full screen, reused as the ONE shared
 * review/edit step every scan entry point in the app (Dashboard, Sales, Purchases, Profile, Money)
 * lands on once extraction completes, per [OcrExtractionResult.documentType]:
 * - Invoice-like/Bank/UPI: a `PENDING_REVIEW` voucher draft already exists (Money > Pending
 *   Reviews) by the time this screen renders - this is a read-only summary of what was detected,
 *   with a shortcut into that existing, already-full-screen review queue
 *   ([com.example.accounting.presentation.features.money.VoucherDraftEditorScreen]).
 * - PAN/Aadhaar: an editable Individual Profile Draft - nothing is written until "Apply to Profile".
 * - GST Certificate: an editable Business Profile Draft (trade name/GSTIN only).
 * - Everything else: the raw recognized text only, no structured guesses to act on.
 */
@Composable
fun OcrResultScreen(
    extraction: OcrExtractionResult,
    onApplyOcrProfileDraft: (name: String, pan: String) -> Unit,
    onApplyOcrBusinessProfileDraft: (businessName: String, gstin: String) -> Unit,
    onOpenOcrPendingReviews: () -> Unit,
    onDismiss: () -> Unit,
    /** The scanned source image's own file path - see [OcrScannedImagePreview]'s doc comment.
     * `null` (any caller that doesn't pass it) simply skips the image preview, same as before this
     * was added. */
    sourceImagePath: String? = null,
    /** Called when the user saves a crop/rotate edit (see [OcrEditableImagePreview]) with the new
     * file's path, so the caller can persist it back into the shared OCR state - the edited image,
     * not the original, becomes what this screen (and any later re-render) shows and applies from.
     * Defaults to a no-op so existing callers that don't care about persisting the edit still compile. */
    onImageEdited: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentImagePath by remember(extraction.sourceAssetId) { mutableStateOf(sourceImagePath) }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Scan Result", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Confidence: ${(extraction.confidenceScore * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        when (extraction.documentType) {
            OcrDocumentType.PURCHASE_BILL, OcrDocumentType.SALES_INVOICE, OcrDocumentType.EXPENSE_RECEIPT,
            OcrDocumentType.BANK_STATEMENT, OcrDocumentType.UPI_PAYMENT -> {
                extraction.vendorNameGuess?.let { Text("Vendor/Party: $it", style = MaterialTheme.typography.bodyMedium) }
                extraction.invoiceNumberGuess?.let { Text("Invoice No: $it", style = MaterialTheme.typography.bodyMedium) }
                extraction.vendorGstinGuess?.let { Text("GSTIN: $it", style = MaterialTheme.typography.bodyMedium) }
                extraction.documentDateGuess?.let { Text("Date: $it", style = MaterialTheme.typography.bodyMedium) }
                extraction.totalAmountGuess?.let { Text("Amount: ${it.format()}", style = MaterialTheme.typography.bodyMedium) }
                extraction.upiVpaGuess?.let { Text("UPI VPA: $it", style = MaterialTheme.typography.bodyMedium) }
                extraction.upiTransactionRefGuess?.let { Text("Txn Ref: $it", style = MaterialTheme.typography.bodyMedium) }
                if (extraction.lineItems.isNotEmpty()) {
                    Text("${extraction.lineItems.size} line item(s) detected", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "A draft has already been created for review - add ledger lines and post it from Money > Pending Reviews. Nothing has been posted yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Button(onClick = onOpenOcrPendingReviews) { Text("Open Pending Reviews") }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }

            OcrDocumentType.PAN_CARD, OcrDocumentType.AADHAAR_CARD -> {
                // Preview -> Edit -> Save -> Apply, per the reusable OcrPreviewCard/OcrPreviewActions/
                // OcrEditActions/OcrDeleteConfirmDialog components (see OcrComponents.kt) - name/number
                // are the committed values shown in the read-only preview; isEditing swaps that
                // preview for the same editable fields this screen already had, with its own local
                // Save/Cancel. "Apply to Profile" is unchanged - still the only thing that writes
                // anywhere - Save only commits the local edit into this screen's own preview state.
                var isEditing by remember(extraction.sourceAssetId) { mutableStateOf(false) }
                var showDeleteConfirm by remember(extraction.sourceAssetId) { mutableStateOf(false) }
                var name by remember(extraction.sourceAssetId) { mutableStateOf(extraction.personNameGuess ?: "") }
                var number by remember(extraction.sourceAssetId) { mutableStateOf(extraction.documentNumberGuess ?: "") }
                var draftName by remember(isEditing) { mutableStateOf(name) }
                var draftNumber by remember(isEditing) { mutableStateOf(number) }
                val isPan = extraction.documentType == OcrDocumentType.PAN_CARD
                val numberLabel = if (isPan) "PAN" else "Aadhaar Number"

                OcrEditableImagePreview(
                    imagePath = currentImagePath,
                    onImageSaved = { newPath -> currentImagePath = newPath; onImageEdited(newPath) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Review before applying - only Name and PAN are saved to your Individual Profile today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isEditing) {
                    OutlinedTextField(value = draftName, onValueChange = { draftName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftNumber, onValueChange = { draftNumber = it },
                        label = { Text(if (isPan) numberLabel else "$numberLabel (reference only, not saved)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    extraction.dateOfBirthGuess?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Date of Birth (reference only, not saved): $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OcrEditActions(
                        onSave = { name = draftName; number = draftNumber; isEditing = false },
                        onCancel = { isEditing = false }
                    )
                } else {
                    OcrPreviewCard(
                        title = if (isPan) "PAN Card" else "Aadhaar Card",
                        fields = listOfNotNull(
                            "Name" to name,
                            numberLabel to number,
                            extraction.dateOfBirthGuess?.let { "Date of Birth" to it.toString() }
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OcrPreviewActions(
                        applyLabel = "Apply to Profile",
                        applyEnabled = name.isNotBlank(),
                        onEdit = { isEditing = true },
                        onApply = { onApplyOcrProfileDraft(name, if (isPan) number else "") },
                        onDelete = { showDeleteConfirm = true }
                    )
                }
                if (showDeleteConfirm) {
                    OcrDeleteConfirmDialog(onConfirm = { showDeleteConfirm = false; onDismiss() }, onCancel = { showDeleteConfirm = false })
                }
            }

            OcrDocumentType.GST_CERTIFICATE -> {
                var isEditing by remember(extraction.sourceAssetId) { mutableStateOf(false) }
                var showDeleteConfirm by remember(extraction.sourceAssetId) { mutableStateOf(false) }
                var businessName by remember(extraction.sourceAssetId) { mutableStateOf(extraction.personNameGuess ?: "") }
                var gstin by remember(extraction.sourceAssetId) { mutableStateOf(extraction.documentNumberGuess ?: "") }
                var draftBusinessName by remember(isEditing) { mutableStateOf(businessName) }
                var draftGstin by remember(isEditing) { mutableStateOf(gstin) }

                OcrEditableImagePreview(
                    imagePath = currentImagePath,
                    onImageSaved = { newPath -> currentImagePath = newPath; onImageEdited(newPath) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Review before applying - this updates your Business Profile's trade name/GSTIN " +
                        "(shown on invoices), never the registered GSTIN used for GST filing - change " +
                        "that in Settings > My Business > GST Details instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isEditing) {
                    OutlinedTextField(value = draftBusinessName, onValueChange = { draftBusinessName = it }, label = { Text("Business / Trade Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = draftGstin, onValueChange = { draftGstin = it }, label = { Text("GSTIN") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    OcrEditActions(
                        onSave = { businessName = draftBusinessName; gstin = draftGstin; isEditing = false },
                        onCancel = { isEditing = false }
                    )
                } else {
                    OcrPreviewCard(
                        title = "GST Certificate",
                        fields = listOf("Business Name" to businessName, "GSTIN" to gstin)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OcrPreviewActions(
                        applyLabel = "Apply to Business Profile",
                        applyEnabled = businessName.isNotBlank(),
                        onEdit = { isEditing = true },
                        onApply = { onApplyOcrBusinessProfileDraft(businessName, gstin) },
                        onDelete = { showDeleteConfirm = true }
                    )
                }
                if (showDeleteConfirm) {
                    OcrDeleteConfirmDialog(onConfirm = { showDeleteConfirm = false; onDismiss() }, onCancel = { showDeleteConfirm = false })
                }
            }

            else -> {
                Text(
                    extraction.rawText.ifBlank { "No text recognized." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
