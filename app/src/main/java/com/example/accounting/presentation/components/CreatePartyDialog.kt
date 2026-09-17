package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.ContactFieldValidation
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.presentation.theme.Spacing

/**
 * Phase 7J UI, extended by Rule 30 (Party/Customer/Supplier Data Validation): adds a Customer or
 * Supplier - a thin form over
 * [com.example.accounting.presentation.viewmodel.AccountingViewModel.createParty], which itself
 * delegates to the frozen `PartyManagementService.createParty` (Phase 7J-B). [role] is fixed by
 * which screen opened this dialog (Sales -> Customer, Purchases -> Supplier) - never chosen here,
 * so a Customer can never be accidentally created from the Purchases tab.
 *
 * Responsive to entity type/GST registration status per Rule 30 Section 6 - the GST Registration
 * choice (and the fields it reveals) only appears for a Business party; GSTIN is never shown as
 * "required" for an Individual or an Unregistered/Unknown Business. Validation shown here is
 * immediate UI feedback only ([GSTRules.isValidGSTIN] reused, never a second regex) - the
 * authoritative check still lives in [com.example.accounting.domain.party.PartyValidation],
 * enforced server-side-of-the-UI in `AccountingRepository.createParty` (Rule 30 Section 7).
 */
@Composable
fun CreatePartyDialog(
    role: PartyRole,
    onDismiss: () -> Unit,
    /** 13-point correctness pass, item 1 (PIN Code API Integration) - reuses the same
     * [com.example.accounting.presentation.viewmodel.AccountingViewModel.lookupPinCode]/
     * `pinCodeLookupResult`/`isPinCodeLookupInProgress` state Profile already drives
     * [com.example.accounting.presentation.components.AddressPinCodeFields] with; this dialog only
     * ever *pre-fills* State Code/Address when they're still blank, never overwrites what the user
     * already typed. Defaults make every existing caller/preview keep compiling unchanged. */
    isLookingUp: Boolean = false,
    lookupResult: com.example.accounting.domain.profile.PinCodeLookupResult? = null,
    onLookupPinCode: (String) -> Unit = {},
    onCreateParty: (
        displayName: String,
        role: PartyRole,
        entityType: PartyEntityType,
        gstin: String,
        phone: String,
        email: String,
        address: String,
        stateCode: String,
        gstRegistrationStatus: GstRegistrationStatus?,
        pinCode: String,
        openingBalance: Money,
        openingBalanceType: DrCr
    ) -> Unit,
    /** Contacts + Favorites correction (docs/CORRECTIONS_LOG.md) - opens the real device Contacts
     * picker (permission handled by the caller, which shows its own rationale first). */
    onRequestContactImport: () -> Unit = {},
    /** Non-null once the caller's contact picker resolves - (name, phone). Only fills fields that
     * are still blank, same "never overwrite what the user already typed" rule the PIN-code lookup
     * above already follows. */
    importedContact: Pair<String, String>? = null,
    onContactImportConsumed: () -> Unit = {}
) {
    var displayName by remember { mutableStateOf("") }
    var entityType by remember { mutableStateOf(PartyEntityType.BUSINESS) }
    var gstin by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var stateCode by remember { mutableStateOf("") }
    // Customer/Supplier Setup fix - optional, never required (matches every other address field).
    var pinCode by remember { mutableStateOf("") }
    // Architecture correction - a Customer/Supplier previously had no way to record a pre-existing
    // balance at all (structurally impossible via this dialog); mirrors CreateLedgerDialog's own
    // Opening Balance field exactly. Defaults to 0/Debit, same as a brand-new ledger.
    var openingBalanceInput by remember { mutableStateOf("0") }
    var openingBalanceType by remember { mutableStateOf(DrCr.DEBIT) }

    LaunchedEffect(pinCode) {
        if (pinCode.length == 6 && pinCode.all { it.isDigit() }) onLookupPinCode(pinCode)
    }
    // Contacts + Favorites correction - fills only fields still blank, never overwrites what the
    // user already typed (same rule the PIN-code lookup below follows).
    LaunchedEffect(importedContact) {
        val contact = importedContact ?: return@LaunchedEffect
        if (displayName.isBlank()) displayName = contact.first
        if (phone.isBlank()) phone = contact.second
        onContactImportConsumed()
    }
    // 13-point correctness pass, item 1 - pre-fills only, never overwrites what the user already
    // typed; a stale result for a since-edited PIN (`lookupResult.pinCode != pinCode`) is ignored.
    LaunchedEffect(lookupResult) {
        val result = lookupResult
        if (result != null && result.success && result.pinCode == pinCode) {
            if (stateCode.isBlank()) Constants.stateCodeForName(result.state)?.let { stateCode = it }
            if (address.isBlank() && result.city.isNotBlank()) address = result.city
        }
    }

    val roleLabel = if (role == PartyRole.CUSTOMER) "Customer" else "Supplier"
    val isBusiness = entityType == PartyEntityType.BUSINESS
    // User correction: "if person having gstin no then only he is registered if not then
    // unregistered there is nothing like unknown on sale invoice" - GST Registration is no longer
    // a free-standing manual choice with a third "Unknown" state; it's derived purely from whether
    // a GSTIN was entered. An Individual never carries a registration status at all (Rule 30
    // Section 4 - unchanged), matching the domain model's own null-means-not-applicable meaning.
    val gstRegistrationStatus = if (isBusiness) {
        if (gstin.isNotBlank()) GstRegistrationStatus.REGISTERED else GstRegistrationStatus.UNREGISTERED
    } else null
    val gstinFormatInvalid = !GSTRules.isValidGSTIN(gstin)
    // Play Store readiness correction - phone/email previously had zero format validation (unlike
    // GSTIN above). Both stay optional (blank is valid, matches every other optional field here);
    // this only rejects a non-blank value that isn't shaped like a real phone/email.
    val phoneFormatInvalid = !ContactFieldValidation.isValidIndianMobile(phone)
    val emailFormatInvalid = !ContactFieldValidation.isValidEmail(email)
    val canSubmit = displayName.isNotBlank() && !gstinFormatInvalid && !phoneFormatInvalid && !emailFormatInvalid

    // Mobile Workflow Correction - Add/Edit Supplier and Add/Edit Customer are major workflows, not
    // small popups, so this reads as a real mobile screen: full-bleed Dialog(usePlatformDefaultWidth
    // = false) + fillMaxSize() Surface, same recipe as [QuickInvoiceEntryScreen] ("New Sale
    // Invoice", the reference pattern) and [CreateVoucherDialog].
    // decorFitsSystemWindows = false - required for navigationBarsPadding() below to have any
    // effect inside a Dialog's separate window (see CreateLedgerDialog's fuller note).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            // Real-device QA finding (see QuickInvoiceEntryScreen's fuller note) - with
            // decorFitsSystemWindows = false, a raw Dialog window's fillMaxSize() measures against
            // the full edge-to-edge window, not the visible area between the status and navigation
            // bars; systemBarsPadding() alone still measured ZERO bottom inset on this OEM's
            // 3-button nav (MIUI/HyperOS), which pushed the footer almost entirely off the physical
            // screen. An explicit 48dp floor - Android's own standard 3-button nav bar height - is
            // added on top as a deterministic fallback; systemBarsPadding() is kept too for
            // gesture-nav devices where it may correctly report a non-zero inset, stacking
            // harmlessly as a bit of extra margin.
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 48.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add $roleLabel", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    }
                    IconButton(onClick = onRequestContactImport) {
                        Icon(Icons.Default.ContactPhone, contentDescription = "Import from Contacts")
                    }
                }
                HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg - Spacing.xs)
            ) {
                FormField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "$roleLabel name",
                    supportingText = "Required",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = entityType == PartyEntityType.BUSINESS,
                        onClick = { entityType = PartyEntityType.BUSINESS },
                        label = { Text("Business") }
                    )
                    FilterChip(
                        selected = entityType == PartyEntityType.INDIVIDUAL,
                        onClick = { entityType = PartyEntityType.INDIVIDUAL },
                        label = { Text("Individual") }
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.sm))

                if (isBusiness) {
                    Text("GST Registration", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    // Derived, never a separate manual choice - see gstRegistrationStatus above.
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (gstRegistrationStatus == GstRegistrationStatus.REGISTERED) "Registered - GSTIN entered below" else "Unregistered - no GSTIN entered",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    FormField(
                        value = gstin,
                        onValueChange = {
                            gstin = Constants.normalizeTaxId(it)
                            if (gstin.length >= 2) stateCode = gstin.take(2)
                        },
                        label = "GSTIN",
                        supportingText = if (gstinFormatInvalid) "Not a valid GSTIN" else "Leave blank if this $roleLabel isn't GST-registered",
                        isError = gstinFormatInvalid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                } else {
                    // Individual - GSTIN optional, never required (Rule 30 Section 4).
                    FormField(
                        value = gstin,
                        onValueChange = { gstin = Constants.normalizeTaxId(it) },
                        label = "GSTIN (optional)",
                        supportingText = if (gstinFormatInvalid) "Not a valid GSTIN" else null,
                        isError = gstinFormatInvalid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs)) {
                    FormField(
                        value = phone, onValueChange = { phone = it }, label = "Phone",
                        keyboardType = KeyboardType.Phone, isError = phoneFormatInvalid,
                        supportingText = if (phoneFormatInvalid) "Not a valid 10-digit mobile number" else null,
                        modifier = Modifier.weight(1f)
                    )
                    FormField(
                        value = email, onValueChange = { email = it }, label = "Email",
                        keyboardType = KeyboardType.Email, isError = emailFormatInvalid,
                        supportingText = if (emailFormatInvalid) "Not a valid email address" else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = openingBalanceInput,
                        onValueChange = { openingBalanceInput = it },
                        label = { Text("Opening Balance (₹, Optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Product correction ("THIS APPLICATION IS NOT AN ERP", docs/CORRECTIONS_LOG.md,
                        // Phase 1 sweep) - this is the everyday Add Customer/Supplier dialog, not an
                        // advanced accounting area, so it must not read "Debit"/"Credit". A Debit
                        // opening balance always means the party owes the business (a receivable) and
                        // Credit always means the business owes the party (a payable) - true for both
                        // a Customer and a Supplier ledger alike, so one pair of plain labels covers
                        // both roles; DrCr.DEBIT/DrCr.CREDIT themselves are completely unchanged, this
                        // is display-only.
                        FilterChip(
                            selected = openingBalanceType == DrCr.DEBIT,
                            onClick = { openingBalanceType = DrCr.DEBIT },
                            label = { Text("They owe me") }
                        )
                        FilterChip(
                            selected = openingBalanceType == DrCr.CREDIT,
                            onClick = { openingBalanceType = DrCr.CREDIT },
                            label = { Text("I owe them") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.sm))

                FormField(value = address, onValueChange = { address = it }, label = "Address", modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs)) {
                    FormField(
                        value = stateCode,
                        onValueChange = { stateCode = it },
                        label = "State Code (GST)",
                        // Derived, display-only - never a second stored state-name field, same
                        // lookup Company profile already uses (Constants.GST_STATE_CODES).
                        supportingText = Constants.GST_STATE_CODES[stateCode]
                            ?: "Needed for Place of Supply - required before this $roleLabel can be used in a GST transaction",
                        modifier = Modifier.weight(1f)
                    )
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        FormField(
                            value = pinCode,
                            onValueChange = { pinCode = it.filter { c -> c.isDigit() }.take(6) },
                            label = "PIN Code (optional)",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            isError = lookupResult?.takeIf { it.pinCode == pinCode }?.success == false,
                            supportingText = lookupResult?.takeIf { it.pinCode == pinCode && !it.success }?.errorMessage
                                ?: "Auto-fills State/Address",
                            modifier = Modifier.weight(1f)
                        )
                        if (isLookingUp) {
                            Spacer(modifier = Modifier.width(6.dp))
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                // Always-visible State/Country line - the State Code field's own supportingText
                // above already resolves the same name, but as small gray hint text under an
                // otherwise-empty field it's easy to miss entirely on a fresh form. This restates
                // the identical, already-derived value (Constants.GST_STATE_CODES[stateCode] -
                // never a second lookup) in a clearly labeled row every time. Country is a plain
                // fixed label, not a form field - this app is India-only (GSTIN/PAN, Indian GST
                // state codes throughout), and neither Party nor its linked Ledger has a country
                // column in the database (only the Company's own Business/Individual profile does,
                // for an unrelated purpose) - showing a fixed "India" here is honest display text,
                // not a new persisted field or a schema change.
                Text(
                    text = "State: ${Constants.GST_STATE_CODES[stateCode] ?: "-- (enter State Code above)"}  |  Country: India",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.lg - Spacing.xs, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    ActionButton(text = "Cancel", style = ActionButtonStyle.TEXT, onClick = onDismiss)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    ActionButton(
                        text = "Add $roleLabel",
                        enabled = canSubmit,
                        onClick = {
                            onCreateParty(
                                displayName, role, entityType, gstin, phone, email, address, stateCode, gstRegistrationStatus, pinCode,
                                Money.parse(openingBalanceInput), openingBalanceType
                            )
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
