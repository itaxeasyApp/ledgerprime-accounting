package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.ocr.OcrExtractionResult

/**
 * Field Validation / User Review step of the Document/Image -> OCR Extraction -> Field Validation
 * -> User Review/Edit -> Draft -> Existing Voucher/Invoice Engine -> User Explicit Post pipeline.
 *
 * Shown as a real modal `Dialog` from [com.example.accounting.presentation.MainAppScreen] itself
 * (not embedded in one specific screen) - a scan can now start from Sales, Purchases, Profile, or
 * Money's Bank ledger/voucher entry (docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md), so the review step
 * must be visible regardless of which screen triggered it, not just when the user happens to be on
 * Data Tools. Routes by [OcrExtractionResult.documentType] - never the hint the user picked before
 * scanning, since the extractor is always free to override a wrong hint:
 * - Invoice-like/Bank/UPI: a `PENDING_REVIEW` voucher draft already exists (Money > Pending
 *   Reviews) by the time this dialog renders - this is a read-only summary of what was detected,
 *   with a shortcut into that existing review queue.
 * - PAN/Aadhaar: an editable Individual Profile Draft - nothing is written until "Apply to Profile".
 * - GST Certificate: an editable Business Profile Draft (trade name/GSTIN only, never Company's
 *   statutory GSTIN - see docs/57_BUSINESS_IDENTITY_DISPLAY.md).
 * - Everything else: the raw recognized text only, no structured guesses to act on.
 */
@Composable
fun OcrReviewDialog(
    extraction: OcrExtractionResult,
    onApplyOcrProfileDraft: (name: String, pan: String) -> Unit,
    onApplyOcrBusinessProfileDraft: (businessName: String, gstin: String) -> Unit,
    onOpenOcrPendingReviews: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
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
                        var name by remember(extraction.sourceAssetId) { mutableStateOf(extraction.personNameGuess ?: "") }
                        var number by remember(extraction.sourceAssetId) { mutableStateOf(extraction.documentNumberGuess ?: "") }
                        Text(
                            "Review before applying - only Name and PAN are saved to your Individual Profile today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = number, onValueChange = { number = it },
                            label = { Text(if (extraction.documentType == OcrDocumentType.PAN_CARD) "PAN" else "Aadhaar Number (reference only, not saved)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        extraction.dateOfBirthGuess?.let {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Date of Birth (reference only, not saved): $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(
                                onClick = { onApplyOcrProfileDraft(name, if (extraction.documentType == OcrDocumentType.PAN_CARD) number else "") },
                                enabled = name.isNotBlank()
                            ) { Text("Apply to Profile") }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onDismiss) { Text("Dismiss") }
                        }
                    }

                    OcrDocumentType.GST_CERTIFICATE -> {
                        var businessName by remember(extraction.sourceAssetId) { mutableStateOf(extraction.personNameGuess ?: "") }
                        var gstin by remember(extraction.sourceAssetId) { mutableStateOf(extraction.documentNumberGuess ?: "") }
                        Text(
                            "Review before applying - this updates your Business Profile's trade name/GSTIN " +
                                "(shown on invoices), never the registered GSTIN used for GST filing - change " +
                                "that in Settings > My Business > GST Details instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = businessName, onValueChange = { businessName = it }, label = { Text("Business / Trade Name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = gstin, onValueChange = { gstin = it }, label = { Text("GSTIN") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            Button(
                                onClick = { onApplyOcrBusinessProfileDraft(businessName, gstin) },
                                enabled = businessName.isNotBlank()
                            ) { Text("Apply to Business Profile") }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onDismiss) { Text("Dismiss") }
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
    }
}
