package com.example.accounting.presentation.features.ocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.presentation.components.AppBottomSheet
import com.example.accounting.presentation.components.SectionCard

/**
 * Dashboard OCR workflow, step 1 of 2 (user-supplied correction) - a real navigation destination
 * ([com.example.accounting.presentation.navigation.AppRoute.OcrScan]), not a Dialog/AlertDialog.
 * Reached from the Dashboard's OCR button. Selecting a document type opens [AppBottomSheet] (the
 * existing generic bottom-sheet shell - its own doc comment already named "an OCR document-upload
 * picker" as the anticipated use, before this) to choose Gallery vs Camera - a compact selection,
 * per this app's own "bottom sheets for selection only" rule, never a second full screen. Either
 * choice calls straight into the caller's existing scan pipeline
 * ([com.example.accounting.presentation.MainAppScreen]'s `launchDocumentScan`/camera-capture
 * launcher, the exact same pipeline every contextual scan entry point already uses) - this screen
 * only ever decides *which* [OcrDocumentType] hint and *which* image source, never how
 * scanning/extraction itself works.
 */
private data class ScanTypeOption(val label: String, val type: OcrDocumentType, val icon: ImageVector)

private val SCAN_TYPE_OPTIONS = listOf(
    ScanTypeOption("Sales Invoice", OcrDocumentType.SALES_INVOICE, Icons.Default.ReceiptLong),
    ScanTypeOption("Purchase Bill", OcrDocumentType.PURCHASE_BILL, Icons.Default.ShoppingCart),
    ScanTypeOption("UPI Payment", OcrDocumentType.UPI_PAYMENT, Icons.Default.Receipt),
    ScanTypeOption("Bank Statement", OcrDocumentType.BANK_STATEMENT, Icons.Default.AccountBalance),
    ScanTypeOption("PAN Card", OcrDocumentType.PAN_CARD, Icons.Default.Badge),
    ScanTypeOption("Aadhaar Card", OcrDocumentType.AADHAAR_CARD, Icons.Default.Badge),
    ScanTypeOption("GST Certificate", OcrDocumentType.GST_CERTIFICATE, Icons.Default.Verified)
)

@Composable
fun OcrScanScreen(
    onPickFromGallery: (OcrDocumentType) -> Unit,
    onCapturePhoto: (OcrDocumentType) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingType by remember { mutableStateOf<OcrDocumentType?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Scan a Document", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(
            "Choose what you're scanning - fields are extracted as a suggestion for you to review; nothing is created or posted automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(SCAN_TYPE_OPTIONS, key = { it.type }) { option ->
                SectionCard(
                    title = option.label,
                    onClick = { pendingType = option.type },
                    trailing = { Icon(option.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                ) {}
            }
        }
    }

    pendingType?.let { type ->
        ScanSourcePickerSheet(
            onDismiss = { pendingType = null },
            onPickFromGallery = { pendingType = null; onPickFromGallery(type) },
            onCapturePhoto = { pendingType = null; onCapturePhoto(type) }
        )
    }
}

/** Reusable Gallery-vs-Camera choice for any scan entry point - built once here, not tied to the
 * Dashboard flow specifically, so a future contextual picker (Sales/Purchases/Profile/Money) can
 * adopt the same choice without a second sheet implementation. */
@Composable
fun ScanSourcePickerSheet(onDismiss: () -> Unit, onPickFromGallery: () -> Unit, onCapturePhoto: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss) {
        Text("Add Document", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SectionCard(
                title = "Choose from Gallery",
                onClick = onPickFromGallery,
                trailing = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.fillMaxWidth()
            ) {}
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SectionCard(
                title = "Take Photo",
                onClick = onCapturePhoto,
                trailing = { Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.fillMaxWidth()
            ) {}
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
