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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.StandardSystemGroups

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateLedgerDialog(
    groups: List<AccountGroup>,
    initialGroupId: String? = null,
    onDismiss: () -> Unit,
    onCreateLedger: (String, String, Money, DrCr, String, String, String, String, String, String, Double, String, String, String, String, String, String) -> Unit
) {
    var ledgerName by remember { mutableStateOf("") }
    var selectedGroupId by remember { mutableStateOf(initialGroupId ?: groups.firstOrNull()?.groupId ?: "") }
    var openingBalanceInput by remember { mutableStateOf("0") }
    var balanceType by remember { mutableStateOf(DrCr.DEBIT) }
    var gstin by remember { mutableStateOf("") }
    var pan by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var hsnSac by remember { mutableStateOf("") }
    // Ledger Setup fix - optional, never required; State Code is the Place-of-Supply fact this
    // ledger needs before it can be used as a Sale/Purchase counterparty (Rule 29). Never defaulted
    // here to the company's own state (see AccountingViewModel.createLedger's own doc comment).
    var stateCode by remember { mutableStateOf("") }
    var pinCode by remember { mutableStateOf("") }
    // Audit fix (Company/Profile/Ledger Setup) - the Ledger domain model already carried
    // bankAccountNumber/bankIfsc with no UI ever collecting them; bankName/bankBranch are new
    // sibling fields (MIGRATION_19_20). Only shown/collected for a ledger actually under the
    // Bank group, mirroring the same groupId-prefix check every other Cash/Bank filter uses.
    var bankName by remember { mutableStateOf("") }
    var bankAccountNumber by remember { mutableStateOf("") }
    var bankIfsc by remember { mutableStateOf("") }
    var bankBranch by remember { mutableStateOf("") }

    var groupDropdownExpanded by remember { mutableStateOf(false) }
    val groupsMap = remember(groups) { groups.associateBy { it.groupId } }
    val isBankGroup = remember(selectedGroupId) {
        selectedGroupId.startsWith("${StandardSystemGroups.BANK_GROUP_ID}_")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "New Ledger",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Add a customer, supplier, or expense account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = ledgerName,
                    onValueChange = { ledgerName = it },
                    label = { Text("Ledger Name *") },
                    supportingText = { Text("The party, bank, or expense head this ledger tracks") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ledger_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                ExposedDropdownMenuBox(
                    expanded = groupDropdownExpanded,
                    onExpandedChange = { groupDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = groupsMap[selectedGroupId]?.name ?: "Select Under Group",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Under Account Group *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = groupDropdownExpanded,
                        onDismissRequest = { groupDropdownExpanded = false }
                    ) {
                        groups.forEach { grp ->
                            DropdownMenuItem(
                                text = { Text("${grp.name} (${grp.primaryGroup.displayName})") },
                                onClick = {
                                    selectedGroupId = grp.groupId
                                    balanceType = grp.primaryGroup.naturalBalance
                                    groupDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = openingBalanceInput,
                        onValueChange = { openingBalanceInput = it },
                        label = { Text("Opening Balance (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = balanceType == DrCr.DEBIT,
                            onClick = { balanceType = DrCr.DEBIT },
                            label = { Text("Debit") }
                        )
                        FilterChip(
                            selected = balanceType == DrCr.CREDIT,
                            onClick = { balanceType = DrCr.CREDIT },
                            label = { Text("Credit") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it.uppercase() },
                        label = { Text("GSTIN (Optional)") },
                        placeholder = { Text("27AAAAA0000A1Z5") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pan,
                        onValueChange = { pan = it.uppercase() },
                        label = { Text("PAN (Optional)") },
                        placeholder = { Text("AAAAA0000A") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address / City / State") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = stateCode,
                        onValueChange = { stateCode = it },
                        label = { Text("State Code (GST, Optional)") },
                        // Derived, display-only - never a second stored state-name field, same
                        // lookup Company profile/Customer-Supplier Setup already use.
                        supportingText = Constants.GST_STATE_CODES[stateCode]?.let { { Text(it) } },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = { pinCode = it },
                        label = { Text("PIN Code (Optional)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isBankGroup) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Bank Details",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text("Bank Name") },
                        placeholder = { Text("HDFC Bank") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = bankAccountNumber,
                            onValueChange = { bankAccountNumber = it },
                            label = { Text("A/c Number") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bankIfsc,
                            onValueChange = { bankIfsc = it.uppercase() },
                            label = { Text("IFSC") },
                            placeholder = { Text("HDFC0000123") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = bankBranch,
                        onValueChange = { bankBranch = it },
                        label = { Text("Branch") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val opBalance = Money.parse(openingBalanceInput)
                            onCreateLedger(
                                ledgerName,
                                selectedGroupId,
                                opBalance,
                                balanceType,
                                gstin,
                                pan,
                                phone,
                                email,
                                address,
                                hsnSac,
                                0.0,
                                if (isBankGroup) bankName else "",
                                if (isBankGroup) bankAccountNumber else "",
                                if (isBankGroup) bankIfsc else "",
                                if (isBankGroup) bankBranch else "",
                                stateCode,
                                pinCode
                            )
                            onDismiss()
                        },
                        enabled = ledgerName.isNotBlank() && selectedGroupId.isNotBlank(),
                        modifier = Modifier.testTag("submit_ledger_button")
                    ) {
                        Text("Save Ledger")
                    }
                }
            }
        }
    }
}
