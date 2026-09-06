package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun CreateCompanyDialog(
    onDismiss: () -> Unit,
    /** 13-point correctness pass, item 1 (PIN Code API Integration) - same shared lookup state as
     * [CreatePartyDialog]/[CreateLedgerDialog]; only ever pre-fills State Code/Address when still
     * blank. */
    isLookingUp: Boolean = false,
    lookupResult: com.example.accounting.domain.profile.PinCodeLookupResult? = null,
    onLookupPinCode: (String) -> Unit = {},
    /** Edit-Company fix - same create-vs-edit dialog pattern as [CreateLedgerDialog]'s
     * [CreateLedgerDialog]/`existingLedger`: non-null here switches the dialog into editing this
     * exact company (fields pre-filled, [onUpdateCompany] called instead of [onCreateCompany]) -
     * previously there was no UI anywhere to change a company's own name/GSTIN/PAN/address/phone/
     * email after creation, despite the repository already supporting it. */
    existingCompany: com.example.accounting.domain.company.Company? = null,
    onCreateCompany: (String, String, String, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onUpdateCompany: (companyId: String, String, String, String, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> }
) {
    var name by remember(existingCompany) { mutableStateOf(existingCompany?.name ?: "") }
    var tradeName by remember(existingCompany) { mutableStateOf(existingCompany?.tradeName ?: "") }
    var gstin by remember(existingCompany) { mutableStateOf(existingCompany?.gstin ?: "") }
    var pan by remember(existingCompany) { mutableStateOf(existingCompany?.pan ?: "") }
    var stateCode by remember(existingCompany) { mutableStateOf(existingCompany?.stateCode ?: "27") }
    var address by remember(existingCompany) { mutableStateOf(existingCompany?.address ?: "") }
    var email by remember(existingCompany) { mutableStateOf(existingCompany?.email ?: "") }
    var phone by remember(existingCompany) { mutableStateOf(existingCompany?.phone ?: "") }
    var pinCode by remember(existingCompany) { mutableStateOf(existingCompany?.pinCode ?: "") }

    LaunchedEffect(pinCode) {
        if (pinCode.length == 6 && pinCode.all { it.isDigit() }) onLookupPinCode(pinCode)
    }
    LaunchedEffect(lookupResult) {
        val result = lookupResult
        if (result != null && result.success && result.pinCode == pinCode) {
            com.example.accounting.core.common.Constants.stateCodeForName(result.state)?.let { stateCode = it }
            if (address.isBlank() && result.city.isNotBlank()) address = result.city
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false - required for navigationBarsPadding() below to have any
        // effect inside a Dialog's separate window (see CreateLedgerDialog's fuller note).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (existingCompany != null) "Edit Company" else "Add Company",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (existingCompany != null) "Update this company's details" else "Set up a new business to track",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Legal Entity Name *") },
                    supportingText = { Text("The registered legal name of the business") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("company_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = tradeName,
                    onValueChange = { tradeName = it },
                    label = { Text("Trade Name (Optional)") },
                    supportingText = { Text("The name customers know the business by") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = gstin,
                        onValueChange = {
                            gstin = com.example.accounting.core.common.Constants.normalizeTaxId(it)
                            if (gstin.length >= 2) {
                                stateCode = gstin.take(2)
                            }
                        },
                        label = { Text("GSTIN") },
                        placeholder = { Text("27ABCDE1234F1Z5") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pan,
                        onValueChange = { pan = com.example.accounting.core.common.Constants.normalizeTaxId(it) },
                        label = { Text("PAN") },
                        placeholder = { Text("ABCDE1234F") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = stateCode,
                        onValueChange = { stateCode = it },
                        label = { Text("State Code") },
                        placeholder = { Text("27") },
                        modifier = Modifier.width(100.dp)
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Registered Business Address") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = { pinCode = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("PIN Code (Optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = lookupResult?.takeIf { it.pinCode == pinCode }?.success == false,
                        supportingText = {
                            Text(
                                lookupResult?.takeIf { it.pinCode == pinCode && !it.success }?.errorMessage
                                    ?: "Auto-fills State Code/Address"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (isLookingUp) {
                        Spacer(modifier = Modifier.width(6.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
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
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (existingCompany != null) {
                                onUpdateCompany(existingCompany.companyId, name, tradeName, gstin, pan, stateCode, address, email, phone, pinCode)
                            } else {
                                onCreateCompany(name, tradeName, gstin, pan, stateCode, address, email, phone, pinCode)
                            }
                            onDismiss()
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.testTag("submit_company_button")
                    ) {
                        Text(if (existingCompany != null) "Update Company" else "Create Company")
                    }
                }
            }
        }
    }
}
