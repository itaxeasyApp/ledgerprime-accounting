package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.core.common.Money

/**
 * Creates a [com.example.accounting.domain.inventory.StockItem] with its HSN/SAC and GST rate on
 * file (Phase 5, Priority 1/3) - the prerequisite for item-driven GST: the Sale/Purchase item
 * picker reads these facts back from here, the user never types/picks a rate freely on a voucher.
 */
@Composable
fun CreateStockItemDialog(
    onDismiss: () -> Unit,
    onCreateItem: (String, String, String, String, Double, Double, Money) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var hsnCode by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("Nos") }
    var gstRate by remember { mutableStateOf(18.0) }
    var openingQtyInput by remember { mutableStateOf("0") }
    var openingRateInput by remember { mutableStateOf("0") }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false - required for navigationBarsPadding() below to have any
        // effect inside a Dialog's separate window (see CreateLedgerDialog's fuller note).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("New Item", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                HorizontalDivider()

            Column(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item Name") }, modifier = Modifier.fillMaxWidth().testTag("item_name_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = sku, onValueChange = { sku = it },
                    label = { Text("SKU / Code (Optional)") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hsnCode, onValueChange = { hsnCode = it },
                        label = { Text("HSN/SAC Code") }, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it },
                        label = { Text("Unit") }, modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("GST Rate", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.0, 5.0, 12.0, 18.0, 28.0).forEach { rate ->
                        FilterChip(
                            selected = gstRate == rate,
                            onClick = { gstRate = rate },
                            label = { Text("${rate.toInt()}%") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = openingQtyInput, onValueChange = { openingQtyInput = it },
                        label = { Text("Opening Quantity") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = openingRateInput, onValueChange = { openingRateInput = it },
                        label = { Text("Opening Rate") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onCreateItem(
                                name, sku, hsnCode, unit, gstRate,
                                openingQtyInput.toDoubleOrNull() ?: 0.0,
                                Money.parse(openingRateInput)
                            )
                            onDismiss()
                        },
                        enabled = name.isNotBlank() && unit.isNotBlank(),
                        modifier = Modifier.testTag("submit_item_button")
                    ) {
                        Text("Create Item")
                    }
                }
            }
        }
    }
}
