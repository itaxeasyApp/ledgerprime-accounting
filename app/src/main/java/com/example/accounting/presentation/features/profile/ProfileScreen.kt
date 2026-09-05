package com.example.accounting.presentation.features.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.profile.PinCodeLookupResult
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.IndividualProfile
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.AddressPinCodeFields
import com.example.accounting.presentation.components.FormField
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.theme.Spacing

/**
 * Phase 7J UI: "Profile & Business Setup" - reached from the persistent top-bar icon, never a
 * bottom-nav item (per the UX spec's "secondary features are reached through their respective
 * sections" rule). Shows both a Business section and an Individual section unconditionally -
 * nothing in the frozen `ProfileApplicationService`/domain model enforces exclusivity between
 * them, so this screen doesn't invent one either. Also hosts the Import/Subscription/Company &
 * Sync entry points, matching the spec's "secondary features reached through here" framing.
 */
@Composable
fun ProfileScreen(
    businessProfile: BusinessProfile?,
    individualProfile: IndividualProfile?,
    isPinCodeLookupInProgress: Boolean = false,
    pinCodeLookupResult: PinCodeLookupResult? = null,
    onLookupPinCode: (String) -> Unit = {},
    onSaveBusinessProfile: (businessName: String, legalName: String, address: String, pinCode: String, city: String, state: String, country: String, phone: String, email: String, gstin: String, pan: String) -> Unit,
    onSaveIndividualProfile: (name: String, address: String, pinCode: String, city: String, state: String, country: String, phone: String, email: String, pan: String) -> Unit,
    onOpenImportData: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenCompanyAndSync: () -> Unit,
    onOpenBusinessSetupWizard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Profile & Business Setup", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

        SectionCard(
            onClick = onOpenBusinessSetupWizard,
            title = "Business Setup Wizard",
            subtitle = "Guided step-by-step setup - GST, bank/payment, branding, invoice settings"
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }

        BusinessProfileSection(businessProfile, isPinCodeLookupInProgress, pinCodeLookupResult, onLookupPinCode, onSaveBusinessProfile)
        IndividualProfileSection(individualProfile, isPinCodeLookupInProgress, pinCodeLookupResult, onLookupPinCode, onSaveIndividualProfile)

        SectionCard(onClick = onOpenImportData, title = "Import & Scan", subtitle = "CSV/JSON import, scan a receipt") {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
        SectionCard(onClick = onOpenSubscription, title = "Subscription", subtitle = "Plan & entitlements") {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
        SectionCard(onClick = onOpenCompanyAndSync, title = "Company & Sync", subtitle = "Accounting setup, governance, cloud sync") {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

/** Small rounded icon badge matching `AppTopBar`'s company-icon treatment, reused here so each
 * profile section reads as a distinct, deliberately-designed block instead of a plain text label
 * over a form (this is what "not customized professionally" was about - not new business logic). */
@Composable
private fun ProfileSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun BusinessProfileSection(
    profile: BusinessProfile?,
    isPinCodeLookupInProgress: Boolean,
    pinCodeLookupResult: PinCodeLookupResult?,
    onLookupPinCode: (String) -> Unit,
    onSave: (String, String, String, String, String, String, String, String, String, String, String) -> Unit
) {
    var businessName by remember(profile) { mutableStateOf(profile?.businessName ?: "") }
    var legalName by remember(profile) { mutableStateOf(profile?.legalName ?: "") }
    var address by remember(profile) { mutableStateOf(profile?.address ?: "") }
    var pinCode by remember(profile) { mutableStateOf(profile?.pinCode ?: "") }
    var city by remember(profile) { mutableStateOf(profile?.city ?: "") }
    var state by remember(profile) { mutableStateOf(profile?.state ?: "") }
    var country by remember(profile) { mutableStateOf(profile?.country ?: "") }
    var phone by remember(profile) { mutableStateOf(profile?.phone ?: "") }
    var email by remember(profile) { mutableStateOf(profile?.email ?: "") }
    var gstin by remember(profile) { mutableStateOf(profile?.gstin ?: "") }
    var pan by remember(profile) { mutableStateOf(profile?.pan ?: "") }

    androidx.compose.runtime.LaunchedEffect(pinCodeLookupResult) {
        val result = pinCodeLookupResult
        if (result != null && result.success && result.pinCode == pinCode) {
            city = result.city; state = result.state; country = result.country
        }
    }

    SectionCard(elevated = true) {
        ProfileSectionHeader(Icons.Default.Business, "Business Profile")
        Spacer(modifier = Modifier.height(Spacing.md))
        FormField(value = businessName, onValueChange = { businessName = it }, label = "Trade name", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.sm))
        FormField(value = legalName, onValueChange = { legalName = it }, label = "Legal name", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FormField(value = phone, onValueChange = { phone = it }, label = "Phone", keyboardType = KeyboardType.Phone, modifier = Modifier.weight(1f))
            FormField(value = email, onValueChange = { email = it }, label = "Email", keyboardType = KeyboardType.Email, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
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
            FormField(value = gstin, onValueChange = { gstin = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "GSTIN", modifier = Modifier.weight(1f))
            FormField(value = pan, onValueChange = { pan = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "PAN", modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        ActionButton(
            text = "Save Business Profile",
            onClick = { onSave(businessName, legalName, address, pinCode, city, state, country, phone, email, gstin, pan) },
            enabled = businessName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun IndividualProfileSection(
    profile: IndividualProfile?,
    isPinCodeLookupInProgress: Boolean,
    pinCodeLookupResult: PinCodeLookupResult?,
    onLookupPinCode: (String) -> Unit,
    onSave: (String, String, String, String, String, String, String, String, String) -> Unit
) {
    var name by remember(profile) { mutableStateOf(profile?.name ?: "") }
    var address by remember(profile) { mutableStateOf(profile?.address ?: "") }
    var pinCode by remember(profile) { mutableStateOf(profile?.pinCode ?: "") }
    var city by remember(profile) { mutableStateOf(profile?.city ?: "") }
    var state by remember(profile) { mutableStateOf(profile?.state ?: "") }
    var country by remember(profile) { mutableStateOf(profile?.country ?: "") }
    var phone by remember(profile) { mutableStateOf(profile?.phone ?: "") }
    var email by remember(profile) { mutableStateOf(profile?.email ?: "") }
    var pan by remember(profile) { mutableStateOf(profile?.pan ?: "") }

    androidx.compose.runtime.LaunchedEffect(pinCodeLookupResult) {
        val result = pinCodeLookupResult
        if (result != null && result.success && result.pinCode == pinCode) {
            city = result.city; state = result.state; country = result.country
        }
    }

    SectionCard(elevated = true) {
        ProfileSectionHeader(Icons.Default.Person, "Individual Profile")
        Spacer(modifier = Modifier.height(Spacing.md))
        FormField(value = name, onValueChange = { name = it }, label = "Full name", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FormField(value = phone, onValueChange = { phone = it }, label = "Phone", keyboardType = KeyboardType.Phone, modifier = Modifier.weight(1f))
            FormField(value = email, onValueChange = { email = it }, label = "Email", keyboardType = KeyboardType.Email, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
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
        FormField(value = pan, onValueChange = { pan = com.example.accounting.core.common.Constants.normalizeTaxId(it) }, label = "PAN", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.md))
        ActionButton(
            text = "Save Individual Profile",
            onClick = { onSave(name, address, pinCode, city, state, country, phone, email, pan) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
