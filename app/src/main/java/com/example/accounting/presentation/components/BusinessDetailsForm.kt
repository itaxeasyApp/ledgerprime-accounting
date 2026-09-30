package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.ContactFieldValidation
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.profile.PinCodeLookupResult
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.ConstitutionType
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.presentation.theme.Spacing

/**
 * The one business-details form (user request: "make at one place form and make its reusable
 * component and call it everywhere"). Replaces four separately-maintained copies - the old
 * Set Up My Business dialog, Profile & Business Setup's Business Profile section, the Setup
 * Wizard's Business/Contact/GST steps, and Settings > My Business's Edit Business Details/GST
 * Details/Contact Details sub-forms - which had already drifted apart (different validation,
 * and a GSTIN entered in the wizard only ever reached Business Profile, never the statutory
 * [Company] record). Every caller now shows these same sections and saves the same
 * [BusinessDetails] through `AccountingViewModel.saveBusinessDetails`/`createBusiness`, which
 * write [Company] and [BusinessProfile] together.
 */
data class BusinessDetails(
    val tradeName: String,
    val legalName: String,
    val constitutionType: ConstitutionType,
    val phone: String,
    val email: String,
    val website: String,
    val address: String,
    val pinCode: String,
    val city: String,
    val state: String,
    val country: String,
    val gstin: String,
    val pan: String,
    val stateCode: String,
    val tan: String,
    val udyam: String
)

/**
 * Seeded per docs/57_BUSINESS_IDENTITY_DISPLAY.md: GSTIN/PAN/state code from [Company] (the
 * statutory record) first, everything else from [BusinessProfile] (branding) first, each falling
 * back to the other for a business that only has one of the two yet.
 */
@Stable
class BusinessDetailsFormState(company: Company?, profile: BusinessProfile?) {
    var tradeName by mutableStateOf(profile?.businessName?.ifBlank { null } ?: company?.tradeName?.ifBlank { null } ?: company?.name.orEmpty())
    var legalName by mutableStateOf(profile?.legalName?.ifBlank { null } ?: company?.legalName.orEmpty())
    var constitutionType by mutableStateOf(profile?.constitutionType ?: ConstitutionType.PROPRIETORSHIP)
    var phone by mutableStateOf(profile?.phone?.ifBlank { null } ?: company?.phone.orEmpty())
    var email by mutableStateOf(profile?.email?.ifBlank { null } ?: company?.email.orEmpty())
    var website by mutableStateOf(profile?.website.orEmpty())
    var address by mutableStateOf(profile?.address?.ifBlank { null } ?: company?.address.orEmpty())
    var pinCode by mutableStateOf(profile?.pinCode?.ifBlank { null } ?: company?.pinCode.orEmpty())
    var city by mutableStateOf(profile?.city.orEmpty())
    var state by mutableStateOf(profile?.state.orEmpty())
    var country by mutableStateOf(profile?.country.orEmpty())
    var gstin by mutableStateOf(company?.gstin?.ifBlank { null } ?: profile?.gstin.orEmpty())
    var pan by mutableStateOf(company?.pan?.ifBlank { null } ?: profile?.pan.orEmpty())
    // "27" when no business exists yet - Company.stateCode's own default and the same starting value
    // the old Set Up My Business dialog used; the GSTIN / PIN-code auto-fills below replace it.
    var stateCode by mutableStateOf(company?.stateCode ?: "27")
    var tan by mutableStateOf(profile?.tan.orEmpty())
    var udyam by mutableStateOf(profile?.udyam.orEmpty())

    val phoneInvalid: Boolean get() = !ContactFieldValidation.isValidIndianMobile(phone)
    val emailInvalid: Boolean get() = !ContactFieldValidation.isValidEmail(email)
    val gstinInvalid: Boolean get() = gstin.isNotBlank() && !GSTRules.isValidGSTIN(gstin)

    // PAN's 4th character is legally fixed by holder type (docs/CORRECTIONS_LOG.md) - checked only
    // where it has a single fixed letter: Proprietorship -> 'P' (the proprietor's own PAN), HUF ->
    // 'H', Private/Public Limited -> 'C'. The 5th-character check was retracted by the user.
    val expectedPanHolderChar: Char?
        get() = when (constitutionType) {
            ConstitutionType.PROPRIETORSHIP -> 'P'
            ConstitutionType.HUF -> 'H'
            ConstitutionType.PRIVATE_LIMITED, ConstitutionType.PUBLIC_LIMITED -> 'C'
            else -> null
        }
    val panFormatInvalid: Boolean get() = !ContactFieldValidation.isValidPan(pan)
    val panHolderTypeInvalid: Boolean
        get() = !panFormatInvalid && expectedPanHolderChar?.let { !ContactFieldValidation.isValidPanForHolderType(pan, it) } ?: false

    val identityValid: Boolean get() = tradeName.isNotBlank()
    val contactValid: Boolean get() = !phoneInvalid && !emailInvalid
    val taxValid: Boolean get() = !gstinInvalid && !panFormatInvalid && !panHolderTypeInvalid
    val isValid: Boolean get() = identityValid && contactValid && taxValid

    fun toDetails() = BusinessDetails(
        tradeName = tradeName.trim(), legalName = legalName.trim(), constitutionType = constitutionType,
        phone = phone.trim(), email = email.trim(), website = website.trim(),
        address = address.trim(), pinCode = pinCode, city = city.trim(), state = state.trim(), country = country.trim(),
        gstin = Constants.normalizeTaxId(gstin), pan = Constants.normalizeTaxId(pan), stateCode = stateCode.trim(),
        tan = Constants.normalizeTaxId(tan), udyam = Constants.normalizeTaxId(udyam)
    )
}

/** Re-seeds only when a different business/profile row appears (e.g. the profile finishing its
 * async load, or the first save creating it) - never on every save of the same row, which would
 * otherwise reset fields mid-edit. */
@Composable
fun rememberBusinessDetailsFormState(company: Company?, profile: BusinessProfile?): BusinessDetailsFormState =
    remember(company?.companyId, profile?.businessProfileId) { BusinessDetailsFormState(company, profile) }

/** Trade name, legal name, business (constitution) type. */
@Composable
fun BusinessIdentityFields(state: BusinessDetailsFormState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        FormField(value = state.tradeName, onValueChange = { state.tradeName = it }, label = "Trade name *", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.sm))
        FormField(
            value = state.legalName, onValueChange = { state.legalName = it }, label = "Legal name",
            supportingText = "Registered name, if different from the trade name", modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        SelectField(
            label = "Business Type", options = ConstitutionType.entries, selectedOption = state.constitutionType,
            optionLabel = { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } },
            onSelect = { state.constitutionType = it }, modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Phone, email, website, address + PIN code (auto-fills City/State/Country and the GST state code). */
@Composable
fun BusinessContactFields(
    state: BusinessDetailsFormState,
    isPinCodeLookupInProgress: Boolean,
    pinCodeLookupResult: PinCodeLookupResult?,
    onLookupPinCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(pinCodeLookupResult) {
        val result = pinCodeLookupResult
        if (result != null && result.success && result.pinCode == state.pinCode) {
            state.city = result.city; state.state = result.state; state.country = result.country
            // A valid GSTIN already fixes the state code (its first two digits) - only fill from
            // the PIN's state when there is no GSTIN to go by.
            if (state.gstin.isBlank()) Constants.stateCodeForName(result.state)?.let { state.stateCode = it }
        }
    }
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FormField(
                value = state.phone, onValueChange = { state.phone = it }, label = "Phone", keyboardType = KeyboardType.Phone,
                isError = state.phoneInvalid, supportingText = if (state.phoneInvalid) "Not a valid 10-digit mobile number" else null,
                modifier = Modifier.weight(1f)
            )
            FormField(
                value = state.email, onValueChange = { state.email = it }, label = "Email", keyboardType = KeyboardType.Email,
                isError = state.emailInvalid, supportingText = if (state.emailInvalid) "Not a valid email address" else null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        FormField(value = state.website, onValueChange = { state.website = it }, label = "Website (optional)", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.sm))
        AddressPinCodeFields(
            address = state.address, onAddressChange = { state.address = it },
            pinCode = state.pinCode, onPinCodeChange = { state.pinCode = it },
            city = state.city, onCityChange = { state.city = it },
            state = state.state, onStateChange = { state.state = it },
            country = state.country, onCountryChange = { state.country = it },
            isLookingUp = isPinCodeLookupInProgress,
            lookupErrorMessage = pinCodeLookupResult?.takeIf { it.pinCode == state.pinCode && !it.success }?.errorMessage,
            onLookupPinCode = onLookupPinCode,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** GSTIN, PAN, GST state code, TAN, UDYAM. PAN and state code auto-fill from a valid GSTIN. */
@Composable
fun BusinessTaxFields(state: BusinessDetailsFormState, modifier: Modifier = Modifier) {
    // A GSTIN contains its holder's PAN (characters 3-12) and GST state code (characters 1-2).
    // PAN is only filled while still blank - never overwrites a value the user typed.
    LaunchedEffect(state.gstin) {
        if (state.gstin.isBlank() || state.gstinInvalid) return@LaunchedEffect
        if (state.pan.isBlank()) ContactFieldValidation.extractPanFromGstin(state.gstin)?.let { state.pan = it }
        state.stateCode = state.gstin.take(2)
    }
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FormField(
                value = state.gstin, onValueChange = { state.gstin = Constants.normalizeTaxId(it) }, label = "GSTIN",
                isError = state.gstinInvalid, supportingText = if (state.gstinInvalid) "Not a valid GSTIN" else null,
                modifier = Modifier.weight(1f)
            )
            FormField(
                value = state.pan, onValueChange = { state.pan = Constants.normalizeTaxId(it) }, label = "PAN",
                isError = state.panFormatInvalid || state.panHolderTypeInvalid,
                supportingText = when {
                    state.panFormatInvalid -> "Not a valid PAN"
                    state.panHolderTypeInvalid ->
                        "A ${state.constitutionType.name.lowercase().replace('_', ' ')}'s PAN must have '${state.expectedPanHolderChar}' as its 4th character"
                    else -> null
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        FormField(
            value = state.stateCode, onValueChange = { state.stateCode = it.filter { c -> c.isDigit() }.take(2) }, label = "GST State Code",
            keyboardType = KeyboardType.Number,
            supportingText = Constants.GST_STATE_CODES[state.stateCode] ?: "Filled from GSTIN or PIN code",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FormField(value = state.tan, onValueChange = { state.tan = Constants.normalizeTaxId(it) }, label = "TAN (optional)", modifier = Modifier.weight(1f))
            FormField(value = state.udyam, onValueChange = { state.udyam = Constants.normalizeTaxId(it) }, label = "UDYAM (optional)", modifier = Modifier.weight(1f))
        }
    }
}
