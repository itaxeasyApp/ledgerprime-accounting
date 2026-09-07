package com.example.accounting.presentation.features.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Search
import com.example.accounting.automation.notifications.AutomationNotification
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.rendering.TabularReportData
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.ActionButtonStyle
import com.example.accounting.presentation.components.Amount
import com.example.accounting.presentation.components.FormField
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.components.StatusBadge
import com.example.accounting.presentation.components.TableRow
import com.example.accounting.presentation.theme.Spacing
import com.example.accounting.presentation.viewmodel.AccountingUiState
import com.example.ui.theme.IndigoContainer
import com.example.ui.theme.IndigoTax
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturn
import com.example.accounting.domain.taxation.gstreturn.GstReturnApplicability
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSection
import com.example.accounting.domain.taxation.gstreturn.GstReturnSectionStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import kotlinx.coroutines.launch
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The GST Return Dashboard (Rule 33) - reached from Reports Center's existing GST category, using
 * only the existing [SectionCard]/[ActionButton]/[TableRow]/[Amount] primitives (no new design
 * system). Redesigned to be compact: FY/scheme/registration render as one dense strip instead of
 * three separate cards, and a return's own status/actions collapse into fewer, denser rows so the
 * real controls sit near the top of the screen rather than below several stacked headers.
 */
@Composable
fun GstReturnDashboardView(
    uiState: AccountingUiState,
    onSelectPeriod: (GstQuarter, Int?, GstReturnType, GstReturnPeriodicity, GstFilingMode) -> Unit,
    onOpenReturn: (String) -> Unit,
    onClearSelection: () -> Unit,
    onPrepare: () -> Unit,
    onValidate: () -> Unit,
    onGenerateJson: () -> Unit,
    onShareArtifact: (String) -> Unit,
    onImportResponseFile: () -> Unit,
    onMarkFiled: (String) -> Unit,
    onSubmitOnline: () -> Unit,
    onUpdateGstEnabled: (Boolean) -> Unit,
    onUpdateGstScheme: (GstScheme) -> Unit,
    onUpdateGstFilingFrequency: (GstReturnPeriodicity) -> Unit,
    onExportCsv: () -> Unit = {},
    onExportGstrJson: () -> Unit = {},
    onSetNilReturn: (Boolean) -> Unit = {},
    onExportJson: () -> Unit = {},
    onPreviewPdf: () -> Unit = {},
    onDownloadPdf: () -> Unit = {},
    onPrintPdf: () -> Unit = {},
    onSharePdfSummary: () -> Unit = {},
    onUpdateGstReminderEnabled: (Boolean) -> Unit = {},
    onSaveProviderUsername: (String) -> Unit = {},
    getProviderUsername: () -> String = { "" },
    onOpenSalesRegister: () -> Unit = {},
    onOpenLedgers: () -> Unit = {},
    gstBottomNavRequest: String? = null,
    onConsumeGstBottomNavRequest: () -> Unit = {},
    /** Error Details' "Fix Now" (reference image) - opens the real voucher a validation issue
     * points to. Wired to the existing read-only Voucher detail view, not a new direct-edit path -
     * editing a posted voucher's tax line touches this app's immutable-posting invariant, which
     * needs an explicit decision before any new mutation path is built against it. */
    onFixNow: (String) -> Unit = {},
    onMarkProcessingManually: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onLogoutCloudSync: () -> Unit = {},
    onActiveBottomTabChanged: (String) -> Unit = {}
) {
    var step by remember { mutableStateOf<GstWizardStep>(GstWizardStep.Dashboard) }
    var pendingReturnType by remember { mutableStateOf<GstReturnType?>(null) }

    // Keeps the bottom nav's `selected` highlight correct no matter how a top-level step was
    // reached - not just via its own tab (see AccountingUiState.gstActiveBottomTab's own KDoc for
    // why gstBottomNavRequest alone, being one-shot/consumed, can't do this).
    androidx.compose.runtime.LaunchedEffect(step) {
        when (step) {
            GstWizardStep.Dashboard -> onActiveBottomTabChanged("Dashboard")
            GstWizardStep.History -> onActiveBottomTabChanged("Returns")
            GstWizardStep.More -> onActiveBottomTabChanged("More")
            else -> {}
        }
    }

    // The GST Dashboard's own bottom nav (rendered by MainAppScreen's Scaffold, outside this
    // composable) can only ever request a coarse jump - "Dashboard"/"Returns"/etc. - since it has
    // no visibility into mid-wizard state like a selected return; each request is one-shot,
    // consumed immediately so leaving and re-entering a step doesn't re-fire it.
    androidx.compose.runtime.LaunchedEffect(gstBottomNavRequest) {
        when (gstBottomNavRequest) {
            "Dashboard" -> step = GstWizardStep.Dashboard
            "Returns" -> step = GstWizardStep.History
            "More" -> step = GstWizardStep.More
        }
        if (gstBottomNavRequest != null) onConsumeGstBottomNavRequest()
    }

    // Reference-flow rule (screens 2-13): navigation is entirely step-driven, never implicit on
    // `selectedGstReturn` alone - opening a return from History or completing Return Details both
    // explicitly set `step`, so the same "a return is selected" state can legitimately mean
    // "show Preview" or "show Filing Progress" depending on how the user got there.
    //
    // One shared 16dp horizontal inset for every step - matches the rest of the app's own
    // convention (see DashboardScreen's LazyColumn) - applied once here rather than per-step, since
    // no individual Gst*Step composable added its own (this was the GST Dashboard's real
    // "not customized" padding gap: content ran edge-to-edge here while every other screen in the
    // app already had this inset).
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    when (val s = step) {
        GstWizardStep.Dashboard -> GstDashboardStep(
            uiState = uiState,
            onFileReturn = { type -> pendingReturnType = type; step = GstWizardStep.SelectReturn },
            onOpenSalesRegister = onOpenSalesRegister,
            onOpenLedgers = onOpenLedgers,
            onOpenHistory = { step = GstWizardStep.History },
            onOpenSettings = { step = GstWizardStep.Settings }
        )
        GstWizardStep.Settings -> GstSettingsStep(
            uiState = uiState,
            onBack = { step = GstWizardStep.Dashboard },
            onUpdateGstEnabled = onUpdateGstEnabled,
            onUpdateGstScheme = onUpdateGstScheme,
            onUpdateGstFilingFrequency = onUpdateGstFilingFrequency,
            onUpdateGstReminderEnabled = onUpdateGstReminderEnabled
        )
        GstWizardStep.SelectReturn -> GstSelectReturnStep(
            uiState = uiState,
            onBack = { step = GstWizardStep.Dashboard },
            onSelect = { type -> pendingReturnType = type; step = GstWizardStep.ReturnDetails }
        )
        GstWizardStep.ReturnDetails -> GstReturnDetailsStep(
            uiState = uiState,
            returnType = pendingReturnType ?: GstReturnType.GSTR1,
            onBack = { step = GstWizardStep.SelectReturn },
            onNext = { quarter, month, periodicity, filingMode ->
                onSelectPeriod(quarter, month, pendingReturnType ?: GstReturnType.GSTR1, periodicity, filingMode)
                step = GstWizardStep.DataSummary
            }
        )
        GstWizardStep.DataSummary -> GstDataSummaryStep(
            uiState = uiState,
            onBack = { onClearSelection(); step = GstWizardStep.ReturnDetails },
            onRefresh = onPrepare,
            onNext = { onPrepare(); step = GstWizardStep.Preview }
        )
        GstWizardStep.Preview -> {
            val selected = uiState.selectedGstReturn
            if (selected == null) {
                GstWizardLoading(title = "Return Preview", onBack = { step = GstWizardStep.DataSummary })
            } else {
                GstReturnDetailView(
                    uiState, selected,
                    onClearSelection = { onClearSelection(); step = GstWizardStep.Dashboard },
                    onPrepare = onPrepare, onValidate = onValidate, onGenerateJson = onGenerateJson,
                    onShareArtifact = onShareArtifact, onImportResponseFile = onImportResponseFile,
                    onMarkFiled = onMarkFiled, onSubmitOnline = onSubmitOnline, onExportCsv = onExportCsv,
                    onExportGstrJson = onExportGstrJson, onSetNilReturn = onSetNilReturn, onExportJson = onExportJson,
                    onPreviewPdf = onPreviewPdf, onDownloadPdf = onDownloadPdf, onPrintPdf = onPrintPdf,
                    onSharePdfSummary = onSharePdfSummary,
                    onProceedToFile = { step = GstWizardStep.Authenticate },
                    onFixNow = onFixNow,
                    onMarkProcessingManually = onMarkProcessingManually
                )
            }
        }
        GstWizardStep.Authenticate -> GstAuthenticateStep(
            uiState = uiState,
            initialUsername = getProviderUsername(),
            onBack = { step = GstWizardStep.Preview },
            onAuthenticate = { username ->
                onSaveProviderUsername(username)
                onSubmitOnline()
                step = GstWizardStep.FilingProgress
            }
        )
        GstWizardStep.FilingProgress -> GstFilingProgressStep(
            uiState = uiState,
            onBack = { step = GstWizardStep.Preview },
            onUseOfflineInstead = { step = GstWizardStep.Preview },
            onBackToDashboard = { onClearSelection(); step = GstWizardStep.Dashboard },
            onDownloadPdf = onDownloadPdf
        )
        GstWizardStep.History -> GstReturnHistoryStep(
            uiState = uiState,
            onBack = { step = GstWizardStep.Dashboard },
            onOpen = { gr ->
                onOpenReturn(gr.gstReturnId)
                step = if (gr.status == GstReturnStatus.FILED || gr.status == GstReturnStatus.SUBMITTED) {
                    GstWizardStep.Acknowledgement
                } else {
                    GstWizardStep.Preview
                }
            }
        )
        GstWizardStep.Acknowledgement -> GstAcknowledgementStep(
            uiState = uiState,
            onBack = { step = GstWizardStep.History },
            onSharePdfSummary = onSharePdfSummary,
            onDownloadPdf = onDownloadPdf,
            onPrintPdf = onPrintPdf
        )
        GstWizardStep.More -> GstMoreStep(
            uiState = uiState,
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToSupport = onNavigateToSupport,
            onLogoutCloudSync = onLogoutCloudSync
        )
    }
    }
}

/** The reference flow's 9 named steps (Dashboard -> Select Return -> Return Details -> Data
 * Summary -> Return Preview -> Authenticate -> Filing Progress -> Success -> Return History) -
 * Success is a state WITHIN Filing Progress ([GstFilingProgressStep] switches to it once
 * [GstReturn.status] genuinely reaches SUBMITTED/FILED), not a separate step here, since it has no
 * navigation of its own beyond what Filing Progress already provides. */
private sealed class GstWizardStep {
    object Dashboard : GstWizardStep()
    object SelectReturn : GstWizardStep()
    object ReturnDetails : GstWizardStep()
    object DataSummary : GstWizardStep()
    object Preview : GstWizardStep()
    object Authenticate : GstWizardStep()
    object FilingProgress : GstWizardStep()
    object History : GstWizardStep()
    /** GSTR-1 Settings - registration/scheme/filing-frequency (previously on the Dashboard, moved
     * here so the Dashboard matches the reference exactly) plus Automation (explicitly relocated
     * here, out of the Dashboard, per instruction). */
    object Settings : GstWizardStep()
    /** Screen 7 (reference: 8-screen WhatsApp image) - reached from History by opening a return
     * that has actually reached SUBMITTED/FILED; shows only real [GstReturn] fields (ARN, filed
     * date, GSTIN, legal name) never a fabricated scanned-document image. */
    object Acknowledgement : GstWizardStep()
    /** Screen 8 (reference: 8-screen WhatsApp image) - the GST Dashboard's own "More" tab. Each row
     * either opens a real existing [com.example.accounting.presentation.navigation.AppRoute] or, for
     * rows with no real destination in this app (Users & Roles, Bank Accounts, E-Way Bills,
     * E-Invoice), is shown disabled with an honest "Not yet available" subtitle - never a fake
     * destination. */
    object More : GstWizardStep()
}

@Composable
private fun GstWizardLoading(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader(title, onBack)
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Screen 2 - Dashboard (reference image, Royal Purple/Off-White). Every figure is real: the
 * current period is derived from today's date and the company's own filing frequency (never a
 * fixed sample month), Returns Status reads real [uiState.gstReturns] for that period (a return
 * that was never opened for this period has no row - PENDING is the honest default only for a
 * return type this company can actually file, per [GstReturnApplicability]), and Recent Activity
 * is the same already-loaded [uiState.vouchers] every other screen uses. Quick Actions reuses real
 * existing destinations (Sales Register/Chart of Accounts/Return History) - never a tile that does
 * nothing. Per explicit instruction: no "Not a Government Filing Service" disclosure on this
 * screen, and Automation lives only in GSTR-1 Settings ([GstSettingsStep]), reached via the
 * settings row below - not duplicated here. */
@Composable
private fun GstDashboardStep(
    uiState: AccountingUiState,
    onFileReturn: (GstReturnType) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSalesRegister: () -> Unit = {},
    onOpenLedgers: () -> Unit = {}
) {
    val company = uiState.currentCompany
    val scheme = company?.gstScheme ?: GstScheme.REGULAR
    val frequency = company?.gstFilingFrequency ?: GstReturnPeriodicity.MONTHLY
    val today = remember { LocalDate.now() }
    val currentPeriodLabel = remember(today, frequency) {
        if (frequency == GstReturnPeriodicity.MONTHLY) {
            today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + today.year
        } else {
            "${GstQuarter.ofMonth(today.monthValue).label} ${today.year}"
        }
    }
    val applicable = if (company?.gstEnabled == true) GstReturnApplicability.availableReturns(scheme, frequency) else emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            // Surfaces the real fields a return is actually prepared under (scheme + filing
            // frequency) directly on the Dashboard, before any "File X" action - per explicit
            // instruction, tapping into a return must never be the first place these show up.
            // Tapping through goes to the same Settings screen these are edited on.
            SectionCard(
                onClick = if (company?.gstEnabled == true) onOpenSettings else null,
                title = "Current Period", trailing = { GstIconBadge() }
            ) {
                Text(currentPeriodLabel, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                if (company?.gstEnabled == true) {
                    Text(
                        if (scheme == GstScheme.REGULAR) {
                            "Regular · ${if (frequency == GstReturnPeriodicity.QUARTERLY) "QRMP (Quarterly)" else "Monthly"} filing"
                        } else {
                            "Composition scheme"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        if (company?.gstEnabled != true) {
            item {
                SectionCard(title = "GST Not Registered", subtitle = "Register this company's GSTIN under Settings to file returns") {}
            }
        } else if (applicable.isEmpty()) {
            item { SectionCard(title = "No returns applicable for the current scheme") {} }
        } else {
            item { Text("Returns Status", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
            items(applicable, key = { it.returnType }) { rule ->
                val latest = uiState.gstReturns.filter { it.returnType == rule.returnType }.maxByOrNull { it.createdAt }
                val filed = latest?.status == GstReturnStatus.FILED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(8.dp)
                                .background(if (filed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                        )
                        Text(
                            "${rule.returnType} ${if (latest != null) "- ${latest.periodKey}" else ""}",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Text(
                        if (filed) "Filed" else "Pending",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (filed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    applicable.forEach { rule ->
                        ActionButton(text = "File ${rule.returnType}", onClick = { onFileReturn(rule.returnType) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        // Quick Actions removed (per instruction) - redundant now that the GST Dashboard's own
        // bottom nav (Dashboard/Invoices/Returns/Reports) already covers the same destinations.
        item {
            SectionCard(onClick = onOpenSettings, title = "GSTR-1 Settings", subtitle = "Registration, scheme, filing frequency, automation & reminders", trailing = {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }) {}
        }
        if (uiState.gstReturns.isNotEmpty()) {
            item {
                SectionCard(onClick = onOpenHistory, title = "Return History", subtitle = "${uiState.gstReturns.size} return(s) on file", trailing = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }) {}
            }
        }
        if (uiState.vouchers.isNotEmpty()) {
            item { Text("Recent Activity", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
            items(uiState.vouchers.sortedByDescending { it.date }.take(3), key = { it.voucherId }) { v ->
                SectionCard(title = "${v.voucherType.displayName} - ${v.voucherNumber}", subtitle = v.date.toString()) {
                    Amount(v.totalDebits, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** GSTR-1 Settings - registration/scheme/filing-frequency (moved off the Dashboard so it matches
 * the reference exactly) and Automation (moved here per explicit instruction: "keep Automation
 * only inside GSTR-1 Settings"). Not one of the reference's 9 named screens - it's this app's own
 * real settings surface for the controls the reference doesn't model at all (GST registration
 * on/off, scheme, reminders), reached only via the Dashboard's own "GSTR-1 Settings" row. */
@Composable
private fun GstSettingsStep(
    uiState: AccountingUiState,
    onBack: () -> Unit,
    onUpdateGstEnabled: (Boolean) -> Unit,
    onUpdateGstScheme: (GstScheme) -> Unit,
    onUpdateGstFilingFrequency: (GstReturnPeriodicity) -> Unit,
    onUpdateGstReminderEnabled: (Boolean) -> Unit
) {
    val company = uiState.currentCompany
    val scheme = company?.gstScheme
    val registered = company?.gstEnabled ?: false
    // Real toggles this app can never verify against gst.gov.in itself (no portal connection
    // exists) - a mismatched frequency here would misfile the Dashboard's own Returns Status/File
    // buttons against what's actually due, so a change is confirmed with an explicit warning
    // rather than applied silently.
    var pendingFrequency by remember { mutableStateOf<GstReturnPeriodicity?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("GSTR-1 Settings", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            item {
                SectionCard(
                    title = uiState.currentFinancialYear?.fyCode?.let { "FY $it" } ?: "No Financial Year",
                    trailing = { GstIconBadge() }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("GST Settings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ChoiceRow(listOf(true, false), registered, { if (it) "Registered" else "Unregistered" }) { onUpdateGstEnabled(it) }
                        if (registered) {
                            ChoiceRow(GstScheme.entries, scheme, { if (it == GstScheme.COMPOSITION) "Composition" else "Regular" }) { onUpdateGstScheme(it) }
                            if (scheme == GstScheme.REGULAR) {
                                ChoiceRow(
                                    GstReturnPeriodicity.entries, company?.gstFilingFrequency,
                                    { if (it == GstReturnPeriodicity.QUARTERLY) "QRMP" else "Monthly" }
                                ) { newFreq -> if (newFreq != company?.gstFilingFrequency) pendingFrequency = newFreq }
                            }
                        }
                    }
                }
            }
            if (registered) {
                item {
                    GstAutomationStatusCard(
                        notifications = uiState.gstAutomationNotifications,
                        reminderEnabled = company?.gstr1ReminderEnabled ?: true,
                        onUpdateReminderEnabled = onUpdateGstReminderEnabled
                    )
                }
            }
        }
    }
    pendingFrequency?.let { newFreq ->
        AlertDialog(
            onDismissRequest = { pendingFrequency = null },
            title = { Text("Change filing frequency?") },
            text = {
                Text(
                    "This only changes how LedgerPrime tracks your filing frequency in this app - " +
                        "there is no live connection to the GST portal, so it cannot change your actual " +
                        "filing frequency there. If you switch this here, you must also change it yourself " +
                        "at gst.gov.in, or your real filing frequency will remain whatever you last chose " +
                        "on the portal, regardless of this setting."
                )
            },
            confirmButton = {
                TextButton(onClick = { onUpdateGstFilingFrequency(newFreq); pendingFrequency = null }) { Text("Change in App") }
            },
            dismissButton = { TextButton(onClick = { pendingFrequency = null }) { Text("Cancel") } }
        )
    }
}

/** Screen 3 - Select Return. Lists every [GstReturnType] the reference shows; only the ones
 * [GstReturnApplicability] actually knows how to build for this company's scheme are tappable -
 * the rest (GSTR-9/9C/CMP-08, and GSTR-1/3B/4 when the scheme doesn't apply) render as disabled
 * "Coming soon"/"Not applicable" rows, matching this codebase's own established precedent (Phase
 * 7J's Fund Flow/CMA tiles) for a real gap rather than a fabricated implementation. */
@Composable
private fun GstSelectReturnStep(uiState: AccountingUiState, onBack: () -> Unit, onSelect: (GstReturnType) -> Unit) {
    val company = uiState.currentCompany
    val scheme = company?.gstScheme ?: GstScheme.REGULAR
    val frequency = company?.gstFilingFrequency ?: GstReturnPeriodicity.MONTHLY
    val applicableTypes = GstReturnApplicability.availableReturns(scheme, frequency).map { it.returnType }.toSet()
    data class ReturnMenuItem(val type: GstReturnType?, val label: String, val description: String)
    val items = listOf(
        ReturnMenuItem(GstReturnType.GSTR1, "GSTR-1", "Details of Outward Supplies"),
        ReturnMenuItem(GstReturnType.GSTR3B, "GSTR-3B", "Monthly Summary Return"),
        ReturnMenuItem(null, "GSTR-9", "Annual Return"),
        ReturnMenuItem(null, "GSTR-9C", "Reconciliation Statement"),
        ReturnMenuItem(GstReturnType.CMP08, "CMP-08", "Composition Tax Payment"),
        ReturnMenuItem(GstReturnType.GSTR4, "GSTR-4", "Annual Return (Composition)")
    )
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("Select Return Type", onBack)
        Text(
            "Choose the return you want to file", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(items) { item ->
                // Strict reference match - the row itself carries no "Coming soon" label the
                // screenshot doesn't show; a type this company can't actually file (no backing
                // GstReturnApplicability rule) is simply not clickable, same visual row either way.
                val available = item.type != null && item.type in applicableTypes
                SectionCard(
                    title = item.label, subtitle = item.description,
                    onClick = if (available) ({ onSelect(item.type!!) }) else null,
                    trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) }
                ) {}
            }
        }
    }
}

/** Screen 4 - Return Details. GSTIN/Financial Year are read-only real company facts (never
 * re-entered); Return Period/Filing Frequency reuse the exact same Quarter/Month/[GstFilingMode]
 * selection the old period picker used, just laid out as this reference's own vertical field
 * list instead of a grid of choice chips. */
@Composable
private fun GstReturnDetailsStep(
    uiState: AccountingUiState,
    returnType: GstReturnType,
    onBack: () -> Unit,
    onNext: (GstQuarter, Int?, GstReturnPeriodicity, GstFilingMode) -> Unit
) {
    val company = uiState.currentCompany
    val frequency = company?.gstFilingFrequency ?: GstReturnPeriodicity.MONTHLY
    // CMP-08 (quarterly statement-cum-challan) and GSTR-4 (annual, but modeled quarterly-cadence
    // here alongside CMP-08 since this app doesn't yet model an annual periodicity) are never
    // filed against the company's own Regular-scheme Monthly/QRMP choice - that setting only ever
    // applies to GSTR-1/GSTR-3B.
    val periodicity = if (returnType == GstReturnType.GSTR4 || returnType == GstReturnType.CMP08) GstReturnPeriodicity.QUARTERLY else frequency
    var quarter by remember { mutableStateOf(GstQuarter.ofMonth(LocalDate.now().monthValue)) }
    var month by remember { mutableStateOf<Int?>(if (periodicity == GstReturnPeriodicity.MONTHLY) LocalDate.now().monthValue else null) }
    var filingMode by remember { mutableStateOf(GstFilingMode.OFFLINE) }
    val fy = uiState.currentFinancialYear
    val range = fy?.let { runCatching { com.example.accounting.domain.taxation.gstreturn.GstPeriod.of(it, quarter, month).dateRange() }.getOrNull() }
    var gstinVerifyMessage by remember { mutableStateOf<String?>(null) }
    var verifyingGstin by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader(returnType.name, onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            item { Text("Details of Outward Supplies", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                SectionCard(title = "GSTIN") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(company?.gstin?.takeIf { it.isNotBlank() } ?: "Not set", style = MaterialTheme.typography.bodyLarge)
                            // Real GSTIN lookup, not a fabricated one - there is no free, keyless
                            // public API for this (checked: every real option needs its own
                            // signup/API key), so this honestly reports "not configured" via
                            // UnconfiguredSandboxProviderAdapter, exactly like online filing does.
                            IconButton(
                                enabled = !verifyingGstin,
                                onClick = {
                                    val gstin = company?.gstin
                                    val profile = uiState.businessProfile
                                    if (gstin.isNullOrBlank() || profile == null) {
                                        gstinVerifyMessage = "No GSTIN/Business Profile on file to verify."
                                        return@IconButton
                                    }
                                    verifyingGstin = true
                                    coroutineScope.launch {
                                        val result = com.example.accounting.domain.sandbox.UnconfiguredSandboxProviderAdapter().verifyGstin(
                                            profile, com.example.accounting.domain.sandbox.SandboxEnvironment.TEST, gstin
                                        )
                                        gstinVerifyMessage = when (result) {
                                            is AccountingResult.Success -> "${result.data.legalName} - ${result.data.status}"
                                            is AccountingResult.Failure -> result.error.message
                                        }
                                        verifyingGstin = false
                                    }
                                }
                            ) { Icon(Icons.Default.Search, contentDescription = "Verify GSTIN") }
                        }
                        gstinVerifyMessage?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { SectionCard(title = "Financial Year") { Text(uiState.currentFinancialYear?.fyCode ?: "Not set", style = MaterialTheme.typography.bodyLarge) } }
            item {
                SectionCard(title = "Quarter") {
                    ChoiceRow(GstQuarter.entries, quarter, { it.label }) { quarter = it; if (periodicity == GstReturnPeriodicity.MONTHLY) month = it.months.first() }
                }
            }
            if (periodicity == GstReturnPeriodicity.MONTHLY) {
                item {
                    SectionCard(title = "Return Period (Month)") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            quarter.months.chunked(3).forEach { row -> ChoiceRow(row, month, { monthLabel(it) }) { month = it } }
                        }
                    }
                }
            }
            item { SectionCard(title = "Filing Frequency") { Text(if (periodicity == GstReturnPeriodicity.QUARTERLY) "Quarterly" else "Monthly", style = MaterialTheme.typography.bodyLarge) } }
            item {
                SectionCard(title = "Filing Mode") {
                    ChoiceRow(GstFilingMode.entries, filingMode, { it.name }) { filingMode = it }
                }
            }
            if (range != null) {
                item {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "You are going to file $returnType for the period ${range.start} to ${range.endInclusive}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            item {
                val ready = periodicity != GstReturnPeriodicity.MONTHLY || month != null
                ActionButton(
                    text = "Next", enabled = ready, modifier = Modifier.fillMaxWidth(),
                    onClick = { onNext(quarter, if (periodicity == GstReturnPeriodicity.MONTHLY) month else null, periodicity, filingMode) }
                )
            }
        }
    }
}

/** Screen 5 - Data Summary. Every count is a real, direct read of already-loaded
 * [uiState.vouchers]/[uiState.selectedGstReturnSections] for the selected return's own date
 * range - no second calculation. Sales/Purchase Invoice counts come straight from posted,
 * non-cancelled vouchers (a general business-activity fact, not itself a GSTR-1 concept, which is
 * why it's NOT read from the Gstr1 sections below); Credit/Debit Notes and Total Outward
 * Supplies/Taxable Value ARE read from the already-prepared Gstr1 sections (CDNR/CDNUR row
 * `noteType`, and the same [sumDeep] every other screen uses) so this screen can never show a
 * different "taxable value" than Return Preview does for the same return. Renders honest zeros
 * (never blank/omitted tiles) until Prepare has actually run once. */
@Composable
private fun GstDataSummaryStep(uiState: AccountingUiState, onBack: () -> Unit, onRefresh: () -> Unit, onNext: () -> Unit) {
    val gstReturn = uiState.selectedGstReturn
    val fy = uiState.currentFinancialYear
    val range = if (gstReturn != null && fy != null) {
        runCatching {
            com.example.accounting.domain.taxation.gstreturn.GstPeriod.of(fy, gstReturn.quarter, gstReturn.month).dateRange()
        }.getOrNull()
    } else null
    val vouchersInPeriod = if (range != null) uiState.vouchers.filter { !it.isCancelled && it.date in range } else emptyList()
    val salesCount = vouchersInPeriod.count { it.voucherType == VoucherType.SALES }
    val purchaseCount = vouchersInPeriod.count { it.voucherType == VoucherType.PURCHASE }

    val sections = uiState.selectedGstReturnSections
    val cdnrNotes = sections.firstOrNull { it.sectionKey == "CDNR" }?.let { parseSectionTotals(it.resultDataJson) }
        ?.let { flattenSectionRows("CDNR", it) } ?: emptyList()
    val cdnurNotes = sections.firstOrNull { it.sectionKey == "CDNUR" }?.let { parseSectionTotals(it.resultDataJson) }
        ?.let { flattenSectionRows("CDNUR", it) } ?: emptyList()
    val allNotes = cdnrNotes + cdnurNotes
    val creditNoteCount = allNotes.count { (it["noteType"] as? String) == "CREDIT" }
    val debitNoteCount = allNotes.count { (it["noteType"] as? String) == "DEBIT" }

    var totalOutward = 0L
    var totalTaxable = 0L
    sections.forEach { section ->
        val tree = parseSectionTotals(section.resultDataJson)
        if (tree != null && section.sectionKey in setOf("B2B", "B2CL", "B2CS", "EXP")) {
            totalOutward += sumDeep(tree, "taxableValuePaise") + sumDeep(tree, "cgstPaise") + sumDeep(tree, "sgstPaise") + sumDeep(tree, "igstPaise") + sumDeep(tree, "cessPaise")
            totalTaxable += sumDeep(tree, "taxableValuePaise")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("Data Summary", onBack)
        Text(
            "Summary of your business data for this period", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SectionCard(title = "Sales Invoices", modifier = Modifier.weight(1f)) { Text("$salesCount", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
                    SectionCard(title = "Purchase Invoices", modifier = Modifier.weight(1f)) { Text("$purchaseCount", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SectionCard(title = "Credit Notes (Sales)", modifier = Modifier.weight(1f)) { Text("$creditNoteCount", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
                    SectionCard(title = "Debit Notes (Sales)", modifier = Modifier.weight(1f)) { Text("$debitNoteCount", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
                }
            }
            item {
                SectionCard(title = "Total Outward Supplies") {
                    Column {
                        Amount(Money.fromPaise(totalOutward), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Taxable Value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Amount(Money.fromPaise(totalTaxable), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (sections.isEmpty()) {
                item {
                    Text(
                        "Tap Refresh Data to prepare this period's figures from posted vouchers.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionButton(text = "Refresh Data", style = ActionButtonStyle.SECONDARY, onClick = onRefresh, modifier = Modifier.weight(1f))
                    ActionButton(text = "Next", onClick = onNext, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Screen 10 - Authenticate. Real fields, real per-company storage
 * ([com.example.accounting.presentation.viewmodel.AccountingViewModel.saveGstProviderUsername] ->
 * [com.example.accounting.core.security.SecureStorage.setCompanySetting]) - never a shared or
 * hardcoded credential (product rule). No password is ever persisted; a real provider integration
 * authenticates per-attempt rather than this app remembering a portal password. Tapping
 * Authenticate always proceeds to Filing Progress - what actually happens there depends entirely
 * on the real [com.example.accounting.domain.taxation.gstreturn.GstOnlineFilingGateway] result,
 * never faked here. */
/** A local, self-generated code for the Authenticate screen's CAPTCHA row - not the real GST
 * Portal's captcha (no portal connection exists to fetch one from). Purely a UI gate matching the
 * reference image; the real submission result is unaffected by it either way. */
private fun generateGstCaptcha(): String {
    val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // no 0/O/1/I - avoids visual ambiguity
    return (1..5).map { alphabet.random() }.joinToString("")
}

@Composable
private fun GstAuthenticateStep(uiState: AccountingUiState, initialUsername: String, onBack: () -> Unit, onAuthenticate: (String) -> Unit) {
    var useOtp by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf(generateGstCaptcha()) }
    var captchaInput by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("Authenticate with GST Portal", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            // Real GSTN fact (not this app's invention, and explicitly kept per user request): a
            // genuine Nil GSTR-1/GSTR-3B/GSTR-4 can be filed by SMS to 14409 from the GSTIN's
            // registered mobile, without portal login at all. LedgerPrime doesn't send that SMS
            // itself (a real telecom/GSTN-side integration this app has no access to).
            if (uiState.selectedGstReturn?.isNilReturn == true) {
                item {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(12.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                            Text(
                                "This is a Nil return. The GST Network lets you file a genuine Nil GSTR-1/3B/4 " +
                                    "by SMS to 14409 from your GSTIN's registered mobile number - LedgerPrime " +
                                    "doesn't send that SMS for you, but it's often faster than portal login.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionButton(text = "Credentials", style = if (!useOtp) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY, onClick = { useOtp = false }, modifier = Modifier.weight(1f))
                    ActionButton(text = "EVC / OTP", style = if (useOtp) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY, onClick = { useOtp = true }, modifier = Modifier.weight(1f))
                }
            }
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormField(value = username, onValueChange = { username = it }, label = if (useOtp) "Registered Mobile / Email" else "GST Portal Username", modifier = Modifier.fillMaxWidth())
                        if (!useOtp) {
                            FormField(value = password, onValueChange = { password = it }, label = "Password", modifier = Modifier.fillMaxWidth())
                            // A CAPTCHA generated by LedgerPrime itself - matching the reference
                            // image's own step - is necessarily a local UI gate, never the real GST
                            // Portal's captcha (there is no portal connection to fetch one from).
                            // It gates nothing false: the real result stays the same honest
                            // "not configured" outcome regardless of whether this is solved.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Text(
                                        captchaCode, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                                IconButton(onClick = { captchaCode = generateGstCaptcha() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh code")
                                }
                            }
                            FormField(value = captchaInput, onValueChange = { captchaInput = it }, label = "Type the text shown", modifier = Modifier.fillMaxWidth())
                        } else {
                            Text("An OTP/EVC request is sent by your configured GST provider once connected.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Text(
                        "These are your own GST Portal credentials, never LedgerPrime's - they're stored only for this company, on this device.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            item {
                val ready = username.isNotBlank() && (useOtp || (password.isNotBlank() && captchaInput.equals(captchaCode, ignoreCase = true)))
                ActionButton(
                    text = "Authenticate", modifier = Modifier.fillMaxWidth(),
                    enabled = ready, onClick = { onAuthenticate(username) }
                )
            }
        }
    }
}

/** Screens 11/12 - Filing Progress and Success are one composable, not two, because "success" is
 * simply what this screen renders once [GstReturn.status] genuinely reaches SUBMITTED/FILED -
 * there is no second, separate success state to fabricate. Preparing/Validating are always shown
 * complete (they already ran, honestly, in Data Summary/Return Preview); Uploading/Submitting
 * reflect real [GstReturnStatus.SUBMITTING]; Generating ARN only ever completes when
 * [GstReturn.acknowledgementNumber] is a real, non-null value from the gateway. On FAILED, this
 * shows the gateway's own real [GstReturn.errorMessage] (today, always
 * `UnconfiguredGstOnlineFilingGateway`'s honest "not configured" message) and a way back to
 * Offline mode - never a retried fake progress bar. */
@Composable
private fun GstFilingProgressStep(uiState: AccountingUiState, onBack: () -> Unit, onUseOfflineInstead: () -> Unit, onBackToDashboard: () -> Unit, onDownloadPdf: () -> Unit) {
    val gstReturn = uiState.selectedGstReturn
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("Filing GSTR-1", onBack)
        if (gstReturn == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text("No return selected.") }
            return
        }
        when (gstReturn.status) {
            GstReturnStatus.SUBMITTED, GstReturnStatus.FILED -> {
                SectionCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp))
                        Text("Return Filed Successfully!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "Your ${gstReturn.returnType} has been filed successfully.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        gstReturn.acknowledgementNumber?.let { TableRow("ARN", value = it) }
                        gstReturn.submittedAt?.let {
                            TableRow("Filed On", value = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date(it)))
                        }
                        TableRow("Return Period", value = gstReturn.periodKey)
                        TableRow("Status", value = "Filed Successfully")
                        ActionButton(text = "Download Acknowledgement", onClick = onDownloadPdf, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        ActionButton(text = "Back to Dashboard", style = ActionButtonStyle.SECONDARY, onClick = onBackToDashboard, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            GstReturnStatus.SUBMITTING -> {
                SectionCard(title = "Please wait while we file your return") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilingStepRow("Preparing Data", done = true)
                        FilingStepRow("Validating with GST Portal", done = true)
                        FilingStepRow("Uploading Data", done = false, inProgress = true)
                        FilingStepRow("Submitting Return", done = false)
                        FilingStepRow("Generating ARN", done = false)
                    }
                }
            }
            GstReturnStatus.FAILED, GstReturnStatus.REJECTED -> {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Text(
                                gstReturn.errorMessage ?: "Filing failed.", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        ActionButton(text = "Use Offline Mode Instead", onClick = onUseOfflineInstead, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            else -> {
                SectionCard(title = "Not yet submitted") {
                    Text("Go back and tap Authenticate to start filing.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun FilingStepRow(label: String, done: Boolean, inProgress: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.secondary else if (inProgress) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
        Text(
            label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp),
            color = if (done || inProgress) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Screen 13 - Return History. The same real [uiState.gstReturns] the old inline "Saved Returns"
 * list already showed, restyled to match the reference's own list layout. */
@Composable
private fun GstReturnHistoryStep(uiState: AccountingUiState, onBack: () -> Unit, onOpen: (GstReturn) -> Unit) {
    var filterType by remember { mutableStateOf<GstReturnType?>(null) }
    val filtered = uiState.gstReturns.filter { filterType == null || it.returnType == filterType }.sortedByDescending { it.createdAt }
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("Return History", onBack)
        Text(
            "View all your filed returns", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            ActionButton(text = "All", style = if (filterType == null) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY, onClick = { filterType = null }, modifier = Modifier.weight(1f))
            GstReturnType.entries.forEach { type ->
                ActionButton(text = type.name, style = if (filterType == type) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY, onClick = { filterType = type }, modifier = Modifier.weight(1f))
            }
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No returns filed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(filtered, key = { it.gstReturnId }) { gr ->
                    val (bg, fg) = gstReturnStatusColors(gr.status)
                    SectionCard(
                        onClick = { onOpen(gr) },
                        title = "${gr.periodKey} - ${gr.returnType}",
                        subtitle = gr.acknowledgementNumber?.let { "ARN: $it" } ?: "${gr.scheme} - ${gr.filingMode}",
                        trailing = { StatusBadge(text = gr.status.name.replace('_', ' '), containerColor = bg, contentColor = fg) }
                    ) {}
                }
            }
        }
    }
}

/** Screen 7 (reference: 8-screen WhatsApp image) - a real acknowledgement, not a fabricated
 * scanned-document image: every field is [GstReturn]/[com.example.accounting.domain.company.Company]
 * data already on record, the same source [GstFilingProgressStep]'s Success branch reads. Share and
 * Download reuse the exact same PDF pipeline as the Preview screen's "PDF Report" card - no second
 * renderer. */
@Composable
private fun GstAcknowledgementStep(
    uiState: AccountingUiState,
    onBack: () -> Unit,
    onSharePdfSummary: () -> Unit,
    onDownloadPdf: () -> Unit,
    onPrintPdf: () -> Unit
) {
    val gstReturn = uiState.selectedGstReturn
    val company = uiState.currentCompany
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader(
            "Acknowledgement", onBack,
            subtitle = gstReturn?.let { "${it.returnType} - ${it.periodKey}" }
        )
        if (gstReturn == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No return selected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .weight(1f, fill = false)
        ) {
            // Per explicit instruction: no "Government of India" letterhead text on this app-local
            // record card - this app never received anything from an actual government portal, so
            // presenting it as if it did would misrepresent the app's own honest "not configured"
            // filing status. The ARN + filed date this app actually holds (what the user typed in
            // after filing themselves at gst.gov.in) are the real, load-bearing facts here.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ARN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            gstReturn.acknowledgementNumber ?: "-",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Filed On", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            gstReturn.submittedAt?.let {
                                java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(it))
                            } ?: "-",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    TableRow("Return Type", value = gstReturn.returnType.name)
                    TableRow("Return Period", value = gstReturn.periodKey)
                    TableRow("GSTIN", value = company?.gstin?.takeIf { it.isNotBlank() } ?: "-")
                    TableRow("Legal Name", value = company?.name?.takeIf { it.isNotBlank() } ?: "-")
                    TableRow("Status", value = gstReturn.status.name.replace('_', ' '))
                }
            }
        }
        Text(
            "Your GSTR", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(text = "Download", style = ActionButtonStyle.SECONDARY, onClick = onDownloadPdf, modifier = Modifier.weight(1f))
            ActionButton(text = "Print", style = ActionButtonStyle.SECONDARY, onClick = onPrintPdf, modifier = Modifier.weight(1f))
            ActionButton(text = "Share", style = ActionButtonStyle.SECONDARY, onClick = onSharePdfSummary, modifier = Modifier.weight(1f))
        }
        ActionButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp))
    }
}

/** Screen 8 (reference: 8-screen WhatsApp image) - the GST Dashboard's own "More" tab. Rows with a
 * real destination in this app navigate there; rows with none (Users & Roles, Bank Accounts,
 * E-Way Bills, E-Invoice - none of these have a built screen or a configured provider anywhere in
 * this codebase today) render disabled with an honest "Not yet available" subtitle instead of a
 * fake tap target. Logout is the one real, already-existing sign-out - Cloud Sync's - never an
 * app-wide account system this app doesn't have. */
@Composable
private fun GstMoreStep(
    uiState: AccountingUiState,
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSupport: () -> Unit,
    onLogoutCloudSync: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("More", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(vertical = 8.dp))
        Text(
            "Manage your account & settings", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            item { SectionCard(onClick = onNavigateToProfile, title = "Profile & Business Details") {} }
            item { SectionCard(onClick = null, title = "Users & Roles", subtitle = "Not yet available") {} }
            item { SectionCard(onClick = null, title = "Bank Accounts", subtitle = "Not yet available") {} }
            item { SectionCard(onClick = null, title = "E-Way Bills", subtitle = "Not yet available") {} }
            item { SectionCard(onClick = null, title = "E-Invoice", subtitle = "Not yet available - no GSP configured") {} }
            item { SectionCard(onClick = onNavigateToSettings, title = "Settings") {} }
            item { SectionCard(onClick = onNavigateToSupport, title = "Help & Support") {} }
            item {
                SectionCard(
                    onClick = if (uiState.isCloudSyncLoggedIn) onLogoutCloudSync else null,
                    title = "Logout",
                    subtitle = if (uiState.isCloudSyncLoggedIn) "Sign out of Cloud Sync" else "Not signed in to Cloud Sync"
                ) {}
            }
        }
    }
}

/** This screen's own GST/tax identity accent - `IndigoTax`/`IndigoContainer`
 * (`ui/theme/Color.kt`, Phase 7J UI) were defined specifically so a GST badge never reads as a
 * second "primary" color next to Royal Purple navigation, but were never actually wired into a
 * screen until this pass. One small badge, reused everywhere this dashboard needs a GST mark. */
@Composable
private fun GstIconBadge() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(IndigoContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Receipt, contentDescription = "GST", tint = IndigoTax, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            ActionButton(
                text = label(option),
                style = if (option == selected) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY,
                onClick = { onSelect(option) }
            )
        }
    }
}

private fun monthLabel(month: Int): String = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)[month - 1]

private val sectionMapMoshi = Moshi.Builder().build()
private val sectionMapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
private val sectionMapAdapter = sectionMapMoshi.adapter<Map<String, Any?>>(sectionMapType)

/** Reads back a section's `resultDataJson` tree - never a second calculation, purely a display
 * parse of already-computed numbers. Phase 8A, Part 1 changed GSTR-1's own section trees from one
 * flat `{count, taxableValuePaise, cgstPaise, sgstPaise, igstPaise, cessPaise}` bucket per section
 * ([com.example.accounting.data.repository.AccountingRepository.bucketTotals], still what GSTR-3B/
 * GSTR-4 use) to a real, nested statutory-table shape per section (parties/invoices/notes/rows, each
 * carrying its own rate-line breakdown - see `Gstr1JsonMapping.kt`) - [sectionTotals] below reads
 * either shape uniformly by summing whatever `*Paise` leaf values it actually finds, rather than
 * assuming one fixed flat structure. */
private fun parseSectionTotals(json: String?): Map<String, Any?>? =
    json?.let { runCatching { sectionMapAdapter.fromJson(it) }.getOrNull() }

private fun Map<String, Any?>.paiseOf(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

/** Recursively sums every occurrence of [key] found anywhere in [node] (a `Map`/`List` tree from
 * Moshi's generic decode) - safe against double-counting because every GSTR-1 section tree only
 * ever places a given `*Paise` key at the LEAF (rate-line) level, never redundantly repeated at a
 * parent level under the same name (an invoice's own derived `invoiceValuePaise`/`noteValuePaise`
 * use deliberately distinct key names for exactly this reason - see `Gstr1JsonMapping.kt`). */
private fun sumDeep(node: Any?, key: String): Long = when (node) {
    // A Map's own matching entry contributes directly; every entry (including that same one, if
    // it's a leaf Number) is also walked recursively - harmless, since sumDeep on a plain Number
    // falls through to the `else -> 0L` branch below rather than double-adding it.
    is Map<*, *> -> ((node[key] as? Number)?.toLong() ?: 0L) + node.values.sumOf { sumDeep(it, key) }
    is List<*> -> node.sumOf { sumDeep(it, key) }
    else -> 0L
}

/** A section's row/line count for display - most GSTR-1 sections already carry a top-level
 * "count" (see `buildGstReturnSections`'s GSTR1 branch); the few that don't (NIL, DOC_ISSUED) fall
 * back to their own "rows" list size. GSTR-3B/GSTR-4's flat bucket shape always has "count". */
private fun Map<String, Any?>.rowCount(): Int = when (val c = this["count"]) {
    is Number -> c.toInt()
    else -> (this["rows"] as? List<*>)?.size ?: 0
}

/**
 * One GSTR section's small widget card (Rule 33 follow-up, Phase 8A Part 1 re-shape) -
 * [section.sectionKey] is a real statutory GST Network table name (B2B/B2CL/B2CS/CDNR/CDNUR/EXP/
 * NIL/HSN/DOC_ISSUED for GSTR-1; the 3.1/Section-4 buckets for GSTR-3B - see
 * `AccountingRepository.buildGstReturnSections`'s own doc comment for the exact mapping and its
 * deliberate limits), never an invented one. Deliberately small/dense - a label, a count, and the
 * taxable+tax totals in one row - not a full report page. [onClick] (Phase 8A, Part 2) opens this
 * section's own drill-in detail screen ([GstSectionDetailScreen]) - the tap target the widget
 * itself never had before this pass.
 */
@Composable
private fun GstSectionWidgetCard(section: GstReturnSection, onClick: () -> Unit) {
    val totals = parseSectionTotals(section.resultDataJson)
    val (bg, fg) = gstSectionStatusColors(section.status)
    SectionCard(
        title = sectionLabel(section.sectionKey),
        subtitle = sectionDescription(section.sectionKey),
        trailing = { StatusBadge(text = section.status.name.replace('_', ' '), containerColor = bg, contentColor = fg) },
        onClick = onClick
    ) {
        if (totals != null) {
            val taxable = sumDeep(totals, "taxableValuePaise")
            val tax = sumDeep(totals, "cgstPaise") + sumDeep(totals, "sgstPaise") + sumDeep(totals, "igstPaise")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${totals.rowCount()} txn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Amount(Money.fromPaise(taxable), style = MaterialTheme.typography.bodySmall)
                Amount(Money.fromPaise(tax), style = MaterialTheme.typography.bodySmall, emphasize = tax > 0)
            }
        }
    }
}

private fun sectionLabel(key: String): String = when (key) {
    "B2B" -> "B2B - Registered"
    "B2CL" -> "B2C - Large Invoices"
    "B2CS" -> "B2C - Small (Summary)"
    "CDNR" -> "Credit/Debit Notes - Registered"
    "CDNUR" -> "Credit/Debit Notes - Unregistered"
    "EXP" -> "Exports"
    "NIL" -> "Nil-Rated / Exempt"
    "HSN" -> "HSN Summary"
    "DOC_ISSUED" -> "Documents Issued"
    // Pre-Phase-8A key names, kept so an already-PREPARED return from before this pass still
    // renders its old sections with a real label instead of the raw key, until it is re-prepared.
    "B2C" -> "B2C - Unregistered"
    "NIL_EXEMPT" -> "Nil-Rated / Exempt"
    "OUTWARD_TAXABLE" -> "3.1 Outward Taxable"
    "OUTWARD_ZERO_RATED" -> "3.1 Zero-Rated"
    "OUTWARD_NIL_EXEMPT" -> "3.1 Nil / Exempt"
    "RCM_LIABILITY" -> "3.1(d) RCM Liability"
    "ITC_FORWARD" -> "4A ITC (Forward Charge)"
    "ITC_RCM" -> "4A ITC (Reverse Charge)"
    "SUMMARY" -> "Return Summary"
    "TURNOVER_SUMMARY" -> "Turnover Summary"
    else -> key
}

/** Row 1's second line (Phase 8A, Part 2) - a one-line plain-English gloss of what real-world
 * records land in this statutory table, next to [sectionLabel]'s own GST Network table name. */
private fun sectionDescription(key: String): String = when (key) {
    "B2B" -> "Invoices to GSTIN-registered recipients"
    "B2CL" -> "Large inter-state invoices to unregistered recipients"
    "B2CS" -> "Small unregistered-recipient invoices, summarized by state and rate"
    "CDNR" -> "Credit/debit notes against registered recipients"
    "CDNUR" -> "Credit/debit notes against unregistered recipients"
    "EXP" -> "Export invoices"
    "NIL" -> "Nil-rated, exempt and non-GST outward supplies"
    "HSN" -> "HSN/SAC-wise summary of outward supplies"
    "DOC_ISSUED" -> "Document series issued this period, including cancellations"
    "B2C" -> "Invoices to unregistered recipients"
    "NIL_EXEMPT" -> "Nil-rated and exempt outward supplies"
    "OUTWARD_TAXABLE" -> "Taxable outward supplies, tax liability"
    "OUTWARD_ZERO_RATED" -> "Zero-rated outward supplies (exports/SEZ)"
    "OUTWARD_NIL_EXEMPT" -> "Nil-rated, exempt and non-GST outward supplies"
    "RCM_LIABILITY" -> "Tax payable under reverse charge"
    "ITC_FORWARD" -> "Input tax credit on forward-charge purchases"
    "ITC_RCM" -> "Input tax credit on reverse-charge purchases"
    "SUMMARY" -> "Aggregate turnover for this period"
    "TURNOVER_SUMMARY" -> "Aggregate turnover for this quarter - composition tax rate depends on business category, not yet computed here"
    else -> "GST return section"
}

/** Maps [GstReturnStatus]/[GstReturnSectionStatus] onto this app's own existing semantic accents
 * (Emerald=success, Crimson=error/failed, Amber=in-progress - the same three "status accents"
 * every other screen already uses, per `ui/theme/Color.kt`'s own doc comment: "unchanged by the
 * Royal Purple swap"). Read through `MaterialTheme.colorScheme.*` (never the raw Color constants)
 * so both light and dark theme stay correct automatically. */
@Composable
private fun gstReturnStatusColors(status: GstReturnStatus): Pair<Color, Color> = when (status) {
    GstReturnStatus.DRAFT ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    GstReturnStatus.VALIDATION_FAILED, GstReturnStatus.FAILED, GstReturnStatus.REJECTED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    GstReturnStatus.READY, GstReturnStatus.FILED ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    GstReturnStatus.SUBMITTING, GstReturnStatus.SUBMITTED, GstReturnStatus.PROCESSING ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
}

@Composable
private fun gstSectionStatusColors(status: GstReturnSectionStatus): Pair<Color, Color> = when (status) {
    GstReturnSectionStatus.PENDING ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    GstReturnSectionStatus.PREPARED ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    GstReturnSectionStatus.VALIDATION_PASSED ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    GstReturnSectionStatus.VALIDATION_FAILED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

/** Phase 8A, Part 2 - the GST Return Detail screen's in-place sub-views (Section Details/
 * Validation/Summary all drill in from here and back out to the same Main view - no new nav
 * route, matching this screen's existing local-`remember`-state pattern used everywhere else in
 * `ReportsCenterScreen.kt`, never a second navigation mechanism). */
private sealed class GstDetailSubView {
    object Main : GstDetailSubView()
    data class SectionDetail(val sectionKey: String) : GstDetailSubView()
    object Validation : GstDetailSubView()
    object ErrorDetails : GstDetailSubView()
    object Summary : GstDetailSubView()
}

/**
 * Declaration strip (Phase 8A, Part 2) - every real GST return form ends with a Name/GSTIN/Place/
 * Date declaration block; this reads it straight off data the company already has on file
 * ([AccountingUiState.individualProfile]/[AccountingUiState.businessProfile]/
 * [AccountingUiState.currentCompany]) rather than asking the user to retype it here. Never
 * editable from this screen - it is a read-only reflection of the Business/Individual Profile
 * (edit those from Settings if any of this is wrong), and Date is always today's real device date
 * (a declaration date, not a stored fact). Falls back sensibly (Individual name -> Business legal
 * name -> Business trading name -> Company name) rather than showing a blank Name for a company
 * that only ever filled in part of its profile.
 */
@Composable
private fun GstDeclarationCard(uiState: AccountingUiState) {
    val company = uiState.currentCompany
    val business = uiState.businessProfile
    val individual = uiState.individualProfile
    val name = individual?.name?.takeIf { it.isNotBlank() }
        ?: business?.legalName?.takeIf { it.isNotBlank() }
        ?: business?.businessName?.takeIf { it.isNotBlank() }
        ?: company?.name ?: "Not set - complete your Business Profile"
    val gstin = company?.gstin?.takeIf { it.isNotBlank() } ?: "Not set"
    val place = business?.city?.takeIf { it.isNotBlank() }
        ?: company?.stateName?.takeIf { it.isNotBlank() } ?: "Not set"
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }

    SectionCard(title = "Declaration") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TableRow("Name", value = name)
            TableRow("GSTIN", value = gstin)
            TableRow("Place", value = place)
            TableRow("Date", value = today)
        }
    }
}

@Composable
private fun GstReturnDetailView(
    uiState: AccountingUiState,
    gstReturn: GstReturn,
    onClearSelection: () -> Unit,
    onPrepare: () -> Unit,
    onValidate: () -> Unit,
    onGenerateJson: () -> Unit,
    onShareArtifact: (String) -> Unit,
    onImportResponseFile: () -> Unit,
    onMarkFiled: (String) -> Unit,
    onSubmitOnline: () -> Unit,
    onExportCsv: () -> Unit,
    onExportGstrJson: () -> Unit,
    onSetNilReturn: (Boolean) -> Unit,
    onExportJson: () -> Unit = {},
    onPreviewPdf: () -> Unit = {},
    onDownloadPdf: () -> Unit = {},
    onPrintPdf: () -> Unit = {},
    onSharePdfSummary: () -> Unit = {},
    onProceedToFile: () -> Unit = {},
    onFixNow: (String) -> Unit = {},
    onMarkProcessingManually: () -> Unit = {}
) {
    var ackNumber by remember(gstReturn.gstReturnId) { mutableStateOf("") }
    var subView by remember(gstReturn.gstReturnId) { mutableStateOf<GstDetailSubView>(GstDetailSubView.Main) }
    val canPrepare = gstReturn.status in setOf(GstReturnStatus.DRAFT, GstReturnStatus.VALIDATION_FAILED)

    when (val sv = subView) {
        is GstDetailSubView.SectionDetail -> {
            val section = uiState.selectedGstReturnSections.firstOrNull { it.sectionKey == sv.sectionKey }
            GstSectionDetailScreen(section, sectionLabel(sv.sectionKey), onBack = { subView = GstDetailSubView.Main })
            return
        }
        GstDetailSubView.Validation -> {
            GstValidationScreen(
                gstReturn, uiState.selectedGstReturnSections, onBack = { subView = GstDetailSubView.Main },
                onViewErrors = { subView = GstDetailSubView.ErrorDetails }, onRevalidate = onValidate
            )
            return
        }
        GstDetailSubView.ErrorDetails -> {
            GstErrorDetailsScreen(
                uiState.selectedGstReturnSections, uiState.vouchers,
                onBack = { subView = GstDetailSubView.Validation }, onFixNow = onFixNow
            )
            return
        }
        GstDetailSubView.Summary -> {
            GstReturnSummaryScreen(uiState.selectedGstReturnSections, onBack = { subView = GstDetailSubView.Main })
            return
        }
        GstDetailSubView.Main -> Unit
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        // Status + Prepare/Validate collapsed into one compact card (was two stacked cards with
        // their own headings) - the two actions sit in a single row.
        item {
            val (statusBg, statusFg) = gstReturnStatusColors(gstReturn.status)
            SectionCard(
                title = "${gstReturn.returnType} - ${gstReturn.periodKey}",
                subtitle = "${gstReturn.scheme} - ${gstReturn.filingMode}",
                trailing = { GstIconBadge() }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusBadge(text = gstReturn.status.name.replace('_', ' '), containerColor = statusBg, contentColor = statusFg)
                    }
                    if (gstReturn.errorMessage != null) TableRow("Message", value = gstReturn.errorMessage)
                    if (gstReturn.acknowledgementNumber != null) TableRow("Acknowledgement No.", value = gstReturn.acknowledgementNumber)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ActionButton(text = "Prepare", onClick = onPrepare, enabled = canPrepare, modifier = Modifier.weight(1f))
                        ActionButton(text = "Validate", style = ActionButtonStyle.SECONDARY, onClick = onValidate, enabled = canPrepare, modifier = Modifier.weight(1f))
                    }
                    // Reference flow, screen 9->10 - only reachable once Validate has genuinely
                    // put this return in READY state; routes to the real Authenticate step, never
                    // straight to a fabricated success.
                    if (gstReturn.status == GstReturnStatus.READY) {
                        ActionButton(text = "Proceed to File", onClick = onProceedToFile, modifier = Modifier.fillMaxWidth())
                    }
                    // Nil Return declaration (Phase 8A, Part 2) - a real GST concept: the taxpayer
                    // explicitly confirms zero outward supplies for the period, rather than a
                    // return silently reading as "empty" with no acknowledgement either way. See
                    // Gstr1Validator's ZERO_OUTWARD_SUPPLIES_NOT_DECLARED_NIL warning, which this
                    // toggle is the only thing that suppresses. Editable any time before FILED.
                    if (gstReturn.status != GstReturnStatus.FILED) {
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("This is a Nil Return", style = MaterialTheme.typography.bodyMedium)
                                Text("No outward supplies for this period", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = gstReturn.isNilReturn, onCheckedChange = onSetNilReturn)
                        }
                    }
                }
            }
        }

        item { GstDeclarationCard(uiState) }

        if (uiState.selectedGstReturnSections.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sections", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(text = "Summary", style = ActionButtonStyle.TEXT, onClick = { subView = GstDetailSubView.Summary })
                        ActionButton(text = "Validation", style = ActionButtonStyle.TEXT, onClick = { subView = GstDetailSubView.Validation })
                    }
                }
            }
            items(uiState.selectedGstReturnSections, key = { it.sectionId }) { section ->
                GstSectionWidgetCard(section, onClick = { subView = GstDetailSubView.SectionDetail(section.sectionKey) })
            }
            // PDF Report (Phase 8A, Part 2) - available regardless of filing mode, unlike the
            // Offline/Online JSON split below (a PDF summary is useful either way). Preview opens
            // the rendered PDF in whatever viewer is installed (ACTION_VIEW); Download shares the
            // same PDF file (this app's existing "download" idiom - see Trial Balance/Balance
            // Sheet's own "Export" button); Print goes through the OS print framework (which shows
            // its own preview); Share sends a plain-text figure summary - four genuinely distinct
            // actions, all reusing patterns already established elsewhere in this app, none
            // reimplemented here.
            item {
                SectionCard(title = "PDF Report", subtitle = "Section-wise summary - records, taxable value, tax") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionButton(text = "Preview", style = ActionButtonStyle.SECONDARY, onClick = onPreviewPdf, modifier = Modifier.weight(1f))
                            ActionButton(text = "Download", style = ActionButtonStyle.SECONDARY, onClick = onDownloadPdf, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionButton(text = "Print", style = ActionButtonStyle.SECONDARY, onClick = onPrintPdf, modifier = Modifier.weight(1f))
                            ActionButton(text = "Share", style = ActionButtonStyle.SECONDARY, onClick = onSharePdfSummary, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (gstReturn.filingMode == GstFilingMode.OFFLINE) {
            item {
                SectionCard(title = "Offline Filing") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionButton(
                                text = "Generate JSON", onClick = onGenerateJson, modifier = Modifier.weight(1f),
                                enabled = gstReturn.status == GstReturnStatus.READY
                            )
                            val artifact = uiState.selectedGstReturnArtifacts.firstOrNull { it.artifactId == gstReturn.latestRequestArtifactId }
                            ActionButton(
                                text = "Share", style = ActionButtonStyle.SECONDARY,
                                onClick = { artifact?.let { onShareArtifact(it.jsonContent) } },
                                modifier = Modifier.weight(1f), enabled = artifact != null
                            )
                        }
                        // Export - JSON/CSV/GST JSON (Phase 8A, Part 2). Plain JSON/CSV use this
                        // project's own readable shape (never fabricated statutory-looking figures
                        // for a return that isn't READY); GST JSON is the real GSTN field-name
                        // schema (Gstr1PortalJsonSerializer) for offline upload at gst.gov.in. All
                        // three read the SAME already-computed Gstr1ReturnData - no recalculation.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionButton(
                                text = "Export JSON", style = ActionButtonStyle.SECONDARY, onClick = onExportJson,
                                modifier = Modifier.weight(1f), enabled = gstReturn.status == GstReturnStatus.READY
                            )
                            ActionButton(
                                text = "Export CSV", style = ActionButtonStyle.SECONDARY, onClick = onExportCsv,
                                modifier = Modifier.weight(1f), enabled = gstReturn.status == GstReturnStatus.READY
                            )
                        }
                        ActionButton(
                            text = "Export GST JSON", style = ActionButtonStyle.SECONDARY, onClick = onExportGstrJson,
                            modifier = Modifier.fillMaxWidth(), enabled = gstReturn.status == GstReturnStatus.READY
                        )
                        ActionButton(
                            text = "Import GST Response JSON", style = ActionButtonStyle.SECONDARY,
                            onClick = onImportResponseFile, modifier = Modifier.fillMaxWidth(),
                            enabled = gstReturn.status in setOf(GstReturnStatus.READY, GstReturnStatus.PROCESSING)
                        )
                        if (gstReturn.status == GstReturnStatus.PROCESSING) {
                            FormField(value = ackNumber, onValueChange = { ackNumber = it }, label = "Acknowledgement Number", modifier = Modifier.fillMaxWidth())
                            ActionButton(text = "Mark as Filed", onClick = { onMarkFiled(ackNumber) }, modifier = Modifier.fillMaxWidth(), enabled = ackNumber.isNotBlank())
                        }
                    }
                }
            }
        } else {
            item {
                SectionCard(title = "Online Filing", subtitle = "No GST Network integration configured") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(text = "Submit Online", onClick = onSubmitOnline, modifier = Modifier.fillMaxWidth(), enabled = gstReturn.status == GstReturnStatus.READY)
                        HorizontalDivider()
                        // Real, common path: many users still file directly at gst.gov.in
                        // themselves rather than through an in-app portal connection - this
                        // records that real filing, it never submits anything on its own.
                        Text(
                            "Already filed this yourself at gst.gov.in?", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (gstReturn.status == GstReturnStatus.READY) {
                            ActionButton(
                                text = "I've Filed This Manually", style = ActionButtonStyle.SECONDARY,
                                onClick = onMarkProcessingManually, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (gstReturn.status == GstReturnStatus.PROCESSING) {
                            FormField(value = ackNumber, onValueChange = { ackNumber = it }, label = "Acknowledgement Number (ARN)", modifier = Modifier.fillMaxWidth())
                            ActionButton(text = "Mark as Filed", onClick = { onMarkFiled(ackNumber) }, modifier = Modifier.fillMaxWidth(), enabled = ackNumber.isNotBlank())
                        }
                    }
                }
            }
        }

        if (uiState.selectedGstReturnArtifacts.isNotEmpty()) {
            item { Text("Artifact History", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
            items(uiState.selectedGstReturnArtifacts, key = { it.artifactId }) { artifact ->
                SectionCard(title = artifact.artifactType.name, subtitle = "Schema ${artifact.schemaVersion}") {}
            }
        }

        item {
            ActionButton(text = "Back to Period Selection", style = ActionButtonStyle.TEXT, onClick = onClearSelection, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Phase 8A, Part 2's own compact back header - [ReportsCenterScreen]'s `BackRow` is file-private
 * there, so this mirrors its exact look (icon + ellipsized title) rather than duplicating a public
 * copy of it for one extra file. */
@Composable
private fun GstDetailBackHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column {
            Text(
                title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ==================== Section Details drill-in ====================

private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> = entries.associate { (it.key as? String ?: it.key.toString()) to it.value }

/** Explodes one invoice/note map to one row per its own `rateLines` entry (Gstr1Models.kt's own
 * doc comment: "GSTR-1's tables are all 'one row per (invoice, rate)'") - [extra] carries fields
 * from an enclosing party wrapper (B2B/CDNR's `recipientGstin`). The invoice/note's OWN
 * `invoiceValuePaise`/`noteValuePaise` (its whole-document total) is kept from [base]; the
 * rate-line's own same-named total is dropped to avoid two different numbers under one column -
 * the rate-wise taxable/CGST/SGST/IGST/Cess IS the statutory content each row exists to show. An
 * invoice/note with zero rate lines (shouldn't happen for a real posted voucher, but never crash
 * on it) still renders once, with blank tax columns, rather than vanishing from the table. */
private fun explodeRateLines(invoice: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()): List<Map<String, Any?>> {
    val rateLines = (invoice["rateLines"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() } ?: emptyList()
    val base = (invoice - "rateLines") + extra
    if (rateLines.isEmpty()) return listOf(base)
    return rateLines.map { rateLine -> base + rateLine.filterKeys { it != "invoiceValuePaise" } }
}

/** Extracts this section's real statutory rows (Phase 8A, Part 2) - reusing
 * [Gstr1ReturnBuilder]/[Gstr1Validator]'s own already-computed JSON, never a second parse-and-
 * recalculate. B2B/B2CL/CDNR/CDNUR go through [explodeRateLines] for full rate-wise columns; EXP/
 * B2CS/NIL/HSN/DOC_ISSUED already carry their figures flat (no nested rate lines in their own
 * [Gstr1JsonMapping] shape) and pass through unchanged. */
private fun flattenSectionRows(sectionKey: String, tree: Map<String, Any?>): List<Map<String, Any?>> {
    fun asList(key: String): List<*> = tree[key] as? List<*> ?: emptyList<Any?>()
    return when (sectionKey) {
        "B2B" -> asList("parties").flatMap { p ->
            val party = (p as? Map<*, *>)?.toStringKeyMap() ?: return@flatMap emptyList()
            val gstin = party["recipientGstin"] as? String ?: ""
            (party["invoices"] as? List<*> ?: emptyList<Any?>()).flatMap { inv ->
                (inv as? Map<*, *>)?.toStringKeyMap()?.let { explodeRateLines(it, mapOf("recipientGstin" to gstin)) } ?: emptyList()
            }
        }
        "CDNR" -> asList("parties").flatMap { p ->
            val party = (p as? Map<*, *>)?.toStringKeyMap() ?: return@flatMap emptyList()
            val gstin = party["recipientGstin"] as? String ?: ""
            (party["notes"] as? List<*> ?: emptyList<Any?>()).flatMap { note ->
                (note as? Map<*, *>)?.toStringKeyMap()?.let { explodeRateLines(it, mapOf("recipientGstin" to gstin)) } ?: emptyList()
            }
        }
        "B2CL" -> asList("invoices").flatMap { (it as? Map<*, *>)?.toStringKeyMap()?.let { m -> explodeRateLines(m) } ?: emptyList() }
        "CDNUR" -> asList("notes").flatMap { (it as? Map<*, *>)?.toStringKeyMap()?.let { m -> explodeRateLines(m) } ?: emptyList() }
        "EXP" -> asList("invoices").mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
        "B2CS", "NIL", "HSN", "DOC_ISSUED" -> asList("rows").mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
        else -> emptyList()
    }
}

private val ROW_PAISE_FIELDS = setOf("taxableValuePaise", "invoiceValuePaise", "noteValuePaise", "totalValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise")
private val ROW_FIELD_LABELS = mapOf(
    "recipientGstin" to "GSTIN", "invoiceNumber" to "Invoice No.", "noteNumber" to "Note No.",
    "invoiceDate" to "Date", "noteDate" to "Date", "posStateCode" to "Place of Supply",
    "hsnSacCode" to "HSN/SAC", "gstRatePercent" to "Rate", "reverseCharge" to "Reverse Charge",
    "natureOfDocument" to "Document", "seriesFrom" to "From", "seriesTo" to "To",
    "totalCount" to "Total Issued", "cancelledCount" to "Cancelled", "netIssued" to "Net Issued",
    "originalInvoiceNumber" to "Original Invoice", "originalInvoiceDate" to "Original Date",
    "bucket" to "Type", "interState" to "Inter-State", "totalQuantity" to "Quantity",
    "noteType" to "Note Type", "amendsFiledPeriod" to "Amends a Filed Period",
    "taxableValuePaise" to "Taxable Value", "invoiceValuePaise" to "Invoice Value",
    "noteValuePaise" to "Note Value", "totalValuePaise" to "Total Value",
    "cgstPaise" to "CGST", "sgstPaise" to "SGST", "igstPaise" to "IGST", "cessPaise" to "Cess"
)

/** Explicit, ordered column list per section (Phase 8A, Part 2) - a fixed set rather than
 * whatever keys happen to be non-null on the first row, so every row in the table lines up under
 * the same header even when one invoice has a null/absent field another doesn't. Matches exactly
 * what [explodeRateLines]/[flattenSectionRows] actually puts in each row map for that section key -
 * see `Gstr1JsonMapping.kt`'s own `toTree()` functions for the source shape. */
private val SECTION_COLUMNS: Map<String, List<String>> = mapOf(
    "B2B" to listOf("recipientGstin", "invoiceNumber", "invoiceDate", "posStateCode", "reverseCharge", "gstRatePercent", "taxableValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise", "invoiceValuePaise"),
    "B2CL" to listOf("invoiceNumber", "invoiceDate", "posStateCode", "gstRatePercent", "taxableValuePaise", "igstPaise", "cessPaise", "invoiceValuePaise"),
    "EXP" to listOf("invoiceNumber", "invoiceDate", "taxableValuePaise"),
    "CDNR" to listOf("recipientGstin", "noteNumber", "noteDate", "noteType", "originalInvoiceNumber", "originalInvoiceDate", "posStateCode", "reverseCharge", "gstRatePercent", "taxableValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise", "noteValuePaise"),
    "CDNUR" to listOf("noteNumber", "noteDate", "noteType", "originalInvoiceNumber", "originalInvoiceDate", "posStateCode", "reverseCharge", "gstRatePercent", "taxableValuePaise", "igstPaise", "cessPaise", "noteValuePaise"),
    "B2CS" to listOf("posStateCode", "gstRatePercent", "taxableValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise"),
    "NIL" to listOf("bucket", "interState", "taxableValuePaise"),
    "HSN" to listOf("hsnSacCode", "gstRatePercent", "totalQuantity", "taxableValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise", "totalValuePaise"),
    "DOC_ISSUED" to listOf("natureOfDocument", "seriesFrom", "seriesTo", "totalCount", "cancelledCount", "netIssued")
)

private fun formatRate(value: Any?): String {
    val d = (value as? Number)?.toDouble() ?: return "-"
    return if (d == d.toLong().toDouble()) "${d.toLong()}%" else "$d%"
}

private fun cellText(row: Map<String, Any?>, key: String): String {
    val value = row[key] ?: return "-"
    return when {
        key in ROW_PAISE_FIELDS -> Money.fromPaise((value as? Number)?.toLong() ?: 0L).formatPlain()
        key == "gstRatePercent" -> formatRate(value)
        key == "totalQuantity" -> (value as? Number)?.let { "%.2f".format(it.toDouble()) } ?: value.toString()
        value is Boolean -> if (value) "Yes" else "No"
        else -> value.toString()
    }
}

private const val TABLE_COLUMN_WIDTH_DP = 118
private val ZOOM_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f)

/** A small +/- row (Phase 8A, Part 2 follow-up) - a real, low-space mobile screen has no room to
 * show a wide statutory table at full column width AND stay readable, so zoom is a genuine
 * control here (unlike the PDF preview, which hands zoom to whatever external viewer opens it -
 * that already has its own pinch-zoom, no need to duplicate it). [zoomIndex] indexes [ZOOM_STEPS]. */
@Composable
private fun ZoomControlRow(zoomIndex: Int, onZoomIndexChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        IconButton(onClick = { if (zoomIndex > 0) onZoomIndexChange(zoomIndex - 1) }, enabled = zoomIndex > 0) {
            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
        }
        Text("${(ZOOM_STEPS[zoomIndex] * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { if (zoomIndex < ZOOM_STEPS.lastIndex) onZoomIndexChange(zoomIndex + 1) }, enabled = zoomIndex < ZOOM_STEPS.lastIndex) {
            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
        }
    }
}

/** Section Details drill-in (Phase 8A, Part 2) - a real horizontal-scroll table (header + body
 * share one [ScrollState] so they never drift apart), one row per real invoice/note/HSN-rate/
 * document-series line, reading the EXACT same `resultDataJson` the section widget card already
 * parses for its totals - never a second GST calculation, purely a deeper display of the same
 * data at full statutory rate-wise granularity. */
@Composable
private fun GstSectionDetailScreen(section: GstReturnSection?, title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader(title, onBack)
        val tree = parseSectionTotals(section?.resultDataJson)
        val columns = SECTION_COLUMNS[section?.sectionKey] ?: emptyList()
        val rows = if (section != null && tree != null) flattenSectionRows(section.sectionKey, tree) else emptyList()
        if (rows.isEmpty() || columns.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No records in this section for the prepared period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            var zoomIndex by remember { mutableStateOf(1) }
            val zoom = ZOOM_STEPS[zoomIndex]
            ZoomControlRow(zoomIndex) { zoomIndex = it }
            val colWidth = (TABLE_COLUMN_WIDTH_DP * zoom).dp
            val totalWidth = colWidth * columns.size
            val headerStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.labelSmall.fontSize * zoom)
            val cellStyle = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize * zoom)
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                Row(
                    modifier = Modifier.width(totalWidth).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp)
                ) {
                    columns.forEach { key ->
                        Text(
                            ROW_FIELD_LABELS[key] ?: key, modifier = Modifier.width(colWidth).padding(horizontal = 6.dp),
                            style = headerStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.width(totalWidth).fillMaxHeight(), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(rows.size) { index ->
                        val row = rows[index]
                        Row(modifier = Modifier.width(totalWidth).padding(vertical = 8.dp)) {
                            columns.forEach { key ->
                                Text(
                                    cellText(row, key), modifier = Modifier.width(colWidth).padding(horizontal = 6.dp),
                                    style = cellStyle, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// ==================== Validation screen ====================

/** One parsed validation issue - reads the structured `"issues"` list
 * [AccountingRepository.validateGstReturn] now writes (code/message/severity/voucherId, straight
 * from [Gstr1Validator]'s own [Gstr1ValidationIssue] - never re-derived or guessed here) alongside
 * the older flattened `"errors"`/`"warnings"` string lists it always wrote. Falls back to parsing
 * those flattened `"[CODE] message"` strings (no real `voucherId`) only for a return that was
 * validated before `"issues"` existed - re-running Validate replaces it with the structured form. */
private data class GstValidationIssueUi(val severity: String, val code: String, val message: String, val voucherId: String?)

private fun parseValidationIssues(sections: List<GstReturnSection>): List<GstValidationIssueUi> {
    val errorsJson = sections.firstOrNull { it.errorsJson != null }?.errorsJson ?: return emptyList()
    val parsed = runCatching { sectionMapAdapter.fromJson(errorsJson) }.getOrNull() ?: return emptyList()
    (parsed["issues"] as? List<*>)?.let { issuesList ->
        return issuesList.mapNotNull { raw ->
            val m = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            GstValidationIssueUi(
                severity = m["severity"] as? String ?: "ERROR",
                code = m["code"] as? String ?: "",
                message = m["message"] as? String ?: "",
                voucherId = m["voucherId"] as? String
            )
        }
    }
    fun legacy(text: String, severity: String): GstValidationIssueUi {
        val code = if (text.startsWith("[")) text.substringAfter("[").substringBefore("]") else ""
        val message = if (text.startsWith("[")) text.substringAfter("] ", text) else text
        return GstValidationIssueUi(severity, code, message, null)
    }
    val errors = (parsed["errors"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    val warnings = (parsed["warnings"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    return errors.map { legacy(it, "ERROR") } + warnings.map { legacy(it, "WARNING") }
}

/** One checklist row (reference image: Validation screen) - [codes] is the real, exact set of
 * [Gstr1Validator] error codes this row reports on; a row with zero matching issues shows
 * [passLabel], one WITH matches shows [failLabel] applied to the match count. Every code here is
 * real and already checked by [Gstr1Validator] - "All Invoices are Confirmed" reuses
 * MISSING_INVOICE_NUMBER (the closest real existing concept to "confirmed"), never a fabricated
 * new check with no backing logic. */
private data class GstChecklistItem(val codes: Set<String>, val passLabel: String, val failLabel: (Int) -> String)
private val GST_VALIDATION_CHECKLIST = listOf(
    GstChecklistItem(setOf("INVALID_GSTIN_CHECKSUM", "REGISTERED_PARTY_MISSING_GSTIN"), "GSTIN is Valid") { n -> "$n GSTIN(s) failed validation" },
    GstChecklistItem(setOf("DUPLICATE_INVOICE_NUMBER"), "Invoice Numbers are Unique") { n -> "$n duplicate invoice number(s) found" },
    GstChecklistItem(setOf("MISSING_HSN_CODE"), "All Invoices have HSN Codes") { n -> "$n Invoice${if (n == 1) "" else "s"} have Missing HSN" },
    GstChecklistItem(setOf("TAX_RECOMPUTATION_MISMATCH"), "GST Rates are Correct") { n -> "$n Invoice${if (n == 1) "" else "s"} ${if (n == 1) "has" else "have"} Wrong GST Rate" },
    GstChecklistItem(setOf("MISSING_INVOICE_NUMBER"), "All Invoices are Confirmed") { n -> "$n invoice(s) missing an invoice number" },
    GstChecklistItem(setOf("NEGATIVE_SALE_VALUE"), "No Negative Values Found") { n -> "$n negative Sales value(s) found" }
)

/** Screen: Validation (reference image) - a named checklist, not a flat error-message dump. Each
 * row's real error code(s) are defined in [GST_VALIDATION_CHECKLIST]; a code Validate returns that
 * isn't covered by any row (Place-of-Supply, scheme mismatch, party-GSTIN consistency, zero-outward
 * Nil warning, note-missing-original-invoice) still fully blocks/warns filing exactly as before -
 * it's just not one of this checklist's own named rows, matching the reference's own fixed list. */
@Composable
private fun GstValidationScreen(gstReturn: GstReturn, sections: List<GstReturnSection>, onBack: () -> Unit, onViewErrors: () -> Unit, onRevalidate: () -> Unit) {
    val issues = parseValidationIssues(sections)
    Column(modifier = Modifier.fillMaxWidth()) {
        GstDetailBackHeader("Validation", onBack)
        if (gstReturn.status !in setOf(GstReturnStatus.READY, GstReturnStatus.VALIDATION_FAILED, GstReturnStatus.SUBMITTING, GstReturnStatus.SUBMITTED, GstReturnStatus.PROCESSING, GstReturnStatus.FILED)) {
            Text("Run Validate first to see results here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }
        Text("We are checking your data", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        val hasErrors = issues.any { it.severity == "ERROR" }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(GST_VALIDATION_CHECKLIST) { item ->
                val count = issues.count { it.code in item.codes }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (count == 0) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (count == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        if (count == 0) item.passLabel else item.failLabel(count),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    ActionButton(text = "View Errors", style = ActionButtonStyle.SECONDARY, enabled = hasErrors, onClick = onViewErrors, modifier = Modifier.weight(1f))
                    ActionButton(text = "Revalidate", onClick = onRevalidate, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Screen: Error Details (reference image) - one card per real ERROR-severity issue (warnings
 * never block filing, so they don't appear here - see [GstValidationScreen]'s own checklist for
 * those). "Fixed" always lists nothing: this app has no persisted history of which validation run
 * an issue first appeared in, so claiming something was "fixed" without that history would be a
 * fabricated status, not a real one - an honestly-empty tab is correct here, not a bug. */
@Composable
private fun GstErrorDetailsScreen(sections: List<GstReturnSection>, vouchers: List<Voucher>, onBack: () -> Unit, onFixNow: (String) -> Unit) {
    val errors = parseValidationIssues(sections).filter { it.severity == "ERROR" }
    var showFixedTab by remember { mutableStateOf(false) }
    val vouchersById = remember(vouchers) { vouchers.associateBy { it.voucherId } }
    Column(modifier = Modifier.fillMaxSize()) {
        GstDetailBackHeader("Error Details", onBack)
        Text("${errors.size} Errors Found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            ActionButton(text = "All Errors (${errors.size})", style = if (!showFixedTab) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY, onClick = { showFixedTab = false }, modifier = Modifier.weight(1f))
            ActionButton(text = "Fixed (0)", style = if (showFixedTab) ActionButtonStyle.PRIMARY else ActionButtonStyle.SECONDARY, onClick = { showFixedTab = true }, modifier = Modifier.weight(1f))
        }
        if (showFixedTab) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No errors have been fixed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(errors.size) { i ->
                    val issue = errors[i]
                    val voucherNumber = issue.voucherId?.let { vouchersById[it]?.voucherNumber }
                    SectionCard {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(voucherNumber?.let { "Invoice $it" } ?: "Return-level issue", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(gstErrorCodeLabel(issue.code), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (issue.voucherId != null) {
                                ActionButton(text = "Fix Now", style = ActionButtonStyle.SECONDARY, onClick = { onFixNow(issue.voucherId) })
                            }
                        }
                    }
                }
            }
        }
        ActionButton(text = "Back to Validation", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

/** A short, human label per real error code (reference image: "Missing HSN Code" / "Wrong GST
 * Rate") - display-only, never changes which codes [Gstr1Validator] actually checks. */
private fun gstErrorCodeLabel(code: String): String = when (code) {
    "MISSING_HSN_CODE" -> "Missing HSN Code"
    "TAX_RECOMPUTATION_MISMATCH" -> "Wrong GST Rate"
    "INVALID_GSTIN_CHECKSUM" -> "Invalid GSTIN"
    "REGISTERED_PARTY_MISSING_GSTIN" -> "Missing GSTIN"
    "DUPLICATE_INVOICE_NUMBER" -> "Duplicate Invoice Number"
    "MISSING_INVOICE_NUMBER" -> "Missing Invoice Number"
    "NEGATIVE_SALE_VALUE" -> "Negative Sale Value"
    "INVALID_PLACE_OF_SUPPLY" -> "Invalid Place of Supply"
    "SCHEME_DOES_NOT_FILE_GSTR1" -> "Wrong Scheme for GSTR-1"
    else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

// ==================== Summary screen ====================

/** Dedicated Summary screen (Phase 8A, Part 2) - one row per section: name, record count, taxable
 * value, tax. Reuses [sumDeep]/[rowCount] (the same shape-agnostic readers the section widget cards
 * already use) - never a second aggregation. */
@Composable
private fun GstReturnSummaryScreen(sections: List<GstReturnSection>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GstDetailBackHeader("Return Summary", onBack)
        if (sections.isEmpty()) {
            Text("Nothing to summarize yet - run Prepare first.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Section", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.4f))
            Text("Count", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.6f))
            Text("Amount", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        }
        var totalTaxable = 0L
        var totalTax = 0L
        val rows = sections.map { section ->
            val tree = parseSectionTotals(section.resultDataJson)
            val taxable = tree?.let { sumDeep(it, "taxableValuePaise") } ?: 0L
            val tax = tree?.let { sumDeep(it, "cgstPaise") + sumDeep(it, "sgstPaise") + sumDeep(it, "igstPaise") } ?: 0L
            totalTaxable += taxable
            totalTax += tax
            Triple(section, tree?.rowCount() ?: 0, taxable to tax)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            items(rows.size) { i ->
                val (section, count, amounts) = rows[i]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(sectionLabel(section.sectionKey), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1.4f))
                    Text(count.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Money.fromPaise(amounts.first).formatPlain(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        if (amounts.second > 0) Text("+${Money.fromPaise(amounts.second).formatPlain()} tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.4f + 0.6f))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Money.fromPaise(totalTaxable).formatPlain(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                        if (totalTax > 0) Text("+${Money.fromPaise(totalTax).formatPlain()} tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ==================== PDF export (Phase 8A, Part 2) ====================

/**
 * The same section/count/taxable/tax rows [GstReturnSummaryScreen] already shows on-screen,
 * reshaped into [TabularReportData] for [com.example.accounting.data.rendering.TabularPdfRenderer]
 * - mirrors `domain/reports/ReportPdfMapping.kt`'s exact pattern (pure display formatting of an
 * already-computed model, never a second calculation) for the one report type that pattern didn't
 * originally cover. Public so [com.example.accounting.presentation.viewmodel.AccountingViewModel]
 * can call it without duplicating [sumDeep]/[rowCount]/[parseSectionTotals]'s JSON-tree reading,
 * which stays here rather than moving to `domain/` (parsing `resultDataJson` is presentation-layer
 * display logic in this codebase, same as every other function in this file).
 */
fun buildGstr1SummaryPdfData(gstReturn: GstReturn, sections: List<GstReturnSection>, companyName: String): TabularReportData {
    var totalTaxable = 0L
    var totalTax = 0L
    val rows = sections.map { section ->
        val tree = parseSectionTotals(section.resultDataJson)
        val taxable = tree?.let { sumDeep(it, "taxableValuePaise") } ?: 0L
        val tax = tree?.let { sumDeep(it, "cgstPaise") + sumDeep(it, "sgstPaise") + sumDeep(it, "igstPaise") } ?: 0L
        totalTaxable += taxable
        totalTax += tax
        listOf(sectionLabel(section.sectionKey), (tree?.rowCount() ?: 0).toString(), Money.fromPaise(taxable).formatPlain(), Money.fromPaise(tax).formatPlain())
    }
    return TabularReportData(
        title = "${gstReturn.returnType} - ${gstReturn.periodKey}",
        subtitle = "$companyName - ${gstReturn.scheme} - ${gstReturn.filingMode}${if (gstReturn.isNilReturn) " - NIL RETURN" else ""}",
        columnHeaders = listOf("Section", "Records", "Taxable Value", "Tax"),
        rows = rows,
        totalsRow = listOf("Total", sections.sumOf { (parseSectionTotals(it.resultDataJson)?.rowCount() ?: 0) }.toString(), Money.fromPaise(totalTaxable).formatPlain(), Money.fromPaise(totalTax).formatPlain())
    )
}

// ==================== Automation status + reminder controls (Phase 8A, Part 2) ====================

/**
 * Real automation activity for this company's GSTR-1 (Phase 8A, Part 1's `GstReturnAutomationChecker`
 * draft-prep/validation/filing-due-date checks) - reads [AutomationNotification]s the checker
 * already emitted (`AutomationNotificationCenter`, category `GST_RETURN_FILING`), never a
 * fabricated status. Empty list renders one honest "no activity yet" line rather than inventing
 * placeholder history. The Switch is the one real control this screen exposes - it flips
 * [com.example.accounting.domain.company.Company.gstr1ReminderEnabled], which
 * `checkFilingReminder` reads before emitting a due-soon/overdue notification; it never touches
 * draft preparation or validation, which stay on regardless (this is a REMINDER control, not an
 * automation on/off switch).
 */
@Composable
private fun GstAutomationStatusCard(
    notifications: List<AutomationNotification>,
    reminderEnabled: Boolean,
    onUpdateReminderEnabled: (Boolean) -> Unit
) {
    SectionCard(title = "Automation") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Filing-due reminders", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Prepares a draft after each period ends and validates it automatically. " +
                            "Reminders start 5 days before the statutory due date - filing itself always " +
                            "needs your explicit action.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = reminderEnabled, onCheckedChange = onUpdateReminderEnabled)
            }
            HorizontalDivider()
            if (notifications.isEmpty()) {
                Text("No automation activity yet for this company.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                notifications.take(5).forEach { notif ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Notifications, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp)
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(notif.title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(notif.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
