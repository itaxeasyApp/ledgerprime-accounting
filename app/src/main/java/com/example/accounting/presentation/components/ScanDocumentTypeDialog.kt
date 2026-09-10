package com.example.accounting.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.ocr.OcrDocumentType

/**
 * Document/Image Scan entry point - the "which kind of document is this?" step the user picks
 * *before* the Photo Picker opens, so [OcrDocumentType] is a real hint the scan is told up front,
 * never a post-hoc guess. Every scan type now has a contextual home (docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md,
 * docs/CORRECTIONS_LOG.md) - there is no more generic "scan anything" entry point; [options] is
 * always the 1-2 types that make sense on the screen this is opened from:
 * - Sales screen: Sales Invoice, UPI Payment
 * - Purchases screen: Purchase Bill, UPI Payment
 * - Profile & Business Setup: PAN Card, Aadhaar Card, GST Certificate
 * - Money's Bank ledger / Receive-Pay voucher entry: Bank Statement, UPI Payment
 *
 * Expense Receipt and Other Document were dropped entirely (no contextual home was ever
 * requested for either).
 */
@Composable
fun ScanTypePickerDialog(options: List<Pair<String, OcrDocumentType>>, onDismiss: () -> Unit, onSelect: (OcrDocumentType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan a Document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Choose what you're scanning - fields are extracted as a suggestion for you to review; nothing is created or posted automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                options.forEach { (label, type) -> ScanTypeRow(label, type, onSelect) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScanTypeRow(label: String, type: OcrDocumentType, onSelect: (OcrDocumentType) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(type) }
            .padding(vertical = 10.dp)
    )
}
