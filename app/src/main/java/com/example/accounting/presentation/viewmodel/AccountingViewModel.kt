package com.example.accounting.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.accounting.automation.scheduler.AccountingScheduler
import com.example.accounting.automation.scheduler.SchedulerPort
import com.example.accounting.automation.scheduler.work.WorkManagerSchedulerPort
import com.example.accounting.application.automation.RecurringVoucherManagementService
import com.example.accounting.application.banking.BankUpiProfileService
import com.example.accounting.application.banking.CashBankLedgerService
import com.example.accounting.application.document.DocumentPreviewService
import com.example.accounting.application.export.ExportManagementService
import com.example.accounting.application.imports.DataImportManagementService
import com.example.accounting.application.invoice.InvoiceManagementServiceImpl
import com.example.accounting.application.ledger.LedgerManagementService
import com.example.accounting.application.ocr.OcrSuggestionService
import com.example.accounting.application.party.PartyManagementService
import com.example.accounting.application.profession.BusinessProfessionService
import com.example.accounting.application.profile.ProfileApplicationService
import com.example.accounting.application.qrbarcode.QrBarcodeManagementService
import com.example.accounting.application.reports.HsnSacSummaryRow
import com.example.accounting.application.reports.ReportManagementService
import com.example.accounting.application.settlement.SettlementManagementService
import com.example.accounting.application.subscription.SubscriptionManagementService
import com.example.accounting.application.voucher.VoucherDraft
import com.example.accounting.application.voucher.VoucherDraftStatus
import com.example.accounting.application.voucher.VoucherManagementServiceImpl
import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.database.AppDatabase
import com.example.accounting.core.network.NetworkMonitor
import com.example.accounting.core.sync.OutboxProcessor
import com.example.accounting.data.dataimport.CsvJsonDataImportAdapter
import com.example.accounting.data.local.entity.OutboxSyncEntity
import com.example.accounting.data.qrbarcode.ZxingQrBarcodeAdapter
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.Branch
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.audit.AuditLog
import com.example.accounting.domain.banking.BankUpiProfile
import com.example.accounting.domain.banking.UpiMetadata
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.dataimport.ImportFileFormat
import com.example.accounting.domain.dataimport.ImportResult
import com.example.accounting.domain.dataimport.ImportRowSuggestion
import com.example.accounting.domain.dataimport.ImportSuggestionType
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.invoice.Invoice
import com.example.accounting.domain.ocr.OcrExtractionResult
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.qrbarcode.BarcodeGenerationResult
import com.example.accounting.domain.qrbarcode.BarcodeScanSuggestion
import com.example.accounting.domain.reports.BalanceSheetReport
import com.example.accounting.domain.reports.CashFlowReport
import com.example.accounting.domain.reports.GSTSummaryReport
import com.example.accounting.domain.reports.LedgerStatementReport
import com.example.accounting.domain.reports.OutstandingReport
import com.example.accounting.domain.reports.ProfitAndLossReport
import com.example.accounting.domain.reports.RatioAnalysisReport
import com.example.accounting.domain.reports.TrialBalanceReport
import com.example.accounting.domain.reports.toPdfData
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.DocumentAssetType
import com.example.accounting.domain.rendering.IndividualProfile
import com.example.accounting.domain.subscription.CompanySubscription
import com.example.accounting.domain.subscription.EntitlementFeature
import com.example.accounting.domain.subscription.SubscriptionPlanType
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstFilingPeriod
import com.example.accounting.domain.taxation.gst.GstLedgerIds
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.application.gstreturn.GstReturnManagementService
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstPeriod
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturn
import com.example.accounting.domain.taxation.gstreturn.GstReturnArtifact
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSection
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import com.example.accounting.domain.trading.OutstandingInvoice
import com.example.accounting.domain.trading.TradingLineInput
import com.example.accounting.domain.trading.TradingWorkflowEngine
import com.example.accounting.presentation.navigation.AppRoute
import com.example.accounting.presentation.navigation.HashRouter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Phase 7J UI: exactly 5 top-level destinations (Home/Sales/Purchases/Money/Reports), per the
 * user-supplied UX spec's bottom-navigation section. Every other area (Party, Items, Cash/Bank,
 * Outstanding, Profile, Import/OCR, Subscription, Search) is reached through one of these 5 or a
 * top-bar entry point - never a 6th+ tab. */
enum class NavigationTab {
    HOME,
    SALES,
    PURCHASES,
    MONEY,
    REPORTS
}

data class AccountingUiState(
    val selectedTab: NavigationTab = NavigationTab.HOME,
    val currentRoute: AppRoute = AppRoute.Dashboard,
    val companies: List<Company> = emptyList(),
    val currentCompany: Company? = null,
    val branches: List<Branch> = emptyList(),
    val financialYears: List<FinancialYear> = emptyList(),
    val currentFinancialYear: FinancialYear? = null,
    val periods: List<AccountingPeriod> = emptyList(),
    val groups: List<AccountGroup> = emptyList(),
    val ledgers: List<Ledger> = emptyList(),
    val vouchers: List<Voucher> = emptyList(),
    val auditLogs: List<AuditLog> = emptyList(),
    val outboxQueue: List<OutboxSyncEntity> = emptyList(),
    val pendingSyncCount: Int = 0,
    val isOnline: Boolean = true,
    val isSyncing: Boolean = false,
    val trialBalance: TrialBalanceReport? = null,
    val profitAndLoss: ProfitAndLossReport? = null,
    val incomeAndExpenditure: com.example.accounting.domain.reports.IncomeExpenditureReport? = null,
    val balanceSheet: BalanceSheetReport? = null,
    val gstSummary: GSTSummaryReport? = null,
    val selectedLedgerStatement: LedgerStatementReport? = null,
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val selectedVoucherTypeFilter: VoucherType? = null,
    val stockItems: List<StockItem> = emptyList(),
    val outstandingInvoices: List<OutstandingInvoice> = emptyList(),
    val gstFilingPeriods: List<GstFilingPeriod> = emptyList(),
    val isCloudSyncLoggedIn: Boolean = false,

    // ==== Phase 7J UI additions ====
    val parties: List<Party> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val bankUpiProfiles: List<BankUpiProfile> = emptyList(),
    val businessProfile: BusinessProfile? = null,
    val individualProfile: IndividualProfile? = null,
    val isPinCodeLookupInProgress: Boolean = false,
    val pinCodeLookupResult: com.example.accounting.domain.profile.PinCodeLookupResult? = null,
    /** Architecture correction (Voucher Correct workflow) - non-null for exactly the window between
     * "Correct Voucher" cancelling the original and the New Voucher dialog it opens (prefilled from
     * this) being dismissed (posted or abandoned) - the single signal [postQuickVoucher] reads to
     * attach `referenceVoucherId` to the corrected repost, and [MainAppScreen] reads to know to open
     * the dialog prefilled. Always cleared on that dialog's dismiss, success or not - never leaks
     * into an unrelated later voucher. */
    val pendingVoucherCorrection: com.example.accounting.domain.accounting.Voucher? = null,
    /** Extend-correction-to-all-types fix - (gstRatePercent, hsnSacCode) captured from the
     * cancelled original's own [com.example.accounting.domain.taxation.gst.GstTransaction] just
     * before cancellation (an account-only Sale/Purchase repost needs these fields but
     * `Voucher`/`JournalItem` don't carry them). `null` whenever the correction isn't a GST-bearing
     * account-only Sale/Purchase. Cleared alongside [pendingVoucherCorrection] in lockstep - never
     * meaningful on its own. */
    val pendingVoucherCorrectionGstDetail: Pair<Double, String>? = null,
    val currentSubscription: CompanySubscription? = null,
    val voucherDraftsPendingReview: List<VoucherDraft> = emptyList(),
    val outstandingReport: OutstandingReport? = null,
    val receivablesReport: OutstandingReport? = null,
    val payablesReport: OutstandingReport? = null,
    val cashFlowReport: CashFlowReport? = null,
    val ratioAnalysisReport: RatioAnalysisReport? = null,
    val hsnSacSummary: List<HsnSacSummaryRow> = emptyList(),
    val lastImportResult: ImportResult? = null,
    val lastImportRowOutcomes: Map<Int, String> = emptyMap(),
    val lastOcrExtraction: OcrExtractionResult? = null,
    val lastBarcodeGeneration: BarcodeGenerationResult? = null,
    val lastBarcodeScan: BarcodeScanSuggestion? = null,

    // ==== Rule 33: GST Return Dashboard & Filing Foundation ====
    val gstReturns: List<GstReturn> = emptyList(),
    val selectedGstReturn: GstReturn? = null,
    val selectedGstReturnSections: List<GstReturnSection> = emptyList(),
    val selectedGstReturnArtifacts: List<GstReturnArtifact> = emptyList(),
    /** Phase 8A, Part 2 - real [com.example.accounting.automation.notifications.AutomationNotification]s
     * already emitted by `GstReturnAutomationChecker` for the current company (category
     * `GST_RETURN_FILING`), most recent first. Never fabricated - empty when no automation has run
     * yet for this company. */
    val gstAutomationNotifications: List<com.example.accounting.automation.notifications.AutomationNotification> = emptyList(),
    /** GST Dashboard's own bottom nav (Dashboard/Invoices/Returns/Reports) lives in
     * [com.example.accounting.presentation.MainAppScreen]'s Scaffold, outside the wizard's own
     * local step state - this is the one-shot "jump to step X" channel between them, same
     * request/consume shape as [reportsDeepLink]/[consumeReportsDeepLink]. */
    val gstBottomNavRequest: String? = null,
    /** Which of the GST bottom nav's own tabs ("Dashboard"/"Returns"/"More") is actually showing
     * right now - unlike [gstBottomNavRequest] (one-shot, consumed to null immediately), this
     * persists so the nav bar's `selected` highlight stays correct after the wizard lands on that
     * step via ANY path, not just a tap on that same bottom nav tab (e.g. tapping the Dashboard's
     * own "Return History" card must highlight "Returns" too). */
    val gstActiveBottomTab: String = "Dashboard",

    // ==== Phase 7J-B.2 (Slice 2): Voucher Document Attachments ====
    /** Which voucher [voucherAttachments] currently holds - lets a screen avoid rendering a
     * previous voucher's stale attachment list for a single frame while a new one loads. */
    val voucherAttachmentsVoucherId: String? = null,
    val voucherAttachments: List<com.example.accounting.data.local.dao.VoucherAttachmentRow> = emptyList(),
    val isVoucherAttachmentsLoading: Boolean = false,
    val isAttachingDocument: Boolean = false,
    /** The `referenceId` currently being unlinked, or null - lets the UI show a per-item spinner
     * on exactly the row being removed rather than one section-wide flag. */
    val removingAttachmentReferenceId: String? = null,

    /** Dashboard-card-to-Report-Center deep link fix - a Dashboard card (Receivables/Payables/
     * Profit&Loss/GST Payable) must open its OWN authoritative report directly, never just the
     * generic Report Center category menu the user would then have to navigate through by hand.
     * Holds the exact [ReportsCenterScreen] report-menu key (e.g. "Outstanding Receivables") to
     * auto-select on next Report Center composition; cleared once consumed so it never re-fires
     * on an unrelated later visit to the Reports tab. */
    val reportsDeepLink: String? = null,

    /** Same one-shot request/consume shape as [reportsDeepLink] - the Dashboard's "Cash"/"Bank"
     * Business Snapshot cards must land directly on that specific ledger list, never on the Money
     * tab's own combined Cash+Bank landing page first. One of "Cash"/"Bank"; cleared once consumed. */
    val moneyDeepLink: String? = null
)

/**
 * Phase 7J UI: the single Account-Mode gating point every Items-related call site reads (CoA's
 * Items tab, Sales/Purchase item pickers, Home's "Add Item" quick action) - never re-derived
 * per-site (the exact kind of duplication `CashBankLedgerService`'s own doc comment already
 * flagged as a smell elsewhere in this codebase).
 */
fun isInventoryEnabled(uiState: AccountingUiState): Boolean =
    uiState.currentCompany?.accountingMode == com.example.accounting.domain.company.AccountingMode.ACCOUNT_WITH_INVENTORY

class AccountingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repository: AccountingRepository = AccountingRepository(
        dao = db.accountingDao(),
        db = db
    )

    val router = HashRouter()
    val networkMonitor = NetworkMonitor(application)
    private val outboxProcessor = OutboxProcessor(application, db.accountingDao())
    val scheduler = AccountingScheduler(db.accountingDao(), repository, outboxProcessor)
    private val schedulerPort: SchedulerPort = WorkManagerSchedulerPort(application)
    private val authRepository = com.example.accounting.core.network.AuthRepository(application)

    // ==== Phase 7J UI: application-service layer (Phase 7J-B, frozen) - every new screen calls
    // through these, never AccountingDao/AccountingRepository directly, except where a thin
    // facade doesn't yet exist for an already-established repository call (matching this
    // ViewModel's own pre-existing pattern, e.g. postQuickVoucher/postTradingDocument). ====
    private val partyService = PartyManagementService(repository)
    private val ledgerService = LedgerManagementService(repository)
    private val voucherDraftService = VoucherManagementServiceImpl(db.accountingDao(), repository)
    private val invoiceService = InvoiceManagementServiceImpl(repository)
    private val settlementService = SettlementManagementService(repository)
    private val reportService = ReportManagementService(repository)
    private val cashBankService = CashBankLedgerService(repository)
    private val bankUpiService = BankUpiProfileService(db.accountingDao())
    private val subscriptionService = SubscriptionManagementService(db.accountingDao())
    private val profileService = ProfileApplicationService(repository)
    private val documentPreviewService = DocumentPreviewService(repository)
    private val exportService = ExportManagementService(repository)
    private val recurringVoucherService = RecurringVoucherManagementService(repository)
    private val businessProfessionService = BusinessProfessionService()
    private val dataImportService = DataImportManagementService(CsvJsonDataImportAdapter(db.accountingDao()), repository)
    private val qrBarcodeService = QrBarcodeManagementService(repository, ZxingQrBarcodeAdapter(db.accountingDao()))
    // OcrIngestionAdapter is deliberately left unimplemented (Phase 7J-B) - null adapter here
    // means requestExtraction always fails gracefully, never a crash. Not a bug to fix.
    private val ocrService = OcrSuggestionService(null, db.accountingDao())
    private val gstReturnService = GstReturnManagementService(repository)
    // The one deliberate offline-first exception (domain/profile/PinCodeLookup.kt's own doc
    // comment) - a real, public, unauthenticated third-party API, never mocked/faked.
    private val pinCodeLookupAdapter: com.example.accounting.domain.profile.PinCodeLookupAdapter =
        com.example.accounting.data.network.PostalPinCodeLookupAdapter()
    // 13-point correctness pass, item 1 - a PIN code's City/State/Country never changes, so a
    // successful lookup this session is reused for every later dialog (Profile/Party/Ledger/
    // Company all share this one adapter+cache) instead of re-hitting the network. Only successful
    // results are cached - a failed/offline lookup is never remembered as "no address found",
    // since a later retry with connectivity back should get a real answer.
    private val pinCodeLookupCache = mutableMapOf<String, com.example.accounting.domain.profile.PinCodeLookupResult>()

    private val _uiState = MutableStateFlow(AccountingUiState(isCloudSyncLoggedIn = authRepository.isLoggedIn()))
    val uiState: StateFlow<AccountingUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>()
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var companyDataJob: Job? = null
    private var fyDataJob: Job? = null
    private var reportsRefreshJob: Job? = null

    init {
        // No auto-seeded default company (removed - see AccountingRepository.seedInitialDataForCompany's
        // own doc): a fresh install starts with zero companies, exactly as loadCompaniesAndInitialData
        // already handles (currentCompany stays null; AppTopBar renders "Select Company").
        viewModelScope.launch {
            loadCompaniesAndInitialData()
        }

        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }

        viewModelScope.launch {
            com.example.accounting.automation.notifications.AutomationNotificationCenter.notifications.collect { notif ->
                emitMessage("${notif.title}: ${notif.message}")
                // Phase 8A, Part 2 - keep the GST Dashboard's Automation card live if a background
                // job (WorkManager) emits a GSTR-1 notification while the app is open, not just on
                // next openGstReturn().
                if (notif.category == com.example.accounting.automation.notifications.NotificationCategory.GST_RETURN_FILING &&
                    notif.companyId == _uiState.value.currentCompany?.companyId
                ) {
                    refreshGstAutomationNotifications()
                }
            }
        }

        viewModelScope.launch {
            router.currentRoute.collect { route ->
                _uiState.update { it.copy(currentRoute = route) }
                when (route) {
                    is AppRoute.Dashboard -> _uiState.update { it.copy(selectedTab = NavigationTab.HOME) }
                    is AppRoute.DayBook -> _uiState.update { it.copy(selectedTab = NavigationTab.HOME) }
                    is AppRoute.ChartOfAccounts -> _uiState.update { it.copy(selectedTab = NavigationTab.HOME) }
                    is AppRoute.Sales -> _uiState.update { it.copy(selectedTab = NavigationTab.SALES) }
                    is AppRoute.Purchases -> _uiState.update { it.copy(selectedTab = NavigationTab.PURCHASES) }
                    is AppRoute.Money -> _uiState.update { it.copy(selectedTab = NavigationTab.MONEY) }
                    is AppRoute.Reports -> {
                        _uiState.update { it.copy(selectedTab = NavigationTab.REPORTS) }
                        refreshFinancialReports()
                        refreshReportsCenterExtras()
                    }
                    is AppRoute.GstDashboard -> {
                        _uiState.update { it.copy(selectedTab = NavigationTab.REPORTS) }
                        refreshFinancialReports()
                        refreshGstAutomationNotifications()
                    }
                    is AppRoute.SettingsAndSync -> { /* reached from Profile - keep whatever tab was active */ }
                    is AppRoute.LedgerStatement -> { /* keep whatever tab was active (Home or Reports) */ }
                    is AppRoute.Parties -> { /* keep whatever tab was active (Sales or Purchases) */ }
                    is AppRoute.Profile -> { /* top-bar entry point - keep whatever tab was active */ }
                    is AppRoute.ProfileWizard -> { /* reached from Profile - keep whatever tab was active */ }
                    is AppRoute.Subscription -> { loadSubscription() }
                    is AppRoute.DataTools -> { /* reached from Profile - keep whatever tab was active */ }
                    is AppRoute.Search -> { /* top-bar entry point - keep whatever tab was active */ }
                    is AppRoute.About, is AppRoute.PrivacyPolicy, is AppRoute.TermsAndConditions, is AppRoute.Support ->
                        { /* drawer entry point - keep whatever tab was active */ }
                }
            }
        }
    }

    private fun loadCompaniesAndInitialData() {
        viewModelScope.launch {
            repository.getCompanies().collect { companiesList ->
                val currentId = _uiState.value.currentCompany?.companyId
                val currentComp = companiesList.find { it.companyId == currentId }
                    ?: companiesList.find { it.isDefault }
                    ?: companiesList.firstOrNull()

                _uiState.update { it.copy(companies = companiesList, currentCompany = currentComp) }

                currentComp?.let { comp ->
                    observeCompanyData(comp.companyId)
                }
            }
        }
    }

    private fun observeCompanyData(companyId: String) {
        companyDataJob?.cancel()
        companyDataJob = viewModelScope.launch {
            launch {
                repository.getFinancialYears(companyId).collect { fyList ->
                    val currentFy = _uiState.value.currentFinancialYear
                        ?: fyList.find { it.isCurrent }
                        ?: fyList.firstOrNull()

                    _uiState.update { it.copy(financialYears = fyList, currentFinancialYear = currentFy) }

                    currentFy?.let { fy ->
                        observeFinancialYearData(companyId, fy.financialYearId)
                    }
                }
            }

            launch {
                repository.getBranches(companyId).collect { branchesList ->
                    _uiState.update { it.copy(branches = branchesList) }
                }
            }

            launch {
                repository.getGroups(companyId).collect { groupsList ->
                    _uiState.update { it.copy(groups = groupsList) }
                }
            }

            launch {
                repository.getLedgers(companyId).collect { ledgersList ->
                    _uiState.update { it.copy(ledgers = ledgersList) }
                    refreshFinancialReports()
                }
            }

            launch {
                repository.getAuditLogs(companyId).collect { logs ->
                    _uiState.update { it.copy(auditLogs = logs) }
                }
            }

            launch {
                repository.getOutboxQueue(companyId).collect { queue ->
                    _uiState.update { it.copy(outboxQueue = queue) }
                }
            }

            launch {
                repository.getPendingSyncCount(companyId).collect { count ->
                    _uiState.update { it.copy(pendingSyncCount = count) }
                }
            }

            launch {
                repository.getStockItems(companyId).collect { items ->
                    _uiState.update { it.copy(stockItems = items) }
                }
            }

            launch {
                repository.getGstFilingPeriods(companyId).collect { periods ->
                    _uiState.update { it.copy(gstFilingPeriods = periods) }
                }
            }

            launch {
                gstReturnService.listReturns(companyId).collect { returns ->
                    _uiState.update { it.copy(gstReturns = returns) }
                }
            }

            // ==== Phase 7J UI additions ====
            launch {
                partyService.getParties(companyId).collect { parties ->
                    _uiState.update { it.copy(parties = parties) }
                }
            }

            launch {
                repository.getInvoicesForCompany(companyId).collect { invoices ->
                    _uiState.update { it.copy(invoices = invoices) }
                }
            }

            launch {
                bankUpiService.list(companyId).collect { profiles ->
                    _uiState.update { it.copy(bankUpiProfiles = profiles) }
                }
            }

            launch {
                voucherDraftService.listDrafts(companyId, VoucherDraftStatus.PENDING_REVIEW).collect { drafts ->
                    _uiState.update { it.copy(voucherDraftsPendingReview = drafts) }
                }
            }

            launch {
                _uiState.update { it.copy(businessProfile = profileService.getBusinessProfile(companyId), individualProfile = profileService.getIndividualProfile(companyId)) }
            }
        }
    }

    private fun observeFinancialYearData(companyId: String, fyId: String) {
        schedulerPort.scheduleRecurringAutomation(companyId, fyId)
        fyDataJob?.cancel()
        fyDataJob = viewModelScope.launch {
            launch {
                repository.getPeriods(fyId).collect { periodList ->
                    _uiState.update { it.copy(periods = periodList) }
                }
            }

            launch {
                repository.getVouchers(companyId, fyId).collect { vouchersList ->
                    _uiState.update { it.copy(vouchers = vouchersList, isLoading = false) }
                    refreshFinancialReports()
                }
            }
        }
    }

    fun selectTab(tab: NavigationTab) {
        val destination = when (tab) {
            NavigationTab.HOME -> AppRoute.Dashboard
            NavigationTab.SALES -> AppRoute.Sales
            NavigationTab.PURCHASES -> AppRoute.Purchases
            NavigationTab.MONEY -> AppRoute.Money
            NavigationTab.REPORTS -> AppRoute.Reports
        }
        router.navigate(destination)
    }

    /** Dashboard-card-to-Report-Center deep link fix - jumps straight to [reportKey] (a
     * `ReportsCenterScreen` report-menu entry, e.g. "Outstanding Receivables", "Profit & Loss",
     * "GST Summary") instead of leaving the user on the generic category menu. */
    fun viewReport(reportKey: String) {
        _uiState.update { it.copy(reportsDeepLink = reportKey) }
        selectTab(NavigationTab.REPORTS)
    }

    /** Called once [ReportsCenterScreen] has consumed [AccountingUiState.reportsDeepLink] and
     * auto-selected it, so leaving/re-entering Reports afterward starts at the category menu
     * again instead of re-firing the same deep link forever. */
    fun consumeReportsDeepLink() {
        _uiState.update { it.copy(reportsDeepLink = null) }
    }

    /** Dashboard's "Cash"/"Bank" Business Snapshot cards - jumps straight to that ledger list on
     * the Money tab, never its combined Cash+Bank landing page. [target] is "Cash" or "Bank". */
    fun viewMoney(target: String) {
        _uiState.update { it.copy(moneyDeepLink = target) }
        selectTab(NavigationTab.MONEY)
    }

    /** Called once [MoneyTabContent] has consumed [AccountingUiState.moneyDeepLink] and jumped to
     * that sub-screen, so leaving/re-entering Money afterward starts at its own landing page again. */
    fun consumeMoneyDeepLink() {
        _uiState.update { it.copy(moneyDeepLink = null) }
    }

    /** The GST Dashboard's own bottom nav (Dashboard/Invoices/Returns/Reports/More) - see
     * [AccountingUiState.gstBottomNavRequest]'s own KDoc. [target] is one of "Dashboard",
     * "Returns", "More" - the wizard step names its own `LaunchedEffect` understands. */
    fun requestGstBottomNav(target: String) {
        _uiState.update { it.copy(gstBottomNavRequest = target) }
    }

    /** See [AccountingUiState.gstActiveBottomTab]'s own KDoc - called by the wizard itself
     * whenever its step lands on Dashboard/History/More, by any path. */
    fun setGstActiveBottomTab(tab: String) {
        _uiState.update { it.copy(gstActiveBottomTab = tab) }
    }

    fun consumeGstBottomNavRequest() {
        _uiState.update { it.copy(gstBottomNavRequest = null) }
    }

    fun navigateTo(route: AppRoute) {
        router.navigate(route)
    }

    fun navigateBack(): Boolean {
        return router.goBack()
    }

    fun setVoucherTypeFilter(type: VoucherType?) {
        _uiState.update { it.copy(selectedVoucherTypeFilter = type) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun switchCompany(company: Company) {
        _uiState.update { it.copy(currentCompany = company, isLoading = true) }
        observeCompanyData(company.companyId)
        emitMessage("Switched active company context to: ${company.name}")
        // Persist so app cold-start reopens this company instead of falling back to alphabetical
        // order (see AccountingRepository.setDefaultCompany) - fire-and-forget, never blocks the
        // UI switch above and never worth surfacing a failure toast for.
        viewModelScope.launch { repository.setDefaultCompany(company.companyId) }
    }

    fun switchFinancialYear(fy: FinancialYear) {
        val compId = _uiState.value.currentCompany?.companyId ?: return
        _uiState.update { it.copy(currentFinancialYear = fy) }
        observeFinancialYearData(compId, fy.financialYearId)
        emitMessage("Switched accounting boundary to: ${fy.fyCode}")
    }

    /** Real "add a year" capability - previously missing entirely, `createCompany()` only ever
     * seeds the one current FY. `getFinancialYears` is an observed Room Flow, so the new row
     * appears in [AccountingUiState.financialYears] on its own once inserted - no manual refresh. */
    fun addPreviousFinancialYear() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            when (val result = repository.addFinancialYear(comp.companyId, addPrevious = true)) {
                is AccountingResult.Success -> emitMessage("Added ${result.data.fyCode}.")
                is AccountingResult.Failure -> emitMessage("Could not add financial year: ${result.error.message}")
            }
        }
    }

    fun togglePeriodLock(period: AccountingPeriod) {
        viewModelScope.launch {
            val shouldLock = period.status == com.example.accounting.domain.financialyear.PeriodStatus.OPEN
            val result = repository.setPeriodLock(period.periodId, shouldLock, "SENIOR_CONTROLLER")
            if (result is AccountingResult.Success) {
                emitMessage("${if (shouldLock) "Locked" else "Unlocked"} accounting period '${period.name}'")
                val compId = _uiState.value.currentCompany?.companyId ?: ""
                scheduler.dispatchEvent(
                    com.example.accounting.automation.jobs.AutomationEvent.PeriodLocked(compId, period.name)
                )
            } else {
                emitMessage("Failed to update period lock: ${result.errorOrNull()?.message}")
            }
        }
    }

    /**
     * Switches the active company's [AccountingMode]/[BusinessType] - a capability toggle only
     * (Phase 4.5): never deletes or hides underlying vouchers/stock movements/GST records/audit
     * history, it only changes which report figures/UI capabilities are active. The `companies`
     * Flow collector already active in this ViewModel picks the change up automatically.
     */
    fun updateAccountingConfiguration(
        accountingMode: com.example.accounting.domain.company.AccountingMode? = null,
        businessType: com.example.accounting.domain.company.BusinessType? = null
    ) {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val result = repository.updateAccountingConfiguration(compId, accountingMode, businessType, "ADMIN")
            if (result is AccountingResult.Success) {
                emitMessage("Accounting configuration updated")
                refreshFinancialReports()
            } else {
                emitMessage("Failed to update accounting configuration: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** Optional Cloud Sync login (Phase 6, Priority 6.7/6.10) - sync-gated, never app-gated; the
     * app already works fully offline before this is ever called. */
    fun loginCloudSync(email: String, password: String) {
        viewModelScope.launch {
            val result = authRepository.login(email, password)
            if (result.isSuccess) {
                _uiState.update { it.copy(isCloudSyncLoggedIn = true) }
                emitMessage("Signed in - cloud sync enabled")
            } else {
                emitMessage("Sign-in failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
            }
        }
    }

    fun logoutCloudSync() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.update { it.copy(isCloudSyncLoggedIn = false) }
            emitMessage("Signed out of cloud sync")
        }
    }

    fun createCompany(
        name: String,
        tradeName: String,
        gstin: String,
        pan: String,
        stateCode: String,
        address: String,
        email: String,
        phone: String,
        pinCode: String = ""
    ) {
        viewModelScope.launch {
            val newCompId = "COMP_${UUID.randomUUID().toString().take(8).uppercase()}"
            val newCompany = Company(
                companyId = newCompId,
                name = name,
                tradeName = tradeName,
                gstin = Constants.normalizeTaxId(gstin),
                pan = Constants.normalizeTaxId(pan),
                stateCode = stateCode,
                // Audit fix - was always the hardcoded Company.stateName default ("Maharashtra")
                // regardless of the state code entered; now honestly derived from the real GST
                // state-code table, falling back to "" (never a guessed name) for an unrecognized code.
                stateName = Constants.GST_STATE_CODES[stateCode] ?: "",
                address = address,
                email = email,
                phone = phone,
                currency = "INR",
                isDefault = false,
                pinCode = pinCode
            )
            val result = repository.createCompany(newCompany)
            if (result is AccountingResult.Success) {
                switchCompany(newCompany)
                emitMessage("Created company '${newCompany.name}' with isolated Chart of Accounts")
            } else {
                emitMessage("Error creating company: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** Edit-Company fix - see [AccountingRepository.updateCompany]. `companies`/`currentCompany`
     * refresh on their own once this returns (`loadCompaniesAndInitialData`'s `getCompanies()`
     * collector is a live Room Flow over the `companies` table) - no manual state patch needed
     * here, same as every other DB write in this ViewModel. */
    fun updateCompany(
        companyId: String,
        name: String,
        tradeName: String,
        gstin: String,
        pan: String,
        stateCode: String,
        address: String,
        email: String,
        phone: String,
        pinCode: String = ""
    ) {
        viewModelScope.launch {
            val existing = _uiState.value.companies.find { it.companyId == companyId } ?: return@launch
            val updated = existing.copy(
                name = name, tradeName = tradeName,
                gstin = Constants.normalizeTaxId(gstin), pan = Constants.normalizeTaxId(pan),
                stateCode = stateCode, address = address, email = email, phone = phone, pinCode = pinCode
            )
            val result = repository.updateCompany(updated)
            if (result is AccountingResult.Success) {
                emitMessage("Updated company '${updated.name}'")
            } else {
                emitMessage("Error updating company: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** Full Company CRUD - Delete. See [AccountingRepository.deleteCompany] for the cascade
     * mechanics. `companies`/`currentCompany` self-heal via `loadCompaniesAndInitialData`'s live
     * Flow collector once this returns: it already falls back to another company whenever the
     * previously-current `companyId` no longer appears in the fresh list, so no manual
     * `switchCompany` call is needed here even when deleting the currently active company. */
    fun deleteCompany(companyId: String) {
        viewModelScope.launch {
            val target = _uiState.value.companies.find { it.companyId == companyId } ?: return@launch
            val result = repository.deleteCompany(companyId)
            if (result is AccountingResult.Success) {
                emitMessage("Deleted company '${target.name}' and all its data")
            } else {
                emitMessage("Error deleting company: ${result.errorOrNull()?.message}")
            }
        }
    }

    fun createBranch(
        code: String,
        name: String,
        gstin: String,
        stateCode: String,
        address: String,
        isHeadOffice: Boolean
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val branch = Branch(
                branchId = "BR_${UUID.randomUUID().toString().take(8).uppercase()}_${comp.companyId}",
                companyId = comp.companyId,
                code = code,
                name = name,
                gstin = gstin,
                stateCode = stateCode,
                address = address,
                isHeadOffice = isHeadOffice,
                isActive = true
            )
            val result = repository.createBranch(branch)
            if (result is AccountingResult.Success) {
                emitMessage("Branch '${branch.name}' (${branch.code}) created successfully")
            } else {
                emitMessage("Failed to create branch: ${result.errorOrNull()?.message}")
            }
        }
    }

    fun createLedger(
        name: String,
        groupId: String,
        openingBalance: Money,
        openingType: DrCr,
        gstin: String = "",
        pan: String = "",
        phone: String = "",
        email: String = "",
        address: String = "",
        hsnSac: String = "",
        defaultTaxRate: Double = 0.0,
        bankName: String = "",
        bankAccountNumber: String = "",
        bankIfsc: String = "",
        bankBranch: String = "",
        // Ledger Setup fix - a ledger's own State/PIN, optional at creation (matches createParty's
        // Rule 29/30 treatment exactly). Previously this silently defaulted to the COMPANY's own
        // stateCode, which is wrong the moment this ledger is used as a Sale/Purchase counterparty
        // in a different state - Place of Supply is resolved from THIS ledger's stateCode (Rule 29),
        // so a silent same-state guess would misclassify a real inter-state supply as intra-state
        // (CGST+SGST) instead of IGST with no visible error. Left blank when unset, exactly like a
        // fresh Party's ledger - the posting-time guard is what actually requires it, not creation.
        stateCode: String = "",
        pinCode: String = ""
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: run { emitMessage("Select a company first."); return@launch }
            val ledger = Ledger(
                ledgerId = "LED_${UUID.randomUUID().toString().take(8).uppercase()}_${comp.companyId}",
                companyId = comp.companyId,
                groupId = groupId,
                name = name,
                openingBalance = openingBalance,
                openingBalanceType = openingType,
                currentBalance = openingBalance,
                currentBalanceType = openingType,
                gstin = gstin,
                pan = pan,
                stateCode = stateCode,
                phone = phone,
                email = email,
                address = address,
                pinCode = pinCode,
                hsnSacCode = hsnSac,
                defaultTaxRate = defaultTaxRate,
                bankName = bankName,
                bankAccountNumber = bankAccountNumber,
                bankIfsc = bankIfsc,
                bankBranch = bankBranch
            )
            val result = repository.createLedger(ledger)
            if (result is AccountingResult.Success) {
                emitMessage("Ledger account '${ledger.name}' created successfully")
                scheduler.dispatchEvent(
                    com.example.accounting.automation.jobs.AutomationEvent.LedgerCreated(comp.companyId, ledger)
                )
            } else {
                emitMessage("Failed to create ledger: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** 13-point correctness pass, item 8 (Editable Ledgers) - [CreateLedgerDialog] in edit mode
     * calls this instead of [createLedger]. Opening Balance is only actually applied by
     * [com.example.accounting.data.repository.AccountingRepository.updateLedger] when the ledger
     * still has zero posted entries (silently preserved otherwise); Current Balance is never
     * settable from here at all - the repository always preserves it regardless of what's passed. */
    fun updateLedger(
        ledgerId: String,
        name: String,
        groupId: String,
        openingBalance: Money,
        openingType: DrCr,
        gstin: String = "",
        pan: String = "",
        phone: String = "",
        email: String = "",
        address: String = "",
        hsnSac: String = "",
        defaultTaxRate: Double = 0.0,
        bankName: String = "",
        bankAccountNumber: String = "",
        bankIfsc: String = "",
        bankBranch: String = "",
        stateCode: String = "",
        pinCode: String = ""
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: run { emitMessage("Select a company first."); return@launch }
            val ledger = Ledger(
                ledgerId = ledgerId,
                companyId = comp.companyId,
                groupId = groupId,
                name = name,
                openingBalance = openingBalance,
                openingBalanceType = openingType,
                gstin = gstin,
                pan = pan,
                stateCode = stateCode,
                phone = phone,
                email = email,
                address = address,
                pinCode = pinCode,
                hsnSacCode = hsnSac,
                defaultTaxRate = defaultTaxRate,
                bankName = bankName,
                bankAccountNumber = bankAccountNumber,
                bankIfsc = bankIfsc,
                bankBranch = bankBranch
            )
            val result = repository.updateLedger(ledger)
            if (result is AccountingResult.Success) {
                emitMessage("Ledger '${ledger.name}' updated")
            } else {
                emitMessage("Failed to update ledger: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** Architecture correction (real Group hierarchy) - the UI entry point for
     * [com.example.accounting.data.repository.AccountingRepository.createGroup], which previously
     * existed correctly but had no caller anywhere. [parentGroupId] must be an existing Group's ID
     * (System or User) - the new Group always inherits that parent's [com.example.accounting.domain.accounting.PrimaryGroup],
     * never a separately-chosen one, so the hierarchy can never end up internally inconsistent. */
    fun createGroup(name: String, parentGroupId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: run { emitMessage("Select a company first."); return@launch }
            val parent = _uiState.value.groups.firstOrNull { it.groupId == parentGroupId }
            if (parent == null) {
                emitMessage("Select a parent group.")
                return@launch
            }
            val group = com.example.accounting.domain.accounting.AccountGroup(
                groupId = "", companyId = comp.companyId, name = name,
                primaryGroup = parent.primaryGroup, parentGroupId = parent.groupId, isSystem = false
            )
            val result = repository.createGroup(group)
            if (result is AccountingResult.Success) {
                emitMessage("Group '$name' created under '${parent.name}'")
            } else {
                emitMessage("Failed to create group: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** Creates a stock item with its HSN/SAC and GST rate on file - the prerequisite for
     * item-driven GST (Phase 5, Priority 3): the Sale/Purchase item picker reads these facts back
     * instead of the user typing/picking a rate freely. */
    fun createStockItem(
        name: String,
        sku: String,
        hsnCode: String,
        unit: String,
        gstRatePercent: Double,
        openingQuantity: Double,
        openingRate: Money
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val item = StockItem(
                itemId = "", companyId = comp.companyId, name = name, sku = sku, hsnCode = hsnCode, unit = unit,
                gstRatePercent = gstRatePercent,
                openingQuantity = com.example.accounting.core.common.Quantity.fromDouble(openingQuantity, unit),
                openingRate = openingRate
            )
            val result = repository.createStockItem(item)
            if (result is AccountingResult.Success) {
                emitMessage("Item '${item.name}' created successfully")
            } else {
                emitMessage("Failed to create item: ${result.errorOrNull()?.message}")
            }
        }
    }

    fun deleteLedgerSafely(ledgerId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val result = repository.deleteLedgerSafely(comp.companyId, ledgerId, "SENIOR_ACCOUNTANT")
            if (result is AccountingResult.Success) {
                emitMessage("Ledger deleted successfully")
            } else {
                emitMessage("Delete rejected: ${result.errorOrNull()?.message}")
            }
        }
    }

    fun deleteVoucherSafely(voucherId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val idempotencyKey = UUID.randomUUID().toString()
            val result = repository.deleteVoucherSafely(comp.companyId, fy.financialYearId, voucherId, idempotencyKey, "SENIOR_ACCOUNTANT")
            if (result is AccountingResult.Success) {
                emitMessage("Voucher cancelled and ledger balances reversed successfully")
                scheduler.dispatchEvent(
                    com.example.accounting.automation.jobs.AutomationEvent.VoucherDeleted(comp.companyId, voucherId, voucherId)
                )
                refreshFinancialReports()
            } else {
                emitMessage("Voucher deletion failed: ${result.errorOrNull()?.message}")
            }
        }
    }

    /** True in-place edit (live-device audit finding) - narration/reference number only, see
     * [AccountingRepository.updateVoucherMetadata]'s doc comment for why amount/ledger/date stay
     * on the reversal-based [correctVoucher] path instead. */
    fun updateVoucherMetadata(voucherId: String, narration: String, referenceNumber: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val result = repository.updateVoucherMetadata(comp.companyId, fy.financialYearId, voucherId, narration, referenceNumber)
            if (result is AccountingResult.Success) {
                emitMessage("Voucher updated successfully")
                refreshFinancialReports()
            } else {
                emitMessage("Voucher update failed: ${result.errorOrNull()?.message}")
            }
        }
    }

    /**
     * Architecture correction (Voucher Correct workflow) - "Correct Voucher" cancels the original
     * via the exact same guarded, append-only [com.example.accounting.data.repository.AccountingRepository.deleteVoucherSafely]
     * as "Delete & Reverse" (same period-lock/atomicity checks, no new bypass), then - only on
     * success - signals the UI (via [AccountingUiState.pendingVoucherCorrection]) to reopen New
     * Voucher prefilled from the original so the user can fix and repost. This is cancel-then-
     * recreate, never an in-place mutation of posted history (Rule 12).
     *
     * Extend-correction-to-all-types fix - originally scoped to only the flat 2-line voucher
     * shapes [postQuickVoucher] builds directly (Receipt/Payment/Contra/Journal); now also covers
     * account-only Sale/Purchase (gated by [com.example.accounting.presentation.viewmodel.isInventoryEnabled]
     * in [VoucherDetailDialog]'s own eligibility check - an inventory-tracked Sale/Purchase still
     * isn't offered "Correct Voucher" here, since reconstructing its stock lines/running-average-cost
     * is a materially higher-risk problem deliberately left for a dedicated pass). For a Sale/
     * Purchase, the original's GST rate/HSN (not carried on `Voucher`/`JournalItem` itself) is
     * read from its [com.example.accounting.domain.taxation.gst.GstTransaction] *before*
     * cancellation reverses it, so [CreateVoucherDialog]'s account-only prefill can restore it.
     * Credit/Debit Notes keep their existing, separate correction mechanism (issuing another note)
     * unchanged - never routed through here.
     */
    fun correctVoucher(voucher: com.example.accounting.domain.accounting.Voucher) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val gstDetail = if (voucher.voucherType == VoucherType.SALES || voucher.voucherType == VoucherType.PURCHASE) {
                repository.getGstTransactionsForVoucher(voucher.voucherId).firstOrNull()?.let { it.gstRatePercent to it.hsnSacCode }
            } else null
            val idempotencyKey = UUID.randomUUID().toString()
            val result = repository.deleteVoucherSafely(comp.companyId, fy.financialYearId, voucher.voucherId, idempotencyKey, "SENIOR_ACCOUNTANT")
            if (result is AccountingResult.Success) {
                scheduler.dispatchEvent(
                    com.example.accounting.automation.jobs.AutomationEvent.VoucherDeleted(comp.companyId, voucher.voucherId, voucher.voucherId)
                )
                refreshFinancialReports()
                _uiState.update { it.copy(pendingVoucherCorrection = voucher, pendingVoucherCorrectionGstDetail = gstDetail) }
                emitMessage("Original voucher cancelled - review and repost the corrected version")
            } else {
                emitMessage("Could not start correction: ${result.errorOrNull()?.message}")
            }
        }
    }

    fun consumeVoucherCorrection() {
        _uiState.update { it.copy(pendingVoucherCorrection = null, pendingVoucherCorrectionGstDetail = null) }
    }

    fun postQuickVoucher(
        voucherType: VoucherType,
        date: LocalDate,
        debitLedgerId: String,
        creditLedgerId: String,
        amount: Money,
        narration: String,
        refNumber: String = "",
        paymentMode: String = "",
        /** Receipt/Payment invoice allocation (Phase 5, Priority 2) - which outstanding invoice(s)
         * this settlement pays off, and how much. Empty for Contra/Journal/an unallocated advance. */
        allocations: List<Pair<String, Money>> = emptyList()
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val ledgersMap = _uiState.value.ledgers.associateBy { it.ledgerId }

            val debitLedger = ledgersMap[debitLedgerId]
            val creditLedger = ledgersMap[creditLedgerId]

            if (debitLedger == null || creditLedger == null) {
                emitMessage("Validation error: Invalid debit or credit ledger selected.")
                return@launch
            }

            val voucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
            val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, fy.financialYearId, voucherType)

            val items = listOf(
                JournalItem(
                    itemId = UUID.randomUUID().toString(),
                    voucherId = voucherId,
                    companyId = comp.companyId,
                    financialYearId = fy.financialYearId,
                    ledgerId = debitLedgerId,
                    ledgerName = debitLedger.name,
                    type = DrCr.DEBIT,
                    amount = amount,
                    narration = narration,
                    lineOrder = 1
                ),
                JournalItem(
                    itemId = UUID.randomUUID().toString(),
                    voucherId = voucherId,
                    companyId = comp.companyId,
                    financialYearId = fy.financialYearId,
                    ledgerId = creditLedgerId,
                    ledgerName = creditLedger.name,
                    type = DrCr.CREDIT,
                    amount = amount,
                    narration = narration,
                    lineOrder = 2
                )
            )

            // Architecture correction (Voucher Correct workflow) - if this post follows a
            // "Correct Voucher" cancellation of a same-type original, link the two via the
            // existing, generic `referenceVoucherId` field (previously only ever set by the
            // Credit/Debit Note flow - nothing about it is Note-specific, confirmed no other
            // posting logic branches on it). A mismatched type (user switched to a different
            // voucher type before reposting) never attaches a misleading link.
            val correction = _uiState.value.pendingVoucherCorrection?.takeIf { it.voucherType == voucherType }

            val voucher = Voucher(
                voucherId = voucherId,
                companyId = comp.companyId,
                financialYearId = fy.financialYearId,
                voucherNumber = voucherNumber,
                voucherType = voucherType,
                date = date,
                referenceNumber = refNumber,
                narration = narration,
                totalAmount = amount,
                items = items,
                createdBy = "SENIOR_ACCOUNTANT",
                paymentMode = paymentMode,
                referenceVoucherId = correction?.voucherId
            )

            val result = repository.postVoucher(voucher)
            when (result) {
                is AccountingResult.Success -> {
                    emitMessage("Voucher $voucherNumber posted successfully (${amount.formatPlain()})")
                    scheduler.dispatchEvent(
                        com.example.accounting.automation.jobs.AutomationEvent.VoucherPosted(comp.companyId, voucher)
                    )
                    // Settlement allocation (Priority 2) - only meaningful for Receipt/Payment, and
                    // only attempted when the caller actually supplied allocations; no GST is
                    // computed or reachable anywhere in this function.
                    if (allocations.isNotEmpty() && (voucherType == VoucherType.RECEIPT || voucherType == VoucherType.PAYMENT)) {
                        val unallocated = amount - allocations.fold(Money.ZERO) { acc, (_, amt) -> acc + amt }
                        val allocResult = repository.allocateSettlement(comp.companyId, fy.financialYearId, voucherId, allocations, unallocated)
                        if (allocResult is AccountingResult.Failure) {
                            emitMessage("Voucher posted, but allocation failed: ${allocResult.error.message}")
                        }
                    }
                }
                is AccountingResult.Failure -> {
                    emitMessage("Posting rejected: ${result.error.message}")
                }
            }
        }
    }

    /**
     * Phase 7J UI: Receive Money/Pay Money/Transfer with an opt-in Round Off toggle - a new,
     * narrow addition, NOT a second posting mechanism. When [applyRoundOff] is false, or the
     * rounding delta is zero, this is byte-for-byte the existing [postQuickVoucher] path. When
     * true and the delta is non-zero: the debit line is posted at the rounded whole-rupee amount
     * (the actual cash/bank movement), the credit line keeps the exact amount entered (the precise
     * settlement), and the difference posts to the existing, protected Round Off ledger - resolved
     * via the existing `AccountingRepository.ensureRoundOffLedgerExists`/`resolveRoundOffLedgerRef`
     * (the same functions `TradingWorkflowEngine`'s caller already uses for Sale/Purchase), never a
     * new ledger, never a hardcoded id. Still posts through the same, single, unmodified
     * `AccountingRepository.postVoucher` -> `VoucherPostingEngine` path as every other voucher -
     * that engine independently re-validates the double-entry balance regardless of what's
     * computed here.
     */
    fun postQuickVoucherWithRoundOff(
        voucherType: VoucherType,
        date: LocalDate,
        debitLedgerId: String,
        creditLedgerId: String,
        amount: Money,
        narration: String,
        refNumber: String = "",
        applyRoundOff: Boolean = false
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val ledgersMap = _uiState.value.ledgers.associateBy { it.ledgerId }
            val debitLedger = ledgersMap[debitLedgerId]
            val creditLedger = ledgersMap[creditLedgerId]

            if (debitLedger == null || creditLedger == null) {
                emitMessage("Validation error: Invalid account selected.")
                return@launch
            }

            val roundOff = if (applyRoundOff) {
                com.example.accounting.domain.accounting.RoundOffEngine.roundInvoiceTotal(amount)
            } else null

            if (roundOff == null || roundOff.roundOffAmount.paise == 0L) {
                postQuickVoucher(voucherType, date, debitLedgerId, creditLedgerId, amount, narration, refNumber)
                return@launch
            }

            val voucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
            val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, fy.financialYearId, voucherType)

            repository.ensureRoundOffLedgerExists(comp.companyId)
            val roundOffRef = repository.resolveRoundOffLedgerRef(comp.companyId)
            val roundOffType = if (roundOff.roundOffAmount.isPositive) DrCr.CREDIT else DrCr.DEBIT

            val items = listOf(
                JournalItem(
                    itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = comp.companyId,
                    financialYearId = fy.financialYearId, ledgerId = debitLedgerId, ledgerName = debitLedger.name,
                    type = DrCr.DEBIT, amount = roundOff.roundedTotal, narration = narration, lineOrder = 1
                ),
                JournalItem(
                    itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = comp.companyId,
                    financialYearId = fy.financialYearId, ledgerId = creditLedgerId, ledgerName = creditLedger.name,
                    type = DrCr.CREDIT, amount = roundOff.rawTotal, narration = narration, lineOrder = 2
                ),
                JournalItem(
                    itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = comp.companyId,
                    financialYearId = fy.financialYearId, ledgerId = roundOffRef.ledgerId, ledgerName = roundOffRef.name,
                    type = roundOffType, amount = roundOff.roundOffAmount.abs(), narration = "Round off adjustment", lineOrder = 3
                )
            )

            val voucher = Voucher(
                voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                voucherNumber = voucherNumber, voucherType = voucherType, date = date, referenceNumber = refNumber,
                narration = narration, totalAmount = roundOff.roundedTotal, items = items, createdBy = "SENIOR_ACCOUNTANT"
            )

            when (val result = repository.postVoucher(voucher)) {
                is AccountingResult.Success -> emitMessage(
                    "Voucher $voucherNumber posted successfully (${roundOff.roundedTotal.formatPlain()}, rounded off ${roundOff.roundOffAmount.abs().formatPlain()})"
                )
                is AccountingResult.Failure -> emitMessage("Posting rejected: ${result.error.message}")
            }
        }
    }

    /** Loads every outstanding Sale/Purchase for [partyLedgerId] into [AccountingUiState.outstandingInvoices]
     * so the Receipt/Payment form can offer them for allocation (Priority 2). */
    fun loadOutstandingInvoices(partyLedgerId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val invoices = repository.getOutstandingInvoices(comp.companyId, partyLedgerId)
            _uiState.update { it.copy(outstandingInvoices = invoices) }
        }
    }

    fun clearOutstandingInvoices() {
        _uiState.update { it.copy(outstandingInvoices = emptyList()) }
    }

    /** One item line as entered in the Sale/Purchase item picker - just the facts the user
     * actually chooses (item, quantity, rate); HSN/GST rate are looked up from the item itself.
     * [supplyNature] (UI-06) is the line's Tax Treatment - defaults to NORMAL (Taxable), matching
     * every line's behavior before this field existed. */
    data class TradingLineForm(
        val itemId: String,
        val quantity: Double,
        val rate: Money,
        val supplyNature: GstSupplyNature = GstSupplyNature.NORMAL,
        /** Rule 31 (Purchase/RCM Foundation) - defaults to FORWARD_CHARGE, matching every line's
         * behavior before this field existed. Only meaningful on a Purchase line. */
        val chargeType: com.example.accounting.domain.taxation.gst.GstChargeType = com.example.accounting.domain.taxation.gst.GstChargeType.FORWARD_CHARGE
    )

    fun postSaleInvoice(
        customerLedgerId: String,
        salesLedgerId: String,
        lines: List<TradingLineForm>,
        date: LocalDate,
        referenceNumber: String,
        narration: String
    ) = postTradingDocument(
        isSale = true, partyLedgerId = customerLedgerId, tradeLedgerId = salesLedgerId,
        lines = lines, date = date, referenceNumber = referenceNumber, narration = narration
    )

    fun postPurchaseBill(
        supplierLedgerId: String,
        purchaseLedgerId: String,
        lines: List<TradingLineForm>,
        date: LocalDate,
        referenceNumber: String,
        narration: String
    ) = postTradingDocument(
        isSale = false, partyLedgerId = supplierLedgerId, tradeLedgerId = purchaseLedgerId,
        lines = lines, date = date, referenceNumber = referenceNumber, narration = narration
    )

    /**
     * Item-line Sale/Purchase flow (Phase 5, Priority 1): resolves each line's GST rate/HSN from
     * the selected [StockItem] (never a hardcoded rate), delegates all tax math, stock-line
     * construction, GST-transaction-fact construction, and Round Off to [TradingWorkflowEngine] -
     * the ViewModel only resolves ledgers/items and assembles the resulting [Voucher].
     */
    private fun postTradingDocument(
        isSale: Boolean,
        partyLedgerId: String,
        tradeLedgerId: String,
        lines: List<TradingLineForm>,
        date: LocalDate,
        referenceNumber: String,
        narration: String
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            if (lines.isEmpty()) {
                emitMessage("Validation error: Add at least one item line.")
                return@launch
            }
            // Rule 31 (Purchase/RCM Foundation): graceful, user-facing pre-checks - the matching
            // authoritative require() backstops live in TradingWorkflowEngine.build() itself.
            if (isSale && lines.any { it.chargeType == com.example.accounting.domain.taxation.gst.GstChargeType.REVERSE_CHARGE }) {
                emitMessage("Validation error: Reverse charge is not applicable to a Sale.")
                return@launch
            }
            if (lines.any { it.chargeType == com.example.accounting.domain.taxation.gst.GstChargeType.REVERSE_CHARGE && it.supplyNature != GstSupplyNature.NORMAL }) {
                emitMessage("Validation error: Reverse charge requires a Taxable line - it cannot be combined with Zero Rated/Exempt/Nil Rated.")
                return@launch
            }

            // Backfill GST duty ledgers and the Round Off ledger for companies seeded before this
            // structure existed - idempotent, safe every call.
            repository.ensureGstLedgersExist(comp.companyId)
            repository.ensureRoundOffLedgerExists(comp.companyId)

            val ledgers = repository.getLedgers(comp.companyId).first().associateBy { it.ledgerId }
            val stockItems = _uiState.value.stockItems.associateBy { it.itemId }

            val partyLedger = ledgers[partyLedgerId]
            val tradeLedger = ledgers[tradeLedgerId]
            if (partyLedger == null || tradeLedger == null) {
                emitMessage("Validation error: Invalid ${if (isSale) "customer" else "supplier"} or ${if (isSale) "sales" else "purchase"} ledger selected.")
                return@launch
            }

            val tradingLines = lines.map { line ->
                val item = stockItems[line.itemId]
                    ?: return@launch emitMessage("Validation error: Selected item could not be found.")
                TradingLineInput(
                    itemId = item.itemId, itemName = item.name, hsnSacCode = item.hsnCode,
                    quantity = com.example.accounting.core.common.Quantity.fromDouble(line.quantity, item.unit),
                    rate = line.rate, gstRatePercent = item.gstRatePercent, supplyNature = line.supplyNature,
                    chargeType = line.chargeType
                )
            }

            val voucherType = if (isSale) VoucherType.SALES else VoucherType.PURCHASE
            val voucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
            val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, fy.financialYearId, voucherType)
            val gstLedgers = repository.resolveGstLedgerRefs(comp.companyId)
            val roundOffRef = repository.resolveRoundOffLedgerRef(comp.companyId)
            // Place of Supply (Priority 3 / Rule 29): must come from the party's own real state -
            // never defaulted to the company's own state. Falling back to the company's state would
            // silently force every such Sale/Purchase to resolve as INTRA_STATE regardless of the
            // party's actual location, which is a wrong GST classification with no visible error.
            if (partyLedger.stateCode.isBlank()) {
                emitMessage(
                    "Validation error: Set a State for ${if (isSale) "customer" else "supplier"} " +
                        "'${partyLedger.name}' before posting - Place of Supply cannot be determined."
                )
                return@launch
            }
            val placeOfSupply = partyLedger.stateCode

            val engineResult = if (isSale) {
                TradingWorkflowEngine.buildSale(
                    voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                    customerLedgerId = partyLedgerId, customerName = partyLedger.name, customerGstin = partyLedger.gstin,
                    salesLedgerId = tradeLedgerId, salesLedgerName = tradeLedger.name,
                    companyStateCode = comp.stateCode, placeOfSupply = placeOfSupply,
                    lines = tradingLines, gstLedgers = gstLedgers,
                    roundOffLedgerId = roundOffRef.ledgerId, roundOffLedgerName = roundOffRef.name
                )
            } else {
                TradingWorkflowEngine.buildPurchase(
                    voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                    supplierLedgerId = partyLedgerId, supplierName = partyLedger.name, supplierGstin = partyLedger.gstin,
                    purchaseLedgerId = tradeLedgerId, purchaseLedgerName = tradeLedger.name,
                    companyStateCode = comp.stateCode, placeOfSupply = placeOfSupply,
                    lines = tradingLines, gstLedgers = gstLedgers,
                    roundOffLedgerId = roundOffRef.ledgerId, roundOffLedgerName = roundOffRef.name
                )
            }

            val voucher = Voucher(
                voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                voucherNumber = voucherNumber, voucherType = voucherType, date = date,
                referenceNumber = referenceNumber, narration = narration.ifBlank { "Being ${voucherType.displayName.lowercase()}" },
                totalAmount = engineResult.totalAmount, items = engineResult.journalItems,
                createdBy = if (isSale) "SALES_BILLING_DESK" else "PURCHASE_DESK",
                partyGstin = partyLedger.gstin, isGstApplicable = true
            )

            val result = repository.postVoucher(voucher, stockLines = engineResult.stockLines, gstTransactions = engineResult.gstTransactions)
            when (result) {
                is AccountingResult.Success -> {
                    emitMessage("${voucherType.displayName} $voucherNumber posted successfully: ${engineResult.totalAmount.formatPlain()}")
                    scheduler.dispatchEvent(
                        com.example.accounting.automation.jobs.AutomationEvent.VoucherPosted(comp.companyId, voucher)
                    )
                }
                is AccountingResult.Failure -> {
                    emitMessage("${voucherType.displayName} posting rejected: ${result.error.message}")
                }
            }
        }
    }

    /**
     * D1a (Company Mode + Account-Only Sale/Purchase) - for a company whose [AccountingMode] is
     * `ACCOUNT_ONLY` (no inventory tracking): Party ledger + Sales ledger + a single amount, no
     * Item/Quantity/Rate, no stock movement. Uses the exact same canonical path as
     * [postTradingDocument] - [TradingWorkflowEngine] -> [AccountingRepository.postVoucher].
     *
     * Accounting-flow audit fix: [gstRatePercent] (0.0 = no GST, matching this function's original
     * behavior byte-for-byte) lets a GST-registered Account-Only company still charge/claim GST on
     * a Sale/Purchase - Inventory Mode must only gate stock/COGS, never whether GST accounting
     * exists (this was the actual bug: GST was previously gated on `isInventoryEnabled`, an
     * unrelated setting). When non-zero, this reuses [TradingWorkflowEngine.buildSale]/[buildPurchase]
     * with `trackInventory = false` and a single synthetic [TradingLineForm] line - the exact same
     * [GstCalculationEngine]/tax-ledger-posting code the item-driven path already uses, just
     * skipping [VoucherStockLine] generation. Place of Supply is checked (Rule 29) only when GST is
     * actually being computed, mirroring [postTradingDocument]'s own check.
     */
    fun postAccountOnlySale(
        customerLedgerId: String,
        salesLedgerId: String,
        amount: Money,
        date: LocalDate,
        referenceNumber: String,
        narration: String,
        gstRatePercent: Double = 0.0,
        hsnSac: String = "",
        supplyNature: GstSupplyNature = GstSupplyNature.NORMAL
    ) = postAccountOnlyTradingDocument(
        isSale = true, partyLedgerId = customerLedgerId, tradeLedgerId = salesLedgerId,
        amount = amount, date = date, referenceNumber = referenceNumber, narration = narration,
        gstRatePercent = gstRatePercent, hsnSac = hsnSac, supplyNature = supplyNature
    )

    /** D1a - Purchase counterpart of [postAccountOnlySale]; see its doc comment. */
    fun postAccountOnlyPurchase(
        supplierLedgerId: String,
        purchaseLedgerId: String,
        amount: Money,
        date: LocalDate,
        referenceNumber: String,
        narration: String,
        gstRatePercent: Double = 0.0,
        hsnSac: String = "",
        supplyNature: GstSupplyNature = GstSupplyNature.NORMAL
    ) = postAccountOnlyTradingDocument(
        isSale = false, partyLedgerId = supplierLedgerId, tradeLedgerId = purchaseLedgerId,
        amount = amount, date = date, referenceNumber = referenceNumber, narration = narration,
        gstRatePercent = gstRatePercent, hsnSac = hsnSac, supplyNature = supplyNature
    )

    private fun postAccountOnlyTradingDocument(
        isSale: Boolean,
        partyLedgerId: String,
        tradeLedgerId: String,
        amount: Money,
        date: LocalDate,
        referenceNumber: String,
        narration: String,
        gstRatePercent: Double = 0.0,
        hsnSac: String = "",
        supplyNature: GstSupplyNature = GstSupplyNature.NORMAL
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            if (!amount.isPositive) {
                emitMessage("Validation error: Enter an amount greater than zero.")
                return@launch
            }

            val ledgers = repository.getLedgers(comp.companyId).first().associateBy { it.ledgerId }
            val partyLedger = ledgers[partyLedgerId]
            val tradeLedger = ledgers[tradeLedgerId]
            if (partyLedger == null || tradeLedger == null) {
                emitMessage("Validation error: Invalid ${if (isSale) "customer" else "supplier"} or ${if (isSale) "sales" else "purchase"} ledger selected.")
                return@launch
            }

            if (gstRatePercent > 0.0) {
                // Accounting-flow audit fix - GST-applicable Account-Only Sale/Purchase: same
                // Place of Supply requirement as the item-driven path (Rule 29), since GST really
                // is being computed here now.
                if (partyLedger.stateCode.isBlank()) {
                    emitMessage(
                        "Validation error: Set a State for ${if (isSale) "customer" else "supplier"} " +
                            "'${partyLedger.name}' before posting - Place of Supply cannot be determined."
                    )
                    return@launch
                }
                repository.ensureGstLedgersExist(comp.companyId)
                repository.ensureRoundOffLedgerExists(comp.companyId)
                val gstLedgers = repository.resolveGstLedgerRefs(comp.companyId)
                val roundOffRef = repository.resolveRoundOffLedgerRef(comp.companyId)
                val voucherType = if (isSale) VoucherType.SALES else VoucherType.PURCHASE
                val voucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
                val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, fy.financialYearId, voucherType)
                val syntheticLine = TradingLineInput(
                    itemId = "", itemName = if (isSale) "Sale (Account Only)" else "Purchase (Account Only)",
                    hsnSacCode = hsnSac, quantity = com.example.accounting.core.common.Quantity.fromDouble(1.0, "Nos"),
                    rate = amount, gstRatePercent = gstRatePercent, supplyNature = supplyNature
                )
                val engineResult = if (isSale) {
                    TradingWorkflowEngine.buildSale(
                        voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                        customerLedgerId = partyLedgerId, customerName = partyLedger.name, customerGstin = partyLedger.gstin,
                        salesLedgerId = tradeLedgerId, salesLedgerName = tradeLedger.name,
                        companyStateCode = comp.stateCode, placeOfSupply = partyLedger.stateCode,
                        lines = listOf(syntheticLine), gstLedgers = gstLedgers,
                        roundOffLedgerId = roundOffRef.ledgerId, roundOffLedgerName = roundOffRef.name,
                        trackInventory = false
                    )
                } else {
                    TradingWorkflowEngine.buildPurchase(
                        voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                        supplierLedgerId = partyLedgerId, supplierName = partyLedger.name, supplierGstin = partyLedger.gstin,
                        purchaseLedgerId = tradeLedgerId, purchaseLedgerName = tradeLedger.name,
                        companyStateCode = comp.stateCode, placeOfSupply = partyLedger.stateCode,
                        lines = listOf(syntheticLine), gstLedgers = gstLedgers,
                        roundOffLedgerId = roundOffRef.ledgerId, roundOffLedgerName = roundOffRef.name,
                        trackInventory = false
                    )
                }
                val voucher = Voucher(
                    voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                    voucherNumber = voucherNumber, voucherType = voucherType, date = date,
                    referenceNumber = referenceNumber, narration = narration.ifBlank { "Being ${voucherType.displayName.lowercase()}" },
                    totalAmount = engineResult.totalAmount, items = engineResult.journalItems,
                    createdBy = if (isSale) "SALES_BILLING_DESK" else "PURCHASE_DESK",
                    partyGstin = partyLedger.gstin, isGstApplicable = true
                )
                val result = repository.postVoucher(voucher, stockLines = engineResult.stockLines, gstTransactions = engineResult.gstTransactions)
                when (result) {
                    is AccountingResult.Success -> {
                        emitMessage("${voucherType.displayName} $voucherNumber posted successfully: ${engineResult.totalAmount.formatPlain()}")
                        scheduler.dispatchEvent(com.example.accounting.automation.jobs.AutomationEvent.VoucherPosted(comp.companyId, voucher))
                    }
                    is AccountingResult.Failure -> emitMessage("${voucherType.displayName} posting rejected: ${result.error.message}")
                }
                return@launch
            }

            val voucherType = if (isSale) VoucherType.SALES else VoucherType.PURCHASE
            val voucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
            val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, fy.financialYearId, voucherType)

            val engineResult = if (isSale) {
                TradingWorkflowEngine.buildAccountOnlySale(
                    voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                    customerLedgerId = partyLedgerId, customerName = partyLedger.name,
                    salesLedgerId = tradeLedgerId, salesLedgerName = tradeLedger.name, amount = amount
                )
            } else {
                TradingWorkflowEngine.buildAccountOnlyPurchase(
                    voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                    supplierLedgerId = partyLedgerId, supplierName = partyLedger.name,
                    purchaseLedgerId = tradeLedgerId, purchaseLedgerName = tradeLedger.name, amount = amount
                )
            }

            val voucher = Voucher(
                voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                voucherNumber = voucherNumber, voucherType = voucherType, date = date,
                referenceNumber = referenceNumber, narration = narration.ifBlank { "Being ${voucherType.displayName.lowercase()}" },
                totalAmount = engineResult.totalAmount, items = engineResult.journalItems,
                createdBy = if (isSale) "SALES_BILLING_DESK" else "PURCHASE_DESK",
                partyGstin = partyLedger.gstin, isGstApplicable = false
            )

            val result = repository.postVoucher(voucher, stockLines = engineResult.stockLines, gstTransactions = engineResult.gstTransactions)
            when (result) {
                is AccountingResult.Success -> {
                    emitMessage("${voucherType.displayName} $voucherNumber posted successfully: ${engineResult.totalAmount.formatPlain()}")
                    scheduler.dispatchEvent(
                        com.example.accounting.automation.jobs.AutomationEvent.VoucherPosted(comp.companyId, voucher)
                    )
                }
                is AccountingResult.Failure -> {
                    emitMessage("${voucherType.displayName} posting rejected: ${result.error.message}")
                }
            }
        }
    }

    fun postCreditNote(originalSaleVoucherId: String, date: LocalDate, referenceNumber: String, narration: String) =
        postNote(isCredit = true, originalVoucherId = originalSaleVoucherId, date = date, referenceNumber = referenceNumber, narration = narration)

    fun postDebitNote(originalPurchaseVoucherId: String, date: LocalDate, referenceNumber: String, narration: String) =
        postNote(isCredit = false, originalVoucherId = originalPurchaseVoucherId, date = date, referenceNumber = referenceNumber, narration = narration)

    /**
     * Credit Note (against a Sale) / Debit Note (against a Purchase) as a full reversal of the
     * original document (Phase 5, Priority 1) - [TradingWorkflowEngine.buildNote] builds the
     * opposite journal/stock lines and negated GST-transaction rows from the original's own
     * (already-posted, never-modified) data. The original voucher row is only ever READ here.
     */
    private fun postNote(isCredit: Boolean, originalVoucherId: String, date: LocalDate, referenceNumber: String, narration: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch

            val original = repository.getVoucherById(comp.companyId, originalVoucherId)
            if (original == null) {
                emitMessage("Validation error: Original voucher not found.")
                return@launch
            }
            val expectedType = if (isCredit) VoucherType.SALES else VoucherType.PURCHASE
            if (original.voucherType != expectedType) {
                emitMessage("Validation error: A ${if (isCredit) "Credit" else "Debit"} Note must reference a ${expectedType.displayName}.")
                return@launch
            }
            if (original.isCancelled) {
                emitMessage("Validation error: Cannot issue a note against a cancelled voucher.")
                return@launch
            }

            val originalStockLines = repository.getStockLinesForVoucher(comp.companyId, originalVoucherId)
            val originalGstTransactions = repository.getGstTransactionsForVoucher(originalVoucherId)

            val voucherType = if (isCredit) VoucherType.CREDIT_NOTE else VoucherType.DEBIT_NOTE
            val voucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
            val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, fy.financialYearId, voucherType)

            val engineResult = TradingWorkflowEngine.buildNote(
                noteVoucherId = voucherId, noteVoucherType = voucherType, originalJournalItems = original.items,
                originalStockLines = originalStockLines, originalGstTransactions = originalGstTransactions
            )

            val voucher = Voucher(
                voucherId = voucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                voucherNumber = voucherNumber, voucherType = voucherType, date = date,
                referenceNumber = referenceNumber.ifBlank { original.voucherNumber },
                narration = narration.ifBlank { "Being ${voucherType.displayName.lowercase()} against ${original.voucherNumber}" },
                totalAmount = engineResult.totalAmount, items = engineResult.journalItems,
                createdBy = if (isCredit) "SALES_BILLING_DESK" else "PURCHASE_DESK",
                partyGstin = original.partyGstin, isGstApplicable = true, referenceVoucherId = originalVoucherId
            )

            val result = repository.postVoucher(voucher, stockLines = engineResult.stockLines, gstTransactions = engineResult.gstTransactions)
            when (result) {
                is AccountingResult.Success -> {
                    emitMessage("${voucherType.displayName} $voucherNumber posted successfully against ${original.voucherNumber}")
                    scheduler.dispatchEvent(
                        com.example.accounting.automation.jobs.AutomationEvent.VoucherPosted(comp.companyId, voucher)
                    )
                }
                is AccountingResult.Failure -> {
                    emitMessage("${voucherType.displayName} posting rejected: ${result.error.message}")
                }
            }
        }
    }

    /** GST filing-period governance (Phase 5, Priority 10) - deliberately isolated: locking a GST
     * filing period never touches accounting-period locking or voucher posting. */
    fun createGstFilingPeriod(periodLabel: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            repository.createGstFilingPeriod(comp.companyId, periodLabel, startDate, endDate)
            emitMessage("GST filing period '$periodLabel' created")
        }
    }

    fun toggleGstFilingPeriodLock(period: GstFilingPeriod) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            repository.setGstFilingPeriodLock(comp.companyId, period.filingPeriodId, !period.isLocked, "ADMIN")
            emitMessage("GST filing period '${period.periodLabel}' ${if (period.isLocked) "unlocked" else "locked"}")
        }
    }

    fun runDailyAutomation() {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val fyId = _uiState.value.currentFinancialYear?.financialYearId
            emitMessage("Running daily accounting automation pipeline...")
            val results = scheduler.runDailyJobs(compId, fyId)
            emitMessage("Daily automation cycle completed: ${results.size} tasks executed.")
        }
    }

    fun runMonthlyAutomation() {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val fyId = _uiState.value.currentFinancialYear?.financialYearId
            emitMessage("Running monthly financial & GST preparation checks...")
            val results = scheduler.runMonthlyJobs(compId, fyId)
            emitMessage("Monthly automation cycle completed: ${results.size} compliance jobs executed.")
        }
    }

    fun runYearlyAutomation() {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val fyId = _uiState.value.currentFinancialYear?.financialYearId
            emitMessage("Running financial year-end validation...")
            val results = scheduler.runYearlyJobs(compId, fyId)
            emitMessage("Year-end automation check completed.")
        }
    }

    fun loadLedgerStatement(ledger: Ledger) {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val statement = repository.generateLedgerStatement(compId, ledger.ledgerId)
            _uiState.update { it.copy(selectedLedgerStatement = statement) }
            router.navigate(AppRoute.LedgerStatement(ledger.ledgerId))
        }
    }

    fun clearLedgerStatement() {
        _uiState.update { it.copy(selectedLedgerStatement = null) }
        router.navigate(AppRoute.ChartOfAccounts)
    }

    /** Refresh feature - Ledger Statement's own Flow collectors (ledgers/vouchers) already keep
     * the underlying data current, but [selectedLedgerStatement] itself is a one-shot snapshot
     * ([loadLedgerStatement]) that never re-fetches on its own; there was no user-facing way to
     * force a re-read of the same ledger. Re-runs the exact same repository call
     * [loadLedgerStatement] does, keyed off the already-selected statement's own ledgerId -
     * never a second statement-generation path. */
    fun refreshLedgerStatement() {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val ledgerId = _uiState.value.selectedLedgerStatement?.ledgerId ?: return@launch
            val statement = repository.generateLedgerStatement(compId, ledgerId)
            _uiState.update { it.copy(selectedLedgerStatement = statement) }
        }
    }

    /** Print/Download fix - Ledger Statement had no PDF path at all before this (see
     * [com.example.accounting.domain.reports.LedgerStatementReport.toPdfData]'s own KDoc). Same
     * "format the already-loaded state, never recompute" pattern as [renderReportPdf] - null only
     * when nothing is currently selected, never for a genuinely empty (zero-transaction) ledger,
     * which still renders a valid PDF ("No data for this period.", same as every other report). */
    fun renderLedgerStatementPdf(): java.io.File? {
        val statement = _uiState.value.selectedLedgerStatement ?: return null
        return com.example.accounting.data.rendering.TabularPdfRenderer.render(getApplication(), statement.toPdfData())
    }

    fun shareLedgerStatementPdf(): android.content.Intent? {
        val file = renderLedgerStatementPdf() ?: return null
        return documentPreviewService.buildShareIntent(getApplication(), file, "application/pdf")
    }

    /** Runs [block], returning its result - or `null` (and appending a short label to [failures])
     * if it throws [com.example.accounting.core.database.AccountingTransactionException]. Used by
     * [refreshFinancialReports] so one report's real accounting-integrity failure (e.g. an
     * unbalanced Balance Sheet) never blocks every OTHER, independent report from updating - they
     * used to all live/die together in one `try` block, so a single unbalanced ledger anywhere
     * silently froze Receivables/Payables/GST Summary/Income & Expenditure/Outstanding at their
     * previous (often never-set, i.e. zero-on-screen) values too, even though none of those actually
     * depend on the whole trial balance netting to zero. */
    private suspend fun <T> reportOrNull(label: String, failures: MutableList<String>, block: suspend () -> T): T? = try {
        block()
    } catch (e: com.example.accounting.core.database.AccountingTransactionException) {
        failures += "$label: ${e.appError.message}"
        null
    }

    fun refreshFinancialReports() {
        // The ledgers and vouchers Flow collectors in observeCompanyData/observeFinancialYearData
        // both call this whenever their table changes, and posting a voucher changes both tables
        // in the same transaction - so two independent, concurrently-launched reads of this
        // (mid-flight against different snapshots) could otherwise race. Cancelling any in-flight
        // refresh before launching a new one collapses a burst of triggers to just the last one.
        reportsRefreshJob?.cancel()
        reportsRefreshJob = viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            val fyId = _uiState.value.currentFinancialYear?.financialYearId ?: return@launch
            val today = LocalDate.now()

            // Each report is computed independently - a failure in one (most commonly Balance
            // Sheet/Trial Balance throwing on a genuinely unbalanced ledger) no longer prevents the
            // others from refreshing. A report that fails simply keeps showing its LAST successful
            // value (never silently reset to zero/null just because an unrelated report failed).
            val failures = mutableListOf<String>()
            val tb = reportOrNull("Trial Balance", failures) { repository.generateTrialBalance(compId, fyId) }
            val pnl = reportOrNull("Profit & Loss", failures) { repository.generateProfitAndLoss(compId, fyId) }
            val bs = reportOrNull("Balance Sheet", failures) { repository.generateBalanceSheet(compId, fyId) }
            val gst = reportOrNull("GST Summary", failures) { repository.generateGSTSummary(compId, fyId) }
            val incExp = reportOrNull("Income & Expenditure", failures) { repository.generateIncomeAndExpenditure(compId, fyId) }
            // Dashboard/Sales/Purchase Receivables & Payables cards read outstandingReport/
            // receivablesReport/payablesReport directly (MainAppScreen.kt, DashboardScreen.kt) -
            // those used to only refresh on a Reports-tab visit (refreshReportsCenterExtras),
            // so every card showed a stale 0.00 (falling through to the coarser, non-aging-aware
            // balanceSheet.sundryDebtors/currentLiabilities fallback) right after posting a
            // voucher until the user happened to open Reports once. Refreshing them here too
            // keeps them live on every vouchers/ledgers change, same as every other report above.
            val outstanding = reportOrNull("Outstanding", failures) { reportService.outstanding(compId, today = today) }
            val receivables = reportOrNull("Receivables", failures) { reportService.receivables(compId, today) }
            val payables = reportOrNull("Payables", failures) { reportService.payables(compId, today) }

            _uiState.update {
                it.copy(
                    trialBalance = tb ?: it.trialBalance,
                    profitAndLoss = pnl ?: it.profitAndLoss,
                    incomeAndExpenditure = incExp ?: it.incomeAndExpenditure,
                    balanceSheet = bs ?: it.balanceSheet,
                    gstSummary = gst ?: it.gstSummary,
                    outstandingReport = outstanding ?: it.outstandingReport,
                    receivablesReport = receivables ?: it.receivablesReport,
                    payablesReport = payables ?: it.payablesReport
                )
            }
            if (failures.isNotEmpty()) {
                emitMessage("Some financial statements could not be generated: ${failures.joinToString("; ")}")
            }
        }
    }

    /**
     * Sync-refresh error message fix - [OutboxProcessor.processPendingOutbox] returns a
     * `Result<Int>`, never a bare count; this used to interpolate that `Result` object directly
     * into the Snackbar message unread, so EVERY tap of the sync button - online or offline -
     * showed a garbled message like "Outbox sync completed. Failure(java.lang.IllegalStateException:
     * Device is offline) pending accounting mutations processed." instead of either a real count or
     * a real error. Now unwrapped: a genuine success shows the real synced count, and a genuine
     * failure (offline, or online with no reachable server - this app has no live backend today)
     * shows [OutboxProcessor]'s own real, specific error message, never a fabricated "completed".
     */
    fun triggerSync() {
        viewModelScope.launch {
            val compId = _uiState.value.currentCompany?.companyId ?: return@launch
            _uiState.update { it.copy(isSyncing = true) }
            val result = outboxProcessor.processPendingOutbox(compId)
            _uiState.update { it.copy(isSyncing = false) }
            result.fold(
                onSuccess = { syncedCount -> emitMessage("Outbox sync completed. $syncedCount pending accounting mutation(s) processed.") },
                onFailure = { error -> emitMessage("Sync failed: ${error.message ?: "unknown error"}") }
            )
        }
    }

    private fun emitMessage(msg: String) {
        viewModelScope.launch {
            _snackbarEvents.emit(msg)
        }
    }

    /** Print & Download crash fix - a public door into the same snackbar channel [emitMessage]
     * already uses, for the handful of Print/Share call sites that must run from a Composable
     * (they need the real Activity Context [PrintManager] requires, which the ViewModel itself
     * never has) rather than from inside a ViewModel coroutine. "On failure, show a controlled
     * error; never crash" only holds if that error can actually reach the user. */
    fun showMessage(msg: String) = emitMessage(msg)

    // ==================== Phase 7J UI additions ====================

    private fun currentRequestingProfile(comp: Company): BusinessProfile =
        _uiState.value.businessProfile ?: BusinessProfile(businessProfileId = "", companyId = comp.companyId, businessName = comp.name)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- Party (Customer/Supplier) ----

    fun createParty(
        displayName: String,
        role: PartyRole,
        entityType: PartyEntityType,
        gstin: String = "",
        phone: String = "",
        email: String = "",
        address: String = "",
        stateCode: String = "",
        gstRegistrationStatus: com.example.accounting.domain.accounting.GstRegistrationStatus? = null,
        pinCode: String = "",
        // Architecture correction - a Customer/Supplier previously had no way to record a
        // pre-existing balance at all; defaults reproduce every existing caller's unchanged
        // zero-opening-balance behavior.
        openingBalance: Money = Money.ZERO,
        openingBalanceType: DrCr = DrCr.DEBIT
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            // Rule 29/30: the party's State must come from what was actually entered for THIS
            // party - never defaulted to the company's own state code. Left blank when unknown,
            // exactly like a fresh Ledger; the posting-time guard (Rule 29) is what actually
            // requires it, not creation.
            val ledgerTemplate = Ledger(
                ledgerId = "", companyId = comp.companyId, groupId = "", name = displayName,
                gstin = gstin, phone = phone, email = email, address = address, stateCode = stateCode,
                gstRegistrationStatus = gstRegistrationStatus, pinCode = pinCode,
                openingBalance = openingBalance, openingBalanceType = openingBalanceType,
                currentBalance = openingBalance, currentBalanceType = openingBalanceType
            )
            val result = partyService.createParty(
                Party(partyId = "", companyId = comp.companyId, ledgerId = "", role = role, entityType = entityType, displayName = displayName),
                ledgerTemplate
            )
            when (result) {
                is AccountingResult.Success -> emitMessage("${if (role == PartyRole.CUSTOMER) "Customer" else "Supplier"} '$displayName' added")
                is AccountingResult.Failure -> emitMessage("Failed to add: ${result.error.message}")
            }
        }
    }

    // ---- Cash/Bank/UPI ----

    fun createBankUpiProfile(
        bankName: String,
        accountHolderName: String,
        accountNumber: String,
        ifscCode: String,
        branchName: String = "",
        upiId: String = "",
        upiPayeeName: String = "",
        partyId: String? = null
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val profile = BankUpiProfile(
                bankUpiProfileId = "", companyId = comp.companyId, partyId = partyId, bankName = bankName,
                accountHolderName = accountHolderName, accountNumber = accountNumber, ifscCode = ifscCode,
                branchName = branchName, upi = if (upiId.isNotBlank()) UpiMetadata(upiId = upiId, payeeName = upiPayeeName) else null
            )
            when (val result = bankUpiService.create(comp.companyId, profile)) {
                is AccountingResult.Success -> emitMessage("Bank/UPI profile added")
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    fun deleteBankUpiProfile(profileId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            when (val result = bankUpiService.delete(comp.companyId, profileId)) {
                is AccountingResult.Success -> emitMessage("Bank/UPI profile removed")
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    fun cashAndBankLedgers(): List<Ledger> {
        val ledgers = _uiState.value.ledgers
        val groupsById = _uiState.value.groups.associateBy { it.groupId }
        return ledgers.filter {
            com.example.accounting.domain.accounting.StandardSystemGroups.isExactSystemGroup(it.groupId, com.example.accounting.domain.accounting.StandardSystemGroups.BANK_GROUP_ID) ||
                com.example.accounting.domain.accounting.StandardSystemGroups.isExactSystemGroup(it.groupId, com.example.accounting.domain.accounting.StandardSystemGroups.CASH_GROUP_ID) ||
                com.example.accounting.domain.accounting.StandardSystemGroups.isUnder(it.groupId, com.example.accounting.domain.accounting.StandardSystemGroups.BANK_GROUP_ID, groupsById) ||
                com.example.accounting.domain.accounting.StandardSystemGroups.isUnder(it.groupId, com.example.accounting.domain.accounting.StandardSystemGroups.CASH_GROUP_ID, groupsById)
        }
    }

    // ---- Voucher Draft review (OCR prefill + manual line entry, never a second posting path) ----

    /**
     * Phase 7J-B.1: manual "Save as Draft" entry point for the generic double-entry voucher flows
     * (Contra/Journal/Receipt/Payment) - complements OCR's [OcrSuggestionService.reviewAndPrefillVoucherDraft],
     * which was previously the only caller of [voucherDraftService].createDraft. Mirrors
     * [postQuickVoucher]'s JournalItem construction exactly, but calls `createDraft` instead of
     * `repository.postVoucher` - no voucher number is generated (drafts never consume one; that
     * happens only in [postVoucherDraft] at actual posting time) and no ledger balance is touched.
     */
    fun saveVoucherAsDraft(
        voucherType: VoucherType,
        date: LocalDate,
        debitLedgerId: String,
        creditLedgerId: String,
        amount: Money,
        narration: String,
        refNumber: String = ""
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val ledgersMap = _uiState.value.ledgers.associateBy { it.ledgerId }
            val debitLedger = ledgersMap[debitLedgerId]
            val creditLedger = ledgersMap[creditLedgerId]
            if (debitLedger == null || creditLedger == null) {
                emitMessage("Validation error: Invalid debit or credit ledger selected.")
                return@launch
            }
            val draftVoucherId = "VCH_${voucherType.code}_${UUID.randomUUID().toString().take(8).uppercase()}"
            val items = listOf(
                JournalItem(
                    itemId = UUID.randomUUID().toString(), voucherId = draftVoucherId, companyId = comp.companyId,
                    financialYearId = fy.financialYearId, ledgerId = debitLedgerId, ledgerName = debitLedger.name,
                    type = DrCr.DEBIT, amount = amount, narration = narration, lineOrder = 1
                ),
                JournalItem(
                    itemId = UUID.randomUUID().toString(), voucherId = draftVoucherId, companyId = comp.companyId,
                    financialYearId = fy.financialYearId, ledgerId = creditLedgerId, ledgerName = creditLedger.name,
                    type = DrCr.CREDIT, amount = amount, narration = narration, lineOrder = 2
                )
            )
            val voucher = Voucher(
                voucherId = draftVoucherId, companyId = comp.companyId, financialYearId = fy.financialYearId,
                voucherNumber = "", voucherType = voucherType, date = date, referenceNumber = refNumber,
                narration = narration, totalAmount = amount, items = items, createdBy = "SENIOR_ACCOUNTANT"
            )
            when (val result = voucherDraftService.createDraft(voucher)) {
                is AccountingResult.Success -> emitMessage("Saved as draft - resume anytime from Pending Reviews")
                is AccountingResult.Failure -> emitMessage("Could not save draft: ${result.error.message}")
            }
        }
    }

    fun editVoucherDraftLines(draft: VoucherDraft, lines: List<com.example.accounting.application.voucher.VoucherDraftLine>) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val ledgersMap = _uiState.value.ledgers.associateBy { it.ledgerId }
            val voucher = Voucher(
                voucherId = draft.draftId, companyId = comp.companyId, financialYearId = draft.financialYearId,
                voucherNumber = "", voucherType = draft.voucherType, date = draft.date,
                referenceNumber = draft.referenceNumber, narration = draft.narration,
                items = lines.mapIndexed { idx, l ->
                    JournalItem(
                        itemId = "", voucherId = draft.draftId, companyId = comp.companyId, financialYearId = draft.financialYearId,
                        ledgerId = l.ledgerId, ledgerName = ledgersMap[l.ledgerId]?.name ?: "", type = l.type,
                        amount = Money.fromPaise(l.amountPaise), narration = l.narration, lineOrder = idx + 1
                    )
                }
            )
            when (val result = voucherDraftService.editDraft(draft.draftId, voucher)) {
                is AccountingResult.Success -> emitMessage("Draft updated")
                is AccountingResult.Failure -> emitMessage("Could not update draft: ${result.error.message}")
            }
        }
    }

    fun postVoucherDraft(draft: VoucherDraft) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            if (draft.lines.isEmpty()) {
                emitMessage("Add at least one ledger line before posting.")
                return@launch
            }
            val voucherNumber = repository.generateNextVoucherNumber(comp.companyId, draft.financialYearId, draft.voucherType)
            val ledgersMap = _uiState.value.ledgers.associateBy { it.ledgerId }
            val voucher = Voucher(
                voucherId = draft.draftId, companyId = comp.companyId, financialYearId = draft.financialYearId,
                voucherNumber = voucherNumber, voucherType = draft.voucherType, date = draft.date,
                referenceNumber = draft.referenceNumber, narration = draft.narration,
                totalAmount = Money.fromPaise(draft.lines.filter { it.type == DrCr.DEBIT }.sumOf { it.amountPaise }),
                items = draft.lines.mapIndexed { idx, l ->
                    JournalItem(
                        itemId = "", voucherId = draft.draftId, companyId = comp.companyId, financialYearId = draft.financialYearId,
                        ledgerId = l.ledgerId, ledgerName = ledgersMap[l.ledgerId]?.name ?: "", type = l.type,
                        amount = Money.fromPaise(l.amountPaise), narration = l.narration, lineOrder = idx + 1
                    )
                },
                createdBy = "SENIOR_ACCOUNTANT"
            )
            when (val result = voucherDraftService.postDraft(voucher)) {
                is AccountingResult.Success -> emitMessage("Voucher $voucherNumber posted successfully")
                is AccountingResult.Failure -> emitMessage("Posting rejected: ${result.error.message}")
            }
        }
    }

    fun discardVoucherDraft(draftId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            when (val result = voucherDraftService.discardDraft(comp.companyId, draftId)) {
                is AccountingResult.Success -> emitMessage("Draft discarded")
                is AccountingResult.Failure -> emitMessage("Could not discard: ${result.error.message}")
            }
        }
    }

    // ---- Phase 7J-B.2 (Slice 2) - Voucher Document Attachments: ViewModel layer over the frozen
    // Slice 1 infrastructure (AttachmentStorageAdapter / VoucherManagementServiceImpl.attach-
    // DocumentReference/removeDocumentReference/getAttachmentsForVoucher). The UI never touches
    // Room, DocumentAssetEntity, company validation, or duplicate semantics directly - every one of
    // those decisions is made here or one layer below. Only real, currently-posted vouchers are
    // supported this slice - see the class-level Slice 2 report for why voucher drafts are not
    // wired (attachDocumentReference requires a real `vouchers` row, which a still-pending draft
    // deliberately does not have).

    fun loadVoucherAttachments(voucherId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            _uiState.update { it.copy(isVoucherAttachmentsLoading = true, voucherAttachmentsVoucherId = voucherId) }
            val attachments = voucherDraftService.getAttachmentsForVoucher(comp.companyId, voucherId)
            _uiState.update { it.copy(isVoucherAttachmentsLoading = false, voucherAttachments = attachments) }
        }
    }

    /** Called when a voucher's detail view closes, so a stale attachment list never flashes for
     * the next voucher opened before its own [loadVoucherAttachments] call resolves. */
    fun clearVoucherAttachments() {
        _uiState.update { it.copy(voucherAttachmentsVoucherId = null, voucherAttachments = emptyList()) }
    }

    /**
     * Attach flow (Slice 2 hardened, per the file-type-validation fix): [AttachmentFileValidator.validate]
     * FIRST, against the real file bytes - never the OS-reported MIME, never the bare extension
     * alone - so a rejected file never reaches [AttachmentStorageAdapter.copyToDurableStorage],
     * never gets a [com.example.accounting.domain.rendering.DocumentAsset] row, and never gets a
     * [com.example.accounting.data.local.entity.VoucherDocumentReferenceEntity] row. Only on
     * acceptance: copy -> real SHA-256 checksum of the copied bytes (the same [sha256] every other
     * asset-creating call site already uses, never a second algorithm) -> `createDocumentAsset`
     * (type [DocumentAssetType.VOUCHER_ATTACHMENT], MIME from the validator, never `ContentResolver`)
     * -> `attachDocumentReference` -> reload. On ANY failure past the copy step, the just-created
     * file (and, if it got that far, the just-created but never-linked [com.example.accounting.domain.rendering.DocumentAsset]
     * row) is deleted - a failed attach never leaves an orphaned file or a dangling asset row, and
     * never reports success.
     */
    fun attachDocumentToVoucher(voucherId: String, uri: android.net.Uri, originalFileName: String?) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val context = getApplication<Application>()
            _uiState.update { it.copy(isAttachingDocument = true) }

            val validation = com.example.accounting.data.storage.AttachmentFileValidator.validate(originalFileName) {
                try { context.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            }
            if (validation is com.example.accounting.data.storage.AttachmentFileValidator.ValidationResult.Rejected) {
                _uiState.update { it.copy(isAttachingDocument = false) }
                emitMessage(validation.reason)
                return@launch
            }
            val accepted = validation as com.example.accounting.data.storage.AttachmentFileValidator.ValidationResult.Accepted

            val copyResult = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    com.example.accounting.data.storage.AttachmentStorageAdapter.copyToDurableStorage(input, context.filesDir, originalFileName, accepted.mimeType)
                } ?: throw com.example.accounting.data.storage.AttachmentStorageAdapter.AttachmentCopyException("Could not open the selected file.")
            } catch (e: com.example.accounting.data.storage.AttachmentStorageAdapter.AttachmentCopyException) {
                _uiState.update { it.copy(isAttachingDocument = false) }
                emitMessage("Could not attach file: ${e.message}")
                return@launch
            }

            val checksum = try {
                sha256(File(copyResult.storageReference).readBytes())
            } catch (e: Exception) {
                File(copyResult.storageReference).delete()
                _uiState.update { it.copy(isAttachingDocument = false) }
                emitMessage("Could not read the copied attachment file.")
                return@launch
            }

            val assetResult = repository.createDocumentAsset(
                comp.companyId, DocumentAssetType.VOUCHER_ATTACHMENT, copyResult.storageReference,
                checksum, copyResult.mimeType, copyResult.sizeBytes
            )
            if (assetResult is AccountingResult.Failure) {
                File(copyResult.storageReference).delete()
                _uiState.update { it.copy(isAttachingDocument = false) }
                emitMessage("Could not attach file: ${assetResult.error.message}")
                return@launch
            }
            val asset = (assetResult as AccountingResult.Success).data

            val attachResult = voucherDraftService.attachDocumentReference(comp.companyId, voucherId, asset.assetId)
            if (attachResult is AccountingResult.Failure) {
                // Roll back both the DB row and the file - never leave an orphaned asset behind.
                repository.deleteDocumentAsset(comp.companyId, asset.assetId)
                File(copyResult.storageReference).delete()
                _uiState.update { it.copy(isAttachingDocument = false) }
                emitMessage("Could not attach file: ${attachResult.error.message}")
                return@launch
            }

            _uiState.update { it.copy(isAttachingDocument = false) }
            emitMessage("Attachment added")
            loadVoucherAttachments(voucherId)
        }
    }

    /** Unlink only (Slice 2, Step 8) - see [com.example.accounting.application.voucher.VoucherManagementServiceImpl.removeDocumentReference]'s
     * doc comment for the exact "never deletes the asset/voucher/journal items" guarantee this
     * delegates to unchanged. */
    fun removeVoucherAttachment(voucherId: String, referenceId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            _uiState.update { it.copy(removingAttachmentReferenceId = referenceId) }
            when (val result = voucherDraftService.removeDocumentReference(comp.companyId, referenceId)) {
                is AccountingResult.Success -> {
                    emitMessage("Attachment removed")
                    loadVoucherAttachments(voucherId)
                }
                is AccountingResult.Failure -> emitMessage("Could not remove attachment: ${result.error.message}")
            }
            _uiState.update { it.copy(removingAttachmentReferenceId = null) }
        }
    }

    // ---- Invoice drafts (read/cancel only this pass - creation reuses the existing, tested
    // Sale/Purchase dialog and its immediate-post path; see docs/54 for the reasoning) ----

    fun cancelInvoiceDraft(invoiceId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            when (val result = invoiceService.cancelInvoice(comp.companyId, fy.financialYearId, invoiceId)) {
                is AccountingResult.Success -> emitMessage("Invoice draft cancelled")
                is AccountingResult.Failure -> emitMessage("Could not cancel: ${result.error.message}")
            }
        }
    }

    // ---- Profile / Business Setup ----

    fun updateBusinessProfile(
        businessName: String, legalName: String, address: String, phone: String, email: String, gstin: String, pan: String,
        pinCode: String? = null, city: String? = null, state: String? = null, country: String? = null
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val base = _uiState.value.businessProfile ?: BusinessProfile(businessProfileId = "", companyId = comp.companyId, businessName = businessName)
            val profile = base.copy(
                businessName = businessName, legalName = legalName, address = address, phone = phone, email = email, gstin = gstin, pan = pan,
                pinCode = pinCode ?: base.pinCode, city = city ?: base.city, state = state ?: base.state, country = country ?: base.country
            )
            when (val result = profileService.upsertBusinessProfile(comp.companyId, profile)) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(businessProfile = result.data) }
                    emitMessage("Business profile updated")
                }
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    /** Profile Wizard (Part 2) - the full [BusinessProfile] shape, unlike [updateBusinessProfile]'s
     * 7-field subset (kept for the old single-page screen, unchanged). Called once per wizard
     * step ("Save progress" requirement) - always `.copy()`s over the currently-loaded profile, so
     * a step the user hasn't reached yet never gets blanked by an earlier step's save. */
    fun updateBusinessProfileFull(
        businessName: String, legalName: String, constitutionType: com.example.accounting.domain.rendering.ConstitutionType,
        address: String, pinCode: String, city: String, state: String, country: String, phone: String, email: String, website: String,
        gstin: String, pan: String, tan: String, udyam: String,
        bankName: String, bankAccountNumber: String, bankIfsc: String, bankBranch: String, upiId: String,
        termsAndConditions: String
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val base = _uiState.value.businessProfile ?: BusinessProfile(businessProfileId = "", companyId = comp.companyId, businessName = businessName)
            val profile = base.copy(
                businessName = businessName, legalName = legalName, constitutionType = constitutionType,
                address = address, pinCode = pinCode, city = city, state = state, country = country,
                phone = phone, email = email, website = website,
                gstin = gstin, pan = pan, tan = tan, udyam = udyam,
                bankName = bankName, bankAccountNumber = bankAccountNumber, bankIfsc = bankIfsc, bankBranch = bankBranch, upiId = upiId,
                termsAndConditions = termsAndConditions
            )
            when (val result = profileService.upsertBusinessProfile(comp.companyId, profile)) {
                is AccountingResult.Success -> _uiState.update { it.copy(businessProfile = result.data) }
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    /** Reuses the exact asset pipeline already established for OCR/import (`sha256` + real
     * [DocumentAssetType], never a fake/placeholder asset id) - picks up wherever the profile
     * currently stands via `.copy()`, same safe-partial-update pattern as [updateBusinessProfileFull]. */
    fun uploadBusinessBrandingAsset(imageFile: File, type: DocumentAssetType) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val bytes = imageFile.readBytes()
            val assetResult = repository.createDocumentAsset(comp.companyId, type, imageFile.absolutePath, sha256(bytes), "image/jpeg", imageFile.length())
            if (assetResult is AccountingResult.Failure) {
                emitMessage("Upload failed: ${assetResult.error.message}")
                return@launch
            }
            val asset = (assetResult as AccountingResult.Success).data
            val base = _uiState.value.businessProfile ?: BusinessProfile(businessProfileId = "", companyId = comp.companyId, businessName = comp.name)
            val profile = when (type) {
                DocumentAssetType.LOGO -> base.copy(logoAssetId = asset.assetId)
                DocumentAssetType.SIGNATURE -> base.copy(signatureAssetId = asset.assetId)
                DocumentAssetType.QR_CODE -> base.copy(qrCodeAssetId = asset.assetId)
                else -> base
            }
            when (val result = profileService.upsertBusinessProfile(comp.companyId, profile)) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(businessProfile = result.data) }
                    emitMessage("${type.name.lowercase().replaceFirstChar { it.uppercase() }} uploaded")
                }
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    fun updateIndividualProfile(
        name: String, address: String, phone: String, email: String, pan: String,
        pinCode: String? = null, city: String? = null, state: String? = null, country: String? = null
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val base = _uiState.value.individualProfile ?: IndividualProfile(individualProfileId = "", companyId = comp.companyId, name = name)
            val profile = base.copy(
                name = name, address = address, phone = phone, email = email, pan = pan,
                pinCode = pinCode ?: base.pinCode, city = city ?: base.city, state = state ?: base.state, country = country ?: base.country
            )
            when (val result = profileService.upsertIndividualProfile(comp.companyId, profile)) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(individualProfile = result.data) }
                    emitMessage("Individual profile updated")
                }
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    /** Fires a real lookup against [pinCodeLookupAdapter] - the caller (Business/Individual
     * Profile's City/State/Country fields) reads [AccountingUiState.pinCodeLookupResult] and
     * applies it itself; this function never writes directly into a profile, since it has no way
     * to know which of the two profiles (or which in-progress wizard step) the caller means. On
     * failure/offline, [PinCodeLookupResult.success] is false and the fields stay exactly as the
     * user already had them - never a guessed value. */
    fun lookupPinCode(pinCode: String) {
        val cached = pinCodeLookupCache[pinCode]
        if (cached != null) {
            _uiState.update { it.copy(pinCodeLookupResult = cached) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPinCodeLookupInProgress = true) }
            val result = pinCodeLookupAdapter.lookup(pinCode)
            if (result.success) pinCodeLookupCache[pinCode] = result
            _uiState.update { it.copy(isPinCodeLookupInProgress = false, pinCodeLookupResult = result) }
        }
    }

    fun clearPinCodeLookupResult() {
        _uiState.update { it.copy(pinCodeLookupResult = null) }
    }

    // ---- Subscription ----

    fun loadSubscription() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            _uiState.update { it.copy(currentSubscription = subscriptionService.getCurrent(comp.companyId, fy.financialYearId)) }
        }
    }

    fun upgradeOrRenewSubscription(planType: SubscriptionPlanType, planName: String, entitlements: Set<EntitlementFeature>) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            when (val result = subscriptionService.createOrRenew(comp.companyId, fy.financialYearId, planType, planName, entitlements)) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(currentSubscription = result.data) }
                    emitMessage("Subscription updated to $planName")
                }
                is AccountingResult.Failure -> emitMessage("Failed: ${result.error.message}")
            }
        }
    }

    // ---- Reports Center extras (Cash Flow/Ratio Analysis/HSN-SAC) ----
    // Outstanding/Receivables/Payables are refreshed reactively by refreshFinancialReports() on
    // every vouchers/ledgers change instead (Dashboard/Sales/Purchase cards need them live, not
    // just on a Reports-tab visit) - recomputing them here too would just be redundant work.

    fun refreshReportsCenterExtras() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            // Crash fix (live-device audit finding) - ratioAnalysis()/cashFlow() both transitively
            // call generateBalanceSheet() -> generateTrialBalance(), which throws
            // AccountingTransactionException on any unbalanced trial balance (e.g. a Balance-Sheet
            // migration import that has only entered some ledgers' opening balances so far - an
            // expected transient state, not corruption). refreshFinancialReports() already guards
            // its own identical calls with this same catch/snackbar pattern; this function fired on
            // every Reports-tab visit with no guard at all, fatally crashing the whole app instead
            // of degrading gracefully.
            try {
                val cashFlow = reportService.cashFlow(comp.companyId, fy.financialYearId, fy.startDate..fy.endDate)
                val ratios = reportService.ratioAnalysis(comp.companyId, fy.financialYearId)
                val hsnSac = reportService.hsnSacSummary(comp.companyId, fy.financialYearId)
                _uiState.update {
                    it.copy(
                        cashFlowReport = cashFlow, ratioAnalysisReport = ratios, hsnSacSummary = hsnSac
                    )
                }
            } catch (e: com.example.accounting.core.database.AccountingTransactionException) {
                emitMessage("Financial statement generation failed: ${e.appError.message}")
            }
        }
    }

    // ==== Rule 33: GST Return Dashboard & Filing Foundation ====

    /** Resolves/creates the return for this exact Dashboard selection and makes it the selected
     * return - the same "find or create, then open" flow whether this is a brand-new selection or
     * reopening a previously-saved one (Rule 33, Section 15). */
    /** Rule 33 follow-up - the Dashboard's own compact Settings strip writes straight through to
     * the existing company-profile update (never a separate/silent path) so Registered/Regular/
     * QRMP toggles here and the same facts anywhere else in the app never drift apart. */
    fun updateGstEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            // GST Settings refactor - "Registered requires and validates GSTIN": unlike the other
            // GST config toggles, this one can genuinely fail (see
            // AccountingRepository.updateAccountingConfiguration's GSTIN guard), so the result must
            // actually be checked - the company otherwise silently stays Unregistered with no
            // feedback as to why the toggle didn't take.
            val result = repository.updateAccountingConfiguration(comp.companyId, gstEnabled = enabled)
            if (result is AccountingResult.Failure) {
                emitMessage(result.error.message)
            }
        }
    }

    fun updateGstScheme(scheme: GstScheme) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            repository.updateAccountingConfiguration(comp.companyId, gstScheme = scheme)
        }
    }

    fun updateGstFilingFrequency(frequency: GstReturnPeriodicity) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            repository.updateAccountingConfiguration(comp.companyId, gstFilingFrequency = frequency)
        }
    }

    /** GST Settings refactor - Top GST Period Row's Return Period dropdown. Pass exactly one of
     * [month]/[quarter] (matching the company's current [Company.gstFilingFrequency]); the other
     * is always cleared, never left stale from a previous frequency. */
    fun updateGstReturnPeriod(month: Int?, quarter: GstQuarter?) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val result = repository.updateGstReturnPeriod(comp.companyId, month, quarter)
            if (result is AccountingResult.Failure) {
                emitMessage("Error updating return period: ${result.error.message}")
            }
        }
    }

    fun selectGstReturnPeriod(
        quarter: GstQuarter,
        month: Int?,
        scheme: GstScheme,
        returnType: GstReturnType,
        periodicity: GstReturnPeriodicity,
        filingMode: GstFilingMode
    ) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val gstReturn = gstReturnService.getOrCreateReturn(comp.companyId, fy, quarter, month, scheme, returnType, periodicity, filingMode)
            openGstReturn(gstReturn.gstReturnId)
        }
    }

    fun openGstReturn(gstReturnId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val gstReturn = gstReturnService.getReturn(comp.companyId, gstReturnId)
            val sections = gstReturnService.getSections(gstReturnId)
            val artifacts = gstReturnService.getArtifacts(gstReturnId)
            _uiState.update {
                it.copy(selectedGstReturn = gstReturn, selectedGstReturnSections = sections, selectedGstReturnArtifacts = artifacts)
            }
            refreshGstAutomationNotifications()
        }
    }

    /** Phase 8A, Part 2 - populates [AccountingUiState.gstAutomationNotifications] from real
     * [com.example.accounting.automation.notifications.AutomationNotificationCenter] history for
     * the current company, filtered to GSTR-1's own category. Called when the GST Dashboard is
     * opened and whenever a matching notification arrives while it's already open. */
    private fun refreshGstAutomationNotifications() {
        val comp = _uiState.value.currentCompany ?: return
        val notifications = com.example.accounting.automation.notifications.AutomationNotificationCenter
            .getAllNotifications(comp.companyId)
            .filter { it.category == com.example.accounting.automation.notifications.NotificationCategory.GST_RETURN_FILING }
        _uiState.update { it.copy(gstAutomationNotifications = notifications) }
    }

    /** Phase 8A, Part 2 - see [com.example.accounting.domain.company.Company.gstr1ReminderEnabled].
     * Mirrors [updateGstEnabled]'s exact pattern. */
    fun updateGstReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            repository.updateAccountingConfiguration(comp.companyId, gstr1ReminderEnabled = enabled)
        }
    }

    fun clearSelectedGstReturn() {
        _uiState.update { it.copy(selectedGstReturn = null, selectedGstReturnSections = emptyList(), selectedGstReturnArtifacts = emptyList()) }
    }

    private suspend fun reopenSelectedGstReturn(gstReturnId: String) {
        val comp = _uiState.value.currentCompany ?: return
        val gstReturn = gstReturnService.getReturn(comp.companyId, gstReturnId)
        val sections = gstReturnService.getSections(gstReturnId)
        val artifacts = gstReturnService.getArtifacts(gstReturnId)
        _uiState.update {
            it.copy(selectedGstReturn = gstReturn, selectedGstReturnSections = sections, selectedGstReturnArtifacts = artifacts)
        }
    }

    fun prepareSelectedGstReturn() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.prepare(comp.companyId, gstReturnId, fy)) {
                is AccountingResult.Success -> emitMessage("Return prepared from posted GST transactions.")
                is AccountingResult.Failure -> emitMessage("Prepare failed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    fun validateSelectedGstReturn() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.validate(comp.companyId, gstReturnId, fy)) {
                is AccountingResult.Success -> emitMessage(
                    if (result.data.status == com.example.accounting.domain.taxation.gstreturn.GstReturnStatus.READY) "Return validated - ready to file."
                    else "Validation failed: ${result.data.errorMessage}"
                )
                is AccountingResult.Failure -> emitMessage("Validation failed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    fun generateSelectedGstReturnOfflineJson() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.generateOfflineJson(comp.companyId, gstReturnId, fy)) {
                is AccountingResult.Success -> emitMessage("Return JSON generated and saved as a new artifact.")
                is AccountingResult.Failure -> emitMessage("JSON generation failed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    fun importSelectedGstReturnOfflineResponse(responseJson: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.importOfflineResponse(comp.companyId, gstReturnId, responseJson)) {
                is AccountingResult.Success -> emitMessage("Response imported. Review it, then mark the return Filed once confirmed.")
                is AccountingResult.Failure -> emitMessage("Import failed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    /** Online mode, but the user filed it themselves directly at gst.gov.in - see
     * [com.example.accounting.application.gstreturn.GstReturnManagementService.markProcessingManually]'s
     * own KDoc for why this is a real, separate action from [submitSelectedGstReturnOnline]. */
    fun markSelectedGstReturnProcessingManually() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.markProcessingManually(comp.companyId, gstReturnId)) {
                is AccountingResult.Success -> emitMessage("Ready for you to enter your acknowledgement number.")
                is AccountingResult.Failure -> emitMessage("Could not proceed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    fun markSelectedGstReturnFiled(acknowledgementNumber: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.markFiled(comp.companyId, gstReturnId, acknowledgementNumber)) {
                is AccountingResult.Success -> emitMessage("Return marked as Filed.")
                is AccountingResult.Failure -> emitMessage("Could not mark as Filed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    /** Phase 8A, Part 2 - see [com.example.accounting.data.repository.AccountingRepository.setGstReturnNilFlag]'s
     * own KDoc for why this is a real, persisted, user-driven declaration rather than an inferred flag. */
    fun setSelectedGstReturnNil(isNil: Boolean) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.setNilReturn(comp.companyId, gstReturnId, isNil)) {
                is AccountingResult.Success -> emitMessage(if (isNil) "Marked as a Nil Return." else "Nil Return declaration removed.")
                is AccountingResult.Failure -> emitMessage("Could not update: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    fun submitSelectedGstReturnOnline() {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val gstReturnId = _uiState.value.selectedGstReturn?.gstReturnId ?: return@launch
            when (val result = gstReturnService.submitOnline(comp.companyId, gstReturnId, fy)) {
                is AccountingResult.Success -> emitMessage(
                    result.data.errorMessage ?: "Return submitted."
                )
                is AccountingResult.Failure -> emitMessage("Submission failed: ${result.error.message}")
            }
            reopenSelectedGstReturn(gstReturnId)
        }
    }

    /** Product rule: the user's own GST-provider credentials, never LedgerPrime's, never shared
     * across companies - stored via the existing per-company [SecureStorage.setCompanySetting]
     * (`comp_<companyId>_gst_provider_username` under the hood), the exact same isolation
     * `Company.gstin`/`gstScheme`/etc. already get, just for a secret rather than a plain column.
     * Deliberately the username only - a GST Portal password/OTP is never persisted; a real
     * provider integration authenticates per-attempt, it doesn't need this app to remember a
     * password. [submitSelectedGstReturnOnline] (called separately, right after this) is the one
     * real submission path - see [com.example.accounting.domain.taxation.gstreturn.UnconfiguredGstOnlineFilingGateway]
     * for why it honestly fails today rather than fabricating a filed return. */
    private val secureStorage by lazy { com.example.accounting.core.security.SecureStorage.getInstance(getApplication()) }

    fun saveGstProviderUsername(username: String) {
        val comp = _uiState.value.currentCompany ?: return
        secureStorage.setCompanySetting(comp.companyId, "gst_provider_username", username)
    }

    fun getGstProviderUsername(): String {
        val comp = _uiState.value.currentCompany ?: return ""
        return secureStorage.getCompanySetting(comp.companyId, "gst_provider_username", "")
    }

    // ---- Data Import (CSV/JSON -> Draft/Suggestion -> Review -> Explicit Create) ----

    fun importFromFile(sourceFile: File, format: ImportFileFormat) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val bytes = sourceFile.readBytes()
            val assetResult = repository.createDocumentAsset(
                comp.companyId, DocumentAssetType.IMPORT_SOURCE_FILE, sourceFile.absolutePath, sha256(bytes),
                if (format == ImportFileFormat.JSON) "application/json" else "text/csv", sourceFile.length()
            )
            if (assetResult is AccountingResult.Failure) {
                emitMessage("Import failed: ${assetResult.error.message}")
                return@launch
            }
            val asset = (assetResult as AccountingResult.Success).data
            val result = dataImportService.parseFile(currentRequestingProfile(comp), format, asset.assetId)
            when (result) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(lastImportResult = result.data, lastImportRowOutcomes = emptyMap()) }
                    emitMessage("Parsed ${result.data.suggestions.size} row(s) for review")
                }
                is AccountingResult.Failure -> emitMessage("Import failed: ${result.error.message}")
            }
        }
    }

    fun reviewAndCreateImportRow(suggestion: ImportRowSuggestion, resolvedType: ImportSuggestionType, groupIdOverride: String? = null) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val result = dataImportService.reviewAndCreate(comp.companyId, suggestion, resolvedType, groupIdOverride)
            val outcome = when (result) {
                is AccountingResult.Success -> "Created"
                is AccountingResult.Failure -> "Failed: ${result.error.message}"
            }
            _uiState.update { it.copy(lastImportRowOutcomes = it.lastImportRowOutcomes + (suggestion.rowNumber to outcome)) }
            emitMessage("Row ${suggestion.rowNumber}: $outcome")
        }
    }

    fun clearImportResult() {
        _uiState.update { it.copy(lastImportResult = null, lastImportRowOutcomes = emptyMap()) }
    }

    // ---- OCR (suggestion-only, adapter deliberately unimplemented - always fails gracefully) ----

    fun scanReceiptForVoucherDraft(imageFile: File) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: return@launch
            val fy = _uiState.value.currentFinancialYear ?: return@launch
            val bytes = imageFile.readBytes()
            val assetResult = repository.createDocumentAsset(
                comp.companyId, DocumentAssetType.OCR_SOURCE_IMAGE, imageFile.absolutePath, sha256(bytes), "image/jpeg", imageFile.length()
            )
            if (assetResult is AccountingResult.Failure) {
                emitMessage("Scan failed: ${assetResult.error.message}")
                return@launch
            }
            val asset = (assetResult as AccountingResult.Success).data
            when (val result = ocrService.requestExtraction(currentRequestingProfile(comp), asset.assetId)) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(lastOcrExtraction = result.data) }
                    ocrService.reviewAndPrefillVoucherDraft(comp.companyId, fy.financialYearId, result.data)
                    emitMessage("Receipt scanned - a draft was created for review; add ledger lines before posting.")
                }
                is AccountingResult.Failure -> emitMessage(result.error.message)
            }
        }
    }

    // ---- QR/Barcode (pure utility - no accounting logic) ----

    fun generateBarcodeForItem(itemId: String) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: run { emitMessage("Select a company first."); return@launch }
            when (val result = qrBarcodeService.generateForStockItem(currentRequestingProfile(comp), comp.companyId, itemId)) {
                is AccountingResult.Success -> _uiState.update { it.copy(lastBarcodeGeneration = result.data) }
                is AccountingResult.Failure -> emitMessage("Could not generate barcode: ${result.error.message}")
            }
        }
    }

    fun scanBarcodeImage(imageFile: File) {
        viewModelScope.launch {
            val comp = _uiState.value.currentCompany ?: run { emitMessage("Select a company first."); return@launch }
            val bytes = imageFile.readBytes()
            val assetResult = repository.createDocumentAsset(
                comp.companyId, DocumentAssetType.OCR_SOURCE_IMAGE, imageFile.absolutePath, sha256(bytes), "image/jpeg", imageFile.length()
            )
            if (assetResult is AccountingResult.Failure) {
                emitMessage("Scan failed: ${assetResult.error.message}")
                return@launch
            }
            val asset = (assetResult as AccountingResult.Success).data
            when (val result = qrBarcodeService.scanImage(currentRequestingProfile(comp), comp.companyId, asset.assetId)) {
                is AccountingResult.Success -> {
                    _uiState.update { it.copy(lastBarcodeScan = result.data) }
                    emitMessage(if (result.data.matchedStockItemId != null) "Match found" else "No matching item found")
                }
                is AccountingResult.Failure -> emitMessage("Scan failed: ${result.error.message}")
            }
        }
    }

    fun clearBarcodeState() {
        _uiState.update { it.copy(lastBarcodeGeneration = null, lastBarcodeScan = null) }
    }

    // ---- Export & Share (Android-only, JSON/CSV; delegates to ExportManagementService/ShareAdapter) ----

    suspend fun exportVoucherAndShare(voucherId: String, format: com.example.accounting.domain.export.ExportFormat): android.content.Intent? {
        val comp = _uiState.value.currentCompany ?: return null
        val result = exportService.exportVoucher(comp.companyId, voucherId, format)
        if (result is AccountingResult.Failure) {
            emitMessage("Export failed: ${result.error.message}")
            return null
        }
        val exportResult = (result as AccountingResult.Success).data
        val ext = if (format == com.example.accounting.domain.export.ExportFormat.CSV) "csv" else "json"
        val mimeType = if (ext == "csv") "text/csv" else "application/json"
        val file = File(getApplication<Application>().cacheDir, "voucher_export_${System.currentTimeMillis()}.$ext")
        file.writeText(exportResult.content)
        return documentPreviewService.buildShareIntent(getApplication(), file, mimeType)
    }

    /** Export + Share for the report screens (Phase UI-REPORT-01) - mirrors [exportVoucherAndShare]'s
     * exact pattern, never a second export/share mechanism. Only the three report kinds
     * [ExportManagementService] actually has a method for reach here - [reportKey] values for
     * Ledger Statement/Day Book/Income & Expenditure (no `ExportType` exists for any of them) are
     * never passed by the UI and return null here defensively. Trial Balance exports as CSV (a
     * genuinely tabular report, per `ExportFormatSupport`); Profit & Loss/Balance Sheet export as
     * JSON, their only supported format today - never a guessed CSV that `ExportFormatSupport`
     * would reject. */
    suspend fun exportReportAndShare(reportKey: String): android.content.Intent? {
        val comp = _uiState.value.currentCompany ?: return null
        val fy = _uiState.value.currentFinancialYear ?: return null
        val format = if (reportKey == "Trial Balance") {
            com.example.accounting.domain.export.ExportFormat.CSV
        } else {
            com.example.accounting.domain.export.ExportFormat.JSON
        }
        val result = when (reportKey) {
            "Trial Balance" -> exportService.exportTrialBalance(comp.companyId, fy.financialYearId, format)
            "Profit & Loss" -> exportService.exportProfitAndLoss(comp.companyId, fy.financialYearId, format)
            "Balance Sheet" -> exportService.exportBalanceSheet(comp.companyId, fy.financialYearId, format)
            else -> return null
        }
        if (result is AccountingResult.Failure) {
            emitMessage("Export failed: ${result.error.message}")
            return null
        }
        val exportResult = (result as AccountingResult.Success).data
        val ext = if (format == com.example.accounting.domain.export.ExportFormat.CSV) "csv" else "json"
        val mimeType = if (ext == "csv") "text/csv" else "application/json"
        val safeName = reportKey.lowercase().replace(" & ", "_").replace(" ", "_")
        val file = File(getApplication<Application>().cacheDir, "${safeName}_export_${System.currentTimeMillis()}.$ext")
        file.writeText(exportResult.content)
        return documentPreviewService.buildShareIntent(getApplication(), file, mimeType)
    }

    /** Phase 8A, Part 2 - CSV/GST JSON export+share for the currently-selected GSTR-1 return,
     * mirroring [exportReportAndShare]'s exact file-then-share pattern (never a second export
     * mechanism). Requires READY (same gate the "Generate JSON"/Export buttons already enforce in
     * the UI) - this defensively re-checks it here too, since [AccountingRepository.exportGstReturnAs]
     * itself has no such gate (it exports whatever the return's current sections reflect, READY or
     * not, for the round-trip-import use case) but a not-yet-validated draft should never be shared
     * as if it were a finished return. */
    suspend fun exportSelectedGstReturnAndShare(format: com.example.accounting.domain.export.ExportFormat): android.content.Intent? {
        val comp = _uiState.value.currentCompany ?: return null
        val fy = _uiState.value.currentFinancialYear ?: return null
        val gstReturn = _uiState.value.selectedGstReturn ?: return null
        if (gstReturn.status != com.example.accounting.domain.taxation.gstreturn.GstReturnStatus.READY) {
            emitMessage("Export requires the return to be READY - run Validate first.")
            return null
        }
        val result = gstReturnService.exportDraft(comp.companyId, gstReturn.gstReturnId, fy, format)
        if (result is AccountingResult.Failure) {
            emitMessage("Export failed: ${result.error.message}")
            return null
        }
        val exportResult = (result as AccountingResult.Success).data
        val ext = when (format) {
            com.example.accounting.domain.export.ExportFormat.CSV -> "csv"
            else -> "json"
        }
        val mimeType = if (ext == "csv") "text/csv" else "application/json"
        val safeName = "gstr1_${gstReturn.periodKey}_${format.name.lowercase()}"
        val file = File(getApplication<Application>().cacheDir, "${safeName}_${System.currentTimeMillis()}.$ext")
        file.writeText(exportResult.content)
        return documentPreviewService.buildShareIntent(getApplication(), file, mimeType)
    }

    /** Plain-text share summary for the same three report kinds - pure formatting of figures
     * [AccountingUiState] already computed and is already displaying on screen (never a
     * recalculation); a lighter-weight alternative to [exportReportAndShare] for a quick
     * WhatsApp/SMS-style share that doesn't need a full JSON/CSV file attachment. Returns null for
     * anything not yet loaded or not one of the three supported keys. */
    fun buildReportShareText(reportKey: String): String? {
        val comp = _uiState.value.currentCompany ?: return null
        return when (reportKey) {
            "Trial Balance" -> _uiState.value.trialBalance?.let {
                "Trial Balance - ${comp.name} (${it.financialYearCode})\n" +
                    "Total Debit: ${it.totalClosingDebit.formatPlain()}\n" +
                    "Total Credit: ${it.totalClosingCredit.formatPlain()}\n" +
                    "Balanced: ${if (it.isBalanced) "Yes" else "No"}"
            }
            "Profit & Loss" -> _uiState.value.profitAndLoss?.let {
                "Profit & Loss - ${comp.name} (${it.financialYearCode})\n" +
                    "Gross Profit: ${it.grossProfit.formatPlain()}\n" +
                    "Net Profit: ${it.netProfit.formatPlain()}"
            }
            "Balance Sheet" -> _uiState.value.balanceSheet?.let {
                "Balance Sheet - ${comp.name} (${it.financialYearCode})\n" +
                    "Total Assets: ${it.totalAssets.formatPlain()}\n" +
                    "Total Liabilities: ${it.totalLiabilities.formatPlain()}\n" +
                    "Balanced: ${if (it.isBalanced) "Yes" else "No"}"
            }
            else -> null
        }
    }

    /** Maps the same already-loaded report [AccountingUiState] currently displays to
     * [com.example.accounting.domain.rendering.TabularReportData] via `ReportPdfMapping.kt` - no
     * recalculation, same three report kinds as [exportReportAndShare]/[buildReportShareText].
     * Null for anything not yet loaded or not one of the three supported keys. */
    private fun reportPdfData(reportKey: String): com.example.accounting.domain.rendering.TabularReportData? {
        val isServiceCompany = _uiState.value.currentCompany?.businessType == com.example.accounting.domain.company.BusinessType.SERVICE
        return when (reportKey) {
            "Trial Balance" -> _uiState.value.trialBalance?.toPdfData()
            "Profit & Loss" -> if (isServiceCompany) _uiState.value.incomeAndExpenditure?.toPdfData() else _uiState.value.profitAndLoss?.toPdfData()
            "Balance Sheet" -> _uiState.value.balanceSheet?.toPdfData()
            else -> null
        }
    }

    /** Renders [reportKey]'s already-loaded report to a PDF file via [TabularPdfRenderer] - the
     * one renderer for tabular financial reports (never [PdfDocumentRenderer], which is
     * DocumentData/trade-document-shaped only). Returns null if the report isn't loaded/supported. */
    fun renderReportPdf(reportKey: String): java.io.File? {
        val data = reportPdfData(reportKey) ?: return null
        return com.example.accounting.data.rendering.TabularPdfRenderer.render(getApplication(), data)
    }

    // Print & Download crash fix (Financial Reports) - "printReport"/"printDayBook" were removed
    // here. Both called `documentPreviewService.print(getApplication(), ...)` ->
    // `PrintAdapter.print(Application context, ...)`, and Android's PrintManager throws
    // `IllegalStateException: Can print only from an activity` for anything but a real Activity
    // Context - exactly the same root cause already fixed once for Invoice print
    // (MainAppScreen's onPrint there calls PrintAdapter.print directly with LocalContext.current).
    // MainAppScreen's onPrintReport/DayBookScreen's onPrint now do the same: fetch the PDF File
    // via renderReportPdf()/renderDayBookPdf() (unchanged below), then call PrintAdapter.print
    // with the Composable's own real Activity Context, wrapped in try/catch so a missing print
    // service (or any other print-time failure) shows a controlled message instead of crashing.

    fun shareReportPdf(reportKey: String): android.content.Intent? {
        val file = renderReportPdf(reportKey) ?: return null
        return documentPreviewService.buildShareIntent(getApplication(), file, "application/pdf")
    }

    /** Day Book Print/PDF - unlike [renderReportPdf] (which formats an already-loaded
     * [AccountingUiState] report), Day Book's on-screen view is a live voucher-list filter, not a
     * stored [com.example.accounting.domain.reports.DayBookReport]. Rendering therefore fetches
     * the SAME, already-existing, unmodified [ReportManagementService.dayBook] used everywhere
     * else Day Book data is needed - the full current-FY Day Book (never a second calculation,
     * and never re-derived from the UI's own search/type filter, which is a browsing convenience,
     * not an accounting boundary). Returns null (with a controlled message) rather than throwing
     * when no company/financial year is active - never a fabricated report. */
    suspend fun renderDayBookPdf(): java.io.File? {
        val comp = _uiState.value.currentCompany ?: run { emitMessage("No company selected."); return null }
        val fy = _uiState.value.currentFinancialYear ?: run { emitMessage("No financial year selected."); return null }
        val report = reportService.dayBook(comp.companyId, fy.startDate..fy.endDate)
        return com.example.accounting.data.rendering.TabularPdfRenderer.render(getApplication(), report.toPdfData())
    }

    // ---- GSTR-1 PDF Preview/Download/Print/Share (Phase 8A, Part 2) ----

    /** Renders the currently-selected GST return's section-wise summary to a PDF file via
     * [com.example.accounting.data.rendering.TabularPdfRenderer] - mirrors [renderReportPdf]'s
     * exact pattern for the one report kind that pattern didn't originally cover. Null if nothing
     * is selected. */
    fun renderGstReturnPdf(): File? {
        val comp = _uiState.value.currentCompany ?: return null
        val gstReturn = _uiState.value.selectedGstReturn ?: return null
        val data = com.example.accounting.presentation.features.reports.buildGstr1SummaryPdfData(
            gstReturn, _uiState.value.selectedGstReturnSections, comp.name
        )
        return com.example.accounting.data.rendering.TabularPdfRenderer.render(getApplication(), data)
    }

    fun printGstReturn() {
        val file = renderGstReturnPdf() ?: run { emitMessage("No GST return selected."); return }
        documentPreviewService.print(getApplication(), file, "GSTR-1 Summary")
    }

    fun shareGstReturnPdf(): android.content.Intent? {
        val file = renderGstReturnPdf() ?: return null
        return documentPreviewService.buildShareIntent(getApplication(), file, "application/pdf")
    }

    /** Plain-text share summary for the selected GST return - mirrors [buildReportShareText]'s
     * exact pattern (pure formatting of already-computed [AccountingUiState] figures, never a
     * recalculation), giving "Share" a genuinely different artifact from "Download"'s PDF file. */
    fun buildGstReturnShareText(): String? {
        val comp = _uiState.value.currentCompany ?: return null
        val gstReturn = _uiState.value.selectedGstReturn ?: return null
        val sections = _uiState.value.selectedGstReturnSections
        val data = com.example.accounting.presentation.features.reports.buildGstr1SummaryPdfData(gstReturn, sections, comp.name)
        val totals = data.totalsRow ?: return null
        return "${data.title} - ${comp.name}\n" +
            "Records: ${totals.getOrNull(1) ?: "0"}\n" +
            "Taxable Value: ${totals.getOrNull(2) ?: "0"}\n" +
            "Tax: ${totals.getOrNull(3) ?: "0"}\n" +
            "Status: ${gstReturn.status}"
    }
}

