package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.accounting.Voucher

/**
 * Credit/Debit Note original-voucher picker used inside [CreateVoucherDialog]. Split into its own
 * file (Lightweight pass) purely to keep `CreateVoucherDialog.kt` from growing into a single giant
 * file - no behavior change from the original inline version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteForm(
    isCredit: Boolean,
    eligibleOriginals: List<Voucher>,
    originalVoucherId: String,
    onOriginalVoucherChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Text(
        if (isCredit) "Credit Note - Sales Return / Adjustment" else "Debit Note - Purchase Return / Adjustment",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "The original ${if (isCredit) "Sale" else "Purchase"} is never modified - this creates a new, linked reversal document.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))

    val selected = eligibleOriginals.find { it.voucherId == originalVoucherId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = selected?.let { "${it.voucherNumber} - ${it.totalAmount.formatPlain()}" } ?: "Select Original ${if (isCredit) "Sale" else "Purchase"}",
            onValueChange = {}, readOnly = true,
            label = { Text("Original ${if (isCredit) "Sale Invoice" else "Purchase Bill"}") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (eligibleOriginals.isEmpty()) {
                DropdownMenuItem(text = { Text("No eligible ${if (isCredit) "sales" else "purchases"} found") }, onClick = {}, enabled = false)
            }
            eligibleOriginals.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v.voucherNumber} - ${v.totalAmount.formatPlain()} (${v.date})") },
                    onClick = { onOriginalVoucherChange(v.voucherId); onExpandedChange(false) }
                )
            }
        }
    }
}
