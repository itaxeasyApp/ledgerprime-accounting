package com.example.accounting.presentation.features.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.profile.PinCodeLookupResult
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.ConstitutionType
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.ActionButtonStyle
import com.example.accounting.presentation.components.AddressPinCodeFields
import com.example.accounting.presentation.components.FormField
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.components.SelectField
import com.example.accounting.presentation.components.TableRow
import com.example.accounting.presentation.theme.Spacing

private enum class ProfileWizardStep(val label: String) {
    BUSINESS_INFO("Business"),
    CONTACT("Contact"),
    GST_TAX("GST & Tax"),
    BANK_PAYMENT("Bank & Payment"),
    INVOICE_SETTINGS("Invoice Settings"),
    BRANDING("Branding"),
    REVIEW("Review")
}

/**
 * Profile/Business Setup as a multistep wizard (Part 2 of the UI/UX completion pass) - replaces
 * the single long-scroll form ([ProfileScreen]'s `BusinessProfileSection`) with the same
 * [BusinessProfile] fields, just paced one logical group at a time. Every field maps 1:1 to an
 * existing [BusinessProfile] property - nothing here is a new model. Progress is saved via
 * [onSave] at the end of every step ("save progress" requirement) - each call is a `.copy()` over
 * whatever is already stored, so navigating Back/Next never blanks a field from a step not yet
 * revisited. [ProfileScreen] itself is untouched and still reachable - this is an additive
 * alternate entry point, not a replacement of the underlying data or service.
 */
@Composable
fun ProfileWizardScreen(
    businessProfile: BusinessProfile?,
    logoAssetLabel: String?,
    signatureAssetLabel: String?,
    isPinCodeLookupInProgress: Boolean,
    pinCodeLookupResult: PinCodeLookupResult?,
    onLookupPinCode: (String) -> Unit,
    onSave: (
        businessName: String, legalName: String, constitutionType: ConstitutionType,
        address: String, pinCode: String, city: String, state: String, country: String,
        phone: String, email: String, website: String,
        gstin: String, pan: String, tan: String, udyam: String,
        bankName: String, bankAccountNumber: String, bankIfsc: String, bankBranch: String, upiId: String,
        termsAndConditions: String
    ) -> Unit,
    onPickLogo: () -> Unit,
    onPickSignature: () -> Unit,
    /** Audit fix - previously `() -> Unit`, so Finish only ever navigated back to [ProfileScreen]
     * without ever creating a [com.example.accounting.domain.company.Company] (a brand-new install
     * has none yet). [onSave] only ever writes the [BusinessProfile] record, a display/branding
     * concept - it was never the thing that seeds a Company's standard Groups/default Ledgers, so
     * completing every step of this wizard and tapping Finish silently left the app with zero
     * ledgers and zero parties creatable ("Set up your business first."), no error shown anywhere.
     * Carries the same fields [onSave] already collects so the caller can create-if-missing without
     * racing [onSave]'s own async persistence. */
    onFinish: (
        businessName: String, legalName: String, address: String, pinCode: String,
        city: String, state: String, phone: String, email: String, gstin: String, pan: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(ProfileWizardStep.BUSINESS_INFO) }

    var businessName by remember(businessProfile) { mutableStateOf(businessProfile?.businessName ?: "") }
    var legalName by remember(businessProfile) { mutableStateOf(businessProfile?.legalName ?: "") }
    var constitutionType by remember(businessProfile) { mutableStateOf(businessProfile?.constitutionType ?: ConstitutionType.PROPRIETORSHIP) }
    var address by remember(businessProfile) { mutableStateOf(businessProfile?.address ?: "") }
    var pinCode by remember(businessProfile) { mutableStateOf(businessProfile?.pinCode ?: "") }
    var city by remember(businessProfile) { mutableStateOf(businessProfile?.city ?: "") }
    var state by remember(businessProfile) { mutableStateOf(businessProfile?.state ?: "") }
    var country by remember(businessProfile) { mutableStateOf(businessProfile?.country ?: "") }
    var phone by remember(businessProfile) { mutableStateOf(businessProfile?.phone ?: "") }
    var email by remember(businessProfile) { mutableStateOf(businessProfile?.email ?: "") }
    var website by remember(businessProfile) { mutableStateOf(businessProfile?.website ?: "") }
    var gstin by remember(businessProfile) { mutableStateOf(businessProfile?.gstin ?: "") }
    var pan by remember(businessProfile) { mutableStateOf(businessProfile?.pan ?: "") }
    var tan by remember(businessProfile) { mutableStateOf(businessProfile?.tan ?: "") }
    var udyam by remember(businessProfile) { mutableStateOf(businessProfile?.udyam ?: "") }
    var bankName by remember(businessProfile) { mutableStateOf(businessProfile?.bankName ?: "") }
    var bankAccountNumber by remember(businessProfile) { mutableStateOf(businessProfile?.bankAccountNumber ?: "") }
    var bankIfsc by remember(businessProfile) { mutableStateOf(businessProfile?.bankIfsc ?: "") }
    var bankBranch by remember(businessProfile) { mutableStateOf(businessProfile?.bankBranch ?: "") }
    var upiId by remember(businessProfile) { mutableStateOf(businessProfile?.upiId ?: "") }
    var termsAndConditions by remember(businessProfile) { mutableStateOf(businessProfile?.termsAndConditions ?: "") }

    fun saveProgress() {
        onSave(
            businessName, legalName, constitutionType, address, pinCode, city, state, country, phone, email, website,
            gstin, pan, tan, udyam, bankName, bankAccountNumber, bankIfsc, bankBranch, upiId,
            termsAndConditions
        )
    }

    // Auto-fills City/State/Country once a lookup for the PIN code currently in this step
    // succeeds - never overwrites a value the user already typed by hand for a DIFFERENT pinCode
    // (the `pinCodeLookupResult.pinCode == pinCode` guard), and never fabricates anything on
    // failure (city/state/country simply stay whatever they already were).
    LaunchedEffect(pinCodeLookupResult) {
        val result = pinCodeLookupResult
        if (result != null && result.success && result.pinCode == pinCode) {
            city = result.city
            state = result.state
            country = result.country
        }
    }

    // Real gap fix (docs/CORRECTIONS_LOG.md, user request: "Extract Pan No from GSTIN") - a GSTIN
    // already contains its holder's real PAN (characters 3-12); auto-fills PAN the moment a valid
    // GSTIN is entered, only while PAN is still blank - never overwrites a value the user typed.
    LaunchedEffect(gstin) {
        if (pan.isBlank()) com.example.accounting.core.common.ContactFieldValidation.extractPanFromGstin(gstin)?.let { pan = it }
    }

    // PAN's 4th character is legally fixed by holder type (docs/CORRECTIONS_LOG.md, user request) -
    // this wizard is the one place the real Constitution Type is already known, so the check can be
    // precise: Proprietorship -> 'P' (a proprietorship's PAN is the proprietor's own individual
    // PAN), HUF -> 'H', Private/Public Limited -> 'C'. Partnership/LLP/Trust/Society/Other have no
    // single fixed letter worth enforcing here, so they're left format-only.
    val expectedPanHolderChar = when (constitutionType) {
        ConstitutionType.PROPRIETORSHIP -> 'P'
        ConstitutionType.HUF -> 'H'
        ConstitutionType.PRIVATE_LIMITED, ConstitutionType.PUBLIC_LIMITED -> 'C'
        else -> null
    }
    // 5th-character (surname/entity-name letter) validation was added and then explicitly
    // retracted by the user after live testing - too unreliable across real naming conventions to
    // enforce - so only the 4th-character holder-type check remains.
    val panFormatInvalid = !com.example.accounting.core.common.ContactFieldValidation.isValidPan(pan)
    val panHolderTypeInvalid = !panFormatInvalid && expectedPanHolderChar != null &&
        !com.example.accounting.core.common.ContactFieldValidation.isValidPanForHolderType(pan, expectedPanHolderChar)
    val panInvalid = panFormatInvalid || panHolderTypeInvalid

    val canAdvance = (step != ProfileWizardStep.BUSINESS_INFO || businessName.isNotBlank()) && (step != ProfileWizardStep.GST_TAX || !panInvalid)

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Text(
                "Step ${step.ordinal + 1} of ${ProfileWizardStep.entries.size} - ${step.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { (step.ordinal + 1) / ProfileWizardStep.entries.size.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when (step) {
                ProfileWizardStep.BUSINESS_INFO -> item {
                    SectionCard(title = "Business Information") {
                        FormField(value = businessName, onValueChange = { businessName = it }, label = "Trade name *", modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        FormField(value = legalName, onValueChange = { legalName = it }, label = "Legal name", modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SelectField(
                            label = "Business Type", options = ConstitutionType.entries, selectedOption = constitutionType,
                            optionLabel = { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } },
                            onSelect = { constitutionType = it }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ProfileWizardStep.CONTACT -> item {
                    SectionCard(title = "Contact Information") {
                        AddressPinCodeFields(
                            address = address, onAddressChange = { address = it },
                            pinCode = pinCode, onPinCodeChange = { pinCode = it },
                            city = city, onCityChange = { city = it },
                            state = state, onStateChange = { state = it },
                            country = country, onCountryChange = { country = it },
                            isLookingUp = isPinCodeLookupInProgress,
                            lookupErrorMessage = pinCodeLookupResult?.takeIf { it.pinCode == pinCode && !it.success }?.errorMessage,
                            onLookupPinCode = onLookupPinCode,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FormField(value = phone, onValueChange = { phone = it }, label = "Phone", modifier = Modifier.weight(1f))
                            FormField(value = email, onValueChange = { email = it }, label = "Email", modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        FormField(value = website, onValueChange = { website = it }, label = "Website (optional)", modifier = Modifier.fillMaxWidth())
                    }
                }
                ProfileWizardStep.GST_TAX -> item {
                    SectionCard(title = "GST & Tax Details") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FormField(
                                value = gstin, onValueChange = { gstin = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "GSTIN",
                                isError = gstin.isNotBlank() && !com.example.accounting.domain.taxation.gst.GSTRules.isValidGSTIN(gstin),
                                supportingText = if (gstin.isNotBlank() && !com.example.accounting.domain.taxation.gst.GSTRules.isValidGSTIN(gstin)) "Not a valid GSTIN" else null,
                                modifier = Modifier.weight(1f)
                            )
                            FormField(
                                value = pan, onValueChange = { pan = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "PAN",
                                isError = panInvalid,
                                supportingText = when {
                                    panFormatInvalid -> "Not a valid PAN"
                                    panHolderTypeInvalid -> "A ${constitutionType.name.lowercase().replace('_', ' ')}'s PAN must have '$expectedPanHolderChar' as its 4th character"
                                    else -> null
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FormField(value = tan, onValueChange = { tan = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "TAN (optional)", modifier = Modifier.weight(1f))
                            FormField(value = udyam, onValueChange = { udyam = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "UDYAM (optional)", modifier = Modifier.weight(1f))
                        }
                    }
                }
                ProfileWizardStep.BANK_PAYMENT -> item {
                    SectionCard(title = "Bank / Payment Details") {
                        FormField(value = bankName, onValueChange = { bankName = it }, label = "Bank name", modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FormField(value = bankAccountNumber, onValueChange = { bankAccountNumber = it }, label = "Account number", modifier = Modifier.weight(1f))
                            FormField(value = bankIfsc, onValueChange = { bankIfsc = it.uppercase() }, label = "IFSC", modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        FormField(value = bankBranch, onValueChange = { bankBranch = it }, label = "Branch", modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        FormField(
                            value = upiId, onValueChange = { upiId = it }, label = "UPI ID",
                            supportingText = "Used to generate a payment QR on invoices", modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ProfileWizardStep.INVOICE_SETTINGS -> item {
                    SectionCard(title = "Invoice Settings") {
                        FormField(
                            value = termsAndConditions, onValueChange = { termsAndConditions = it },
                            label = "Terms & Conditions", supportingText = "Printed on every invoice",
                            singleLine = false, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ProfileWizardStep.BRANDING -> item {
                    SectionCard(title = "Branding") {
                        TableRow("Logo", value = logoAssetLabel ?: "Not uploaded")
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        ActionButton(text = "Upload Logo", style = ActionButtonStyle.SECONDARY, onClick = onPickLogo, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        TableRow("Signature", value = signatureAssetLabel ?: "Not uploaded")
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        ActionButton(text = "Upload Signature", style = ActionButtonStyle.SECONDARY, onClick = onPickSignature, modifier = Modifier.fillMaxWidth())
                    }
                }
                ProfileWizardStep.REVIEW -> item {
                    SectionCard(title = "Review", subtitle = "Confirm before finishing") {
                        TableRow("Trade name", value = businessName.ifBlank { "-" })
                        TableRow("Legal name", value = legalName.ifBlank { "-" })
                        TableRow("Business type", value = constitutionType.name)
                        TableRow("PIN Code", value = pinCode.ifBlank { "-" })
                        TableRow("City", value = city.ifBlank { "-" })
                        TableRow("State", value = state.ifBlank { "-" })
                        TableRow("Phone", value = phone.ifBlank { "-" })
                        TableRow("Email", value = email.ifBlank { "-" })
                        TableRow("GSTIN", value = gstin.ifBlank { "-" })
                        TableRow("PAN", value = pan.ifBlank { "-" })
                        TableRow("Bank", value = bankName.ifBlank { "-" })
                        TableRow("UPI ID", value = upiId.ifBlank { "-" })
                        TableRow("Logo", value = logoAssetLabel ?: "Not uploaded")
                        TableRow("Signature", value = signatureAssetLabel ?: "Not uploaded")
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(Spacing.xl)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (step != ProfileWizardStep.BUSINESS_INFO) {
                ActionButton(
                    text = "Back", style = ActionButtonStyle.SECONDARY, modifier = Modifier.weight(1f),
                    onClick = { step = ProfileWizardStep.entries[step.ordinal - 1] }
                )
            }
            ActionButton(
                text = if (step == ProfileWizardStep.REVIEW) "Finish" else "Next",
                enabled = canAdvance,
                modifier = Modifier.weight(1f),
                onClick = {
                    saveProgress()
                    if (step == ProfileWizardStep.REVIEW) {
                        onFinish(businessName, legalName, address, pinCode, city, state, phone, email, gstin, pan)
                    } else {
                        step = ProfileWizardStep.entries[step.ordinal + 1]
                    }
                }
            )
        }
    }
}
