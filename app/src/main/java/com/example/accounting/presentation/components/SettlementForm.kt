package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.trading.OutstandingInvoice

/**
 * Receipt/Payment settlement + outstanding-invoice allocation form used inside [CreateVoucherDialog].
 * Split into its own file (Lightweight pass) purely to keep `CreateVoucherDialog.kt` from growing
 * into a single giant file - no behavior change from the original inline version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettlementForm(
    isReceipt: Boolean,
    eligibleParties: List<Ledger>,
    partyLedgerId: String,
    onPartyLedgerChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    cashBankLedgers: List<Ledger>,
    cashBankLedgerId: String,
    onCashBankLedgerChange: (String) -> Unit,
    paymentMode: String,
    onPaymentModeChange: (String) -> Unit,
    outstandingInvoices: List<OutstandingInvoice>,
    allocationInputs: Map<String, String>,
    onAllocationChange: (String, String) -> Unit,
    amountInput: String,
    onAmountChange: (String) -> Unit,
    totalAllocated: Money,
    unallocatedRemainder: Money,
    onAddNewParty: () -> Unit = {},
    onAddNewBankLedger: () -> Unit = {}
) {
    var cashBankDropdownExpanded by remember { mutableStateOf(false) }
    val ledgersById = remember(eligibleParties, cashBankLedgers) { (eligibleParties + cashBankLedgers).associateBy { it.ledgerId } }

    Text(
        if (isReceipt) "Receipt - Money from Customer" else "Payment - Money to Supplier",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    )
    Spacer(modifier = Modifier.height(8.dp))

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = ledgersById[partyLedgerId]?.name ?: if (isReceipt) "Select Customer" else "Select Supplier",
            onValueChange = {}, readOnly = true,
            label = { Text(if (isReceipt) "Customer" else "Supplier") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            eligibleParties.forEach { led ->
                DropdownMenuItem(text = { Text(led.name) }, onClick = { onPartyLedgerChange(led.ledgerId); onExpandedChange(false) })
            }
            DropdownMenuItem(
                text = { Text("+ Add New ${if (isReceipt) "Customer" else "Supplier"}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                onClick = { onExpandedChange(false); onAddNewParty() }
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (partyLedgerId.isNotBlank()) {
        Text("Outstanding Invoices", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(4.dp))
        if (outstandingInvoices.isEmpty()) {
            Text("No outstanding invoices - this will be recorded as an advance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            outstandingInvoices.forEach { inv ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(inv.voucherNumber, style = MaterialTheme.typography.bodyMedium)
                        Text("Outstanding ${inv.outstandingAmount.formatPlain()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = allocationInputs[inv.voucherId] ?: "",
                        onValueChange = { onAllocationChange(inv.voucherId, it) },
                        placeholder = { Text("0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = { outstandingInvoices.forEach { onAllocationChange(it.voucherId, (it.outstandingAmount.paise / 100.0).toString()) } }) {
            Text("Allocate Full Outstanding")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    ExposedDropdownMenuBox(expanded = cashBankDropdownExpanded, onExpandedChange = { cashBankDropdownExpanded = it }) {
        OutlinedTextField(
            value = ledgersById[cashBankLedgerId]?.name ?: "Select Cash/Bank Account",
            onValueChange = {}, readOnly = true,
            label = { Text("Settlement Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cashBankDropdownExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = cashBankDropdownExpanded, onDismissRequest = { cashBankDropdownExpanded = false }) {
            cashBankLedgers.forEach { led ->
                DropdownMenuItem(text = { Text(led.name) }, onClick = { onCashBankLedgerChange(led.ledgerId); cashBankDropdownExpanded = false })
            }
            DropdownMenuItem(
                text = { Text("+ Add New Bank Account", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                onClick = { cashBankDropdownExpanded = false; onAddNewBankLedger() }
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text("Payment Mode", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("CASH", "BANK", "UPI").forEach { mode ->
            FilterChip(selected = paymentMode == mode, onClick = { onPaymentModeChange(mode) }, label = { Text(mode) })
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = amountInput, onValueChange = onAmountChange,
        label = { Text(if (isReceipt) "Amount Received" else "Amount Paid") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("voucher_amount_input")
    )

    if (totalAllocated.isPositive || Money.parse(amountInput).isPositive) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Allocated ${totalAllocated.formatPlain()} - ${if (unallocatedRemainder.paise < 0) "Exceeds amount by ${unallocatedRemainder.abs().formatPlain()}" else "Unallocated (Advance) ${unallocatedRemainder.formatPlain()}"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (unallocatedRemainder.paise < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
