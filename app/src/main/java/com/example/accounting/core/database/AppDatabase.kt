package com.example.accounting.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.accounting.core.common.Constants
import com.example.accounting.core.database.converters.RoomConverters
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.AuditLogEntity
import com.example.accounting.data.local.entity.BankUpiProfileEntity
import com.example.accounting.data.local.entity.BranchEntity
import com.example.accounting.data.local.entity.BusinessProfileEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.CompanySubscriptionEntity
import com.example.accounting.data.local.entity.DocumentAssetEntity
import com.example.accounting.data.local.entity.DocumentTemplateEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.GstFilingPeriodEntity
import com.example.accounting.data.local.entity.GstReturnArtifactEntity
import com.example.accounting.data.local.entity.GstReturnEntity
import com.example.accounting.data.local.entity.GstReturnSectionEntity
import com.example.accounting.data.local.entity.GstReturnSubmissionEntity
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
import com.example.accounting.data.local.entity.StockMovementEntity
import com.example.accounting.data.local.entity.TradeDocumentEntity
import com.example.accounting.data.local.entity.TradeDocumentLineEntity
import com.example.accounting.data.local.entity.VoucherDocumentReferenceEntity
import com.example.accounting.data.local.entity.VoucherDraftEntity
import com.example.accounting.data.local.entity.VoucherDraftLineEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity

@Database(
    entities = [
        CompanyEntity::class,
        BranchEntity::class,
        FinancialYearEntity::class,
        AccountingPeriodEntity::class,
        GroupEntity::class,
        LedgerEntity::class,
        VoucherEntity::class,
        JournalItemEntity::class,
        StockItemEntity::class,
        VoucherStockLineEntity::class,
        StockMovementEntity::class,
        AuditLogEntity::class,
        OutboxSyncEntity::class,
        GstTransactionEntity::class,
        SettlementAllocationEntity::class,
        GstFilingPeriodEntity::class,
        PartyEntity::class,
        InvoiceEntity::class,
        InvoiceLineEntity::class,
        TradeDocumentEntity::class,
        TradeDocumentLineEntity::class,
        DocumentTemplateEntity::class,
        BusinessProfileEntity::class,
        IndividualProfileEntity::class,
        DocumentAssetEntity::class,
        RenderedDocumentRecordEntity::class,
        RecurringVoucherScheduleEntity::class,
        RecurringVoucherLineEntity::class,
        RecurringVoucherDraftEntity::class,
        RecurringVoucherDraftLineEntity::class,
        VoucherDraftEntity::class,
        VoucherDraftLineEntity::class,
        VoucherDocumentReferenceEntity::class,
        CompanySubscriptionEntity::class,
        BankUpiProfileEntity::class,
        GstReturnEntity::class,
        GstReturnArtifactEntity::class,
        GstReturnSectionEntity::class,
        GstReturnSubmissionEntity::class
    ],
    version = 29,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountingDao(): AccountingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledgerprime_accounting.db"
                )
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.setForeignKeyConstraintsEnabled(true)
                        // Invariant: Ensure GRP_SYS_SUSPENSE exists and LED_SYS_SUSPENSE is correctly parented without data loss
                        try {
                            db.execSQL("""
                                INSERT OR IGNORE INTO account_groups (groupId, companyId, name, primaryGroup, parentGroupId, isSystem, affectsGrossProfit, displayOrder)
                                SELECT 'GRP_SYS_SUSPENSE_' || companyId, companyId, 'Suspense A/c', 'SPECIAL_CONTROL', NULL, 1, 0, 999
                                FROM companies
                            """.trimIndent())
                            db.execSQL("""
                                UPDATE ledgers
                                SET groupId = 'GRP_SYS_SUSPENSE_' || companyId
                                WHERE ledgerId LIKE 'LED_SYS_SUSPENSE_%' AND groupId NOT LIKE 'GRP_SYS_SUSPENSE_%'
                            """.trimIndent())
                        } catch (_: Exception) {
                            // Tables may not yet be initialized on initial creation
                        }
                    }
                })
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Migration 1 -> 2 (Phase 4: Inventory & COGS).
         * Purely additive: two new tables plus two new columns on `companies`, each with a
         * default that reproduces pre-Phase-4 behavior exactly (ACCOUNT_ONLY / TRADING) so every
         * existing row keeps working unchanged. No column is dropped, renamed, or retyped, and no
         * existing row is rewritten - satisfies Invariant 21 (no destructive fallback migration).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN accountingMode TEXT NOT NULL DEFAULT 'ACCOUNT_ONLY'")
                db.execSQL("ALTER TABLE companies ADD COLUMN businessType TEXT NOT NULL DEFAULT 'TRADING'")
                db.execSQL("ALTER TABLE stock_items ADD COLUMN currentAvgCostPaise INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voucher_stock_lines (
                        lineId TEXT NOT NULL PRIMARY KEY,
                        voucherId TEXT NOT NULL,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        quantityRaw INTEGER NOT NULL,
                        ratePaise INTEGER NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        FOREIGN KEY(voucherId) REFERENCES vouchers(voucherId) ON DELETE CASCADE,
                        FOREIGN KEY(itemId) REFERENCES stock_items(itemId) ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_stock_lines_voucherId ON voucher_stock_lines(voucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_stock_lines_itemId ON voucher_stock_lines(itemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_stock_lines_companyId ON voucher_stock_lines(companyId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_movements (
                        movementId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        voucherId TEXT,
                        date TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        movementType TEXT NOT NULL,
                        quantityRaw INTEGER NOT NULL,
                        ratePaise INTEGER NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        runningAvgCostAfterPaise INTEGER NOT NULL,
                        reference TEXT NOT NULL,
                        narration TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        createdBy TEXT NOT NULL,
                        FOREIGN KEY(itemId) REFERENCES stock_items(itemId) ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_companyId ON stock_movements(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_financialYearId ON stock_movements(financialYearId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_itemId ON stock_movements(itemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_voucherId ON stock_movements(voucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_date ON stock_movements(date)")
            }
        }

        /**
         * Migration 2 -> 3 (Phase 5: GST & Statutory Accounting Engine).
         * Purely additive: two new nullable/defaulted columns on `vouchers` plus three new tables,
         * none of which alter or remove any existing column or row. `gst_transactions` replaces
         * the old `ledgerName.contains("Output CGST")` report-time reconstruction with a real
         * per-line GST fact record; `settlement_allocations` models Receipt/Payment invoice
         * allocation without storing any redundant balance; `gst_filing_periods` is deliberately
         * unreferenced by any accounting-period or posting-engine code (Priority 10 - GST filing
         * governance must stay isolated from accounting-period governance).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vouchers ADD COLUMN referenceVoucherId TEXT")
                db.execSQL("ALTER TABLE vouchers ADD COLUMN paymentMode TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gst_transactions (
                        gstTransactionId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        voucherId TEXT NOT NULL,
                        voucherType TEXT NOT NULL,
                        partyLedgerId TEXT NOT NULL,
                        partyGstin TEXT NOT NULL,
                        placeOfSupply TEXT NOT NULL,
                        supplyType TEXT NOT NULL,
                        itemId TEXT,
                        hsnSacCode TEXT NOT NULL,
                        quantityRaw INTEGER,
                        taxableAmountPaise INTEGER NOT NULL,
                        gstRatePercent REAL NOT NULL,
                        cgstPaise INTEGER NOT NULL,
                        sgstPaise INTEGER NOT NULL,
                        igstPaise INTEGER NOT NULL,
                        cessPaise INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(voucherId) REFERENCES vouchers(voucherId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_companyId ON gst_transactions(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_financialYearId ON gst_transactions(financialYearId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_voucherId ON gst_transactions(voucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_partyLedgerId ON gst_transactions(partyLedgerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_direction ON gst_transactions(direction)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS settlement_allocations (
                        allocationId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        settlementVoucherId TEXT NOT NULL,
                        invoiceVoucherId TEXT,
                        allocatedAmountPaise INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(settlementVoucherId) REFERENCES vouchers(voucherId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_allocations_companyId ON settlement_allocations(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_allocations_settlementVoucherId ON settlement_allocations(settlementVoucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_settlement_allocations_invoiceVoucherId ON settlement_allocations(invoiceVoucherId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gst_filing_periods (
                        filingPeriodId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        periodLabel TEXT NOT NULL,
                        startDate TEXT NOT NULL,
                        endDate TEXT NOT NULL,
                        isLocked INTEGER NOT NULL,
                        lockedAt INTEGER,
                        lockedBy TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_filing_periods_companyId ON gst_filing_periods(companyId)")
            }
        }

        /**
         * Migration 3 -> 4 (Phase 7A: Party + Invoice Domain Foundation).
         * Purely additive: three new tables only, none of which alter, drop, or rename any
         * existing column or row. `parties` is a thin 1:1 extension of an existing `ledgers` row
         * (never a replacement); `invoices`/`invoice_lines` are a genuinely separate pre-posting
         * concept - `VoucherPostingEngine` and its tables are completely untouched by this
         * migration. Satisfies Invariant 21 (no destructive fallback migration).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS parties (
                        partyId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        ledgerId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        contactName TEXT NOT NULL,
                        creditLimitPaise INTEGER,
                        paymentTermsType TEXT NOT NULL,
                        paymentTermsCustomDays INTEGER,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(ledgerId) REFERENCES ledgers(ledgerId) ON DELETE RESTRICT,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_parties_companyId ON parties(companyId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_parties_ledgerId ON parties(ledgerId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS invoices (
                        invoiceId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        invoiceType TEXT NOT NULL,
                        invoiceNumber TEXT,
                        partyId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        dueDate TEXT,
                        voucherId TEXT,
                        referenceInvoiceId TEXT,
                        narration TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE,
                        FOREIGN KEY(partyId) REFERENCES parties(partyId) ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoices_companyId ON invoices(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoices_partyId ON invoices(partyId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_invoices_voucherId ON invoices(voucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoices_referenceInvoiceId ON invoices(referenceInvoiceId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS invoice_lines (
                        lineId TEXT NOT NULL PRIMARY KEY,
                        invoiceId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        itemName TEXT NOT NULL,
                        hsnSacCode TEXT NOT NULL,
                        quantityRaw INTEGER NOT NULL,
                        ratePaise INTEGER NOT NULL,
                        gstRatePercent REAL NOT NULL,
                        cessRatePercent REAL NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        FOREIGN KEY(invoiceId) REFERENCES invoices(invoiceId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_lines_invoiceId ON invoice_lines(invoiceId)")
            }
        }

        /**
         * Migration 4 -> 5 (Phase 7B: Document/Voucher Lifecycle Architecture).
         * Purely additive: two new tables (`trade_documents`, `trade_document_lines`) plus one
         * new nullable column on `invoices` - no existing row is rewritten, no existing column
         * altered/dropped/renamed. Satisfies Invariant 21 (no destructive fallback migration).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN sourceTradeDocumentId TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trade_documents (
                        tradeDocumentId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        documentType TEXT NOT NULL,
                        documentNumber TEXT NOT NULL,
                        partyId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        status TEXT NOT NULL,
                        sourceTradeDocumentId TEXT,
                        narration TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE,
                        FOREIGN KEY(partyId) REFERENCES parties(partyId) ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trade_documents_companyId ON trade_documents(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trade_documents_partyId ON trade_documents(partyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trade_documents_sourceTradeDocumentId ON trade_documents(sourceTradeDocumentId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trade_document_lines (
                        lineId TEXT NOT NULL PRIMARY KEY,
                        tradeDocumentId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        itemName TEXT NOT NULL,
                        hsnSacCode TEXT NOT NULL,
                        quantityRaw INTEGER NOT NULL,
                        ratePaise INTEGER NOT NULL,
                        gstRatePercent REAL NOT NULL,
                        cessRatePercent REAL NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        FOREIGN KEY(tradeDocumentId) REFERENCES trade_documents(tradeDocumentId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trade_document_lines_tradeDocumentId ON trade_document_lines(tradeDocumentId)")
            }
        }

        /**
         * Migration 5 -> 6 (Phase 7D: Document Template & Rendering Architecture).
         * Purely additive: five new tables only, none of which alter, drop, or rename any
         * existing column or row. None of these tables are read by `VoucherPostingEngine`,
         * `apply_voucher_event`, or any GST/inventory/settlement engine - this migration cannot
         * affect any accounting calculation. Satisfies Invariant 21 (no destructive fallback
         * migration).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_templates (
                        id TEXT NOT NULL PRIMARY KEY,
                        templateId TEXT NOT NULL,
                        companyId TEXT NOT NULL,
                        documentType TEXT NOT NULL,
                        templateName TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        isDefault INTEGER NOT NULL,
                        configJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_templates_companyId ON document_templates(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_templates_templateId ON document_templates(templateId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_templates_companyId_documentType ON document_templates(companyId, documentType)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS business_profiles (
                        businessProfileId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        businessName TEXT NOT NULL,
                        legalName TEXT NOT NULL,
                        address TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        email TEXT NOT NULL,
                        website TEXT NOT NULL,
                        gstin TEXT NOT NULL,
                        pan TEXT NOT NULL,
                        logoAssetId TEXT,
                        bankName TEXT NOT NULL,
                        bankAccountNumber TEXT NOT NULL,
                        bankIfsc TEXT NOT NULL,
                        bankBranch TEXT NOT NULL,
                        upiId TEXT NOT NULL,
                        qrCodeAssetId TEXT,
                        signatureAssetId TEXT,
                        termsAndConditions TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_business_profiles_companyId ON business_profiles(companyId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS individual_profiles (
                        individualProfileId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        address TEXT NOT NULL,
                        pan TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        email TEXT NOT NULL,
                        signatureAssetId TEXT,
                        termsAndConditions TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_individual_profiles_companyId ON individual_profiles(companyId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_assets (
                        assetId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        storageReference TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_assets_companyId ON document_assets(companyId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rendered_document_records (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        documentType TEXT NOT NULL,
                        templateId TEXT NOT NULL,
                        templateVersion INTEGER NOT NULL,
                        format TEXT NOT NULL,
                        storageReference TEXT,
                        generatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rendered_document_records_companyId ON rendered_document_records(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rendered_document_records_documentId ON rendered_document_records(documentId)")
            }
        }

        /**
         * Explicit Room Migrations Array.
         * In accordance with Accounting Invariant 21, production accounting databases must NEVER
         * execute destructive fallback migrations (fallbackToDestructiveMigration is strictly omitted).
         */
        /**
         * Migration 6 -> 7 (Business Profile hardening: constitution type, TAN, UDYAM).
         * Purely additive: three new nullable/defaulted columns on `business_profiles` only - no
         * existing column altered, dropped, or renamed, no existing row rewritten.
         * `constitutionType` defaults to `'PROPRIETORSHIP'` so every profile created before this
         * migration keeps behaving exactly as it did (a company with no constitution recorded is
         * the common individual/proprietor case, matching `ConstitutionType`'s own Kotlin default).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN constitutionType TEXT NOT NULL DEFAULT 'PROPRIETORSHIP'")
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN tan TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN udyam TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Migration 7 -> 8 (Phase 7F: Recurring Voucher Engine, draft-first).
         * Purely additive: four new tables only, no existing table altered. Generation produces a
         * `recurring_voucher_drafts` row - not a `vouchers` row - so a generated candidate has no
         * journal/ledger/balance/GST/inventory effect until the user explicitly posts it. The
         * unique composite index on `(scheduleId, periodKey)` in `recurring_voucher_drafts` is the
         * idempotency guarantee - a monthly automation cycle can never generate a second candidate
         * for the same period, and since draft rows are never deleted (only status-transitioned to
         * POSTED/DISCARDED), a period the user has already decided on is never re-proposed either.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_voucher_schedules (
                        scheduleId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        voucherType TEXT NOT NULL,
                        frequency TEXT NOT NULL,
                        dayOfMonth INTEGER NOT NULL,
                        narration TEXT NOT NULL,
                        startDate TEXT NOT NULL,
                        endDate TEXT,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_voucher_schedules_companyId ON recurring_voucher_schedules(companyId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_voucher_lines (
                        lineId TEXT NOT NULL PRIMARY KEY,
                        scheduleId TEXT NOT NULL,
                        ledgerId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        narration TEXT NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        FOREIGN KEY(scheduleId) REFERENCES recurring_voucher_schedules(scheduleId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_voucher_lines_scheduleId ON recurring_voucher_lines(scheduleId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_voucher_drafts (
                        draftId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        scheduleId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        periodKey TEXT NOT NULL,
                        voucherType TEXT NOT NULL,
                        date TEXT NOT NULL,
                        narration TEXT NOT NULL,
                        status TEXT NOT NULL,
                        generatedVoucherId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(scheduleId) REFERENCES recurring_voucher_schedules(scheduleId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_voucher_drafts_companyId ON recurring_voucher_drafts(companyId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recurring_voucher_drafts_scheduleId_periodKey ON recurring_voucher_drafts(scheduleId, periodKey)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_voucher_draft_lines (
                        draftLineId TEXT NOT NULL PRIMARY KEY,
                        draftId TEXT NOT NULL,
                        ledgerId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        narration TEXT NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        FOREIGN KEY(draftId) REFERENCES recurring_voucher_drafts(draftId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_voucher_draft_lines_draftId ON recurring_voucher_draft_lines(draftId)")
            }
        }

        /**
         * Migration 8 -> 9 (Phase 7J-B: Management Layer).
         * Purely additive: five new tables only, no existing table/column altered, dropped, or
         * renamed. `voucher_drafts`/`voucher_draft_lines` mirror `recurring_voucher_drafts`/
         * `recurring_voucher_draft_lines`'s exact shape - deliberately not the `vouchers`/
         * `journal_items` tables, so a draft has no journal/ledger/balance/GST/inventory effect
         * until explicitly posted through the existing, unmodified `postVoucher`.
         * `voucher_document_references` is a thin metadata join table (an attached supporting
         * document, never a rendered output - see `RenderedDocumentRecordEntity`, left untouched).
         * `company_subscriptions` gives `CompanySubscription` (Phase 7J) its first real persistence
         * - the unique `(companyId, financialYearId)` index enforces one row per company per FY;
         * paid validity is always derived from the referenced FinancialYear's own stored dates, no
         * date column is stored here at all. `bank_upi_profiles` gives `BankUpiProfile` (Phase 7G)
         * its first real persistence - settlement/contact metadata, structurally outside the
         * double-entry stream (no Ledger/Voucher/JournalItem foreign key). Satisfies Invariant 21
         * (no destructive fallback migration).
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voucher_drafts (
                        draftId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        voucherType TEXT NOT NULL,
                        date TEXT NOT NULL,
                        referenceNumber TEXT NOT NULL,
                        narration TEXT NOT NULL,
                        status TEXT NOT NULL,
                        postedVoucherId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_drafts_companyId ON voucher_drafts(companyId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voucher_draft_lines (
                        draftLineId TEXT NOT NULL PRIMARY KEY,
                        draftId TEXT NOT NULL,
                        ledgerId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        narration TEXT NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        FOREIGN KEY(draftId) REFERENCES voucher_drafts(draftId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_draft_lines_draftId ON voucher_draft_lines(draftId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voucher_document_references (
                        referenceId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        voucherId TEXT NOT NULL,
                        documentAssetId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_document_references_companyId ON voucher_document_references(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_document_references_voucherId ON voucher_document_references(voucherId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS company_subscriptions (
                        subscriptionId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        planType TEXT NOT NULL,
                        planName TEXT NOT NULL,
                        entitlementsCsv TEXT NOT NULL DEFAULT '',
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_company_subscriptions_companyId ON company_subscriptions(companyId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_company_subscriptions_companyId_financialYearId ON company_subscriptions(companyId, financialYearId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bank_upi_profiles (
                        bankUpiProfileId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        partyId TEXT,
                        bankName TEXT NOT NULL,
                        accountHolderName TEXT NOT NULL,
                        accountNumber TEXT NOT NULL,
                        ifscCode TEXT NOT NULL,
                        branchName TEXT NOT NULL,
                        upiId TEXT,
                        upiPayeeName TEXT NOT NULL DEFAULT '',
                        upiIsVerified INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bank_upi_profiles_companyId ON bank_upi_profiles(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bank_upi_profiles_partyId ON bank_upi_profiles(partyId)")
            }
        }

        /**
         * Migration 9 -> 10 (Company-level GST configuration). Purely additive: one new column on
         * `companies`, `gstEnabled`, defaulted to `1` (true) so every existing company keeps its
         * current, always-on GST behavior unchanged - `Voucher.isGstApplicable` is already
         * hardcoded `true` at every Sale/Purchase/Credit-Debit-Note construction site today,
         * regardless of any company setting, so `true` is the value that reproduces existing
         * behavior, never `false`. No column dropped, renamed, or retyped; no existing row
         * rewritten - satisfies Invariant 21 (no destructive fallback migration).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN gstEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Migration 10 -> 11 (Architecture Checkpoint: GST must be able to exist independently of
         * accounting). The only change is `gst_transactions.voucherId` NOT NULL -> nullable, so a
         * GST-only company's transaction can be persisted with no [VoucherEntity] at all - a NULL
         * child key is never checked against a SQLite FOREIGN KEY, so every existing row (always a
         * real voucherId) is completely unaffected; only a future genuinely voucher-less row can
         * use NULL. SQLite has no `ALTER COLUMN`, so this uses the standard rebuild procedure
         * (create new table with the relaxed constraint, copy every existing row unchanged, drop
         * the old table, rename, recreate every index) - `gst_transactions` is a leaf table (no
         * other table has a foreign key pointing to it), so nothing else is affected by the rebuild.
         * No data is deleted, dropped, or reinterpreted - satisfies Invariant 21 (no destructive
         * fallback migration). This is a schema-capability change only: nothing in this pass yet
         * writes a NULL voucherId - `VoucherPostingEngine`/`TradingWorkflowEngine`/every existing
         * caller is untouched and keeps supplying a real voucherId exactly as before.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL(
                    """
                    CREATE TABLE gst_transactions_new (
                        gstTransactionId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        voucherId TEXT,
                        voucherType TEXT NOT NULL,
                        partyLedgerId TEXT NOT NULL,
                        partyGstin TEXT NOT NULL,
                        placeOfSupply TEXT NOT NULL,
                        supplyType TEXT NOT NULL,
                        itemId TEXT,
                        hsnSacCode TEXT NOT NULL,
                        quantityRaw INTEGER,
                        taxableAmountPaise INTEGER NOT NULL,
                        gstRatePercent REAL NOT NULL,
                        cgstPaise INTEGER NOT NULL,
                        sgstPaise INTEGER NOT NULL,
                        igstPaise INTEGER NOT NULL,
                        cessPaise INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        lineOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(voucherId) REFERENCES vouchers(voucherId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO gst_transactions_new
                    SELECT gstTransactionId, companyId, financialYearId, voucherId, voucherType,
                           partyLedgerId, partyGstin, placeOfSupply, supplyType, itemId, hsnSacCode,
                           quantityRaw, taxableAmountPaise, gstRatePercent, cgstPaise, sgstPaise,
                           igstPaise, cessPaise, direction, lineOrder, createdAt
                    FROM gst_transactions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE gst_transactions")
                db.execSQL("ALTER TABLE gst_transactions_new RENAME TO gst_transactions")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_companyId ON gst_transactions(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_financialYearId ON gst_transactions(financialYearId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_voucherId ON gst_transactions(voucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_partyLedgerId ON gst_transactions(partyLedgerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_direction ON gst_transactions(direction)")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /**
         * Rule 30 (Party/Customer/Supplier Data Validation) - adds the storage column for
         * [com.example.accounting.domain.accounting.Ledger.gstRegistrationStatus], which existed
         * on the domain model with nowhere to persist to before this. A plain nullable column add
         * (no FK/constraint involved) - no table rebuild needed, unlike [MIGRATION_10_11]. Every
         * existing row reads back NULL (UNKNOWN) - never guessed as REGISTERED/UNREGISTERED.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ledgers ADD COLUMN gstRegistrationStatus TEXT")
            }
        }

        /**
         * Rule 31 (Purchase/RCM Foundation) - adds the storage column for
         * [com.example.accounting.domain.taxation.gst.GstTransaction.chargeType]. A plain NOT NULL
         * column with a real, correct default: every pre-existing row was posted before RCM existed
         * in this codebase at all, so it genuinely was FORWARD_CHARGE - not a guess.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gst_transactions ADD COLUMN chargeType TEXT NOT NULL DEFAULT 'FORWARD_CHARGE'")
            }
        }

        /**
         * Rule 33 (GST Return Dashboard & Filing Foundation) - adds the company's own GST scheme
         * column (defaulting every pre-existing company to REGULAR, the ordinary scheme - see
         * [com.example.accounting.domain.company.Company.gstScheme]) and the four new tables the
         * return lifecycle needs. No existing table's columns are touched.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN gstScheme TEXT NOT NULL DEFAULT 'REGULAR'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_returns` (
                        `gstReturnId` TEXT NOT NULL,
                        `companyId` TEXT NOT NULL,
                        `financialYearId` TEXT NOT NULL,
                        `fyCode` TEXT NOT NULL,
                        `quarter` TEXT NOT NULL,
                        `month` INTEGER,
                        `periodKey` TEXT NOT NULL,
                        `scheme` TEXT NOT NULL,
                        `returnType` TEXT NOT NULL,
                        `periodicity` TEXT NOT NULL,
                        `filingMode` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `submittedAt` INTEGER,
                        `acknowledgementNumber` TEXT,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `latestRequestArtifactId` TEXT,
                        `latestResponseArtifactId` TEXT,
                        `schemaVersion` TEXT NOT NULL,
                        PRIMARY KEY(`gstReturnId`),
                        FOREIGN KEY(`companyId`) REFERENCES `companies`(`companyId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`financialYearId`) REFERENCES `financial_years`(`financialYearId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_returns_companyId` ON `gst_returns` (`companyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_returns_financialYearId` ON `gst_returns` (`financialYearId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_returns_companyId_periodKey_returnType_scheme` ON `gst_returns` (`companyId`, `periodKey`, `returnType`, `scheme`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_return_artifacts` (
                        `artifactId` TEXT NOT NULL,
                        `gstReturnId` TEXT NOT NULL,
                        `artifactType` TEXT NOT NULL,
                        `schemaVersion` TEXT NOT NULL,
                        `jsonContent` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`artifactId`),
                        FOREIGN KEY(`gstReturnId`) REFERENCES `gst_returns`(`gstReturnId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_return_artifacts_gstReturnId` ON `gst_return_artifacts` (`gstReturnId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_return_sections` (
                        `sectionId` TEXT NOT NULL,
                        `gstReturnId` TEXT NOT NULL,
                        `sectionKey` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `resultDataJson` TEXT,
                        `errorsJson` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sectionId`),
                        FOREIGN KEY(`gstReturnId`) REFERENCES `gst_returns`(`gstReturnId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_return_sections_gstReturnId` ON `gst_return_sections` (`gstReturnId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_gst_return_sections_gstReturnId_sectionKey` ON `gst_return_sections` (`gstReturnId`, `sectionKey`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gst_return_submissions` (
                        `submissionId` TEXT NOT NULL,
                        `gstReturnId` TEXT NOT NULL,
                        `attemptNumber` INTEGER NOT NULL,
                        `requestArtifactId` TEXT,
                        `responseArtifactId` TEXT,
                        `status` TEXT NOT NULL,
                        `acknowledgementNumber` TEXT,
                        `errorCode` TEXT,
                        `errorMessage` TEXT,
                        `submittedAt` INTEGER NOT NULL,
                        `respondedAt` INTEGER,
                        PRIMARY KEY(`submissionId`),
                        FOREIGN KEY(`gstReturnId`) REFERENCES `gst_returns`(`gstReturnId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gst_return_submissions_gstReturnId` ON `gst_return_submissions` (`gstReturnId`)")
            }
        }

        /**
         * Rule 33 follow-up - adds the company's filing-frequency column (Monthly/Quarterly for a
         * REGULAR company's QRMP choice - see [com.example.accounting.domain.company.Company.gstFilingFrequency]).
         * Defaulting every pre-existing company to MONTHLY reproduces prior behavior exactly, since
         * QRMP never existed as an option before this. GstScheme.QRMP (a since-removed enum value)
         * is never written by any app code path going forward; [RoomConverters.toGstScheme]'s own
         * try/catch already falls back to REGULAR for any stray value it cannot parse, so no data
         * migration of the `scheme` column itself is needed.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN gstFilingFrequency TEXT NOT NULL DEFAULT 'MONTHLY'")
            }
        }

        /**
         * PIN-code address lookup (Profile Wizard follow-up) - adds structured City/State/
         * Country/PinCode columns to `business_profiles`/`individual_profiles`, additive
         * alongside the existing free-text `address` column (never replacing it). Plain nullable-
         * default column adds, no table rebuild needed. Every pre-existing profile row reads back
         * empty strings for these - never guessed from `address` text or any other source.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN pinCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN city TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN state TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE business_profiles ADD COLUMN country TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE individual_profiles ADD COLUMN pinCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE individual_profiles ADD COLUMN city TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE individual_profiles ADD COLUMN state TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE individual_profiles ADD COLUMN country TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Phase 7J-B.2 — a database-level guarantee against a duplicate voucher attachment: the
         * same `(voucherId, documentAssetId)` pair can never be linked twice, while the same
         * `documentAssetId` remains freely attachable to a *different* voucher (the index is
         * composite, not on `documentAssetId` alone). Pure `CREATE UNIQUE INDEX` - no column
         * added, no table rebuild, no existing row can violate it (the table has never had a
         * production write path before this phase).
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_voucher_document_references_voucherId_documentAssetId ON voucher_document_references(voucherId, documentAssetId)")
            }
        }

        /**
         * D1a (Company Mode + Account-Only Sale/Purchase) - one new column on `companies`,
         * `gstOperatingMode`, backfilled from each company's own existing `gstEnabled` value
         * rather than a single blanket default, per Invariant 21 (no destructive/guessed
         * migration): a company that already had GST disabled (`gstEnabled = 0`) becomes
         * `ACCOUNT_ONLY`; every other company (the default, `gstEnabled = 1`) becomes
         * `ACCOUNT_WITH_GST`, reproducing its actual prior behavior exactly rather than assuming
         * one. No column dropped, renamed, or retyped; no voucher/ledger/GST-transaction/stock
         * table touched - this is a company-configuration-only change, and per this migration's
         * own scope, mode never rewrites, recalculates, or reposts any existing transaction.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN gstOperatingMode TEXT NOT NULL DEFAULT 'ACCOUNT_WITH_GST'")
                db.execSQL("UPDATE companies SET gstOperatingMode = 'ACCOUNT_ONLY' WHERE gstEnabled = 0")
            }
        }

        /**
         * D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - four new columns
         * on `gst_transactions`, all plain additive `ADD COLUMN`s (no rebuild needed, unlike
         * [MIGRATION_10_11]'s voucherId relaxation):
         *
         * - `supplyNature` (NOT NULL, default 'NORMAL'): backfilled from each row's own existing
         *   `supplyType` where that's unambiguous (INTRA_STATE/INTER_STATE -> NORMAL, EXPORT ->
         *   EXPORT, EXEMPT -> EXEMPT) - a real, disclosed limitation: `supplyType` has always
         *   collapsed EXEMPT and NIL_RATED into one value, so a pre-existing EXEMPT row cannot be
         *   distinguished from a pre-existing NIL_RATED one after the fact. This backfill is the
         *   closest honest approximation available, not a guess presented as certain.
         * - `transactionGroupId` (NOT NULL, default ''): backfilled to `COALESCE(voucherId,
         *   gstTransactionId)` - the real voucherId for every accounting-integrated row (correct,
         *   unambiguous), or the row's own id as a single-row group for any pre-existing GST-only
         *   row (none exist in production per the D1a-era audit; still the honest fallback).
         * - `transactionDate` (nullable TEXT, ISO-8601): left NULL for every existing row - every
         *   existing row is accounting-integrated and already has an unambiguous date via the
         *   joined `vouchers.date`, so nothing here needs backfilling or is lost.
         * - `partyGstRegistrationStatus` (nullable TEXT): left NULL (UNKNOWN) for every existing
         *   row - no historical snapshot of this ever existed before this migration, so `null` is
         *   the only honest value, matching `ledgers.gstRegistrationStatus`'s own "null means
         *   unknown, never guessed" convention (see `MIGRATION_11_12`).
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gst_transactions ADD COLUMN supplyNature TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("UPDATE gst_transactions SET supplyNature = 'EXPORT' WHERE supplyType = 'EXPORT'")
                db.execSQL("UPDATE gst_transactions SET supplyNature = 'EXEMPT' WHERE supplyType = 'EXEMPT'")
                db.execSQL("ALTER TABLE gst_transactions ADD COLUMN transactionGroupId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE gst_transactions SET transactionGroupId = COALESCE(voucherId, gstTransactionId) WHERE transactionGroupId = ''")
                db.execSQL("ALTER TABLE gst_transactions ADD COLUMN transactionDate TEXT")
                db.execSQL("ALTER TABLE gst_transactions ADD COLUMN partyGstRegistrationStatus TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_transactions_transactionGroupId ON gst_transactions(transactionGroupId)")
            }
        }

        /**
         * Audit fix (Company/Profile/Ledger Setup) - two new columns on `ledgers`,
         * `bankName`/`bankBranch`, completing the Bank-ledger detail set alongside the two that
         * already existed (`bankAccountNumber`/`bankIfsc`) but had no UI collecting any of them.
         * Both plain additive `ADD COLUMN`s with an explicit default matching the entity's own
         * `@ColumnInfo(defaultValue = "''")` exactly (the lesson from this same session's earlier
         * `gst_transactions`/`companies` schema-validation crash) - every existing ledger reads
         * back an honest empty string, never a guessed bank name/branch.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ledgers ADD COLUMN bankName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ledgers ADD COLUMN bankBranch TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Customer/Supplier Setup fix - one new column on `ledgers`, `pinCode`, completing the
         * postal-address fields a Customer/Supplier ledger can optionally carry (Address/State
         * Code already existed). Plain additive `ADD COLUMN` with an explicit default matching the
         * entity's own `@ColumnInfo(defaultValue = "''")` exactly, same pattern as `MIGRATION_19_20`. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ledgers ADD COLUMN pinCode TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 13-point correctness pass, item 1 - `companies.pinCode` (Company previously had no PIN
         * field at all, only Ledger did). Plain additive `ADD COLUMN` with an explicit default
         * matching the entity's own `@ColumnInfo(defaultValue = ...)` exactly, same pattern as
         * every migration since `MIGRATION_19_20`. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN pinCode TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Group-hierarchy audit fix - `AccountingRepository.createCompany()` was seeding only a
         * flat 10-group subset (Bank/Cash/Debtors/Creditors/Duties/Sales/Purchase/Direct-Indirect
         * Expenses/Capital, all with `parentGroupId = NULL`) instead of the canonical, correctly
         * nested 28-group hierarchy (`StandardSystemGroups.getStandardGroupsForCompany`) already
         * used elsewhere in this file. Every company created before this fix is missing 17 System
         * Groups entirely - most importantly Loans (Liability)/Bank OD A/c/Secured Loans/Unsecured
         * Loans, so a company with a real Bank OD, CC, or loan account had no existing appropriate
         * Liabilities-side Group to file it under at all.
         *
         * Purely additive and idempotent: `INSERT OR IGNORE` keyed on each group's already-unique
         * `groupId` primary key, so a company's own already-existing groups (Bank/Cash/Debtors/
         * Creditors/Duties/Sales/Purchase/Direct-Indirect Expenses/Capital) are left completely
         * untouched - not reparented, not renamed, not reclassified - only the groups that never
         * existed for that company are backfilled. No ledger, voucher, or journal-item row is
         * touched; no existing Group's `parentGroupId` changes, so no prior Trial
         * Balance/Balance Sheet figure is altered by this migration (the report engine derives
         * Bank/Cash/Debtors/Creditors/Duties totals by exact `groupId` lookup, never by walking
         * up from a child - see `AccountingRepository.generateBalanceSheet`'s `netDebit`/`netCredit`
         * helpers). Satisfies Invariant 21 (no destructive fallback migration). Parent groups are
         * inserted before the children that reference them by `parentGroupId` for readability only
         * - `account_groups` has no self-referencing foreign key, so insert order has no functional
         * effect.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                fun backfillGroup(bareId: String, name: String, primaryGroup: String, parentBareId: String?, affectsGrossProfit: Boolean, displayOrder: Int) {
                    val parentExpr = if (parentBareId != null) "'${parentBareId}_' || companyId" else "NULL"
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO account_groups (groupId, companyId, name, primaryGroup, parentGroupId, isSystem, affectsGrossProfit, displayOrder)
                        SELECT '${bareId}_' || companyId, companyId, '$name', '$primaryGroup', $parentExpr, 1, ${if (affectsGrossProfit) 1 else 0}, $displayOrder
                        FROM companies
                        """.trimIndent()
                    )
                }

                // EQUITY
                backfillGroup("GRP_RESERVES", Constants.SYS_RESERVES_SURPLUS, "EQUITY", null, false, 20)

                // ASSETS (parent container first, then its children)
                backfillGroup("GRP_CURRENT_ASSETS", Constants.SYS_CURRENT_ASSETS, "ASSETS", null, false, 30)
                backfillGroup("GRP_DEPOSITS", Constants.SYS_DEPOSITS_ASSET, "ASSETS", "GRP_CURRENT_ASSETS", false, 33)
                backfillGroup("GRP_LOANS_ADV", Constants.SYS_LOANS_ADVANCES, "ASSETS", "GRP_CURRENT_ASSETS", false, 34)
                backfillGroup("GRP_STOCK", Constants.SYS_STOCK_IN_HAND, "ASSETS", "GRP_CURRENT_ASSETS", true, 35)
                backfillGroup("GRP_FIXED_ASSETS", Constants.SYS_FIXED_ASSETS, "ASSETS", null, false, 40)
                backfillGroup("GRP_INVESTMENTS", Constants.SYS_INVESTMENTS, "ASSETS", null, false, 50)
                backfillGroup("GRP_MISC_EXP", Constants.SYS_MISC_EXPENSES, "ASSETS", null, false, 55)

                // LIABILITIES (parent containers first, then their children)
                backfillGroup("GRP_CURRENT_LIAB", Constants.SYS_CURRENT_LIABILITIES, "LIABILITIES", null, false, 60)
                backfillGroup("GRP_PROVISIONS", Constants.SYS_PROVISIONS, "LIABILITIES", "GRP_CURRENT_LIAB", false, 62)
                backfillGroup("GRP_LOANS", Constants.SYS_LOANS_LIABILITY, "LIABILITIES", null, false, 70)
                backfillGroup("GRP_BANK_OD", Constants.SYS_BANK_OD, "LIABILITIES", "GRP_LOANS", false, 71)
                backfillGroup("GRP_SECURED_LOANS", Constants.SYS_SECURED_LOANS, "LIABILITIES", "GRP_LOANS", false, 72)
                backfillGroup("GRP_UNSECURED_LOANS", Constants.SYS_UNSECURED_LOANS, "LIABILITIES", "GRP_LOANS", false, 73)
                backfillGroup("GRP_BRANCH_DIV", Constants.SYS_BRANCH_DIVISIONS, "LIABILITIES", null, false, 75)

                // INCOME
                backfillGroup("GRP_DIR_INCOME", Constants.SYS_DIRECT_INCOMES, "INCOME", null, true, 90)
                backfillGroup("GRP_INDIR_INCOME", Constants.SYS_INDIRECT_INCOMES, "INCOME", null, false, 100)
            }
        }

        /** Phase 8A, Part 2 - adds [GstReturnEntity.isNilReturn], the user's own explicit
         * declaration that a period genuinely has zero outward supplies (never inferred silently
         * from an empty section - see [Gstr1Validator]'s own "zero outward supplies" warning,
         * which this flag is the one thing that suppresses). Defaults to 0/false for every
         * existing row - an already-prepared return was never marked Nil before this column
         * existed, so `false` is the only honest backfill, never a guess either way. */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gst_returns ADD COLUMN isNilReturn INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Phase 8A, Part 2 - see [com.example.accounting.domain.company.Company.gstr1ReminderEnabled].
         * Defaults every existing company to `1` (enabled) - the only value that reproduces the
         * always-on reminder behavior every company already had before this toggle existed. */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN gstr1ReminderEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** "5 Invoice PDF Templates" task - stores the exact discount amount subtracted per stock
         * line (previously computed in-memory and discarded) so an invoice PDF/preview can display
         * it without recalculating. Purely additive; every historical row defaults to 0. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voucher_stock_lines ADD COLUMN discountPaise INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** GST Settings refactor - see [com.example.accounting.data.local.entity.CompanyEntity.gstReturnPeriodMonth]/
         * [com.example.accounting.data.local.entity.CompanyEntity.gstReturnPeriodQuarter]. Both
         * columns default NULL for every existing company - "no explicit selection yet", the exact
         * same as the in-memory default this screen already fell back to before either column
         * existed. */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companies ADD COLUMN gstReturnPeriodMonth INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE companies ADD COLUMN gstReturnPeriodQuarter TEXT DEFAULT NULL")
            }
        }

        /** Root-cause fix for company deletion failing with SQLITE_CONSTRAINT_FOREIGNKEY on any
         * company with stock activity: `stock_movements` had a `companyId` column but NO foreign
         * key to `companies`, so deleting a company cascaded away its `stock_items` while the
         * (never-deleted, append-only) `stock_movements` rows referencing those items were left
         * behind, tripping the `itemId -> stock_items` RESTRICT constraint at commit. This adds
         * the missing `companies` CASCADE so a company delete now takes its stock movement history
         * with it, exactly like every other company-scoped table already does. SQLite has no
         * `ALTER TABLE ... ADD CONSTRAINT`, so this uses the same rebuild procedure as
         * MIGRATION_10_11 (new table, copy every row unchanged, drop, rename, recreate indices) -
         * `stock_movements` is a leaf table (nothing has a foreign key pointing to it), so nothing
         * else is affected. No data is deleted, dropped, or reinterpreted for any company that
         * still exists. */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL(
                    """
                    CREATE TABLE stock_movements_new (
                        movementId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        financialYearId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        voucherId TEXT,
                        date TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        movementType TEXT NOT NULL,
                        quantityRaw INTEGER NOT NULL,
                        ratePaise INTEGER NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        runningAvgCostAfterPaise INTEGER NOT NULL,
                        reference TEXT NOT NULL,
                        narration TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        createdBy TEXT NOT NULL,
                        FOREIGN KEY(itemId) REFERENCES stock_items(itemId) ON DELETE RESTRICT,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO stock_movements_new
                    SELECT movementId, companyId, financialYearId, itemId, voucherId, date,
                           direction, movementType, quantityRaw, ratePaise, amountPaise,
                           runningAvgCostAfterPaise, reference, narration, createdAt, createdBy
                    FROM stock_movements
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE stock_movements")
                db.execSQL("ALTER TABLE stock_movements_new RENAME TO stock_movements")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_companyId ON stock_movements(companyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_financialYearId ON stock_movements(financialYearId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_itemId ON stock_movements(itemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_voucherId ON stock_movements(voucherId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_date ON stock_movements(date)")

                // Same gap, same fix: gst_filing_periods also had a companyId column with no
                // foreign key at all, so its rows were silently orphaned (not blocking deletion,
                // since nothing references it - but violating the "everything under it" promise
                // the company-delete confirmation makes to the user).
                db.execSQL(
                    """
                    CREATE TABLE gst_filing_periods_new (
                        filingPeriodId TEXT NOT NULL PRIMARY KEY,
                        companyId TEXT NOT NULL,
                        periodLabel TEXT NOT NULL,
                        startDate TEXT NOT NULL,
                        endDate TEXT NOT NULL,
                        isLocked INTEGER NOT NULL,
                        lockedAt INTEGER,
                        lockedBy TEXT,
                        FOREIGN KEY(companyId) REFERENCES companies(companyId) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO gst_filing_periods_new
                    SELECT filingPeriodId, companyId, periodLabel, startDate, endDate, isLocked, lockedAt, lockedBy
                    FROM gst_filing_periods
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE gst_filing_periods")
                db.execSQL("ALTER TABLE gst_filing_periods_new RENAME TO gst_filing_periods")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gst_filing_periods_companyId ON gst_filing_periods(companyId)")

                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /** Contacts + Favorites correction (docs/CORRECTIONS_LOG.md, 2026-09-09) - a real, persisted
         * "starred" flag for a Customer/Supplier, so it can sort to the top of its list. Purely
         * additive; every existing party defaults to not-favorite (unchanged behaviour/ordering
         * until a user explicitly stars one). */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parties ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29)
    }
}
