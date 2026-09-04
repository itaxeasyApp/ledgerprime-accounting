package com.example.accounting.data.repository

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.core.database.AccountingTransactionException
import com.example.accounting.core.database.AppDatabase
import com.example.accounting.core.database.DatabaseTransaction
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.AuditLogEntity
import com.example.accounting.data.local.entity.BranchEntity
import com.example.accounting.data.local.entity.BusinessProfileEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.DocumentAssetEntity
import com.example.accounting.data.local.entity.DocumentTemplateEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.GstFilingPeriodEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.IndividualProfileEntity
import com.example.accounting.data.local.entity.InvoiceEntity
import com.example.accounting.data.local.entity.InvoiceLineEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.OutboxSyncEntity
import com.example.accounting.data.local.entity.PartyEntity
import com.example.accounting.data.local.entity.RecurringVoucherDraftEntity
import com.example.accounting.data.local.entity.RecurringVoucherDraftLineEntity
import com.example.accounting.data.local.entity.RecurringVoucherLineEntity
import com.example.accounting.data.local.entity.RecurringVoucherScheduleEntity
import com.example.accounting.data.local.entity.RenderedDocumentRecordEntity
import com.example.accounting.data.local.entity.SettlementAllocationEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.TradeDocumentEntity
import com.example.accounting.data.local.entity.TradeDocumentLineEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.document.DocumentStatus
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.document.TradeDocument
import com.example.accounting.domain.document.TradeDocumentLine
import com.example.accounting.domain.invoice.Invoice
import com.example.accounting.domain.invoice.InvoiceStatus
import com.example.accounting.domain.invoice.InvoiceStatusEngine
import com.example.accounting.domain.invoice.InvoiceLine
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.inventory.StockMovementType
import com.example.accounting.domain.inventory.VoucherStockLine
import com.example.accounting.domain.inventory.engine.CogsEngine
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.party.PaymentTerms
import com.example.accounting.domain.sync.SyncAggregateType
import com.example.accounting.domain.sync.SyncEvent
import com.example.accounting.domain.sync.SyncEventSerializer
import com.example.accounting.domain.sync.SyncGstTransactionDto
import com.example.accounting.domain.sync.SyncInvoiceDto
import com.example.accounting.domain.sync.SyncInvoiceLineDto
import com.example.accounting.domain.sync.SyncLedgerDto
import com.example.accounting.domain.sync.SyncOperation
import com.example.accounting.domain.sync.SyncPartyDto
import com.example.accounting.domain.sync.SyncTradeDocumentDto
import com.example.accounting.domain.sync.SyncTradeDocumentLineDto
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstFilingPeriod
import com.example.accounting.domain.taxation.gst.GstLedgerIds
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.data.local.entity.GstReturnArtifactEntity
import com.example.accounting.data.local.entity.GstReturnEntity
import com.example.accounting.data.local.entity.GstReturnSectionEntity
import com.example.accounting.data.local.entity.GstReturnSubmissionEntity
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstOnlineFilingGateway
import com.example.accounting.domain.taxation.gstreturn.GstPeriod
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturn
import com.example.accounting.domain.taxation.gstreturn.GstReturnArtifact
import com.example.accounting.domain.taxation.gstreturn.GstReturnArtifactType
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSection
import com.example.accounting.domain.taxation.gstreturn.GstReturnSectionStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatusTransitions
import com.example.accounting.domain.taxation.gstreturn.GstReturnSubmission
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import com.example.accounting.domain.taxation.gstreturn.UnconfiguredGstOnlineFilingGateway
import com.squareup.moshi.Moshi
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.Branch
import com.example.accounting.domain.accounting.DoubleEntryValidator
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.audit.AuditAction
import com.example.accounting.domain.audit.AuditLog
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.reports.AgingBucket
import com.example.accounting.domain.reports.AgingBucketTotal
import com.example.accounting.domain.reports.BalanceSheetReport
import com.example.accounting.domain.reports.CashFlowReport
import com.example.accounting.domain.reports.DayBookEntryStatus
import com.example.accounting.domain.reports.DayBookReport
import com.example.accounting.domain.reports.DayBookRow
import com.example.accounting.domain.reports.GSTSummaryReport
import com.example.accounting.domain.reports.GroupAggregationEngine
import com.example.accounting.domain.reports.IncomeExpenditureReport
import com.example.accounting.domain.reports.LedgerStatementReport
import com.example.accounting.domain.reports.LedgerStatementRow
import com.example.accounting.domain.reports.OutstandingReport
import com.example.accounting.domain.reports.OutstandingReportRow
import com.example.accounting.domain.reports.ProfitAndLossReport
import com.example.accounting.domain.reports.RatioAnalysisEngine
import com.example.accounting.domain.reports.RatioAnalysisReport
import com.example.accounting.domain.reports.TrialBalanceReport
import com.example.accounting.domain.reports.TrialBalanceRow
import com.example.accounting.domain.export.BalanceSheetExportDto
import com.example.accounting.domain.export.CsvEngine
import com.example.accounting.domain.export.ExportFormat
import com.example.accounting.domain.export.ExportFormatSupport
import com.example.accounting.domain.export.ExportJsonSerializer
import com.example.accounting.domain.export.ExportMetadata
import com.example.accounting.domain.export.ExportResult
import com.example.accounting.domain.export.ExportType
import com.example.accounting.domain.export.GSTSummaryExportDto
import com.example.accounting.domain.export.GSTTransactionExportDto
import com.example.accounting.domain.export.GstrJsonSerializer
import com.example.accounting.domain.export.InvoiceExportDto
import com.example.accounting.domain.export.JournalLineExportDto
import com.example.accounting.domain.export.LedgerExportDto
import com.example.accounting.domain.export.OutstandingExportDto
import com.example.accounting.domain.export.OutstandingRowExportDto
import com.example.accounting.domain.export.PartyExportDto
import com.example.accounting.domain.export.ProfitAndLossExportDto
import com.example.accounting.domain.export.TrialBalanceExportDto
import com.example.accounting.domain.export.TrialBalanceRowExportDto
import com.example.accounting.domain.export.VoucherExportDto
import com.example.accounting.domain.export.toCsvHeaders
import com.example.accounting.domain.export.toCsvRows
import com.example.accounting.domain.export.toGstTransactionCsvHeaders
import com.example.accounting.domain.export.toGstTransactionCsvRows
import com.example.accounting.domain.export.toLedgerCsvHeaders
import com.example.accounting.domain.export.toLedgerCsvRows
import com.example.accounting.domain.export.toPartyCsvHeaders
import com.example.accounting.domain.export.toPartyCsvRows
import com.example.accounting.domain.export.toTree
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.domain.rendering.DocumentAsset
import com.example.accounting.domain.rendering.DocumentAssetType
import com.example.accounting.domain.rendering.DocumentBrandingSnapshot
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.DocumentLineData
import com.example.accounting.domain.rendering.DocumentPartySnapshot
import com.example.accounting.domain.rendering.DocumentPaymentInfo
import com.example.accounting.domain.rendering.DocumentReferenceInfo
import com.example.accounting.domain.rendering.DocumentTemplate
import com.example.accounting.domain.rendering.DocumentTotals
import com.example.accounting.domain.rendering.IndividualProfile
import com.example.accounting.domain.rendering.JsonDocumentRenderer
import com.example.accounting.domain.rendering.RenderedDocumentRecord
import com.example.accounting.domain.rendering.TemplateConfigSerializer
import com.example.accounting.domain.rendering.TemplateStatus
import com.example.accounting.domain.rendering.TemplateVisualConfig
import com.example.accounting.domain.recurring.RecurringVoucherDraft
import com.example.accounting.domain.recurring.RecurringVoucherDraftLine
import com.example.accounting.domain.recurring.RecurringVoucherDraftStatus
import com.example.accounting.domain.recurring.RecurringVoucherGenerationOutcome
import com.example.accounting.domain.recurring.RecurringVoucherLine
import com.example.accounting.domain.recurring.RecurringVoucherPeriod
import com.example.accounting.domain.recurring.RecurringVoucherSchedule
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransactionFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class AccountingRepository(
    private val dao: AccountingDao,
    private val db: AppDatabase? = null
) {

    private val dbTransaction: DatabaseTransaction? = db?.let { DatabaseTransaction(it, dao) }

    // Rule 33 - reuses the exact same Moshi "Map<String, Any?>" approach already established by
    // ExportJsonSerializer/GstrJsonSerializer, never a new JSON dependency/framework.
    private val gstReturnMoshi: Moshi = Moshi.Builder().build()
    private val gstReturnMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val gstReturnJsonAdapter = gstReturnMoshi.adapter<Map<String, Any?>>(gstReturnMapType)
    // A GST portal response is a JSON object; parsing it as a generic string-keyed map is a real,
    // schema-agnostic well-formedness check - never a guess at the actual (unknown) response shape.
    private val genericJsonAdapter = gstReturnJsonAdapter

    private fun safeParseDate(str: String?): LocalDate {
        if (str.isNullOrBlank()) return LocalDate.now()
        return try {
            LocalDate.parse(str)
        } catch (e: Throwable) {
            LocalDate.now()
        }
    }

    // ==================== INITIALIZATION & SEEDING ====================
    /**
     * Seeds the standard Chart of Accounts (financial years, accounting periods, system groups,
     * starter Cash/Bank/Capital/Sales ledgers, GST duty ledgers) for a real, user-created company -
     * every real company needs this scaffolding, the same way Tally/QuickBooks auto-provision a
     * standard CoA for a brand-new company file. [companyName]/[gstin] must be the caller's real
     * data; this function has no default company of its own to fall back to (removed - a
     * production app must never auto-create a fake company at startup just because none exists
     * yet; a genuinely empty company list is the correct, already-supported first-launch state -
     * `AppTopBar` already renders "Select Company" for a null [com.example.accounting.domain.company.Company]).
     */
    suspend fun seedInitialDataForCompany(
        companyId: String,
        companyName: String,
        gstin: String
    ) {
        val company = CompanyEntity(
            companyId = companyId,
            name = companyName,
            tradeName = companyName,
            gstin = gstin,
            pan = gstin.take(10).ifBlank { "AAACA1234F" },
            stateCode = gstin.take(2).ifBlank { "27" },
            stateName = "Maharashtra",
            email = "accounts@apextech.in",
            phone = "+91 98200 11223",
            address = "Plot 42, MIDC Industrial Area, Andheri East, Mumbai, MH - 400093",
            currency = "INR",
            financialYearStartMonth = 4,
            isDefault = true,
            createdAt = System.currentTimeMillis()
        )
        dao.insertCompany(company)

        // Seed Financial Years: FY 2025-26 (Closed), FY 2026-27 (Current/Open), FY 2027-28 (Future/Open)
        val fyList = listOf(
            FinancialYearEntity(
                financialYearId = "FY_2025_26_$companyId",
                companyId = companyId,
                fyCode = "FY 2025-26",
                startDate = "2025-04-01",
                endDate = "2026-03-31",
                isCurrent = false,
                isLocked = true,
                lockedAt = System.currentTimeMillis(),
                lockedBy = "SYSTEM"
            ),
            FinancialYearEntity(
                financialYearId = "FY_2026_27_$companyId",
                companyId = companyId,
                fyCode = "FY 2026-27",
                startDate = "2026-04-01",
                endDate = "2027-03-31",
                isCurrent = true,
                isLocked = false,
                lockedAt = null,
                lockedBy = null
            ),
            FinancialYearEntity(
                financialYearId = "FY_2027_28_$companyId",
                companyId = companyId,
                fyCode = "FY 2027-28",
                startDate = "2027-04-01",
                endDate = "2028-03-31",
                isCurrent = false,
                isLocked = false,
                lockedAt = null,
                lockedBy = null
            )
        )
        fyList.forEach { dao.insertFinancialYear(it) }

        // Seed 12 Accounting Periods for FY 2026-27
        val fyId = "FY_2026_27_$companyId"
        val months = listOf(
            Triple("Apr 2026", "2026-04-01", "2026-04-30"),
            Triple("May 2026", "2026-05-01", "2026-05-31"),
            Triple("Jun 2026", "2026-06-01", "2026-06-30"),
            Triple("Jul 2026", "2026-07-01", "2026-07-31"),
            Triple("Aug 2026", "2026-08-01", "2026-08-31"),
            Triple("Sep 2026", "2026-09-01", "2026-09-30"),
            Triple("Oct 2026", "2026-10-01", "2026-10-31"),
            Triple("Nov 2026", "2026-11-01", "2026-11-30"),
            Triple("Dec 2026", "2026-12-01", "2026-12-31"),
            Triple("Jan 2027", "2027-01-01", "2027-01-31"),
            Triple("Feb 2027", "2027-02-01", "2027-02-28"),
            Triple("Mar 2027", "2027-03-01", "2027-03-31")
        )
        val periods = months.mapIndexed { index, (name, start, end) ->
            AccountingPeriodEntity(
                periodId = "PER_${companyId}_${index + 1}",
                companyId = companyId,
                financialYearId = fyId,
                name = name,
                startDate = start,
                endDate = end,
                status = PeriodStatus.OPEN,
                lockedAt = null,
                lockedBy = null
            )
        }
        dao.insertPeriods(periods)

        // Seed Standard 28 Chart of Accounts Groups
        val standardGroups = StandardSystemGroups.getStandardGroupsForCompany(companyId).map {
            GroupEntity(
                groupId = it.groupId,
                companyId = it.companyId,
                name = it.name,
                primaryGroup = it.primaryGroup,
                parentGroupId = it.parentGroupId,
                isSystem = it.isSystem,
                affectsGrossProfit = it.affectsGrossProfit,
                displayOrder = it.displayOrder
            )
        }
        dao.insertGroups(standardGroups)

        // Seed Standard Chart of Accounts Ledgers
        val starterLedgers = listOf(
            LedgerEntity(
                ledgerId = "LED_BANK_HDFC_$companyId",
                companyId = companyId,
                groupId = "GRP_BANK_$companyId",
                name = "HDFC Current A/c",
                code = "1001",
                openingBalancePaise = 50000000L, // ₹5,00,000 Dr
                openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 50000000L,
                currentBalanceType = DrCr.DEBIT,
                gstin = "",
                pan = "",
                stateCode = "27",
                email = "",
                phone = "",
                address = "HDFC Bank, Andheri East Branch",
                bankAccountNumber = "50200012345678",
                bankIfsc = "HDFC0000123",
                isSystem = false,
                isActive = true,
                hsnSacCode = "",
                defaultTaxRate = 0.0
            ),
            LedgerEntity(
                ledgerId = "LED_CASH_$companyId",
                companyId = companyId,
                groupId = "GRP_CASH_$companyId",
                name = "Cash-in-Hand",
                code = "1002",
                openingBalancePaise = 10000000L, // ₹1,00,000 Dr
                openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 10000000L,
                currentBalanceType = DrCr.DEBIT,
                gstin = "",
                pan = "",
                stateCode = "27",
                email = "",
                phone = "",
                address = "",
                bankAccountNumber = "",
                bankIfsc = "",
                isSystem = true,
                isActive = true,
                hsnSacCode = "",
                defaultTaxRate = 0.0
            ),
            LedgerEntity(
                ledgerId = "LED_CAPITAL_$companyId",
                companyId = companyId,
                groupId = "GRP_CAPITAL_$companyId",
                name = "Share Capital Account",
                code = "2001",
                openingBalancePaise = 60000000L, // ₹6,00,000 Cr (Balances Dr = Cr)
                openingBalanceType = DrCr.CREDIT,
                currentBalancePaise = 60000000L,
                currentBalanceType = DrCr.CREDIT,
                gstin = "",
                pan = "AAACA1234F",
                stateCode = "27",
                email = "",
                phone = "",
                address = "",
                bankAccountNumber = "",
                bankIfsc = "",
                isSystem = false,
                isActive = true,
                hsnSacCode = "",
                defaultTaxRate = 0.0
            ),
            LedgerEntity(
                ledgerId = "LED_SALES_18_$companyId",
                companyId = companyId,
                groupId = "GRP_SALES_$companyId",
                name = "Sales @ 18% GST",
                code = "3001",
                openingBalancePaise = 0L,
                openingBalanceType = DrCr.CREDIT,
                currentBalancePaise = 0L,
                currentBalanceType = DrCr.CREDIT,
                gstin = "",
                pan = "",
                stateCode = "27",
                email = "",
                phone = "",
                address = "",
                bankAccountNumber = "",
                bankIfsc = "",
                isSystem = false,
                isActive = true,
                hsnSacCode = "9983",
                defaultTaxRate = 18.0
            ),
            LedgerEntity(
                ledgerId = "LED_PURCHASE_18_$companyId",
                companyId = companyId,
                groupId = "GRP_PURCHASE_$companyId",
                name = "Purchase @ 18% GST",
                code = "4001",
                openingBalancePaise = 0L,
                openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 0L,
                currentBalanceType = DrCr.DEBIT,
                gstin = "",
                pan = "",
                stateCode = "27",
                email = "",
                phone = "",
                address = "",
                bankAccountNumber = "",
                bankIfsc = "",
                isSystem = false,
                isActive = true,
                hsnSacCode = "9983",
                defaultTaxRate = 18.0
            ),
            *gstLedgersFor(companyId).toTypedArray(),
            LedgerEntity(
                ledgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyId",
                companyId = companyId,
                groupId = "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyId",
                name = "Suspense A/c",
                code = "9999",
                openingBalancePaise = 0L,
                openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 0L,
                currentBalanceType = DrCr.DEBIT,
                gstin = "",
                pan = "",
                stateCode = "27",
                email = "",
                phone = "",
                address = "",
                bankAccountNumber = "",
                bankIfsc = "",
                isSystem = true,
                isActive = true,
                hsnSacCode = "",
                defaultTaxRate = 0.0
            )
        )
        dao.insertLedgers(starterLedgers)

        // Seed Audit log
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = fyId,
                action = AuditAction.CREATE_COMPANY,
                entityType = "Company",
                entityId = companyId,
                description = "Company initialized with Indian Standard Chart of Accounts (28 Groups, FY 2026-27)",
                performedBy = "SYSTEM",
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )
    }

    // ==================== COMPANY OPERATIONS ====================
    fun getCompanies(): Flow<List<Company>> = dao.getAllCompanies().map { list ->
        list.map {
            Company(
                companyId = it.companyId,
                name = it.name,
                tradeName = it.tradeName,
                gstin = it.gstin,
                pan = it.pan,
                stateCode = it.stateCode,
                stateName = it.stateName,
                email = it.email,
                phone = it.phone,
                address = it.address,
                currency = it.currency,
                financialYearStartMonth = it.financialYearStartMonth,
                accountingMode = it.accountingMode,
                businessType = it.businessType,
                gstEnabled = it.gstEnabled,
                gstOperatingMode = it.gstOperatingMode,
                gstScheme = it.gstScheme,
                gstFilingFrequency = it.gstFilingFrequency,
                isDefault = it.isDefault,
                createdAt = it.createdAt
            )
        }
    }

    suspend fun createCompany(company: Company): AccountingResult<Company> {
        val entity = CompanyEntity(
            companyId = company.companyId,
            name = company.name,
            tradeName = company.tradeName,
            gstin = company.gstin,
            pan = company.pan,
            stateCode = company.stateCode,
            stateName = company.stateName,
            email = company.email,
            phone = company.phone,
            address = company.address,
            currency = company.currency,
            financialYearStartMonth = company.financialYearStartMonth,
            accountingMode = company.accountingMode,
            businessType = company.businessType,
            gstEnabled = company.gstEnabled,
            gstOperatingMode = company.gstOperatingMode,
            gstScheme = company.gstScheme,
            gstFilingFrequency = company.gstFilingFrequency,
            isDefault = company.isDefault,
            createdAt = System.currentTimeMillis()
        )
        dao.insertCompany(entity)

        // Audit fix - Create initial Financial Year for new company, derived from the real
        // current date and the company's own financialYearStartMonth (was previously always the
        // literal, hardcoded "FY 2026-27" / 2026-04-01..2027-03-31 regardless of when the company
        // was actually created - correct only by coincidence for companies created within that
        // one calendar window, silently wrong for every company created afterward).
        val today = java.time.LocalDate.now()
        val fyStartMonth = company.financialYearStartMonth
        val fyStartYear = if (today.monthValue >= fyStartMonth) today.year else today.year - 1
        val fyStartDate = java.time.LocalDate.of(fyStartYear, fyStartMonth, 1)
        val fyEndDate = fyStartDate.plusYears(1).minusDays(1)
        val fyId = "FY_${fyStartDate.year}_${fyEndDate.year}_${company.companyId}"
        val fy = FinancialYearEntity(
            financialYearId = fyId,
            companyId = company.companyId,
            fyCode = "FY ${fyStartDate.year}-${fyEndDate.year.toString().takeLast(2)}",
            startDate = fyStartDate.toString(),
            endDate = fyEndDate.toString(),
            isCurrent = true,
            isLocked = false,
            lockedAt = null,
            lockedBy = null
        )
        dao.insertFinancialYear(fy)

        // Clone base standard groups for this company
        val baseGroups = listOf(
            GroupEntity("GRP_BANK_${company.companyId}", company.companyId, Constants.SYS_BANK_ACCOUNTS, PrimaryGroup.ASSETS, null, true, false, 1),
            GroupEntity("GRP_CASH_${company.companyId}", company.companyId, Constants.SYS_CASH_IN_HAND, PrimaryGroup.ASSETS, null, true, false, 2),
            GroupEntity("GRP_DEBTORS_${company.companyId}", company.companyId, Constants.SYS_SUNDRY_DEBTORS, PrimaryGroup.ASSETS, null, true, false, 3),
            GroupEntity("GRP_CREDITORS_${company.companyId}", company.companyId, Constants.SYS_SUNDRY_CREDITORS, PrimaryGroup.LIABILITIES, null, true, false, 4),
            GroupEntity("GRP_DUTIES_${company.companyId}", company.companyId, Constants.SYS_DUTIES_TAXES, PrimaryGroup.LIABILITIES, null, true, false, 5),
            GroupEntity("GRP_SALES_${company.companyId}", company.companyId, Constants.SYS_SALES_ACCOUNTS, PrimaryGroup.INCOME, null, true, true, 6),
            GroupEntity("GRP_PURCHASE_${company.companyId}", company.companyId, Constants.SYS_PURCHASE_ACCOUNTS, PrimaryGroup.EXPENSES, null, true, true, 7),
            GroupEntity("GRP_DIR_EXP_${company.companyId}", company.companyId, Constants.SYS_DIRECT_EXPENSES, PrimaryGroup.EXPENSES, null, true, true, 8),
            GroupEntity("GRP_INDIR_EXP_${company.companyId}", company.companyId, Constants.SYS_INDIRECT_EXPENSES, PrimaryGroup.EXPENSES, null, true, false, 9),
            GroupEntity("GRP_CAPITAL_${company.companyId}", company.companyId, Constants.SYS_CAPITAL_ACCOUNT, PrimaryGroup.EQUITY, null, true, false, 10)
        )
        dao.insertGroups(baseGroups)

        // Insert primary cash/bank ledgers, plus one Sales and one Purchase account so a new
        // company can post its first Sale/Purchase immediately - without these, the trading-voucher
        // ledger picker (TradingForm) starts genuinely empty and the Post button can never enable
        // (voucher-UI audit finding, Rule 33 follow-up). Zero opening balance, no GST rate baked in
        // (never `fallbackToDestructiveMigration`-style fake data) - same honest-zero pattern as
        // Cash/Bank above, just extended to the two groups a trading voucher actually needs.
        // Accounting-flow audit fix - a Service business gets Income/Expenditure-labeled ledgers
        // instead of Sales/Purchase Account, matching what it actually posts (VoucherType.SALES/
        // PURCHASE still route through the same SALES/PURCHASE_GROUP_ID structure underneath -
        // renaming only the user-visible ledger.name, never the groupId/VoucherType plumbing every
        // isSalesLedger/isPurchaseLedger check and TradingWorkflowEngine call keys off, keeps this
        // a display-only fix with zero risk to the posting/report engines). Trading Account is
        // never forced onto a Service business by this - see generateIncomeAndExpenditure, which
        // already renders a Service company's P&L equivalent without any Trading/COGS section.
        val isService = company.businessType == BusinessType.SERVICE
        val salesLedgerName = if (isService) "Income Account" else "Sales Account"
        val purchaseLedgerName = if (isService) "Expenditure Account" else "Purchase Account"
        val defaultLedgers = listOf(
            LedgerEntity(
                ledgerId = "LED_CASH_${company.companyId}", companyId = company.companyId, groupId = "GRP_CASH_${company.companyId}",
                name = "Cash in Hand", code = "1001", openingBalancePaise = 0L, openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 0L, currentBalanceType = DrCr.DEBIT, gstin = "", pan = "", stateCode = company.stateCode,
                email = "", phone = "", address = "", bankName = "", bankAccountNumber = "", bankIfsc = "", bankBranch = "",
                isSystem = true, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
            ),
            LedgerEntity(
                ledgerId = "LED_BANK_${company.companyId}", companyId = company.companyId, groupId = "GRP_BANK_${company.companyId}",
                name = "Primary Bank Account", code = "1002", openingBalancePaise = 0L, openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 0L, currentBalanceType = DrCr.DEBIT, gstin = "", pan = "", stateCode = company.stateCode,
                email = "", phone = "", address = "", bankName = "", bankAccountNumber = "", bankIfsc = "", bankBranch = "",
                isSystem = true, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
            ),
            LedgerEntity(
                ledgerId = "LED_SALES_${company.companyId}", companyId = company.companyId, groupId = "GRP_SALES_${company.companyId}",
                name = salesLedgerName, code = "3001", openingBalancePaise = 0L, openingBalanceType = DrCr.CREDIT,
                currentBalancePaise = 0L, currentBalanceType = DrCr.CREDIT, gstin = "", pan = "", stateCode = company.stateCode,
                email = "", phone = "", address = "", bankName = "", bankAccountNumber = "", bankIfsc = "", bankBranch = "",
                isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
            ),
            LedgerEntity(
                ledgerId = "LED_PURCHASE_${company.companyId}", companyId = company.companyId, groupId = "GRP_PURCHASE_${company.companyId}",
                name = purchaseLedgerName, code = "4001", openingBalancePaise = 0L, openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 0L, currentBalanceType = DrCr.DEBIT, gstin = "", pan = "", stateCode = company.stateCode,
                email = "", phone = "", address = "", bankName = "", bankAccountNumber = "", bankIfsc = "", bankBranch = "",
                isSystem = false, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
            )
        )
        dao.insertLedgers(defaultLedgers)

        // Record Audit
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = company.companyId,
                financialYearId = fyId,
                action = AuditAction.CREATE_COMPANY,
                entityType = "Company",
                entityId = company.companyId,
                description = "Created new company '${company.name}' with isolated Chart of Accounts",
                performedBy = "ADMIN",
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )

        return AccountingResult.Success(company)
    }

    /**
     * Switches a company's [AccountingMode]/[BusinessType]. This is a CAPABILITY toggle only
     * (Phase 4 spec: "switching modes must never delete or hide underlying history") - it flips
     * two columns on the company row and nothing else. Existing vouchers, ledgers, stock
     * movements, GST records, and audit history are always retained untouched; switching back to
     * ACCOUNT_ONLY simply means inventory-aware report figures (COGS, Stock-in-Hand) go back to
     * being omitted, and switching back to ACCOUNT_WITH_INVENTORY makes them reappear, computed
     * from whatever stock-movement history already exists.
     */
    suspend fun updateAccountingConfiguration(
        companyId: String,
        accountingMode: AccountingMode? = null,
        businessType: BusinessType? = null,
        userId: String = "ADMIN",
        gstEnabled: Boolean? = null,
        /** D1a - see [com.example.accounting.domain.company.Company.gstOperatingMode]. Same
         * "named explicit parameter on this company-profile-update function, no separate silent
         * path" rationale as [gstScheme]/[gstFilingFrequency] below. */
        gstOperatingMode: com.example.accounting.domain.company.GstOperatingMode? = null,
        /** Rule 33 - the company's own statutory scheme choice. Deliberately a named, explicit
         * parameter on this SAME company-profile-update function rather than a new one on the
         * Return Dashboard - the dashboard reads [com.example.accounting.domain.company.Company.gstScheme],
         * it never has its own path to silently overwrite it. */
        gstScheme: com.example.accounting.domain.taxation.gstreturn.GstScheme? = null,
        /** Rule 33 follow-up - the Regular-scheme filing frequency (Monthly/QRMP-Quarterly). Same
         * "named explicit parameter on the company-profile update, no separate silent path"
         * rationale as [gstScheme]. */
        gstFilingFrequency: com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity? = null
    ): AccountingResult<Unit> {
        val existing = dao.getCompanyById(companyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Company not found"))

        dao.updateCompany(
            existing.copy(
                accountingMode = accountingMode ?: existing.accountingMode,
                businessType = businessType ?: existing.businessType,
                gstEnabled = gstEnabled ?: existing.gstEnabled,
                gstOperatingMode = gstOperatingMode ?: existing.gstOperatingMode,
                gstScheme = gstScheme ?: existing.gstScheme,
                gstFilingFrequency = gstFilingFrequency ?: existing.gstFilingFrequency
            )
        )

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = "",
                action = AuditAction.UPDATE,
                entityType = "Company",
                entityId = companyId,
                description = "Accounting configuration changed: mode=${accountingMode ?: existing.accountingMode}, businessType=${businessType ?: existing.businessType}, gstEnabled=${gstEnabled ?: existing.gstEnabled}, gstOperatingMode=${gstOperatingMode ?: existing.gstOperatingMode}",
                performedBy = userId,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )
        return AccountingResult.Success(Unit)
    }

    /** The standard GST duty ledgers (Output/Input x CGST/SGST/IGST, CESS, and Rule 31's
     * RCM Liability/Input x CGST/SGST/IGST) for a company - never one ledger conflating two of these. */
    private fun gstLedgersFor(companyId: String): List<LedgerEntity> {
        fun ledger(bareId: String, name: String, code: String, type: DrCr) = LedgerEntity(
            ledgerId = "${bareId}_$companyId", companyId = companyId, groupId = "GRP_DUTIES_$companyId",
            name = name, code = code, openingBalancePaise = 0L, openingBalanceType = type,
            currentBalancePaise = 0L, currentBalanceType = type, gstin = "", pan = "", stateCode = "27",
            email = "", phone = "", address = "", bankAccountNumber = "", bankIfsc = "",
            isSystem = true, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
        )
        return listOf(
            ledger(GstLedgerIds.OUTPUT_CGST_LEDGER_ID, "Output CGST A/c", "5001", DrCr.CREDIT),
            ledger(GstLedgerIds.OUTPUT_SGST_LEDGER_ID, "Output SGST A/c", "5002", DrCr.CREDIT),
            ledger(GstLedgerIds.OUTPUT_IGST_LEDGER_ID, "Output IGST A/c", "5003", DrCr.CREDIT),
            ledger(GstLedgerIds.INPUT_CGST_LEDGER_ID, "Input CGST A/c", "5004", DrCr.DEBIT),
            ledger(GstLedgerIds.INPUT_SGST_LEDGER_ID, "Input SGST A/c", "5005", DrCr.DEBIT),
            ledger(GstLedgerIds.INPUT_IGST_LEDGER_ID, "Input IGST A/c", "5006", DrCr.DEBIT),
            ledger(GstLedgerIds.CESS_LEDGER_ID, "CESS A/c", "5007", DrCr.CREDIT),
            // Rule 31 (Purchase/RCM Foundation) - RCM Liability is payable to the government
            // (natural CREDIT balance, same family as Output); RCM Input is the corresponding
            // Input Tax Credit claim (natural DEBIT balance, same family as Input).
            ledger(GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID, "RCM Liability CGST A/c", "5008", DrCr.CREDIT),
            ledger(GstLedgerIds.RCM_LIABILITY_SGST_LEDGER_ID, "RCM Liability SGST A/c", "5009", DrCr.CREDIT),
            ledger(GstLedgerIds.RCM_LIABILITY_IGST_LEDGER_ID, "RCM Liability IGST A/c", "5010", DrCr.CREDIT),
            ledger(GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID, "RCM Input CGST A/c", "5011", DrCr.DEBIT),
            ledger(GstLedgerIds.RCM_INPUT_SGST_LEDGER_ID, "RCM Input SGST A/c", "5012", DrCr.DEBIT),
            ledger(GstLedgerIds.RCM_INPUT_IGST_LEDGER_ID, "RCM Input IGST A/c", "5013", DrCr.DEBIT)
        )
    }

    /**
     * Idempotent backfill: ensures all 7 GST duty ledgers (Output/Input x CGST/SGST/IGST + CESS)
     * exist for [companyId], inserting only whichever are missing - existing ledgers (and any
     * balance they've accrued) are never overwritten. Needed because companies seeded before the
     * Phase 4.5 GST ledger split only have the old 3 conflated Input/Output ledgers, and companies
     * seeded before Phase 5 have none of the 6 but not CESS.
     */
    suspend fun ensureGstLedgersExist(companyId: String) {
        val existingIds = dao.getLedgersByCompany(companyId).first().map { it.ledgerId }.toSet()
        val missing = gstLedgersFor(companyId).filter { it.ledgerId !in existingIds }
        if (missing.isNotEmpty()) {
            dao.insertLedgers(missing)
        }
    }

    /**
     * Idempotent backfill for the Round Off system ledger (Phase 5, Priority 7) - same pattern as
     * [ensureGstLedgersExist]. Both the group and ledger are created together the first time a
     * company needs one; a second call is a no-op.
     */
    suspend fun ensureRoundOffLedgerExists(companyId: String) {
        val roundOffLedgerId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"
        if (dao.getLedgerById(companyId, roundOffLedgerId) != null) return

        val roundOffGroupId = "${StandardSystemGroups.ROUND_OFF_GROUP_ID}_$companyId"
        if (dao.getGroupById(companyId, roundOffGroupId) == null) {
            dao.insertGroup(
                GroupEntity(
                    groupId = roundOffGroupId, companyId = companyId, name = Constants.SYS_ROUND_OFF_ACCOUNT,
                    primaryGroup = PrimaryGroup.SPECIAL_CONTROL, parentGroupId = null,
                    isSystem = true, affectsGrossProfit = false, displayOrder = 998
                )
            )
        }
        dao.insertLedger(
            LedgerEntity(
                ledgerId = roundOffLedgerId, companyId = companyId, groupId = roundOffGroupId,
                name = Constants.SYS_ROUND_OFF_ACCOUNT, code = "9998", openingBalancePaise = 0L, openingBalanceType = DrCr.DEBIT,
                currentBalancePaise = 0L, currentBalanceType = DrCr.DEBIT, gstin = "", pan = "", stateCode = "27",
                email = "", phone = "", address = "", bankAccountNumber = "", bankIfsc = "",
                isSystem = true, isActive = true, hsnSacCode = "", defaultTaxRate = 0.0
            )
        )
    }

    /** Resolves the 7 companyId-suffixed GST duty ledger refs for [TradingWorkflowEngine], keyed
     * by [GstLedgerIds]' bare constants. Callers must have already run [ensureGstLedgersExist]. */
    suspend fun resolveGstLedgerRefs(companyId: String): com.example.accounting.domain.trading.TradingGstLedgers {
        val ledgers = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        fun ref(bareId: String) = ledgers["${bareId}_$companyId"]?.let { com.example.accounting.domain.trading.LedgerRef(it.ledgerId, it.name) }
            ?: com.example.accounting.domain.trading.LedgerRef("${bareId}_$companyId", bareId)
        return com.example.accounting.domain.trading.TradingGstLedgers(
            outputCgst = ref(GstLedgerIds.OUTPUT_CGST_LEDGER_ID),
            outputSgst = ref(GstLedgerIds.OUTPUT_SGST_LEDGER_ID),
            outputIgst = ref(GstLedgerIds.OUTPUT_IGST_LEDGER_ID),
            inputCgst = ref(GstLedgerIds.INPUT_CGST_LEDGER_ID),
            inputSgst = ref(GstLedgerIds.INPUT_SGST_LEDGER_ID),
            inputIgst = ref(GstLedgerIds.INPUT_IGST_LEDGER_ID),
            cess = ref(GstLedgerIds.CESS_LEDGER_ID),
            rcmLiabilityCgst = ref(GstLedgerIds.RCM_LIABILITY_CGST_LEDGER_ID),
            rcmLiabilitySgst = ref(GstLedgerIds.RCM_LIABILITY_SGST_LEDGER_ID),
            rcmLiabilityIgst = ref(GstLedgerIds.RCM_LIABILITY_IGST_LEDGER_ID),
            rcmInputCgst = ref(GstLedgerIds.RCM_INPUT_CGST_LEDGER_ID),
            rcmInputSgst = ref(GstLedgerIds.RCM_INPUT_SGST_LEDGER_ID),
            rcmInputIgst = ref(GstLedgerIds.RCM_INPUT_IGST_LEDGER_ID)
        )
    }

    /** Resolves the Round Off system ledger's ref. Callers must have already run [ensureRoundOffLedgerExists]. */
    suspend fun resolveRoundOffLedgerRef(companyId: String): com.example.accounting.domain.trading.LedgerRef {
        val ledgerId = "${StandardSystemGroups.ROUND_OFF_LEDGER_ID}_$companyId"
        val ledger = dao.getLedgerById(companyId, ledgerId)
        return com.example.accounting.domain.trading.LedgerRef(ledgerId, ledger?.name ?: Constants.SYS_ROUND_OFF_ACCOUNT)
    }

    // ==================== STOCK ITEMS (Phase 5 - item-driven GST needs items to exist) ====================
    fun getStockItems(companyId: String): Flow<List<StockItem>> = dao.getStockItemsByCompany(companyId).map { list ->
        list.map {
            StockItem(
                itemId = it.itemId, companyId = it.companyId, name = it.name, sku = it.sku,
                hsnCode = it.hsnCode, unit = it.unit, gstRatePercent = it.gstRatePercent,
                openingQuantity = com.example.accounting.core.common.Quantity(it.openingQuantity, it.unit),
                openingRate = Money.fromPaise(it.openingRatePaise),
                currentQuantity = com.example.accounting.core.common.Quantity(it.currentQuantity, it.unit),
                standardCost = Money.fromPaise(it.standardCostPaise),
                standardSellingPrice = Money.fromPaise(it.standardSellingPricePaise)
            )
        }
    }

    suspend fun createStockItem(item: StockItem): AccountingResult<StockItem> {
        val entity = StockItemEntity(
            itemId = item.itemId.ifBlank { "ITEM_${UUID.randomUUID().toString().take(8)}_${item.companyId}" },
            companyId = item.companyId, name = item.name, sku = item.sku, hsnCode = item.hsnCode, unit = item.unit,
            gstRatePercent = item.gstRatePercent, openingQuantity = item.openingQuantity.rawValue,
            openingRatePaise = item.openingRate.paise, currentQuantity = item.openingQuantity.rawValue,
            standardCostPaise = item.standardCost.paise, standardSellingPricePaise = item.standardSellingPrice.paise,
            currentAvgCostPaise = item.openingRate.paise
        )
        dao.insertStockItem(entity)
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(), companyId = item.companyId, financialYearId = "",
                action = AuditAction.CREATE, entityType = "StockItem", entityId = entity.itemId,
                description = "Created stock item '${item.name}' (HSN ${item.hsnCode}, GST ${item.gstRatePercent}%)",
                performedBy = "ADMIN", timestamp = System.currentTimeMillis(), payloadJson = "{}"
            )
        )
        return AccountingResult.Success(item.copy(itemId = entity.itemId))
    }

    // ==================== GST TRANSACTIONS / STOCK LINES (Phase 5 - Credit/Debit Note source data) ====================
    suspend fun getGstTransactionsForVoucher(voucherId: String): List<GstTransaction> =
        dao.getGstTransactionsForVoucher(voucherId).map {
            GstTransaction(
                gstTransactionId = it.gstTransactionId, companyId = it.companyId, financialYearId = it.financialYearId,
                voucherId = it.voucherId, voucherType = it.voucherType, partyLedgerId = it.partyLedgerId,
                partyGstin = it.partyGstin, placeOfSupply = it.placeOfSupply, supplyType = it.supplyType,
                itemId = it.itemId, hsnSacCode = it.hsnSacCode,
                quantity = it.quantityRaw?.let { q -> com.example.accounting.core.common.Quantity(q) },
                taxableAmount = Money.fromPaise(it.taxableAmountPaise), gstRatePercent = it.gstRatePercent,
                cgst = Money.fromPaise(it.cgstPaise), sgst = Money.fromPaise(it.sgstPaise),
                igst = Money.fromPaise(it.igstPaise), cess = Money.fromPaise(it.cessPaise),
                direction = it.direction, lineOrder = it.lineOrder, chargeType = it.chargeType
            )
        }

    /** Domain-mapped company+FY GST transaction listing (Phase 7J UI, one of the 3 pre-approved
     * backend additions) - mirrors [getGstTransactionsForVoucher]'s exact mapping, just scoped by
     * `dao.getGstTransactionsForCompanyFY` (already used raw by [generateGSTSummary]) instead of by
     * voucher. Read-only, no new tax calculation - the sole consumer is
     * [com.example.accounting.application.reports.ReportManagementService]'s HSN/SAC grouping. */
    suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String): List<GstTransaction> =
        dao.getGstTransactionsForCompanyFY(companyId, fyId).map {
            GstTransaction(
                gstTransactionId = it.gstTransactionId, companyId = it.companyId, financialYearId = it.financialYearId,
                voucherId = it.voucherId, voucherType = it.voucherType, partyLedgerId = it.partyLedgerId,
                partyGstin = it.partyGstin, placeOfSupply = it.placeOfSupply, supplyType = it.supplyType,
                itemId = it.itemId, hsnSacCode = it.hsnSacCode,
                quantity = it.quantityRaw?.let { q -> com.example.accounting.core.common.Quantity(q) },
                taxableAmount = Money.fromPaise(it.taxableAmountPaise), gstRatePercent = it.gstRatePercent,
                cgst = Money.fromPaise(it.cgstPaise), sgst = Money.fromPaise(it.sgstPaise),
                igst = Money.fromPaise(it.igstPaise), cess = Money.fromPaise(it.cessPaise),
                direction = it.direction, lineOrder = it.lineOrder, chargeType = it.chargeType
            )
        }

    suspend fun getStockLinesForVoucher(companyId: String, voucherId: String): List<VoucherStockLine> {
        val items = dao.getStockItemsByCompany(companyId).first().associateBy { it.itemId }
        return dao.getStockLinesForVoucher(voucherId).map {
            VoucherStockLine(
                lineId = it.lineId, voucherId = it.voucherId, companyId = it.companyId, financialYearId = it.financialYearId,
                itemId = it.itemId, itemName = items[it.itemId]?.name ?: "", direction = it.direction,
                quantity = com.example.accounting.core.common.Quantity(it.quantityRaw), rate = Money.fromPaise(it.ratePaise),
                amount = Money.fromPaise(it.amountPaise), lineOrder = it.lineOrder
            )
        }
    }

    // ==================== SETTLEMENT ALLOCATION (Phase 5, Priority 2) ====================
    /**
     * Allocates a Receipt/Payment voucher's amount against one or more outstanding invoices, or
     * leaves it (fully or partly) unallocated as an advance. `Σ allocations + unallocatedAmount`
     * must equal the settlement voucher's total - this single rule is what makes full/partial/
     * multi-invoice/advance all fall out of the same code path, no special-casing needed. No GST
     * is computed here or reachable from here - settlement never creates new tax facts.
     */
    /** Computed outstanding (in paise) for a single Sale/Purchase voucher - `total - Σ allocations
     * - Σ note adjustments` - shared by [getOutstandingInvoices] and [allocateSettlement]'s
     * over-allocation guard so both always agree on the same figure. Null if the voucher doesn't exist.
     *
     * Cancel/Reverse audit fix - an allocation whose OWN settlement (Receipt/Payment) voucher was
     * later cancelled must not keep counting against this invoice: [VoucherPostingEngine.cancel]
     * reverses a cancelled voucher's journal items/GST facts but - by design, same as every other
     * voucher type - never deletes/mutates its [SettlementAllocationEntity] rows (Rule 12,
     * append-only; the allocation itself is a true historical fact, only which voucher it's
     * attributed to changes). So this is a read-time exclusion, exactly mirroring how
     * [noteAdjustment] already excludes a cancelled Credit/Debit Note two lines below - never a
     * write to `settlement_allocations` itself. Only an explicitly CONFIRMED cancellation excludes
     * the allocation - a `settlementVoucherId` that resolves to no voucher at all is left counted
     * exactly as before this fix, never treated as equivalent to "cancelled". */
    private suspend fun computeOutstandingPaise(companyId: String, invoiceVoucherId: String): Long? {
        val invoice = dao.getVoucherById(companyId, invoiceVoucherId) ?: return null
        val allocated = dao.getAllocationsForInvoice(invoiceVoucherId).fold(0L) { acc, a ->
            val settlementVoucher = dao.getVoucherById(companyId, a.settlementVoucherId)
            if (settlementVoucher?.isCancelled == true) acc else acc + a.allocatedAmountPaise
        }
        val noteAdjustment = dao.getVouchersByCompany(companyId).first()
            .filter { it.referenceVoucherId == invoiceVoucherId && !it.isCancelled }
            .fold(0L) { acc, note -> acc + note.totalAmountPaise }
        return invoice.totalAmountPaise - allocated - noteAdjustment
    }

    /**
     * Allocates a Receipt/Payment voucher's amount against one or more outstanding invoices, or
     * leaves it (fully or partly) unallocated as an advance. `Σ allocations + unallocatedAmount`
     * must equal the settlement voucher's total - this single rule is what makes full/partial/
     * multi-invoice/advance all fall out of the same code path, no special-casing needed. No GST
     * is computed here or reachable from here - settlement never creates new tax facts.
     *
     * Each individual allocation is also validated against ITS OWN invoice's remaining outstanding
     * (Phase 5 final audit finding): without this, a second settlement allocated against an
     * already-fully-paid invoice would silently over-allocate/double-pay it, since the original
     * check only verified the settlement voucher's own total balanced - it never looked at the
     * invoice being paid at all.
     */
    suspend fun allocateSettlement(
        companyId: String,
        financialYearId: String,
        settlementVoucherId: String,
        allocations: List<Pair<String, Money>>,
        unallocatedAmount: Money
    ): AccountingResult<Unit> {
        val settlement = dao.getVoucherById(companyId, settlementVoucherId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Settlement voucher not found"))

        val allocatedSum = allocations.fold(Money.ZERO) { acc, (_, amt) -> acc + amt }
        if ((allocatedSum + unallocatedAmount).paise != settlement.totalAmountPaise) {
            return AccountingResult.Failure(
                AppError.InvalidAllocation(
                    "Allocated amount (${allocatedSum.format()}) + unallocated (${unallocatedAmount.format()}) must equal the settlement total (${Money.fromPaise(settlement.totalAmountPaise).format()})."
                )
            )
        }
        if (allocations.any { it.second.paise <= 0L }) {
            return AccountingResult.Failure(AppError.InvalidAllocation("Each allocation amount must be positive."))
        }

        for ((invoiceVoucherId, amount) in allocations) {
            val outstandingPaise = computeOutstandingPaise(companyId, invoiceVoucherId)
                ?: return AccountingResult.Failure(AppError.InvalidAllocation("Invoice voucher '$invoiceVoucherId' was not found for this company."))
            if (amount.paise > outstandingPaise) {
                return AccountingResult.Failure(
                    AppError.InvalidAllocation(
                        "Allocation of ${amount.format()} exceeds invoice '$invoiceVoucherId''s remaining outstanding (${Money.fromPaise(outstandingPaise.coerceAtLeast(0L)).format()})."
                    )
                )
            }
        }

        val rows = allocations.map { (invoiceVoucherId, amount) ->
            SettlementAllocationEntity(
                allocationId = UUID.randomUUID().toString(), companyId = companyId, financialYearId = financialYearId,
                settlementVoucherId = settlementVoucherId, invoiceVoucherId = invoiceVoucherId,
                allocatedAmountPaise = amount.paise, createdAt = System.currentTimeMillis()
            )
        } + if (unallocatedAmount.isPositive) {
            listOf(SettlementAllocationEntity(
                allocationId = UUID.randomUUID().toString(), companyId = companyId, financialYearId = financialYearId,
                settlementVoucherId = settlementVoucherId, invoiceVoucherId = null,
                allocatedAmountPaise = unallocatedAmount.paise, createdAt = System.currentTimeMillis()
            ))
        } else emptyList()

        if (rows.isNotEmpty()) dao.insertSettlementAllocations(rows)
        return AccountingResult.Success(Unit)
    }

    /** Every non-cancelled Sale/Purchase voucher for [partyLedgerId] with its computed outstanding
     * (`total - Σ allocations - Σ note adjustments referencing it`) - never a stored balance. */
    suspend fun getOutstandingInvoices(companyId: String, partyLedgerId: String): List<com.example.accounting.domain.trading.OutstandingInvoice> {
        val allVouchers = dao.getVouchersByCompany(companyId).first()
        val candidates = allVouchers.filter {
            !it.isCancelled && (it.voucherType == VoucherType.SALES || it.voucherType == VoucherType.PURCHASE)
        }
        return candidates.mapNotNull { voucherEntity ->
            val items = dao.getJournalItemsForVoucherSync(voucherEntity.voucherId)
            val partyLine = items.firstOrNull { it.ledgerId == partyLedgerId } ?: return@mapNotNull null

            val outstandingPaise = computeOutstandingPaise(companyId, voucherEntity.voucherId) ?: return@mapNotNull null
            if (outstandingPaise <= 0L) return@mapNotNull null

            com.example.accounting.domain.trading.OutstandingInvoice(
                voucherId = voucherEntity.voucherId, voucherNumber = voucherEntity.voucherNumber,
                voucherType = voucherEntity.voucherType, date = safeParseDate(voucherEntity.date),
                totalAmount = Money.fromPaise(voucherEntity.totalAmountPaise),
                outstandingAmount = Money.fromPaise(outstandingPaise)
            )
        }
    }

    // ==================== GST FILING PERIODS (Phase 5, Priority 10 - isolated from accounting periods) ====================
    fun getGstFilingPeriods(companyId: String): Flow<List<GstFilingPeriod>> = dao.getGstFilingPeriodsByCompany(companyId).map { list ->
        list.map {
            GstFilingPeriod(it.filingPeriodId, it.companyId, it.periodLabel, it.startDate, it.endDate, it.isLocked, it.lockedAt, it.lockedBy)
        }
    }

    suspend fun createGstFilingPeriod(companyId: String, periodLabel: String, startDate: String, endDate: String) {
        dao.insertGstFilingPeriod(
            GstFilingPeriodEntity(
                filingPeriodId = "GSTFP_${UUID.randomUUID().toString().take(8)}_$companyId",
                companyId = companyId, periodLabel = periodLabel, startDate = startDate, endDate = endDate,
                isLocked = false, lockedAt = null, lockedBy = null
            )
        )
    }

    /** Locking/unlocking a GST filing period is purely a compliance-tracking flag - it never
     * touches [AccountingPeriodEntity]/[com.example.accounting.domain.accounting.DoubleEntryValidator]
     * and never rejects a voucher posting (Priority 10). */
    suspend fun setGstFilingPeriodLock(companyId: String, filingPeriodId: String, locked: Boolean, userId: String = "ADMIN") {
        dao.setGstFilingPeriodLock(companyId, filingPeriodId, locked, if (locked) System.currentTimeMillis() else null, if (locked) userId else null)
    }

    // ==================== FINANCIAL YEARS & PERIODS ====================
    fun getFinancialYears(companyId: String): Flow<List<FinancialYear>> = dao.getFinancialYearsByCompany(companyId).map { list ->
        list.map {
            FinancialYear(
                financialYearId = it.financialYearId,
                companyId = it.companyId,
                fyCode = it.fyCode,
                startDate = safeParseDate(it.startDate),
                endDate = safeParseDate(it.endDate),
                isCurrent = it.isCurrent,
                isLocked = it.isLocked,
                lockedAt = it.lockedAt,
                lockedBy = it.lockedBy
            )
        }
    }

    fun getPeriods(financialYearId: String): Flow<List<AccountingPeriod>> = dao.getPeriodsByFinancialYear(financialYearId).map { list ->
        list.map {
            AccountingPeriod(
                periodId = it.periodId,
                companyId = it.companyId,
                financialYearId = it.financialYearId,
                name = it.name,
                startDate = safeParseDate(it.startDate),
                endDate = safeParseDate(it.endDate),
                status = it.status,
                lockedAt = it.lockedAt,
                lockedBy = it.lockedBy
            )
        }
    }

    fun getPeriods(companyId: String, financialYearId: String): Flow<List<AccountingPeriod>> = dao.getPeriodsByFinancialYear(companyId, financialYearId).map { list ->
        list.map {
            AccountingPeriod(
                periodId = it.periodId,
                companyId = it.companyId,
                financialYearId = it.financialYearId,
                name = it.name,
                startDate = safeParseDate(it.startDate),
                endDate = safeParseDate(it.endDate),
                status = it.status,
                lockedAt = it.lockedAt,
                lockedBy = it.lockedBy
            )
        }
    }

    suspend fun setPeriodStatus(companyId: String, periodId: String, status: PeriodStatus, user: String): AccountingResult<Unit> {
        val period = dao.getPeriodById(companyId, periodId) ?: return AccountingResult.Failure(AppError.ValidationError("Period not found"))
        val timestamp = if (status == PeriodStatus.LOCKED || status == PeriodStatus.AUDIT_LOCKED) System.currentTimeMillis() else null
        dao.setPeriodStatus(companyId, periodId, status, if (timestamp != null) user else null, timestamp)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = period.companyId,
                financialYearId = period.financialYearId,
                action = if (status == PeriodStatus.LOCKED || status == PeriodStatus.AUDIT_LOCKED) AuditAction.LOCK_PERIOD else AuditAction.UNLOCK_PERIOD,
                entityType = "AccountingPeriod",
                entityId = periodId,
                description = "Set status of accounting period '${period.name}' to $status",
                performedBy = user,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )
        return AccountingResult.Success(Unit)
    }

    suspend fun setPeriodLock(periodId: String, lock: Boolean, user: String): AccountingResult<Unit> {
        val period = dao.getPeriodById(periodId) ?: return AccountingResult.Failure(AppError.ValidationError("Period not found"))
        val status = if (lock) PeriodStatus.LOCKED else PeriodStatus.OPEN
        val timestamp = if (lock) System.currentTimeMillis() else null
        dao.updatePeriodStatus(periodId, status, timestamp, if (lock) user else null)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = period.companyId,
                financialYearId = period.financialYearId,
                action = if (lock) AuditAction.LOCK_PERIOD else AuditAction.UNLOCK_PERIOD,
                entityType = "AccountingPeriod",
                entityId = periodId,
                description = "${if (lock) "Locked" else "Unlocked"} accounting period '${period.name}'",
                performedBy = user,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )
        return AccountingResult.Success(Unit)
    }

    suspend fun closeFinancialYear(companyId: String, financialYearId: String, user: String): AccountingResult<Unit> {
        val periods = dao.getPeriodsByFinancialYear(financialYearId).first()
        val allClosed = periods.all { it.status == com.example.accounting.domain.financialyear.PeriodStatus.LOCKED || it.status == com.example.accounting.domain.financialyear.PeriodStatus.AUDIT_LOCKED }
        if (!allClosed) {
            return AccountingResult.Failure(com.example.accounting.core.common.AppError.ValidationError("Cannot close FY: all 12 accounting periods must be locked first."))
        }

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = financialYearId,
                action = AuditAction.LOCK_PERIOD,
                entityType = "FinancialYear",
                entityId = financialYearId,
                description = "Financial year closed and opening balances prepared for next period by $user",
                performedBy = user,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )
        return AccountingResult.Success(Unit)
    }

    // ==================== GROUPS & LEDGERS ====================
    fun getGroups(companyId: String): Flow<List<AccountGroup>> = dao.getGroupsByCompany(companyId).map { list ->
        list.map {
            AccountGroup(
                groupId = it.groupId,
                companyId = it.companyId,
                name = it.name,
                primaryGroup = it.primaryGroup,
                parentGroupId = it.parentGroupId,
                isSystem = it.isSystem,
                affectsGrossProfit = it.affectsGrossProfit,
                displayOrder = it.displayOrder
            )
        }
    }

    suspend fun createGroup(group: AccountGroup): AccountingResult<AccountGroup> {
        val entity = GroupEntity(
            groupId = group.groupId.ifBlank { "GRP_${UUID.randomUUID().toString().take(8)}_${group.companyId}" },
            companyId = group.companyId,
            name = group.name,
            primaryGroup = group.primaryGroup,
            parentGroupId = group.parentGroupId,
            isSystem = group.isSystem,
            affectsGrossProfit = group.affectsGrossProfit,
            displayOrder = group.displayOrder
        )
        dao.insertGroup(entity)
        return AccountingResult.Success(group.copy(groupId = entity.groupId))
    }

    suspend fun updateGroup(group: AccountGroup): AccountingResult<AccountGroup> {
        val existing = dao.getGroupById(group.companyId, group.groupId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Group not found"))

        if (existing.isSystem || group.groupId.let { id -> id.startsWith(StandardSystemGroups.SUSPENSE_GROUP_ID) || id.startsWith(StandardSystemGroups.ROUND_OFF_GROUP_ID) }) {
            if (existing.name != group.name) {
                return AccountingResult.Failure(AppError.BusinessRuleViolation("System groups and Suspense control group cannot be renamed."))
            }
            if (existing.parentGroupId != group.parentGroupId) {
                return AccountingResult.Failure(AppError.BusinessRuleViolation("System groups and Suspense control group cannot be reparented."))
            }
            if (existing.primaryGroup != group.primaryGroup) {
                return AccountingResult.Failure(AppError.BusinessRuleViolation("System groups and Suspense control group cannot be reclassified."))
            }
        }

        val entity = GroupEntity(
            groupId = group.groupId,
            companyId = group.companyId,
            name = group.name,
            primaryGroup = group.primaryGroup,
            parentGroupId = group.parentGroupId,
            isSystem = group.isSystem,
            affectsGrossProfit = group.affectsGrossProfit,
            displayOrder = group.displayOrder
        )
        dao.updateGroup(entity)
        return AccountingResult.Success(group)
    }

    suspend fun deleteGroup(companyId: String, groupId: String): AccountingResult<Unit> {
        val existing = dao.getGroupById(companyId, groupId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Group not found"))

        if (existing.isSystem || groupId.let { id -> id.startsWith(StandardSystemGroups.SUSPENSE_GROUP_ID) || id.startsWith(StandardSystemGroups.ROUND_OFF_GROUP_ID) }) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("System groups and Suspense control group are permanent and cannot be deleted."))
        }

        val childLedgers = dao.getLedgersByGroupId(companyId, groupId)
        if (childLedgers.isNotEmpty()) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Group contains active ledgers and cannot be deleted."))
        }

        dao.deleteGroup(companyId, groupId)
        return AccountingResult.Success(Unit)
    }

    fun getLedgers(companyId: String): Flow<List<Ledger>> = dao.getLedgersByCompany(companyId).map { list ->
        val groups = dao.getGroupsByCompany(companyId).first().associateBy { it.groupId }
        list.map {
            val group = groups[it.groupId]
            Ledger(
                ledgerId = it.ledgerId,
                companyId = it.companyId,
                groupId = it.groupId,
                groupName = group?.name ?: "General",
                primaryGroup = group?.primaryGroup ?: PrimaryGroup.ASSETS,
                name = it.name,
                code = it.code,
                openingBalance = Money.fromPaise(it.openingBalancePaise),
                openingBalanceType = it.openingBalanceType,
                currentBalance = Money.fromPaise(it.currentBalancePaise),
                currentBalanceType = it.currentBalanceType,
                gstin = it.gstin,
                pan = it.pan,
                stateCode = it.stateCode,
                gstRegistrationStatus = it.gstRegistrationStatus?.let { raw -> runCatching { GstRegistrationStatus.valueOf(raw) }.getOrNull() },
                email = it.email,
                phone = it.phone,
                address = it.address,
                bankName = it.bankName,
                bankAccountNumber = it.bankAccountNumber,
                bankIfsc = it.bankIfsc,
                bankBranch = it.bankBranch,
                isSystem = it.isSystem,
                isActive = it.isActive,
                hsnSacCode = it.hsnSacCode,
                defaultTaxRate = it.defaultTaxRate
            )
        }
    }

    suspend fun updateLedger(ledger: Ledger): AccountingResult<Ledger> {
        val existing = dao.getLedgerById(ledger.companyId, ledger.ledgerId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Ledger not found"))

        if (existing.isSystem || ledger.ledgerId.let { id -> id.startsWith(StandardSystemGroups.SUSPENSE_LEDGER_ID) || id.startsWith(StandardSystemGroups.ROUND_OFF_LEDGER_ID) }) {
            if (existing.name != ledger.name) {
                return AccountingResult.Failure(AppError.BusinessRuleViolation("System ledgers and Suspense A/c name is protected and cannot be renamed."))
            }
            if (existing.groupId != ledger.groupId) {
                return AccountingResult.Failure(AppError.BusinessRuleViolation("System ledgers and Suspense A/c parent group is protected and cannot be changed."))
            }
        }

        val entity = LedgerEntity(
            ledgerId = ledger.ledgerId,
            companyId = ledger.companyId,
            groupId = ledger.groupId,
            name = ledger.name,
            code = ledger.code,
            openingBalancePaise = ledger.openingBalance.paise,
            openingBalanceType = ledger.openingBalanceType,
            currentBalancePaise = ledger.currentBalance.paise,
            currentBalanceType = ledger.currentBalanceType,
            gstin = ledger.gstin,
            pan = ledger.pan,
            stateCode = ledger.stateCode,
            gstRegistrationStatus = ledger.gstRegistrationStatus?.name,
            email = ledger.email,
            phone = ledger.phone,
            address = ledger.address,
            bankName = ledger.bankName,
            bankAccountNumber = ledger.bankAccountNumber,
            bankIfsc = ledger.bankIfsc,
            bankBranch = ledger.bankBranch,
            isSystem = ledger.isSystem,
            isActive = ledger.isActive,
            hsnSacCode = ledger.hsnSacCode,
            defaultTaxRate = ledger.defaultTaxRate
        )
        dao.updateLedger(entity)
        return AccountingResult.Success(ledger)
    }

    /** Shared builder for the CREATE_LEDGER/DELETE_LEDGER [SyncEvent]s (Phase 6, Priority 6.4). */
    private fun ledgerSyncEvent(operation: SyncOperation, idempotencyKey: String, entity: LedgerEntity): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(),
        idempotencyKey = idempotencyKey,
        companyId = entity.companyId,
        financialYearId = "",
        operation = operation.name,
        aggregateType = SyncAggregateType.LEDGER.name,
        aggregateId = entity.ledgerId,
        ledger = SyncLedgerDto(
            ledgerId = entity.ledgerId, groupId = entity.groupId, name = entity.name, code = entity.code,
            openingBalancePaise = entity.openingBalancePaise, openingBalanceType = entity.openingBalanceType.name,
            gstin = entity.gstin, pan = entity.pan, stateCode = entity.stateCode,
            hsnSacCode = entity.hsnSacCode, defaultTaxRate = entity.defaultTaxRate
        )
    )

    suspend fun createLedger(ledger: Ledger): AccountingResult<Ledger> {
        val entity = LedgerEntity(
            ledgerId = ledger.ledgerId.ifBlank { "LED_${UUID.randomUUID().toString().take(8)}_${ledger.companyId}" },
            companyId = ledger.companyId,
            groupId = ledger.groupId,
            name = ledger.name,
            code = ledger.code,
            openingBalancePaise = ledger.openingBalance.paise,
            openingBalanceType = ledger.openingBalanceType,
            currentBalancePaise = ledger.openingBalance.paise,
            currentBalanceType = ledger.openingBalanceType,
            gstin = ledger.gstin,
            pan = ledger.pan,
            stateCode = ledger.stateCode,
            gstRegistrationStatus = ledger.gstRegistrationStatus?.name,
            email = ledger.email,
            phone = ledger.phone,
            address = ledger.address,
            bankName = ledger.bankName,
            bankAccountNumber = ledger.bankAccountNumber,
            bankIfsc = ledger.bankIfsc,
            bankBranch = ledger.bankBranch,
            isSystem = ledger.isSystem,
            isActive = ledger.isActive,
            hsnSacCode = ledger.hsnSacCode,
            defaultTaxRate = ledger.defaultTaxRate
        )
        dao.insertLedger(entity)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = ledger.companyId,
                financialYearId = "",
                action = AuditAction.CREATE_LEDGER,
                entityType = "Ledger",
                entityId = entity.ledgerId,
                description = "Created ledger account '${ledger.name}' under group ID '${ledger.groupId}'",
                performedBy = "ADMIN",
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )

        // Queue in Outbox - complete versioned SyncEvent (Phase 6), not a bare `{"name":...}` string.
        val createIdempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(),
                companyId = ledger.companyId,
                entityType = "Ledger",
                entityId = entity.ledgerId,
                operation = "INSERT",
                payloadJson = SyncEventSerializer.toJson(ledgerSyncEvent(SyncOperation.CREATE_LEDGER, createIdempotencyKey, entity)),
                idempotencyKey = createIdempotencyKey,
                syncState = SyncState.PENDING,
                retryCount = 0,
                lastError = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        return AccountingResult.Success(ledger.copy(ledgerId = entity.ledgerId))
    }

    /**
     * Rule 23: LEDGER DELETION
     * ONLY allow ledger deletion when: accountingEntryCount == 0
     * If: accountingEntryCount > 0 then: DELETE REJECTED
     * Historical accounting must remain intact.
     * Special: Suspense A/c & System Ledgers are never deletable.
     */
    suspend fun deleteLedgerSafely(
        companyId: String,
        ledgerId: String,
        userId: String = "ADMIN"
    ): AccountingResult<Unit> {
        val ledger = dao.getLedgerById(companyId, ledgerId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Ledger account not found"))

        if (ledger.isSystem || ledgerId.let { id -> id.startsWith(StandardSystemGroups.SUSPENSE_LEDGER_ID) || id.startsWith(StandardSystemGroups.ROUND_OFF_LEDGER_ID) }) {
            return AccountingResult.Failure(
                AppError.BusinessRuleViolation("System ledgers and Suspense A/c are permanent and cannot be deleted.")
            )
        }

        val entryCount = dao.countJournalEntriesForLedger(companyId, ledgerId)
        if (entryCount > 0) {
            return AccountingResult.Failure(
                AppError.BusinessRuleViolation(
                    "DELETE REJECTED: Ledger '${ledger.name}' contains $entryCount active accounting entries. Historical accounting audit integrity must remain intact."
                )
            )
        }

        dao.deleteLedger(companyId, ledgerId)

        // Queue deletion in outbox - complete versioned SyncEvent (Phase 6).
        val deleteIdempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(),
                companyId = companyId,
                entityType = "Ledger",
                entityId = ledgerId,
                operation = "DELETE",
                payloadJson = SyncEventSerializer.toJson(ledgerSyncEvent(SyncOperation.DELETE_LEDGER, deleteIdempotencyKey, ledger)),
                idempotencyKey = deleteIdempotencyKey,
                syncState = SyncState.PENDING,
                retryCount = 0,
                lastError = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Audit deletion
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = "",
                action = AuditAction.DELETE_LEDGER,
                entityType = "Ledger",
                entityId = ledgerId,
                description = "Deleted unused ledger account '${ledger.name}' (0 journal entries)",
                performedBy = userId,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )

        return AccountingResult.Success(Unit)
    }

    // ==================== BRANCHES / DIVISIONS ====================
    fun getBranches(companyId: String): Flow<List<Branch>> = dao.getBranchesByCompany(companyId).map { list ->
        list.map {
            Branch(
                branchId = it.branchId,
                companyId = it.companyId,
                code = it.code,
                name = it.name,
                gstin = it.gstin,
                stateCode = it.stateCode,
                address = it.address,
                isHeadOffice = it.isHeadOffice,
                isActive = it.isActive
            )
        }
    }

    suspend fun createBranch(branch: Branch): AccountingResult<Branch> {
        val entity = BranchEntity(
            branchId = branch.branchId.ifBlank { "BR_${UUID.randomUUID().toString().take(8)}_${branch.companyId}" },
            companyId = branch.companyId,
            code = branch.code,
            name = branch.name,
            gstin = branch.gstin,
            stateCode = branch.stateCode,
            address = branch.address,
            isHeadOffice = branch.isHeadOffice,
            isActive = branch.isActive
        )
        dao.insertBranch(entity)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = branch.companyId,
                financialYearId = "",
                action = AuditAction.CREATE_BRANCH,
                entityType = "Branch",
                entityId = entity.branchId,
                description = "Created branch/division '${branch.name}' (${branch.code})",
                performedBy = "ADMIN",
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )

        return AccountingResult.Success(branch.copy(branchId = entity.branchId))
    }

    // ==================== VOUCHER POSTING & DOUBLE ENTRY ====================
    suspend fun generateNextVoucherNumber(companyId: String, fyId: String, type: VoucherType): String {
        val count = dao.getVoucherCountByType(companyId, fyId, type)
        val fy = dao.getFinancialYearById(fyId)
        val yearPart = fy?.startDate?.take(4) ?: "2026"
        return "${type.prefix}$yearPart-${String.format("%04d", count + 1)}"
    }

    /** Phase 7B - the document-number sequence, fully independent of [generateNextVoucherNumber]:
     * mirrors its exact shape (count existing rows of this type for the company+FY, format
     * prefix+year+zero-padded count) with its own separate counter, so a document's own number
     * (e.g. "SI-2026-0001") is never assumed to equal its eventual Voucher's number
     * (e.g. "INV-2026-0001"). Posting-document types are counted against the `invoices` table;
     * non-posting types against `trade_documents`. */
    suspend fun generateNextDocumentNumber(companyId: String, fyId: String, documentType: DocumentType): String {
        val count = if (documentType.isPostingDocument) {
            dao.getInvoiceCountByType(companyId, fyId, InvoiceType.valueOf(documentType.name))
        } else {
            dao.getTradeDocumentCountByType(companyId, fyId, documentType)
        }
        val fy = dao.getFinancialYearById(fyId)
        val yearPart = fy?.startDate?.take(4) ?: "2026"
        return "${documentType.prefix}$yearPart-${String.format("%04d", count + 1)}"
    }

    /**
     * Maps a failure out of [DatabaseTransaction]'s `runCatching { database.withTransaction { ... } }`
     * back to a typed [AppError]. [AccountingTransactionException] carries the exact domain error;
     * anything else (e.g. a genuine SQLite/IO failure) is a [AppError.SystemError].
     */
    private fun mapTransactionFailure(throwable: Throwable?): AppError =
        when (throwable) {
            is AccountingTransactionException -> throwable.appError
            else -> AppError.SystemError(throwable?.message ?: "Accounting transaction failed")
        }

    /**
     * Finds the accounting period whose date range contains [date] within [financialYearId].
     * Shared by [postVoucher] and [deleteVoucherSafely] so posting and cancellation apply the
     * identical period-lock rule (Project Principle 4: locked/audit-locked periods reject
     * postings, edits, AND cancellations).
     */
    private suspend fun findMatchingPeriod(financialYearId: String, date: LocalDate): AccountingPeriod? {
        val periods = dao.getPeriodsByFinancialYear(financialYearId).first()
        val matching = periods.find {
            val start = safeParseDate(it.startDate)
            val end = safeParseDate(it.endDate)
            !date.isBefore(start) && !date.isAfter(end)
        }
        return matching?.let {
            AccountingPeriod(
                periodId = it.periodId,
                companyId = it.companyId,
                financialYearId = it.financialYearId,
                name = it.name,
                startDate = safeParseDate(it.startDate),
                endDate = safeParseDate(it.endDate),
                status = it.status,
                lockedAt = it.lockedAt,
                lockedBy = it.lockedBy
            )
        }
    }

    /**
     * Posts a voucher through the single authoritative atomic posting path
     * ([DatabaseTransaction.postVoucherAtomic]). Pre-validation (double-entry, financial year,
     * period lock, tenant isolation) runs before the atomic write; duplicate voucher-number and
     * idempotency-key checks run inside the transaction itself.
     */
    suspend fun postVoucher(
        voucher: Voucher,
        idempotencyKey: String = UUID.randomUUID().toString(),
        stockLines: List<VoucherStockLine> = emptyList(),
        gstTransactions: List<GstTransaction> = emptyList()
    ): AccountingResult<Voucher> {
        val companyId = voucher.companyId
        val fy = dao.getFinancialYearById(voucher.financialYearId)
        val domainFy = fy?.let {
            FinancialYear(
                financialYearId = it.financialYearId,
                companyId = it.companyId,
                fyCode = it.fyCode,
                startDate = safeParseDate(it.startDate),
                endDate = safeParseDate(it.endDate),
                isCurrent = it.isCurrent,
                isLocked = it.isLocked,
                lockedAt = it.lockedAt,
                lockedBy = it.lockedBy
            )
        }

        val allLedgers = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        val validLedgerIds = allLedgers.keys

        // Find active accounting period for the voucher date
        val domainPeriod = findMatchingPeriod(voucher.financialYearId, voucher.date)

        // 1. Strict Double-Entry Validation
        val validationResult = DoubleEntryValidator.validate(
            voucher = voucher,
            activeFinancialYear = domainFy,
            activePeriod = domainPeriod,
            validLedgerIdsForCompany = validLedgerIds
        )

        if (validationResult is AccountingResult.Failure) {
            return validationResult
        }

        if (dbTransaction == null) {
            return AccountingResult.Failure(AppError.SystemError("Database transaction unavailable: cannot post voucher atomically."))
        }

        // 2. Build entities for the atomic posting engine
        val voucherEntity = VoucherEntity(
            voucherId = voucher.voucherId,
            companyId = voucher.companyId,
            financialYearId = voucher.financialYearId,
            voucherNumber = voucher.voucherNumber,
            voucherType = voucher.voucherType,
            date = voucher.date.toString(),
            referenceNumber = voucher.referenceNumber,
            narration = voucher.narration,
            totalAmountPaise = voucher.totalDebits.paise,
            isPosted = true,
            isCancelled = false,
            syncState = SyncState.PENDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            createdBy = voucher.createdBy,
            partyGstin = voucher.partyGstin,
            isGstApplicable = voucher.isGstApplicable,
            referenceVoucherId = voucher.referenceVoucherId,
            paymentMode = voucher.paymentMode
        )

        val itemEntities = voucher.items.mapIndexed { index, item ->
            JournalItemEntity(
                itemId = item.itemId.ifBlank { UUID.randomUUID().toString() },
                voucherId = voucher.voucherId,
                companyId = voucher.companyId,
                financialYearId = voucher.financialYearId,
                ledgerId = item.ledgerId,
                type = item.type,
                amountPaise = item.amount.paise,
                narration = item.narration,
                lineOrder = index + 1
            )
        }

        val stockLineEntities = stockLines.mapIndexed { index, line ->
            VoucherStockLineEntity(
                lineId = line.lineId.ifBlank { UUID.randomUUID().toString() },
                voucherId = voucher.voucherId,
                companyId = voucher.companyId,
                financialYearId = voucher.financialYearId,
                itemId = line.itemId,
                direction = line.direction,
                quantityRaw = line.quantity.rawValue,
                ratePaise = line.rate.paise,
                amountPaise = line.amount.paise,
                lineOrder = index + 1
            )
        }

        val gstTransactionEntities = gstTransactions.map { gt ->
            GstTransactionEntity(
                gstTransactionId = gt.gstTransactionId, companyId = gt.companyId, financialYearId = gt.financialYearId,
                voucherId = voucher.voucherId, voucherType = gt.voucherType, partyLedgerId = gt.partyLedgerId,
                partyGstin = gt.partyGstin, placeOfSupply = gt.placeOfSupply, supplyType = gt.supplyType,
                itemId = gt.itemId, hsnSacCode = gt.hsnSacCode, quantityRaw = gt.quantity?.rawValue,
                taxableAmountPaise = gt.taxableAmount.paise, gstRatePercent = gt.gstRatePercent,
                cgstPaise = gt.cgst.paise, sgstPaise = gt.sgst.paise, igstPaise = gt.igst.paise, cessPaise = gt.cess.paise,
                direction = gt.direction, lineOrder = gt.lineOrder, createdAt = System.currentTimeMillis(),
                chargeType = gt.chargeType
            )
        }

        // 3-8. Atomic header insert, journal lines, ledger balances, audit log, outbox enqueue,
        // (Phase 4, additive) stock movements when stockLines is non-empty, and (Phase 5, additive)
        // GST transaction facts when gstTransactions is non-empty.
        val result = dbTransaction.postVoucherAtomic(voucherEntity, itemEntities, idempotencyKey, voucher.createdBy, stockLineEntities, gstTransactionEntities)
        return if (result.isSuccess) {
            AccountingResult.Success(voucher)
        } else {
            AccountingResult.Failure(mapTransactionFailure(result.exceptionOrNull()))
        }
    }

    /**
     * GST-only Sale (Architecture Checkpoint follow-up, Option B continuation) - the smallest
     * repository capability that can persist a real [GstTransaction] with NO accounting [Voucher]
     * at all, for a company that does not maintain accounting. Mirrors [postVoucher]'s own
     * shape (resolve inputs -> validate company/FY/ledger -> delegate to the domain engine ->
     * persist atomically -> map the result) rather than inventing a different one.
     *
     * Deliberately does NOT call [postVoucher]/[com.example.accounting.core.database.VoucherPostingEngine] -
     * there is no [Voucher] to build. [com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlySale]
     * (the one, same GST engine every accounting-enabled Sale already uses) computes the GST facts;
     * this function only resolves company/FY/customer-ledger and persists the result. No UI calls
     * this yet (out of scope for this pass) - a future ViewModel entry point would call this
     * exactly the way `postSaleInvoice` calls [postVoucher] today.
     */
    suspend fun postGstOnlySale(
        companyId: String,
        financialYearId: String,
        customerLedgerId: String,
        lines: List<com.example.accounting.domain.trading.TradingLineInput>,
        /** D1b - the real invoice/business date; see [GstTransaction.transactionDate]. Required,
         * never defaulted to `createdAt`. */
        date: LocalDate,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): AccountingResult<List<GstTransaction>> {
        val company = dao.getCompanyById(companyId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Company", companyId))
        val fy = dao.getFinancialYearById(financialYearId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("FinancialYear", financialYearId))
        if (fy.companyId != companyId) {
            return AccountingResult.Failure(AppError.ValidationError("Financial year '$financialYearId' does not belong to company '$companyId'."))
        }
        val customerLedger = dao.getLedgerById(companyId, customerLedgerId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Ledger", customerLedgerId))
        if (lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("At least one item line is required."))
        }

        // Place of Supply (Rule 29): never defaulted to the company's own state - that would
        // silently force INTRA_STATE regardless of the customer's actual location. If the ledger
        // has no state on file, surface it instead of guessing. Checked before the dbTransaction
        // guard below (business-rule rejections are surfaced before infrastructure-availability
        // ones, and this ordering also lets this specific rule be exercised in a pure-JVM test with
        // no real Room AppDatabase, matching how far every other business-rule check here already
        // reaches without one).
        if (customerLedger.stateCode.isBlank()) {
            return AccountingResult.Failure(
                AppError.ValidationError("Set a State for customer '${customerLedger.name}' before posting - Place of Supply cannot be determined.")
            )
        }
        if (dbTransaction == null) {
            return AccountingResult.Failure(AppError.SystemError("Database transaction unavailable: cannot post GST transaction atomically."))
        }
        val placeOfSupply = customerLedger.stateCode
        val partyRegistrationStatus = customerLedger.gstRegistrationStatus?.let { raw ->
            runCatching { GstRegistrationStatus.valueOf(raw) }.getOrNull()
        }
        val gstTransactions = com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlySale(
            companyId = companyId, financialYearId = financialYearId,
            customerLedgerId = customerLedgerId, customerGstin = customerLedger.gstin,
            companyStateCode = company.stateCode, placeOfSupply = placeOfSupply, lines = lines,
            date = date, partyGstRegistrationStatus = partyRegistrationStatus
        )

        return persistGstOnlyTransactions(companyId, financialYearId, gstTransactions, idempotencyKey)
    }

    /**
     * D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - the Purchase
     * counterpart of [postGstOnlySale], mirroring its exact shape. Rule 31/RCM stays exactly as
     * explicit here as it already is on the accounting-integrated Purchase path - `chargeType`
     * travels per-line from [lines] into [com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlyPurchase],
     * never inferred from [partyRegistrationStatus]/party type/vendor type/GSTIN/tax rate.
     */
    suspend fun postGstOnlyPurchase(
        companyId: String,
        financialYearId: String,
        supplierLedgerId: String,
        lines: List<com.example.accounting.domain.trading.TradingLineInput>,
        date: LocalDate,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): AccountingResult<List<GstTransaction>> {
        val company = dao.getCompanyById(companyId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Company", companyId))
        val fy = dao.getFinancialYearById(financialYearId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("FinancialYear", financialYearId))
        if (fy.companyId != companyId) {
            return AccountingResult.Failure(AppError.ValidationError("Financial year '$financialYearId' does not belong to company '$companyId'."))
        }
        val supplierLedger = dao.getLedgerById(companyId, supplierLedgerId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Ledger", supplierLedgerId))
        if (lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("At least one item line is required."))
        }

        // Place of Supply (Rule 29) - same anti-guessing rule, and same "checked before the
        // dbTransaction guard" ordering rationale, as postGstOnlySale.
        if (supplierLedger.stateCode.isBlank()) {
            return AccountingResult.Failure(
                AppError.ValidationError("Set a State for supplier '${supplierLedger.name}' before posting - Place of Supply cannot be determined.")
            )
        }
        if (dbTransaction == null) {
            return AccountingResult.Failure(AppError.SystemError("Database transaction unavailable: cannot post GST transaction atomically."))
        }
        val placeOfSupply = supplierLedger.stateCode
        val partyRegistrationStatus = supplierLedger.gstRegistrationStatus?.let { raw ->
            runCatching { GstRegistrationStatus.valueOf(raw) }.getOrNull()
        }
        val gstTransactionsResult = runCatching {
            com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlyPurchase(
                companyId = companyId, financialYearId = financialYearId,
                supplierLedgerId = supplierLedgerId, supplierGstin = supplierLedger.gstin,
                companyStateCode = company.stateCode, placeOfSupply = placeOfSupply, lines = lines,
                date = date, partyGstRegistrationStatus = partyRegistrationStatus
            )
        }
        val gstTransactions = gstTransactionsResult.getOrElse {
            return AccountingResult.Failure(AppError.ValidationError(it.message ?: "Invalid GST-only Purchase line."))
        }

        return persistGstOnlyTransactions(companyId, financialYearId, gstTransactions, idempotencyKey)
    }

    suspend fun postGstOnlyCreditNote(
        companyId: String, financialYearId: String, originalTransactionGroupId: String,
        date: LocalDate, idempotencyKey: String = UUID.randomUUID().toString()
    ) = postGstOnlyNote(isCredit = true, companyId, financialYearId, originalTransactionGroupId, date, idempotencyKey)

    suspend fun postGstOnlyDebitNote(
        companyId: String, financialYearId: String, originalTransactionGroupId: String,
        date: LocalDate, idempotencyKey: String = UUID.randomUUID().toString()
    ) = postGstOnlyNote(isCredit = false, companyId, financialYearId, originalTransactionGroupId, date, idempotencyKey)

    /**
     * D1b - GST-only Credit Note (against a GST-only Sale) / Debit Note (against a GST-only
     * Purchase). Mirrors [postNote]'s own shape (look up the original -> validate its type/state ->
     * delegate to the domain engine -> persist) but looks the original up by
     * [GstTransactionEntity.transactionGroupId] instead of a voucherId, since a GST-only
     * transaction has none - see [com.example.accounting.data.local.dao.AccountingDao.getGstTransactionsByGroupId]'s
     * own KDoc for why this is the correct lookup key.
     */
    private suspend fun postGstOnlyNote(
        isCredit: Boolean, companyId: String, financialYearId: String,
        originalTransactionGroupId: String, date: LocalDate, idempotencyKey: String
    ): AccountingResult<List<GstTransaction>> {
        val fy = dao.getFinancialYearById(financialYearId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("FinancialYear", financialYearId))
        if (fy.companyId != companyId) {
            return AccountingResult.Failure(AppError.ValidationError("Financial year '$financialYearId' does not belong to company '$companyId'."))
        }
        val originalEntities = dao.getGstTransactionsByGroupId(companyId, originalTransactionGroupId)
        if (originalEntities.isEmpty()) {
            return AccountingResult.Failure(AppError.ResourceNotFound("Original GST-only transaction", originalTransactionGroupId))
        }
        val expectedType = if (isCredit) VoucherType.SALES else VoucherType.PURCHASE
        if (originalEntities.any { it.voucherType != expectedType }) {
            return AccountingResult.Failure(
                AppError.ValidationError("A GST-only ${if (isCredit) "Credit" else "Debit"} Note must reference a GST-only ${expectedType.displayName}.")
            )
        }
        if (dbTransaction == null) {
            return AccountingResult.Failure(AppError.SystemError("Database transaction unavailable: cannot post GST transaction atomically."))
        }
        val noteVoucherType = if (isCredit) VoucherType.CREDIT_NOTE else VoucherType.DEBIT_NOTE
        val originalDomain = originalEntities.map { it.toDomainGstTransaction() }
        val gstTransactions = com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlyNote(
            noteVoucherType = noteVoucherType, originalGstTransactions = originalDomain, date = date
        )
        return persistGstOnlyTransactions(companyId, financialYearId, gstTransactions, idempotencyKey)
    }

    /** D1b - the shared entity-mapping/Outbox-enqueue/atomic-persist tail every GST-only posting
     * function (Sale/Purchase/Credit Note/Debit Note) ends with - extracted once these became four
     * near-identical call sites instead of one, per this project's "reuse over duplication" rule. */
    private suspend fun persistGstOnlyTransactions(
        companyId: String, financialYearId: String, gstTransactions: List<GstTransaction>, idempotencyKey: String
    ): AccountingResult<List<GstTransaction>> {
        val entities = gstTransactions.map { gt ->
            GstTransactionEntity(
                gstTransactionId = gt.gstTransactionId, companyId = gt.companyId, financialYearId = gt.financialYearId,
                voucherId = null, voucherType = gt.voucherType, partyLedgerId = gt.partyLedgerId,
                partyGstin = gt.partyGstin, placeOfSupply = gt.placeOfSupply, supplyType = gt.supplyType,
                itemId = gt.itemId, hsnSacCode = gt.hsnSacCode, quantityRaw = gt.quantity?.rawValue,
                taxableAmountPaise = gt.taxableAmount.paise, gstRatePercent = gt.gstRatePercent,
                cgstPaise = gt.cgst.paise, sgstPaise = gt.sgst.paise, igstPaise = gt.igst.paise, cessPaise = gt.cess.paise,
                direction = gt.direction, lineOrder = gt.lineOrder, createdAt = System.currentTimeMillis(),
                chargeType = gt.chargeType, supplyNature = gt.supplyNature,
                transactionGroupId = gt.transactionGroupId, transactionDate = gt.transactionDate?.toString(),
                partyGstRegistrationStatus = gt.partyGstRegistrationStatus?.name
            )
        }

        // D1b: every line already carries the real, explicit transactionGroupId (generated once
        // per posting call by the engine) - this is what correlates the Outbox aggregate now,
        // never "the first line's own id" as a stand-in.
        val aggregateId = entities.first().transactionGroupId
        val outboxItem = OutboxSyncEntity(
            syncId = UUID.randomUUID().toString(), companyId = companyId,
            entityType = "GST_TRANSACTION", entityId = aggregateId, operation = "INSERT",
            payloadJson = SyncEventSerializer.toJson(
                SyncEvent(
                    eventId = UUID.randomUUID().toString(), idempotencyKey = idempotencyKey,
                    companyId = companyId, financialYearId = financialYearId,
                    operation = SyncOperation.POST_GST_TRANSACTION.name,
                    aggregateType = SyncAggregateType.GST_TRANSACTION.name,
                    aggregateId = aggregateId,
                    voucher = null,
                    gstTransactions = entities.map {
                        SyncGstTransactionDto(
                            gstTransactionId = it.gstTransactionId, voucherType = it.voucherType.name, partyLedgerId = it.partyLedgerId,
                            partyGstin = it.partyGstin, placeOfSupply = it.placeOfSupply, supplyType = it.supplyType.name,
                            itemId = it.itemId, hsnSacCode = it.hsnSacCode, quantityRaw = it.quantityRaw,
                            taxableAmountPaise = it.taxableAmountPaise, gstRatePercent = it.gstRatePercent,
                            cgstPaise = it.cgstPaise, sgstPaise = it.sgstPaise, igstPaise = it.igstPaise, cessPaise = it.cessPaise,
                            direction = it.direction.name, lineOrder = it.lineOrder,
                            chargeType = it.chargeType.name, supplyNature = it.supplyNature.name,
                            transactionGroupId = it.transactionGroupId, transactionDate = it.transactionDate,
                            partyGstRegistrationStatus = it.partyGstRegistrationStatus
                        )
                    }
                )
            ),
            idempotencyKey = idempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )

        val result = dbTransaction!!.postGstOnlyTransactionsAtomic(entities, outboxItem)
        return if (result.isSuccess) {
            AccountingResult.Success(gstTransactions)
        } else {
            AccountingResult.Failure(mapTransactionFailure(result.exceptionOrNull()))
        }
    }

    private suspend fun RecurringVoucherScheduleEntity.toDomain(): RecurringVoucherSchedule {
        val lineEntities = dao.getLinesForRecurringVoucherSchedule(scheduleId)
        return RecurringVoucherSchedule(
            scheduleId = scheduleId,
            companyId = companyId,
            financialYearId = financialYearId,
            name = name,
            voucherType = voucherType,
            frequency = frequency,
            dayOfMonth = dayOfMonth,
            narration = narration,
            startDate = safeParseDate(startDate),
            endDate = endDate?.let { safeParseDate(it) },
            lines = lineEntities.sortedBy { it.lineOrder }.map {
                RecurringVoucherLine(
                    ledgerId = it.ledgerId,
                    type = it.type,
                    amountPaise = it.amountPaise,
                    narration = it.narration,
                    lineOrder = it.lineOrder
                )
            },
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /** Recurring Voucher Engine (Phase 7F, "B") - persists a schedule TEMPLATE only. This never
     * posts anything; see [generateRecurringVoucherIfDue] for the sole path to an actual voucher. */
    suspend fun createRecurringVoucherSchedule(schedule: RecurringVoucherSchedule): AccountingResult<RecurringVoucherSchedule> {
        if (schedule.lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("Recurring voucher schedule must have at least one line"))
        }
        dao.insertRecurringVoucherSchedule(
            RecurringVoucherScheduleEntity(
                scheduleId = schedule.scheduleId,
                companyId = schedule.companyId,
                financialYearId = schedule.financialYearId,
                name = schedule.name,
                voucherType = schedule.voucherType,
                frequency = schedule.frequency,
                dayOfMonth = schedule.dayOfMonth,
                narration = schedule.narration,
                startDate = schedule.startDate.toString(),
                endDate = schedule.endDate?.toString(),
                isActive = schedule.isActive,
                createdAt = schedule.createdAt,
                updatedAt = schedule.updatedAt
            )
        )
        val lineEntities = schedule.lines.mapIndexed { index, line ->
            RecurringVoucherLineEntity(
                lineId = UUID.randomUUID().toString(),
                scheduleId = schedule.scheduleId,
                ledgerId = line.ledgerId,
                type = line.type,
                amountPaise = line.amountPaise,
                narration = line.narration,
                lineOrder = if (line.lineOrder != 0) line.lineOrder else index + 1
            )
        }
        dao.insertRecurringVoucherLines(lineEntities)
        return AccountingResult.Success(
            schedule.copy(lines = lineEntities.map {
                RecurringVoucherLine(it.ledgerId, it.type, it.amountPaise, it.narration, it.lineOrder)
            })
        )
    }

    fun getRecurringVoucherSchedules(companyId: String): Flow<List<RecurringVoucherSchedule>> =
        dao.getActiveRecurringVoucherSchedules(companyId).map { entities -> entities.map { it.toDomain() } }

    private suspend fun RecurringVoucherDraftEntity.toDomain(): RecurringVoucherDraft {
        val lineEntities = dao.getLinesForRecurringVoucherDraft(draftId)
        return RecurringVoucherDraft(
            draftId = draftId,
            companyId = companyId,
            scheduleId = scheduleId,
            financialYearId = financialYearId,
            periodKey = periodKey,
            voucherType = voucherType,
            date = safeParseDate(date),
            narration = narration,
            lines = lineEntities.sortedBy { it.lineOrder }.map {
                RecurringVoucherDraftLine(
                    ledgerId = it.ledgerId,
                    type = it.type,
                    amountPaise = it.amountPaise,
                    narration = it.narration,
                    lineOrder = it.lineOrder
                )
            },
            status = status,
            generatedVoucherId = generatedVoucherId,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /**
     * The sole path from a due [RecurringVoucherSchedule] to a review-only
     * [RecurringVoucherDraft] candidate. **This never posts anything** - it never calls
     * [postVoucher], never touches `vouchers`/`journal_items`, and never affects any ledger
     * balance, GST figure, or inventory movement. A generated draft is inert until a human
     * explicitly calls [postRecurringVoucherDraft].
     *
     * Idempotency: before ever inserting a draft, checks the `(scheduleId, periodKey)` unique
     * index on [RecurringVoucherDraftEntity] and returns
     * [RecurringVoucherGenerationOutcome.AlreadyGenerated] if a candidate already exists for this
     * period (regardless of its status - pending, posted, or discarded) - so a daily/monthly
     * automation cycle running twice on the same day, or retried after a partial failure, can
     * never propose the same rent/depreciation voucher twice.
     */
    suspend fun generateRecurringVoucherIfDue(
        companyId: String,
        scheduleId: String,
        asOfDate: LocalDate = LocalDate.now()
    ): AccountingResult<RecurringVoucherGenerationOutcome> {
        val scheduleEntity = dao.getRecurringVoucherScheduleById(companyId, scheduleId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("RecurringVoucherSchedule", scheduleId))
        val schedule = scheduleEntity.toDomain()

        if (!RecurringVoucherPeriod.isDue(schedule, asOfDate)) {
            return AccountingResult.Success(RecurringVoucherGenerationOutcome.NotDue("Schedule '${schedule.name}' is not due on $asOfDate"))
        }

        val periodKey = RecurringVoucherPeriod.periodKeyFor(schedule.frequency, asOfDate)
        val existingDraft = dao.getRecurringVoucherDraftForPeriod(scheduleId, periodKey)
        if (existingDraft != null) {
            return AccountingResult.Success(RecurringVoucherGenerationOutcome.AlreadyGenerated(periodKey))
        }

        if (schedule.lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("Recurring voucher schedule '${schedule.name}' has no lines"))
        }

        val draftId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertRecurringVoucherDraft(
            RecurringVoucherDraftEntity(
                draftId = draftId,
                companyId = companyId,
                scheduleId = scheduleId,
                financialYearId = schedule.financialYearId,
                periodKey = periodKey,
                voucherType = schedule.voucherType,
                date = asOfDate.toString(),
                narration = schedule.narration.ifBlank { "Recurring: ${schedule.name}" },
                status = RecurringVoucherDraftStatus.PENDING_REVIEW,
                generatedVoucherId = null,
                createdAt = now,
                updatedAt = now
            )
        )
        val draftLines = schedule.lines.mapIndexed { index, line ->
            RecurringVoucherDraftLineEntity(
                draftLineId = UUID.randomUUID().toString(),
                draftId = draftId,
                ledgerId = line.ledgerId,
                type = line.type,
                amountPaise = line.amountPaise,
                narration = line.narration,
                lineOrder = index + 1
            )
        }
        dao.insertRecurringVoucherDraftLines(draftLines)

        return AccountingResult.Success(RecurringVoucherGenerationOutcome.DraftGenerated(draftId, periodKey))
    }

    /** Drafts awaiting user review for a company - defaults to [RecurringVoucherDraftStatus.PENDING_REVIEW]. */
    fun getRecurringVoucherDrafts(
        companyId: String,
        status: RecurringVoucherDraftStatus = RecurringVoucherDraftStatus.PENDING_REVIEW
    ): Flow<List<RecurringVoucherDraft>> =
        dao.getRecurringVoucherDraftsByStatus(companyId, status).map { entities -> entities.map { it.toDomain() } }

    /** Lets the user edit a still-pending draft's date/narration/lines before posting it - a
     * [RecurringVoucherDraftStatus.POSTED] or [RecurringVoucherDraftStatus.DISCARDED] draft is
     * immutable (that decision is already final). */
    suspend fun updateRecurringVoucherDraft(companyId: String, draft: RecurringVoucherDraft): AccountingResult<RecurringVoucherDraft> {
        val existing = dao.getRecurringVoucherDraftById(companyId, draft.draftId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("RecurringVoucherDraft", draft.draftId))
        if (existing.status != RecurringVoucherDraftStatus.PENDING_REVIEW) {
            return AccountingResult.Failure(AppError.ValidationError("Only a pending-review draft can be edited."))
        }
        if (draft.lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("A recurring voucher draft must have at least one line."))
        }

        dao.updateRecurringVoucherDraft(
            existing.copy(
                date = draft.date.toString(),
                narration = draft.narration,
                updatedAt = System.currentTimeMillis()
            )
        )
        dao.deleteLinesForRecurringVoucherDraft(draft.draftId)
        val lineEntities = draft.lines.mapIndexed { index, line ->
            RecurringVoucherDraftLineEntity(
                draftLineId = UUID.randomUUID().toString(),
                draftId = draft.draftId,
                ledgerId = line.ledgerId,
                type = line.type,
                amountPaise = line.amountPaise,
                narration = line.narration,
                lineOrder = if (line.lineOrder != 0) line.lineOrder else index + 1
            )
        }
        dao.insertRecurringVoucherDraftLines(lineEntities)

        return AccountingResult.Success(draft.copy(status = RecurringVoucherDraftStatus.PENDING_REVIEW, generatedVoucherId = null))
    }

    /** The user rejects a pending draft - terminal, keeps the row (audit trail), never re-proposed
     * for the same `(scheduleId, periodKey)` again. */
    suspend fun discardRecurringVoucherDraft(companyId: String, draftId: String): AccountingResult<Unit> {
        val existing = dao.getRecurringVoucherDraftById(companyId, draftId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("RecurringVoucherDraft", draftId))
        if (existing.status != RecurringVoucherDraftStatus.PENDING_REVIEW) {
            return AccountingResult.Failure(AppError.ValidationError("Only a pending-review draft can be discarded."))
        }
        dao.updateRecurringVoucherDraft(existing.copy(status = RecurringVoucherDraftStatus.DISCARDED, updatedAt = System.currentTimeMillis()))
        return AccountingResult.Success(Unit)
    }

    /**
     * The **only** function anywhere that turns a recurring voucher draft into a real voucher -
     * called exclusively in direct response to an explicit user "Post" action, never by
     * automation. Builds a plain [Voucher] from the draft's (possibly user-edited) lines and calls
     * the existing, unmodified [postVoucher] - the exact same [DoubleEntryValidator]/period-lock/
     * atomic-transaction path every other voucher in this app already goes through. There is no
     * second posting mechanism.
     */
    suspend fun postRecurringVoucherDraft(
        companyId: String,
        draftId: String,
        postedBy: String = "SENIOR_ACCOUNTANT"
    ): AccountingResult<Voucher> {
        val draftEntity = dao.getRecurringVoucherDraftById(companyId, draftId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("RecurringVoucherDraft", draftId))
        if (draftEntity.status != RecurringVoucherDraftStatus.PENDING_REVIEW) {
            return AccountingResult.Failure(AppError.ValidationError("This draft has already been ${draftEntity.status.name.lowercase()} and cannot be posted again."))
        }
        val draft = draftEntity.toDomain()
        if (draft.lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("A recurring voucher draft must have at least one line."))
        }

        val allLedgers = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        val voucherId = UUID.randomUUID().toString()
        val voucherNumber = generateNextVoucherNumber(companyId, draft.financialYearId, draft.voucherType)

        val journalItems = draft.lines.mapIndexed { index, line ->
            JournalItem(
                itemId = UUID.randomUUID().toString(),
                voucherId = voucherId,
                companyId = companyId,
                financialYearId = draft.financialYearId,
                ledgerId = line.ledgerId,
                ledgerName = allLedgers[line.ledgerId]?.name ?: "",
                type = line.type,
                amount = Money(line.amountPaise),
                narration = line.narration,
                lineOrder = index + 1
            )
        }

        val voucher = Voucher(
            voucherId = voucherId,
            companyId = companyId,
            financialYearId = draft.financialYearId,
            voucherNumber = voucherNumber,
            voucherType = draft.voucherType,
            date = draft.date,
            narration = draft.narration,
            items = journalItems,
            createdBy = postedBy
        )

        return when (val postResult = postVoucher(voucher, idempotencyKey = "RECURRING_DRAFT_$draftId")) {
            is AccountingResult.Failure -> AccountingResult.Failure(postResult.error)
            is AccountingResult.Success -> {
                dao.updateRecurringVoucherDraft(
                    draftEntity.copy(status = RecurringVoucherDraftStatus.POSTED, generatedVoucherId = voucherId, updatedAt = System.currentTimeMillis())
                )
                AccountingResult.Success(postResult.data)
            }
        }
    }

    /**
     * Rule 12: VOUCHER CANCELLATION (Deletion Policy)
     * Posted vouchers are never physically deleted. Cancellation is a single atomic compensating
     * reversal via [DatabaseTransaction.cancelVoucherAtomic] - the sole authoritative path:
     * - Inserts opposite-sign journal lines reversing every original line (originals untouched)
     * - Reverses affected ledger running balances
     * - Marks the voucher isCancelled = true
     * - Appends an immutable CANCEL_VOUCHER audit record
     * - Enqueues a cancellation outbox item with idempotencyKey
     *
     * Pre-validates the period lock the same way [postVoucher] does (Project Principle 4:
     * locked/audit-locked periods reject postings, edits, AND cancellations) before entering
     * the atomic transaction.
     */
    suspend fun deleteVoucherSafely(
        companyId: String,
        financialYearId: String,
        voucherId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
        userId: String = "SENIOR_ACCOUNTANT"
    ): AccountingResult<Unit> {
        val voucher = dao.getVoucherById(companyId, voucherId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Voucher not found"))

        val period = findMatchingPeriod(financialYearId, safeParseDate(voucher.date))
        if (period != null && !period.isOpen) {
            return AccountingResult.Failure(
                AppError.PeriodLocked(periodName = period.name, date = voucher.date)
            )
        }

        if (dbTransaction == null) {
            return AccountingResult.Failure(AppError.SystemError("Database transaction unavailable: cannot cancel voucher atomically."))
        }
        val result = dbTransaction.cancelVoucherAtomic(companyId, financialYearId, voucherId, idempotencyKey, userId)
        return if (result.isSuccess) {
            AccountingResult.Success(Unit)
        } else {
            AccountingResult.Failure(mapTransactionFailure(result.exceptionOrNull()))
        }
    }

    private suspend fun VoucherEntity.toDomainVoucher(allLedgers: Map<String, LedgerEntity>): Voucher {
        val items = dao.getJournalItemsForVoucherSync(voucherId).map { item ->
            JournalItem(
                itemId = item.itemId,
                voucherId = item.voucherId,
                companyId = item.companyId,
                financialYearId = item.financialYearId,
                ledgerId = item.ledgerId,
                ledgerName = allLedgers[item.ledgerId]?.name ?: "Account",
                type = item.type,
                amount = Money.fromPaise(item.amountPaise),
                narration = item.narration,
                lineOrder = item.lineOrder
            )
        }
        return Voucher(
            voucherId = voucherId,
            companyId = companyId,
            financialYearId = financialYearId,
            voucherNumber = voucherNumber,
            voucherType = voucherType,
            date = safeParseDate(date),
            referenceNumber = referenceNumber,
            narration = narration,
            totalAmount = Money.fromPaise(totalAmountPaise),
            items = items,
            isPosted = isPosted,
            isCancelled = isCancelled,
            syncState = syncState,
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdBy = createdBy,
            partyGstin = partyGstin,
            isGstApplicable = isGstApplicable,
            referenceVoucherId = referenceVoucherId,
            paymentMode = paymentMode
        )
    }

    fun getVouchers(companyId: String, fyId: String): Flow<List<Voucher>> = dao.getVouchersByFinancialYear(companyId, fyId).map { list ->
        val allLedgers = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        list.map { it.toDomainVoucher(allLedgers) }
    }

    /** Single-voucher fetch with items resolved (Phase 5) - used by Credit/Debit Note (to read the
     * original Sale/Purchase's journal items back so [TradingWorkflowEngine.buildNote] can reverse
     * them) and by settlement allocation (to read an outstanding invoice's total). */
    suspend fun getVoucherById(companyId: String, voucherId: String): Voucher? {
        val entity = dao.getVoucherById(companyId, voucherId) ?: return null
        val allLedgers = dao.getLedgersByCompany(companyId).first().associateBy { it.ledgerId }
        return entity.toDomainVoucher(allLedgers)
    }

    // ==================== FINANCIAL REPORTS CALCULATION ====================
    /**
     * Generates the Trial Balance - the read-model source of truth every other financial
     * statement is derived from. Never mutates data (Section 24: read-only guarantee).
     *
     * @param dateRange optional custom period within [fyId]; must satisfy start <= end and lie
     *   entirely inside the financial year, else [AccountingTransactionException] wrapping
     *   [AppError.InvalidDateRange] is thrown. Null (default) means the full financial year.
     * @param includeZeroBalance when false, zero-closing-balance ledger rows are omitted from
     *   [TrialBalanceReport.rows] (totals are unaffected either way, since a zero-balance ledger
     *   contributes zero regardless). Default true, matching prior behavior.
     * @throws AccountingTransactionException wrapping [AppError.InvalidDateRange],
     *   [AppError.GroupHierarchyInvalid] (cyclic group relationship), or
     *   [AppError.TrialBalanceNotBalanced] (Dr != Cr - a data-integrity condition, never silently
     *   reconciled).
     */
    suspend fun generateTrialBalance(
        companyId: String,
        fyId: String,
        dateRange: ClosedRange<LocalDate>? = null,
        includeZeroBalance: Boolean = true
    ): TrialBalanceReport {
        val company = dao.getCompanyById(companyId)
        val fyEntity = dao.getFinancialYearById(fyId)
        val fyStart = safeParseDate(fyEntity?.startDate)
        val fyEnd = safeParseDate(fyEntity?.endDate)

        if (dateRange != null) {
            if (dateRange.start.isAfter(dateRange.endInclusive)) {
                throw AccountingTransactionException(
                    AppError.InvalidDateRange("Start date ${dateRange.start} is after end date ${dateRange.endInclusive}.")
                )
            }
            if (fyEntity != null && (dateRange.start.isBefore(fyStart) || dateRange.endInclusive.isAfter(fyEnd))) {
                throw AccountingTransactionException(
                    AppError.InvalidDateRange("Date range ${dateRange.start} to ${dateRange.endInclusive} is outside financial year ${fyEntity.fyCode} ($fyStart to $fyEnd).")
                )
            }
        }

        val groupEntities = dao.getGroupsByCompany(companyId).first()
        val groupsById = groupEntities.associateBy { it.groupId }
        val ledgers = dao.getLedgersByCompany(companyId).first()
        val vouchersById = dao.getAllVouchersByCompany(companyId).first().associateBy { it.voucherId }
        val allJournalItems = dao.getAllJournalItems(companyId, fyId).first().filter { item ->
            if (dateRange == null) return@filter true
            val voucherDate = vouchersById[item.voucherId]?.date?.let { safeParseDate(it) } ?: return@filter true
            !voucherDate.isBefore(dateRange.start) && !voucherDate.isAfter(dateRange.endInclusive)
        }

        val itemsByLedger = allJournalItems.groupBy { it.ledgerId }

        var totalOpDr = 0L
        var totalOpCr = 0L
        var totalTxDr = 0L
        var totalTxCr = 0L
        var totalClDr = 0L
        var totalClCr = 0L

        val allRows = ledgers.map { led ->
            val group = groupsById[led.groupId]
            val primaryGroup = group?.primaryGroup ?: PrimaryGroup.ASSETS

            val opDr = if (led.openingBalanceType == DrCr.DEBIT) led.openingBalancePaise else 0L
            val opCr = if (led.openingBalanceType == DrCr.CREDIT) led.openingBalancePaise else 0L

            val ledgerItems = itemsByLedger[led.ledgerId] ?: emptyList()
            val txDr = ledgerItems.filter { it.type == DrCr.DEBIT }.sumOf { it.amountPaise }
            val txCr = ledgerItems.filter { it.type == DrCr.CREDIT }.sumOf { it.amountPaise }

            val netBalanceSigned = (opDr - opCr) + (txDr - txCr)
            val clDr = if (netBalanceSigned >= 0) netBalanceSigned else 0L
            val clCr = if (netBalanceSigned < 0) -netBalanceSigned else 0L

            totalOpDr += opDr
            totalOpCr += opCr
            totalTxDr += txDr
            totalTxCr += txCr
            totalClDr += clDr
            totalClCr += clCr

            TrialBalanceRow(
                ledgerId = led.ledgerId,
                ledgerName = led.name,
                groupId = led.groupId,
                groupName = group?.name ?: "General",
                primaryGroup = primaryGroup,
                openingDebit = Money.fromPaise(opDr),
                openingCredit = Money.fromPaise(opCr),
                transactionDebit = Money.fromPaise(txDr),
                transactionCredit = Money.fromPaise(txCr),
                closingDebit = Money.fromPaise(clDr),
                closingCredit = Money.fromPaise(clCr)
            )
        }

        val rows = if (includeZeroBalance) allRows else allRows.filter { it.closingDebit.isPositive || it.closingCredit.isPositive }

        val domainGroups = groupEntities.map {
            AccountGroup(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        }
        val contributions = allRows.map { GroupAggregationEngine.LedgerContribution(it.groupId, it.closingDebit.paise, it.closingCredit.paise) }
        val hierarchy = when (val result = GroupAggregationEngine.aggregate(domainGroups, contributions)) {
            is AccountingResult.Success -> result.data
            is AccountingResult.Failure -> throw AccountingTransactionException(result.error)
        }

        val report = TrialBalanceReport(
            companyName = company?.name ?: "Company",
            financialYearCode = fyEntity?.fyCode ?: "FY 2026-27",
            asOfDate = dateRange?.endInclusive ?: LocalDate.now(),
            rows = rows,
            totalOpeningDebit = Money.fromPaise(totalOpDr),
            totalOpeningCredit = Money.fromPaise(totalOpCr),
            totalTransactionDebit = Money.fromPaise(totalTxDr),
            totalTransactionCredit = Money.fromPaise(totalTxCr),
            totalClosingDebit = Money.fromPaise(totalClDr),
            totalClosingCredit = Money.fromPaise(totalClCr),
            groupHierarchy = hierarchy
        )

        if (!report.isBalanced) {
            throw AccountingTransactionException(
                AppError.TrialBalanceNotBalanced(report.totalClosingDebit, report.totalClosingCredit, report.difference)
            )
        }

        return report
    }

    /**
     * Generates the Profit & Loss statement. Classification walks the real group hierarchy
     * (groupId -> parentGroupId -> ... -> standard root ID) instead of matching on group name
     * text. Uses PERIOD MOVEMENT ONLY (transactionDebit/transactionCredit) for every Income/
     * Expense figure, never closing balance - opening balance is a Balance-Sheet-only concept
     * that must not leak into P&L even if a ledger was incorrectly seeded with one (a genuine
     * bug in the pre-Phase-3 implementation, which summed closingDebit/closingCredit here).
     * Suspense (PrimaryGroup.SPECIAL_CONTROL) is excluded by construction - it simply never
     * matches the INCOME/EXPENSES filters below.
     */
    suspend fun generateProfitAndLoss(
        companyId: String,
        fyId: String,
        dateRange: ClosedRange<LocalDate>? = null
    ): ProfitAndLossReport {
        val company = dao.getCompanyById(companyId)
        val fy = dao.getFinancialYearById(fyId)
        val trialBalance = generateTrialBalance(companyId, fyId, dateRange, includeZeroBalance = true)

        val groupEntities = dao.getGroupsByCompany(companyId).first()
        val domainGroups = groupEntities.map {
            AccountGroup(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        }
        val periodContributions = trialBalance.rows.map {
            GroupAggregationEngine.LedgerContribution(it.groupId, it.transactionDebit.paise, it.transactionCredit.paise)
        }
        val periodHierarchy = when (val result = GroupAggregationEngine.aggregate(domainGroups, periodContributions)) {
            is AccountingResult.Success -> result.data
            is AccountingResult.Failure -> throw AccountingTransactionException(result.error)
        }

        fun namedNodeNet(bareGroupId: String): Long {
            val node = GroupAggregationEngine.findNode(periodHierarchy, "${bareGroupId}_$companyId") ?: return 0L
            return if (node.primaryGroup == PrimaryGroup.INCOME) node.totalCreditPaise - node.totalDebitPaise
            else node.totalDebitPaise - node.totalCreditPaise
        }

        val totalIncomePaise = periodHierarchy.filter { it.primaryGroup == PrimaryGroup.INCOME }.sumOf { it.totalCreditPaise - it.totalDebitPaise }
        val totalExpensePaise = periodHierarchy.filter { it.primaryGroup == PrimaryGroup.EXPENSES }.sumOf { it.totalDebitPaise - it.totalCreditPaise }

        val salesPaise = namedNodeNet(StandardSystemGroups.SALES_GROUP_ID)
        val directIncomePaise = namedNodeNet(StandardSystemGroups.DIRECT_INCOME_GROUP_ID)
        val purchasePaise = namedNodeNet(StandardSystemGroups.PURCHASE_GROUP_ID)
        val directExpensePaise = namedNodeNet(StandardSystemGroups.DIRECT_EXPENSE_GROUP_ID)

        // "Indirect" is everything else under INCOME/EXPENSES not already named above - this
        // correctly rolls up any custom top-level group too, matching the prior else-branch intent.
        val indirectIncomePaise = totalIncomePaise - salesPaise - directIncomePaise
        val indirectExpensePaise = totalExpensePaise - purchasePaise - directExpensePaise

        // Phase 4: when this company tracks inventory, Gross Profit uses COGS (Opening Stock +
        // Purchases - Purchase Returns - Closing Stock) instead of raw Purchases. ACCOUNT_ONLY
        // companies are entirely unaffected - cogsResult is null and the formula is identical to
        // pre-Phase-4 behavior.
        val cogsResult = computeCogsIfInventoryAware(companyId, fyId, dateRange)
        val tradingExpensePaise = if (cogsResult != null) cogsResult.cogsPaise + directExpensePaise else purchasePaise + directExpensePaise

        val totalTradingIncome = salesPaise + directIncomePaise
        val grossProfitPaise = totalTradingIncome - tradingExpensePaise
        val netProfitPaise = grossProfitPaise + indirectIncomePaise - indirectExpensePaise

        return ProfitAndLossReport(
            companyName = company?.name ?: "Company",
            financialYearCode = fy?.fyCode ?: "FY 2026-27",
            dateRange = dateRange?.let { "${it.start} to ${it.endInclusive}" } ?: "${fy?.startDate} to ${fy?.endDate}",
            salesRevenue = Money.fromPaise(salesPaise),
            directIncomes = Money.fromPaise(directIncomePaise),
            purchases = Money.fromPaise(purchasePaise),
            directExpenses = Money.fromPaise(directExpensePaise),
            grossProfit = Money.fromPaise(grossProfitPaise),
            indirectIncomes = Money.fromPaise(indirectIncomePaise),
            indirectExpenses = Money.fromPaise(indirectExpensePaise),
            netProfit = Money.fromPaise(netProfitPaise),
            cogs = Money.fromPaise(cogsResult?.cogsPaise ?: 0L),
            openingStock = Money.fromPaise(cogsResult?.openingStockPaise ?: 0L),
            closingStock = Money.fromPaise(cogsResult?.closingStockPaise ?: 0L),
            isInventoryAware = cogsResult != null
        )
    }

    /**
     * Income & Expenditure statement for SERVICE-type companies (Phase 4). Same underlying
     * INCOME/EXPENSES group data as [generateProfitAndLoss] via [GroupAggregationEngine], with no
     * Trading/COGS section - read-only, never mutates data.
     */
    suspend fun generateIncomeAndExpenditure(
        companyId: String,
        fyId: String,
        dateRange: ClosedRange<LocalDate>? = null
    ): IncomeExpenditureReport {
        val company = dao.getCompanyById(companyId)
        val fy = dao.getFinancialYearById(fyId)
        val trialBalance = generateTrialBalance(companyId, fyId, dateRange, includeZeroBalance = true)

        val groupEntities = dao.getGroupsByCompany(companyId).first()
        val domainGroups = groupEntities.map {
            AccountGroup(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
        }
        val periodContributions = trialBalance.rows.map {
            GroupAggregationEngine.LedgerContribution(it.groupId, it.transactionDebit.paise, it.transactionCredit.paise)
        }
        val periodHierarchy = when (val result = GroupAggregationEngine.aggregate(domainGroups, periodContributions)) {
            is AccountingResult.Success -> result.data
            is AccountingResult.Failure -> throw AccountingTransactionException(result.error)
        }

        val incomePaise = periodHierarchy.filter { it.primaryGroup == PrimaryGroup.INCOME }.sumOf { it.totalCreditPaise - it.totalDebitPaise }
        val expenditurePaise = periodHierarchy.filter { it.primaryGroup == PrimaryGroup.EXPENSES }.sumOf { it.totalDebitPaise - it.totalCreditPaise }

        return IncomeExpenditureReport(
            companyName = company?.name ?: "Company",
            financialYearCode = fy?.fyCode ?: "FY 2026-27",
            dateRange = dateRange?.let { "${it.start} to ${it.endInclusive}" } ?: "${fy?.startDate} to ${fy?.endDate}",
            income = Money.fromPaise(incomePaise),
            expenditure = Money.fromPaise(expenditurePaise),
            surplusOrDeficit = Money.fromPaise(incomePaise - expenditurePaise)
        )
    }

    /**
     * Aggregates COGS across every stock item for [companyId]/[fyId] (optionally restricted to
     * [dateRange]), or returns null if the company is not in ACCOUNT_WITH_INVENTORY mode - the
     * single gate that keeps ACCOUNT_ONLY companies byte-for-byte unaffected by Phase 4.
     */
    private suspend fun computeCogsIfInventoryAware(
        companyId: String,
        fyId: String,
        dateRange: ClosedRange<LocalDate>?
    ): CogsEngine.CogsResult? {
        val company = dao.getCompanyById(companyId) ?: return null
        if (company.accountingMode != AccountingMode.ACCOUNT_WITH_INVENTORY) return null

        val items = dao.getStockItemsByCompany(companyId).first()
        if (items.isEmpty()) return CogsEngine.CogsResult(0L, 0L, 0L, 0L, 0L)

        val movementsByItem = dao.getStockMovementsForCompanyFY(companyId, fyId).groupBy { it.itemId }

        val results = items.map { item ->
            val itemMovements = movementsByItem[item.itemId] ?: emptyList()
            val before = if (dateRange == null) emptyList() else itemMovements.filter { safeParseDate(it.date).isBefore(dateRange.start) }
            val inPeriod = if (dateRange == null) itemMovements else itemMovements.filter {
                val d = safeParseDate(it.date)
                !d.isBefore(dateRange.start) && !d.isAfter(dateRange.endInclusive)
            }
            CogsEngine.computeForItem(item.openingQuantity, item.openingRatePaise, before, inPeriod)
        }
        return CogsEngine.aggregate(results)
    }

    /**
     * Generates the Balance Sheet. Reuses the exact same recursive group hierarchy the Trial
     * Balance already built (closing-balance contributions) - Assets/Liabilities/Equity legitimately
     * use closing balances (opening + all transactions ever), unlike P&L. Suspense uses its
     * dedicated system group/ledger identity (never name matching, never folded into ordinary
     * Current Liabilities/Assets) and is presented on whichever side its net balance falls on,
     * per Section 18/19 - a nonzero balance never blocks statement generation.
     *
     * @throws AccountingTransactionException wrapping [AppError.BalanceSheetNotBalanced] if
     *   Assets != Liabilities + Equity - a data-integrity condition, never silently reconciled.
     */
    suspend fun generateBalanceSheet(
        companyId: String,
        fyId: String,
        dateRange: ClosedRange<LocalDate>? = null
    ): BalanceSheetReport {
        val company = dao.getCompanyById(companyId)
        val fy = dao.getFinancialYearById(fyId)
        val trialBalance = generateTrialBalance(companyId, fyId, dateRange, includeZeroBalance = true)
        val pnl = generateProfitAndLoss(companyId, fyId, dateRange)
        val hierarchy = trialBalance.groupHierarchy

        fun netDebit(bareId: String): Long {
            val node = GroupAggregationEngine.findNode(hierarchy, "${bareId}_$companyId") ?: return 0L
            return node.totalDebitPaise - node.totalCreditPaise
        }
        fun netCredit(bareId: String): Long {
            val node = GroupAggregationEngine.findNode(hierarchy, "${bareId}_$companyId") ?: return 0L
            return node.totalCreditPaise - node.totalDebitPaise
        }

        val suspenseNode = GroupAggregationEngine.findNode(hierarchy, "${StandardSystemGroups.SUSPENSE_GROUP_ID}_$companyId")
        val suspenseNetSigned = (suspenseNode?.totalDebitPaise ?: 0L) - (suspenseNode?.totalCreditPaise ?: 0L)
        val suspenseDebitPaise = if (suspenseNetSigned > 0) suspenseNetSigned else 0L
        val suspenseCreditPaise = if (suspenseNetSigned < 0) -suspenseNetSigned else 0L

        // EQUITY
        val capitalPaise = netCredit(StandardSystemGroups.CAPITAL_GROUP_ID)
        val reservesPaise = netCredit(StandardSystemGroups.RESERVES_GROUP_ID)

        // LIABILITIES - named buckets subtracted from the primary-group-wide total, so nested
        // subgroups (e.g. GRP_DUTIES lives under GRP_CURRENT_LIAB) are attributed once, correctly.
        val loansPaise = netCredit(StandardSystemGroups.LOANS_GROUP_ID)
        val dutiesTaxesPaise = netCredit(StandardSystemGroups.DUTIES_GROUP_ID)
        val branchDivPaise = netCredit(StandardSystemGroups.BRANCH_DIVISIONS_GROUP_ID)
        val totalLiabilitiesPrimaryPaise = hierarchy.filter { it.primaryGroup == PrimaryGroup.LIABILITIES }.sumOf { it.totalCreditPaise - it.totalDebitPaise }
        val currentLiabPaise = totalLiabilitiesPrimaryPaise - loansPaise - dutiesTaxesPaise - branchDivPaise

        // ASSETS - same "named bucket subtracted from primary-group total" pattern.
        val fixedAssetsPaise = netDebit(StandardSystemGroups.FIXED_ASSETS_GROUP_ID)
        val investmentsPaise = netDebit(StandardSystemGroups.INVESTMENTS_GROUP_ID)
        val miscExpPaise = netDebit(StandardSystemGroups.MISC_EXPENSES_GROUP_ID)
        val debtorsPaise = netDebit(StandardSystemGroups.DEBTORS_GROUP_ID)
        val bankPaise = netDebit(StandardSystemGroups.BANK_GROUP_ID)
        val cashPaise = netDebit(StandardSystemGroups.CASH_GROUP_ID)
        val totalAssetsPrimaryPaise = hierarchy.filter { it.primaryGroup == PrimaryGroup.ASSETS }.sumOf { it.totalDebitPaise - it.totalCreditPaise }
        val currentAssetsPaise = totalAssetsPrimaryPaise - fixedAssetsPaise - investmentsPaise - miscExpPaise - debtorsPaise - bankPaise - cashPaise

        // Stock-in-Hand (Phase 4, Account + Inventory only) - a COMPUTED figure like netProfitForYear,
        // never backed by a real ledger (periodic inventory: Purchase/Sales journal postings are
        // unchanged from pre-Phase-4). Reuses the closing stock valuation P&L already computed
        // above, so COGS is derived exactly once per report.
        val stockInHandPaise = pnl.closingStock.paise

        val totalLiabilitiesPaise = capitalPaise + reservesPaise + pnl.netProfit.paise + loansPaise + currentLiabPaise + dutiesTaxesPaise + branchDivPaise + suspenseCreditPaise
        val totalAssetsPaise = fixedAssetsPaise + investmentsPaise + currentAssetsPaise + debtorsPaise + bankPaise + cashPaise + miscExpPaise + suspenseDebitPaise + stockInHandPaise

        val report = BalanceSheetReport(
            companyName = company?.name ?: "Company",
            financialYearCode = fy?.fyCode ?: "FY 2026-27",
            asOfDate = dateRange?.endInclusive ?: LocalDate.now(),
            capitalAccounts = Money.fromPaise(capitalPaise),
            reservesAndSurplus = Money.fromPaise(reservesPaise),
            netProfitForYear = pnl.netProfit,
            loansLiabilities = Money.fromPaise(loansPaise),
            currentLiabilities = Money.fromPaise(currentLiabPaise),
            dutiesAndTaxesLiability = Money.fromPaise(dutiesTaxesPaise),
            branchDivisions = Money.fromPaise(branchDivPaise),
            suspenseCredit = Money.fromPaise(suspenseCreditPaise),
            totalLiabilities = Money.fromPaise(totalLiabilitiesPaise),
            fixedAssets = Money.fromPaise(fixedAssetsPaise),
            investments = Money.fromPaise(investmentsPaise),
            currentAssets = Money.fromPaise(currentAssetsPaise),
            sundryDebtors = Money.fromPaise(debtorsPaise),
            bankAccounts = Money.fromPaise(bankPaise),
            cashInHand = Money.fromPaise(cashPaise),
            miscExpensesAsset = Money.fromPaise(miscExpPaise),
            stockInHand = Money.fromPaise(stockInHandPaise),
            suspenseDebit = Money.fromPaise(suspenseDebitPaise),
            totalAssets = Money.fromPaise(totalAssetsPaise)
        )

        if (!report.isBalanced) {
            throw AccountingTransactionException(
                AppError.BalanceSheetNotBalanced(report.totalAssets, report.totalLiabilities, report.difference)
            )
        }

        return report
    }

    suspend fun generateLedgerStatement(companyId: String, ledgerId: String): LedgerStatementReport {
        val ledger = dao.getLedgerById(companyId, ledgerId)
        val journalItems = dao.getJournalItemsByLedger(companyId, ledgerId)
        val vouchers = dao.getAllVouchersByCompany(companyId).first().associateBy { it.voucherId }

        val opPaise = ledger?.openingBalancePaise ?: 0L
        val opType = ledger?.openingBalanceType ?: DrCr.DEBIT

        var runningSigned = if (opType == DrCr.DEBIT) opPaise else -opPaise
        var totalDr = 0L
        var totalCr = 0L

        val rows = journalItems.map { item ->
            val v = vouchers[item.voucherId]
            val dr = if (item.type == DrCr.DEBIT) item.amountPaise else 0L
            val cr = if (item.type == DrCr.CREDIT) item.amountPaise else 0L
            totalDr += dr
            totalCr += cr

            val delta = if (item.type == DrCr.DEBIT) item.amountPaise else -item.amountPaise
            runningSigned += delta

            val currentType = if (runningSigned >= 0) DrCr.DEBIT else DrCr.CREDIT

            LedgerStatementRow(
                voucherId = item.voucherId,
                voucherNumber = v?.voucherNumber ?: "VCH",
                voucherType = v?.voucherType?.displayName ?: "Journal",
                date = safeParseDate(v?.date),
                particulars = item.narration.ifBlank { v?.narration ?: "Transaction Entry" },
                debitAmount = Money.fromPaise(dr),
                creditAmount = Money.fromPaise(cr),
                runningBalance = Money.fromPaise(kotlin.math.abs(runningSigned)),
                balanceType = currentType
            )
        }

        val closingType = if (runningSigned >= 0) DrCr.DEBIT else DrCr.CREDIT

        return LedgerStatementReport(
            ledgerId = ledgerId,
            ledgerName = ledger?.name ?: "Ledger Account",
            openingBalance = Money.fromPaise(opPaise),
            openingType = opType,
            rows = rows,
            totalDebit = Money.fromPaise(totalDr),
            totalCredit = Money.fromPaise(totalCr),
            closingBalance = Money.fromPaise(kotlin.math.abs(runningSigned)),
            closingType = closingType
        )
    }

    /**
     * GST Summary, rebuilt entirely from [GstTransactionEntity] rows (Phase 5, Priority 4) - the
     * old implementation matched on `ledgerName.contains("Output CGST", ...)`, the exact
     * name-based classification anti-pattern Phase 3 eliminated everywhere else. Every figure here
     * is grouped by [GstDirection]/[com.example.accounting.domain.taxation.gst.SupplyType], never
     * by string content, and (unlike the trial-balance-derived version) reflects Credit/Debit Note
     * adjustments correctly since those post negated rows at the SAME direction as the original
     * supply rather than an opposite one.
     */
    suspend fun generateGSTSummary(companyId: String, fyId: String): GSTSummaryReport {
        val company = dao.getCompanyById(companyId)
        val fy = dao.getFinancialYearById(fyId)
        val transactions = dao.getGstTransactionsForCompanyFY(companyId, fyId)

        val outward = transactions.filter { it.direction == GstDirection.OUTPUT }
        val inward = transactions.filter { it.direction == GstDirection.INPUT }

        fun sum(rows: List<GstTransactionEntity>, selector: (GstTransactionEntity) -> Long) = rows.fold(0L) { acc, r -> acc + selector(r) }

        val taxableOutward = sum(outward) { it.taxableAmountPaise }
        val cgstOutward = sum(outward) { it.cgstPaise }
        val sgstOutward = sum(outward) { it.sgstPaise }
        val igstOutward = sum(outward) { it.igstPaise }
        val cessOutward = sum(outward) { it.cessPaise }

        val taxableInward = sum(inward) { it.taxableAmountPaise }
        val cgstInward = sum(inward) { it.cgstPaise }
        val sgstInward = sum(inward) { it.sgstPaise }
        val igstInward = sum(inward) { it.igstPaise }
        val cessInward = sum(inward) { it.cessPaise }

        val totalTaxOutward = cgstOutward + sgstOutward + igstOutward
        val totalTaxInward = cgstInward + sgstInward + igstInward
        val netPayable = (totalTaxOutward - totalTaxInward).coerceAtLeast(0L)
        val totalCess = cessOutward + cessInward
        val netCessPayable = (cessOutward - cessInward).coerceAtLeast(0L)

        return GSTSummaryReport(
            companyName = company?.name ?: "",
            gstin = company?.gstin ?: "",
            period = fy?.fyCode ?: fyId,
            totalTaxableOutward = Money.fromPaise(taxableOutward),
            totalCGSTOutward = Money.fromPaise(cgstOutward),
            totalSGSTOutward = Money.fromPaise(sgstOutward),
            totalIGSTOutward = Money.fromPaise(igstOutward),
            totalTaxOutward = Money.fromPaise(totalTaxOutward),
            totalTaxableInward = Money.fromPaise(taxableInward),
            totalCGSTInwardITC = Money.fromPaise(cgstInward),
            totalSGSTInwardITC = Money.fromPaise(sgstInward),
            totalIGSTInwardITC = Money.fromPaise(igstInward),
            totalTaxInwardITC = Money.fromPaise(totalTaxInward),
            netTaxPayable = Money.fromPaise(netPayable),
            totalCess = Money.fromPaise(totalCess),
            netCessPayable = Money.fromPaise(netCessPayable)
        )
    }

    // ==================== RULE 33: GST RETURN DASHBOARD & FILING FOUNDATION ====================

    private fun GstTransactionEntity.toDomainGstTransaction(): GstTransaction = GstTransaction(
        gstTransactionId = gstTransactionId, companyId = companyId, financialYearId = financialYearId,
        voucherId = voucherId, voucherType = voucherType, partyLedgerId = partyLedgerId,
        partyGstin = partyGstin, placeOfSupply = placeOfSupply, supplyType = supplyType,
        itemId = itemId, hsnSacCode = hsnSacCode,
        quantity = quantityRaw?.let { q -> Quantity(q) },
        taxableAmount = Money.fromPaise(taxableAmountPaise), gstRatePercent = gstRatePercent,
        cgst = Money.fromPaise(cgstPaise), sgst = Money.fromPaise(sgstPaise),
        igst = Money.fromPaise(igstPaise), cess = Money.fromPaise(cessPaise),
        direction = direction, lineOrder = lineOrder, chargeType = chargeType,
        supplyNature = supplyNature, transactionGroupId = transactionGroupId,
        transactionDate = transactionDate?.let { safeParseDate(it) },
        partyGstRegistrationStatus = partyGstRegistrationStatus?.let { raw ->
            runCatching { GstRegistrationStatus.valueOf(raw) }.getOrNull()
        }
    )

    private fun GstReturnEntity.toDomain(): GstReturn = GstReturn(
        gstReturnId = gstReturnId, companyId = companyId, financialYearId = financialYearId, fyCode = fyCode,
        quarter = GstQuarter.valueOf(quarter), month = month, periodKey = periodKey, scheme = scheme,
        returnType = returnType, periodicity = periodicity, filingMode = filingMode, status = status,
        createdAt = createdAt, updatedAt = updatedAt, submittedAt = submittedAt,
        acknowledgementNumber = acknowledgementNumber, errorCode = errorCode, errorMessage = errorMessage,
        latestRequestArtifactId = latestRequestArtifactId, latestResponseArtifactId = latestResponseArtifactId,
        schemaVersion = schemaVersion
    )

    private fun GstReturnArtifactEntity.toDomain(): GstReturnArtifact = GstReturnArtifact(
        artifactId, gstReturnId, artifactType, schemaVersion, jsonContent, createdAt
    )

    private fun GstReturnSectionEntity.toDomain(): GstReturnSection = GstReturnSection(
        sectionId, gstReturnId, sectionKey, status, resultDataJson, errorsJson, updatedAt
    )

    private fun GstReturnSubmissionEntity.toDomain(): GstReturnSubmission = GstReturnSubmission(
        submissionId, gstReturnId, attemptNumber, requestArtifactId, responseArtifactId, status,
        acknowledgementNumber, errorCode, errorMessage, submittedAt, respondedAt
    )

    fun getGstReturns(companyId: String): Flow<List<GstReturn>> =
        dao.getGstReturnsForCompany(companyId).map { list -> list.map { it.toDomain() } }

    suspend fun getGstReturn(companyId: String, gstReturnId: String): GstReturn? =
        dao.getGstReturnById(companyId, gstReturnId)?.toDomain()

    suspend fun getGstReturnArtifacts(gstReturnId: String): List<GstReturnArtifact> =
        dao.getArtifactsForGstReturn(gstReturnId).map { it.toDomain() }

    suspend fun getGstReturnSections(gstReturnId: String): List<GstReturnSection> =
        dao.getSectionsForGstReturn(gstReturnId).map { it.toDomain() }

    suspend fun getGstReturnSubmissions(gstReturnId: String): List<GstReturnSubmission> =
        dao.getSubmissionsForGstReturn(gstReturnId).map { it.toDomain() }

    /**
     * Finds the existing return for this exact (period, return type, scheme), or creates a fresh
     * DRAFT one - idempotent, so re-opening the same Dashboard selection never creates duplicate
     * rows (Rule 33, Section 15 - "a prepared return must be reopenable").
     */
    suspend fun getOrCreateGstReturn(
        companyId: String,
        fy: FinancialYear,
        quarter: GstQuarter,
        month: Int?,
        scheme: GstScheme,
        returnType: GstReturnType,
        periodicity: GstReturnPeriodicity,
        filingMode: GstFilingMode
    ): GstReturn {
        val period = GstPeriod.of(fy, quarter, month)
        dao.findGstReturn(companyId, period.periodKey, returnType.name, scheme.name)?.let { return it.toDomain() }
        val now = System.currentTimeMillis()
        val entity = GstReturnEntity(
            gstReturnId = UUID.randomUUID().toString(), companyId = companyId, financialYearId = fy.financialYearId,
            fyCode = fy.fyCode, quarter = quarter.name, month = month, periodKey = period.periodKey,
            scheme = scheme, returnType = returnType, periodicity = periodicity, filingMode = filingMode,
            status = GstReturnStatus.DRAFT, createdAt = now, updatedAt = now, submittedAt = null,
            acknowledgementNumber = null, errorCode = null, errorMessage = null,
            latestRequestArtifactId = null, latestResponseArtifactId = null, schemaVersion = "1.0"
        )
        dao.insertGstReturn(entity)
        return entity.toDomain()
    }

    /**
     * Rule 33, Section 11/14 - the ACTIVE (non-cancelled) posted GST transactions falling inside
     * [dateRange], the sole data source [prepareGstReturn] consumes. Reuses
     * [getGstTransactionsForCompanyFY]'s already-persisted rows (never a second GST calculation),
     * excludes any row whose voucher is cancelled, and filters by each row's REAL voucher date
     * (never `createdAt`/a device display date) - a voucher-less GST-only row has no cancellation
     * state to check and is dated by its own `createdAt` as the only fact available for it (that
     * capability has no UI entry point yet; see [postGstOnlySale]'s own doc comment).
     */
    suspend fun getActiveGstTransactionsForPeriod(
        companyId: String,
        financialYearId: String,
        dateRange: ClosedRange<LocalDate>
    ): List<GstTransaction> {
        val rows = dao.getGstTransactionsForCompanyFY(companyId, financialYearId)
        val vouchersById = dao.getAllVouchersByCompany(companyId).first().associateBy { it.voucherId }
        return rows.filter { row ->
            val voucher = row.voucherId?.let { vouchersById[it] }
            val active = voucher == null || !voucher.isCancelled
            val effectiveDate = voucher?.date?.let { safeParseDate(it) }
                ?: java.time.Instant.ofEpochMilli(row.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            active && !effectiveDate.isBefore(dateRange.start) && !effectiveDate.isAfter(dateRange.endInclusive)
        }.map { it.toDomainGstTransaction() }
    }

    /**
     * Rule 33, Section 5/10/11 - PREPARE: pulls this return's period worth of active GST
     * transactions and stores one generic "SUMMARY" section (never a statutory GSTR table name this
     * foundation doesn't own). Forward-charge and RCM inward tax are kept in separate figures here -
     * RCM Input/Liability are accounting postings, never folded into ordinary inward tax (Section
     * 12). Never transitions [GstReturn.status] itself - VALIDATE decides READY/VALIDATION_FAILED.
     */
    /** A generic per-bucket total this repository can honestly compute from already-persisted
     * [GstTransaction] rows - never a statutory field this domain doesn't actually store (no B2CL
     * ₹2.5L threshold split, no invoice-level detail). */
    private fun bucketTotals(rows: List<GstTransaction>): Map<String, Any?> = linkedMapOf(
        "count" to rows.size,
        "taxableValuePaise" to rows.sumOf { it.taxableAmount.paise },
        "cgstPaise" to rows.sumOf { it.cgst.paise },
        "sgstPaise" to rows.sumOf { it.sgst.paise },
        "igstPaise" to rows.sumOf { it.igst.paise },
        "cessPaise" to rows.sumOf { it.cess.paise }
    )

    /**
     * Rule 33 follow-up - real, statutorily-named GSTR-1/GSTR-3B section buckets (per the public
     * GST Network return schema: GSTR-1's B2B/B2C/EXP/NIL/HSN tables; GSTR-3B's 3.1 outward and
     * Section 4 ITC tables), computed ONLY from facts this domain already has per
     * [GstTransaction] (direction, chargeType, supplyType, partyGstin, hsnSacCode) - never a second
     * GST calculation, and never a fabricated field (invoice-level B2CL/B2CS ₹2.5L threshold
     * splitting needs invoice-level detail this domain does not store, so B2C is reported as one
     * combined bucket, not the GSTN portal's exact two). GSTR-1 covers OUTWARD supplies only, never
     * inward/purchase data - GSTR-3B covers both sides. GSTR-4 (Composition) has no section
     * breakdown defined yet (out of this pass's scope) and gets a single generic bucket.
     */
    private fun buildGstReturnSections(returnType: GstReturnType, transactions: List<GstTransaction>): Map<String, Map<String, Any?>> {
        val outward = transactions.filter { it.direction == GstDirection.OUTPUT }
        val inward = transactions.filter { it.direction == GstDirection.INPUT }
        return when (returnType) {
            GstReturnType.GSTR1 -> linkedMapOf(
                "B2B" to bucketTotals(outward.filter { it.partyGstin.isNotBlank() }),
                "B2C" to bucketTotals(outward.filter { it.partyGstin.isBlank() && it.supplyType != SupplyType.EXPORT && it.supplyType != SupplyType.EXEMPT }),
                "EXP" to bucketTotals(outward.filter { it.supplyType == SupplyType.EXPORT }),
                "NIL_EXEMPT" to bucketTotals(outward.filter { it.supplyType == SupplyType.EXEMPT }),
                "HSN" to bucketTotals(outward)
            )
            GstReturnType.GSTR3B -> linkedMapOf(
                "OUTWARD_TAXABLE" to bucketTotals(outward.filter { it.supplyType == SupplyType.INTRA_STATE || it.supplyType == SupplyType.INTER_STATE }),
                "OUTWARD_ZERO_RATED" to bucketTotals(outward.filter { it.supplyType == SupplyType.EXPORT }),
                "OUTWARD_NIL_EXEMPT" to bucketTotals(outward.filter { it.supplyType == SupplyType.EXEMPT }),
                "RCM_LIABILITY" to bucketTotals(inward.filter { it.chargeType == GstChargeType.REVERSE_CHARGE }),
                "ITC_FORWARD" to bucketTotals(inward.filter { it.chargeType == GstChargeType.FORWARD_CHARGE }),
                "ITC_RCM" to bucketTotals(inward.filter { it.chargeType == GstChargeType.REVERSE_CHARGE })
            )
            GstReturnType.GSTR4 -> linkedMapOf("SUMMARY" to bucketTotals(transactions))
        }
    }

    suspend fun prepareGstReturn(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturn> {
        val entity = dao.getGstReturnById(companyId, gstReturnId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("GstReturn", gstReturnId))
        val period = GstPeriod.of(fy, GstQuarter.valueOf(entity.quarter), entity.month)
        val transactions = getActiveGstTransactionsForPeriod(companyId, entity.financialYearId, period.dateRange())

        val now = System.currentTimeMillis()
        val existingSections = dao.getSectionsForGstReturn(gstReturnId).associateBy { it.sectionKey }
        buildGstReturnSections(entity.returnType, transactions).forEach { (key, data) ->
            dao.upsertGstReturnSection(
                GstReturnSectionEntity(
                    sectionId = existingSections[key]?.sectionId ?: UUID.randomUUID().toString(),
                    gstReturnId = gstReturnId, sectionKey = key, status = GstReturnSectionStatus.PREPARED,
                    resultDataJson = gstReturnJsonAdapter.toJson(data), errorsJson = null, updatedAt = now
                )
            )
        }
        dao.updateGstReturn(entity.copy(updatedAt = now))
        return AccountingResult.Success(dao.getGstReturnById(companyId, gstReturnId)!!.toDomain())
    }

    /**
     * Rule 33, Section 5/7/13 - VALIDATE: requires a PREPARED section to exist, and (Rule 29)
     * defensively re-checks that every transaction this return covers has a resolved
     * [GstTransaction.placeOfSupply] - by construction this can only be blank if a row bypassed
     * normal posting-time validation, but this layer must surface that rather than silently accept
     * or fabricate a state. Transitions to READY or VALIDATION_FAILED via
     * [GstReturnStatusTransitions] - never a direct field write outside it.
     */
    suspend fun validateGstReturn(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturn> {
        val entity = dao.getGstReturnById(companyId, gstReturnId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("GstReturn", gstReturnId))
        val sections = dao.getSectionsForGstReturn(gstReturnId)
        val errors = mutableListOf<String>()
        // A section only ever starts PENDING - PREPARED/VALIDATION_PASSED/VALIDATION_FAILED all
        // prove Prepare has run at least once (re-validating an already-validated return, e.g.
        // after a failed submission attempt, must not be mistaken for "never prepared"). A return
        // now carries several real sections (Rule 33 follow-up) rather than one "SUMMARY" bucket -
        // this only requires at least one to exist and none still PENDING.
        if (sections.isEmpty() || sections.any { it.status == GstReturnSectionStatus.PENDING }) {
            errors += "Return has not been prepared - run Prepare before Validate."
        } else {
            val period = GstPeriod.of(fy, GstQuarter.valueOf(entity.quarter), entity.month)
            val transactions = getActiveGstTransactionsForPeriod(companyId, entity.financialYearId, period.dateRange())
            val unresolved = transactions.filter { it.placeOfSupply.isBlank() }
            if (unresolved.isNotEmpty()) {
                errors += "${unresolved.size} transaction(s) have an unresolved Place of Supply - this cannot be guessed from the company's own state."
            }
        }

        val newStatus = if (errors.isEmpty()) GstReturnStatus.READY else GstReturnStatus.VALIDATION_FAILED
        if (!GstReturnStatusTransitions.isAllowed(entity.status, newStatus)) {
            return AccountingResult.Failure(
                AppError.BusinessRuleViolation("Cannot move return from ${entity.status} to $newStatus.")
            )
        }
        val now = System.currentTimeMillis()
        dao.updateGstReturn(
            entity.copy(status = newStatus, errorMessage = errors.joinToString("; ").ifBlank { null }, updatedAt = now)
        )
        sections.forEach { section ->
            dao.upsertGstReturnSection(
                section.copy(
                    status = if (errors.isEmpty()) GstReturnSectionStatus.VALIDATION_PASSED else GstReturnSectionStatus.VALIDATION_FAILED,
                    errorsJson = if (errors.isEmpty()) null else gstReturnJsonAdapter.toJson(mapOf("errors" to errors)),
                    updatedAt = now
                )
            )
        }
        return AccountingResult.Success(dao.getGstReturnById(companyId, gstReturnId)!!.toDomain())
    }

    /**
     * Rule 33, Section 5/9 - GENERATE JSON (offline): requires READY, reuses the existing
     * [GstrJsonSerializer]/[ExportMetadata] (never a second JSON framework/serializer), and always
     * inserts a NEW artifact row rather than overwriting a previous one (Section 9/16).
     */
    suspend fun generateGstReturnOfflineJson(companyId: String, gstReturnId: String, fy: FinancialYear): AccountingResult<GstReturnArtifact> {
        val entity = dao.getGstReturnById(companyId, gstReturnId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("GstReturn", gstReturnId))
        if (entity.status != GstReturnStatus.READY) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Return must be READY before generating JSON - current status is ${entity.status}."))
        }
        val period = GstPeriod.of(fy, GstQuarter.valueOf(entity.quarter), entity.month)
        val transactions = getActiveGstTransactionsForPeriod(companyId, entity.financialYearId, period.dateRange())
        val dtos = transactions.map { gt ->
            GSTTransactionExportDto(
                gstTransactionId = gt.gstTransactionId, voucherId = gt.voucherId, voucherType = gt.voucherType,
                partyGstin = gt.partyGstin, placeOfSupply = gt.placeOfSupply, supplyType = gt.supplyType.name,
                hsnSacCode = gt.hsnSacCode, isService = null, taxableAmountPaise = gt.taxableAmount.paise,
                gstRatePercent = gt.gstRatePercent, cgstPaise = gt.cgst.paise, sgstPaise = gt.sgst.paise,
                igstPaise = gt.igst.paise, cessPaise = gt.cess.paise, direction = gt.direction.name, lineOrder = gt.lineOrder
            )
        }
        val json = GstrJsonSerializer.serialize(
            ExportMetadata(exportType = ExportType.GST_TRANSACTIONS, companyId = companyId, financialYearId = entity.financialYearId),
            dtos
        )
        val now = System.currentTimeMillis()
        val artifact = GstReturnArtifactEntity(
            artifactId = UUID.randomUUID().toString(), gstReturnId = gstReturnId,
            artifactType = GstReturnArtifactType.REQUEST, schemaVersion = "1.0", jsonContent = json, createdAt = now
        )
        dao.insertGstReturnArtifact(artifact)
        dao.updateGstReturn(entity.copy(latestRequestArtifactId = artifact.artifactId, updatedAt = now))
        return AccountingResult.Success(artifact.toDomain())
    }

    /**
     * Rule 33, Section 5/9 - IMPORT RESPONSE (offline): validates the given string is at least
     * well-formed JSON (reusing Moshi, the same library already used for every other export/import
     * in this codebase - never a new JSON dependency), stores it as a new RESPONSE artifact (never
     * overwriting a previous one), and moves the return to PROCESSING - a real GST response was
     * received and awaits the user's review, but this application never claims a return was FILED
     * merely because *a* response arrived (Section 5: "MUST NOT claim that an offline return was
     * filed/submitted"). [markGstReturnFiled] is the explicit, separate, user-driven step for that.
     */
    suspend fun importGstReturnOfflineResponse(companyId: String, gstReturnId: String, responseJson: String): AccountingResult<GstReturnArtifact> {
        val entity = dao.getGstReturnById(companyId, gstReturnId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("GstReturn", gstReturnId))
        val parsed = try {
            genericJsonAdapter.fromJson(responseJson)
        } catch (e: Exception) {
            null
        }
        if (parsed == null) {
            return AccountingResult.Failure(AppError.ValidationError("The imported file is not valid JSON."))
        }
        val newStatus = GstReturnStatus.PROCESSING
        if (!GstReturnStatusTransitions.isAllowed(entity.status, newStatus)) {
            return AccountingResult.Failure(
                AppError.BusinessRuleViolation("Cannot import a response while the return is ${entity.status}.")
            )
        }
        val now = System.currentTimeMillis()
        val artifact = GstReturnArtifactEntity(
            artifactId = UUID.randomUUID().toString(), gstReturnId = gstReturnId,
            artifactType = GstReturnArtifactType.RESPONSE, schemaVersion = "1.0", jsonContent = responseJson, createdAt = now
        )
        dao.insertGstReturnArtifact(artifact)
        dao.updateGstReturn(
            entity.copy(status = newStatus, latestResponseArtifactId = artifact.artifactId, updatedAt = now)
        )
        return AccountingResult.Success(artifact.toDomain())
    }

    /**
     * Rule 33, Section 5 - the explicit, user-driven confirmation that a PROCESSING return (an
     * external response has already been imported and reviewed) is actually filed, carrying the
     * real acknowledgement number the user read off that response. This application never infers
     * FILED automatically from parsing an unknown response schema (Section 5/6: "do not fabricate").
     */
    suspend fun markGstReturnFiled(companyId: String, gstReturnId: String, acknowledgementNumber: String): AccountingResult<GstReturn> {
        val entity = dao.getGstReturnById(companyId, gstReturnId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("GstReturn", gstReturnId))
        if (!GstReturnStatusTransitions.isAllowed(entity.status, GstReturnStatus.FILED)) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Cannot mark ${entity.status} as FILED."))
        }
        if (acknowledgementNumber.isBlank()) {
            return AccountingResult.Failure(AppError.ValidationError("An acknowledgement number is required to mark a return as Filed."))
        }
        val now = System.currentTimeMillis()
        dao.updateGstReturn(
            entity.copy(status = GstReturnStatus.FILED, acknowledgementNumber = acknowledgementNumber, updatedAt = now)
        )
        return AccountingResult.Success(dao.getGstReturnById(companyId, gstReturnId)!!.toDomain())
    }

    /**
     * Rule 33, Section 6/16 - ONLINE submission through [gateway] (defaults to
     * [UnconfiguredGstOnlineFilingGateway] - this repository has no real GST Network client to use
     * instead). Requires READY, transitions READY->SUBMITTING->(SUBMITTED|FAILED) strictly through
     * [GstReturnStatusTransitions], and always records the attempt as a new [GstReturnSubmission]
     * row - a return submitted more than once keeps every prior attempt (Section 16).
     */
    suspend fun submitGstReturnOnline(
        companyId: String,
        gstReturnId: String,
        fy: FinancialYear,
        gateway: GstOnlineFilingGateway = UnconfiguredGstOnlineFilingGateway()
    ): AccountingResult<GstReturn> {
        val entity = dao.getGstReturnById(companyId, gstReturnId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("GstReturn", gstReturnId))
        if (entity.status != GstReturnStatus.READY) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Return must be READY before submitting - current status is ${entity.status}."))
        }
        val now = System.currentTimeMillis()
        dao.updateGstReturn(entity.copy(status = GstReturnStatus.SUBMITTING, updatedAt = now))

        val period = GstPeriod.of(fy, GstQuarter.valueOf(entity.quarter), entity.month)
        val transactions = getActiveGstTransactionsForPeriod(companyId, entity.financialYearId, period.dateRange())
        val dtos = transactions.map { gt ->
            GSTTransactionExportDto(
                gstTransactionId = gt.gstTransactionId, voucherId = gt.voucherId, voucherType = gt.voucherType,
                partyGstin = gt.partyGstin, placeOfSupply = gt.placeOfSupply, supplyType = gt.supplyType.name,
                hsnSacCode = gt.hsnSacCode, isService = null, taxableAmountPaise = gt.taxableAmount.paise,
                gstRatePercent = gt.gstRatePercent, cgstPaise = gt.cgst.paise, sgstPaise = gt.sgst.paise,
                igstPaise = gt.igst.paise, cessPaise = gt.cess.paise, direction = gt.direction.name, lineOrder = gt.lineOrder
            )
        }
        val requestJson = GstrJsonSerializer.serialize(
            ExportMetadata(exportType = ExportType.GST_TRANSACTIONS, companyId = companyId, financialYearId = entity.financialYearId),
            dtos
        )
        val requestArtifact = GstReturnArtifactEntity(
            artifactId = UUID.randomUUID().toString(), gstReturnId = gstReturnId,
            artifactType = GstReturnArtifactType.REQUEST, schemaVersion = "1.0", jsonContent = requestJson, createdAt = now
        )
        dao.insertGstReturnArtifact(requestArtifact)

        val result = gateway.submitReturn(requestJson)
        val finalStatus = if (result.success) GstReturnStatus.SUBMITTED else GstReturnStatus.FAILED
        val respondedAt = System.currentTimeMillis()
        var responseArtifactId: String? = null
        if (result.responseJson.isNotBlank()) {
            val responseArtifact = GstReturnArtifactEntity(
                artifactId = UUID.randomUUID().toString(), gstReturnId = gstReturnId,
                artifactType = GstReturnArtifactType.RESPONSE, schemaVersion = "1.0", jsonContent = result.responseJson, createdAt = respondedAt
            )
            dao.insertGstReturnArtifact(responseArtifact)
            responseArtifactId = responseArtifact.artifactId
        }

        val priorAttempts = dao.getSubmissionsForGstReturn(gstReturnId)
        dao.insertGstReturnSubmission(
            GstReturnSubmissionEntity(
                submissionId = UUID.randomUUID().toString(), gstReturnId = gstReturnId,
                attemptNumber = priorAttempts.size + 1, requestArtifactId = requestArtifact.artifactId,
                responseArtifactId = responseArtifactId, status = finalStatus,
                acknowledgementNumber = result.acknowledgementNumber, errorCode = result.errorCode,
                errorMessage = result.errorMessage, submittedAt = now, respondedAt = respondedAt
            )
        )
        dao.updateGstReturn(
            dao.getGstReturnById(companyId, gstReturnId)!!.copy(
                status = finalStatus, submittedAt = now, acknowledgementNumber = result.acknowledgementNumber,
                errorCode = result.errorCode, errorMessage = result.errorMessage,
                latestRequestArtifactId = requestArtifact.artifactId,
                latestResponseArtifactId = responseArtifactId ?: entity.latestResponseArtifactId,
                updatedAt = respondedAt
            )
        )
        return AccountingResult.Success(dao.getGstReturnById(companyId, gstReturnId)!!.toDomain())
    }

    // ==================== OUTBOX SYNC & AUDIT ====================
    fun getAuditLogs(companyId: String): Flow<List<AuditLog>> = dao.getAuditLogsByCompany(companyId).map { list ->
        list.map {
            AuditLog(
                logId = it.logId,
                companyId = it.companyId,
                financialYearId = it.financialYearId,
                action = it.action,
                entityType = it.entityType,
                entityId = it.entityId,
                description = it.description,
                performedBy = it.performedBy,
                timestamp = it.timestamp,
                payloadJson = it.payloadJson
            )
        }
    }

    fun getOutboxQueue(companyId: String): Flow<List<OutboxSyncEntity>> = dao.getOutboxQueue(companyId)
    fun getPendingSyncCount(companyId: String): Flow<Int> = dao.getPendingSyncCount(companyId)

    suspend fun triggerSyncCycle(companyId: String): AccountingResult<Int> {
        val pendingItems = dao.getOutboxQueue(companyId).first().filter { it.syncState == SyncState.PENDING }
        for (item in pendingItems) {
            dao.updateOutboxItem(
                item.copy(
                    syncState = SyncState.SYNCED,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = "",
                action = AuditAction.SYNC_TRIGGERED,
                entityType = "SyncEngine",
                entityId = "SYNC_${System.currentTimeMillis()}",
                description = "Synchronized ${pendingItems.size} offline transactions with cloud accounting replica",
                performedBy = "SYNC_WORKER",
                timestamp = System.currentTimeMillis(),
                payloadJson = "{}"
            )
        )
        return AccountingResult.Success(pendingItems.size)
    }

    // ==================== PHASE 7A: PARTY + INVOICE DOMAIN FOUNDATION ====================

    private fun PartyEntity.toDomainParty(): Party = Party(
        partyId = partyId, companyId = companyId, ledgerId = ledgerId, role = role, entityType = entityType,
        displayName = displayName, contactName = contactName, creditLimitPaise = creditLimitPaise,
        paymentTerms = PaymentTerms(paymentTermsType, paymentTermsCustomDays),
        isActive = isActive, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun InvoiceEntity.toDomainInvoice(): Invoice = Invoice(
        invoiceId = invoiceId, companyId = companyId, financialYearId = financialYearId, invoiceType = invoiceType,
        invoiceNumber = invoiceNumber, partyId = partyId, date = safeParseDate(date),
        dueDate = dueDate?.let { safeParseDate(it) }, voucherId = voucherId, referenceInvoiceId = referenceInvoiceId,
        sourceTradeDocumentId = sourceTradeDocumentId,
        narration = narration, createdAt = createdAt, updatedAt = updatedAt
    )

    /** Resolves the standard Debtors/Creditors group ID for [role] (Phase 7A) - the exact groupId
     * convention [StandardSystemGroups]/[createCompany] already use, so a Party's ledger always
     * lands under the correct existing system group. */
    private fun partyGroupId(companyId: String, role: PartyRole): String = when (role) {
        PartyRole.CUSTOMER -> "${StandardSystemGroups.DEBTORS_GROUP_ID}_$companyId"
        PartyRole.SUPPLIER -> "${StandardSystemGroups.CREDITORS_GROUP_ID}_$companyId"
    }

    /** Shared builder for the CREATE_PARTY/UPDATE_PARTY [SyncEvent]s (Phase 7A), mirroring
     * [ledgerSyncEvent]'s pattern exactly. */
    private fun partySyncEvent(operation: SyncOperation, idempotencyKey: String, entity: PartyEntity): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(),
        idempotencyKey = idempotencyKey,
        companyId = entity.companyId,
        financialYearId = "",
        operation = operation.name,
        aggregateType = SyncAggregateType.PARTY.name,
        aggregateId = entity.partyId,
        party = SyncPartyDto(
            partyId = entity.partyId, ledgerId = entity.ledgerId, role = entity.role.name, entityType = entity.entityType.name,
            displayName = entity.displayName, contactName = entity.contactName, creditLimitPaise = entity.creditLimitPaise,
            paymentTermsType = entity.paymentTermsType.name, paymentTermsCustomDays = entity.paymentTermsCustomDays,
            isActive = entity.isActive
        )
    )

    private fun draftInvoiceSyncEvent(entity: InvoiceEntity, lines: List<InvoiceLineEntity>, idempotencyKey: String): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(),
        idempotencyKey = idempotencyKey,
        companyId = entity.companyId,
        financialYearId = entity.financialYearId,
        operation = SyncOperation.CREATE_DRAFT_INVOICE.name,
        aggregateType = SyncAggregateType.INVOICE.name,
        aggregateId = entity.invoiceId,
        invoice = SyncInvoiceDto(
            invoiceId = entity.invoiceId, invoiceType = entity.invoiceType.name, invoiceNumber = entity.invoiceNumber,
            partyId = entity.partyId, date = entity.date, dueDate = entity.dueDate, voucherId = entity.voucherId,
            referenceInvoiceId = entity.referenceInvoiceId, sourceTradeDocumentId = entity.sourceTradeDocumentId,
            narration = entity.narration,
            lines = lines.map {
                SyncInvoiceLineDto(it.lineId, it.itemId, it.itemName, it.hsnSacCode, it.quantityRaw, it.ratePaise, it.gstRatePercent, it.cessRatePercent, it.lineOrder)
            }
        )
    )

    /** Phase 7B: no longer carries invoiceNumber - Invoice numbering is assigned once, at draft
     * creation, and is never overwritten by posting (see [createDraftInvoice]/[postInvoice]). */
    private fun linkInvoiceVoucherSyncEvent(companyId: String, invoiceId: String, voucherId: String, idempotencyKey: String): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(),
        idempotencyKey = idempotencyKey,
        companyId = companyId,
        financialYearId = "",
        operation = SyncOperation.LINK_INVOICE_VOUCHER.name,
        aggregateType = SyncAggregateType.INVOICE.name,
        aggregateId = invoiceId,
        invoice = SyncInvoiceDto(invoiceId = invoiceId, voucherId = voucherId)
    )

    private fun cancelDraftInvoiceSyncEvent(companyId: String, invoiceId: String, idempotencyKey: String): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(),
        idempotencyKey = idempotencyKey,
        companyId = companyId,
        financialYearId = "",
        operation = SyncOperation.CANCEL_DRAFT_INVOICE.name,
        aggregateType = SyncAggregateType.INVOICE.name,
        aggregateId = invoiceId,
        invoice = SyncInvoiceDto(invoiceId = invoiceId)
    )

    /**
     * Creates a Party (Phase 7A) - a thin 1:1 extension of a Ledger, never a replacement. If
     * [party].ledgerId is blank, a new Ledger is created under the standard Debtors/Creditors
     * group via the existing, unmodified [createLedger] ([ledgerTemplate] optionally supplies
     * GSTIN/PAN/address/bank/state-code details for that new ledger - Party itself never stores
     * them). If [party].ledgerId is non-blank, an existing ledger is adopted as-is.
     */
    suspend fun createParty(party: Party, ledgerTemplate: Ledger? = null): AccountingResult<Party> {
        // Rule 30 (Party/Customer/Supplier Data Validation): authoritative here, not only in the
        // UI, so no caller can bypass it (Section 7). Only runs when a NEW ledger is actually being
        // created (ledgerTemplate != null) - linking an existing ledger's already-on-file GSTIN/
        // registration status is never retroactively rejected (Section 10).
        if (ledgerTemplate != null) {
            val validationError = com.example.accounting.domain.party.PartyValidation.validateGstFacts(
                entityType = party.entityType,
                gstRegistrationStatus = ledgerTemplate.gstRegistrationStatus,
                gstin = ledgerTemplate.gstin
            )
            if (validationError != null) {
                return AccountingResult.Failure(AppError.ValidationError(validationError))
            }
        }

        val ledgerId: String
        if (party.ledgerId.isBlank()) {
            val template = ledgerTemplate ?: Ledger(ledgerId = "", companyId = party.companyId, groupId = "", name = party.displayName)
            val ledgerResult = createLedger(
                template.copy(
                    ledgerId = "",
                    companyId = party.companyId,
                    groupId = partyGroupId(party.companyId, party.role),
                    name = party.displayName,
                    isSystem = false,
                    isActive = true
                )
            )
            if (ledgerResult is AccountingResult.Failure) return ledgerResult
            ledgerId = (ledgerResult as AccountingResult.Success).data.ledgerId
        } else {
            dao.getLedgerById(party.companyId, party.ledgerId)
                ?: return AccountingResult.Failure(AppError.ValidationError("Ledger '${party.ledgerId}' was not found for this company."))
            ledgerId = party.ledgerId
        }

        if (dao.getPartyByLedgerId(party.companyId, ledgerId) != null) {
            return AccountingResult.Failure(AppError.ValidationError("Ledger '$ledgerId' is already tracked as a Party."))
        }

        val entity = PartyEntity(
            partyId = party.partyId.ifBlank { "PTY_${UUID.randomUUID().toString().take(8)}_${party.companyId}" },
            companyId = party.companyId,
            ledgerId = ledgerId,
            role = party.role,
            entityType = party.entityType,
            displayName = party.displayName,
            contactName = party.contactName,
            creditLimitPaise = party.creditLimitPaise,
            paymentTermsType = party.paymentTerms.type,
            paymentTermsCustomDays = party.paymentTerms.customDays,
            isActive = party.isActive,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertParty(entity)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(), companyId = party.companyId, financialYearId = "",
                action = AuditAction.CREATE, entityType = "Party", entityId = entity.partyId,
                description = "Created ${party.role.name.lowercase()} party '${party.displayName}' linked to ledger '$ledgerId'",
                performedBy = "ADMIN", timestamp = System.currentTimeMillis(), payloadJson = "{}"
            )
        )

        val createIdempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = party.companyId, entityType = "Party",
                entityId = entity.partyId, operation = "INSERT",
                payloadJson = SyncEventSerializer.toJson(partySyncEvent(SyncOperation.CREATE_PARTY, createIdempotencyKey, entity)),
                idempotencyKey = createIdempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
            )
        )

        return AccountingResult.Success(entity.toDomainParty())
    }

    fun getParties(companyId: String, role: PartyRole? = null): Flow<List<Party>> {
        val source = if (role != null) dao.getPartiesByRole(companyId, role) else dao.getPartiesByCompany(companyId)
        return source.map { list -> list.map { it.toDomainParty() } }
    }

    /**
     * Creates a DRAFT Invoice (Phase 7A) - purely non-accounting-affecting: no Ledger/JournalItem/
     * Trial-Balance/P&L impact whatsoever until [postInvoice] is called. [invoice].dueDate is
     * resolved from the Party's [PaymentTerms] when not explicitly supplied, then snapshotted -
     * a later change to the party's terms never retroactively alters this invoice's due date.
     */
    suspend fun createDraftInvoice(invoice: Invoice, lines: List<InvoiceLine>): AccountingResult<Invoice> {
        if (lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("An invoice must have at least one line item."))
        }
        val party = dao.getPartyById(invoice.companyId, invoice.partyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Party '${invoice.partyId}' was not found."))

        val resolvedDueDate = invoice.dueDate ?: PaymentTerms(party.paymentTermsType, party.paymentTermsCustomDays).dueDate(invoice.date)
        val invoiceId = invoice.invoiceId.ifBlank { "INVD_${UUID.randomUUID().toString().take(8)}_${invoice.companyId}" }
        // Phase 7B: assigned once, here, via its own independent sequence - never assumed to equal
        // the eventual Voucher's own voucherNumber (see generateNextDocumentNumber/postInvoice).
        val invoiceNumber = generateNextDocumentNumber(invoice.companyId, invoice.financialYearId, DocumentType.valueOf(invoice.invoiceType.name))

        val entity = InvoiceEntity(
            invoiceId = invoiceId, companyId = invoice.companyId, financialYearId = invoice.financialYearId,
            invoiceType = invoice.invoiceType, invoiceNumber = invoiceNumber, partyId = invoice.partyId,
            date = invoice.date.toString(), dueDate = resolvedDueDate.toString(), voucherId = null,
            referenceInvoiceId = invoice.referenceInvoiceId, sourceTradeDocumentId = invoice.sourceTradeDocumentId,
            narration = invoice.narration,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
        dao.insertInvoice(entity)

        val lineEntities = lines.mapIndexed { index, line ->
            InvoiceLineEntity(
                lineId = line.lineId.ifBlank { UUID.randomUUID().toString() }, invoiceId = invoiceId,
                itemId = line.itemId, itemName = line.itemName, hsnSacCode = line.hsnSacCode,
                quantityRaw = line.quantity.rawValue, ratePaise = line.rate.paise,
                gstRatePercent = line.gstRatePercent, cessRatePercent = line.cessRatePercent, lineOrder = index + 1
            )
        }
        dao.insertInvoiceLines(lineEntities)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(), companyId = invoice.companyId, financialYearId = invoice.financialYearId,
                action = AuditAction.CREATE, entityType = "Invoice", entityId = invoiceId,
                description = "Created draft ${invoice.invoiceType.name.lowercase().replace('_', ' ')} for party '${party.displayName}'",
                performedBy = "ADMIN", timestamp = System.currentTimeMillis(), payloadJson = "{}"
            )
        )

        val createIdempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = invoice.companyId, entityType = "Invoice",
                entityId = invoiceId, operation = "INSERT",
                payloadJson = SyncEventSerializer.toJson(draftInvoiceSyncEvent(entity, lineEntities, createIdempotencyKey)),
                idempotencyKey = createIdempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
            )
        )

        return AccountingResult.Success(entity.toDomainInvoice())
    }

    /**
     * Updates an existing, still-DRAFT invoice's header/lines (Phase 7J-B) - mirrors
     * [createDraftInvoice]'s exact persistence shape (the one narrow, additive repository function
     * this phase's [com.example.accounting.application.invoice.InvoiceManagementService.updateDraft]
     * needed, since no update path existed before). Never callable once posted (posting is
     * immutable, matching every other voucher's Deletion Policy) - rejects with
     * [AppError.BusinessRuleViolation], the same way [postInvoice] already rejects double-posting.
     * `invoiceNumber` is never reassigned here - it was already fixed at draft-creation time
     * (Phase 7B) and is never regenerated by an edit.
     */
    suspend fun updateDraftInvoice(invoice: Invoice, lines: List<InvoiceLine>): AccountingResult<Invoice> {
        if (lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("An invoice must have at least one line item."))
        }
        val existing = dao.getInvoiceById(invoice.companyId, invoice.invoiceId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Invoice '${invoice.invoiceId}' was not found."))
        if (existing.voucherId != null) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Invoice '${invoice.invoiceId}' has already been posted and can no longer be edited."))
        }
        val party = dao.getPartyById(invoice.companyId, invoice.partyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Party '${invoice.partyId}' was not found."))

        val resolvedDueDate = invoice.dueDate ?: PaymentTerms(party.paymentTermsType, party.paymentTermsCustomDays).dueDate(invoice.date)
        val updatedAt = System.currentTimeMillis()
        val updatedEntity = existing.copy(
            partyId = invoice.partyId,
            date = invoice.date.toString(),
            dueDate = resolvedDueDate.toString(),
            referenceInvoiceId = invoice.referenceInvoiceId,
            narration = invoice.narration,
            updatedAt = updatedAt
        )
        dao.insertInvoice(updatedEntity)

        dao.deleteLinesForInvoice(invoice.invoiceId)
        val lineEntities = lines.mapIndexed { index, line ->
            InvoiceLineEntity(
                lineId = line.lineId.ifBlank { UUID.randomUUID().toString() }, invoiceId = invoice.invoiceId,
                itemId = line.itemId, itemName = line.itemName, hsnSacCode = line.hsnSacCode,
                quantityRaw = line.quantity.rawValue, ratePaise = line.rate.paise,
                gstRatePercent = line.gstRatePercent, cessRatePercent = line.cessRatePercent, lineOrder = index + 1
            )
        }
        dao.insertInvoiceLines(lineEntities)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(), companyId = invoice.companyId, financialYearId = invoice.financialYearId,
                action = AuditAction.UPDATE, entityType = "Invoice", entityId = invoice.invoiceId,
                description = "Updated draft invoice '${invoice.invoiceId}'",
                performedBy = "ADMIN", timestamp = updatedAt, payloadJson = "{}"
            )
        )

        return AccountingResult.Success(updatedEntity.toDomainInvoice())
    }

    /**
     * Posts a draft Invoice (Phase 7A). The caller builds [voucher] (plus [stockLines]/
     * [gstTransactions]) exactly the way every Sale/Purchase/Credit-Debit-Note voucher is already
     * built today, via the existing, unmodified [com.example.accounting.domain.trading.TradingWorkflowEngine] -
     * this function never re-derives GST/ledger resolution itself. It only calls the existing,
     * unmodified [postVoucher] and then links the Invoice to the resulting Voucher; PARTIALLY_PAID/
     * PAID/OVERDUE/CANCELLED are never stored here, only ever derived by [getInvoiceStatus].
     */
    suspend fun postInvoice(
        companyId: String,
        invoiceId: String,
        voucher: Voucher,
        idempotencyKey: String = UUID.randomUUID().toString(),
        stockLines: List<VoucherStockLine> = emptyList(),
        gstTransactions: List<GstTransaction> = emptyList()
    ): AccountingResult<Invoice> {
        val invoiceEntity = dao.getInvoiceById(companyId, invoiceId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Invoice '$invoiceId' was not found."))
        if (invoiceEntity.voucherId != null) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Invoice '$invoiceId' has already been posted as voucher '${invoiceEntity.voucherId}'."))
        }

        val postResult = postVoucher(voucher, idempotencyKey, stockLines, gstTransactions)
        if (postResult is AccountingResult.Failure) return postResult
        val postedVoucher = (postResult as AccountingResult.Success).data

        // Phase 7B: only ever sets voucherId - invoiceNumber was already assigned at draft-creation
        // time and is never overwritten here (it is deliberately NOT assumed to equal the
        // Voucher's own voucherNumber).
        val linkedAt = System.currentTimeMillis()
        dao.linkInvoiceToVoucher(companyId, invoiceId, postedVoucher.voucherId, linkedAt)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(), companyId = companyId, financialYearId = invoiceEntity.financialYearId,
                action = AuditAction.UPDATE, entityType = "Invoice", entityId = invoiceId,
                description = "Posted invoice '$invoiceId' as voucher '${postedVoucher.voucherNumber}'",
                performedBy = "ADMIN", timestamp = linkedAt, payloadJson = "{}"
            )
        )

        val linkIdempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = companyId, entityType = "Invoice", entityId = invoiceId,
                operation = "UPDATE",
                payloadJson = SyncEventSerializer.toJson(
                    linkInvoiceVoucherSyncEvent(companyId, invoiceId, postedVoucher.voucherId, linkIdempotencyKey)
                ),
                idempotencyKey = linkIdempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = linkedAt, updatedAt = linkedAt
            )
        )

        val updatedEntity = invoiceEntity.copy(voucherId = postedVoucher.voucherId, updatedAt = linkedAt)
        return AccountingResult.Success(updatedEntity.toDomainInvoice())
    }

    /**
     * Cancels an Invoice (Phase 7A). A still-DRAFT invoice (no voucherId yet) is simply deleted
     * outright, since it never had any accounting effect to reverse. A posted invoice delegates
     * to the existing, unmodified [deleteVoucherSafely] on its linked voucher - CANCELLED is then
     * purely derived from that Voucher's isCancelled flag via [InvoiceStatusEngine], never a
     * separate stored field that could drift.
     */
    suspend fun cancelInvoice(companyId: String, financialYearId: String, invoiceId: String): AccountingResult<Unit> {
        val invoiceEntity = dao.getInvoiceById(companyId, invoiceId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Invoice '$invoiceId' was not found."))

        val voucherId = invoiceEntity.voucherId
        if (voucherId == null) {
            dao.deleteLinesForInvoice(invoiceId)
            val deleted = dao.deleteDraftInvoice(companyId, invoiceId)
            if (deleted == 0) {
                return AccountingResult.Failure(AppError.BusinessRuleViolation("Invoice '$invoiceId' could not be deleted as a draft."))
            }

            val deleteIdempotencyKey = UUID.randomUUID().toString()
            dao.insertOutboxItem(
                OutboxSyncEntity(
                    syncId = UUID.randomUUID().toString(), companyId = companyId, entityType = "Invoice", entityId = invoiceId,
                    operation = "DELETE",
                    payloadJson = SyncEventSerializer.toJson(cancelDraftInvoiceSyncEvent(companyId, invoiceId, deleteIdempotencyKey)),
                    idempotencyKey = deleteIdempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
                )
            )
            return AccountingResult.Success(Unit)
        }

        return deleteVoucherSafely(companyId, financialYearId, voucherId)
    }

    /** The single read path for an Invoice's lifecycle status (Phase 7A) - composes the existing,
     * unmodified [computeOutstandingPaise] with [InvoiceStatusEngine]; never a stored field. */
    suspend fun getInvoiceStatus(companyId: String, invoiceId: String): AccountingResult<InvoiceStatus> {
        val invoiceEntity = dao.getInvoiceById(companyId, invoiceId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Invoice '$invoiceId' was not found."))

        val voucherId = invoiceEntity.voucherId
            ?: return AccountingResult.Success(InvoiceStatus.DRAFT)

        val voucher = dao.getVoucherById(companyId, voucherId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Linked voucher '$voucherId' was not found."))

        val outstandingPaise = computeOutstandingPaise(companyId, voucherId) ?: 0L
        val status = InvoiceStatusEngine.deriveStatus(
            voucherId = voucherId,
            isCancelled = voucher.isCancelled,
            totalAmountPaise = voucher.totalAmountPaise,
            outstandingPaise = outstandingPaise,
            dueDate = invoiceEntity.dueDate?.let { safeParseDate(it) }
        )
        return AccountingResult.Success(status)
    }

    fun getInvoicesForParty(companyId: String, partyId: String): Flow<List<Invoice>> =
        dao.getInvoicesByParty(companyId, partyId).map { list -> list.map { it.toDomainInvoice() } }

    /** Every Invoice for a company, regardless of party (Phase 7J-B) - a second minimal, read-only,
     * one-line addition alongside [updateDraftInvoice], mirroring [getInvoicesForParty]'s exact
     * shape. Backs [com.example.accounting.application.invoice.InvoiceManagementService.search]
     * when no `partyId` filter is supplied - never a second query engine, the same
     * `dao.getInvoicesByCompany` the internal outstanding-report functions already use. */
    fun getInvoicesForCompany(companyId: String): Flow<List<Invoice>> =
        dao.getInvoicesByCompany(companyId).map { list -> list.map { it.toDomainInvoice() } }

    /** A single Invoice's line items (Phase 7J-B) - a third minimal, read-only addition backing
     * [com.example.accounting.application.invoice.InvoiceManagementService.duplicateInvoice],
     * reusing the exact entity->domain mapping [convertTradeDocumentToInvoice] already applies to
     * `TradeDocumentLine`, applied here to `InvoiceLineEntity` instead. */
    suspend fun getInvoiceLines(companyId: String, invoiceId: String): AccountingResult<List<InvoiceLine>> {
        dao.getInvoiceById(companyId, invoiceId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Invoice '$invoiceId' was not found."))
        val lines = dao.getLinesForInvoice(invoiceId).map {
            InvoiceLine(
                lineId = it.lineId, itemId = it.itemId, itemName = it.itemName, hsnSacCode = it.hsnSacCode,
                quantity = Quantity(it.quantityRaw), rate = Money.fromPaise(it.ratePaise),
                gstRatePercent = it.gstRatePercent, cessRatePercent = it.cessRatePercent, lineOrder = it.lineOrder
            )
        }
        return AccountingResult.Success(lines)
    }

    // ==================== PHASE 7B: DOCUMENT/VOUCHER LIFECYCLE ARCHITECTURE ====================

    private fun TradeDocumentEntity.toDomainTradeDocument(): TradeDocument = TradeDocument(
        tradeDocumentId = tradeDocumentId, companyId = companyId, financialYearId = financialYearId,
        documentType = documentType, documentNumber = documentNumber, partyId = partyId,
        date = safeParseDate(date), status = status, sourceTradeDocumentId = sourceTradeDocumentId,
        narration = narration, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun tradeDocumentSyncEvent(operation: SyncOperation, entity: TradeDocumentEntity, lines: List<TradeDocumentLineEntity>, idempotencyKey: String): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(), idempotencyKey = idempotencyKey, companyId = entity.companyId,
        financialYearId = entity.financialYearId, operation = operation.name, aggregateType = SyncAggregateType.TRADE_DOCUMENT.name,
        aggregateId = entity.tradeDocumentId,
        tradeDocument = SyncTradeDocumentDto(
            tradeDocumentId = entity.tradeDocumentId, documentType = entity.documentType.name, documentNumber = entity.documentNumber,
            partyId = entity.partyId, date = entity.date, status = entity.status.name,
            sourceTradeDocumentId = entity.sourceTradeDocumentId, narration = entity.narration,
            lines = lines.map {
                SyncTradeDocumentLineDto(it.lineId, it.itemId, it.itemName, it.hsnSacCode, it.quantityRaw, it.ratePaise, it.gstRatePercent, it.cessRatePercent, it.lineOrder)
            }
        )
    )

    private fun tradeDocumentStatusSyncEvent(operation: SyncOperation, companyId: String, tradeDocumentId: String, status: DocumentStatus, idempotencyKey: String): SyncEvent = SyncEvent(
        eventId = UUID.randomUUID().toString(), idempotencyKey = idempotencyKey, companyId = companyId,
        financialYearId = "", operation = operation.name, aggregateType = SyncAggregateType.TRADE_DOCUMENT.name,
        aggregateId = tradeDocumentId,
        tradeDocument = SyncTradeDocumentDto(tradeDocumentId = tradeDocumentId, status = status.name)
    )

    /**
     * Creates a DRAFT TradeDocument (Phase 7B) - Quotation/Proforma/Sales-Purchase-Order/Delivery-
     * Receipt-Note. Purely non-accounting-affecting: no Ledger/JournalItem/Voucher row whatsoever.
     * Rejects the 4 posting document types ([DocumentType.isPostingDocument]) - those are Invoices
     * (7A), created via [createDraftInvoice], not TradeDocuments.
     */
    suspend fun createTradeDocument(document: TradeDocument, lines: List<TradeDocumentLine>): AccountingResult<TradeDocument> {
        if (document.documentType.isPostingDocument) {
            return AccountingResult.Failure(AppError.ValidationError("${document.documentType} is a posting document type - create it as an Invoice, not a TradeDocument."))
        }
        if (lines.isEmpty()) {
            return AccountingResult.Failure(AppError.ValidationError("A document must have at least one line item."))
        }
        val party = dao.getPartyById(document.companyId, document.partyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Party '${document.partyId}' was not found."))

        val tradeDocumentId = document.tradeDocumentId.ifBlank { "TRD_${UUID.randomUUID().toString().take(8)}_${document.companyId}" }
        val documentNumber = generateNextDocumentNumber(document.companyId, document.financialYearId, document.documentType)

        val entity = TradeDocumentEntity(
            tradeDocumentId = tradeDocumentId, companyId = document.companyId, financialYearId = document.financialYearId,
            documentType = document.documentType, documentNumber = documentNumber, partyId = document.partyId,
            date = document.date.toString(), status = DocumentStatus.DRAFT, sourceTradeDocumentId = document.sourceTradeDocumentId,
            narration = document.narration, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
        dao.insertTradeDocument(entity)

        val lineEntities = lines.mapIndexed { index, line ->
            TradeDocumentLineEntity(
                lineId = line.lineId.ifBlank { UUID.randomUUID().toString() }, tradeDocumentId = tradeDocumentId,
                itemId = line.itemId, itemName = line.itemName, hsnSacCode = line.hsnSacCode,
                quantityRaw = line.quantity.rawValue, ratePaise = line.rate.paise,
                gstRatePercent = line.gstRatePercent, cessRatePercent = line.cessRatePercent, lineOrder = index + 1
            )
        }
        dao.insertTradeDocumentLines(lineEntities)

        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(), companyId = document.companyId, financialYearId = document.financialYearId,
                action = AuditAction.CREATE, entityType = "TradeDocument", entityId = tradeDocumentId,
                description = "Created draft ${document.documentType.name.lowercase().replace('_', ' ')} for party '${party.displayName}'",
                performedBy = "ADMIN", timestamp = System.currentTimeMillis(), payloadJson = "{}"
            )
        )

        val createIdempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = document.companyId, entityType = "TradeDocument",
                entityId = tradeDocumentId, operation = "INSERT",
                payloadJson = SyncEventSerializer.toJson(tradeDocumentSyncEvent(SyncOperation.CREATE_TRADE_DOCUMENT, entity, lineEntities, createIdempotencyKey)),
                idempotencyKey = createIdempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
            )
        )

        return AccountingResult.Success(entity.toDomainTradeDocument())
    }

    fun getTradeDocuments(companyId: String, documentType: DocumentType? = null): Flow<List<TradeDocument>> {
        val source = if (documentType != null) dao.getTradeDocumentsByType(companyId, documentType) else dao.getTradeDocumentsByCompany(companyId)
        return source.map { list -> list.map { it.toDomainTradeDocument() } }
    }

    /** Issues a DRAFT TradeDocument (Phase 7B): DRAFT -> ISSUED. Lines become immutable once
     * issued (mirrors "once shown/sent, don't silently rewrite it"). */
    suspend fun issueTradeDocument(companyId: String, tradeDocumentId: String): AccountingResult<TradeDocument> {
        val entity = dao.getTradeDocumentById(companyId, tradeDocumentId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Document '$tradeDocumentId' was not found."))
        if (entity.status != DocumentStatus.DRAFT) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$tradeDocumentId' is not a draft (status: ${entity.status})."))
        }

        val updatedAt = System.currentTimeMillis()
        val updated = entity.copy(status = DocumentStatus.ISSUED, updatedAt = updatedAt)
        dao.updateTradeDocument(updated)

        val idempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = companyId, entityType = "TradeDocument", entityId = tradeDocumentId,
                operation = "UPDATE",
                payloadJson = SyncEventSerializer.toJson(tradeDocumentStatusSyncEvent(SyncOperation.ISSUE_TRADE_DOCUMENT, companyId, tradeDocumentId, DocumentStatus.ISSUED, idempotencyKey)),
                idempotencyKey = idempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = updatedAt, updatedAt = updatedAt
            )
        )

        return AccountingResult.Success(updated.toDomainTradeDocument())
    }

    private suspend fun markTradeDocumentConverted(companyId: String, tradeDocumentId: String) {
        val source = dao.getTradeDocumentById(companyId, tradeDocumentId) ?: return
        val updatedAt = System.currentTimeMillis()
        dao.updateTradeDocument(source.copy(status = DocumentStatus.CONVERTED, updatedAt = updatedAt))

        val idempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = companyId, entityType = "TradeDocument", entityId = tradeDocumentId,
                operation = "UPDATE",
                payloadJson = SyncEventSerializer.toJson(tradeDocumentStatusSyncEvent(SyncOperation.CONVERT_TRADE_DOCUMENT, companyId, tradeDocumentId, DocumentStatus.CONVERTED, idempotencyKey)),
                idempotencyKey = idempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = updatedAt, updatedAt = updatedAt
            )
        )
    }

    /**
     * Converts a TradeDocument into another TradeDocument (Phase 7B - e.g. Quotation -> Sales
     * Order) - a plain copy of header+lines with [TradeDocument.sourceTradeDocumentId] set to the
     * source, and the source's own status flipped to CONVERTED. Never re-implements posting - this
     * path never produces a Voucher or Invoice; use [convertTradeDocumentToInvoice] for that.
     */
    suspend fun convertTradeDocument(companyId: String, sourceTradeDocumentId: String, targetType: DocumentType): AccountingResult<TradeDocument> {
        if (targetType.isPostingDocument) {
            return AccountingResult.Failure(AppError.ValidationError("Use convertTradeDocumentToInvoice to convert into $targetType."))
        }
        val source = dao.getTradeDocumentById(companyId, sourceTradeDocumentId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Document '$sourceTradeDocumentId' was not found."))
        if (source.status == DocumentStatus.CONVERTED) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$sourceTradeDocumentId' has already been converted."))
        }
        if (source.status == DocumentStatus.CANCELLED) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$sourceTradeDocumentId' is cancelled and cannot be converted."))
        }

        val sourceLines = dao.getLinesForTradeDocument(sourceTradeDocumentId)
        val newDocument = TradeDocument(
            tradeDocumentId = "", companyId = companyId, financialYearId = source.financialYearId,
            documentType = targetType, partyId = source.partyId, date = safeParseDate(source.date),
            sourceTradeDocumentId = sourceTradeDocumentId, narration = source.narration
        )
        val newLines = sourceLines.map {
            TradeDocumentLine(
                lineId = "", itemId = it.itemId, itemName = it.itemName, hsnSacCode = it.hsnSacCode,
                quantity = Quantity(it.quantityRaw), rate = Money.fromPaise(it.ratePaise),
                gstRatePercent = it.gstRatePercent, cessRatePercent = it.cessRatePercent
            )
        }
        val createResult = createTradeDocument(newDocument, newLines)
        if (createResult is AccountingResult.Failure) return createResult

        markTradeDocumentConverted(companyId, sourceTradeDocumentId)
        return createResult
    }

    /**
     * Converts a TradeDocument into a new DRAFT Invoice (Phase 7B - e.g. Sales Order -> Sales
     * Invoice) by calling the existing, unmodified [createDraftInvoice] - never a new posting
     * mechanism. Actually posting the resulting Invoice still goes through the existing,
     * unmodified [postInvoice].
     */
    suspend fun convertTradeDocumentToInvoice(companyId: String, sourceTradeDocumentId: String, invoiceType: InvoiceType): AccountingResult<Invoice> {
        val source = dao.getTradeDocumentById(companyId, sourceTradeDocumentId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Document '$sourceTradeDocumentId' was not found."))
        if (source.status == DocumentStatus.CONVERTED) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$sourceTradeDocumentId' has already been converted."))
        }
        if (source.status == DocumentStatus.CANCELLED) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$sourceTradeDocumentId' is cancelled and cannot be converted."))
        }

        val sourceLines = dao.getLinesForTradeDocument(sourceTradeDocumentId)
        val newInvoice = Invoice(
            invoiceId = "", companyId = companyId, financialYearId = source.financialYearId,
            invoiceType = invoiceType, partyId = source.partyId, date = safeParseDate(source.date),
            sourceTradeDocumentId = sourceTradeDocumentId, narration = source.narration
        )
        val newLines = sourceLines.map {
            InvoiceLine(
                lineId = "", itemId = it.itemId, itemName = it.itemName, hsnSacCode = it.hsnSacCode,
                quantity = Quantity(it.quantityRaw), rate = Money.fromPaise(it.ratePaise),
                gstRatePercent = it.gstRatePercent, cessRatePercent = it.cessRatePercent
            )
        }
        val createResult = createDraftInvoice(newInvoice, newLines)
        if (createResult is AccountingResult.Failure) return createResult

        markTradeDocumentConverted(companyId, sourceTradeDocumentId)
        return createResult
    }

    /**
     * Cancels a TradeDocument (Phase 7B). A DRAFT is hard-deleted (never shown to anyone); an
     * ISSUED document is marked CANCELLED (record preserved). A CONVERTED document can never be
     * cancelled directly - the downstream document/invoice must be dealt with first.
     */
    suspend fun cancelTradeDocument(companyId: String, tradeDocumentId: String): AccountingResult<Unit> {
        val entity = dao.getTradeDocumentById(companyId, tradeDocumentId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Document '$tradeDocumentId' was not found."))

        if (entity.status == DocumentStatus.CONVERTED) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$tradeDocumentId' has already been converted and cannot be cancelled directly."))
        }
        if (entity.status == DocumentStatus.CANCELLED) {
            return AccountingResult.Failure(AppError.BusinessRuleViolation("Document '$tradeDocumentId' is already cancelled."))
        }

        val updatedAt = System.currentTimeMillis()
        val wasDraft = entity.status == DocumentStatus.DRAFT
        if (wasDraft) {
            dao.deleteLinesForTradeDocument(tradeDocumentId)
            dao.deleteTradeDocument(companyId, tradeDocumentId)
        } else {
            dao.updateTradeDocument(entity.copy(status = DocumentStatus.CANCELLED, updatedAt = updatedAt))
        }

        val idempotencyKey = UUID.randomUUID().toString()
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(), companyId = companyId, entityType = "TradeDocument", entityId = tradeDocumentId,
                operation = if (wasDraft) "DELETE" else "UPDATE",
                payloadJson = SyncEventSerializer.toJson(tradeDocumentStatusSyncEvent(SyncOperation.CANCEL_TRADE_DOCUMENT, companyId, tradeDocumentId, DocumentStatus.CANCELLED, idempotencyKey)),
                idempotencyKey = idempotencyKey, syncState = SyncState.PENDING, retryCount = 0, lastError = null,
                createdAt = updatedAt, updatedAt = updatedAt
            )
        )

        return AccountingResult.Success(Unit)
    }

    // ==================== PHASE 7C: REPORT MANAGEMENT ====================

    /**
     * Day Book (Phase 7C) - a chronological listing of posted/cancelled Vouchers only. A
     * non-posting document ([com.example.accounting.domain.document.TradeDocument], Phase 7B)
     * cannot appear here even in principle: this reads exclusively from `dao.getVouchersByDateRange`,
     * a structurally different table. Party name prefers the existing Invoice<->Voucher link (7A)
     * when present (Sales/Purchase/Credit/Debit), falling back to [resolveTradeCounterparty]'s
     * real counterparty ledger - most Sale/Purchase postings never create an [Invoice] row, so
     * requiring one would leave this column blank for nearly every real posting; other voucher
     * types carry no party linkage in the current domain and are left null rather than guessed at.
     */
    suspend fun generateDayBook(companyId: String, dateRange: ClosedRange<LocalDate>): DayBookReport {
        val vouchers = dao.getVouchersByDateRange(companyId, dateRange.start.toString(), dateRange.endInclusive.toString())
            .first().sortedBy { it.date }
        val tradeVoucherTypes = setOf(VoucherType.SALES, VoucherType.PURCHASE, VoucherType.CREDIT_NOTE, VoucherType.DEBIT_NOTE)

        val rows = vouchers.map { voucher ->
            val invoicePartyName = dao.getInvoiceByVoucherId(voucher.voucherId)?.let { invoice -> dao.getPartyById(companyId, invoice.partyId)?.displayName }
            val partyName = invoicePartyName
                ?: if (voucher.voucherType in tradeVoucherTypes) resolveTradeCounterparty(companyId, voucher.voucherId)?.name else null
            DayBookRow(
                voucherId = voucher.voucherId,
                voucherNumber = voucher.voucherNumber,
                voucherType = voucher.voucherType,
                date = safeParseDate(voucher.date),
                partyName = partyName,
                narration = voucher.narration,
                totalAmount = Money.fromPaise(voucher.totalAmountPaise),
                status = if (voucher.isCancelled) DayBookEntryStatus.CANCELLED else DayBookEntryStatus.POSTED
            )
        }

        return DayBookReport(
            dateRangeLabel = "${dateRange.start} to ${dateRange.endInclusive}",
            rows = rows,
            totalAmount = Money.fromPaise(rows.filter { it.status == DayBookEntryStatus.POSTED }.sumOf { it.totalAmount.paise })
        )
    }

    private fun agingBucketFor(daysOutstanding: Int): AgingBucket = when {
        daysOutstanding <= 0 -> AgingBucket.CURRENT
        daysOutstanding <= 30 -> AgingBucket.DAYS_1_30
        daysOutstanding <= 60 -> AgingBucket.DAYS_31_60
        daysOutstanding <= 90 -> AgingBucket.DAYS_61_90
        else -> AgingBucket.DAYS_90_PLUS
    }

    /** Resolves the real counterparty ledger of a Sale/Purchase/Credit-Note/Debit-Note voucher -
     * always the `lineOrder == 1` journal item ([TradingWorkflowEngine.build]/[buildAccountOnly]
     * post it first, Debit for Sale/Credit for Purchase; [TradingWorkflowEngine.buildNote] preserves
     * line order when reversing) - regardless of whether that ledger is a registered Customer/
     * Supplier [Party]. Never touches the frozen posting engine; purely a read-side lookup so
     * Outstanding/Day Book can attribute a real posting to its real ledger instead of requiring a
     * [Party]/[Invoice] record that most Sale/Purchase postings never create. */
    private suspend fun resolveTradeCounterparty(companyId: String, voucherId: String): LedgerEntity? {
        val partyLine = dao.getJournalItemsForVoucherSync(voucherId).minByOrNull { it.lineOrder } ?: return null
        return dao.getLedgerById(companyId, partyLine.ledgerId)
    }

    /**
     * Outstanding/Receivables/Payables (Phase 7C) - one shared report over every real, non-
     * cancelled Sale/Purchase voucher, Party or not (Rule: Receivable/Payable = actual outstanding
     * balances, never Customer/Supplier-only). Every figure is sourced from the existing,
     * unmodified [computeOutstandingPaise] (amount) and [InvoiceStatusEngine.deriveStatus]
     * (status) - this function never re-derives an outstanding amount itself. `role = null` returns
     * both Receivables and Payables combined; [generateReceivablesReport]/[generatePayablesReport]
     * are thin role-scoped wrappers.
     *
     * Sourced directly from [VoucherEntity]/[computeOutstandingPaise] - the same authoritative facts
     * [getOutstandingInvoices] already uses for Settlement allocation - rather than the [Invoice]
     * table: a real [Invoice] draft is a separate, optional pre-posting document (Phase 7A) that
     * nothing in the actual Sale/Purchase entry flow ([TradingWorkflowEngine]/[postVoucher]) creates,
     * so sourcing from it exclusively silently dropped every ordinary posting. When a voucher DOES
     * have a linked [Invoice] (e.g. a future Invoice-lifecycle UI, or [RecurringInvoiceSchedule]),
     * its formal invoiceNumber/dueDate/partyId enrich the row instead of being required for it.
     */
    suspend fun generateOutstandingReport(
        companyId: String,
        role: PartyRole? = null,
        today: LocalDate = LocalDate.now()
    ): OutstandingReport {
        val voucherTypes = when (role) {
            PartyRole.CUSTOMER -> setOf(VoucherType.SALES)
            PartyRole.SUPPLIER -> setOf(VoucherType.PURCHASE)
            null -> setOf(VoucherType.SALES, VoucherType.PURCHASE)
        }
        val invoiceTypeFor = { voucherType: VoucherType ->
            if (voucherType == VoucherType.SALES) InvoiceType.SALES_INVOICE else InvoiceType.PURCHASE_BILL
        }

        val vouchers = dao.getVouchersByCompany(companyId).first().filter { !it.isCancelled && it.voucherType in voucherTypes }

        val rows = mutableListOf<OutstandingReportRow>()
        for (voucher in vouchers) {
            val outstandingPaise = computeOutstandingPaise(companyId, voucher.voucherId) ?: continue
            if (outstandingPaise <= 0L) continue

            val linkedInvoice = dao.getInvoiceByVoucherId(voucher.voucherId)
            val counterpartyLedger = resolveTradeCounterparty(companyId, voucher.voucherId)
            val linkedParty = linkedInvoice?.let { dao.getPartyById(companyId, it.partyId) }

            val partyId = linkedParty?.partyId ?: counterpartyLedger?.ledgerId ?: continue
            val partyName = linkedParty?.displayName ?: counterpartyLedger?.name ?: partyId

            val dueDate = linkedInvoice?.dueDate?.let { safeParseDate(it) }
            val status = InvoiceStatusEngine.deriveStatus(
                voucherId = voucher.voucherId, isCancelled = voucher.isCancelled, totalAmountPaise = voucher.totalAmountPaise,
                outstandingPaise = outstandingPaise, dueDate = dueDate, today = today
            )
            // Aging is measured from the due date; a posting with no linked Invoice (hence no due
            // date) is not aged at all (daysOutstanding = 0, bucketed CURRENT) rather than guessing
            // a basis for it - see docs/39_OUTSTANDING_REPORTS.md.
            val daysOutstanding = if (dueDate != null) java.time.temporal.ChronoUnit.DAYS.between(dueDate, today).toInt().coerceAtLeast(0) else 0

            rows.add(
                OutstandingReportRow(
                    invoiceId = linkedInvoice?.invoiceId ?: voucher.voucherId, invoiceNumber = linkedInvoice?.invoiceNumber,
                    invoiceType = invoiceTypeFor(voucher.voucherType),
                    partyId = partyId, partyName = partyName, voucherId = voucher.voucherId, voucherNumber = voucher.voucherNumber,
                    date = safeParseDate(linkedInvoice?.date ?: voucher.date), dueDate = dueDate,
                    totalAmount = Money.fromPaise(voucher.totalAmountPaise), outstandingAmount = Money.fromPaise(outstandingPaise),
                    status = status, daysOutstanding = daysOutstanding, agingBucket = agingBucketFor(daysOutstanding)
                )
            )
        }

        val agingSummary = AgingBucket.entries.map { bucket ->
            val bucketRows = rows.filter { it.agingBucket == bucket }
            AgingBucketTotal(bucket = bucket, totalOutstanding = Money.fromPaise(bucketRows.sumOf { it.outstandingAmount.paise }), invoiceCount = bucketRows.size)
        }

        return OutstandingReport(
            rows = rows.sortedBy { it.dueDate ?: it.date },
            totalOutstanding = Money.fromPaise(rows.sumOf { it.outstandingAmount.paise }),
            agingSummary = agingSummary
        )
    }

    suspend fun generateReceivablesReport(companyId: String, today: LocalDate = LocalDate.now()): OutstandingReport =
        generateOutstandingReport(companyId, PartyRole.CUSTOMER, today)

    suspend fun generatePayablesReport(companyId: String, today: LocalDate = LocalDate.now()): OutstandingReport =
        generateOutstandingReport(companyId, PartyRole.SUPPLIER, today)

    /** Opening Current-Assets(excl. Cash/Bank)/Current-Liabilities using ONLY each ledger's own
     * stored opening balance (zero transactions) - reuses [GroupAggregationEngine] with opening-
     * balance contributions instead of journal-item contributions, the same engine
     * [generateTrialBalance]/[generateBalanceSheet] already feed different contribution sets to.
     * Needed only when a Cash Flow period starts exactly at the financial year's own start date,
     * where "the moment before periodStart" falls outside the FY and there is no valid
     * transaction-filtered date range left to query. */
    private suspend fun openingCurrentAssetsExclCashAndLiabilitiesPaise(companyId: String): Pair<Long, Long> {
        val groupEntities = dao.getGroupsByCompany(companyId).first()
        val domainGroups = groupEntities.map { AccountGroup(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder) }
        val ledgers = dao.getLedgersByCompany(companyId).first()
        val contributions = ledgers.map {
            val dr = if (it.openingBalanceType == DrCr.DEBIT) it.openingBalancePaise else 0L
            val cr = if (it.openingBalanceType == DrCr.CREDIT) it.openingBalancePaise else 0L
            GroupAggregationEngine.LedgerContribution(it.groupId, dr, cr)
        }
        val hierarchy = when (val result = GroupAggregationEngine.aggregate(domainGroups, contributions)) {
            is AccountingResult.Success -> result.data
            is AccountingResult.Failure -> return 0L to 0L
        }
        fun netDebit(bareId: String): Long {
            val node = GroupAggregationEngine.findNode(hierarchy, "${bareId}_$companyId") ?: return 0L
            return node.totalDebitPaise - node.totalCreditPaise
        }
        fun netCredit(bareId: String): Long {
            val node = GroupAggregationEngine.findNode(hierarchy, "${bareId}_$companyId") ?: return 0L
            return node.totalCreditPaise - node.totalDebitPaise
        }
        val currentAssetsExclCash = netDebit(StandardSystemGroups.CURRENT_ASSETS_GROUP_ID) - netDebit(StandardSystemGroups.BANK_GROUP_ID) - netDebit(StandardSystemGroups.CASH_GROUP_ID)
        val currentLiabilities = netCredit(StandardSystemGroups.CURRENT_LIABILITIES_GROUP_ID)
        return currentAssetsExclCash to currentLiabilities
    }

    /**
     * Cash Flow (Phase 7C) - Operating Activities only, via the standard indirect method, sourced
     * entirely from the existing, unmodified [generateProfitAndLoss]/[generateBalanceSheet].
     * Investing/Financing are explicit, documented extension points (see [CashFlowReport]) - never
     * fabricated. [dateRange] must fall within the given financial year.
     */
    suspend fun generateCashFlow(companyId: String, fyId: String, dateRange: ClosedRange<LocalDate>): CashFlowReport {
        val company = dao.getCompanyById(companyId)
        val fy = dao.getFinancialYearById(fyId)
        val fyStart = safeParseDate(fy?.startDate)

        val periodStart = dateRange.start
        val periodEnd = dateRange.endInclusive

        val (openingCurrentAssetsExclCash, openingCurrentLiabilities, openingCashPaise) = if (!periodStart.isAfter(fyStart)) {
            val (assets, liabilities) = openingCurrentAssetsExclCashAndLiabilitiesPaise(companyId)
            Triple(assets, liabilities, 0L)
        } else {
            val openingBalanceSheet = generateBalanceSheet(companyId, fyId, fyStart..periodStart.minusDays(1))
            // BalanceSheetReport.currentAssets/currentLiabilities are RESIDUAL buckets
            // (generateBalanceSheet subtracts Debtors/Bank/Cash/Stock and Duties&Taxes out into
            // their own named fields for display) - "current assets excluding cash/bank" must add
            // Debtors/Stock back in (only Bank/Cash stay excluded); "current liabilities" must add
            // Duties&Taxes back in (only Loans/BranchDiv, which are long-term, stay excluded).
            val assets = openingBalanceSheet.currentAssets.paise + openingBalanceSheet.sundryDebtors.paise + openingBalanceSheet.stockInHand.paise
            val liabilities = openingBalanceSheet.currentLiabilities.paise + openingBalanceSheet.dutiesAndTaxesLiability.paise
            Triple(assets, liabilities, openingBalanceSheet.bankAccounts.paise + openingBalanceSheet.cashInHand.paise)
        }

        val closingBalanceSheet = generateBalanceSheet(companyId, fyId, fyStart..periodEnd)
        val closingCurrentAssetsExclCash = closingBalanceSheet.currentAssets.paise + closingBalanceSheet.sundryDebtors.paise + closingBalanceSheet.stockInHand.paise
        val closingCurrentLiabilities = closingBalanceSheet.currentLiabilities.paise + closingBalanceSheet.dutiesAndTaxesLiability.paise
        val closingCashPaise = closingBalanceSheet.bankAccounts.paise + closingBalanceSheet.cashInHand.paise

        val periodProfitAndLoss = generateProfitAndLoss(companyId, fyId, periodStart..periodEnd)

        val changeInCurrentAssetsExclCash = closingCurrentAssetsExclCash - openingCurrentAssetsExclCash
        val changeInCurrentLiabilities = closingCurrentLiabilities - openingCurrentLiabilities
        val netProfitPaise = periodProfitAndLoss.netProfit.paise
        val cashFromOperatingPaise = netProfitPaise - changeInCurrentAssetsExclCash + changeInCurrentLiabilities

        return CashFlowReport(
            companyName = company?.name ?: "Company",
            financialYearCode = fy?.fyCode ?: "FY 2026-27",
            dateRangeLabel = "$periodStart to $periodEnd",
            netProfit = Money.fromPaise(netProfitPaise),
            changeInCurrentAssetsExcludingCash = Money.fromPaise(changeInCurrentAssetsExclCash),
            changeInCurrentLiabilities = Money.fromPaise(changeInCurrentLiabilities),
            netCashFromOperatingActivities = Money.fromPaise(cashFromOperatingPaise),
            openingCashAndBank = Money.fromPaise(openingCashPaise),
            closingCashAndBank = Money.fromPaise(closingCashPaise),
            netChangeInCashAndBank = Money.fromPaise(closingCashPaise - openingCashPaise)
        )
    }

    /** Ratio Analysis (Phase 7C) - a thin wrapper: fetches the existing, unmodified
     * [generateBalanceSheet]/[generateProfitAndLoss] and delegates to the pure [RatioAnalysisEngine]. */
    suspend fun generateRatioAnalysis(companyId: String, fyId: String, dateRange: ClosedRange<LocalDate>? = null): RatioAnalysisReport {
        val balanceSheet = generateBalanceSheet(companyId, fyId, dateRange)
        val profitAndLoss = generateProfitAndLoss(companyId, fyId, dateRange)
        return RatioAnalysisEngine.compute(balanceSheet, profitAndLoss)
    }

    // ============================================================
    // PHASE 7D - Document Template & Rendering Architecture
    // ============================================================

    private fun DocumentTemplateEntity.toDomain(): DocumentTemplate = DocumentTemplate(
        templateId = templateId, companyId = companyId, documentType = documentType, templateName = templateName,
        version = version, status = status, isDefault = isDefault,
        visualConfig = TemplateConfigSerializer.fromJson(configJson), createdAt = createdAt, updatedAt = updatedAt
    )

    private fun BusinessProfileEntity.toDomain(): BusinessProfile = BusinessProfile(
        businessProfileId = businessProfileId, companyId = companyId, businessName = businessName, legalName = legalName,
        constitutionType = constitutionType, address = address, pinCode = pinCode, city = city, state = state, country = country,
        phone = phone, email = email, website = website,
        gstin = gstin, pan = pan, tan = tan, udyam = udyam,
        logoAssetId = logoAssetId, bankName = bankName, bankAccountNumber = bankAccountNumber, bankIfsc = bankIfsc,
        bankBranch = bankBranch, upiId = upiId, qrCodeAssetId = qrCodeAssetId, signatureAssetId = signatureAssetId,
        termsAndConditions = termsAndConditions, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun IndividualProfileEntity.toDomain(): IndividualProfile = IndividualProfile(
        individualProfileId = individualProfileId, companyId = companyId, name = name, address = address,
        pinCode = pinCode, city = city, state = state, country = country, pan = pan,
        phone = phone, email = email, signatureAssetId = signatureAssetId, termsAndConditions = termsAndConditions,
        createdAt = createdAt, updatedAt = updatedAt
    )

    private fun DocumentAssetEntity.toDomain(): DocumentAsset = DocumentAsset(
        assetId = assetId, companyId = companyId, type = type, storageReference = storageReference,
        checksum = checksum, mimeType = mimeType, sizeBytes = sizeBytes, createdAt = createdAt
    )

    private fun RenderedDocumentRecordEntity.toDomain(): RenderedDocumentRecord = RenderedDocumentRecord(
        recordId = recordId, companyId = companyId, documentId = documentId, documentType = documentType,
        templateId = templateId, templateVersion = templateVersion, format = format,
        storageReference = storageReference, generatedAt = generatedAt
    )

    // ---------------- Document Templates ----------------

    suspend fun createDocumentTemplate(
        companyId: String, documentType: DocumentType, templateName: String,
        visualConfig: TemplateVisualConfig = TemplateVisualConfig(), isDefault: Boolean = false
    ): AccountingResult<DocumentTemplate> {
        if (templateName.isBlank()) return AccountingResult.Failure(AppError.ValidationError("Template name must not be blank."))
        val templateId = "TPL_${UUID.randomUUID().toString().take(8)}_$companyId"
        val now = System.currentTimeMillis()
        if (isDefault) dao.clearDefaultTemplateFlag(companyId, documentType)
        val entity = DocumentTemplateEntity(
            id = "${templateId}_v1", templateId = templateId, companyId = companyId, documentType = documentType,
            templateName = templateName, version = 1, status = TemplateStatus.ACTIVE, isDefault = isDefault,
            configJson = TemplateConfigSerializer.toJson(visualConfig), createdAt = now, updatedAt = now
        )
        dao.insertDocumentTemplate(entity)
        return AccountingResult.Success(entity.toDomain())
    }

    /** Creates a new version, archiving the previous one - never mutates an existing version's
     * row (Section 10: an already-rendered document must stay reproducible under the version it
     * used). [templateName]/[visualConfig] left null keep the prior version's value. */
    suspend fun updateDocumentTemplate(
        companyId: String, templateId: String, templateName: String? = null, visualConfig: TemplateVisualConfig? = null
    ): AccountingResult<DocumentTemplate> {
        val current = dao.getActiveTemplateVersion(companyId, templateId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Template '$templateId' was not found."))
        val nextVersion = (dao.getMaxTemplateVersion(companyId, templateId) ?: current.version) + 1
        dao.setTemplateStatus(companyId, templateId, current.version, TemplateStatus.ARCHIVED)
        val now = System.currentTimeMillis()
        val updated = current.copy(
            id = "${templateId}_v$nextVersion", version = nextVersion, status = TemplateStatus.ACTIVE,
            templateName = templateName ?: current.templateName,
            configJson = visualConfig?.let { TemplateConfigSerializer.toJson(it) } ?: current.configJson,
            updatedAt = now
        )
        dao.insertDocumentTemplate(updated)
        return AccountingResult.Success(updated.toDomain())
    }

    suspend fun setDefaultDocumentTemplate(companyId: String, documentType: DocumentType, templateId: String): AccountingResult<DocumentTemplate> {
        val current = dao.getActiveTemplateVersion(companyId, templateId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Template '$templateId' was not found."))
        dao.clearDefaultTemplateFlag(companyId, documentType)
        val updated = current.copy(isDefault = true, updatedAt = System.currentTimeMillis())
        dao.insertDocumentTemplate(updated)
        return AccountingResult.Success(updated.toDomain())
    }

    fun getDocumentTemplatesByType(companyId: String, documentType: DocumentType): Flow<List<DocumentTemplate>> =
        dao.getActiveTemplatesByType(companyId, documentType).map { list -> list.map { it.toDomain() } }

    suspend fun getDocumentTemplateVersion(companyId: String, templateId: String, version: Int): DocumentTemplate? =
        dao.getTemplateVersion(companyId, templateId, version)?.toDomain()

    /** Resolution order: an explicitly-picked template's current version, else the company's
     * configured default for [documentType], else [DocumentTemplate.builtinDefault] - a document
     * is always renderable, never blocked on template setup. */
    suspend fun resolveTemplateForRender(companyId: String, documentType: DocumentType, templateId: String? = null): DocumentTemplate {
        val explicit = templateId?.let { dao.getActiveTemplateVersion(companyId, it) }
        if (explicit != null) return explicit.toDomain()
        val default = dao.getDefaultTemplate(companyId, documentType)
        return default?.toDomain() ?: DocumentTemplate.builtinDefault(companyId, documentType)
    }

    // ---------------- Business / Individual Profiles ----------------

    suspend fun getBusinessProfile(companyId: String): BusinessProfile? = dao.getBusinessProfile(companyId)?.toDomain()

    suspend fun upsertBusinessProfile(profile: BusinessProfile): AccountingResult<BusinessProfile> {
        if (profile.businessName.isBlank()) return AccountingResult.Failure(AppError.ValidationError("Business name must not be blank."))
        val existing = dao.getBusinessProfile(profile.companyId)
        val now = System.currentTimeMillis()
        val entity = BusinessProfileEntity(
            businessProfileId = existing?.businessProfileId ?: profile.businessProfileId.ifBlank { "BIZ_${UUID.randomUUID().toString().take(8)}_${profile.companyId}" },
            companyId = profile.companyId, businessName = profile.businessName, legalName = profile.legalName,
            constitutionType = profile.constitutionType, address = profile.address,
            pinCode = profile.pinCode, city = profile.city, state = profile.state, country = profile.country,
            phone = profile.phone, email = profile.email,
            website = profile.website, gstin = profile.gstin, pan = profile.pan, tan = profile.tan, udyam = profile.udyam,
            logoAssetId = profile.logoAssetId,
            bankName = profile.bankName, bankAccountNumber = profile.bankAccountNumber, bankIfsc = profile.bankIfsc,
            bankBranch = profile.bankBranch, upiId = profile.upiId, qrCodeAssetId = profile.qrCodeAssetId,
            signatureAssetId = profile.signatureAssetId, termsAndConditions = profile.termsAndConditions,
            createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        if (existing != null) {
            dao.updateBusinessProfile(
                companyId = entity.companyId, businessProfileId = entity.businessProfileId, businessName = entity.businessName,
                legalName = entity.legalName, constitutionType = entity.constitutionType, address = entity.address,
                pinCode = entity.pinCode, city = entity.city, state = entity.state, country = entity.country,
                phone = entity.phone, email = entity.email, website = entity.website, gstin = entity.gstin, pan = entity.pan,
                tan = entity.tan, udyam = entity.udyam, logoAssetId = entity.logoAssetId,
                bankName = entity.bankName, bankAccountNumber = entity.bankAccountNumber, bankIfsc = entity.bankIfsc,
                bankBranch = entity.bankBranch, upiId = entity.upiId, qrCodeAssetId = entity.qrCodeAssetId,
                signatureAssetId = entity.signatureAssetId, termsAndConditions = entity.termsAndConditions, updatedAt = entity.updatedAt
            )
        } else {
            dao.insertBusinessProfile(entity)
        }
        return AccountingResult.Success(entity.toDomain())
    }

    suspend fun getIndividualProfile(companyId: String): IndividualProfile? = dao.getIndividualProfile(companyId)?.toDomain()

    suspend fun upsertIndividualProfile(profile: IndividualProfile): AccountingResult<IndividualProfile> {
        if (profile.name.isBlank()) return AccountingResult.Failure(AppError.ValidationError("Individual name must not be blank."))
        val existing = dao.getIndividualProfile(profile.companyId)
        val now = System.currentTimeMillis()
        val entity = IndividualProfileEntity(
            individualProfileId = existing?.individualProfileId ?: profile.individualProfileId.ifBlank { "IND_${UUID.randomUUID().toString().take(8)}_${profile.companyId}" },
            companyId = profile.companyId, name = profile.name, address = profile.address,
            pinCode = profile.pinCode, city = profile.city, state = profile.state, country = profile.country, pan = profile.pan,
            phone = profile.phone, email = profile.email, signatureAssetId = profile.signatureAssetId,
            termsAndConditions = profile.termsAndConditions, createdAt = existing?.createdAt ?: now, updatedAt = now
        )
        if (existing != null) {
            dao.updateIndividualProfile(
                companyId = entity.companyId, individualProfileId = entity.individualProfileId, name = entity.name,
                address = entity.address, pinCode = entity.pinCode, city = entity.city, state = entity.state, country = entity.country,
                pan = entity.pan, phone = entity.phone, email = entity.email,
                signatureAssetId = entity.signatureAssetId, termsAndConditions = entity.termsAndConditions, updatedAt = entity.updatedAt
            )
        } else {
            dao.insertIndividualProfile(entity)
        }
        return AccountingResult.Success(entity.toDomain())
    }

    // ---------------- Document Assets ----------------

    suspend fun createDocumentAsset(
        companyId: String, type: DocumentAssetType, storageReference: String, checksum: String, mimeType: String, sizeBytes: Long
    ): AccountingResult<DocumentAsset> {
        if (storageReference.isBlank()) return AccountingResult.Failure(AppError.ValidationError("Asset storage reference must not be blank."))
        val entity = DocumentAssetEntity(
            assetId = "AST_${UUID.randomUUID().toString().take(8)}_$companyId", companyId = companyId, type = type,
            storageReference = storageReference, checksum = checksum, mimeType = mimeType, sizeBytes = sizeBytes,
            createdAt = System.currentTimeMillis()
        )
        dao.insertDocumentAsset(entity)
        return AccountingResult.Success(entity.toDomain())
    }

    fun getDocumentAssetsByCompany(companyId: String): Flow<List<DocumentAsset>> =
        dao.getDocumentAssetsByCompany(companyId).map { list -> list.map { it.toDomain() } }

    suspend fun getDocumentAsset(companyId: String, assetId: String): DocumentAsset? =
        dao.getDocumentAssetById(companyId, assetId)?.toDomain()

    /** Rollback-only (Phase 7J-B.2 Slice 2) - see [com.example.accounting.data.local.dao.AccountingDao.deleteDocumentAsset]'s
     * doc comment. Never a general asset-deletion entry point. */
    suspend fun deleteDocumentAsset(companyId: String, assetId: String): Int = dao.deleteDocumentAsset(companyId, assetId)

    // ---------------- Rendered Document Records (Section 10/21 reproducibility log) ----------------

    suspend fun logDocumentRender(
        companyId: String, documentId: String, documentType: DocumentType, template: DocumentTemplate,
        format: String, storageReference: String? = null
    ): RenderedDocumentRecord {
        val entity = RenderedDocumentRecordEntity(
            recordId = "RDR_${UUID.randomUUID()}", companyId = companyId, documentId = documentId, documentType = documentType,
            templateId = template.templateId, templateVersion = template.version, format = format,
            storageReference = storageReference, generatedAt = System.currentTimeMillis()
        )
        dao.insertRenderedDocumentRecord(entity)
        return entity.toDomain()
    }

    fun getRenderedDocumentRecords(companyId: String, documentId: String): Flow<List<RenderedDocumentRecord>> =
        dao.getRenderedDocumentRecords(companyId, documentId).map { list -> list.map { it.toDomain() } }

    // ---------------- Document Data Assembly ----------------

    /** Sale-direction document types render the company as seller and the Party as buyer;
     * purchase-direction types render the reverse. Presentation-only - never affects which ledger
     * a posting actually debits/credits (that's `TradingWorkflowEngine`'s frozen classification). */
    private fun isSalesDirection(documentType: DocumentType): Boolean = when (documentType) {
        DocumentType.SALES_INVOICE, DocumentType.CREDIT_NOTE, DocumentType.QUOTATION,
        DocumentType.PROFORMA_INVOICE, DocumentType.SALES_ORDER, DocumentType.DELIVERY_NOTE -> true
        DocumentType.PURCHASE_BILL, DocumentType.DEBIT_NOTE, DocumentType.PURCHASE_ORDER,
        DocumentType.RECEIPT_NOTE -> false
    }

    private suspend fun partySnapshot(companyId: String, partyId: String): DocumentPartySnapshot? {
        val party = dao.getPartyById(companyId, partyId) ?: return null
        val ledger = dao.getLedgerById(companyId, party.ledgerId)
        return DocumentPartySnapshot(
            name = party.displayName, address = ledger?.address.orEmpty(), gstin = ledger?.gstin.orEmpty(),
            pan = ledger?.pan.orEmpty(), phone = ledger?.phone.orEmpty(), email = ledger?.email.orEmpty()
        )
    }

    private suspend fun sellerSnapshot(companyId: String, company: CompanyEntity): DocumentPartySnapshot {
        val profile = dao.getBusinessProfile(companyId)
        return DocumentPartySnapshot(
            name = profile?.businessName?.ifBlank { company.name } ?: company.name,
            address = profile?.address?.ifBlank { company.address } ?: company.address,
            gstin = profile?.gstin?.ifBlank { company.gstin } ?: company.gstin,
            pan = profile?.pan?.ifBlank { company.pan } ?: company.pan,
            phone = profile?.phone?.ifBlank { company.phone } ?: company.phone,
            email = profile?.email?.ifBlank { company.email } ?: company.email
        )
    }

    private suspend fun brandingSnapshot(companyId: String): DocumentBrandingSnapshot {
        val profile = dao.getBusinessProfile(companyId) ?: return DocumentBrandingSnapshot()
        return DocumentBrandingSnapshot(
            logoStorageReference = profile.logoAssetId?.let { dao.getDocumentAssetById(companyId, it)?.storageReference },
            signatureStorageReference = profile.signatureAssetId?.let { dao.getDocumentAssetById(companyId, it)?.storageReference },
            qrCodeStorageReference = profile.qrCodeAssetId?.let { dao.getDocumentAssetById(companyId, it)?.storageReference }
        )
    }

    private suspend fun paymentInfoSnapshot(companyId: String): DocumentPaymentInfo {
        val profile = dao.getBusinessProfile(companyId) ?: return DocumentPaymentInfo()
        return DocumentPaymentInfo(profile.bankName, profile.bankAccountNumber, profile.bankIfsc, profile.bankBranch, profile.upiId)
    }

    /** Calls the existing, unmodified [GstCalculationEngine.calculateDetailed] - never
     * reimplements GST math. Used only when no [GstTransactionEntity] exists yet (a draft
     * Invoice, or any non-posting TradeDocument, neither of which is ever posted). */
    private fun computeLineTax(
        taxableAmount: Money, gstRatePercent: Double, cessRatePercent: Double, companyStateCode: String, partyStateCode: String
    ) = GstCalculationEngine.calculateDetailed(
        GstTransactionFacts(
            taxableAmount = taxableAmount, gstRatePercent = gstRatePercent, cessRatePercent = cessRatePercent,
            supplierStateCode = companyStateCode, placeOfSupply = partyStateCode.ifBlank { companyStateCode },
            supplyNature = GstSupplyNature.NORMAL
        )
    )

    /** `InvoiceLineEntity`/`TradeDocumentLineEntity` have no explicit "quantity not applicable"
     * flag yet (a genuine domain gap flagged by the Phase 7D structural audit) - a stored
     * `quantityRaw == 0` is read as "no quantity" (a service line), never a fabricated zero
     * quantity for a real inventory line. See `docs/42_DOCUMENT_TEMPLATE_ARCHITECTURE.md`. */
    private fun quantityOrNull(quantityRaw: Long): Quantity? = if (quantityRaw == 0L) null else Quantity(quantityRaw)

    private suspend fun referenceInfoForInvoice(companyId: String, invoice: InvoiceEntity): DocumentReferenceInfo {
        invoice.referenceInvoiceId?.let { refId ->
            val ref = dao.getInvoiceById(companyId, refId)
            return DocumentReferenceInfo(ref?.invoiceId, ref?.invoiceNumber, ref?.date?.let { safeParseDate(it) })
        }
        invoice.sourceTradeDocumentId?.let { refId ->
            val ref = dao.getTradeDocumentById(companyId, refId)
            return DocumentReferenceInfo(ref?.tradeDocumentId, ref?.documentNumber, ref?.date?.let { safeParseDate(it) })
        }
        return DocumentReferenceInfo()
    }

    private suspend fun referenceInfoForTradeDocument(companyId: String, document: TradeDocumentEntity): DocumentReferenceInfo {
        val refId = document.sourceTradeDocumentId ?: return DocumentReferenceInfo()
        val ref = dao.getTradeDocumentById(companyId, refId)
        return DocumentReferenceInfo(ref?.tradeDocumentId, ref?.documentNumber, ref?.date?.let { safeParseDate(it) })
    }

    /**
     * Assembles a fully-computed, renderer-ready [DocumentData] for one Invoice (posting document)
     * or TradeDocument (non-posting) - the ONLY function that reads Invoice/TradeDocument/
     * GstTransaction/Party/Ledger/Company/BusinessProfile data for rendering purposes. For a
     * **posted** Invoice, every tax/total figure comes straight from the already-persisted
     * `GstTransaction`/`Voucher` rows. For a **draft** Invoice or any TradeDocument (neither ever
     * has a `GstTransaction`), it calls the existing, unmodified [GstCalculationEngine] per line -
     * it never reimplements GST math itself. Completely read-only: no DAO write happens anywhere
     * in this function.
     */
    suspend fun assembleDocumentData(companyId: String, documentType: DocumentType, documentId: String): AccountingResult<DocumentData> {
        val company = dao.getCompanyById(companyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Company '$companyId' was not found."))
        val businessProfile = dao.getBusinessProfile(companyId)

        if (documentType.isPostingDocument) {
            val invoice = dao.getInvoiceById(companyId, documentId)
                ?: return AccountingResult.Failure(AppError.ValidationError("Invoice '$documentId' was not found."))
            val lines = dao.getLinesForInvoice(invoice.invoiceId)
            val buyer = partySnapshot(companyId, invoice.partyId)
                ?: return AccountingResult.Failure(AppError.ValidationError("Party '${invoice.partyId}' was not found."))
            val partyLedger = dao.getPartyById(companyId, invoice.partyId)?.let { dao.getLedgerById(companyId, it.ledgerId) }
            val partyStateCode = partyLedger?.stateCode.orEmpty()

            val voucher = invoice.voucherId?.let { dao.getVoucherById(companyId, it) }
            val gstByLineOrder = voucher?.let { v -> dao.getGstTransactionsForVoucher(v.voucherId).associateBy { it.lineOrder } } ?: emptyMap()

            val itemLines = lines.map { line ->
                val gst = gstByLineOrder[line.lineOrder]
                if (gst != null) {
                    DocumentLineData(
                        itemId = line.itemId, description = line.itemName, hsnSacCode = line.hsnSacCode,
                        quantity = quantityOrNull(line.quantityRaw), unit = "", rate = Money.fromPaise(line.ratePaise),
                        taxableAmount = Money.fromPaise(gst.taxableAmountPaise), gstRatePercent = gst.gstRatePercent,
                        cgst = Money.fromPaise(gst.cgstPaise), sgst = Money.fromPaise(gst.sgstPaise),
                        igst = Money.fromPaise(gst.igstPaise), cess = Money.fromPaise(gst.cessPaise),
                        lineTotal = Money.fromPaise(gst.taxableAmountPaise + gst.cgstPaise + gst.sgstPaise + gst.igstPaise + gst.cessPaise)
                    )
                } else {
                    val quantity = quantityOrNull(line.quantityRaw)
                    val taxable = if (quantity != null) Money.fromPaise(line.ratePaise) * (line.quantityRaw / 1000.0) else Money.fromPaise(line.ratePaise)
                    val tax = computeLineTax(taxable, line.gstRatePercent, line.cessRatePercent, company.stateCode, partyStateCode)
                    DocumentLineData(
                        itemId = line.itemId, description = line.itemName, hsnSacCode = line.hsnSacCode,
                        quantity = quantity, unit = "", rate = Money.fromPaise(line.ratePaise), taxableAmount = taxable,
                        gstRatePercent = line.gstRatePercent, cgst = tax.cgstAmount, sgst = tax.sgstAmount, igst = tax.igstAmount,
                        cess = tax.cessAmount,
                        lineTotal = taxable + tax.totalTax
                    )
                }
            }

            val sumTaxable = Money.fromPaise(itemLines.sumOf { it.taxableAmount.paise })
            val sumCgst = Money.fromPaise(itemLines.sumOf { it.cgst.paise })
            val sumSgst = Money.fromPaise(itemLines.sumOf { it.sgst.paise })
            val sumIgst = Money.fromPaise(itemLines.sumOf { it.igst.paise })
            val sumCess = Money.fromPaise(itemLines.sumOf { it.cess.paise })
            val sumLineTotals = itemLines.sumOf { it.lineTotal.paise }
            val grandTotal = voucher?.totalAmountPaise ?: sumLineTotals
            val totals = DocumentTotals(
                taxableAmount = sumTaxable, cgst = sumCgst, sgst = sumSgst, igst = sumIgst, cess = sumCess,
                roundOff = Money.fromPaise(grandTotal - sumLineTotals), grandTotal = Money.fromPaise(grandTotal)
            )

            return AccountingResult.Success(
                DocumentData(
                    documentId = invoice.invoiceId, companyId = companyId, documentType = documentType,
                    documentNumber = invoice.invoiceNumber ?: invoice.invoiceId, documentDate = safeParseDate(invoice.date),
                    dueDate = invoice.dueDate?.let { safeParseDate(it) },
                    seller = if (isSalesDirection(documentType)) sellerSnapshot(companyId, company) else buyer,
                    buyer = if (isSalesDirection(documentType)) buyer else sellerSnapshot(companyId, company),
                    items = itemLines, totals = totals, paymentInformation = paymentInfoSnapshot(companyId),
                    references = referenceInfoForInvoice(companyId, invoice),
                    terms = businessProfile?.termsAndConditions.orEmpty(), branding = brandingSnapshot(companyId),
                    isPosted = voucher != null, accountingVoucherNumber = voucher?.voucherNumber
                )
            )
        }

        val document = dao.getTradeDocumentById(companyId, documentId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Document '$documentId' was not found."))
        val lines = dao.getLinesForTradeDocument(document.tradeDocumentId)
        val buyer = partySnapshot(companyId, document.partyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Party '${document.partyId}' was not found."))
        val partyLedger = dao.getPartyById(companyId, document.partyId)?.let { dao.getLedgerById(companyId, it.ledgerId) }
        val partyStateCode = partyLedger?.stateCode.orEmpty()

        val itemLines = lines.map { line ->
            val quantity = quantityOrNull(line.quantityRaw)
            val taxable = if (quantity != null) Money.fromPaise(line.ratePaise) * (line.quantityRaw / 1000.0) else Money.fromPaise(line.ratePaise)
            val tax = computeLineTax(taxable, line.gstRatePercent, line.cessRatePercent, company.stateCode, partyStateCode)
            DocumentLineData(
                itemId = line.itemId, description = line.itemName, hsnSacCode = line.hsnSacCode,
                quantity = quantity, unit = "", rate = Money.fromPaise(line.ratePaise), taxableAmount = taxable,
                gstRatePercent = line.gstRatePercent, cgst = tax.cgstAmount, sgst = tax.sgstAmount, igst = tax.igstAmount,
                cess = tax.cessAmount, lineTotal = taxable + tax.totalTax
            )
        }
        val totals = DocumentTotals(
            taxableAmount = Money.fromPaise(itemLines.sumOf { it.taxableAmount.paise }),
            cgst = Money.fromPaise(itemLines.sumOf { it.cgst.paise }), sgst = Money.fromPaise(itemLines.sumOf { it.sgst.paise }),
            igst = Money.fromPaise(itemLines.sumOf { it.igst.paise }), cess = Money.fromPaise(itemLines.sumOf { it.cess.paise }),
            roundOff = Money.ZERO, grandTotal = Money.fromPaise(itemLines.sumOf { it.lineTotal.paise })
        )

        return AccountingResult.Success(
            DocumentData(
                documentId = document.tradeDocumentId, companyId = companyId, documentType = documentType,
                documentNumber = document.documentNumber, documentDate = safeParseDate(document.date), dueDate = null,
                seller = if (isSalesDirection(documentType)) sellerSnapshot(companyId, company) else buyer,
                buyer = if (isSalesDirection(documentType)) buyer else sellerSnapshot(companyId, company),
                items = itemLines, totals = totals, paymentInformation = paymentInfoSnapshot(companyId),
                references = referenceInfoForTradeDocument(companyId, document),
                terms = businessProfile?.termsAndConditions.orEmpty(), branding = brandingSnapshot(companyId),
                isPosted = false, accountingVoucherNumber = null
            )
        )
    }

    // ---------------- Rendering entry points ----------------

    /** Assembles [DocumentData], resolves the template (explicit or default), renders to JSON via
     * the pure [com.example.accounting.domain.rendering.JsonDocumentRenderer], and logs the render
     * (Section 10/21 reproducibility). Read-only except for the append-only render-log row. */
    suspend fun renderDocumentAsJson(
        companyId: String, documentType: DocumentType, documentId: String, templateId: String? = null
    ): AccountingResult<String> {
        val dataResult = assembleDocumentData(companyId, documentType, documentId)
        if (dataResult is AccountingResult.Failure) return dataResult
        val data = (dataResult as AccountingResult.Success).data
        val template = resolveTemplateForRender(companyId, documentType, templateId)
        val json = JsonDocumentRenderer.render(data, template)
        logDocumentRender(companyId, documentId, documentType, template, "JSON")
        return AccountingResult.Success(json)
    }

    // ============================================================
    // PHASE 7E - Export Architecture & Data Interchange
    //
    // Every function below is READ -> MAP -> SERIALIZE (Section 23): it reads already-authoritative
    // data (via an existing DAO/report-generation call), maps it to a distinct Export DTO
    // (`domain.export.*`, never a Room entity or a raw report model reference), and serializes.
    // None of these functions perform accounting/GST/report calculation themselves.
    // ============================================================

    private fun buildMetadata(companyId: String, exportType: ExportType, financialYearId: String? = null): ExportMetadata =
        ExportMetadata(exportType = exportType, companyId = companyId, financialYearId = financialYearId)

    private fun formatPaymentTerms(terms: PaymentTerms): String =
        if (terms.type == com.example.accounting.domain.party.PaymentTermsType.CUSTOM) "CUSTOM:${terms.customDays ?: 0}" else terms.type.name

    /** Rejects an `(exportType, format)` combination [ExportFormatSupport] doesn't allow (e.g.
     * GSTR_JSON for anything but GST transactions) with the same structured error every other
     * export function uses - never a silently-wrong serialization. */
    private fun requireSupportedFormat(exportType: ExportType, format: ExportFormat): AccountingResult<Unit> =
        if (ExportFormatSupport.supports(exportType, format)) AccountingResult.Success(Unit)
        else AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, exportType.name))

    // ---------------- Voucher export ----------------

    suspend fun exportVoucher(companyId: String, voucherId: String): AccountingResult<VoucherExportDto> {
        val voucher = dao.getVoucherById(companyId, voucherId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Voucher", voucherId))
        val lines = dao.getJournalItemsForVoucherSync(voucherId)
        val ledgerNames = dao.getLedgersByCompany(companyId).first().associate { it.ledgerId to it.name }
        return AccountingResult.Success(
            VoucherExportDto(
                voucherId = voucher.voucherId, voucherNumber = voucher.voucherNumber, voucherType = voucher.voucherType,
                date = safeParseDate(voucher.date), referenceNumber = voucher.referenceNumber, narration = voucher.narration,
                totalAmountPaise = voucher.totalAmountPaise, isPosted = voucher.isPosted, isCancelled = voucher.isCancelled,
                referenceVoucherId = voucher.referenceVoucherId,
                journalLines = lines.sortedBy { it.lineOrder }.map {
                    JournalLineExportDto(it.ledgerId, ledgerNames[it.ledgerId].orEmpty(), it.type, it.amountPaise, it.narration, it.lineOrder)
                }
            )
        )
    }

    // ---------------- Party export ----------------

    suspend fun exportParty(companyId: String, partyId: String): AccountingResult<PartyExportDto> {
        val party = dao.getPartyById(companyId, partyId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Party", partyId))
        return AccountingResult.Success(
            PartyExportDto(
                partyId = party.partyId, ledgerId = party.ledgerId, role = party.role.name, entityType = party.entityType.name,
                displayName = party.displayName, contactName = party.contactName, creditLimitPaise = party.creditLimitPaise,
                paymentTerms = formatPaymentTerms(PaymentTerms(party.paymentTermsType, party.paymentTermsCustomDays)),
                isActive = party.isActive
            )
        )
    }

    // ---------------- Ledger export ----------------

    suspend fun exportLedger(companyId: String, ledgerId: String): AccountingResult<LedgerExportDto> {
        val ledger = dao.getLedgerById(companyId, ledgerId)
            ?: return AccountingResult.Failure(AppError.ResourceNotFound("Ledger", ledgerId))
        return AccountingResult.Success(
            LedgerExportDto(
                ledgerId = ledger.ledgerId, groupId = ledger.groupId, name = ledger.name, code = ledger.code,
                openingBalancePaise = ledger.openingBalancePaise, openingBalanceType = ledger.openingBalanceType,
                currentBalancePaise = ledger.currentBalancePaise, currentBalanceType = ledger.currentBalanceType,
                gstin = ledger.gstin, pan = ledger.pan, stateCode = ledger.stateCode, address = ledger.address,
                isSystem = ledger.isSystem, isActive = ledger.isActive
            )
        )
    }

    // ---------------- Invoice/document export (thin wrapper over Phase 7D's DocumentData) ----------------

    suspend fun exportInvoice(companyId: String, documentType: DocumentType, documentId: String): AccountingResult<InvoiceExportDto> {
        val dataResult = assembleDocumentData(companyId, documentType, documentId)
        if (dataResult is AccountingResult.Failure) return dataResult
        val data = (dataResult as AccountingResult.Success).data
        return AccountingResult.Success(
            InvoiceExportDto(
                documentId = data.documentId, documentType = data.documentType.name, documentNumber = data.documentNumber,
                documentDate = data.documentDate, dueDate = data.dueDate, sellerName = data.seller.name, buyerName = data.buyer.name,
                buyerGstin = data.buyer.gstin, lineCount = data.items.size, taxableAmountPaise = data.totals.taxableAmount.paise,
                cgstPaise = data.totals.cgst.paise, sgstPaise = data.totals.sgst.paise, igstPaise = data.totals.igst.paise,
                cessPaise = data.totals.cess.paise, roundOffPaise = data.totals.roundOff.paise, grandTotalPaise = data.totals.grandTotal.paise,
                isPosted = data.isPosted, accountingVoucherNumber = data.accountingVoucherNumber
            )
        )
    }

    // ---------------- Report exports (thin mappers over the existing, unmodified generate* functions) ----------------

    fun TrialBalanceReport.toExportDto(): TrialBalanceExportDto = TrialBalanceExportDto(
        companyName = companyName, financialYearCode = financialYearCode, asOfDate = asOfDate,
        rows = rows.map {
            TrialBalanceRowExportDto(
                it.ledgerId, it.ledgerName, it.groupId, it.groupName, it.primaryGroup,
                it.openingDebit.paise, it.openingCredit.paise, it.transactionDebit.paise, it.transactionCredit.paise,
                it.closingDebit.paise, it.closingCredit.paise
            )
        },
        totalClosingDebitPaise = totalClosingDebit.paise, totalClosingCreditPaise = totalClosingCredit.paise, isBalanced = isBalanced
    )

    fun ProfitAndLossReport.toExportDto(): ProfitAndLossExportDto = ProfitAndLossExportDto(
        companyName = companyName, financialYearCode = financialYearCode, dateRange = dateRange,
        salesRevenuePaise = salesRevenue.paise, directIncomesPaise = directIncomes.paise, purchasesPaise = purchases.paise,
        directExpensesPaise = directExpenses.paise, grossProfitPaise = grossProfit.paise, indirectIncomesPaise = indirectIncomes.paise,
        indirectExpensesPaise = indirectExpenses.paise, netProfitPaise = netProfit.paise, isInventoryAware = isInventoryAware
    )

    fun BalanceSheetReport.toExportDto(): BalanceSheetExportDto = BalanceSheetExportDto(
        companyName = companyName, financialYearCode = financialYearCode, asOfDate = asOfDate,
        totalLiabilitiesPaise = totalLiabilities.paise, totalAssetsPaise = totalAssets.paise, isBalanced = isBalanced,
        capitalAccountsPaise = capitalAccounts.paise, loansLiabilitiesPaise = loansLiabilities.paise,
        currentLiabilitiesPaise = currentLiabilities.paise, fixedAssetsPaise = fixedAssets.paise,
        currentAssetsPaise = currentAssets.paise, sundryDebtorsPaise = sundryDebtors.paise,
        bankAccountsPaise = bankAccounts.paise, cashInHandPaise = cashInHand.paise, stockInHandPaise = stockInHand.paise
    )

    fun OutstandingReport.toExportDto(): OutstandingExportDto = OutstandingExportDto(
        rows = rows.map {
            OutstandingRowExportDto(
                it.invoiceId, it.invoiceNumber, it.invoiceType, it.partyId, it.partyName, it.voucherNumber,
                it.date, it.dueDate, it.totalAmount.paise, it.outstandingAmount.paise, it.status, it.daysOutstanding, it.agingBucket
            )
        },
        totalOutstandingPaise = totalOutstanding.paise
    )

    fun GSTSummaryReport.toExportDto(): GSTSummaryExportDto = GSTSummaryExportDto(
        companyName = companyName, gstin = gstin, period = period,
        totalTaxableOutwardPaise = totalTaxableOutward.paise, totalTaxOutwardPaise = totalTaxOutward.paise,
        totalTaxableInwardPaise = totalTaxableInward.paise, totalTaxInwardItcPaise = totalTaxInwardITC.paise,
        netTaxPayablePaise = netTaxPayable.paise, totalCessPaise = totalCess.paise, netCessPayablePaise = netCessPayable.paise
    )

    // ---------------- GST transaction export / GSTR JSON ----------------

    suspend fun exportGstTransactions(companyId: String, fyId: String): List<GSTTransactionExportDto> =
        dao.getGstTransactionsForCompanyFY(companyId, fyId).map {
            GSTTransactionExportDto(
                gstTransactionId = it.gstTransactionId, voucherId = it.voucherId, voucherType = it.voucherType,
                partyGstin = it.partyGstin, placeOfSupply = it.placeOfSupply, supplyType = it.supplyType.name,
                hsnSacCode = it.hsnSacCode, isService = null, taxableAmountPaise = it.taxableAmountPaise,
                gstRatePercent = it.gstRatePercent, cgstPaise = it.cgstPaise, sgstPaise = it.sgstPaise, igstPaise = it.igstPaise,
                cessPaise = it.cessPaise, direction = it.direction.name, lineOrder = it.lineOrder
            )
        }

    // ---------------- Format-dispatching entry points ----------------

    suspend fun exportVoucherAs(companyId: String, voucherId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.VOUCHER, format)
        if (supported is AccountingResult.Failure) return supported
        val dtoResult = exportVoucher(companyId, voucherId)
        if (dtoResult is AccountingResult.Failure) return dtoResult
        val dto = (dtoResult as AccountingResult.Success).data
        val metadata = buildMetadata(companyId, ExportType.VOUCHER)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(dto.toCsvHeaders(), dto.toCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.VOUCHER.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportPartyAs(companyId: String, partyId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.PARTY, format)
        if (supported is AccountingResult.Failure) return supported
        val dtoResult = exportParty(companyId, partyId)
        if (dtoResult is AccountingResult.Failure) return dtoResult
        val dto = (dtoResult as AccountingResult.Success).data
        val metadata = buildMetadata(companyId, ExportType.PARTY)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(listOf(dto).toPartyCsvHeaders(), listOf(dto).toPartyCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.PARTY.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportLedgerAs(companyId: String, ledgerId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.LEDGER, format)
        if (supported is AccountingResult.Failure) return supported
        val dtoResult = exportLedger(companyId, ledgerId)
        if (dtoResult is AccountingResult.Failure) return dtoResult
        val dto = (dtoResult as AccountingResult.Success).data
        val metadata = buildMetadata(companyId, ExportType.LEDGER)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(listOf(dto).toLedgerCsvHeaders(), listOf(dto).toLedgerCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.LEDGER.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportInvoiceAs(companyId: String, documentType: DocumentType, documentId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.INVOICE, format)
        if (supported is AccountingResult.Failure) return supported
        val dtoResult = exportInvoice(companyId, documentType, documentId)
        if (dtoResult is AccountingResult.Failure) return dtoResult
        val dto = (dtoResult as AccountingResult.Success).data
        val metadata = buildMetadata(companyId, ExportType.INVOICE)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.INVOICE.name))
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.INVOICE.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportTrialBalanceAs(companyId: String, fyId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.TRIAL_BALANCE, format)
        if (supported is AccountingResult.Failure) return supported
        val dto = generateTrialBalance(companyId, fyId).toExportDto()
        val metadata = buildMetadata(companyId, ExportType.TRIAL_BALANCE, fyId)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(dto.toCsvHeaders(), dto.toCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.TRIAL_BALANCE.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportProfitAndLossAs(companyId: String, fyId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.PROFIT_AND_LOSS, format)
        if (supported is AccountingResult.Failure) return supported
        val dto = generateProfitAndLoss(companyId, fyId).toExportDto()
        val metadata = buildMetadata(companyId, ExportType.PROFIT_AND_LOSS, fyId)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(dto.toCsvHeaders(), dto.toCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.PROFIT_AND_LOSS.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportBalanceSheetAs(companyId: String, fyId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.BALANCE_SHEET, format)
        if (supported is AccountingResult.Failure) return supported
        val dto = generateBalanceSheet(companyId, fyId).toExportDto()
        val metadata = buildMetadata(companyId, ExportType.BALANCE_SHEET, fyId)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(dto.toCsvHeaders(), dto.toCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.BALANCE_SHEET.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportOutstandingAs(companyId: String, format: ExportFormat, today: LocalDate = LocalDate.now()): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.OUTSTANDING, format)
        if (supported is AccountingResult.Failure) return supported
        val dto = generateOutstandingReport(companyId, today = today).toExportDto()
        val metadata = buildMetadata(companyId, ExportType.OUTSTANDING)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(dto.toCsvHeaders(), dto.toCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.OUTSTANDING.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportGstSummaryAs(companyId: String, fyId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.GST_SUMMARY, format)
        if (supported is AccountingResult.Failure) return supported
        val dto = generateGSTSummary(companyId, fyId).toExportDto()
        val metadata = buildMetadata(companyId, ExportType.GST_SUMMARY, fyId)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dto.toTree())
            ExportFormat.CSV -> CsvEngine.write(dto.toCsvHeaders(), dto.toCsvRows())
            ExportFormat.GSTR_JSON -> return AccountingResult.Failure(AppError.ExportFormatUnsupported(format.name, ExportType.GST_SUMMARY.name))
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }

    suspend fun exportGstTransactionsAs(companyId: String, fyId: String, format: ExportFormat): AccountingResult<ExportResult> {
        val supported = requireSupportedFormat(ExportType.GST_TRANSACTIONS, format)
        if (supported is AccountingResult.Failure) return supported
        val dtos = exportGstTransactions(companyId, fyId)
        val metadata = buildMetadata(companyId, ExportType.GST_TRANSACTIONS, fyId)
        val content = when (format) {
            ExportFormat.JSON -> ExportJsonSerializer.serialize(metadata, dtos.toTree())
            ExportFormat.CSV -> CsvEngine.write(dtos.toGstTransactionCsvHeaders(), dtos.toGstTransactionCsvRows())
            ExportFormat.GSTR_JSON -> GstrJsonSerializer.serialize(metadata, dtos)
        }
        return AccountingResult.Success(ExportResult(metadata, format, content))
    }
}
