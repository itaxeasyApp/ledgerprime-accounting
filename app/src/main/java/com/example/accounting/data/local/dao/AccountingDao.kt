package com.example.accounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.accounting.application.voucher.VoucherDraftStatus
import com.example.accounting.core.common.DrCr
import com.example.accounting.data.local.entity.AccountingPeriodEntity
import com.example.accounting.data.local.entity.AuditLogEntity
import com.example.accounting.data.local.entity.BankUpiProfileEntity
import com.example.accounting.data.local.entity.BranchEntity
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.data.local.entity.CompanySubscriptionEntity
import com.example.accounting.data.local.entity.VoucherDocumentReferenceEntity
import com.example.accounting.data.local.entity.VoucherDraftEntity
import com.example.accounting.data.local.entity.VoucherDraftLineEntity
import com.example.accounting.data.local.entity.FinancialYearEntity
import com.example.accounting.data.local.entity.GroupEntity
import com.example.accounting.data.local.entity.GstFilingPeriodEntity
import com.example.accounting.data.local.entity.GstReturnArtifactEntity
import com.example.accounting.data.local.entity.GstReturnEntity
import com.example.accounting.data.local.entity.GstReturnSectionEntity
import com.example.accounting.data.local.entity.GstReturnSubmissionEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.InvoiceEntity
import com.example.accounting.data.local.entity.InvoiceLineEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.OutboxSyncEntity
import com.example.accounting.data.local.entity.BusinessProfileEntity
import com.example.accounting.data.local.entity.DocumentAssetEntity
import com.example.accounting.data.local.entity.DocumentTemplateEntity
import com.example.accounting.data.local.entity.IndividualProfileEntity
import com.example.accounting.data.local.entity.PartyEntity
import com.example.accounting.data.local.entity.RecurringVoucherDraftEntity
import com.example.accounting.data.local.entity.RecurringVoucherDraftLineEntity
import com.example.accounting.data.local.entity.RecurringVoucherLineEntity
import com.example.accounting.data.local.entity.RecurringVoucherScheduleEntity
import com.example.accounting.domain.recurring.RecurringVoucherDraftStatus
import com.example.accounting.data.local.entity.RenderedDocumentRecordEntity
import com.example.accounting.data.local.entity.SettlementAllocationEntity
import com.example.accounting.data.local.entity.StockItemEntity
import com.example.accounting.data.local.entity.StockMovementEntity
import com.example.accounting.data.local.entity.TradeDocumentEntity
import com.example.accounting.data.local.entity.TradeDocumentLineEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.rendering.ConstitutionType
import com.example.accounting.domain.rendering.DocumentAssetType
import com.example.accounting.domain.rendering.TemplateStatus
import kotlinx.coroutines.flow.Flow

/** Plain query-projection POJO (Phase 7J-B.2), not a `@Entity` - the joined-row shape
 * [AccountingDao.getVoucherAttachments] returns, one row per [VoucherDocumentReferenceEntity] +
 * its linked [DocumentAssetEntity] metadata (never the file bytes). */
data class VoucherAttachmentRow(
    val referenceId: String,
    val voucherId: String,
    val documentAssetId: String,
    val type: DocumentAssetType,
    val storageReference: String,
    val checksum: String,
    val mimeType: String,
    val sizeBytes: Long,
    val attachedAt: Long
)

@Dao
interface AccountingDao {

    // ==================== COMPANY (TENANT BOUNDARY) ====================
    @Query("SELECT * FROM companies ORDER BY name ASC")
    fun getAllCompanies(): Flow<List<CompanyEntity>>

    @Query("SELECT * FROM companies WHERE companyId = :companyId LIMIT 1")
    suspend fun getCompanyById(companyId: String): CompanyEntity?

    @Query("SELECT * FROM companies WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultCompany(): CompanyEntity?

    @Query("SELECT COUNT(*) FROM companies")
    suspend fun getCompanyCount(): Int

    @Query("SELECT * FROM companies ORDER BY name ASC")
    suspend fun getAllCompaniesSnapshot(): List<CompanyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompany(company: CompanyEntity)

    @Update
    suspend fun updateCompany(company: CompanyEntity)

    // Single atomic statement so exactly one company is ever isDefault=1 at a time - no
    // read-then-write race between clearing the old default and setting the new one.
    @Query("UPDATE companies SET isDefault = CASE WHEN companyId = :companyId THEN 1 ELSE 0 END")
    suspend fun setDefaultCompany(companyId: String)

    // Every company-scoped entity's foreign key to `companies` is declared CASCADE (see
    // Entities.kt), and Room enables SQLite foreign-key enforcement (AppDatabase.kt), so this
    // single delete cascades through branches/ledgers/vouchers/stock/parties/invoices/etc. -
    // no manual per-table cleanup needed.
    @Query("DELETE FROM companies WHERE companyId = :companyId")
    suspend fun deleteCompany(companyId: String)

    // ==================== BRANCHES ====================
    @Query("SELECT * FROM branches WHERE companyId = :companyId ORDER BY isHeadOffice DESC, name ASC")
    fun getBranchesByCompany(companyId: String): Flow<List<BranchEntity>>

    @Query("SELECT * FROM branches WHERE companyId = :companyId AND branchId = :branchId LIMIT 1")
    suspend fun getBranchById(companyId: String, branchId: String): BranchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBranch(branch: BranchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBranches(branches: List<BranchEntity>)

    // ==================== FINANCIAL YEAR ====================
    @Query("SELECT * FROM financial_years WHERE companyId = :companyId ORDER BY startDate DESC")
    fun getFinancialYearsByCompany(companyId: String): Flow<List<FinancialYearEntity>>

    @Query("SELECT * FROM financial_years WHERE companyId = :companyId AND isCurrent = 1 LIMIT 1")
    suspend fun getCurrentFinancialYear(companyId: String): FinancialYearEntity?

    @Query("SELECT * FROM financial_years WHERE companyId = :companyId AND financialYearId = :fyId LIMIT 1")
    suspend fun getFinancialYearById(companyId: String, fyId: String): FinancialYearEntity?

    @Query("SELECT * FROM financial_years WHERE financialYearId = :fyId LIMIT 1")
    suspend fun getFinancialYearById(fyId: String): FinancialYearEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFinancialYear(fy: FinancialYearEntity)

    @Update
    suspend fun updateFinancialYear(fy: FinancialYearEntity)

    @Query("UPDATE financial_years SET isLocked = 1, lockedBy = :lockedBy, lockedAt = :lockedAt WHERE companyId = :companyId AND financialYearId = :fyId")
    suspend fun lockFinancialYear(companyId: String, fyId: String, lockedBy: String, lockedAt: Long = System.currentTimeMillis())

    // ==================== ACCOUNTING PERIODS ====================
    @Query("SELECT * FROM accounting_periods WHERE companyId = :companyId AND financialYearId = :fyId ORDER BY startDate ASC")
    fun getPeriodsByFinancialYear(companyId: String, fyId: String): Flow<List<AccountingPeriodEntity>>

    @Query("SELECT * FROM accounting_periods WHERE financialYearId = :fyId ORDER BY startDate ASC")
    fun getPeriodsByFinancialYear(fyId: String): Flow<List<AccountingPeriodEntity>>

    @Query("SELECT * FROM accounting_periods WHERE companyId = :companyId AND periodId = :periodId LIMIT 1")
    suspend fun getPeriodById(companyId: String, periodId: String): AccountingPeriodEntity?

    @Query("SELECT * FROM accounting_periods WHERE periodId = :periodId LIMIT 1")
    suspend fun getPeriodById(periodId: String): AccountingPeriodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriods(periods: List<AccountingPeriodEntity>)

    @Query("UPDATE accounting_periods SET status = :status, lockedBy = :lockedBy, lockedAt = :lockedAt WHERE companyId = :companyId AND periodId = :periodId")
    suspend fun setPeriodStatus(companyId: String, periodId: String, status: PeriodStatus, lockedBy: String?, lockedAt: Long? = System.currentTimeMillis())

    @Query("UPDATE accounting_periods SET status = :status, lockedAt = :timestamp, lockedBy = :user WHERE periodId = :periodId")
    suspend fun updatePeriodStatus(periodId: String, status: PeriodStatus, timestamp: Long?, user: String?)

    // ==================== GROUPS ====================
    @Query("SELECT * FROM account_groups WHERE companyId = :companyId ORDER BY displayOrder ASC, name ASC")
    fun getGroupsByCompany(companyId: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM account_groups WHERE companyId = :companyId AND groupId = :groupId LIMIT 1")
    suspend fun getGroupById(companyId: String, groupId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<GroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Query("DELETE FROM account_groups WHERE companyId = :companyId AND groupId = :groupId")
    suspend fun deleteGroup(companyId: String, groupId: String): Int

    // ==================== LEDGERS ====================
    @Query("SELECT * FROM ledgers WHERE companyId = :companyId ORDER BY name ASC")
    fun getLedgersByCompany(companyId: String): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledgers WHERE companyId = :companyId AND isActive = 1 ORDER BY name ASC")
    fun getActiveLedgersByCompany(companyId: String): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledgers WHERE companyId = :companyId AND ledgerId = :ledgerId LIMIT 1")
    suspend fun getLedgerById(companyId: String, ledgerId: String): LedgerEntity?

    @Query("SELECT * FROM ledgers WHERE companyId = :companyId AND groupId = :groupId ORDER BY name ASC")
    suspend fun getLedgersByGroupId(companyId: String, groupId: String): List<LedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgers(ledgers: List<LedgerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedger(ledger: LedgerEntity)

    @Update
    suspend fun updateLedger(ledger: LedgerEntity)

    @Query("UPDATE ledgers SET currentBalancePaise = :balancePaise, currentBalanceType = :balanceType WHERE companyId = :companyId AND ledgerId = :ledgerId")
    suspend fun updateLedgerBalance(companyId: String, ledgerId: String, balancePaise: Long, balanceType: DrCr)

    @Query("DELETE FROM ledgers WHERE companyId = :companyId AND ledgerId = :ledgerId AND isSystem = 0 AND ledgerId NOT LIKE 'LED_SYS_%' AND (SELECT COUNT(*) FROM journal_items WHERE companyId = :companyId AND ledgerId = :ledgerId) = 0")
    suspend fun deleteLedgerSafely(companyId: String, ledgerId: String): Int

    @Query("DELETE FROM ledgers WHERE companyId = :companyId AND ledgerId = :ledgerId")
    suspend fun deleteLedger(companyId: String, ledgerId: String): Int

    @Query("SELECT COUNT(*) FROM journal_items WHERE companyId = :companyId AND ledgerId = :ledgerId")
    suspend fun countTransactionsForLedger(companyId: String, ledgerId: String): Int

    @Query("SELECT COUNT(*) FROM journal_items WHERE companyId = :companyId AND ledgerId = :ledgerId")
    suspend fun countJournalEntriesForLedger(companyId: String, ledgerId: String): Int

    // ==================== VOUCHERS ====================
    @Query("SELECT * FROM vouchers WHERE companyId = :companyId ORDER BY date DESC, createdAt DESC")
    fun getVouchersByCompany(companyId: String): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers WHERE companyId = :companyId ORDER BY date DESC, createdAt DESC")
    fun getAllVouchersByCompany(companyId: String): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers WHERE companyId = :companyId AND financialYearId = :fyId ORDER BY date DESC, createdAt DESC")
    fun getVouchersByFinancialYear(companyId: String, fyId: String): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers WHERE companyId = :companyId AND date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    fun getVouchersByDateRange(companyId: String, startDate: String, endDate: String): Flow<List<VoucherEntity>>

    @Query("SELECT * FROM vouchers WHERE companyId = :companyId AND voucherId = :voucherId LIMIT 1")
    suspend fun getVoucherById(companyId: String, voucherId: String): VoucherEntity?

    @Query("SELECT COUNT(*) FROM vouchers WHERE companyId = :companyId AND financialYearId = :fyId AND voucherType = :type")
    suspend fun getVoucherCountByType(companyId: String, fyId: String, type: VoucherType): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucher(voucher: VoucherEntity)

    @Update
    suspend fun updateVoucher(voucher: VoucherEntity)

    @Query("UPDATE vouchers SET isCancelled = 1, updatedAt = :updatedAt WHERE companyId = :companyId AND voucherId = :voucherId")
    suspend fun cancelVoucher(companyId: String, voucherId: String, updatedAt: Long)

    @Query("DELETE FROM vouchers WHERE companyId = :companyId AND voucherId = :voucherId")
    suspend fun deleteVoucher(companyId: String, voucherId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM vouchers WHERE companyId = :companyId AND financialYearId = :financialYearId AND voucherNumber = :voucherNumber LIMIT 1)")
    suspend fun isVoucherNumberTaken(companyId: String, financialYearId: String, voucherNumber: String): Boolean

    // ==================== JOURNAL ITEMS ====================
    @Query("SELECT * FROM journal_items WHERE voucherId = :voucherId ORDER BY lineOrder ASC")
    fun getJournalItemsByVoucher(voucherId: String): Flow<List<JournalItemEntity>>

    @Query("SELECT * FROM journal_items WHERE companyId = :companyId AND financialYearId = :fyId ORDER BY lineOrder ASC")
    fun getAllJournalItems(companyId: String, fyId: String): Flow<List<JournalItemEntity>>

    /** Architecture correction (Opening Balance/FY fix) - every journal item ever posted for this
     * company, across ALL financial years, unlike [getAllJournalItems] which is scoped to one FY.
     * Used exclusively by [com.example.accounting.data.repository.AccountingRepository.generateTrialBalance]
     * to correctly carry a Balance-Sheet-nature ledger's balance forward into any FY after its
     * first one - the single stored [com.example.accounting.data.local.entity.LedgerEntity.openingBalancePaise]
     * alone is only ever correct for that ledger's very first FY. */
    @Query("SELECT * FROM journal_items WHERE companyId = :companyId ORDER BY lineOrder ASC")
    fun getAllJournalItemsForCompany(companyId: String): Flow<List<JournalItemEntity>>

    @Query("SELECT * FROM journal_items WHERE voucherId = :voucherId ORDER BY lineOrder ASC")
    suspend fun getJournalItemsForVoucherSync(voucherId: String): List<JournalItemEntity>

    @Query("SELECT * FROM journal_items WHERE companyId = :companyId AND ledgerId = :ledgerId ORDER BY itemId ASC")
    suspend fun getJournalItemsByLedger(companyId: String, ledgerId: String): List<JournalItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJournalItems(items: List<JournalItemEntity>)

    @Query("DELETE FROM journal_items WHERE voucherId = :voucherId")
    suspend fun deleteJournalItemsByVoucher(voucherId: String)

    // ==================== STOCK ITEMS ====================
    @Query("SELECT * FROM stock_items WHERE companyId = :companyId ORDER BY name ASC")
    fun getStockItemsByCompany(companyId: String): Flow<List<StockItemEntity>>

    @Query("SELECT * FROM stock_items WHERE companyId = :companyId AND itemId = :itemId LIMIT 1")
    suspend fun getStockItemById(companyId: String, itemId: String): StockItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItem(stockItem: StockItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockItems(items: List<StockItemEntity>)

    @Query("UPDATE stock_items SET currentQuantity = :newQuantity WHERE companyId = :companyId AND itemId = :itemId")
    suspend fun updateStockQuantity(companyId: String, itemId: String, newQuantity: Long)

    @Query("UPDATE stock_items SET currentQuantity = :newQuantity, currentAvgCostPaise = :newAvgCostPaise WHERE companyId = :companyId AND itemId = :itemId")
    suspend fun updateStockCache(companyId: String, itemId: String, newQuantity: Long, newAvgCostPaise: Long)

    // ==================== VOUCHER STOCK LINES ====================
    @Query("SELECT * FROM voucher_stock_lines WHERE voucherId = :voucherId ORDER BY lineOrder ASC")
    suspend fun getStockLinesForVoucher(voucherId: String): List<VoucherStockLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucherStockLines(lines: List<VoucherStockLineEntity>)

    // ==================== STOCK MOVEMENTS (immutable ledger) ====================
    @Query("SELECT * FROM stock_movements WHERE companyId = :companyId AND itemId = :itemId ORDER BY date ASC, createdAt ASC")
    suspend fun getStockMovementsForItem(companyId: String, itemId: String): List<StockMovementEntity>

    @Query("SELECT * FROM stock_movements WHERE voucherId = :voucherId ORDER BY createdAt ASC")
    suspend fun getStockMovementsForVoucher(voucherId: String): List<StockMovementEntity>

    @Query("SELECT * FROM stock_movements WHERE companyId = :companyId AND financialYearId = :fyId ORDER BY date ASC, createdAt ASC")
    suspend fun getStockMovementsForCompanyFY(companyId: String, fyId: String): List<StockMovementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockMovement(movement: StockMovementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockMovements(movements: List<StockMovementEntity>)

    // ==================== AUDIT LOGS ====================
    @Query("SELECT * FROM audit_logs WHERE companyId = :companyId ORDER BY timestamp DESC LIMIT 200")
    fun getAuditLogsByCompany(companyId: String): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLogEntity)

    // ==================== OUTBOX SYNC ====================
    @Query("SELECT * FROM outbox_sync WHERE companyId = :companyId ORDER BY createdAt ASC")
    fun getOutboxQueue(companyId: String): Flow<List<OutboxSyncEntity>>

    @Query("SELECT * FROM outbox_sync WHERE companyId = :companyId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getOutboxItemsByCompany(companyId: String, limit: Int = 100): List<OutboxSyncEntity>

    @Query("SELECT * FROM outbox_sync WHERE companyId = :companyId AND syncState IN ('PENDING', 'FAILED') ORDER BY createdAt ASC LIMIT :batchSize")
    suspend fun getPendingOutboxBatch(companyId: String, batchSize: Int = 20): List<OutboxSyncEntity>

    @Query("SELECT * FROM outbox_sync WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getOutboxByIdempotencyKey(key: String): OutboxSyncEntity?

    @Query("SELECT COUNT(*) FROM outbox_sync WHERE companyId = :companyId AND syncState = 'PENDING'")
    fun getPendingSyncCount(companyId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_sync WHERE companyId = :companyId AND syncState = 'PENDING'")
    suspend fun countPendingOutbox(companyId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxSync(item: OutboxSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxItem(item: OutboxSyncEntity)

    @Update
    suspend fun updateOutboxItem(item: OutboxSyncEntity)

    @Query("UPDATE outbox_sync SET syncState = :state, retryCount = :retries, lastError = :error, updatedAt = :updatedAt WHERE syncId = :syncId")
    suspend fun updateOutboxStatus(syncId: String, state: SyncState, retries: Int, error: String?, updatedAt: Long)

    @Query("UPDATE outbox_sync SET syncState = :status, retryCount = :retryCount, lastError = :lastError, updatedAt = :updatedAt WHERE syncId = :syncId")
    suspend fun updateOutboxSyncStatus(syncId: String, status: SyncState, retryCount: Int, lastError: String?, updatedAt: Long)

    @Query("UPDATE outbox_sync SET syncState = 'SYNCED', updatedAt = :timestamp WHERE syncId IN (:syncIds)")
    suspend fun markOutboxSynced(syncIds: List<String>, timestamp: Long)

    @Query("DELETE FROM outbox_sync WHERE syncId = :syncId")
    suspend fun deleteOutboxItem(syncId: String)

    @Query("DELETE FROM outbox_sync WHERE syncState = 'SYNCED'")
    suspend fun clearSyncedOutbox()

    // ==================== GST TRANSACTIONS (Phase 5) ====================
    @Query("SELECT * FROM gst_transactions WHERE voucherId = :voucherId ORDER BY lineOrder ASC")
    suspend fun getGstTransactionsForVoucher(voucherId: String): List<GstTransactionEntity>

    @Query("SELECT * FROM gst_transactions WHERE companyId = :companyId AND financialYearId = :fyId ORDER BY createdAt ASC")
    suspend fun getGstTransactionsForCompanyFY(companyId: String, fyId: String): List<GstTransactionEntity>

    /** D1b - fetches every line of ONE business transaction by its [GstTransactionEntity.transactionGroupId],
     * company-scoped to prevent cross-tenant lookup. The only way to find a GST-only transaction's
     * lines (voucherId is always null there) - used to build a GST-only Credit/Debit Note against
     * the original GST-only Sale/Purchase. */
    @Query("SELECT * FROM gst_transactions WHERE companyId = :companyId AND transactionGroupId = :groupId ORDER BY lineOrder ASC")
    suspend fun getGstTransactionsByGroupId(companyId: String, groupId: String): List<GstTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGstTransactions(transactions: List<GstTransactionEntity>)

    @Query("DELETE FROM gst_transactions WHERE voucherId = :voucherId")
    suspend fun deleteGstTransactionsByVoucher(voucherId: String)

    // ==================== SETTLEMENT ALLOCATIONS (Phase 5) ====================
    @Query("SELECT * FROM settlement_allocations WHERE invoiceVoucherId = :invoiceVoucherId")
    suspend fun getAllocationsForInvoice(invoiceVoucherId: String): List<SettlementAllocationEntity>

    @Query("SELECT * FROM settlement_allocations WHERE settlementVoucherId = :settlementVoucherId")
    suspend fun getAllocationsForSettlement(settlementVoucherId: String): List<SettlementAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlementAllocations(allocations: List<SettlementAllocationEntity>)

    // ==================== GST FILING PERIODS (Phase 5, isolated from accounting periods) ====================
    @Query("SELECT * FROM gst_filing_periods WHERE companyId = :companyId ORDER BY startDate ASC")
    fun getGstFilingPeriodsByCompany(companyId: String): Flow<List<GstFilingPeriodEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGstFilingPeriod(period: GstFilingPeriodEntity)

    @Query("UPDATE gst_filing_periods SET isLocked = :isLocked, lockedAt = :lockedAt, lockedBy = :lockedBy WHERE companyId = :companyId AND filingPeriodId = :filingPeriodId")
    suspend fun setGstFilingPeriodLock(companyId: String, filingPeriodId: String, isLocked: Boolean, lockedAt: Long?, lockedBy: String?)

    // ==================== PARTIES (Phase 7A) ====================
    @Query("SELECT * FROM parties WHERE companyId = :companyId AND isActive = 1 ORDER BY displayName ASC")
    fun getPartiesByCompany(companyId: String): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE companyId = :companyId AND role = :role AND isActive = 1 ORDER BY displayName ASC")
    fun getPartiesByRole(companyId: String, role: PartyRole): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE companyId = :companyId AND partyId = :partyId LIMIT 1")
    suspend fun getPartyById(companyId: String, partyId: String): PartyEntity?

    @Query("SELECT * FROM parties WHERE companyId = :companyId AND ledgerId = :ledgerId LIMIT 1")
    suspend fun getPartyByLedgerId(companyId: String, ledgerId: String): PartyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParty(party: PartyEntity)

    @Update
    suspend fun updateParty(party: PartyEntity)

    // ==================== INVOICES (Phase 7A) ====================
    @Query("SELECT * FROM invoices WHERE companyId = :companyId ORDER BY date DESC, createdAt DESC")
    fun getInvoicesByCompany(companyId: String): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE companyId = :companyId AND partyId = :partyId ORDER BY date DESC, createdAt DESC")
    fun getInvoicesByParty(companyId: String, partyId: String): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE companyId = :companyId AND invoiceId = :invoiceId LIMIT 1")
    suspend fun getInvoiceById(companyId: String, invoiceId: String): InvoiceEntity?

    @Query("SELECT * FROM invoices WHERE voucherId = :voucherId LIMIT 1")
    suspend fun getInvoiceByVoucherId(voucherId: String): InvoiceEntity?

    @Query("SELECT COUNT(*) FROM invoices WHERE companyId = :companyId AND financialYearId = :fyId AND invoiceType = :invoiceType")
    suspend fun getInvoiceCountByType(companyId: String, fyId: String, invoiceType: InvoiceType): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: InvoiceEntity)

    @Update
    suspend fun updateInvoice(invoice: InvoiceEntity)

    // Phase 7B: only ever sets voucherId - invoiceNumber is assigned once at draft-creation time
    // (see AccountingRepository.createDraftInvoice/generateNextDocumentNumber) and is never
    // overwritten by posting.
    @Query("UPDATE invoices SET voucherId = :voucherId, updatedAt = :updatedAt WHERE companyId = :companyId AND invoiceId = :invoiceId")
    suspend fun linkInvoiceToVoucher(companyId: String, invoiceId: String, voucherId: String, updatedAt: Long)

    @Query("DELETE FROM invoices WHERE companyId = :companyId AND invoiceId = :invoiceId AND voucherId IS NULL")
    suspend fun deleteDraftInvoice(companyId: String, invoiceId: String): Int

    // ==================== INVOICE LINES (Phase 7A) ====================
    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :invoiceId ORDER BY lineOrder ASC")
    suspend fun getLinesForInvoice(invoiceId: String): List<InvoiceLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoiceLines(lines: List<InvoiceLineEntity>)

    @Query("DELETE FROM invoice_lines WHERE invoiceId = :invoiceId")
    suspend fun deleteLinesForInvoice(invoiceId: String)

    // ==================== TRADE DOCUMENTS (Phase 7B) ====================
    @Query("SELECT * FROM trade_documents WHERE companyId = :companyId ORDER BY date DESC, createdAt DESC")
    fun getTradeDocumentsByCompany(companyId: String): Flow<List<TradeDocumentEntity>>

    @Query("SELECT * FROM trade_documents WHERE companyId = :companyId AND documentType = :documentType ORDER BY date DESC, createdAt DESC")
    fun getTradeDocumentsByType(companyId: String, documentType: DocumentType): Flow<List<TradeDocumentEntity>>

    @Query("SELECT * FROM trade_documents WHERE companyId = :companyId AND tradeDocumentId = :tradeDocumentId LIMIT 1")
    suspend fun getTradeDocumentById(companyId: String, tradeDocumentId: String): TradeDocumentEntity?

    @Query("SELECT COUNT(*) FROM trade_documents WHERE companyId = :companyId AND financialYearId = :fyId AND documentType = :documentType")
    suspend fun getTradeDocumentCountByType(companyId: String, fyId: String, documentType: DocumentType): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTradeDocument(document: TradeDocumentEntity)

    @Update
    suspend fun updateTradeDocument(document: TradeDocumentEntity)

    @Query("DELETE FROM trade_documents WHERE companyId = :companyId AND tradeDocumentId = :tradeDocumentId")
    suspend fun deleteTradeDocument(companyId: String, tradeDocumentId: String): Int

    @Query("SELECT * FROM trade_document_lines WHERE tradeDocumentId = :tradeDocumentId ORDER BY lineOrder ASC")
    suspend fun getLinesForTradeDocument(tradeDocumentId: String): List<TradeDocumentLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTradeDocumentLines(lines: List<TradeDocumentLineEntity>)

    @Query("DELETE FROM trade_document_lines WHERE tradeDocumentId = :tradeDocumentId")
    suspend fun deleteLinesForTradeDocument(tradeDocumentId: String)

    // ==================== DOCUMENT TEMPLATES (Phase 7D) ====================
    @Query("SELECT * FROM document_templates WHERE companyId = :companyId AND documentType = :documentType AND status = 'ACTIVE' ORDER BY templateName ASC")
    fun getActiveTemplatesByType(companyId: String, documentType: DocumentType): Flow<List<DocumentTemplateEntity>>

    @Query("SELECT * FROM document_templates WHERE companyId = :companyId AND templateId = :templateId AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTemplateVersion(companyId: String, templateId: String): DocumentTemplateEntity?

    @Query("SELECT * FROM document_templates WHERE companyId = :companyId AND templateId = :templateId AND version = :version LIMIT 1")
    suspend fun getTemplateVersion(companyId: String, templateId: String, version: Int): DocumentTemplateEntity?

    @Query("SELECT * FROM document_templates WHERE companyId = :companyId AND documentType = :documentType AND isDefault = 1 AND status = 'ACTIVE' LIMIT 1")
    suspend fun getDefaultTemplate(companyId: String, documentType: DocumentType): DocumentTemplateEntity?

    @Query("SELECT MAX(version) FROM document_templates WHERE companyId = :companyId AND templateId = :templateId")
    suspend fun getMaxTemplateVersion(companyId: String, templateId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentTemplate(template: DocumentTemplateEntity)

    @Query("UPDATE document_templates SET status = :status WHERE companyId = :companyId AND templateId = :templateId AND version = :version")
    suspend fun setTemplateStatus(companyId: String, templateId: String, version: Int, status: TemplateStatus)

    @Query("UPDATE document_templates SET isDefault = 0 WHERE companyId = :companyId AND documentType = :documentType")
    suspend fun clearDefaultTemplateFlag(companyId: String, documentType: DocumentType)

    // ==================== BUSINESS / INDIVIDUAL PROFILES (Phase 7D) ====================
    @Query("SELECT * FROM business_profiles WHERE companyId = :companyId LIMIT 1")
    suspend fun getBusinessProfile(companyId: String): BusinessProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBusinessProfile(profile: BusinessProfileEntity)

    // Explicit companyId-scoped UPDATE with individually-named parameters (rather than a plain
    // @Update keyed only by primary key) - defense-in-depth so this row can never be updated under
    // the wrong tenant even if a future caller ever passed a mismatched companyId/businessProfileId
    // pair. Room does not support `:entity.field` POJO-property binding in this project's version,
    // hence the explicit parameter list instead of passing the whole entity.
    @Query(
        """
        UPDATE business_profiles SET
            businessName = :businessName, legalName = :legalName, constitutionType = :constitutionType, address = :address,
            pinCode = :pinCode, city = :city, state = :state, country = :country,
            phone = :phone, email = :email, website = :website,
            gstin = :gstin, pan = :pan, tan = :tan, udyam = :udyam, logoAssetId = :logoAssetId,
            bankName = :bankName, bankAccountNumber = :bankAccountNumber, bankIfsc = :bankIfsc,
            bankBranch = :bankBranch, upiId = :upiId, qrCodeAssetId = :qrCodeAssetId,
            signatureAssetId = :signatureAssetId, termsAndConditions = :termsAndConditions,
            updatedAt = :updatedAt
        WHERE companyId = :companyId AND businessProfileId = :businessProfileId
        """
    )
    suspend fun updateBusinessProfile(
        companyId: String, businessProfileId: String, businessName: String, legalName: String,
        constitutionType: ConstitutionType, address: String, pinCode: String, city: String, state: String, country: String,
        phone: String, email: String, website: String, gstin: String, pan: String, tan: String, udyam: String, logoAssetId: String?,
        bankName: String, bankAccountNumber: String, bankIfsc: String, bankBranch: String, upiId: String,
        qrCodeAssetId: String?, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
    )

    @Query("SELECT * FROM individual_profiles WHERE companyId = :companyId LIMIT 1")
    suspend fun getIndividualProfile(companyId: String): IndividualProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIndividualProfile(profile: IndividualProfileEntity)

    // Same defense-in-depth reasoning as updateBusinessProfile above.
    @Query(
        """
        UPDATE individual_profiles SET
            name = :name, address = :address, pinCode = :pinCode, city = :city, state = :state, country = :country,
            pan = :pan, phone = :phone,
            email = :email, signatureAssetId = :signatureAssetId,
            termsAndConditions = :termsAndConditions, updatedAt = :updatedAt
        WHERE companyId = :companyId AND individualProfileId = :individualProfileId
        """
    )
    suspend fun updateIndividualProfile(
        companyId: String, individualProfileId: String, name: String, address: String,
        pinCode: String, city: String, state: String, country: String, pan: String,
        phone: String, email: String, signatureAssetId: String?, termsAndConditions: String, updatedAt: Long
    )

    // ==================== DOCUMENT ASSETS (Phase 7D) ====================
    @Query("SELECT * FROM document_assets WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun getDocumentAssetsByCompany(companyId: String): Flow<List<DocumentAssetEntity>>

    @Query("SELECT * FROM document_assets WHERE companyId = :companyId AND assetId = :assetId LIMIT 1")
    suspend fun getDocumentAssetById(companyId: String, assetId: String): DocumentAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentAsset(asset: DocumentAssetEntity)

    /** Narrow rollback primitive (Phase 7J-B.2 Slice 2) - NOT a general "delete an asset" feature
     * (deliberately not built yet, see [com.example.accounting.application.voucher.VoucherManagementServiceImpl]'s
     * doc comments). Its only caller is [com.example.accounting.presentation.viewmodel.AccountingViewModel.attachDocumentToVoucher]'s
     * failure path, cleaning up a just-created, never-successfully-linked [DocumentAssetEntity] row
     * so a failed attach never leaves an orphaned asset behind. Company-scoped like every delete in
     * this app. */
    @Query("DELETE FROM document_assets WHERE companyId = :companyId AND assetId = :assetId")
    suspend fun deleteDocumentAsset(companyId: String, assetId: String): Int

    // ==================== RENDERED DOCUMENT RECORDS (Phase 7D) ====================
    @Query("SELECT * FROM rendered_document_records WHERE companyId = :companyId AND documentId = :documentId ORDER BY generatedAt DESC")
    fun getRenderedDocumentRecords(companyId: String, documentId: String): Flow<List<RenderedDocumentRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRenderedDocumentRecord(record: RenderedDocumentRecordEntity)

    // ==================== RECURRING VOUCHER ENGINE (Phase 7F) ====================
    @Query("SELECT * FROM recurring_voucher_schedules WHERE companyId = :companyId AND isActive = 1")
    fun getActiveRecurringVoucherSchedules(companyId: String): Flow<List<RecurringVoucherScheduleEntity>>

    @Query("SELECT * FROM recurring_voucher_schedules WHERE companyId = :companyId AND scheduleId = :scheduleId LIMIT 1")
    suspend fun getRecurringVoucherScheduleById(companyId: String, scheduleId: String): RecurringVoucherScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringVoucherSchedule(schedule: RecurringVoucherScheduleEntity)

    @Query("SELECT * FROM recurring_voucher_lines WHERE scheduleId = :scheduleId ORDER BY lineOrder ASC")
    suspend fun getLinesForRecurringVoucherSchedule(scheduleId: String): List<RecurringVoucherLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringVoucherLines(lines: List<RecurringVoucherLineEntity>)

    @Query("SELECT * FROM recurring_voucher_drafts WHERE scheduleId = :scheduleId AND periodKey = :periodKey LIMIT 1")
    suspend fun getRecurringVoucherDraftForPeriod(scheduleId: String, periodKey: String): RecurringVoucherDraftEntity?

    @Query("SELECT * FROM recurring_voucher_drafts WHERE companyId = :companyId AND status = :status")
    fun getRecurringVoucherDraftsByStatus(companyId: String, status: RecurringVoucherDraftStatus): Flow<List<RecurringVoucherDraftEntity>>

    @Query("SELECT * FROM recurring_voucher_drafts WHERE companyId = :companyId AND draftId = :draftId LIMIT 1")
    suspend fun getRecurringVoucherDraftById(companyId: String, draftId: String): RecurringVoucherDraftEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecurringVoucherDraft(draft: RecurringVoucherDraftEntity)

    @Update
    suspend fun updateRecurringVoucherDraft(draft: RecurringVoucherDraftEntity)

    @Query("SELECT * FROM recurring_voucher_draft_lines WHERE draftId = :draftId ORDER BY lineOrder ASC")
    suspend fun getLinesForRecurringVoucherDraft(draftId: String): List<RecurringVoucherDraftLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringVoucherDraftLines(lines: List<RecurringVoucherDraftLineEntity>)

    @Query("DELETE FROM recurring_voucher_draft_lines WHERE draftId = :draftId")
    suspend fun deleteLinesForRecurringVoucherDraft(draftId: String)

    // ==================== Phase 7J-B: Voucher Management (draft entity) ====================
    @Query("SELECT * FROM voucher_drafts WHERE companyId = :companyId AND draftId = :draftId LIMIT 1")
    suspend fun getVoucherDraftById(companyId: String, draftId: String): VoucherDraftEntity?

    @Query("SELECT * FROM voucher_drafts WHERE companyId = :companyId AND status = :status")
    fun getVoucherDraftsByStatus(companyId: String, status: VoucherDraftStatus): Flow<List<VoucherDraftEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVoucherDraft(draft: VoucherDraftEntity)

    @Update
    suspend fun updateVoucherDraft(draft: VoucherDraftEntity)

    @Query("SELECT * FROM voucher_draft_lines WHERE draftId = :draftId ORDER BY lineOrder ASC")
    suspend fun getLinesForVoucherDraft(draftId: String): List<VoucherDraftLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucherDraftLines(lines: List<VoucherDraftLineEntity>)

    @Query("DELETE FROM voucher_draft_lines WHERE draftId = :draftId")
    suspend fun deleteLinesForVoucherDraft(draftId: String)

    /** IGNORE, not REPLACE: the `(voucherId, documentAssetId)` unique index (Phase 7J-B.2) means a
     * duplicate insert would otherwise either crash (ABORT) or silently reassign a fresh
     * [VoucherDocumentReferenceEntity.referenceId]/[VoucherDocumentReferenceEntity.createdAt] to an
     * existing link (REPLACE) - IGNORE keeps the original row untouched, matching the service
     * layer's explicit idempotent-on-duplicate contract in [com.example.accounting.application.voucher.VoucherManagementServiceImpl.attachDocumentReference]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVoucherDocumentReference(reference: VoucherDocumentReferenceEntity)

    @Query("SELECT * FROM voucher_document_references WHERE companyId = :companyId AND voucherId = :voucherId")
    suspend fun getDocumentReferencesForVoucher(companyId: String, voucherId: String): List<VoucherDocumentReferenceEntity>

    /** Unlink only (Phase 7J-B.2) - deletes exactly one `voucher_document_references` row, scoped by
     * [companyId] so a reference can never be removed by guessing another company's [referenceId].
     * NEVER deletes [DocumentAssetEntity] or the underlying file - see
     * [com.example.accounting.application.voucher.VoucherManagementServiceImpl.removeDocumentReference].
     * Returns the number of rows deleted (0 = not found / wrong company) so the caller can
     * distinguish "removed" from "nothing to remove". */
    @Query("DELETE FROM voucher_document_references WHERE companyId = :companyId AND referenceId = :referenceId")
    suspend fun deleteVoucherDocumentReference(companyId: String, referenceId: String): Int

    /** One joined query (Phase 7J-B.2), never N+1 - a future attachments UI can render a voucher's
     * full attachment list (thumbnail-relevant metadata included) from a single call. */
    @Query(
        """
        SELECT vdr.referenceId AS referenceId, vdr.voucherId AS voucherId, da.assetId AS documentAssetId,
               da.type AS type, da.storageReference AS storageReference, da.checksum AS checksum,
               da.mimeType AS mimeType, da.sizeBytes AS sizeBytes, vdr.createdAt AS attachedAt
        FROM voucher_document_references vdr
        INNER JOIN document_assets da ON da.assetId = vdr.documentAssetId
        WHERE vdr.companyId = :companyId AND vdr.voucherId = :voucherId
        ORDER BY vdr.createdAt DESC
        """
    )
    suspend fun getVoucherAttachments(companyId: String, voucherId: String): List<VoucherAttachmentRow>

    // ==================== Phase 7J-B: Subscription/Entitlements ====================
    @Query("SELECT * FROM company_subscriptions WHERE companyId = :companyId AND financialYearId = :financialYearId LIMIT 1")
    suspend fun getSubscriptionForCompanyAndFy(companyId: String, financialYearId: String): CompanySubscriptionEntity?

    @Query("SELECT * FROM company_subscriptions WHERE companyId = :companyId")
    fun getSubscriptionsForCompany(companyId: String): Flow<List<CompanySubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubscription(subscription: CompanySubscriptionEntity)

    @Update
    suspend fun updateSubscription(subscription: CompanySubscriptionEntity)

    // ==================== Phase 7J-B: Bank/UPI Profile ====================
    @Query("SELECT * FROM bank_upi_profiles WHERE companyId = :companyId")
    fun getBankUpiProfilesForCompany(companyId: String): Flow<List<BankUpiProfileEntity>>

    @Query("SELECT * FROM bank_upi_profiles WHERE companyId = :companyId AND partyId = :partyId")
    fun getBankUpiProfilesForParty(companyId: String, partyId: String): Flow<List<BankUpiProfileEntity>>

    @Query("SELECT * FROM bank_upi_profiles WHERE companyId = :companyId AND bankUpiProfileId = :bankUpiProfileId LIMIT 1")
    suspend fun getBankUpiProfileById(companyId: String, bankUpiProfileId: String): BankUpiProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBankUpiProfile(profile: BankUpiProfileEntity)

    @Update
    suspend fun updateBankUpiProfile(profile: BankUpiProfileEntity)

    @Query("DELETE FROM bank_upi_profiles WHERE companyId = :companyId AND bankUpiProfileId = :bankUpiProfileId")
    suspend fun deleteBankUpiProfile(companyId: String, bankUpiProfileId: String): Int

    // ==================== Rule 33: GST Return Dashboard & Filing Foundation ====================
    @Query("SELECT * FROM gst_returns WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun getGstReturnsForCompany(companyId: String): Flow<List<GstReturnEntity>>

    @Query("SELECT * FROM gst_returns WHERE companyId = :companyId AND gstReturnId = :gstReturnId LIMIT 1")
    suspend fun getGstReturnById(companyId: String, gstReturnId: String): GstReturnEntity?

    @Query("SELECT * FROM gst_returns WHERE companyId = :companyId AND periodKey = :periodKey AND returnType = :returnType AND scheme = :scheme LIMIT 1")
    suspend fun findGstReturn(companyId: String, periodKey: String, returnType: String, scheme: String): GstReturnEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGstReturn(gstReturn: GstReturnEntity)

    @Update
    suspend fun updateGstReturn(gstReturn: GstReturnEntity)

    @Query("SELECT * FROM gst_return_artifacts WHERE gstReturnId = :gstReturnId ORDER BY createdAt ASC")
    suspend fun getArtifactsForGstReturn(gstReturnId: String): List<GstReturnArtifactEntity>

    @Query("SELECT * FROM gst_return_artifacts WHERE artifactId = :artifactId LIMIT 1")
    suspend fun getGstReturnArtifactById(artifactId: String): GstReturnArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGstReturnArtifact(artifact: GstReturnArtifactEntity)

    @Query("SELECT * FROM gst_return_sections WHERE gstReturnId = :gstReturnId ORDER BY sectionKey ASC")
    suspend fun getSectionsForGstReturn(gstReturnId: String): List<GstReturnSectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGstReturnSection(section: GstReturnSectionEntity)

    /** Phase 8A, Part 2 - cleans up a return's own STALE section rows whose key is no longer part
     * of the current build (e.g. a return PREPAREd before Part 1's real-statutory-table rebuild
     * still carries old "B2C"/"NIL_EXEMPT" rows that upsert alone would never remove, since it only
     * ever adds/updates the keys the CURRENT build actually returns). Scoped to one gstReturnId and
     * an explicit keep-list - never a blanket delete. */
    @Query("DELETE FROM gst_return_sections WHERE gstReturnId = :gstReturnId AND sectionKey NOT IN (:keepKeys)")
    suspend fun deleteGstReturnSectionsNotIn(gstReturnId: String, keepKeys: List<String>)

    @Query("SELECT * FROM gst_return_submissions WHERE gstReturnId = :gstReturnId ORDER BY attemptNumber ASC")
    suspend fun getSubmissionsForGstReturn(gstReturnId: String): List<GstReturnSubmissionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGstReturnSubmission(submission: GstReturnSubmissionEntity)
}
