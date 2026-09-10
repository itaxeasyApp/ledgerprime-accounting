package com.example.accounting.presentation.features.invoice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.DocumentTemplate
import com.example.accounting.domain.rendering.InvoiceSummaryCalculator
import com.example.accounting.domain.rendering.TaxColumnMode

/**
 * "5 Invoice PDF Templates" task - a real Compose preview of [DocumentData] before generating/
 * sharing the PDF, plus the 5-template picker. Deliberately not a pixel-identical bitmap of the
 * PDF (that would need rasterizing [com.example.accounting.data.rendering.PdfDocumentRenderer]'s
 * canvas, extra complexity for a preview whose job is "does this look right, is this the invoice I
 * mean" not "exact print proof") - every number shown comes from the same [InvoiceSummaryCalculator]/
 * [com.example.accounting.domain.rendering.TaxColumnSelector] the PDF renderer itself calls, so the
 * two never disagree on what to show, only on pixel layout.
 */
@Composable
fun InvoicePreviewScreen(
    data: DocumentData?,
    templates: List<DocumentTemplate>,
    selectedTemplateId: String?,
    errorMessage: String?,
    onBack: () -> Unit,
    onSelectTemplate: (String) -> Unit,
    onSetAsDefault: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onShareCsv: () -> Unit = {},
    onShareExcel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Invoice Preview", style = MaterialTheme.typography.titleLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "show me UI for PDF CSV Excel" (docs/CORRECTIONS_LOG.md) - CSV/Excel sit next to
                // Print/Share as plain text buttons (no TableChart/GridOn icon exists in this app's
                // icon set - only material-icons-core is a dependency, not -extended) rather than a
                // guessed icon that might not resolve.
                androidx.compose.material3.TextButton(onClick = onShareCsv, enabled = data != null) { Text("CSV") }
                androidx.compose.material3.TextButton(onClick = onShareExcel, enabled = data != null) { Text("Excel") }
                IconButton(onClick = onPrint, enabled = data != null) { Icon(Icons.Default.Print, contentDescription = "Print") }
                IconButton(onClick = onShare, enabled = data != null) { Icon(Icons.Default.Share, contentDescription = "Share PDF") }
            }
        }

        if (errorMessage != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(errorMessage, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }

        if (data == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (errorMessage == null) CircularProgressIndicator()
            }
            return
        }

        val selectedTemplate = templates.firstOrNull { it.templateId == selectedTemplateId }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            templates.forEach { template ->
                TemplateSwatchCard(
                    template = template,
                    selected = template.templateId == selectedTemplateId,
                    onClick = { onSelectTemplate(template.templateId) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onSetAsDefault, enabled = selectedTemplate != null && !selectedTemplate.isDefault) {
                Text(if (selectedTemplate?.isDefault == true) "Default template" else "Set as Default")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        val accent = selectedTemplate?.visualConfig?.colors?.primary?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            ?: MaterialTheme.colorScheme.primary

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { InvoicePreviewCard(data = data, accent = accent) }
        }
    }
}

@Composable
private fun TemplateSwatchCard(template: DocumentTemplate, selected: Boolean, onClick: () -> Unit) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(template.visualConfig.colors.primary)) }.getOrNull() ?: MaterialTheme.colorScheme.primary
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.width(110.dp).padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(accent))
            Spacer(modifier = Modifier.height(6.dp))
            Text(template.templateName, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
            if (template.isDefault) {
                Spacer(modifier = Modifier.height(2.dp))
                Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun InvoicePreviewCard(data: DocumentData, accent: Color) {
    val summary = InvoiceSummaryCalculator.from(data)
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(data.seller.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = accent))
                    if (data.seller.gstin.isNotBlank()) Text("GSTIN: ${data.seller.gstin}", style = MaterialTheme.typography.bodySmall)
                    if (data.seller.stateCode.isNotBlank()) Text("State: ${data.seller.stateCode} - ${data.seller.stateName}", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(data.documentType.name.replace('_', ' '), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Text("No: ${data.documentNumber}", style = MaterialTheme.typography.bodySmall)
                    Text("Date: ${data.documentDate}", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text("Bill To", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            Text(data.buyer.name, style = MaterialTheme.typography.bodyMedium)
            if (data.buyer.gstin.isNotBlank()) Text("GSTIN: ${data.buyer.gstin}", style = MaterialTheme.typography.bodySmall)
            if (data.buyer.stateCode.isNotBlank()) Text("State: ${data.buyer.stateCode} - ${data.buyer.stateName}", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text("Items", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(6.dp))
            data.items.forEachIndexed { index, line ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}. ${line.description}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(line.lineTotal.formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    }
                    val qtyText = line.quantity?.let { "Qty ${"%.2f".format(it.rawValue / 1000.0)} x ${line.rate.formatPlain()}" } ?: "Rate ${line.rate.formatPlain()}"
                    val discountText = if (line.discount.isPositive) "  Disc ${line.discount.formatPlain()}" else ""
                    val hsn = line.hsnSacCode.ifBlank { "-" }
                    Text(
                        "HSN $hsn  $qtyText$discountText  GST ${line.gstRatePercent}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (index != data.items.lastIndex) HorizontalDivider(thickness = 0.5.dp)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            TotalsRow("Total Quantity", "%.2f".format(summary.totalQuantity))
            TotalsRow("Item Amount", summary.itemAmount.formatPlain())
            if (summary.totalDiscount.isPositive) TotalsRow("Discount", summary.totalDiscount.formatPlain())
            TotalsRow("Taxable Amount", summary.taxableAmount.formatPlain())
            when (summary.taxColumnMode) {
                TaxColumnMode.CGST_SGST -> {
                    TotalsRow("CGST", summary.cgst.formatPlain())
                    TotalsRow("SGST", summary.sgst.formatPlain())
                }
                TaxColumnMode.IGST -> TotalsRow("IGST", summary.igst.formatPlain())
                TaxColumnMode.NONE -> {}
            }
            if (summary.cess.isPositive) TotalsRow("CESS", summary.cess.formatPlain())
            TotalsRow("Total GST", summary.totalGst.formatPlain())
            if (summary.roundOff.paise != 0L) TotalsRow("Round Off", summary.roundOff.formatPlain())
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            TotalsRow("Grand Total", summary.grandTotal.formatPlain(), emphasize = true, accent = accent)
            Spacer(modifier = Modifier.height(10.dp))
            Text("Amount in Words: ${summary.amountInWords}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (data.paymentInformation.bankName.isNotBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text("Bank Details", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Text("${data.paymentInformation.bankName}  A/c ${data.paymentInformation.bankAccountNumber}", style = MaterialTheme.typography.bodySmall)
                if (data.paymentInformation.bankIfsc.isNotBlank()) {
                    Text("IFSC ${data.paymentInformation.bankIfsc}  ${data.paymentInformation.bankBranch}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TotalsRow(label: String, value: String, emphasize: Boolean = false, accent: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodySmall
        )
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodySmall,
            color = if (emphasize && accent != Color.Unspecified) accent else MaterialTheme.colorScheme.onSurface
        )
    }
}
