package com.example.accounting.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Base component - a date-picking text field. Not previously built because nothing in this app
 * currently calls it (every voucher date is set from `LocalDate.now()`, with no UI date-picker
 * anywhere) - it is a real, generic primitive now available for a future screen that needs one
 * (e.g. backdating a voucher), without that screen having to build its own
 * `DatePickerDialog`/`rememberDatePickerState` wiring. The calendar trailing icon is the tap
 * target that opens the picker - the standard, reliable pattern for a read-only Material3 field
 * (a read-only `OutlinedTextField` itself has no click callback to hook into). Never a business
 * rule about which dates are valid - a caller wanting a min/max range would extend this with an
 * explicit parameter once a real consumer needs it, not invented speculatively here.
 *
 * [selectedDate] may be `null` ("no date chosen" - shown as an empty field, never defaulted to today);
 * the picker then opens on today only as a starting point. When [onCleared] is supplied, a chosen
 * date can be removed again (back to `null`) with a close icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onCleared: (() -> Unit)? = null
) {
    var pickerOpen by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = selectedDate?.toString() ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            Row {
                if (onCleared != null && selectedDate != null) {
                    IconButton(onClick = onCleared) { Icon(Icons.Default.Close, contentDescription = "Clear date") }
                }
                IconButton(onClick = { pickerOpen = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date")
                }
            }
        },
        modifier = modifier
    )

    if (pickerOpen) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (selectedDate ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    pickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}
