package com.example.accounting.presentation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.dataimport.ImportFileFormat
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyRole
import androidx.activity.compose.BackHandler
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.example.accounting.presentation.navigation.AdaptiveNavigationType
import com.example.accounting.presentation.navigation.AppRoute
import com.example.accounting.presentation.navigation.getAdaptiveNavigationType
import com.example.accounting.presentation.theme.Breakpoints
import com.example.accounting.presentation.components.AppDivider
import com.example.accounting.presentation.components.AppTopBar
import com.example.accounting.presentation.components.CreateBankUpiProfileDialog
import com.example.accounting.presentation.components.CreateCompanyDialog
import com.example.accounting.presentation.components.CreateGroupDialog
import com.example.accounting.presentation.components.CreateLedgerDialog
import com.example.accounting.presentation.components.CreatePartyDialog
import com.example.accounting.presentation.components.CreateStockItemDialog
import com.example.accounting.presentation.components.CreateVoucherDialog
import com.example.accounting.presentation.components.VoucherDetailDialog
import com.example.accounting.presentation.features.dashboard.DashboardScreen
import com.example.accounting.presentation.features.datatools.DataToolsScreen
import com.example.accounting.presentation.features.daybook.DayBookScreen
import com.example.accounting.presentation.features.ledgers.ChartOfAccountsScreen
import com.example.accounting.presentation.features.money.MoneyTabContent
import com.example.accounting.presentation.features.party.PartiesScreen
import com.example.accounting.presentation.features.profile.ProfileScreen
import com.example.accounting.presentation.features.purchases.PurchasesScreen
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

/** Bug #3 fix - which form a pending QR/Barcode scan result should be routed into once the photo
 * picker returns. Purely a presentation-layer routing concern (never touches domain/frozen
 * engines): [ItemLookup] keeps the pre-existing standalone Items-tab behavior (a result dialog);
 * [PurchaseVoucher]/[ReceivePayment] instead feed the scan result into the already-open form as a
 * prefill, never a second scan/decode mechanism. */
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
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
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
    // Edit-Company fix - non-null switches CreateCompanyDialog into edit mode for this company,
    // same isCreateCompanyOpen dialog instance as "+ Add Company" (see editingLedger above).
    var editingCompany by remember { mutableStateOf<com.example.accounting.domain.company.Company?>(null) }
    var selectedVoucherDetail by remember { mutableStateOf<Voucher?>(null) }
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

    // Android Photo Picker for OCR receipt scans - no runtime permission required.
    val receiptPhotoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val file = copyUriToCacheFile(context, uri, "receipt_${System.currentTimeMillis()}.jpg")
                if (file != null) viewModel.scanReceiptForVoucherDraft(file)
            }
        }
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

    val adaptiveNavType = getAdaptiveNavigationType(widthSizeClass)
    val useRail = adaptiveNavType == AdaptiveNavigationType.NAVIGATION_RAIL || adaptiveNavType == AdaptiveNavigationType.PERMANENT_NAVIGATION_DRAWER

    // Play Store readiness pass - Legal + Support drawer, additive over the existing bottom-nav/
    // rail navigation (never replaces it, per the chosen "Legal + support only" scope).
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            com.example.accounting.presentation.components.AppDrawerContent(
                currentCompany = uiState.currentCompany,
                currentRoute = uiState.currentRoute,
                onNavigate = { route ->
                    viewModel.navigateTo(route)
                    coroutineScope.launch { drawerState.close() }
                }
            )
        }
    ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = useRail || maxWidth >= Breakpoints.tablet

        Scaffold(
            topBar = {
                AppTopBar(
                    currentCompany = uiState.currentCompany,
                    companies = uiState.companies,
                    currentFinancialYear = uiState.currentFinancialYear,
                    financialYears = uiState.financialYears,
                    onCompanySelected = { viewModel.switchCompany(it) },
                    onFinancialYearSelected = { viewModel.switchFinancialYear(it) },
                    onAddPreviousFinancialYear = { viewModel.addPreviousFinancialYear() },
                    onNewCompanyClicked = { isCreateCompanyOpen = true },
                    onSearchClicked = { viewModel.navigateTo(AppRoute.Search()) },
                    onProfileClicked = { viewModel.navigateTo(AppRoute.Profile) },
                    onMenuClicked = { coroutineScope.launch { drawerState.open() } },
                    canGoBack = canGoBack,
                    onBack = { viewModel.navigateBack() }
                )
            },
            bottomBar = {
                if (!isExpanded) {
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
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (isExpanded) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        navItems.forEach { item ->
                            NavigationRailItem(
                                selected = uiState.selectedTab == item.tab,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }

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
                    // Fix Now (reference image) - opens the real voucher in the existing
                    // read-only detail dialog, same mechanism onVoucherClick already uses
                    // elsewhere. Never a new direct-edit-posted-voucher path (see this param's
                    // own KDoc on GstReturnDashboardView for why).
                    onFixNow = { voucherId ->
                        uiState.vouchers.find { it.voucherId == voucherId }?.let { selectedVoucherDetail = it }
                    },
                    onMarkProcessingManually = { viewModel.markSelectedGstReturnProcessingManually() },
                    onNavigateToProfile = { viewModel.navigateTo(AppRoute.Profile) },
                    onNavigateToSettings = { viewModel.navigateTo(AppRoute.SettingsAndSync) },
                    onNavigateToSupport = { viewModel.navigateTo(AppRoute.Support) },
                    onLogoutCloudSync = { viewModel.logoutCloudSync() },
                    onActiveBottomTabChanged = { viewModel.setGstActiveBottomTab(it) }
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    when (val route = uiState.currentRoute) {
                        is AppRoute.Dashboard -> DashboardScreen(
                            uiState = uiState,
                            onOpenCreateVoucher = { type -> createVoucherType = type; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onVoucherClick = { selectedVoucherDetail = it },
                            onViewAllDayBook = { viewModel.navigateTo(AppRoute.DayBook) },
                            onViewReceivables = { viewModel.viewReport("Outstanding Receivables") },
                            onViewPayables = { viewModel.viewReport("Outstanding Payables") },
                            onViewProfitLoss = { viewModel.viewReport("Profit & Loss") },
                            onViewGstSummary = { viewModel.viewReport("GST Summary") },
                            onViewGstDashboard = { viewModel.navigateTo(AppRoute.GstDashboard) },
                            onOpenCash = { viewModel.viewMoney("Cash") },
                            onOpenBank = { viewModel.viewMoney("Bank") },
                            onOpenSales = { viewModel.selectTab(NavigationTab.SALES) },
                            onOpenPurchases = { viewModel.selectTab(NavigationTab.PURCHASES) },
                            onAddCustomer = { createPartyRole = PartyRole.CUSTOMER },
                            onAddSupplier = { createPartyRole = PartyRole.SUPPLIER },
                            onAddItem = { isCreateStockItemOpen = true }
                        )

                        is AppRoute.DayBook -> DayBookScreen(
                            uiState = uiState,
                            onVoucherClick = { selectedVoucherDetail = it },
                            onOpenCreateVoucher = { type -> createVoucherType = type; isCreateVoucherTypeLocked = false; isCreateVoucherOpen = true },
                            onFilterTypeSelected = { viewModel.setVoucherTypeFilter(it) },
                            onSearchQueryChanged = { viewModel.setSearchQuery(it) },
                            onPrint = { viewModel.printDayBook() }
                        )

                        is AppRoute.ChartOfAccounts, is AppRoute.LedgerStatement -> ChartOfAccountsScreen(
                            uiState = uiState,
                            onLedgerClick = { ledger -> viewModel.loadLedgerStatement(ledger) },
                            onBackFromStatement = { viewModel.clearLedgerStatement() },
                            onOpenCreateLedger = { isCreateLedgerOpen = true },
                            onOpenCreateStockItem = { isCreateStockItemOpen = true },
                            onDeleteLedger = { ledger -> viewModel.deleteLedgerSafely(ledger.ledgerId) },
                            onEditLedger = { ledger -> editingLedger = ledger; isCreateLedgerOpen = true },
                            onOpenCreateGroup = { isCreateGroupOpen = true },
                            showItemsTab = isInventoryEnabled(uiState),
                            onGenerateBarcode = { itemId -> viewModel.generateBarcodeForItem(itemId) },
                            onScanBarcode = {
                                barcodeScanTarget = BarcodeScanTarget.ItemLookup
                                barcodePhotoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onOpenAccountingSetup = { viewModel.navigateTo(AppRoute.SettingsAndSync) },
                            onVoucherClick = { voucherId ->
                                uiState.vouchers.find { it.voucherId == voucherId }?.let { selectedVoucherDetail = it }
                            }
                        )

                        is AppRoute.Reports -> ReportsCenterScreen(
                            uiState = uiState,
                            onOpenDayBook = { viewModel.navigateTo(AppRoute.DayBook) },
                            onOpenAllLedgers = { viewModel.navigateTo(AppRoute.ChartOfAccounts) },
                            deepLinkReportKey = uiState.reportsDeepLink,
                            onDeepLinkConsumed = { viewModel.consumeReportsDeepLink() },
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
                                    context.startActivity(Intent.createChooser(sendIntent, "Share report"))
                                }
                            },
                            onPrintReport = { reportKey -> viewModel.printReport(reportKey) },
                            gstReturnActions = gstReturnActions
                        )

                        // Dedicated top-level route (see AppRoute.GstDashboard's own KDoc) - calls
                        // GstReturnDashboardView directly, with only its own step header, instead
                        // of Reports Center's category header plus GstCategory's own BackRow both
                        // stacking on top of it (the "three headers eat the screen" problem on a
                        // phone-sized display). Also wires the GST-specific bottom nav below.
                        is AppRoute.GstDashboard -> com.example.accounting.presentation.features.reports.GstReturnDashboardView(
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
                            gstBottomNavRequest = uiState.gstBottomNavRequest,
                            onConsumeGstBottomNavRequest = { viewModel.consumeGstBottomNavRequest() },
                            onFixNow = gstReturnActions.onFixNow,
                            onMarkProcessingManually = gstReturnActions.onMarkProcessingManually,
                            onNavigateToProfile = gstReturnActions.onNavigateToProfile,
                            onNavigateToSettings = gstReturnActions.onNavigateToSettings,
                            onNavigateToSupport = gstReturnActions.onNavigateToSupport,
                            onLogoutCloudSync = gstReturnActions.onLogoutCloudSync,
                            onActiveBottomTabChanged = gstReturnActions.onActiveBottomTabChanged
                        )

                        is AppRoute.SettingsAndSync -> SettingsAndSyncScreen(
                            uiState = uiState,
                            onCompanySwitch = { viewModel.switchCompany(it) },
                            onOpenCreateCompany = { isCreateCompanyOpen = true },
                            onEditCompany = { company -> editingCompany = company; isCreateCompanyOpen = true },
                            onTogglePeriodLock = { viewModel.togglePeriodLock(it) },
                            onTriggerSync = { viewModel.triggerSync() },
                            onUpdateAccountingConfiguration = { mode, businessType -> viewModel.updateAccountingConfiguration(mode, businessType) },
                            isCloudSyncLoggedIn = uiState.isCloudSyncLoggedIn,
                            onCloudSyncLogin = { email, password -> viewModel.loginCloudSync(email, password) },
                            onCloudSyncLogout = { viewModel.logoutCloudSync() }
                        )

                        is AppRoute.Sales -> SalesScreen(
                            vouchers = uiState.vouchers,
                            parties = uiState.parties,
                            ledgers = uiState.ledgers,
                            salesRevenue = uiState.profitAndLoss?.salesRevenue ?: com.example.accounting.core.common.Money.ZERO,
                            receivables = uiState.receivablesReport?.totalOutstanding ?: (uiState.balanceSheet?.sundryDebtors ?: com.example.accounting.core.common.Money.ZERO),
                            onNewSale = { createVoucherType = VoucherType.SALES; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onNewCreditNote = { createVoucherType = VoucherType.CREDIT_NOTE; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onVoucherClick = { selectedVoucherDetail = it },
                            onAddCustomer = { createPartyRole = PartyRole.CUSTOMER },
                            onPartyClick = { party -> onPartySelected(party, uiState.ledgers, viewModel) }
                        )

                        is AppRoute.Purchases -> PurchasesScreen(
                            vouchers = uiState.vouchers,
                            parties = uiState.parties,
                            ledgers = uiState.ledgers,
                            onNewPurchase = { createVoucherType = VoucherType.PURCHASE; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onNewDebitNote = { createVoucherType = VoucherType.DEBIT_NOTE; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onVoucherClick = { selectedVoucherDetail = it },
                            onAddSupplier = { createPartyRole = PartyRole.SUPPLIER },
                            onPartyClick = { party -> onPartySelected(party, uiState.ledgers, viewModel) }
                        )

                        is AppRoute.Money -> MoneyTabContent(
                            uiState = uiState,
                            onOpenCreateVoucher = { type -> createVoucherType = type; isCreateVoucherTypeLocked = true; isCreateVoucherOpen = true },
                            onLedgerClick = { ledger -> viewModel.loadLedgerStatement(ledger); viewModel.navigateTo(AppRoute.ChartOfAccounts) },
                            onAddBankUpiProfile = { isCreateBankUpiOpen = true },
                            onDeleteBankUpiProfile = { viewModel.deleteBankUpiProfile(it) },
                            onSaveDraftLines = { draft, lines -> viewModel.editVoucherDraftLines(draft, lines) },
                            onPostDraft = { viewModel.postVoucherDraft(it) },
                            onDiscardDraft = { viewModel.discardVoucherDraft(it) },
                            onSubmitMoneyVoucher = { type, date, debitId, creditId, amount, narration, ref, roundOff ->
                                viewModel.postQuickVoucherWithRoundOff(type, date, debitId, creditId, amount, narration, ref, roundOff)
                            },
                            onAddParty = { role -> createPartyRole = role },
                            onEditLedger = { ledger -> editingLedger = ledger; isCreateLedgerOpen = true },
                            moneyDeepLink = uiState.moneyDeepLink,
                            onMoneyDeepLinkConsumed = { viewModel.consumeMoneyDeepLink() }
                        )

                        is AppRoute.Parties -> PartiesScreen(
                            role = if (route.role == "SUPPLIER") PartyRole.SUPPLIER else PartyRole.CUSTOMER,
                            parties = uiState.parties,
                            ledgers = uiState.ledgers,
                            onAddParty = { createPartyRole = if (route.role == "SUPPLIER") PartyRole.SUPPLIER else PartyRole.CUSTOMER },
                            onPartyClick = { party -> onPartySelected(party, uiState.ledgers, viewModel) },
                            onEditLedger = { ledger -> editingLedger = ledger; isCreateLedgerOpen = true }
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
                            onOpenBusinessSetupWizard = { viewModel.navigateTo(AppRoute.ProfileWizard) }
                        )

                        is AppRoute.ProfileWizard -> com.example.accounting.presentation.features.profile.ProfileWizardScreen(
                            businessProfile = businessProfileSeed(uiState),
                            logoAssetLabel = uiState.businessProfile?.logoAssetId?.let { "Uploaded" },
                            signatureAssetLabel = uiState.businessProfile?.signatureAssetId?.let { "Uploaded" },
                            isPinCodeLookupInProgress = uiState.isPinCodeLookupInProgress,
                            pinCodeLookupResult = uiState.pinCodeLookupResult,
                            onLookupPinCode = { viewModel.lookupPinCode(it) },
                            onSave = { bn, ln, ct, addr, pin, city, state, country, ph, em, web, gst, pan, tan, udy, bank, acct, ifsc, branch, upi, terms ->
                                viewModel.updateBusinessProfileFull(bn, ln, ct, addr, pin, city, state, country, ph, em, web, gst, pan, tan, udy, bank, acct, ifsc, branch, upi, terms)
                            },
                            onPickLogo = {
                                logoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onPickSignature = {
                                signaturePickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onFinish = { viewModel.navigateBack() }
                        )

                        is AppRoute.Subscription -> SubscriptionScreen(
                            subscription = uiState.currentSubscription,
                            onUpgradeOrRenew = { plan, name, entitlements -> viewModel.upgradeOrRenewSubscription(plan, name, entitlements) }
                        )

                        is AppRoute.DataTools -> DataToolsScreen(
                            lastImportResult = uiState.lastImportResult,
                            lastImportRowOutcomes = uiState.lastImportRowOutcomes,
                            groups = uiState.groups,
                            onPickCsvFile = { pendingImportFormat = ImportFileFormat.CSV; openDocumentLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                            onPickJsonFile = { pendingImportFormat = ImportFileFormat.JSON; openDocumentLauncher.launch(arrayOf("application/json", "*/*")) },
                            onReviewAndCreateRow = { suggestion, type, groupIdOverride -> viewModel.reviewAndCreateImportRow(suggestion, type, groupIdOverride) },
                            onPickReceiptPhoto = {
                                receiptPhotoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )

                        is AppRoute.Search -> SearchScreen(
                            initialQuery = route.query,
                            parties = uiState.parties,
                            ledgers = uiState.ledgers,
                            vouchers = uiState.vouchers,
                            stockItems = uiState.stockItems,
                            onBack = { viewModel.navigateBack() },
                            onPartyClick = { party -> onPartySelected(party, uiState.ledgers, viewModel) },
                            onLedgerClick = { ledger -> viewModel.loadLedgerStatement(ledger); viewModel.navigateTo(AppRoute.ChartOfAccounts) },
                            onVoucherClick = { selectedVoucherDetail = it }
                        )

                        is AppRoute.About -> com.example.accounting.presentation.features.legal.AboutScreen()
                        is AppRoute.PrivacyPolicy -> com.example.accounting.presentation.features.legal.PrivacyPolicyScreen()
                        is AppRoute.TermsAndConditions -> com.example.accounting.presentation.features.legal.TermsAndConditionsScreen()
                        is AppRoute.Support -> com.example.accounting.presentation.features.legal.SupportScreen()
                    }
                }
            }
        }
    }
    }

    // Modal Dialogs
    if (isCreateVoucherOpen) {
        CreateVoucherDialog(
            ledgers = uiState.ledgers,
            groups = uiState.groups,
            stockItems = uiState.stockItems,
            vouchers = uiState.vouchers,
            outstandingInvoices = uiState.outstandingInvoices,
            companyStateCode = uiState.currentCompany?.stateCode ?: "",
            isInventoryEnabled = isInventoryEnabled(uiState),
            gstApplicable = uiState.currentCompany?.gstOperatingMode != com.example.accounting.domain.company.GstOperatingMode.ACCOUNT_ONLY,
            isServiceCompany = uiState.currentCompany?.businessType == com.example.accounting.domain.company.BusinessType.SERVICE,
            defaultVoucherType = createVoucherType,
            lockedType = isCreateVoucherTypeLocked,
            prefillFrom = uiState.pendingVoucherCorrection,
            prefillGstDetail = uiState.pendingVoucherCorrectionGstDetail,
            onDismiss = {
                isCreateVoucherOpen = false
                viewModel.clearOutstandingInvoices()
                viewModel.consumeVoucherCorrection()
            },
            onAddNewParty = { role -> createPartyRole = role },
            onAddNewBankLedger = {
                quickAddLedgerGroupId = uiState.groups.firstOrNull {
                    com.example.accounting.domain.accounting.StandardSystemGroups.isExactSystemGroup(it.groupId, com.example.accounting.domain.accounting.StandardSystemGroups.BANK_GROUP_ID)
                }?.groupId
                isCreateLedgerOpen = true
            },
            onAddNewTradeLedger = { isSale ->
                val wantGroupId = if (isSale) com.example.accounting.domain.accounting.StandardSystemGroups.SALES_GROUP_ID else com.example.accounting.domain.accounting.StandardSystemGroups.PURCHASE_GROUP_ID
                quickAddLedgerGroupId = uiState.groups.firstOrNull { it.groupId.startsWith("${wantGroupId}_") }?.groupId
                isCreateLedgerOpen = true
            },
            onPostQuickVoucher = { type, date, drLedger, crLedger, amount, narration, ref ->
                viewModel.postQuickVoucher(type, date, drLedger, crLedger, amount, narration, ref)
            },
            onSaveAsDraft = { type, date, drLedger, crLedger, amount, narration, ref ->
                viewModel.saveVoucherAsDraft(type, date, drLedger, crLedger, amount, narration, ref)
            },
            onPostSaleInvoice = { customer, sales, lines, date, ref, narration ->
                viewModel.postSaleInvoice(customer, sales, lines, date, ref, narration)
            },
            onPostPurchaseBill = { supplier, purchase, lines, date, ref, narration ->
                viewModel.postPurchaseBill(supplier, purchase, lines, date, ref, narration)
            },
            onPostAccountOnlySale = { customer, sales, amount, date, ref, narration, gstRate, hsnSac ->
                viewModel.postAccountOnlySale(customer, sales, amount, date, ref, narration, gstRate, hsnSac)
            },
            onPostAccountOnlyPurchase = { supplier, purchase, amount, date, ref, narration, gstRate, hsnSac ->
                viewModel.postAccountOnlyPurchase(supplier, purchase, amount, date, ref, narration, gstRate, hsnSac)
            },
            onPostCreditNote = { originalId, date, ref, narration ->
                viewModel.postCreditNote(originalId, date, ref, narration)
            },
            onPostDebitNote = { originalId, date, ref, narration ->
                viewModel.postDebitNote(originalId, date, ref, narration)
            },
            onPostSettlement = { type, date, drLedger, crLedger, amount, narration, ref, paymentMode, allocations ->
                viewModel.postQuickVoucher(type, date, drLedger, crLedger, amount, narration, ref, paymentMode, allocations)
            },
            onLoadOutstandingInvoices = { partyLedgerId -> viewModel.loadOutstandingInvoices(partyLedgerId) },
            onClearOutstandingInvoices = { viewModel.clearOutstandingInvoices() },
            onScanBarcode = {
                barcodeScanTarget = BarcodeScanTarget.PurchaseVoucher
                barcodePhotoPickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            scannedBarcodeValue = if (barcodeScanTarget == BarcodeScanTarget.PurchaseVoucher) uiState.lastBarcodeScan?.rawValue else null,
            scannedMatchedItemId = if (barcodeScanTarget == BarcodeScanTarget.PurchaseVoucher) uiState.lastBarcodeScan?.matchedStockItemId else null,
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
            onDismiss = { isCreateLedgerOpen = false; quickAddLedgerGroupId = null; editingLedger = null },
            onCreateLedger = { name, grpId, opBal, opType, gstin, pan, phone, email, addr, hsn, taxRate, bankName, bankAcctNo, bankIfsc, bankBranch, stateCode, pinCode ->
                viewModel.createLedger(name, grpId, opBal, opType, gstin, pan, phone, email, addr, hsn, taxRate, bankName, bankAcctNo, bankIfsc, bankBranch, stateCode, pinCode)
            },
            onUpdateLedger = { ledgerId, name, grpId, opBal, opType, gstin, pan, phone, email, addr, hsn, taxRate, bankName, bankAcctNo, bankIfsc, bankBranch, stateCode, pinCode ->
                viewModel.updateLedger(ledgerId, name, grpId, opBal, opType, gstin, pan, phone, email, addr, hsn, taxRate, bankName, bankAcctNo, bankIfsc, bankBranch, stateCode, pinCode)
            }
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
            onCreateItem = { name, sku, hsn, unit, gstRate, openingQty, openingRate ->
                viewModel.createStockItem(name, sku, hsn, unit, gstRate, openingQty, openingRate)
            }
        )
    }

    if (isCreateCompanyOpen) {
        CreateCompanyDialog(
            onDismiss = { isCreateCompanyOpen = false; editingCompany = null },
            isLookingUp = uiState.isPinCodeLookupInProgress,
            lookupResult = uiState.pinCodeLookupResult,
            onLookupPinCode = { viewModel.lookupPinCode(it) },
            existingCompany = editingCompany,
            onCreateCompany = { name, trade, gstin, pan, state, addr, email, phone, pinCode ->
                viewModel.createCompany(name, trade, gstin, pan, state, addr, email, phone, pinCode)
            },
            onUpdateCompany = { companyId, name, trade, gstin, pan, state, addr, email, phone, pinCode ->
                viewModel.updateCompany(companyId, name, trade, gstin, pan, state, addr, email, phone, pinCode)
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
            onCreateParty = { displayName, r, entityType, gstin, phone, email, address, stateCode, gstRegistrationStatus, pinCode, openingBalance, openingBalanceType ->
                viewModel.createParty(displayName, r, entityType, gstin, phone, email, address, stateCode, gstRegistrationStatus, pinCode, openingBalance, openingBalanceType)
            }
        )
    }

    if (isCreateBankUpiOpen) {
        CreateBankUpiProfileDialog(
            onDismiss = { isCreateBankUpiOpen = false },
            onCreate = { bankName, holder, accNum, ifsc, branch, upiId, upiPayee ->
                viewModel.createBankUpiProfile(bankName, holder, accNum, ifsc, branch, upiId, upiPayee)
            }
        )
    }

    uiState.lastBarcodeGeneration?.let { generated ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.clearBarcodeState() },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.clearBarcodeState() }) { Text("Close") }
            },
            title = { Text("Item Barcode") },
            text = { Text(generated.payload.rawValue, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
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

    selectedVoucherDetail?.let { voucher ->
        LaunchedEffect(voucher.voucherId) { viewModel.loadVoucherAttachments(voucher.voucherId) }
        val attachmentsForThisVoucher = if (uiState.voucherAttachmentsVoucherId == voucher.voucherId) uiState.voucherAttachments else emptyList()
        VoucherDetailDialog(
            voucher = voucher,
            onDismiss = { selectedVoucherDetail = null; viewModel.clearVoucherAttachments() },
            onDeleteVoucher = { v -> viewModel.deleteVoucherSafely(v.voucherId) },
            onCorrectVoucher = { v -> viewModel.correctVoucher(v) },
            onUpdateVoucherMetadata = { voucherId, narration, referenceNumber -> viewModel.updateVoucherMetadata(voucherId, narration, referenceNumber) },
            isInventoryEnabled = com.example.accounting.presentation.viewmodel.isInventoryEnabled(uiState),
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
            onRemoveAttachment = { attachment -> viewModel.removeVoucherAttachment(voucher.voucherId, attachment.referenceId) }
        )
    }
}

/** Tapping a Party navigates to its linked Ledger's statement - reuses the existing
 * `loadLedgerStatement`/`ChartOfAccountsScreen` machinery verbatim, never a second statement view
 * (Party is a thin Ledger extension, Phase 7A - this is the same underlying account). */
private fun onPartySelected(party: Party, ledgers: List<Ledger>, viewModel: AccountingViewModel) {
    val ledger = ledgers.find { it.ledgerId == party.ledgerId } ?: return
    viewModel.loadLedgerStatement(ledger)
    viewModel.navigateTo(AppRoute.ChartOfAccounts)
}

/** Copies a picked SAF/Photo-Picker [android.net.Uri] into an app-private cache [File] - both
 * `DataImportManagementService.parseFile`/`OcrSuggestionService.requestExtraction` need a real
 * [com.example.accounting.domain.rendering.DocumentAsset] backed by a real file path, matching the
 * existing `AccountingRepository.createDocumentAsset` convention used elsewhere in this app. */
private fun copyUriToCacheFile(context: android.content.Context, uri: android.net.Uri, fileName: String): File? {
    return try {
        val file = File(context.cacheDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file
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
