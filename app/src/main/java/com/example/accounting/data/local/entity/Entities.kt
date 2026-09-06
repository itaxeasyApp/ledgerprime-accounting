package com.example.accounting.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.accounting.application.voucher.VoucherDraftStatus
import com.example.accounting.core.common.DrCr
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.audit.AuditAction
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.company.GstOperatingMode
import com.example.accounting.domain.document.DocumentStatus
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.StockMovementType
import com.example.accounting.domain.party.PartyEntityType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.party.PaymentTermsType
import com.example.accounting.domain.recurring.RecurringFrequency
import com.example.accounting.domain.recurring.RecurringVoucherDraftStatus
import com.example.accounting.domain.rendering.ConstitutionType
import com.example.accounting.domain.rendering.DocumentAssetType
import com.example.accounting.domain.rendering.TemplateStatus
import com.example.accounting.domain.subscription.SubscriptionPlanType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.SupplyType
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstReturnArtifactType
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnSectionStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme

@Entity(tableName = "companies")
data class CompanyEntity(
    @PrimaryKey val companyId: String,
    val name: String,
    val tradeName: String,
    val gstin: String,
    val pan: String,
    val stateCode: String,
    val stateName: String,
    val email: String,
    val phone: String,
    val address: String,
    val currency: String,
    val financialYearStartMonth: Int,
    val isDefault: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'ACCOUNT_ONLY'") val accountingMode: AccountingMode = AccountingMode.ACCOUNT_ONLY,
    @ColumnInfo(defaultValue = "'TRADING'") val businessType: BusinessType = BusinessType.TRADING,
    @ColumnInfo(defaultValue = "1") val gstEnabled: Boolean = true,
    /** D1a - see [com.example.accounting.domain.company.Company.gstOperatingMode]. */
    @ColumnInfo(defaultValue = "'ACCOUNT_WITH_GST'") val gstOperatingMode: GstOperatingMode = GstOperatingMode.ACCOUNT_WITH_GST,
    /** Rule 33 (GST Return Dashboard & Filing Foundation) - see
     * [com.example.accounting.domain.company.Company.gstScheme]. */
    @ColumnInfo(defaultValue = "'REGULAR'") val gstScheme: GstScheme = GstScheme.REGULAR,
    /** Rule 33 - see [com.example.accounting.domain.company.Company.gstFilingFrequency]. */
    @ColumnInfo(defaultValue = "'MONTHLY'") val gstFilingFrequency: GstReturnPeriodicity = GstReturnPeriodicity.MONTHLY,
    /** 13-point correctness pass, item 1 - see [com.example.accounting.domain.company.Company.pinCode]. */
    @ColumnInfo(defaultValue = "''") val pinCode: String = ""
)

@Entity(
    tableName = "branches",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index(value = ["companyId", "code"], unique = true)]
)
data class BranchEntity(
    @PrimaryKey val branchId: String,
    val companyId: String,
    val code: String,
    val name: String,
    val gstin: String?,
    val stateCode: String?,
    val address: String?,
    val isHeadOffice: Boolean,
    val isActive: Boolean
)

@Entity(
    tableName = "financial_years",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class FinancialYearEntity(
    @PrimaryKey val financialYearId: String,
    val companyId: String,
    val fyCode: String,
    val startDate: String, // ISO-8601 YYYY-MM-DD
    val endDate: String,
    val isCurrent: Boolean,
    val isLocked: Boolean,
    val lockedAt: Long?,
    val lockedBy: String?
)

@Entity(
    tableName = "accounting_periods",
    foreignKeys = [
        ForeignKey(
            entity = FinancialYearEntity::class,
            parentColumns = ["financialYearId"],
            childColumns = ["financialYearId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("financialYearId"), Index("companyId")]
)
data class AccountingPeriodEntity(
    @PrimaryKey val periodId: String,
    val companyId: String,
    val financialYearId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val status: PeriodStatus,
    val lockedAt: Long?,
    val lockedBy: String?
)

@Entity(
    tableName = "account_groups",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val companyId: String,
    val name: String,
    val primaryGroup: PrimaryGroup,
    val parentGroupId: String?,
    val isSystem: Boolean,
    val affectsGrossProfit: Boolean,
    val displayOrder: Int
)

@Entity(
    tableName = "ledgers",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index("groupId")]
)
data class LedgerEntity(
    @PrimaryKey val ledgerId: String,
    val companyId: String,
    val groupId: String,
    val name: String,
    val code: String,
    val openingBalancePaise: Long,
    val openingBalanceType: DrCr,
    val currentBalancePaise: Long,
    val currentBalanceType: DrCr,
    val gstin: String,
    val pan: String,
    val stateCode: String,
    val email: String,
    val phone: String,
    val address: String,
    @ColumnInfo(defaultValue = "''") val bankName: String = "",
    val bankAccountNumber: String,
    val bankIfsc: String,
    @ColumnInfo(defaultValue = "''") val bankBranch: String = "",
    val isSystem: Boolean,
    val isActive: Boolean,
    val hsnSacCode: String,
    val defaultTaxRate: Double,
    /** Rule 30 (Party/Customer/Supplier Data Validation) - stores [GstRegistrationStatus.name], or
     * `null` for UNKNOWN. Was declared on the domain [Ledger] model with no backing column until
     * this field was added - never persisted before, so every pre-existing row reads back `null`
     * (honestly UNKNOWN), never a guessed REGISTERED/UNREGISTERED. */
    val gstRegistrationStatus: String? = null,
    /** Customer/Supplier Setup fix - optional postal PIN code, appended last (never inserted
     * mid-constructor) so every existing positional `LedgerEntity(...)` call site - test fixtures
     * included - keeps compiling unchanged. */
    @ColumnInfo(defaultValue = "''") val pinCode: String = ""
)

@Entity(
    tableName = "vouchers",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FinancialYearEntity::class,
            parentColumns = ["financialYearId"],
            childColumns = ["financialYearId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("companyId"), Index("financialYearId"), Index("voucherNumber")]
)
data class VoucherEntity(
    @PrimaryKey val voucherId: String,
    val companyId: String,
    val financialYearId: String,
    val voucherNumber: String,
    val voucherType: VoucherType,
    val date: String, // YYYY-MM-DD
    val referenceNumber: String,
    val narration: String,
    val totalAmountPaise: Long,
    val isPosted: Boolean,
    val isCancelled: Boolean,
    val syncState: SyncState,
    val createdAt: Long,
    val updatedAt: Long,
    val createdBy: String,
    val partyGstin: String,
    val isGstApplicable: Boolean,
    /** Credit Note -> original Sale voucherId, Debit Note -> original Purchase voucherId. Null for
     * every other voucher type. The referenced voucher is never modified - this is purely a
     * pointer, preserving immutability of the original posting (Phase 5, Priority 1). */
    val referenceVoucherId: String? = null,
    /** Metadata only ("CASH"/"BANK"/"UPI") - the actual settlement ledger is whatever Cash/Bank
     * ledger the journal lines reference; UPI never becomes its own ledger type (Phase 5, Priority 8). */
    @ColumnInfo(defaultValue = "''") val paymentMode: String = ""
)

@Entity(
    tableName = "journal_items",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["voucherId"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["ledgerId"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("voucherId"), Index("ledgerId"), Index("companyId")]
)
data class JournalItemEntity(
    @PrimaryKey val itemId: String,
    val voucherId: String,
    val companyId: String,
    val financialYearId: String,
    val ledgerId: String,
    val type: DrCr,
    val amountPaise: Long,
    val narration: String,
    val lineOrder: Int
)

@Entity(
    tableName = "stock_items",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId")]
)
data class StockItemEntity(
    @PrimaryKey val itemId: String,
    val companyId: String,
    val name: String,
    val sku: String,
    val hsnCode: String,
    val unit: String,
    val gstRatePercent: Double,
    val openingQuantity: Long,
    val openingRatePaise: Long,
    val currentQuantity: Long,
    val standardCostPaise: Long,
    val standardSellingPricePaise: Long,
    /**
     * Cached weighted-average cost per unit (paise), updated alongside every IN [StockMovementEntity].
     * Performance cache only - [StockMovementEntity] history remains the authoritative source of
     * truth and this value must always be reproducible by replaying movements from scratch.
     */
    @ColumnInfo(defaultValue = "0") val currentAvgCostPaise: Long = 0L
)

@Entity(
    tableName = "voucher_stock_lines",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["voucherId"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = StockItemEntity::class,
            parentColumns = ["itemId"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("voucherId"), Index("itemId"), Index("companyId")]
)
data class VoucherStockLineEntity(
    @PrimaryKey val lineId: String,
    val voucherId: String,
    val companyId: String,
    val financialYearId: String,
    val itemId: String,
    val direction: StockDirection,
    val quantityRaw: Long, // thousandths, see Quantity
    val ratePaise: Long,   // transaction rate (purchase cost or selling price) as entered on the voucher
    val amountPaise: Long,
    val lineOrder: Int
)

/**
 * Immutable stock ledger entry (Section 4 of the Phase 4 spec). NEVER updated or deleted after
 * insert - cancellations append a compensating [StockMovementType.CANCELLATION_REVERSAL] row with
 * opposite [StockDirection] instead (mirrors the Phase 2 voucher-cancellation design exactly).
 * [ratePaise]/[amountPaise] are the COST basis, not the voucher's transaction rate: for IN
 * movements this equals the transaction rate; for OUT movements this is the weighted-average
 * cost at the moment of the movement (see StockValuationEngine), making it directly usable as a
 * COGS contribution.
 */
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = StockItemEntity::class,
            parentColumns = ["itemId"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("companyId"), Index("financialYearId"), Index("itemId"), Index("voucherId"), Index("date")]
)
data class StockMovementEntity(
    @PrimaryKey val movementId: String,
    val companyId: String,
    val financialYearId: String,
    val itemId: String,
    val voucherId: String?,
    val date: String, // YYYY-MM-DD
    val direction: StockDirection,
    val movementType: StockMovementType,
    val quantityRaw: Long,
    val ratePaise: Long,
    val amountPaise: Long,
    val runningAvgCostAfterPaise: Long,
    val reference: String,
    val narration: String,
    val createdAt: Long,
    val createdBy: String
)

@Entity(
    tableName = "audit_logs",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index("timestamp")]
)
data class AuditLogEntity(
    @PrimaryKey val logId: String,
    val companyId: String,
    val financialYearId: String,
    val action: AuditAction,
    val entityType: String,
    val entityId: String,
    val description: String,
    val performedBy: String,
    val timestamp: Long,
    val payloadJson: String
)

@Entity(
    tableName = "outbox_sync",
    indices = [
        Index("companyId"),
        Index("syncState"),
        Index("createdAt"),
        Index(value = ["idempotencyKey"], unique = true)
    ]
)
data class OutboxSyncEntity(
    @PrimaryKey val syncId: String,
    val companyId: String,
    val entityType: String,
    val entityId: String,
    val operation: String, // INSERT, UPDATE, DELETE, POST
    val payloadJson: String,
    val idempotencyKey: String = java.util.UUID.randomUUID().toString(),
    val syncState: SyncState,
    val retryCount: Int,
    val lastError: String?,
    val version: Long = 1L,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * The dedicated GST-fact record (Phase 5, Priority 4). One row per taxable line on a GST-bearing
 * voucher (Sale/Purchase/Credit Note/Debit Note) - replaces reconstructing GST facts from
 * `ledgerName.contains("Output CGST")` scans with real, queryable domain identity: party, place of
 * supply, HSN/SAC, rate, and the CGST/SGST/IGST/CESS split, all tied to the voucher that created
 * them. This is the sole source for [com.example.accounting.domain.reports.GSTSummaryReport] and
 * any future HSN/rate/party-wise statutory report.
 */
@Entity(
    tableName = "gst_transactions",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["voucherId"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index("financialYearId"), Index("voucherId"), Index("partyLedgerId"), Index("direction"), Index("transactionGroupId")]
)
data class GstTransactionEntity(
    @PrimaryKey val gstTransactionId: String,
    val companyId: String,
    val financialYearId: String,
    /** UI-06/Architecture Checkpoint: nullable (was non-null) - a GST-only company's transaction
     * has no [VoucherEntity] at all. `null` bypasses SQLite's FK check entirely (a NULL child key
     * is never validated against the parent), so every existing row (always a real voucherId)
     * keeps working unchanged; only a genuinely voucher-less GST transaction can now use `null`. */
    val voucherId: String?,
    val voucherType: VoucherType,
    val partyLedgerId: String,
    val partyGstin: String,
    val placeOfSupply: String,
    val supplyType: SupplyType,
    val itemId: String?,
    val hsnSacCode: String,
    val quantityRaw: Long?,
    val taxableAmountPaise: Long,
    val gstRatePercent: Double,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
    val direction: GstDirection,
    val lineOrder: Int,
    val createdAt: Long,
    /** Rule 31 (Purchase/RCM Foundation) - see [com.example.accounting.domain.taxation.gst.GstTransaction.chargeType].
     * Defaults to FORWARD_CHARGE - every pre-existing row (RCM never existed before this) really
     * was forward-charge, so the migration backfill default is a genuine fact, not a guess. */
    @ColumnInfo(defaultValue = "'FORWARD_CHARGE'") val chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE,
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.supplyNature]. Defaults
     * to NORMAL; `MIGRATION_18_19` backfills existing rows from their own `supplyType` (see that
     * migration's own comment for the exact, disclosed EXEMPT-vs-NIL_RATED limitation). */
    @ColumnInfo(defaultValue = "'NORMAL'") val supplyNature: GstSupplyNature = GstSupplyNature.NORMAL,
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.transactionGroupId].
     * Never blank in practice - `MIGRATION_18_19` backfills every existing row from its own
     * `voucherId` (accounting-integrated) or, failing that, its own `gstTransactionId` (a
     * pre-migration GST-only row, treated as its own one-line group - none exist in production
     * per the D1a-era audit, but this is still the honest, non-destructive backfill). */
    @ColumnInfo(defaultValue = "''") val transactionGroupId: String = "",
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.transactionDate]. ISO-
     * 8601 (`YYYY-MM-DD`), matching [VoucherEntity.date]'s own convention - `null` for every
     * accounting-integrated row (unchanged; that row's real date is [VoucherEntity.date] via the
     * join), always a real value for a GST-only row. */
    val transactionDate: String? = null,
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.partyGstRegistrationStatus].
     * Stores [com.example.accounting.domain.accounting.GstRegistrationStatus.name], or `null` for
     * UNKNOWN - the exact same plain-nullable-String convention [LedgerEntity.gstRegistrationStatus]
     * already uses (manual `.name`/`.valueOf()` mapping at the repository boundary, no Room
     * converter), deliberately mirrored rather than introduced as a second pattern. */
    val partyGstRegistrationStatus: String? = null
)

/**
 * Receipt/Payment invoice allocation (Phase 5, Priority 2). One row per (settlement voucher,
 * invoice voucher) pair the user allocated part of the receipt/payment against - `invoiceVoucherId
 * = null` represents an unallocated/advance amount. Per-invoice outstanding is always computed at
 * query time (`invoice total - Sum(allocations) - Sum(note adjustments)`), never stored
 * redundantly, matching the project's "ledger balance is the only stored balance" discipline.
 */
@Entity(
    tableName = "settlement_allocations",
    foreignKeys = [
        ForeignKey(
            entity = VoucherEntity::class,
            parentColumns = ["voucherId"],
            childColumns = ["settlementVoucherId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index("settlementVoucherId"), Index("invoiceVoucherId")]
)
data class SettlementAllocationEntity(
    @PrimaryKey val allocationId: String,
    val companyId: String,
    val financialYearId: String,
    val settlementVoucherId: String,
    val invoiceVoucherId: String?,
    val allocatedAmountPaise: Long,
    val createdAt: Long
)

/**
 * GST compliance-period governance, deliberately isolated from [AccountingPeriodEntity] (Phase 5,
 * Priority 10) - a GST filing period can be locked for return-filing purposes without touching
 * accounting-period locking at all. Nothing in [com.example.accounting.domain.accounting.DoubleEntryValidator]
 * or the posting engine reads this table.
 */
@Entity(
    tableName = "gst_filing_periods",
    indices = [Index("companyId")]
)
data class GstFilingPeriodEntity(
    @PrimaryKey val filingPeriodId: String,
    val companyId: String,
    val periodLabel: String,
    val startDate: String,
    val endDate: String,
    val isLocked: Boolean,
    val lockedAt: Long?,
    val lockedBy: String?
)

/**
 * A thin, additive extension of an existing [LedgerEntity] (Phase 7A) - one row per Party,
 * always pointing at exactly one pre-existing ledger under the standard Sundry Debtors/Creditors
 * group. GSTIN/PAN/address/bank fields deliberately stay on [LedgerEntity], never duplicated here;
 * this table adds only what a plain ledger doesn't carry: role, entity type, credit limit, and
 * payment terms.
 */
@Entity(
    tableName = "parties",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["ledgerId"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("companyId"), Index(value = ["ledgerId"], unique = true)]
)
data class PartyEntity(
    @PrimaryKey val partyId: String,
    val companyId: String,
    val ledgerId: String,
    val role: PartyRole,
    val entityType: PartyEntityType,
    val displayName: String,
    val contactName: String,
    val creditLimitPaise: Long?,
    val paymentTermsType: PaymentTermsType,
    val paymentTermsCustomDays: Int?,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A pre-posting document (Phase 7A) - see [com.example.accounting.domain.invoice.Invoice] for the
 * full rationale. [voucherId] is null while DRAFT (zero ledger/journal effect) and is set exactly
 * once, atomically alongside the posting call, by
 * [com.example.accounting.data.repository.AccountingRepository.postInvoice]. Deliberately no
 * declared foreign key on [voucherId]/[referenceInvoiceId] - same convention already used by
 * [VoucherEntity.referenceVoucherId] and [SettlementAllocationEntity.invoiceVoucherId] (a plain
 * indexed pointer, not an enforced FK, since the referenced row may not exist yet at the moment
 * this row is first inserted as a draft).
 */
@Entity(
    tableName = "invoices",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PartyEntity::class,
            parentColumns = ["partyId"],
            childColumns = ["partyId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("companyId"), Index("partyId"), Index(value = ["voucherId"], unique = true), Index("referenceInvoiceId")]
)
data class InvoiceEntity(
    @PrimaryKey val invoiceId: String,
    val companyId: String,
    val financialYearId: String,
    val invoiceType: InvoiceType,
    val invoiceNumber: String?,
    val partyId: String,
    val date: String, // YYYY-MM-DD
    val dueDate: String?, // YYYY-MM-DD
    val voucherId: String? = null,
    val referenceInvoiceId: String? = null,
    /** Phase 7B - the TradeDocument this Invoice was converted from (Sales Order -> Sales
     * Invoice, etc). Null when created directly. No declared FK - same convention as
     * [referenceInvoiceId]/[VoucherEntity.referenceVoucherId]. */
    val sourceTradeDocumentId: String? = null,
    val narration: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A draft-invoice line item (Phase 7A) - the pre-posting working copy of what becomes real
 * [JournalItemEntity]/[VoucherStockLineEntity]/[GstTransactionEntity] rows once the parent
 * [InvoiceEntity] is posted. Deleted (via `deleteLinesForInvoice`) once no longer needed as a
 * draft; posted data of record lives exclusively in the Voucher-side tables from that point on.
 */
@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["invoiceId"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("invoiceId")]
)
data class InvoiceLineEntity(
    @PrimaryKey val lineId: String,
    val invoiceId: String,
    val itemId: String,
    val itemName: String,
    val hsnSacCode: String,
    val quantityRaw: Long,
    val ratePaise: Long,
    val gstRatePercent: Double,
    val cessRatePercent: Double,
    val lineOrder: Int
)

/**
 * A non-posting trade document (Phase 7B) - see [com.example.accounting.domain.document.TradeDocument]
 * for the full rationale. Never creates any Ledger/JournalItem/Voucher row merely by existing;
 * [status] is a genuine stored field here (unlike [InvoiceEntity], whose status is always
 * derived) since there is no accounting state to keep in sync against.
 */
@Entity(
    tableName = "trade_documents",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["companyId"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PartyEntity::class,
            parentColumns = ["partyId"],
            childColumns = ["partyId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("companyId"), Index("partyId"), Index("sourceTradeDocumentId")]
)
data class TradeDocumentEntity(
    @PrimaryKey val tradeDocumentId: String,
    val companyId: String,
    val financialYearId: String,
    val documentType: DocumentType,
    val documentNumber: String,
    val partyId: String,
    val date: String, // YYYY-MM-DD
    val status: DocumentStatus,
    /** Self-referencing pointer to the TradeDocument this one was converted from. No declared FK -
     * same convention as [InvoiceEntity.referenceInvoiceId]. */
    val sourceTradeDocumentId: String? = null,
    val narration: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A [TradeDocumentEntity] line item (Phase 7B) - mirrors [InvoiceLineEntity]'s shape exactly.
 */
@Entity(
    tableName = "trade_document_lines",
    foreignKeys = [
        ForeignKey(
            entity = TradeDocumentEntity::class,
            parentColumns = ["tradeDocumentId"],
            childColumns = ["tradeDocumentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tradeDocumentId")]
)
data class TradeDocumentLineEntity(
    @PrimaryKey val lineId: String,
    val tradeDocumentId: String,
    val itemId: String,
    val itemName: String,
    val hsnSacCode: String,
    val quantityRaw: Long,
    val ratePaise: Long,
    val gstRatePercent: Double,
    val cessRatePercent: Double,
    val lineOrder: Int
)

/**
 * A company-scoped, versioned document template (Phase 7D) - see
 * [com.example.accounting.domain.rendering.DocumentTemplate] for the full versioning rationale.
 * [id] is the Room primary key (`"{templateId}_v{version}"`); [templateId] groups every version of
 * the same template lineage. Every row, once inserted, is never updated in place except for the
 * [status] flip from ACTIVE to ARCHIVED when superseded - [configJson]/[templateName]/[isDefault]
 * of an already-created version are otherwise immutable, which is what keeps a historical render
 * reproducible.
 */
@Entity(
    tableName = "document_templates",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId"), Index("templateId"), Index("companyId", "documentType")]
)
data class DocumentTemplateEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val companyId: String,
    val documentType: DocumentType,
    val templateName: String,
    val version: Int,
    val status: TemplateStatus,
    val isDefault: Boolean,
    val configJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** One [BusinessProfile] per companyId (Phase 7D) - document-branding identity, deliberately
 * separate from [CompanyEntity]'s authoritative statutory fields. */
@Entity(
    tableName = "business_profiles",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId", unique = true)]
)
data class BusinessProfileEntity(
    @PrimaryKey val businessProfileId: String,
    val companyId: String,
    val businessName: String,
    val legalName: String,
    @ColumnInfo(defaultValue = "'PROPRIETORSHIP'") val constitutionType: ConstitutionType = ConstitutionType.PROPRIETORSHIP,
    val address: String,
    @ColumnInfo(defaultValue = "''") val pinCode: String = "",
    @ColumnInfo(defaultValue = "''") val city: String = "",
    @ColumnInfo(defaultValue = "''") val state: String = "",
    @ColumnInfo(defaultValue = "''") val country: String = "",
    val phone: String,
    val email: String,
    val website: String,
    val gstin: String,
    val pan: String,
    @ColumnInfo(defaultValue = "''") val tan: String = "",
    @ColumnInfo(defaultValue = "''") val udyam: String = "",
    val logoAssetId: String?,
    val bankName: String,
    val bankAccountNumber: String,
    val bankIfsc: String,
    val bankBranch: String,
    val upiId: String,
    val qrCodeAssetId: String?,
    val signatureAssetId: String?,
    val termsAndConditions: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** One [IndividualProfile] per companyId (Phase 7D) - see
 * [com.example.accounting.domain.rendering.IndividualProfile] for the boundary with
 * [BusinessProfileEntity]. */
@Entity(
    tableName = "individual_profiles",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId", unique = true)]
)
data class IndividualProfileEntity(
    @PrimaryKey val individualProfileId: String,
    val companyId: String,
    val name: String,
    val address: String,
    @ColumnInfo(defaultValue = "''") val pinCode: String = "",
    @ColumnInfo(defaultValue = "''") val city: String = "",
    @ColumnInfo(defaultValue = "''") val state: String = "",
    @ColumnInfo(defaultValue = "''") val country: String = "",
    val pan: String,
    val phone: String,
    val email: String,
    val signatureAssetId: String?,
    val termsAndConditions: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** A reference to one binary branding asset (Phase 7D) - never the image bytes themselves, see
 * [com.example.accounting.domain.rendering.DocumentAsset]. */
@Entity(
    tableName = "document_assets",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId")]
)
data class DocumentAssetEntity(
    @PrimaryKey val assetId: String,
    val companyId: String,
    val type: DocumentAssetType,
    val storageReference: String,
    val checksum: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long
)

/** Logs which exact template version rendered a document (Phase 7D) - see
 * [com.example.accounting.domain.rendering.RenderedDocumentRecord] for why this exists instead of
 * a field on `InvoiceEntity`/`TradeDocumentEntity`. */
@Entity(
    tableName = "rendered_document_records",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId"), Index("documentId")]
)
data class RenderedDocumentRecordEntity(
    @PrimaryKey val recordId: String,
    val companyId: String,
    val documentId: String,
    val documentType: DocumentType,
    val templateId: String,
    val templateVersion: Int,
    val format: String,
    val storageReference: String?,
    val generatedAt: Long
)

/** Recurring Voucher Engine (Phase 7F, "B") - a TEMPLATE only, never posted directly. See
 * [com.example.accounting.domain.recurring.RecurringVoucherSchedule]. */
@Entity(
    tableName = "recurring_voucher_schedules",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId")]
)
data class RecurringVoucherScheduleEntity(
    @PrimaryKey val scheduleId: String,
    val companyId: String,
    val financialYearId: String,
    val name: String,
    val voucherType: VoucherType,
    val frequency: RecurringFrequency,
    val dayOfMonth: Int,
    val narration: String,
    val startDate: String,
    val endDate: String?,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "recurring_voucher_lines",
    foreignKeys = [ForeignKey(entity = RecurringVoucherScheduleEntity::class, parentColumns = ["scheduleId"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scheduleId")]
)
data class RecurringVoucherLineEntity(
    @PrimaryKey val lineId: String,
    val scheduleId: String,
    val ledgerId: String,
    val type: DrCr,
    val amountPaise: Long,
    val narration: String,
    val lineOrder: Int
)

/**
 * A generated, review-only candidate voucher (Phase 7F, "B", draft-first). Deliberately NOT the
 * `vouchers`/`journal_items` tables - nothing here is read by `generateTrialBalance`,
 * `generateBalanceSheet`, GST computation, or inventory movement, which is what makes "a draft has
 * no journal/ledger/balance/GST/inventory effect" a structural guarantee, not a convention. The
 * unique `(scheduleId, periodKey)` index is what guarantees a monthly automation cycle can never
 * generate a second candidate for the same period, even if the daily/monthly job runs more than
 * once for the same day - and since [PENDING_REVIEW]/[POSTED]/[DISCARDED] rows are never deleted
 * (see [com.example.accounting.domain.recurring.RecurringVoucherDraftStatus]), a period the user
 * has already decided on (posted OR discarded) is never re-proposed either.
 */
@Entity(
    tableName = "recurring_voucher_drafts",
    foreignKeys = [ForeignKey(entity = RecurringVoucherScheduleEntity::class, parentColumns = ["scheduleId"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId"), Index(value = ["scheduleId", "periodKey"], unique = true)]
)
data class RecurringVoucherDraftEntity(
    @PrimaryKey val draftId: String,
    val companyId: String,
    val scheduleId: String,
    val financialYearId: String,
    val periodKey: String,
    val voucherType: VoucherType,
    val date: String,
    val narration: String,
    val status: RecurringVoucherDraftStatus,
    val generatedVoucherId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "recurring_voucher_draft_lines",
    foreignKeys = [ForeignKey(entity = RecurringVoucherDraftEntity::class, parentColumns = ["draftId"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("draftId")]
)
data class RecurringVoucherDraftLineEntity(
    @PrimaryKey val draftLineId: String,
    val draftId: String,
    val ledgerId: String,
    val type: DrCr,
    val amountPaise: Long,
    val narration: String,
    val lineOrder: Int
)

/**
 * Phase 7J-B — Voucher Management application service: a review-only draft, structurally mirroring
 * [RecurringVoucherDraftEntity]/[RecurringVoucherDraftLineEntity]'s exact pattern (see
 * [com.example.accounting.application.voucher.VoucherDraft]'s doc comment for the full rationale).
 * Deliberately NOT the `vouchers`/`journal_items` tables - nothing here is read by
 * `generateTrialBalance`, `generateBalanceSheet`, GST computation, or inventory movement, so "a
 * draft has no journal/ledger/balance/GST/inventory effect" stays a structural guarantee. Unlike
 * `RecurringVoucherDraftEntity`, there is no unique-indexed `(scheduleId, periodKey)` slot - a
 * generic voucher draft is created explicitly by a user or by [com.example.accounting.application.ocr.OcrSuggestionService],
 * not by a periodic automation cycle, so there is no idempotency window to protect.
 */
@Entity(
    tableName = "voucher_drafts",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId")]
)
data class VoucherDraftEntity(
    @PrimaryKey val draftId: String,
    val companyId: String,
    val financialYearId: String,
    val voucherType: VoucherType,
    val date: String,
    val referenceNumber: String,
    val narration: String,
    val status: VoucherDraftStatus,
    val postedVoucherId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "voucher_draft_lines",
    foreignKeys = [ForeignKey(entity = VoucherDraftEntity::class, parentColumns = ["draftId"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("draftId")]
)
data class VoucherDraftLineEntity(
    @PrimaryKey val draftLineId: String,
    val draftId: String,
    val ledgerId: String,
    val type: DrCr,
    val amountPaise: Long,
    val narration: String,
    val lineOrder: Int
)

/**
 * Phase 7J-B — links an already-uploaded [DocumentAssetEntity] (referenced by id, never duplicated)
 * to an existing, real (already-posted) [VoucherEntity] as supporting evidence (e.g. a scanned
 * receipt image) - metadata only, zero accounting effect. Deliberately its own small join table
 * rather than repurposing [RenderedDocumentRecordEntity] (Phase 7D), which logs a *rendered output*
 * of a document, not an *attached input* to one - different semantics, kept separate.
 *
 * The `(voucherId, documentAssetId)` unique index (Phase 7J-B.2) makes an exact duplicate
 * attachment a DB-level impossibility, never just a UI/service-layer convention - the same asset
 * MAY still be attached to a *different* voucher (many-to-many), only the exact pair is unique.
 */
@Entity(
    tableName = "voucher_document_references",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId"), Index("voucherId"), Index(value = ["voucherId", "documentAssetId"], unique = true)]
)
data class VoucherDocumentReferenceEntity(
    @PrimaryKey val referenceId: String,
    val companyId: String,
    val voucherId: String,
    val documentAssetId: String,
    val createdAt: Long
)

/**
 * Phase 7J-B — real persistence for [com.example.accounting.domain.subscription.CompanySubscription]
 * (Phase 7J domain model, previously unpersisted). One row per company per financial year (the
 * unique `(companyId, financialYearId)` index enforces this), keyed by [financialYearId] - never a
 * raw date range - so paid validity is always derived from the referenced
 * [com.example.accounting.domain.financialyear.FinancialYear]'s own stored `startDate`/`endDate`,
 * never a hardcoded "1 Apr-31 Mar" literal anywhere in this entity or the service that reads it.
 * [entitlementsCsv] is a plain comma-joined `EntitlementFeature.name` list - parsed by the
 * repository-side `toDomain()` extension, matching [DocumentTemplateEntity.configJson]'s existing
 * "plain column + call-site parse" convention rather than adding a generic collection converter.
 */
@Entity(
    tableName = "company_subscriptions",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId"), Index(value = ["companyId", "financialYearId"], unique = true)]
)
data class CompanySubscriptionEntity(
    @PrimaryKey val subscriptionId: String,
    val companyId: String,
    val financialYearId: String,
    val planType: SubscriptionPlanType,
    val planName: String,
    val entitlementsCsv: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Phase 7J-B — real persistence for [com.example.accounting.domain.banking.BankUpiProfile] (Phase
 * 7G domain model, previously unpersisted). [UpiMetadata] is flattened onto this entity's `upi*`
 * columns, the same flattening convention [BusinessProfileEntity] already uses for its own bank/UPI
 * fields. [partyId] is null for the company's own profile, non-null when scoped to one
 * [PartyEntity]. This is settlement/contact metadata, deliberately outside the double-entry stream
 * - no [VoucherEntity]/[JournalItemEntity]/[LedgerEntity] foreign key anywhere in this table.
 */
@Entity(
    tableName = "bank_upi_profiles",
    foreignKeys = [ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("companyId"), Index("partyId")]
)
data class BankUpiProfileEntity(
    @PrimaryKey val bankUpiProfileId: String,
    val companyId: String,
    val partyId: String?,
    val bankName: String,
    val accountHolderName: String,
    val accountNumber: String,
    val ifscCode: String,
    val branchName: String,
    val upiId: String?,
    val upiPayeeName: String,
    val upiIsVerified: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Rule 33 (GST Return Dashboard & Filing Foundation) - one GST return's identity/period/scheme/
 * lifecycle. See [com.example.accounting.domain.taxation.gstreturn.GstReturn] for the full
 * rationale; this is its 1:1 persisted shape. Deliberately outside the double-entry stream - no
 * [VoucherEntity]/[JournalItemEntity] foreign key, since a return is a filing-workflow record, not
 * an accounting posting.
 */
@Entity(
    tableName = "gst_returns",
    foreignKeys = [
        ForeignKey(entity = CompanyEntity::class, parentColumns = ["companyId"], childColumns = ["companyId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FinancialYearEntity::class, parentColumns = ["financialYearId"], childColumns = ["financialYearId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("companyId"), Index("financialYearId"), Index(value = ["companyId", "periodKey", "returnType", "scheme"])]
)
data class GstReturnEntity(
    @PrimaryKey val gstReturnId: String,
    val companyId: String,
    val financialYearId: String,
    val fyCode: String,
    val quarter: String,
    val month: Int?,
    val periodKey: String,
    val scheme: GstScheme,
    val returnType: GstReturnType,
    val periodicity: GstReturnPeriodicity,
    val filingMode: GstFilingMode,
    val status: GstReturnStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val submittedAt: Long?,
    val acknowledgementNumber: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val latestRequestArtifactId: String?,
    val latestResponseArtifactId: String?,
    val schemaVersion: String,
    /** Phase 8A, Part 2 - see [com.example.accounting.domain.taxation.gstreturn.GstReturn.isNilReturn]. */
    @ColumnInfo(defaultValue = "0") val isNilReturn: Boolean = false
)

/**
 * Rule 33 - one versioned JSON artifact (request or response) belonging to a [GstReturnEntity].
 * See [com.example.accounting.domain.taxation.gstreturn.GstReturnArtifact]. Never updated in place -
 * every generate/import inserts a new row, preserving history (Section 16).
 */
@Entity(
    tableName = "gst_return_artifacts",
    foreignKeys = [ForeignKey(entity = GstReturnEntity::class, parentColumns = ["gstReturnId"], childColumns = ["gstReturnId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gstReturnId")]
)
data class GstReturnArtifactEntity(
    @PrimaryKey val artifactId: String,
    val gstReturnId: String,
    val artifactType: GstReturnArtifactType,
    val schemaVersion: String,
    val jsonContent: String,
    val createdAt: Long
)

/**
 * Rule 33 - one section's preparation/validation result within a [GstReturnEntity]. See
 * [com.example.accounting.domain.taxation.gstreturn.GstReturnSection]. [sectionKey] is caller-
 * supplied and generic - never a statutory GSTR table name invented by this foundation.
 */
@Entity(
    tableName = "gst_return_sections",
    foreignKeys = [ForeignKey(entity = GstReturnEntity::class, parentColumns = ["gstReturnId"], childColumns = ["gstReturnId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gstReturnId"), Index(value = ["gstReturnId", "sectionKey"], unique = true)]
)
data class GstReturnSectionEntity(
    @PrimaryKey val sectionId: String,
    val gstReturnId: String,
    val sectionKey: String,
    val status: GstReturnSectionStatus,
    val resultDataJson: String?,
    val errorsJson: String?,
    val updatedAt: Long
)

/**
 * Rule 33 - one online-filing attempt's history for a [GstReturnEntity]. See
 * [com.example.accounting.domain.taxation.gstreturn.GstReturnSubmission]. Every (re)submission
 * inserts a new row; [GstReturnEntity]'s own status/acknowledgement fields only ever reflect the
 * latest attempt.
 */
@Entity(
    tableName = "gst_return_submissions",
    foreignKeys = [ForeignKey(entity = GstReturnEntity::class, parentColumns = ["gstReturnId"], childColumns = ["gstReturnId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gstReturnId")]
)
data class GstReturnSubmissionEntity(
    @PrimaryKey val submissionId: String,
    val gstReturnId: String,
    val attemptNumber: Int,
    val requestArtifactId: String?,
    val responseArtifactId: String?,
    val status: GstReturnStatus,
    val acknowledgementNumber: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val submittedAt: Long,
    val respondedAt: Long?
)
