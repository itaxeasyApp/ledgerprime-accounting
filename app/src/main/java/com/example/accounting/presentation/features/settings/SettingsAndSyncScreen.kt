package com.example.accounting.presentation.features.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.viewmodel.AccountingUiState

/**
 * Settings structure correction (single-business app) - previously a flat scroll of cards under
 * one "My Business & Sync" screen. Restructured into a real hub matching the requested tree:
 *
 * Settings
 *  |- My Business (Edit Business Details / GST Details / Contact Details / Invoice & Business
 *  |   Preferences)
 *  |- Financial Year
 *  |- Backup & Sync
 *  |- Users / Staff (reserved - no staff/role feature exists yet, see UsersStaffStep)
 *  |- Advanced Settings
 *
 * Internal step navigation only (no new AppRoute/back-stack entries) - same pattern
 * GstReturnDashboardScreen's own GstWizardStep already uses for a whole multi-screen flow inside
 * one route. Never exposes a business list/switcher anywhere in this tree (product decision -
 * single business per app, see the delete dialog below).
 */
private sealed class SettingsStep {
    object Root : SettingsStep()
    object MyBusiness : SettingsStep()
    object EditBusinessDetails : SettingsStep()
    object GstDetails : SettingsStep()
    object ContactDetails : SettingsStep()
    object InvoicePreferences : SettingsStep()
    object DangerZone : SettingsStep()
    object FinancialYear : SettingsStep()
    object BackupSync : SettingsStep()
    object UsersStaff : SettingsStep()
    object AdvancedSettings : SettingsStep()
}

/** Delete Business - Strict Safety: two real stages, never a single tap from anywhere casual.
 * [Warning] explains the permanent effect and offers backing up first (routes to Backup & Sync
 * instead of proceeding) before the user can even reach the type-to-confirm stage; [Confirm] is
 * the existing type-the-exact-name gate. Reaching [Warning] itself already requires navigating
 * Settings > My Business > Danger Zone > Delete My Business - three deliberate taps before any
 * dialog even appears. */
private sealed class DeleteBusinessStage {
    object Warning : DeleteBusinessStage()
    object Confirm : DeleteBusinessStage()
}

@Composable
fun SettingsAndSyncScreen(
    uiState: AccountingUiState,
    onOpenCreateCompany: () -> Unit,
    /** Saves a whole edited [Company] snapshot (see [AccountingRepository.updateCompany]) - each
     * sub-form below builds its own copy of the current business with only its own fields changed
     * and everything else carried over untouched, so one save path covers Business Details/GST
     * Details/Contact Details without three separate ViewModel functions. */
    onSaveCompany: (Company) -> Unit = {},
    /** Full Company CRUD - Delete. Real, irreversible, cascades all of that business's data
     * (see [com.example.accounting.data.repository.AccountingRepository.deleteCompany]). */
    onDeleteCompany: (Company) -> Unit = {},
    onTogglePeriodLock: (AccountingPeriod) -> Unit,
    onAddPreviousFinancialYear: () -> Unit = {},
    onTriggerSync: () -> Unit,
    onUpdateAccountingConfiguration: (AccountingMode?, BusinessType?) -> Unit = { _, _ -> },
    isCloudSyncLoggedIn: Boolean = false,
    onOpenLogin: () -> Unit = {},
    onCloudSyncLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentCompany = uiState.currentCompany
    var step by remember { mutableStateOf<SettingsStep>(SettingsStep.Root) }
    var deleteStage by remember { mutableStateOf<DeleteBusinessStage?>(null) }

    val stageSnapshot = deleteStage
    if (stageSnapshot != null && currentCompany != null) {
        val target = currentCompany
        when (stageSnapshot) {
            // Stage 1 - explains the permanent effect and offers a real way out (back up first)
            // before the user can even reach the actual confirmation gate below.
            DeleteBusinessStage.Warning -> AlertDialog(
                onDismissRequest = { deleteStage = null },
                title = { Text("Delete '${target.name}'?") },
                text = {
                    Text(
                        "This permanently deletes your business and everything under it - ledgers, " +
                            "vouchers, stock, parties, invoices, and GST records. Accounting data " +
                            "cannot be recovered after this. If you want to keep a copy, sign in to " +
                            "Backup & Sync first and sync your data before deleting."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { deleteStage = DeleteBusinessStage.Confirm }) {
                        Text("Continue to Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { deleteStage = null; step = SettingsStep.BackupSync }) { Text("Back Up First") }
                        TextButton(onClick = { deleteStage = null }) { Text("Cancel") }
                    }
                }
            )

            // Stage 2 - the real, final gate. Product decision - single-business app: deleting
            // your business is always allowed (never a hard block), but it is never "choose
            // another one from a list" - there is no other business to fall back to
            // (MainAppScreen shows the Dashboard with a "Set Up My Business" prompt for that
            // empty state instead). Typing the exact business name is real friction against an
            // accidental tap wiping the only business this app has, matching how this class of
            // irreversible delete is gated everywhere else (e.g. GitHub repo deletion). Reaching
            // even Stage 1 above already required Settings > My Business > Danger Zone > Delete
            // My Business - never a single casual tap from the business summary itself.
            DeleteBusinessStage.Confirm -> {
                var confirmText by remember(target.companyId) { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { deleteStage = null },
                    title = { Text("Confirm deletion") },
                    text = {
                        Column {
                            Text("This cannot be undone.")
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Type \"${target.name}\" to confirm:",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = confirmText,
                                onValueChange = { confirmText = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeleteCompany(target)
                                deleteStage = null
                                step = SettingsStep.Root
                            },
                            enabled = confirmText == target.name
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteStage = null }) { Text("Cancel") }
                    }
                )
            }
        }
    }

    when (step) {
        is SettingsStep.Root -> SettingsRootStep(
            // Display precedence rule (docs/57_BUSINESS_IDENTITY_DISPLAY.md) - Business Profile's
            // Trade Name first, Company's registered name only as fallback, so this row always
            // matches what the Dashboard header/Drawer show - never a second, independently-stale
            // "business name" display.
            businessName = uiState.businessProfile?.businessName?.ifBlank { null } ?: currentCompany?.name,
            onOpenMyBusiness = { step = SettingsStep.MyBusiness },
            onOpenFinancialYear = { step = SettingsStep.FinancialYear },
            onOpenBackupSync = { step = SettingsStep.BackupSync },
            onOpenUsersStaff = { step = SettingsStep.UsersStaff },
            onOpenAdvanced = { step = SettingsStep.AdvancedSettings },
            modifier = modifier
        )

        is SettingsStep.MyBusiness -> MyBusinessStep(
            company = currentCompany,
            onBack = { step = SettingsStep.Root },
            onOpenCreateCompany = onOpenCreateCompany,
            onOpenEditBusinessDetails = { step = SettingsStep.EditBusinessDetails },
            onOpenGstDetails = { step = SettingsStep.GstDetails },
            onOpenContactDetails = { step = SettingsStep.ContactDetails },
            onOpenInvoicePreferences = { step = SettingsStep.InvoicePreferences },
            onOpenDangerZone = { step = SettingsStep.DangerZone },
            modifier = modifier
        )

        is SettingsStep.EditBusinessDetails -> currentCompany?.let { company ->
            EditBusinessDetailsStep(company = company, onBack = { step = SettingsStep.MyBusiness }, onSave = onSaveCompany, modifier = modifier)
        }

        is SettingsStep.DangerZone -> currentCompany?.let { company ->
            DangerZoneStep(company = company, onBack = { step = SettingsStep.MyBusiness }, onRequestDelete = { deleteStage = DeleteBusinessStage.Warning }, modifier = modifier)
        }

        is SettingsStep.GstDetails -> currentCompany?.let { company ->
            GstDetailsStep(company = company, onBack = { step = SettingsStep.MyBusiness }, onSave = onSaveCompany, modifier = modifier)
        }

        is SettingsStep.ContactDetails -> currentCompany?.let { company ->
            ContactDetailsStep(company = company, onBack = { step = SettingsStep.MyBusiness }, onSave = onSaveCompany, modifier = modifier)
        }

        is SettingsStep.InvoicePreferences -> currentCompany?.let { company ->
            InvoicePreferencesStep(
                company = company,
                onBack = { step = SettingsStep.MyBusiness },
                onUpdateAccountingConfiguration = onUpdateAccountingConfiguration,
                modifier = modifier
            )
        }

        is SettingsStep.FinancialYear -> FinancialYearStep(
            uiState = uiState,
            onBack = { step = SettingsStep.Root },
            onTogglePeriodLock = onTogglePeriodLock,
            onAddPreviousFinancialYear = onAddPreviousFinancialYear,
            modifier = modifier
        )

        is SettingsStep.BackupSync -> BackupSyncStep(
            uiState = uiState,
            onBack = { step = SettingsStep.Root },
            onTriggerSync = onTriggerSync,
            isCloudSyncLoggedIn = isCloudSyncLoggedIn,
            onOpenLogin = onOpenLogin,
            onCloudSyncLogout = onCloudSyncLogout,
            modifier = modifier
        )

        is SettingsStep.UsersStaff -> UsersStaffStep(onBack = { step = SettingsStep.Root }, modifier = modifier)

        is SettingsStep.AdvancedSettings -> AdvancedSettingsStep(onBack = { step = SettingsStep.Root }, modifier = modifier)
    }
}

/** Shared back+title header for every non-root step below - same shape as
 * GstReturnDashboardScreen's own GstDetailBackHeader, kept local here rather than shared across
 * files (small, purely presentational). */
@Composable
private fun SettingsBackHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsRootStep(
    businessName: String?,
    onOpenMyBusiness: () -> Unit,
    onOpenFinancialYear: () -> Unit,
    onOpenBackupSync: () -> Unit,
    onOpenUsersStaff: () -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(4.dp))

        SectionCard(
            title = "My Business",
            subtitle = businessName ?: "Not set up yet",
            onClick = onOpenMyBusiness,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "Financial Year",
            subtitle = "Years, periods, and locking",
            onClick = onOpenFinancialYear,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "Backup & Sync",
            subtitle = "Local sync status and optional cloud backup",
            onClick = onOpenBackupSync,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "Users / Staff",
            subtitle = "Not enabled - you're the only user on this business",
            onClick = onOpenUsersStaff,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "Advanced Settings",
            subtitle = "Additional configuration",
            onClick = onOpenAdvanced,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun MyBusinessStep(
    company: Company?,
    onBack: () -> Unit,
    onOpenCreateCompany: () -> Unit,
    onOpenEditBusinessDetails: () -> Unit,
    onOpenGstDetails: () -> Unit,
    onOpenContactDetails: () -> Unit,
    onOpenInvoicePreferences: () -> Unit,
    onOpenDangerZone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsBackHeader("My Business", onBack)

        if (company == null) {
            ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "You haven't set up your business yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = onOpenCreateCompany) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Set Up My Business")
                    }
                }
            }
            return@Column
        }

        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(company.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    "GSTIN: ${company.gstin.ifBlank { "Unregistered" }} • ${company.stateName}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(
            title = "Edit Business Details",
            subtitle = "Legal entity name, trade name",
            onClick = onOpenEditBusinessDetails,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "GST Details",
            subtitle = "GSTIN, PAN, state",
            onClick = onOpenGstDetails,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "Contact Details",
            subtitle = "Phone, email, address",
            onClick = onOpenContactDetails,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        SectionCard(
            title = "Invoice / Business Preferences",
            subtitle = "Inventory tracking, trading vs service",
            onClick = onOpenInvoicePreferences,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
        ) {}

        Spacer(modifier = Modifier.height(16.dp))

        // Delete Business - Strict Safety: never a prominent/casual trash button on the summary
        // card above. Deleting is real, destructive work and must feel like it - tucked away
        // behind its own deliberately-labeled "Danger Zone" entry, one more full navigation away
        // from anything a normal user taps while just viewing their business.
        SectionCard(
            title = "Danger Zone",
            subtitle = "Delete this business",
            onClick = onOpenDangerZone,
            trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        ) {}

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun DangerZoneStep(company: Company, onBack: () -> Unit, onRequestDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsBackHeader("Danger Zone", onBack)

        ElevatedCard(
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Delete My Business", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Permanently deletes '${company.name}' and everything under it - ledgers, vouchers, " +
                        "stock, parties, invoices, and GST records. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onRequestDelete,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete My Business")
                }
            }
        }
    }
}

@Composable
private fun EditBusinessDetailsStep(company: Company, onBack: () -> Unit, onSave: (Company) -> Unit, modifier: Modifier = Modifier) {
    var name by remember(company.companyId) { mutableStateOf(company.name) }
    var tradeName by remember(company.companyId) { mutableStateOf(company.tradeName) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsBackHeader("Edit Business Details", onBack)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Legal Entity Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tradeName, onValueChange = { tradeName = it }, label = { Text("Trade Name (Optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { onSave(company.copy(name = name, tradeName = tradeName)); onBack() },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Changes") }
    }
}

@Composable
private fun GstDetailsStep(company: Company, onBack: () -> Unit, onSave: (Company) -> Unit, modifier: Modifier = Modifier) {
    var gstin by remember(company.companyId) { mutableStateOf(company.gstin) }
    var pan by remember(company.companyId) { mutableStateOf(company.pan) }
    var stateCode by remember(company.companyId) { mutableStateOf(company.stateCode) }

    // Real gap fix (docs/CORRECTIONS_LOG.md, user request: "Extract Pan No from GSTIN") - a GSTIN
    // already contains its holder's real PAN (characters 3-12); auto-fills PAN the moment a valid
    // GSTIN is entered, only while PAN is still blank - never overwrites a value the user typed.
    androidx.compose.runtime.LaunchedEffect(gstin) {
        if (pan.isBlank()) com.example.accounting.core.common.ContactFieldValidation.extractPanFromGstin(gstin)?.let { pan = it }
    }

    // Play Store readiness correction (docs/CORRECTIONS_LOG.md) - this is the authoritative
    // statutory GSTIN/PAN (GST filing, e-invoice QR all read Company directly, never Business
    // Profile) - real format validation matters most here, not just cosmetically on a branding copy.
    val gstinInvalid = gstin.isNotBlank() && !com.example.accounting.domain.taxation.gst.GSTRules.isValidGSTIN(gstin)
    val panInvalid = !com.example.accounting.core.common.ContactFieldValidation.isValidPan(pan)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsBackHeader("GST Details", onBack, subtitle = "Registration status and filing frequency are under GST Dashboard > GST Settings")
        OutlinedTextField(
            value = gstin, onValueChange = { gstin = it.uppercase() }, label = { Text("GSTIN") },
            isError = gstinInvalid, supportingText = if (gstinInvalid) { { Text("Not a valid GSTIN") } } else null,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pan, onValueChange = { pan = it.uppercase() }, label = { Text("PAN") },
            isError = panInvalid, supportingText = if (panInvalid) { { Text("Not a valid PAN") } } else null,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(value = stateCode, onValueChange = { stateCode = it }, label = { Text("State Code") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { onSave(company.copy(gstin = gstin, pan = pan, stateCode = stateCode)); onBack() },
            enabled = !gstinInvalid && !panInvalid,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Changes") }
    }
}

@Composable
private fun ContactDetailsStep(company: Company, onBack: () -> Unit, onSave: (Company) -> Unit, modifier: Modifier = Modifier) {
    var phone by remember(company.companyId) { mutableStateOf(company.phone) }
    var email by remember(company.companyId) { mutableStateOf(company.email) }
    var address by remember(company.companyId) { mutableStateOf(company.address) }
    var pinCode by remember(company.companyId) { mutableStateOf(company.pinCode) }

    val phoneInvalid = !com.example.accounting.core.common.ContactFieldValidation.isValidIndianMobile(phone)
    val emailInvalid = !com.example.accounting.core.common.ContactFieldValidation.isValidEmail(email)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsBackHeader("Contact Details", onBack)
        OutlinedTextField(
            value = phone, onValueChange = { phone = it }, label = { Text("Phone") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
            isError = phoneInvalid, supportingText = if (phoneInvalid) { { Text("Not a valid 10-digit mobile number") } } else null,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
            isError = emailInvalid, supportingText = if (emailInvalid) { { Text("Not a valid email address") } } else null,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Registered Business Address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pinCode, onValueChange = { pinCode = it }, label = { Text("PIN Code") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { onSave(company.copy(phone = phone, email = email, address = address, pinCode = pinCode)); onBack() },
            enabled = !phoneInvalid && !emailInvalid,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Changes") }
    }
}

@Composable
private fun InvoicePreferencesStep(
    company: Company,
    onBack: () -> Unit,
    onUpdateAccountingConfiguration: (AccountingMode?, BusinessType?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SettingsBackHeader("Invoice / Business Preferences", onBack)
        Text(
            "Controls which features and reports are available for this business",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Track Inventory", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            if (company.accountingMode == AccountingMode.ACCOUNT_WITH_INVENTORY) "Stock, valuation & COGS are active" else "Accounts only - no stock tracking",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = company.accountingMode == AccountingMode.ACCOUNT_WITH_INVENTORY,
                        onCheckedChange = { checked ->
                            onUpdateAccountingConfiguration(if (checked) AccountingMode.ACCOUNT_WITH_INVENTORY else AccountingMode.ACCOUNT_ONLY, null)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Service Business", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            if (company.businessType == BusinessType.SERVICE) "Reports show Income & Expenditure" else "Reports show Trading Profit & Loss",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = company.businessType == BusinessType.SERVICE,
                        onCheckedChange = { checked ->
                            onUpdateAccountingConfiguration(null, if (checked) BusinessType.SERVICE else BusinessType.TRADING)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FinancialYearStep(
    uiState: AccountingUiState,
    onBack: () -> Unit,
    onTogglePeriodLock: (AccountingPeriod) -> Unit,
    onAddPreviousFinancialYear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsBackHeader("Financial Year", onBack)

        if (uiState.financialYears.isEmpty()) {
            Text("No financial years yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            uiState.financialYears.forEach { fy ->
                val periodsForFy = uiState.periods.filter { it.financialYearId == fy.financialYearId }
                ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fy.fyCode,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (fy.isCurrent) FontWeight.Bold else FontWeight.Normal)
                            )
                            if (fy.isCurrent) {
                                Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (periodsForFy.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            periodsForFy.forEach { period ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(period.name, style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { onTogglePeriodLock(period) }) {
                                        Icon(
                                            if (period.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = if (period.isLocked) "Unlock ${period.name}" else "Lock ${period.name}",
                                            tint = if (period.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onAddPreviousFinancialYear) { Text("+ Add Previous Year") }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun BackupSyncStep(
    uiState: AccountingUiState,
    onBack: () -> Unit,
    onTriggerSync: () -> Unit,
    isCloudSyncLoggedIn: Boolean,
    onOpenLogin: () -> Unit,
    onCloudSyncLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Sign out of Cloud Sync?") },
            text = { Text("Your books stay right here on this device either way - signing out only turns off backup/sync until you sign back in.") },
            confirmButton = {
                TextButton(onClick = { showLogoutConfirm = false; onCloudSyncLogout() }) { Text("Sign Out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsBackHeader("Backup & Sync", onBack)

        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Data Sync", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (uiState.pendingSyncCount > 0) "${uiState.pendingSyncCount} change(s) waiting to sync" else "Everything is synced",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onTriggerSync, enabled = !uiState.isSyncing) {
                    Icon(
                        imageVector = if (uiState.isSyncing) Icons.Default.Sync else Icons.Default.CloudDone,
                        contentDescription = null, modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (uiState.isSyncing) "Syncing..." else "Sync Now")
                }
            }
        }

        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Cloud Sync", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Optional - your books work fully offline either way. Sign in only to back up and sync to the cloud.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isCloudSyncLoggedIn) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Signed in", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                        TextButton(onClick = { showLogoutConfirm = true }) { Text("Sign Out") }
                    }
                } else {
                    // Phone/OTP login (Week 1, Play Store update plan) - a dedicated full page
                    // (see presentation/features/auth/LoginScreen.kt), not an inline email/password
                    // form here anymore.
                    Button(onClick = onOpenLogin) { Text("Sign In") }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

/** Reserved for real future work - no role/staff/permissions feature exists in this app today.
 * Product rule: normal single-owner onboarding never asks "select your role" (the first person to
 * set up a business is simply the owner - there is nothing else to choose from until a real
 * staff-management feature is intentionally built, at which point THIS screen gains an "Add
 * Staff" flow, never the other way around). */
@Composable
private fun UsersStaffStep(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsBackHeader("Users / Staff", onBack)
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                Text("You're the only user on this business", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Staff accounts and permission roles aren't available yet. When they are, you'll be able to add staff and assign what they can see and do from here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettingsStep(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsBackHeader("Advanced Settings", onBack)
        Text(
            "Nothing here yet.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
