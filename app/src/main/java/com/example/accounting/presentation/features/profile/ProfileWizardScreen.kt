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
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.profile.PinCodeLookupResult
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.ActionButtonStyle
import com.example.accounting.presentation.components.BusinessContactFields
import com.example.accounting.presentation.components.BusinessDetails
import com.example.accounting.presentation.components.BusinessIdentityFields
import com.example.accounting.presentation.components.BusinessTaxFields
import com.example.accounting.presentation.components.FormField
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.components.TableRow
import com.example.accounting.presentation.components.rememberBusinessDetailsFormState
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
 * Business Setup Wizard - the one place business details are entered: it creates the business
 * (step 1, when none exists yet - the old separate Set Up My Business dialog is gone) and edits
 * it afterwards. Business/Contact/GST steps are the shared [BusinessIdentityFields]/
 * [BusinessContactFields]/[BusinessTaxFields] (same form Settings > My Business uses); the
 * bank/invoice/branding steps stay wizard-only. Progress is saved via [onSave] at the end of
 * every step - each save `.copy()`s over whatever is already stored, so Back/Next never blanks a
 * field from a step not yet revisited.
 */
@Composable
fun ProfileWizardScreen(
    company: Company?,
    businessProfile: BusinessProfile?,
    logoAssetLabel: String?,
    signatureAssetLabel: String?,
    isPinCodeLookupInProgress: Boolean,
    pinCodeLookupResult: PinCodeLookupResult?,
    onLookupPinCode: (String) -> Unit,
    onCreateBusiness: (BusinessDetails) -> Unit,
    /** Saves the shared details plus this wizard's own bank/terms fields (applied onto the profile). */
    onSave: (BusinessDetails, (BusinessProfile) -> BusinessProfile) -> Unit,
    onPickLogo: () -> Unit,
    onPickSignature: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(ProfileWizardStep.BUSINESS_INFO) }

    val details = rememberBusinessDetailsFormState(company, businessProfile)
    var bankName by remember(businessProfile?.businessProfileId) { mutableStateOf(businessProfile?.bankName ?: "") }
    var bankAccountNumber by remember(businessProfile?.businessProfileId) { mutableStateOf(businessProfile?.bankAccountNumber ?: "") }
    var bankIfsc by remember(businessProfile?.businessProfileId) { mutableStateOf(businessProfile?.bankIfsc ?: "") }
    var bankBranch by remember(businessProfile?.businessProfileId) { mutableStateOf(businessProfile?.bankBranch ?: "") }
    var upiId by remember(businessProfile?.businessProfileId) { mutableStateOf(businessProfile?.upiId ?: "") }
    var termsAndConditions by remember(businessProfile?.businessProfileId) { mutableStateOf(businessProfile?.termsAndConditions ?: "") }

    fun saveProgress() {
        onSave(details.toDetails()) {
            it.copy(
                bankName = bankName, bankAccountNumber = bankAccountNumber, bankIfsc = bankIfsc, bankBranch = bankBranch, upiId = upiId,
                termsAndConditions = termsAndConditions
            )
        }
    }

    // Creating the business is async - Next stays disabled until it exists (a second tap would
    // otherwise create a second business), then the wizard moves on to Contact by itself.
    var isCreating by remember { mutableStateOf(false) }
    LaunchedEffect(company?.companyId) {
        if (isCreating && company != null) {
            isCreating = false
            step = ProfileWizardStep.CONTACT
        }
    }

    val canAdvance = !isCreating && when (step) {
        ProfileWizardStep.BUSINESS_INFO -> details.identityValid
        ProfileWizardStep.CONTACT -> details.contactValid
        ProfileWizardStep.GST_TAX -> details.taxValid
        else -> true
    }

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
                    SectionCard(title = "Business Information") { BusinessIdentityFields(details, modifier = Modifier.fillMaxWidth()) }
                }
                ProfileWizardStep.CONTACT -> item {
                    SectionCard(title = "Contact Information") {
                        BusinessContactFields(details, isPinCodeLookupInProgress, pinCodeLookupResult, onLookupPinCode, modifier = Modifier.fillMaxWidth())
                    }
                }
                ProfileWizardStep.GST_TAX -> item {
                    SectionCard(title = "GST & Tax Details") { BusinessTaxFields(details, modifier = Modifier.fillMaxWidth()) }
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
                        TableRow("Trade name", value = details.tradeName.ifBlank { "-" })
                        TableRow("Legal name", value = details.legalName.ifBlank { "-" })
                        TableRow("Business type", value = details.constitutionType.name)
                        TableRow("PIN Code", value = details.pinCode.ifBlank { "-" })
                        TableRow("City", value = details.city.ifBlank { "-" })
                        TableRow("State", value = details.state.ifBlank { "-" })
                        TableRow("Phone", value = details.phone.ifBlank { "-" })
                        TableRow("Email", value = details.email.ifBlank { "-" })
                        TableRow("GSTIN", value = details.gstin.ifBlank { "-" })
                        TableRow("PAN", value = details.pan.ifBlank { "-" })
                        TableRow("GST State Code", value = details.stateCode.ifBlank { "-" })
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
                    if (company == null) {
                        // No business yet (only possible on step 1): create it, then move on once it exists.
                        isCreating = true
                        onCreateBusiness(details.toDetails())
                    } else {
                        saveProgress()
                        if (step == ProfileWizardStep.REVIEW) onFinish() else step = ProfileWizardStep.entries[step.ordinal + 1]
                    }
                }
            )
        }
    }
}
