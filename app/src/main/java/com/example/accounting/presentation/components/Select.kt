package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Phase UI-03: the one generic dropdown-select primitive - consolidates a pattern that was
 * independently hand-rolled at least twice (`CreateVoucherDialog`'s and
 * `MoneyVoucherEntryScreen`'s private `LedgerDropdown`, both Ledger-specific). Generic over [T] via
 * [optionLabel] so it is never tied to Ledger or any other domain type - a future caller selecting
 * a Party, a GstRegistrationStatus, a payment term, etc. reaches for this instead of writing a
 * fourth near-identical `ExposedDropdownMenuBox`. Existing call sites are not retrofitted in this
 * pass (out of scope - both already work, already tested, already fixed once this session for a
 * real trailing-lambda bug; swapping them carries re-verification cost not justified as part of
 * adding a missing component).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectField(
    label: String,
    options: List<T>,
    selectedOption: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    emptyOptionsLabel: String = "None yet",
    addNewLabel: String? = null,
    onAddNew: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = selectedOption?.let(optionLabel) ?: placeholder

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (addNewLabel != null && onAddNew != null) {
                DropdownMenuItem(
                    text = { Text(addNewLabel, color = MaterialTheme.colorScheme.primary) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { expanded = false; onAddNew() }
                )
                if (options.isNotEmpty()) HorizontalDivider()
            }
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(emptyOptionsLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { expanded = false },
                    enabled = false
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}
