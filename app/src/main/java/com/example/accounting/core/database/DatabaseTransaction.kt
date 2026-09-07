package com.example.accounting.core.database

import androidx.room.withTransaction
import com.example.accounting.core.common.AppError
import com.example.accounting.core.common.DrCr
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.AuditLogEntity
import com.example.accounting.data.local.entity.GstTransactionEntity
import com.example.accounting.data.local.entity.JournalItemEntity
import com.example.accounting.data.local.entity.LedgerEntity
import com.example.accounting.data.local.entity.OutboxSyncEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.audit.AuditAction
import com.example.accounting.domain.inventory.engine.InventoryEngine
import com.example.accounting.domain.sync.SyncAggregateType
import com.example.accounting.domain.sync.SyncEvent
import com.example.accounting.domain.sync.SyncEventSerializer
import com.example.accounting.domain.sync.SyncGstTransactionDto
import com.example.accounting.domain.sync.SyncJournalLineDto
import com.example.accounting.domain.sync.SyncOperation
import com.example.accounting.domain.sync.SyncStockLineDto
import com.example.accounting.domain.sync.SyncVoucherDto
import com.example.accounting.domain.sync.toPostOperation
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Carries a typed [AppError] out of the posting/cancellation engine so callers can map the
 * failure back to the exact domain error instead of a generic exception.
 */
class AccountingTransactionException(val appError: AppError) : Exception(appError.message)

/**
 * Single authoritative posting/cancellation engine, expressed purely in terms of [AccountingDao]
 * suspend calls with no dependency on Room's `RoomDatabase`/`withTransaction`. [DatabaseTransaction]
 * wraps these in a real atomic Room transaction for production use; the same functions can be
 * invoked directly against a fake [AccountingDao] in JVM unit tests (see `Phase2TestSuite`) to
 * verify the guard/business logic without requiring a Robolectric-backed Room instance.
 */
internal object VoucherPostingEngine {

    /**
     * Computes the new signed ledger balance after applying a Dr/Cr delta.
     * Shared by posting and cancellation so both mutate balances identically.
     */
    fun applyLedgerDelta(ledger: LedgerEntity, type: DrCr, amountPaise: Long): Pair<Long, DrCr> {
        val currentSignedPaise = if (ledger.currentBalanceType == DrCr.DEBIT) {
            ledger.currentBalancePaise
        } else {
            -ledger.currentBalancePaise
        }
        val deltaSignedPaise = if (type == DrCr.DEBIT) amountPaise else -amountPaise
        val newSignedPaise = currentSignedPaise + deltaSignedPaise
        val newBalancePaise = kotlin.math.abs(newSignedPaise)
        val newBalanceType = if (newSignedPaise >= 0) DrCr.DEBIT else DrCr.CREDIT
        return newBalancePaise to newBalanceType
    }

    /**
     * Posts a voucher (single authoritative posting path):
     * 0. Idempotent replay guard - if this idempotencyKey was already processed, no-op success.
     * 1. Duplicate voucher-number guard within company + financial year.
     * 2. Inserts Voucher header.
     * 3. Inserts Journal Items.
     * 4. Updates Ledger current balances.
     * 5. Appends Audit Log (POST_VOUCHER).
     * 6. Enqueues Outbox sync entry with idempotency key.
     * 7. (Phase 4, additive) If [stockLines] is non-empty, applies them via [InventoryEngine] -
     *    a parallel system to the journal/ledger steps above, which it never modifies. Existing
     *    callers passing no stock lines (the default) see byte-for-byte identical behavior.
     */
    suspend fun post(
        dao: AccountingDao,
        voucher: VoucherEntity,
        items: List<JournalItemEntity>,
        idempotencyKey: String,
        userId: String,
        stockLines: List<VoucherStockLineEntity> = emptyList(),
        gstTransactions: List<GstTransactionEntity> = emptyList()
    ) {
        // 0. Idempotent replay guard
        if (dao.getOutboxByIdempotencyKey(idempotencyKey) != null) {
            return
        }

        // 1. Duplicate voucher-number guard
        if (dao.isVoucherNumberTaken(voucher.companyId, voucher.financialYearId, voucher.voucherNumber)) {
            throw AccountingTransactionException(
                AppError.DuplicateVoucherNumber(voucher.voucherNumber, voucher.financialYearId)
            )
        }

        // 1.5. Contra domain enforcement (Phase 5, Priority 6) - the UI already filters its ledger
        // picker to Cash/Bank, but that alone doesn't stop a Contra voucher from reaching a
        // non-Cash/Bank ledger through any other caller (tests, a future API, direct repository
        // use). VoucherPostingEngine.post() is the single authoritative posting path, so the
        // rejection belongs here, not only in the dialog. Architecture correction (real Group
        // hierarchy) - a proper ancestor walk via StandardSystemGroups.isUnder, not a flat
        // groupId-prefix check, so a company-created User Group nested under System Bank
        // Accounts/Cash-in-Hand (now reachable via the Group creation UI) is correctly recognized
        // too, matching the UI's own filter (CreateVoucherDialog.kt).
        if (voucher.voucherType == VoucherType.CONTRA) {
            val groupsById = dao.getGroupsByCompany(voucher.companyId).first().associate {
                it.groupId to com.example.accounting.domain.accounting.AccountGroup(it.groupId, it.companyId, it.name, it.primaryGroup, it.parentGroupId, it.isSystem, it.affectsGrossProfit, it.displayOrder)
            }
            for (item in items) {
                val ledger = dao.getLedgerById(voucher.companyId, item.ledgerId)
                // Direct-match check kept as a guaranteed fast path (matches every ledger filed
                // straight under the System group, the common case) alongside the ancestor walk
                // (covers a ledger filed under a User Group nested further down) - never only one.
                // Bank/Bank-OD collision fix (live-device audit finding) - a raw
                // `startsWith("GRP_BANK_")` also matched "GRP_BANK_OD_..." (Bank OD is a LIABILITY,
                // "GRP_BANK_OD" itself starts with "GRP_BANK_"), letting a Bank OD/loan ledger
                // through as if it were an ordinary Cash/Bank asset ledger for Contra transfers -
                // isExactSystemGroup resolves the longest matching bare group id first, so
                // "GRP_BANK_OD_x" now correctly fails this check.
                val isCashOrBank = ledger != null && (
                    StandardSystemGroups.isExactSystemGroup(ledger.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
                        StandardSystemGroups.isExactSystemGroup(ledger.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
                        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById) ||
                        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
                    )
                if (!isCashOrBank) {
                    throw AccountingTransactionException(
                        AppError.InvalidContraLedger(ledger?.name ?: item.ledgerId)
                    )
                }
            }
        }

        // 2. Insert voucher
        dao.insertVoucher(voucher)

        // 3. Insert journal lines
        dao.insertJournalItems(items)

        // 4. Update balances for each affected ledger
        for (item in items) {
            val ledger = dao.getLedgerById(voucher.companyId, item.ledgerId)
            if (ledger != null) {
                val (newBalancePaise, newBalanceType) = applyLedgerDelta(ledger, item.type, item.amountPaise)
                dao.updateLedgerBalance(voucher.companyId, ledger.ledgerId, newBalancePaise, newBalanceType)
            }
        }

        // 5. Audit Log
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = voucher.companyId,
                financialYearId = voucher.financialYearId,
                action = AuditAction.POST_VOUCHER,
                entityType = "VOUCHER",
                entityId = voucher.voucherId,
                description = "Posted voucher ${voucher.voucherNumber} (${voucher.voucherType}) for amount ₹${voucher.totalAmountPaise / 100.0}",
                performedBy = userId,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{\"voucherId\":\"${voucher.voucherId}\",\"idempotencyKey\":\"$idempotencyKey\"}"
            )
        )

        // 6. Enqueue outbox item - a complete, versioned SyncEvent (Phase 6, Priority 6.4), not the
        // previous narrow `{"voucherNumber":...,"amount":...}` string. Built from the same items/
        // stockLines/gstTransactions this function already received, so no extra DB read is needed.
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(),
                companyId = voucher.companyId,
                entityType = "VOUCHER",
                entityId = voucher.voucherId,
                operation = "INSERT",
                payloadJson = SyncEventSerializer.toJson(
                    SyncEvent(
                        eventId = UUID.randomUUID().toString(),
                        idempotencyKey = idempotencyKey,
                        companyId = voucher.companyId,
                        financialYearId = voucher.financialYearId,
                        operation = voucher.voucherType.toPostOperation().name,
                        aggregateType = SyncAggregateType.VOUCHER.name,
                        aggregateId = voucher.voucherId,
                        voucher = SyncVoucherDto(
                            voucherId = voucher.voucherId, voucherNumber = voucher.voucherNumber,
                            voucherType = voucher.voucherType.name, date = voucher.date,
                            referenceNumber = voucher.referenceNumber, narration = voucher.narration,
                            totalAmountPaise = voucher.totalAmountPaise, isCancelled = voucher.isCancelled,
                            createdBy = voucher.createdBy, partyGstin = voucher.partyGstin,
                            isGstApplicable = voucher.isGstApplicable, referenceVoucherId = voucher.referenceVoucherId,
                            paymentMode = voucher.paymentMode
                        ),
                        journalLines = items.map {
                            SyncJournalLineDto(it.itemId, it.ledgerId, "", it.type.name, it.amountPaise, it.narration, it.lineOrder)
                        },
                        stockLines = stockLines.map {
                            SyncStockLineDto(it.lineId, it.itemId, it.direction.name, it.quantityRaw, it.ratePaise, it.amountPaise, it.lineOrder)
                        },
                        gstTransactions = gstTransactions.map {
                            SyncGstTransactionDto(
                                it.gstTransactionId, it.voucherType.name, it.partyLedgerId, it.partyGstin, it.placeOfSupply, it.supplyType.name,
                                it.itemId, it.hsnSacCode, it.quantityRaw, it.taxableAmountPaise, it.gstRatePercent,
                                it.cgstPaise, it.sgstPaise, it.igstPaise, it.cessPaise, it.direction.name, it.lineOrder
                            )
                        }
                    )
                ),
                idempotencyKey = idempotencyKey,
                syncState = SyncState.PENDING,
                retryCount = 0,
                lastError = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // 7. Inventory (Phase 4, additive - no-op when stockLines is empty)
        if (stockLines.isNotEmpty()) {
            dao.insertVoucherStockLines(stockLines)
            InventoryEngine.applyStockLines(dao, voucher, stockLines, userId)
        }

        // 8. GST transaction facts (Phase 5, additive - no-op when gstTransactions is empty). Persisted
        // atomically alongside the voucher/journal/stock rows, the same way stockLines already are.
        if (gstTransactions.isNotEmpty()) {
            dao.insertGstTransactions(gstTransactions)
        }
    }

    /**
     * Real cancellation (explicit correction, not the earlier "Rule 12 compensating reversal"
     * design): per actual Indian accounting/GST practice, a voucher whose period has never been
     * reported to the government is genuinely cancelled/deleted - never left visible alongside a
     * same-voucher offsetting entry (that made a single cancelled "Sale Invoice" show both its
     * original lines AND a reversal of itself, which is not a real transaction and confused the
     * voucher's own detail view). [AccountingRepository.deleteVoucherSafely] already blocks this
     * whole function from ever running once the voucher's period has a PROCESSING/FILED GST return
     * - the only correct correction past that point is a real, separate Credit/Debit Note.
     * 0. Idempotent replay guard.
     * 1. Reverses ledger balance mutations using the same delta helper as posting (this math is
     *    unchanged from the old design - only proven-correct here, nothing new).
     * 2. Deletes the original Journal Items and GST transactions outright (never a same-voucher
     *    offsetting entry).
     * 3. Deletes the voucher row itself.
     * 4. Appends Audit Log (CANCEL_VOUCHER) - the real audit trail lives here, not in fabricated
     *    day-book entries.
     * 5. Enqueues Outbox deletion entry.
     * Stock movements (Phase 4, inventory-tracked vouchers only) still use their own existing
     * compensating-reversal path below (step 6) - reversing average-cost history safely on a true
     * delete is a materially different, higher-risk problem than reversing a ledger balance, and is
     * deliberately out of scope for this pass.
     */
    suspend fun cancel(
        dao: AccountingDao,
        companyId: String,
        financialYearId: String,
        voucherId: String,
        idempotencyKey: String,
        userId: String
    ) {
        // 0. Idempotent replay guard
        if (dao.getOutboxByIdempotencyKey(idempotencyKey) != null) {
            return
        }

        val voucher = dao.getVoucherById(companyId, voucherId)
            ?: throw IllegalArgumentException("Voucher $voucherId not found")

        val originalItems = dao.getJournalItemsForVoucherSync(voucherId)

        // 1. Reverse ledger balances - same math as the old design, just never re-inserted as a
        // visible journal line afterward.
        for (item in originalItems) {
            val reversedType = if (item.type == DrCr.DEBIT) DrCr.CREDIT else DrCr.DEBIT
            val ledger = dao.getLedgerById(companyId, item.ledgerId)
            if (ledger != null) {
                val (newBalancePaise, newBalanceType) = applyLedgerDelta(ledger, reversedType, item.amountPaise)
                dao.updateLedgerBalance(companyId, ledger.ledgerId, newBalancePaise, newBalanceType)
            }
        }

        // 2. Delete the original Journal Items and GST transactions outright - the whole point is
        // that nothing about this voucher remains visible anywhere once it's gone.
        dao.deleteJournalItemsByVoucher(voucherId)
        dao.deleteGstTransactionsByVoucher(voucherId)

        // 3. Delete the voucher row itself - a genuine delete, not an isCancelled flag on a row
        // that still shows up everywhere.
        dao.deleteVoucher(companyId, voucherId)

        // 4. Audit Log - the real record that this happened, since the voucher/journal rows
        // themselves are now gone rather than left behind as a visible trail.
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = financialYearId,
                action = AuditAction.CANCEL_VOUCHER,
                entityType = "VOUCHER",
                entityId = voucherId,
                description = "Deleted voucher ${voucher.voucherNumber} (${originalItems.size} journal line(s) removed, ledger balances reversed)",
                performedBy = userId,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{\"voucherId\":\"$voucherId\",\"idempotencyKey\":\"$idempotencyKey\"}"
            )
        )

        // 5. Outbox deletion record - no live backend exists to sync to today (see
        // OutboxProcessor's own KDoc), so journalLines/gstTransactions are empty here rather than
        // re-sending data that no longer exists locally either.
        dao.insertOutboxItem(
            OutboxSyncEntity(
                syncId = UUID.randomUUID().toString(),
                companyId = companyId,
                entityType = "VOUCHER",
                entityId = voucherId,
                operation = "CANCEL",
                payloadJson = SyncEventSerializer.toJson(
                    SyncEvent(
                        eventId = UUID.randomUUID().toString(),
                        idempotencyKey = idempotencyKey,
                        companyId = companyId,
                        financialYearId = financialYearId,
                        operation = SyncOperation.CANCEL_VOUCHER.name,
                        aggregateType = SyncAggregateType.VOUCHER.name,
                        aggregateId = voucherId,
                        voucher = SyncVoucherDto(
                            voucherId = voucher.voucherId, voucherNumber = voucher.voucherNumber,
                            voucherType = voucher.voucherType.name, date = voucher.date,
                            referenceNumber = voucher.referenceNumber, narration = voucher.narration,
                            totalAmountPaise = voucher.totalAmountPaise, isCancelled = true,
                            createdBy = voucher.createdBy, partyGstin = voucher.partyGstin,
                            isGstApplicable = voucher.isGstApplicable, referenceVoucherId = voucher.referenceVoucherId,
                            paymentMode = voucher.paymentMode
                        ),
                        journalLines = emptyList(),
                        gstTransactions = emptyList()
                    )
                ),
                idempotencyKey = idempotencyKey,
                syncState = SyncState.PENDING,
                retryCount = 0,
                lastError = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // 6. Inventory (Phase 4, additive) - reverses stock movements the same compensating way;
        // a no-op if this voucher never had any stock lines. Deliberately unchanged (see this
        // function's own KDoc for why stock reversal keeps its existing, safer path for now).
        InventoryEngine.reverseStockMovements(dao, companyId, voucherId, voucher.date, userId)
    }
}

/**
 * Wraps [VoucherPostingEngine] in a real atomic Room transaction (Project Principle 3).
 * All balance updates, voucher/journal rows, outbox entries, and audit logs commit atomically
 * or roll back completely.
 */
class DatabaseTransaction(
    private val database: AppDatabase,
    private val dao: AccountingDao
) {

    /**
     * Executes arbitrary block inside Room database transaction
     */
    suspend fun <R> runAtomic(block: suspend () -> R): R {
        return database.withTransaction {
            block()
        }
    }

    suspend fun postVoucherAtomic(
        voucher: VoucherEntity,
        items: List<JournalItemEntity>,
        idempotencyKey: String = UUID.randomUUID().toString(),
        userId: String = "SYSTEM_USER",
        stockLines: List<VoucherStockLineEntity> = emptyList(),
        gstTransactions: List<GstTransactionEntity> = emptyList()
    ): Result<Unit> = runCatching {
        database.withTransaction {
            VoucherPostingEngine.post(dao, voucher, items, idempotencyKey, userId, stockLines, gstTransactions)
        }
    }

    /**
     * GST-only Sale (Architecture Checkpoint follow-up) - persists [gstTransactions] (every row's
     * `voucherId` already `null`, set by [com.example.accounting.domain.trading.TradingWorkflowEngine.buildGstOnlySale])
     * and enqueues [outboxItem] atomically. Deliberately does NOT call [VoucherPostingEngine.post] -
     * there is no [VoucherEntity], no [JournalItemEntity], and no ledger balance to touch for this
     * path, so none of that engine's steps apply. The idempotent-replay guard is duplicated here
     * (one `if` check, not a re-implementation of any posting logic) rather than routed through
     * [VoucherPostingEngine], since every other step of that engine is inapplicable.
     */
    suspend fun postGstOnlyTransactionsAtomic(
        gstTransactions: List<GstTransactionEntity>,
        outboxItem: OutboxSyncEntity
    ): Result<Unit> = runCatching {
        database.withTransaction {
            if (dao.getOutboxByIdempotencyKey(outboxItem.idempotencyKey) == null) {
                dao.insertGstTransactions(gstTransactions)
                dao.insertOutboxItem(outboxItem)
            }
        }
    }

    suspend fun cancelVoucherAtomic(
        companyId: String,
        financialYearId: String,
        voucherId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
        userId: String = "SYSTEM_USER"
    ): Result<Unit> = runCatching {
        database.withTransaction {
            VoucherPostingEngine.cancel(dao, companyId, financialYearId, voucherId, idempotencyKey, userId)
        }
    }
}
