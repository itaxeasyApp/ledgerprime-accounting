package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.core.common.Constants
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
        pinCode: String
    ) -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var entityType by remember { mutableStateOf(PartyEntityType.BUSINESS) }
    // null = UNKNOWN (Rule 30 Section 2) - never defaulted to REGISTERED or UNREGISTERED.
    var gstRegistrationStatus by remember { mutableStateOf<GstRegistrationStatus?>(null) }
    var gstin by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var stateCode by remember { mutableStateOf("") }
    // Customer/Supplier Setup fix - optional, never required (matches every other address field).
    var pinCode by remember { mutableStateOf("") }

    val roleLabel = if (role == PartyRole.CUSTOMER) "Customer" else "Supplier"
    val isBusiness = entityType == PartyEntityType.BUSINESS
    // GSTIN is only ever "required" for a Business explicitly marked Registered (Rule 30 Section 3)
    // - never for Individual (Section 4), never for Unregistered/Unknown.
    val gstinRequired = isBusiness && gstRegistrationStatus == GstRegistrationStatus.REGISTERED
    val gstinFormatInvalid = !GSTRules.isValidGSTIN(gstin)
    val gstinMissing = gstinRequired && gstin.isBlank()
    val canSubmit = displayName.isNotBlank() && !gstinFormatInvalid && !gstinMissing

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg - Spacing.xs)
            ) {
                Text("Add $roleLabel", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(Spacing.md))

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
                        onClick = {
                            entityType = PartyEntityType.INDIVIDUAL
                            // GST Registration is a Business-only concept in this form - clear it
                            // so switching back to Business never carries over a stale choice.
                            gstRegistrationStatus = null
                        },
                        label = { Text("Individual") }
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.sm))

                if (isBusiness) {
                    Text("GST Registration", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        FilterChip(
                            selected = gstRegistrationStatus == GstRegistrationStatus.REGISTERED,
                            onClick = { gstRegistrationStatus = GstRegistrationStatus.REGISTERED },
                            label = { Text("Registered") }
                        )
                        FilterChip(
                            selected = gstRegistrationStatus == GstRegistrationStatus.UNREGISTERED,
                            onClick = {
                                gstRegistrationStatus = GstRegistrationStatus.UNREGISTERED
                                // Never fabricate a GSTIN for a party just marked Unregistered.
                                gstin = ""
                            },
                            label = { Text("Unregistered") }
                        )
                        FilterChip(
                            selected = gstRegistrationStatus == null,
                            onClick = { gstRegistrationStatus = null },
                            label = { Text("Unknown") }
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    when (gstRegistrationStatus) {
                        GstRegistrationStatus.REGISTERED -> {
                            FormField(
                                value = gstin,
                                onValueChange = { gstin = it; if (it.length >= 2) stateCode = it.take(2) },
                                label = "GSTIN",
                                supportingText = when {
                                    gstinMissing -> "Required for a GST-registered business"
                                    gstinFormatInvalid -> "Not a valid GSTIN"
                                    else -> "Required for a GST-registered business"
                                },
                                isError = gstinMissing || gstinFormatInvalid,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                        null -> {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "GST registration status is unresolved for this $roleLabel - it cannot be used in a GST-relevant transaction until this is set.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                        else -> { /* UNREGISTERED - no GSTIN field requirement (Rule 30 Section 6) */ }
                    }
                } else {
                    // Individual - GSTIN optional, never required (Rule 30 Section 4).
                    FormField(
                        value = gstin,
                        onValueChange = { gstin = it },
                        label = "GSTIN (optional)",
                        supportingText = if (gstinFormatInvalid) "Not a valid GSTIN" else null,
                        isError = gstinFormatInvalid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs)) {
                    FormField(value = phone, onValueChange = { phone = it }, label = "Phone", modifier = Modifier.weight(1f))
                    FormField(value = email, onValueChange = { email = it }, label = "Email", modifier = Modifier.weight(1f))
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
                    FormField(
                        value = pinCode,
                        onValueChange = { pinCode = it },
                        label = "PIN Code (optional)",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.lg - Spacing.xs))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton(text = "Cancel", style = ActionButtonStyle.TEXT, onClick = onDismiss)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    ActionButton(
                        text = "Add $roleLabel",
                        enabled = canSubmit,
                        onClick = {
                            onCreateParty(displayName, role, entityType, gstin, phone, email, address, stateCode, gstRegistrationStatus, pinCode)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
