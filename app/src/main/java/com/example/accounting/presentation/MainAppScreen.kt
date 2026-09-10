package com.example.accounting.presentation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.dataimport.ImportFileFormat
import com.example.accounting.domain.ocr.OcrDocumentType
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyRole
import androidx.activity.compose.BackHandler
import com.example.accounting.presentation.navigation.AppRoute
import com.example.accounting.presentation.components.AppDivider
import com.example.accounting.presentation.components.AppTopBar
import com.example.accounting.presentation.components.CreateBankUpiProfileDialog
import com.example.accounting.presentation.components.CreateCompanyDialog
import com.example.accounting.presentation.components.CreateGroupDialog
import com.example.accounting.presentation.components.CreateLedgerDialog
import com.example.accounting.presentation.components.CreatePartyDialog
import com.example.accounting.presentation.components.CreateStockItemDialog
import com.example.accounting.presentation.components.CreateVoucherDialog
import com.example.accounting.presentation.components.OcrReviewDialog
import com.example.accounting.presentation.components.VoucherDetailDialog
import com.example.accounting.presentation.features.dashboard.DashboardScreen
import com.example.accounting.presentation.features.datatools.DataToolsScreen
import com.example.accounting.presentation.features.daybook.DayBookScreen
import com.example.accounting.presentation.features.invoice.InvoicePreviewScreen
import com.example.accounting.presentation.features.invoice.QuickInvoiceEntryScreen
import com.example.accounting.presentation.features.ledgers.ChartOfAccountsScreen
import com.example.accounting.presentation.features.legal.AboutScreen
import com.example.accounting.presentation.features.legal.PrivacyPolicyScreen
import com.example.accounting.presentation.features.legal.SupportScreen
import com.example.accounting.presentation.features.legal.TermsAndConditionsScreen
import com.example.accounting.presentation.features.money.MoneyTabContent
import com.example.accounting.presentation.features.party.PartiesScreen
import com.example.accounting.presentation.features.profile.ProfileScreen
import com.example.accounting.presentation.features.profile.ProfileWizardScreen
import com.example.accounting.presentation.features.purchases.PurchasesScreen
import com.example.accounting.presentation.features.reports.GstReturnDashboardView
import com.example.accounting.presentation.features.reports.ReportsCenterScreen
import com.example.accounting.presentation.features.sales.SalesScreen
import com.example.accounting.presentation.features.search.SearchScreen
import com.example.accounting.presentation.features.settings.SettingsAndSyncScreen
import com.example.accounting.presentation.features.subscription.SubscriptionScreen
import com.example.accounting.presentation.viewmodel.AccountingViewModel
import com.example.accounting.presentation.viewmodel.NavigationTab
import com.example.accounting.presentation.viewmodel.isInventoryEnabled
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

data class NavItem(
    val tab: NavigationTab,
    val route: AppRoute,
    val label: String,
    val icon: ImageVector,
    val tag: String
)

/** Bug #3 fix - which open form a pending QR/Barcode scan result should be routed into once the
 * photo picker returns. Purely a presentation-layer routing concern (never touches domain/frozen
 * engines): [BarcodeScanTarget.ItemLookup] keeps the pre-existing standalone Items-tab behavior (a
 * result dialog); [BarcodeScanTarget.PurchaseVoucher] instead feeds the scan result into the
 * already-open form as a prefill, never a second scan/decode mechanism. */
private enum class BarcodeScanTarget { ItemLookup, PurchaseVoucher }

/** Audit fix (Company/Profile/Ledger Setup) - reuse the Company's own already-entered
 * name/GSTIN/PAN/address/phone/email as the Business Profile screen's starting point instead of
 * every field starting blank, when no real [com.example.accounting.domain.rendering.BusinessProfile]
 * has been saved yet for this company. Purely a seed for the initial on-screen values - never
 * persisted as-is; both [com.example.accounting.presentation.features.profile.ProfileScreen] and
 * [com.example.accounting.presentation.features.profile.ProfileWizardScreen]'s own `onSave` still
 * independently read/write the real `uiState.businessProfile`. */
private fun businessProfileSeed(uiState: com.example.accounting.presentation.viewmodel.AccountingUiState): com.example.accounting.domain.rendering.BusinessProfile? =
    uiState.businessProfile ?: uiState.currentCompany?.let { comp ->
        com.example.accounting.domain.rendering.BusinessProfile(
            businessProfileId = "",
            companyId = comp.companyId,
            businessName = comp.tradeName.ifBlank { comp.name },
            legalName = comp.name,
            address = comp.address,
            phone = comp.phone,
            email = comp.email,
            gstin = comp.gstin,
            pan = comp.pan
        )
    }

/**
 * Phase 7J UI: 5-item bottom nav (Home/Sales/Purchases/Money/Reports) per the UX spec's Section
 * 14. Every other area (Party, Items, Cash/Bank, Outstanding, Profile, Import/OCR, Subscription,
 * Search) is reached through one of these 5 or a top-bar entry point - content dispatch below
 * switches on `uiState.currentRoute`, not just `selectedTab`, since several routes (Profile,
 * Subscription, DataTools, Search) never own a bottom-nav tab of their own.
 */
@Composable
fun MainAppScreen(
    viewModel: AccountingViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val canGoBack by viewModel.router.canGoBack.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = canGoBack) {
        viewModel.navigateBack()
    }

    // Dialog state controllers
    var isCreateVoucherOpen by remember { mutableStateOf(false) }
    var createVoucherType by remember { mutableStateOf(VoucherType.PAYMENT) }
    var isCreateVoucherTypeLocked by remember { mutableStateOf(false) }

    // Architecture correction (Voucher Correct workflow) - once "Correct Voucher" successfully
    // cancels the original, AccountingUiState.pendingVoucherCorrection carries it; this reopens
    // New Voucher locked to the same type and prefilled from it.
    LaunchedEffect(uiState.pendingVoucherCorrection) {
        uiState.pendingVoucherCorrection?.let { original ->
            createVoucherType = original.voucherType
            isCreateVoucherTypeLocked = true
            isCreateVoucherOpen = true
        }
    }

    var isCreateLedgerOpen by remember { mutableStateOf(false) }
    var quickAddLedgerGroupId by remember { mutableStateOf<String?>(null) }
    // 13-point correctness pass, item 8 (Editable Ledgers) - non-null switches CreateLedgerDialog
    // into edit mode for this ledger; cleared on dismiss so the next "+ New Ledger" open is fresh.
    var editingLedger by remember { mutableStateOf<com.example.accounting.domain.accounting.Ledger?>(null) }
    // Architecture correction (real Group hierarchy) - opens CreateGroupDialog.
    var isCreateGroupOpen by remember { mutableStateOf(false) }
    var isCreateStockItemOpen by remember { mutableStateOf(false) }
    var isCreateCompanyOpen by remember { mutableStateOf(false) }
    var selectedVoucherDetail by remember { mutableStateOf<Voucher?>(null) }
    var showInvoicePreview by remember { mutableStateOf(false) }
    var createPartyRole by remember { mutableStateOf<PartyRole?>(null) }
    var isCreateBankUpiOpen by remember { mutableStateOf(false) }
    var pendingImportFormat by remember { mutableStateOf(ImportFileFormat.CSV) }
    // Bug #3 fix - which open form a pending barcode scan should be applied to; defaults to the
    // pre-existing standalone Items-tab behavior.
    var barcodeScanTarget by remember { mutableStateOf(BarcodeScanTarget.ItemLookup) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Storage Access Framework picker for CSV/JSON import - zero new manifest entries needed.
    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "import_${System.currentTimeMillis()}")
                if (file != null) viewModel.importFromFile(file, pendingImportFormat)
            }
        }
    }

    // Rule 33 - Storage Access Framework picker for an imported GST response JSON file, the exact
    // same mechanism (and zero new manifest entries) the CSV/JSON data-import picker above already
    // uses - just reads the file as text instead of handing it to the CSV/JSON import adapter.
    val gstResponseImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "gst_response_${System.currentTimeMillis()}.json")
                val text = file?.let { runCatching { it.readText() }.getOrNull() }
                if (text != null) viewModel.importSelectedGstReturnOfflineResponse(text)
            }
        }
    }

    // Android Photo Picker for the Document/Image Scan feature - no runtime permission required.
    // pendingScanDocumentType carries the type the user picked in one of the contextual
    // ScanTypePickerDialogs (Sales/Purchases/Profile/Money - docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md)
    // through to the moment a photo actually comes back, since the launcher's own callback can't
    // take extra parameters.
    var pendingScanDocumentType by remember { mutableStateOf(OcrDocumentType.UNKNOWN) }
    val documentPhotoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "scan_${System.currentTimeMillis()}.jpg")
                if (file != null) viewModel.scanDocument(file, pendingScanDocumentType)
            }
        }
    }
    // Shared trigger every contextual scan entry point (Sales/Purchases/Profile/Money -
    // docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md, docs/CORRECTIONS_LOG.md) calls with its own already-
    // known [OcrDocumentType] - avoids repeating the same two-line launch at each call site.
    val launchDocumentScan: (OcrDocumentType) -> Unit = { type ->
        pendingScanDocumentType = type
        documentPhotoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Phase 7J UI fix: Android Photo Picker for barcode/QR scans - `scanBarcodeImage` already
    // existed in the ViewModel with no UI entry point before this fix. No runtime permission
    // required (same launcher pattern as the receipt-photo picker above).
    val barcodePhotoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "barcode_${System.currentTimeMillis()}.jpg")
                if (file != null) viewModel.scanBarcodeImage(file)
            }
        }
    }

    // Contacts + Favorites correction (docs/CORRECTIONS_LOG.md) - "Import from Contacts" on Add
    // Customer/Supplier. Unlike the Photo Picker above, there is NO permission-free equivalent for
    // reading a picked contact's phone number (docs/52_MANAGEMENT_ARCHITECTURE.md's own prior
    // research) - READ_CONTACTS is a real, user-accepted Play Console Restricted Permission here.
    // pendingContactImport carries the resolved (name, phone) into CreatePartyDialog the same way
    // pendingScanDocumentType carries the OCR hint above - the launcher callback can't return
    // values directly into a Composable's own state.
    var pendingContactImport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showContactsPermissionRationale by remember { mutableStateOf(false) }
    val contactPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            val resolved = resolveContactNameAndPhone(context, uri)
            if (resolved != null) {
                pendingContactImport = resolved
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar("Could not read that contact's details") }
            }
        }
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) contactPickerLauncher.launch(null)
        else coroutineScope.launch { snackbarHostState.showSnackbar("Contacts permission is needed to import a contact") }
    }
    val onRequestContactImport: () -> Unit = {
        val alreadyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) contactPickerLauncher.launch(null) else showContactsPermissionRationale = true
    }

    // Profile Wizard branding (Part 2) - same Photo Picker + cache-file pattern as the receipt/
    // barcode pickers above, just a different DocumentAssetType at the end.
    val logoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "logo_${System.currentTimeMillis()}.jpg")
                if (file != null) viewModel.uploadBusinessBrandingAsset(file, com.example.accounting.domain.rendering.DocumentAssetType.LOGO)
            }
        }
    }
    val signaturePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "signature_${System.currentTimeMillis()}.jpg")
                if (file != null) viewModel.uploadBusinessBrandingAsset(file, com.example.accounting.domain.rendering.DocumentAssetType.SIGNATURE)
            }
        }
    }

    // Phase 7J-B.2 (Slice 2) - voucher document attachments. OpenDocument (not PickVisualMedia)
    // because Step 3 requires supporting images AND PDF from one picker; the real, resolved
    // file/MIME copy itself happens in AttachmentStorageAdapter (durable filesDir storage, not
    // cacheDir) via AccountingViewModel.attachDocumentToVoucher - this launcher only supplies the
    // picked Uri and the voucher it's being attached to.
    var voucherIdPendingAttachment by remember { mutableStateOf<String?>(null) }
    val attachmentPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val voucherId = voucherIdPendingAttachment
        voucherIdPendingAttachment = null
        if (uri != null && voucherId != null) {
            val fileName = queryDisplayName(context, uri)
            viewModel.attachDocumentToVoucher(voucherId, uri, fileName)
        }
    }

    val navItems = listOf(
        NavItem(NavigationTab.HOME, AppRoute.Dashboard, "Home", Icons.Default.Home, "nav_home"),
        NavItem(NavigationTab.SALES, AppRoute.Sales, "Sales", Icons.Default.Storefront, "nav_sales"),
        NavItem(NavigationTab.PURCHASES, AppRoute.Purchases, "Purchase", Icons.Default.ShoppingCart, "nav_purchases"),
        NavItem(NavigationTab.MONEY, AppRoute.Money, "Money", Icons.Default.AccountBalanceWallet, "nav_money"),
        NavItem(NavigationTab.REPORTS, AppRoute.Reports, "Reports", Icons.Default.Assessment, "nav_reports")
    )

    // Product decision - single-business app: a business-less user must still see the Dashboard
    // and every bottom-nav function, not a forced "set up your business first" gate. AppTopBar
    // renders "My Business" / "GSTIN: --" as a static header (no switcher - there is only ever
    // one business); "Set Up My Business" lives in Profile > Company & Sync, reached via the
    // profile icon, the same isCreateCompanyOpen/CreateCompanyDialog every edit already uses.
    // uiState's lists (vouchers/parties/ledgers/...) simply stay empty since nothing was ever
    // loaded for a null company, and every single AccountingViewModel action already guards on
    // `_uiState.value.currentCompany ?: return` - none of them can crash on a null company, they
    // just no-op or show "Select a company first."
    // Play Store readiness pass - Legal + Support drawer, additive over the existing bottom-nav/
    // rail navigation (never replaces it, per the chosen "Legal + support only" scope).
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            com.example.accounting.presentation.components.AppDrawerContent(
                currentCompany = uiState.currentCompany,
                businessProfile = uiState.businessProfile,
                currentRoute = uiState.currentRoute,
                onNavigate = { route ->
                    viewModel.navigateTo(route)
                    coroutineScope.launch { drawerState.close() }
                },
                onOpenPlayStore = {
                    val packageName = context.packageName
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName")))
                    } catch (e: android.content.ActivityNotFoundException) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                    }
                },
                isCloudSyncLoggedIn = uiState.isCloudSyncLoggedIn,
                onLogout = { viewModel.logoutCloudSync() }
            )
        }
    ) {
        Scaffold(
            topBar = {
                AppTopBar(
                    currentFinancialYear = uiState.currentFinancialYear,
                    financialYears = uiState.financialYears,
                    onFinancialYearSelected = { viewModel.switchFinancialYear(it) },
                    onAddPreviousFinancialYear = { viewModel.addPreviousFinancialYear() },
                    onSearchClicked = { viewModel.navigateTo(AppRoute.Search()) },
                    onProfileClicked = { viewModel.navigateTo(AppRoute.Profile) },
                    onMenuClicked = { coroutineScope.launch { drawerState.open() } },
                    canGoBack = canGoBack,
                    onBack = { viewModel.navigateBack() },
                    showSearchBar = uiState.currentRoute !is AppRoute.Search
                )
            },
            bottomBar = {
                // Product decision: this is a mobile app, and it uses the SAME bottom navigation
                // bar on every device/screen size - phone or tablet - never a NavigationRail. A
                // width-based rail switch was tried and explicitly rejected by the user (it read
                // as "the bottom bar moved to the side" on a tablet, not as good tablet support).
                Column {
                    // NavigationBar reserves its own bottom system-nav-bar inset (correct,
                    // needed on gesture-nav devices) - on a 3-button-nav device that reserved
                    // strip has no visual boundary from the tappable row above it, so the whole
                    // bottom area reads as one abnormally tall block ("bottom bar too high").
                    // This divider marks where the actual nav bar ends.
                    AppDivider()
                    if (uiState.currentRoute is AppRoute.GstDashboard) {
                        // The GST Dashboard's own bottom nav (matches the reference image),
                        // swapped in for the main app's Home/Sales/Purchase/Money/Reports bar
                        // only while this route is active - see AppRoute.GstDashboard's KDoc.
                        NavigationBar {
                            NavigationBarItem(
                                selected = uiState.gstActiveBottomTab == "Dashboard",
                                onClick = { viewModel.requestGstBottomNav("Dashboard") },
                                icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                                label = { Text("Dashboard", maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) }
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = { viewModel.viewReport("Sales Register") },
                                icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "Invoices") },
                                label = { Text("Invoices", maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) }
                            )
                            NavigationBarItem(
                                selected = uiState.gstActiveBottomTab == "Returns",
                                onClick = { viewModel.requestGstBottomNav("Returns") },
                                icon = { Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = "Returns") },
                                label = { Text("Returns", maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) }
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = { viewModel.navigateTo(AppRoute.Reports) },
                                icon = { Icon(Icons.Default.Assessment, contentDescription = "Reports") },
                                label = { Text("Reports", maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) }
                            )
                            NavigationBarItem(
                                selected = uiState.gstActiveBottomTab == "More",
                                onClick = { viewModel.requestGstBottomNav("More") },
                                icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "More") },
                                label = { Text("More", maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) }
                            )
                        }
                    } else {
                        NavigationBar {
                            navItems.forEach { item ->
                                NavigationBarItem(
                                    selected = uiState.selectedTab == item.tab,
                                    onClick = { viewModel.selectTab(item.tab) },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) },
                                    modifier = Modifier.testTag(item.tag)
                                )
                            }
                        }
                    }
                }
            },
            // The generic center-bottom-bar scan FAB was retired entirely
            // (docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md, docs/CORRECTIONS_LOG.md) - every scan type
            // now has a contextual entry point on the screen it belongs to (Sales/Purchases/
            // Profile/Money), so there is nothing left for a generic popup to offer.
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Hoisted above the route `when` (Phase 8A, Part 2) - the exact same callbacks the
                // GST Dashboard needs whether it's reached through Reports Center (legacy path,
                // still reachable via Reports -> GST -> GST Return Dashboard) or its own dedicated
                // AppRoute.GstDashboard (the primary path now - see that route's own KDoc for why
                // a separate top-level route exists at all).
                val gstReturnActions = com.example.accounting.presentation.features.reports.GstReturnDashboardActions(
                    onSelectPeriod = { quarter, month, returnType, periodicity, filingMode ->
                        viewModel.selectGstReturnPeriod(quarter, month, viewModel.uiState.value.currentCompany?.gstScheme
                            ?: com.example.accounting.domain.taxation.gstreturn.GstScheme.REGULAR, returnType, periodicity, filingMode)
                    },
                    onOpenReturn = { viewModel.openGstReturn(it) },
                    onClearSelection = { viewModel.clearSelectedGstReturn() },
                    onPrepare = { viewModel.prepareSelectedGstReturn() },
                    onValidate = { viewModel.validateSelectedGstReturn() },
                    onGenerateJson = { viewModel.generateSelectedGstReturnOfflineJson() },
                    onShareArtifact = { jsonContent ->
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, jsonContent)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share GST return JSON"))
                    },
                    onImportResponseFile = { gstResponseImportLauncher.launch(arrayOf("application/json", "text/*")) },
                    onMarkFiled = { ack -> viewModel.markSelectedGstReturnFiled(ack) },
                    onSubmitOnline = { viewModel.submitSelectedGstReturnOnline() },
                    onUpdateGstEnabled = { viewModel.updateGstEnabled(it) },
                    onUpdateGstScheme = { viewModel.updateGstScheme(it) },
                    onUpdateGstFilingFrequency = { viewModel.updateGstFilingFrequency(it) },
                    onUpdateGstReturnPeriod = { month, quarter -> viewModel.updateGstReturnPeriod(month, quarter) },
                    onFinancialYearSelected = { viewModel.switchFinancialYear(it) },
                    onExportCsv = {
                        coroutineScope.launch {
                            val intent = viewModel.exportSelectedGstReturnAndShare(com.example.accounting.domain.export.ExportFormat.CSV)
                            if (intent != null) context.startActivity(intent)
                        }
                    },
                    onExportGstrJson = {
                        coroutineScope.launch {
                            val intent = viewModel.exportSelectedGstReturnAndShare(com.example.accounting.domain.export.ExportFormat.GSTR_JSON)
                            if (intent != null) context.startActivity(intent)
                        }
                    },
                    onSetNilReturn = { isNil -> viewModel.setSelectedGstReturnNil(isNil) },
                    onExportJson = {
                        coroutineScope.launch {
                            val intent = viewModel.exportSelectedGstReturnAndShare(com.example.accounting.domain.export.ExportFormat.JSON)
                            if (intent != null) context.startActivity(intent)
                        }
                    },
                    onPreviewPdf = {
                        val file = viewModel.renderGstReturnPdf()
                        if (file != null) {
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, com.example.accounting.data.rendering.ShareAdapter.FILE_PROVIDER_AUTHORITY, file
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Preview GSTR-1 PDF"))
                            } catch (e: android.content.ActivityNotFoundException) {
                                // No PDF viewer installed - same silent-no-op convention every
                                // other export/share callback here already uses.
                            }
                        }
                    },
                    onDownloadPdf = {
                        val intent = viewModel.shareGstReturnPdf()
                        if (intent != null) context.startActivity(Intent.createChooser(intent, "Download GSTR-1 PDF"))
                    },
                    onPrintPdf = { viewModel.printGstReturn() },
                    onSharePdfSummary = {
                        val text = viewModel.buildGstReturnShareText()
                        if (text != null) {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share GSTR-1 summary"))
                        }
                    },
                    onUpdateGstReminderEnabled = { viewModel.updateGstReminderEnabled(it) },
                    onSaveProviderUsername = { viewModel.saveGstProviderUsername(it) },
                    getProviderUsername = { viewModel.getGstProviderUsername() },
                    onOpenSalesRegister = { viewModel.viewReport("Sales Register") },
                    onOpenLedgers = { viewModel.navigateTo(AppRoute.ChartOfAccounts) },
                    onFixNow = { voucherId ->
                        uiState.vouchers.find { it.voucherId == voucherId }?.let { selectedVoucherDetail = it }
                    }
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    when (val route = uiState.currentRoute) {
                        is AppRoute.Dashboard -> DashboardScreen(
                            uiState = uiState,
                            onOpenCreateVoucher = { type ->
                                createVoucherType = type
                                isCreateVoucherTypeLocked = false
                                isCreateVoucherOpen = true
                            },
                            onVoucherClick = { selectedVoucherDetail = it },
                            onViewAllDayBook = { viewModel.navigateTo(AppRoute.DayBook) },
                            onViewReceivables = { viewModel.viewReport("Outstanding Receivables") },
                            onViewPayables = { viewModel.viewReport("Outstanding Payables") },
                            onViewProfitLoss = { viewModel.viewReport("Profit & Loss") },
                            onViewGstSummary = { viewModel.viewReport("GST Summary") },
                            onViewGstDashboard = { viewModel.navigateTo(AppRoute.GstDashboard) },
                            onViewTrialBalance = { viewModel.viewReport("Trial Balance") },
                            onViewBalanceSheet = { viewModel.viewReport("Balance Sheet") },
                            onViewCashFlow = { viewModel.viewReport("Cash Flow") },
                            onOpenCash = { viewModel.navigateTo(AppRoute.Money) },
                            onOpenBank = { viewModel.navigateTo(AppRoute.Money) },
                            onOpenSales = { viewModel.navigateTo(AppRoute.Sales) },
                            onOpenPurchases = { viewModel.navigateTo(AppRoute.Purchases) },
                            onAddCustomer = { createPartyRole = PartyRole.CUSTOMER },
                            onAddSupplier = { createPartyRole = PartyRole.SUPPLIER },
                            onAddItem = { isCreateStockItemOpen = true }
                        )

                        is AppRoute.DayBook -> DayBookScreen(
                            uiState = uiState,
                            onVoucherClick = { selectedVoucherDetail = it },
                            onOpenCreateVoucher = { type ->
                                createVoucherType = type
                                isCreateVoucherTypeLocked = false
                                isCreateVoucherOpen = true
                            },
                            onFilterTypeSelected = { viewModel.setVoucherTypeFilter(it) },
                            onSearchQueryChanged = { viewModel.setSearchQuery(it) },
                            onPrint = {
                                coroutineScope.launch {
                                    val file = viewModel.renderDayBookPdf()
                                    if (file != null) {
                                        try {
                                            com.example.accounting.data.rendering.PrintAdapter.print(context, file, "Day Book")
                                        } catch (e: Exception) {
                                            // No print service configured - same silent no-op every
                                            // other export/share callback here already uses.
                                        }
                                    }
                                }
                            }
                        )

                        is AppRoute.ChartOfAccounts, is AppRoute.LedgerStatement -> ChartOfAccountsScreen(
                            uiState = uiState,
                            onLedgerClick = { viewModel.loadLedgerStatement(it) },
                            onBackFromStatement = { viewModel.clearLedgerStatement() },
                            onOpenCreateLedger = { editingLedger = null; quickAddLedgerGroupId = null; isCreateLedgerOpen = true },
                            onOpenCreateStockItem = { isCreateStockItemOpen = true },
                            onDeleteLedger = { viewModel.deleteLedgerSafely(it.ledgerId) },
                            onEditLedger = { editingLedger = it; isCreateLedgerOpen = true },
                            onOpenCreateGroup = { isCreateGroupOpen = true },
                            showItemsTab = isInventoryEnabled(uiState),
                            onGenerateBarcode = { viewModel.generateBarcodeForItem(it) },
                            onScanBarcode = { barcodeScanTarget = BarcodeScanTarget.ItemLookup; barcodePhotoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onOpenAccountingSetup = { viewModel.navigateTo(AppRoute.SettingsAndSync) },
                            onVoucherClick = { voucherId -> uiState.vouchers.find { it.voucherId == voucherId }?.let { selectedVoucherDetail = it } },
                            onPrintLedgerStatement = {
                                val file = viewModel.renderLedgerStatementPdf()
                                if (file != null) {
                                    try {
                                        com.example.accounting.data.rendering.PrintAdapter.print(context, file, "Ledger Statement")
                                    } catch (e: Exception) {
                                        // No print service configured.
                                    }
                                }
                            },
                            onShareLedgerStatement = {
                                val intent = viewModel.shareLedgerStatementPdf()
                                if (intent != null) context.startActivity(Intent.createChooser(intent, "Share Ledger Statement"))
                            },
                            onRefreshLedgerStatement = { viewModel.refreshLedgerStatement() }
                        )

                        is AppRoute.Reports -> ReportsCenterScreen(
                            uiState = uiState,
                            onOpenDayBook = { viewModel.navigateTo(AppRoute.DayBook) },
                            onOpenAllLedgers = { viewModel.navigateTo(AppRoute.ChartOfAccounts) },
                            onExportReport = { reportKey ->
                                coroutineScope.launch {
                                    val intent = viewModel.exportReportAndShare(reportKey)
                                    if (intent != null) context.startActivity(intent)
                                }
                            },
                            onShareReport = { reportKey ->
                                val text = viewModel.buildReportShareText(reportKey)
                                if (text != null) {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share $reportKey"))
                                }
                            },
                            onPrintReport = { reportKey ->
                                val file = viewModel.renderReportPdf(reportKey)
                                if (file != null) {
                                    try {
                                        com.example.accounting.data.rendering.PrintAdapter.print(context, file, reportKey)
                                    } catch (e: Exception) {
                                        // No print service configured.
                                    }
                                }
                            },
                            onRefreshReport = { viewModel.refreshFinancialReports() },
                            gstReturnActions = gstReturnActions,
                            deepLinkReportKey = uiState.reportsDeepLink,
                            onDeepLinkConsumed = { viewModel.consumeReportsDeepLink() }
                        )

                        is AppRoute.GstDashboard -> GstReturnDashboardView(
                            uiState = uiState,
                            onSelectPeriod = gstReturnActions.onSelectPeriod,
                            onOpenReturn = gstReturnActions.onOpenReturn,
                            onClearSelection = gstReturnActions.onClearSelection,
                            onPrepare = gstReturnActions.onPrepare,
                            onValidate = gstReturnActions.onValidate,
                            onGenerateJson = gstReturnActions.onGenerateJson,
                            onShareArtifact = gstReturnActions.onShareArtifact,
                            onImportResponseFile = gstReturnActions.onImportResponseFile,
                            onMarkFiled = gstReturnActions.onMarkFiled,
                            onSubmitOnline = gstReturnActions.onSubmitOnline,
                            onUpdateGstEnabled = gstReturnActions.onUpdateGstEnabled,
                            onUpdateGstScheme = gstReturnActions.onUpdateGstScheme,
                            onUpdateGstFilingFrequency = gstReturnActions.onUpdateGstFilingFrequency,
                            onUpdateGstReturnPeriod = gstReturnActions.onUpdateGstReturnPeriod,
                            onFinancialYearSelected = gstReturnActions.onFinancialYearSelected,
                            onExportCsv = gstReturnActions.onExportCsv,
                            onExportGstrJson = gstReturnActions.onExportGstrJson,
                            onSetNilReturn = gstReturnActions.onSetNilReturn,
                            onExportJson = gstReturnActions.onExportJson,
                            onPreviewPdf = gstReturnActions.onPreviewPdf,
                            onDownloadPdf = gstReturnActions.onDownloadPdf,
                            onPrintPdf = gstReturnActions.onPrintPdf,
                            onSharePdfSummary = gstReturnActions.onSharePdfSummary,
                            onUpdateGstReminderEnabled = gstReturnActions.onUpdateGstReminderEnabled,
                            onSaveProviderUsername = gstReturnActions.onSaveProviderUsername,
                            getProviderUsername = gstReturnActions.getProviderUsername,
                            onOpenSalesRegister = gstReturnActions.onOpenSalesRegister,
                            onOpenLedgers = gstReturnActions.onOpenLedgers,
                            gstBottomNavRequest = uiState.gstActiveBottomTab,
                            onConsumeGstBottomNavRequest = {},
                            onFixNow = gstReturnActions.onFixNow,
                            onMarkProcessingManually = { viewModel.markSelectedGstReturnProcessingManually() },
                            onNavigateToProfile = { viewModel.navigateTo(AppRoute.Profile) },
                            onNavigateToSettings = { viewModel.navigateTo(AppRoute.SettingsAndSync) },
                            onNavigateToSupport = { viewModel.navigateTo(AppRoute.Support) },
                            onLogoutCloudSync = { viewModel.logoutCloudSync() },
                            onActiveBottomTabChanged = { viewModel.requestGstBottomNav(it) }
                        )

                        is AppRoute.SettingsAndSync -> SettingsAndSyncScreen(
                            uiState = uiState,
                            onOpenCreateCompany = { isCreateCompanyOpen = true },
                            onSaveCompany = { company ->
                                viewModel.updateCompany(
                                    company.companyId, company.name, company.tradeName, company.gstin, company.pan,
                                    company.stateCode, company.address, company.email, company.phone, company.pinCode
                                )
                            },
                            onDeleteCompany = { viewModel.deleteCompany(it.companyId) },
                            onTogglePeriodLock = { viewModel.togglePeriodLock(it) },
                            onAddPreviousFinancialYear = { viewModel.addPreviousFinancialYear() },
                            onTriggerSync = { viewModel.triggerSync() },
                            onUpdateAccountingConfiguration = { mode, type -> viewModel.updateAccountingConfiguration(mode, type) },
                            isCloudSyncLoggedIn = uiState.isCloudSyncLoggedIn,
                            onCloudSyncLogin = { email, password -> viewModel.loginCloudSync(email, password) },
                            onCloudSyncLogout = { viewModel.logoutCloudSync() }
                        )

                        is AppRoute.Sales -> SalesScreen(
                            vouchers = uiState.vouchers.filter { it.voucherType == VoucherType.SALES || it.voucherType == VoucherType.CREDIT_NOTE },
                            parties = uiState.parties.filter { it.role == PartyRole.CUSTOMER },
                            ledgers = uiState.ledgers,
                            salesRevenue = uiState.vouchers.filter { it.voucherType == VoucherType.SALES && !it.isCancelled }
                                .fold(com.example.accounting.core.common.Money.ZERO) { acc, v -> acc + v.totalDebits },
                            receivables = uiState.outstandingByVoucherId.values.fold(0L) { acc, v -> acc + v }
                                .let { com.example.accounting.core.common.Money.fromPaise(it) },
                            onNewSale = { createVoucherType = VoucherType.SALES; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onNewCreditNote = { createVoucherType = VoucherType.CREDIT_NOTE; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onVoucherClick = { selectedVoucherDetail = it },
                            onAddCustomer = { createPartyRole = PartyRole.CUSTOMER },
                            onPartyClick = { party -> uiState.ledgers.find { l -> l.ledgerId == party.ledgerId }?.let { viewModel.loadLedgerStatement(it) } },
                            onToggleFavoriteParty = { viewModel.toggleFavoriteParty(it.partyId) },
                            outstandingByVoucherId = uiState.outstandingByVoucherId,
                            onScanDocument = launchDocumentScan
                        )

                        is AppRoute.Purchases -> PurchasesScreen(
                            vouchers = uiState.vouchers.filter { it.voucherType == VoucherType.PURCHASE || it.voucherType == VoucherType.DEBIT_NOTE },
                            parties = uiState.parties.filter { it.role == PartyRole.SUPPLIER },
                            ledgers = uiState.ledgers,
                            onNewPurchase = { createVoucherType = VoucherType.PURCHASE; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onNewDebitNote = { createVoucherType = VoucherType.DEBIT_NOTE; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onVoucherClick = { selectedVoucherDetail = it },
                            onAddSupplier = { createPartyRole = PartyRole.SUPPLIER },
                            onPartyClick = { party -> uiState.ledgers.find { l -> l.ledgerId == party.ledgerId }?.let { viewModel.loadLedgerStatement(it) } },
                            onToggleFavoriteParty = { viewModel.toggleFavoriteParty(it.partyId) },
                            onScanDocument = launchDocumentScan,
                            outstandingByVoucherId = uiState.outstandingByVoucherId
                        )

                        is AppRoute.Money -> MoneyTabContent(
                            uiState = uiState,
                            onOpenCreateVoucher = { type ->
                                createVoucherType = type
                                isCreateVoucherTypeLocked = false
                                isCreateVoucherOpen = true
                            },
                            onLedgerClick = { viewModel.loadLedgerStatement(it) },
                            onAddBankUpiProfile = { isCreateBankUpiOpen = true },
                            onDeleteBankUpiProfile = { viewModel.deleteBankUpiProfile(it) },
                            onSaveDraftLines = { draft, lines -> viewModel.editVoucherDraftLines(draft, lines) },
                            onPostDraft = { viewModel.postVoucherDraft(it) },
                            onDiscardDraft = { viewModel.discardVoucherDraft(it) },
                            // Step 2 (Sales/Purchase/Money) audit fix - this lambda's params were
                            // misnamed against MoneyVoucherEntryScreen's real call order
                            // (narration, refNumber, applyRoundOff, paymentMode), causing three
                            // confirmed defects: narration/reference number swapped on every
                            // posted Receive/Pay/Transfer, the real Round Off toggle silently
                            // never applied (postQuickVoucher has no round-off step at all), and
                            // the real derived CASH/BANK/UPI payment mode discarded in favor of a
                            // guess from the misread round-off boolean. postQuickVoucherWithRoundOff
                            // is the existing, already-correct function for this exact call shape.
                            onSubmitMoneyVoucher = { type, date, debit, credit, amount, narration, refNumber, applyRoundOff, paymentMode ->
                                viewModel.postQuickVoucherWithRoundOff(type, date, debit, credit, amount, narration, refNumber, applyRoundOff, paymentMode)
                            },
                            onAddParty = { role -> createPartyRole = role },
                            onEditLedger = { editingLedger = it; isCreateLedgerOpen = true },
                            moneyDeepLink = uiState.moneyDeepLink,
                            onMoneyDeepLinkConsumed = { viewModel.consumeMoneyDeepLink() },
                            onScanBankStatement = { launchDocumentScan(OcrDocumentType.BANK_STATEMENT) },
                            onScanDocument = launchDocumentScan
                        )

                        is AppRoute.Parties -> PartiesScreen(
                            role = PartyRole.valueOf(route.role),
                            parties = uiState.parties.filter { it.role.name == route.role },
                            ledgers = uiState.ledgers,
                            onAddParty = { createPartyRole = PartyRole.valueOf(route.role) },
                            onPartyClick = { party -> uiState.ledgers.find { l -> l.ledgerId == party.ledgerId }?.let { viewModel.loadLedgerStatement(it) } },
                            onEditLedger = { editingLedger = it; isCreateLedgerOpen = true },
                            onToggleFavorite = { viewModel.toggleFavoriteParty(it.partyId) }
                        )

                        is AppRoute.Profile -> ProfileScreen(
                            businessProfile = businessProfileSeed(uiState),
                            individualProfile = uiState.individualProfile,
                            isPinCodeLookupInProgress = uiState.isPinCodeLookupInProgress,
                            pinCodeLookupResult = uiState.pinCodeLookupResult,
                            onLookupPinCode = { viewModel.lookupPinCode(it) },
                            onSaveBusinessProfile = { bn, ln, addr, pin, city, state, country, ph, em, gst, pan ->
                                viewModel.updateBusinessProfile(bn, ln, addr, ph, em, gst, pan, pin, city, state, country)
                            },
                            onSaveIndividualProfile = { name, addr, pin, city, state, country, ph, em, pan ->
                                viewModel.updateIndividualProfile(name, addr, ph, em, pan, pin, city, state, country)
                            },
                            onOpenImportData = { viewModel.navigateTo(AppRoute.DataTools) },
                            onOpenSubscription = { viewModel.navigateTo(AppRoute.Subscription) },
                            onOpenCompanyAndSync = { viewModel.navigateTo(AppRoute.SettingsAndSync) },
                            onOpenBusinessSetupWizard = { viewModel.navigateTo(AppRoute.ProfileWizard) },
                            onScanProfileDocument = launchDocumentScan
                        )

                        is AppRoute.ProfileWizard -> ProfileWizardScreen(
                            businessProfile = businessProfileSeed(uiState),
                            logoAssetLabel = uiState.businessProfile?.logoAssetId,
                            signatureAssetLabel = uiState.businessProfile?.signatureAssetId,
                            isPinCodeLookupInProgress = uiState.isPinCodeLookupInProgress,
                            pinCodeLookupResult = uiState.pinCodeLookupResult,
                            onLookupPinCode = { viewModel.lookupPinCode(it) },
                            onSave = { businessName, legalName, constitutionType, address, pinCode, city, state, country,
                                phone, email, website, gstin, pan, tan, udyam, bankName, bankAccountNumber, bankIfsc, bankBranch, upiId, terms ->
                                viewModel.updateBusinessProfileFull(
                                    businessName, legalName, constitutionType, address, pinCode, city, state, country,
                                    phone, email, website, gstin, pan, tan, udyam, bankName, bankAccountNumber, bankIfsc, bankBranch, upiId, terms
                                )
                            },
                            onPickLogo = { logoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onPickSignature = { signaturePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onFinish = { viewModel.navigateTo(AppRoute.Profile) }
                        )

                        is AppRoute.Subscription -> SubscriptionScreen(
                            subscription = uiState.currentSubscription,
                            onUpgradeOrRenew = { planType, planName, entitlements -> viewModel.upgradeOrRenewSubscription(planType, planName, entitlements) }
                        )

                        is AppRoute.DataTools -> DataToolsScreen(
                            lastImportResult = uiState.lastImportResult,
                            lastImportRowOutcomes = uiState.lastImportRowOutcomes,
                            groups = uiState.groups,
                            onPickCsvFile = { pendingImportFormat = ImportFileFormat.CSV; openDocumentLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                            onPickJsonFile = { pendingImportFormat = ImportFileFormat.JSON; openDocumentLauncher.launch(arrayOf("application/json", "text/*")) },
                            onReviewAndCreateRow = { suggestion, type, groupId -> viewModel.reviewAndCreateImportRow(suggestion, type, groupId) }
                        )

                        is AppRoute.Search -> SearchScreen(
                            initialQuery = route.query,
                            parties = uiState.parties,
                            ledgers = uiState.ledgers,
                            vouchers = uiState.vouchers,
                            stockItems = uiState.stockItems,
                            onBack = { viewModel.navigateBack() },
                            onPartyClick = { party -> uiState.ledgers.find { l -> l.ledgerId == party.ledgerId }?.let { viewModel.loadLedgerStatement(it) } },
                            onLedgerClick = { viewModel.loadLedgerStatement(it) },
                            onVoucherClick = { selectedVoucherDetail = it }
                        )

                        is AppRoute.About -> AboutScreen()
                        is AppRoute.PrivacyPolicy -> PrivacyPolicyScreen()
                        is AppRoute.TermsAndConditions -> TermsAndConditionsScreen()
                        is AppRoute.Support -> SupportScreen()
                    }
                }
            }
        }
    }

    // "Build a real invoice UI, easiest to use by anyone" - a fresh Sale/Purchase creation
    // reroutes to the dedicated full-screen QuickInvoiceEntryScreen instead of the generic 8-way
    // CreateVoucherDialog. isCreateVoucherTypeLocked is also true for SalesScreen/PurchasesScreen's
    // own "+ New Sale"/"+ New Purchase" FABs (not only Correct-Voucher), so the real "still needs
    // the dialog's prefill support" signal is pendingVoucherCorrection specifically - every other
    // voucher type (Receipt/Payment/Contra/Journal/Notes) and any Correct-Voucher re-post keep
    // using CreateVoucherDialog completely unchanged below.
    val isNewInvoiceEntry = isCreateVoucherOpen && uiState.pendingVoucherCorrection == null &&
        (createVoucherType == VoucherType.SALES || createVoucherType == VoucherType.PURCHASE)

    if (isNewInvoiceEntry) {
        QuickInvoiceEntryScreen(
            isSale = createVoucherType == VoucherType.SALES,
            ledgers = uiState.ledgers,
            groups = uiState.groups,
            stockItems = uiState.stockItems,
            companyStateCode = uiState.currentCompany?.stateCode.orEmpty(),
            isInventoryEnabled = isInventoryEnabled(uiState),
            gstApplicable = uiState.currentCompany?.gstOperatingMode != com.example.accounting.domain.company.GstOperatingMode.ACCOUNT_ONLY,
            isServiceCompany = uiState.currentCompany?.businessType == com.example.accounting.domain.company.BusinessType.SERVICE,
            onDismiss = { isCreateVoucherOpen = false },
            onAddNewParty = { role -> createPartyRole = role },
            onAddNewTradeLedger = { isCreateLedgerOpen = true },
            onPostSaleInvoice = { customer, sales, lines, date, ref, narration, pricingMode ->
                viewModel.postSaleInvoice(customer, sales, lines, date, ref, narration, pricingMode)
            },
            onPostPurchaseBill = { supplier, purchase, lines, date, ref, narration, pricingMode ->
                viewModel.postPurchaseBill(supplier, purchase, lines, date, ref, narration, pricingMode)
            },
            onPostAccountOnlySale = { customer, sales, amount, date, ref, narration, gstRate, hsn ->
                viewModel.postAccountOnlySale(customer, sales, amount, date, ref, narration, gstRate, hsn)
            },
            onPostAccountOnlyPurchase = { supplier, purchase, amount, date, ref, narration, gstRate, hsn ->
                viewModel.postAccountOnlyPurchase(supplier, purchase, amount, date, ref, narration, gstRate, hsn)
            }
        )
    } else if (isCreateVoucherOpen) {
        CreateVoucherDialog(
            ledgers = uiState.ledgers,
            groups = uiState.groups,
            stockItems = uiState.stockItems,
            vouchers = uiState.vouchers,
            outstandingInvoices = uiState.outstandingInvoices,
            companyStateCode = uiState.currentCompany?.stateCode.orEmpty(),
            isInventoryEnabled = isInventoryEnabled(uiState),
            gstApplicable = uiState.currentCompany?.gstOperatingMode != com.example.accounting.domain.company.GstOperatingMode.ACCOUNT_ONLY,
            defaultVoucherType = createVoucherType,
            isServiceCompany = uiState.currentCompany?.businessType == com.example.accounting.domain.company.BusinessType.SERVICE,
            lockedType = isCreateVoucherTypeLocked,
            prefillFrom = uiState.pendingVoucherCorrection,
            onDismiss = { isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false; viewModel.clearOutstandingInvoices() },
            onAddNewParty = { role -> createPartyRole = role },
            onAddNewBankLedger = { editingLedger = null; quickAddLedgerGroupId = null; isCreateLedgerOpen = true },
            onAddNewTradeLedger = { isCreateLedgerOpen = true },
            onPostQuickVoucher = { type, date, debit, credit, amount, narration, ref ->
                viewModel.postQuickVoucher(type, date, debit, credit, amount, narration, ref)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostSaleInvoice = { customer, sales, lines, date, ref, narration, pricingMode ->
                viewModel.postSaleInvoice(customer, sales, lines, date, ref, narration, pricingMode)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostPurchaseBill = { supplier, purchase, lines, date, ref, narration, pricingMode ->
                viewModel.postPurchaseBill(supplier, purchase, lines, date, ref, narration, pricingMode)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostAccountOnlySale = { customer, sales, amount, date, ref, narration, gstRate, hsn ->
                viewModel.postAccountOnlySale(customer, sales, amount, date, ref, narration, gstRate, hsn)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostAccountOnlyPurchase = { supplier, purchase, amount, date, ref, narration, gstRate, hsn ->
                viewModel.postAccountOnlyPurchase(supplier, purchase, amount, date, ref, narration, gstRate, hsn)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostCreditNote = { originalId, date, ref, narration ->
                viewModel.postCreditNote(originalId, date, ref, narration)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostDebitNote = { originalId, date, ref, narration ->
                viewModel.postDebitNote(originalId, date, ref, narration)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onPostSettlement = { type, date, debit, credit, amount, narration, ref, mode, allocations ->
                viewModel.postQuickVoucher(type, date, debit, credit, amount, narration, ref, mode, allocations)
                isCreateVoucherOpen = false; isCreateVoucherTypeLocked = false
            },
            onLoadOutstandingInvoices = { viewModel.loadOutstandingInvoices(it) },
            onClearOutstandingInvoices = { viewModel.clearOutstandingInvoices() },
            onScanBarcode = { barcodeScanTarget = BarcodeScanTarget.PurchaseVoucher; barcodePhotoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            scannedBarcodeValue = uiState.lastBarcodeScan?.rawValue,
            scannedMatchedItemId = uiState.lastBarcodeScan?.matchedStockItemId,
            onScannedValueConsumed = { viewModel.clearBarcodeState() }
        )
    }

    if (isCreateLedgerOpen) {
        CreateLedgerDialog(
            groups = uiState.groups,
            initialGroupId = quickAddLedgerGroupId,
            existingLedger = editingLedger,
            isLookingUp = uiState.isPinCodeLookupInProgress,
            lookupResult = uiState.pinCodeLookupResult,
            onLookupPinCode = { viewModel.lookupPinCode(it) },
            onDismiss = { isCreateLedgerOpen = false; editingLedger = null; quickAddLedgerGroupId = null },
            onCreateLedger = viewModel::createLedger,
            onUpdateLedger = viewModel::updateLedger
        )
    }

    if (isCreateGroupOpen) {
        CreateGroupDialog(
            parentCandidates = uiState.groups,
            onDismiss = { isCreateGroupOpen = false },
            onCreateGroup = { name, parentGroupId -> viewModel.createGroup(name, parentGroupId) }
        )
    }

    if (isCreateStockItemOpen) {
        CreateStockItemDialog(
            onDismiss = { isCreateStockItemOpen = false },
            onCreateItem = viewModel::createStockItem
        )
    }

    if (isCreateCompanyOpen) {
        CreateCompanyDialog(
            onDismiss = { isCreateCompanyOpen = false },
            isLookingUp = uiState.isPinCodeLookupInProgress,
            lookupResult = uiState.pinCodeLookupResult,
            onLookupPinCode = { viewModel.lookupPinCode(it) },
            onCreateCompany = { name, tradeName, gstin, pan, stateCode, address, email, phone, pinCode ->
                viewModel.createCompany(name, tradeName, gstin, pan, stateCode, address, email, phone, pinCode)
                isCreateCompanyOpen = false
            }
        )
    }

    if (isCreateBankUpiOpen) {
        CreateBankUpiProfileDialog(
            onDismiss = { isCreateBankUpiOpen = false },
            onCreate = { bankName, accountHolderName, accountNumber, ifscCode, branchName, upiId, upiPayeeName ->
                viewModel.createBankUpiProfile(bankName, accountHolderName, accountNumber, ifscCode, branchName, upiId, upiPayeeName)
                isCreateBankUpiOpen = false
            }
        )
    }

    createPartyRole?.let { role ->
        CreatePartyDialog(
            role = role,
            onDismiss = { createPartyRole = null },
            isLookingUp = uiState.isPinCodeLookupInProgress,
            lookupResult = uiState.pinCodeLookupResult,
            onLookupPinCode = { viewModel.lookupPinCode(it) },
            onCreateParty = viewModel::createParty,
            onRequestContactImport = onRequestContactImport,
            importedContact = pendingContactImport,
            onContactImportConsumed = { pendingContactImport = null }
        )
    }

    if (showContactsPermissionRationale) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showContactsPermissionRationale = false },
            title = { Text("Allow Contacts access?") },
            text = { Text("LedgerPrime reads your Contacts only when you tap \"Import from Contacts\" while adding a Customer or Supplier, to fill in their name and phone number for you.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showContactsPermissionRationale = false
                    contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                }) { Text("Continue") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showContactsPermissionRationale = false }) { Text("Not now") }
            }
        )
    }

    // Phase 7J UI fix: was computed by the ViewModel but never displayed anywhere - a scan
    // silently produced a result no one could see. matchedStockItemId is only ever a suggestion
    // (per QrBarcodeAdapter's own contract) - never auto-selected into anything.
    // Bug #3 fix: only shown for the standalone Items-tab scan (ItemLookup) - a
    // PurchaseVoucher/ReceivePayment scan instead flows silently into the already-open form as a
    // prefill (see CreateVoucherDialog/MoneyVoucherEntryScreen's own LaunchedEffect), so this
    // generic result dialog would otherwise pop up redundantly on top of it.
    if (barcodeScanTarget == BarcodeScanTarget.ItemLookup) {
        uiState.lastBarcodeScan?.let { scan ->
            val matchedItem = uiState.stockItems.firstOrNull { it.itemId == scan.matchedStockItemId }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.clearBarcodeState() },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { viewModel.clearBarcodeState() }) { Text("Close") }
                },
                title = { Text("Barcode Scan") },
                text = {
                    if (matchedItem != null) {
                        Text("Matched: ${matchedItem.name}")
                    } else {
                        Text("No matching item found for this barcode. You can add it as a new item.")
                    }
                }
            )
        }
    }

    // Step 8 audit fix - the exact same class of bug the Barcode Scan dialog above was already
    // fixed for ("was computed by the ViewModel but never displayed anywhere - a scan silently
    // produced a result no one could see"): ItemsListView's own "Generate barcode" button called
    // AccountingViewModel.generateBarcodeForItem, which stored a real BarcodeGenerationResult in
    // uiState.lastBarcodeGeneration - and nothing anywhere ever read that state. Tapping the
    // button had zero visible effect. Reuses the same QrCodeImage component "Invoice QR > Show"
    // already renders a real scannable code with (VoucherDetailDialog) - never a new rendering
    // path.
    uiState.lastBarcodeGeneration?.let { generation ->
        val item = uiState.stockItems.firstOrNull { it.itemId == generation.stockItemId }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.clearBarcodeState() },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.clearBarcodeState() }) { Text("Close") }
            },
            title = { Text(item?.name?.let { "Barcode - $it" } ?: "Barcode") },
            text = { com.example.accounting.presentation.components.GeneratedBarcodeContent(generation.payload.rawValue) }
        )
    }

    uiState.lastOcrExtraction?.let { extraction ->
        OcrReviewDialog(
            extraction = extraction,
            onApplyOcrProfileDraft = { name, pan -> viewModel.applyOcrProfileDraft(name, pan) },
            onApplyOcrBusinessProfileDraft = { businessName, gstin -> viewModel.applyOcrBusinessProfileDraft(businessName, gstin) },
            onOpenOcrPendingReviews = { viewModel.clearOcrExtraction(); viewModel.navigateTo(AppRoute.Money) },
            onDismiss = { viewModel.clearOcrExtraction() }
        )
    }

    selectedVoucherDetail?.let { voucher ->
        LaunchedEffect(voucher.voucherId) {
            viewModel.loadVoucherAttachments(voucher.voucherId)
            // Product correction ("THIS APPLICATION IS NOT AN ERP") - loads the plain-Bill data
            // this dialog's default view needs, so it never falls back to showing raw Dr/Cr while
            // waiting; cleared on dismiss below so a stale bill never flashes for the next voucher.
            viewModel.loadVoucherBillDetails(voucher)
        }
        val attachmentsForThisVoucher = if (uiState.voucherAttachmentsVoucherId == voucher.voucherId) uiState.voucherAttachments else emptyList()
        VoucherDetailDialog(
            voucher = voucher,
            documentData = uiState.voucherBillDocumentData,
            billSummary = uiState.voucherBillSummary,
            onDismiss = { selectedVoucherDetail = null; viewModel.clearVoucherAttachments(); viewModel.clearVoucherBillDetails() },
            onDeleteVoucher = { v -> viewModel.deleteVoucherSafely(v.voucherId) },
            onCorrectVoucher = { v -> viewModel.correctVoucher(v) },
            onUpdateVoucherMetadata = { voucherId, narration, referenceNumber -> viewModel.updateVoucherMetadata(voucherId, narration, referenceNumber) },
            isInventoryEnabled = isInventoryEnabled(uiState),
            allVouchers = uiState.vouchers,
            attachments = attachmentsForThisVoucher,
            isAttachmentsLoading = uiState.isVoucherAttachmentsLoading,
            isAttaching = uiState.isAttachingDocument,
            removingAttachmentReferenceId = uiState.removingAttachmentReferenceId,
            onAttachClick = {
                voucherIdPendingAttachment = voucher.voucherId
                // The OS picker filter is a UX convenience only (Part 5) - it is not the
                // correctness/security boundary. AttachmentFileValidator re-checks the actual
                // file bytes regardless of what the user manages to pick here.
                attachmentPickerLauncher.launch(
                    arrayOf(
                        "image/jpeg", "image/png", "image/webp", "application/pdf",
                        "text/csv", "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                )
            },
            onRemoveAttachment = { attachment -> viewModel.removeVoucherAttachment(voucher.voucherId, attachment.referenceId) },
            outstandingPaise = uiState.outstandingByVoucherId[voucher.voucherId],
            companyGstin = uiState.currentCompany?.gstin.orEmpty(),
            onPreviewInvoice = { v ->
                selectedVoucherDetail = null
                showInvoicePreview = true
                viewModel.loadInvoicePreview(v.voucherId)
            }
        )
    }

    if (showInvoicePreview) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showInvoicePreview = false; viewModel.clearInvoicePreview() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                InvoicePreviewScreen(
                    data = uiState.invoicePreviewData,
                    templates = uiState.invoicePreviewTemplates,
                    selectedTemplateId = uiState.invoicePreviewSelectedTemplateId,
                    errorMessage = uiState.invoicePreviewError,
                    onBack = { showInvoicePreview = false; viewModel.clearInvoicePreview() },
                    onSelectTemplate = { viewModel.selectInvoicePreviewTemplate(it) },
                    onSetAsDefault = { viewModel.setInvoicePreviewTemplateAsDefault() },
                    onShare = {
                        val intent = viewModel.shareInvoicePreviewPdf()
                        if (intent != null) context.startActivity(Intent.createChooser(intent, "Share Invoice PDF"))
                    },
                    onShareCsv = {
                        val intent = viewModel.shareInvoicePreviewCsv()
                        if (intent != null) context.startActivity(Intent.createChooser(intent, "Share Invoice CSV"))
                    },
                    onShareExcel = {
                        val intent = viewModel.shareInvoicePreviewExcel()
                        if (intent != null) context.startActivity(Intent.createChooser(intent, "Share Invoice Excel"))
                    },
                    onPrint = {
                        // Real-device QA fix - Android's PrintManager requires a real Activity
                        // Context (throws "Can print only from an activity" otherwise, which is
                        // exactly what crashed here when this used to go through the ViewModel's
                        // Application-only `getApplication()`). `context` here is the Composable's
                        // own LocalContext, the real hosting Activity.
                        val file = viewModel.renderInvoicePreviewPdf()
                        if (file != null) {
                            try {
                                com.example.accounting.data.rendering.PrintAdapter.print(context, file, "Invoice")
                            } catch (e: Exception) {
                                // No print service configured on this device - same silent-no-op
                                // convention every other export/share callback here already uses.
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Tapping a Party navigates to its linked Ledger's statement - reuses the existing
 * `loadLedgerStatement`/`ChartOfAccountsScreen` machinery verbatim, never a second statement view.
 *
 * Copies a picked SAF [android.net.Uri] into this app's own cache dir under [fileName] - every
 * OCR/import/branding photo picker above needs a real, stable [File] (not a content:// Uri) to
 * hand to the ViewModel; returns null (never throws) if the source stream can't be opened/read. */
private fun copyUriToCacheFile(context: android.content.Context, uri: android.net.Uri, fileName: String): File? = try {
    // Step 6 audit fix - openInputStream returning null (a real, documented Android possibility)
    // used to fall through silently: the `?.use` block simply never ran, so outputFile was never
    // actually written, yet this function still returned it as a normal non-null File - directly
    // contradicting this function's own "returns null ... if the source stream can't be opened"
    // doc comment above. A caller (e.g. importFromFile) then called sourceFile.readBytes() on a
    // File that was never created, an uncaught FileNotFoundException inside a bare
    // viewModelScope.launch with no try/catch of its own.
    val input = context.contentResolver.openInputStream(uri) ?: return null
    val outputFile = File(context.cacheDir, fileName)
    input.use { stream -> outputFile.outputStream().use { output -> stream.copyTo(output) } }
    outputFile
} catch (e: Exception) {
    null
}

/** Contacts + Favorites correction (docs/CORRECTIONS_LOG.md) - resolves a picked
 * `ContactsContract.Contacts` [android.net.Uri] to a (display name, phone number) pair. A
 * try/catch wrapping a `return` needs a real block-body function, not an expression body - split
 * into this block-body wrapper. Returns null (never a partial/guessed result) if the contact has
 * no phone number, or on any provider error. */
private fun resolveContactNameAndPhone(context: android.content.Context, uri: android.net.Uri): Pair<String, String>? {
    return try {
        val contactCursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        contactCursor.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val idIndex = cursor.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
            if (idIndex < 0 || nameIndex < 0) return null
            val contactId = cursor.getString(idIndex) ?: return null
            val displayName = cursor.getString(nameIndex) ?: ""

            val phoneCursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            ) ?: return null
            phoneCursor.use { phones ->
                if (!phones.moveToFirst()) return null
                val numberIndex = phones.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex < 0) return null
                val phone = phones.getString(numberIndex) ?: return null
                Pair(displayName, phone)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Resolves a picked SAF [android.net.Uri]'s real display name (Phase 7J-B.2 Slice 2) - used only
 * to preserve the original filename/extension when attaching a document; never assumed to be an
 * image, unlike the Photo-Picker-based callers above. Returns null (never guesses) if the
 * provider doesn't expose [android.provider.OpenableColumns.DISPLAY_NAME]. */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
} catch (e: Exception) {
    null
}
