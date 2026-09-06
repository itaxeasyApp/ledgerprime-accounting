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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
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
    /** 13-point correctness pass, item 8 (Editable Ledgers) - non-null switches this dialog into
     * edit mode: every field pre-fills from the existing ledger and the submit action calls
     * [onUpdateLedger] instead of [onCreateLedger]. `ledgerId`/current balance/system-ledger status
     * are never editable here regardless - [onUpdateLedger]'s repository-side handler is the one
     * enforcing that (opening balance is also frozen there once entries exist), this dialog just
     * doesn't collect a `ledgerId` input at all. */
    existingLedger: com.example.accounting.domain.accounting.Ledger? = null,
    /** 13-point correctness pass, item 1 (PIN Code API Integration) - same shared lookup state as
     * [CreatePartyDialog]; only ever pre-fills State Code/Address when still blank. */
    isLookingUp: Boolean = false,
    lookupResult: com.example.accounting.domain.profile.PinCodeLookupResult? = null,
    onLookupPinCode: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onCreateLedger: (String, String, Money, DrCr, String, String, String, String, String, String, Double, String, String, String, String, String, String) -> Unit,
    onUpdateLedger: (ledgerId: String, String, String, Money, DrCr, String, String, String, String, String, String, Double, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    var ledgerName by remember(existingLedger) { mutableStateOf(existingLedger?.name ?: "") }
    var selectedGroupId by remember(existingLedger) { mutableStateOf(existingLedger?.groupId ?: initialGroupId ?: groups.firstOrNull()?.groupId ?: "") }
    var openingBalanceInput by remember(existingLedger) { mutableStateOf(existingLedger?.openingBalance?.formatPlain() ?: "0") }
    var balanceType by remember(existingLedger) { mutableStateOf(existingLedger?.openingBalanceType ?: DrCr.DEBIT) }
    var gstin by remember(existingLedger) { mutableStateOf(existingLedger?.gstin ?: "") }
    var pan by remember(existingLedger) { mutableStateOf(existingLedger?.pan ?: "") }
    var phone by remember(existingLedger) { mutableStateOf(existingLedger?.phone ?: "") }
    var email by remember(existingLedger) { mutableStateOf(existingLedger?.email ?: "") }
    var address by remember(existingLedger) { mutableStateOf(existingLedger?.address ?: "") }
    var hsnSac by remember(existingLedger) { mutableStateOf(existingLedger?.hsnSacCode ?: "") }
    // Ledger Setup fix - optional, never required; State Code is the Place-of-Supply fact this
    // ledger needs before it can be used as a Sale/Purchase counterparty (Rule 29). Never defaulted
    // here to the company's own state (see AccountingViewModel.createLedger's own doc comment).
    var stateCode by remember(existingLedger) { mutableStateOf(existingLedger?.stateCode ?: "") }
    var pinCode by remember(existingLedger) { mutableStateOf(existingLedger?.pinCode ?: "") }
    // Audit fix (Company/Profile/Ledger Setup) - the Ledger domain model already carried
    // bankAccountNumber/bankIfsc with no UI ever collecting them; bankName/bankBranch are new
    // sibling fields (MIGRATION_19_20). Only shown/collected for a ledger actually under the
    // Bank group, mirroring the same groupId-prefix check every other Cash/Bank filter uses.
    var bankName by remember(existingLedger) { mutableStateOf(existingLedger?.bankName ?: "") }
    var bankAccountNumber by remember(existingLedger) { mutableStateOf(existingLedger?.bankAccountNumber ?: "") }
    var bankIfsc by remember(existingLedger) { mutableStateOf(existingLedger?.bankIfsc ?: "") }
    var bankBranch by remember(existingLedger) { mutableStateOf(existingLedger?.bankBranch ?: "") }

    LaunchedEffect(pinCode) {
        if (pinCode.length == 6 && pinCode.all { it.isDigit() }) onLookupPinCode(pinCode)
    }
    LaunchedEffect(lookupResult) {
        val result = lookupResult
        if (result != null && result.success && result.pinCode == pinCode) {
            if (stateCode.isBlank()) Constants.stateCodeForName(result.state)?.let { stateCode = it }
            if (address.isBlank() && result.city.isNotBlank()) address = result.city
        }
    }

    var groupDropdownExpanded by remember { mutableStateOf(false) }
    val groupsMap = remember(groups) { groups.associateBy { it.groupId } }
    // Architecture correction (real Group hierarchy) - a direct prefix check (fast path, every
    // ledger filed straight under the System Bank Accounts group) OR an ancestor walk (a ledger
    // filed under a company-created User Group nested under it), never only one.
    val isBankGroup = remember(selectedGroupId, groupsMap) {
        StandardSystemGroups.isExactSystemGroup(selectedGroupId, StandardSystemGroups.BANK_GROUP_ID) ||
            StandardSystemGroups.isUnder(selectedGroupId, StandardSystemGroups.BANK_GROUP_ID, groupsMap)
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false is required for navigationBarsPadding() below to have any
        // effect at all - a Compose Dialog's window is a separate Android window from the host
        // Activity and does not propagate WindowInsets into its content by default (confirmed live:
        // navigationBarsPadding() alone was a silent no-op, verified via a UI-tree bounds dump
        // showing the footer's tap targets completely unchanged before/after adding it).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Debug finding (live-device audit) - this dialog's form is long enough (14 field rows once
        // Bank Details shows) that, without an explicit bound here, the Dialog window's own default
        // wrap-content sizing left the Column's height constraint effectively unconstrained, so the
        // `weight(1f, fill = false)` scrollable body never actually had to share space with the
        // footer - it just grew to fit everything, pushing Save/Cancel off the bottom of the
        // visible screen (confirmed via a UI-tree bounds dump: identical footer bounds before and
        // after the decorFitsSystemWindows/navigationBarsPadding fix, which DID work unmodified for
        // the shorter CreatePartyDialog/CreateVoucherDialog forms). Bounding the Surface's own
        // height gives the Column a real, finite budget, so `weight` can do its job: the footer
        // always gets its full size, the middle section scrolls for whatever's left over.
        val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = maxDialogHeight)
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Fixed header - never scrolls with the form body, so the dialog's identity/close
                // affordance stays reachable regardless of how long the form below gets.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (existingLedger != null) "Edit Ledger" else "New Ledger",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (existingLedger != null) "Update this ledger's details" else "Add a customer, supplier, or expense account",
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
                        onValueChange = { gstin = Constants.normalizeTaxId(it) },
                        label = { Text("GSTIN (Optional)") },
                        placeholder = { Text("27AAAAA0000A1Z5") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pan,
                        onValueChange = { pan = Constants.normalizeTaxId(it) },
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
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pinCode,
                            onValueChange = { pinCode = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("PIN Code (Optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = lookupResult?.takeIf { it.pinCode == pinCode }?.success == false,
                            supportingText = {
                                Text(
                                    lookupResult?.takeIf { it.pinCode == pinCode && !it.success }?.errorMessage
                                        ?: "Auto-fills State/Address"
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
            }

                HorizontalDivider()
                // Fixed footer, outside the scrollable body - `navigationBarsPadding()` is the
                // actual bug fix (live-device audit finding): on an edge-to-edge window (mandatory
                // since Android 15 for this app's targetSdk 36), a bottom action row with no inset
                // padding renders behind the system navigation bar and becomes untappable. Pinning
                // this row outside the scroll area also means Save/Cancel are always visible,
                // never requiring the user to scroll a long form to find them.
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
                            val opBalance = Money.parse(openingBalanceInput)
                            val bName = if (isBankGroup) bankName else ""
                            val bAcctNo = if (isBankGroup) bankAccountNumber else ""
                            val bIfsc = if (isBankGroup) bankIfsc else ""
                            val bBranch = if (isBankGroup) bankBranch else ""
                            val editing = existingLedger
                            if (editing != null) {
                                onUpdateLedger(
                                    editing.ledgerId, ledgerName, selectedGroupId, opBalance, balanceType,
                                    gstin, pan, phone, email, address, hsnSac, 0.0,
                                    bName, bAcctNo, bIfsc, bBranch, stateCode, pinCode
                                )
                            } else {
                                onCreateLedger(
                                    ledgerName, selectedGroupId, opBalance, balanceType,
                                    gstin, pan, phone, email, address, hsnSac, 0.0,
                                    bName, bAcctNo, bIfsc, bBranch, stateCode, pinCode
                                )
                            }
                            onDismiss()
                        },
                        enabled = ledgerName.isNotBlank() && selectedGroupId.isNotBlank(),
                        modifier = Modifier.testTag("submit_ledger_button")
                    ) {
                        Text(if (existingLedger != null) "Save Changes" else "Save Ledger")
                    }
                }
            }
        }
    }
}
