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
        // rejection belongs here, not only in the dialog. Exact groupId-prefix check, matching the
        // UI's own filter (CreateVoucherDialog.kt) and this project's "classify by ID, never by
        // name" rule - never a full ancestor walk, since Bank/Cash ledgers are always created with
        // that groupId directly, not several levels of nesting down.
        if (voucher.voucherType == VoucherType.CONTRA) {
            for (item in items) {
                val ledger = dao.getLedgerById(voucher.companyId, item.ledgerId)
                val isCashOrBank = ledger != null && (ledger.groupId.startsWith("GRP_BANK_") || ledger.groupId.startsWith("GRP_CASH_"))
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
     * Cancels a posted voucher via a compensating reversal (Rule 12 - Deletion Policy).
     * Posted vouchers are NEVER physically deleted; original journal items are never touched.
     * 0. Idempotent replay guard.
     * 1. Rejects if already cancelled (no double reversal).
     * 2. Inserts opposite-sign Journal Items reversing every original line.
     * 3. Reverses ledger balance mutations using the same delta helper as posting.
     * 4. Marks the voucher isCancelled = true.
     * 5. Appends Audit Log (CANCEL_VOUCHER).
     * 6. Enqueues Outbox cancellation entry.
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

        // 1. Reject double-cancellation
        if (voucher.isCancelled) {
            throw AccountingTransactionException(
                AppError.BusinessRuleViolation("Voucher ${voucher.voucherNumber} is already cancelled.")
            )
        }

        val originalItems = dao.getJournalItemsForVoucherSync(voucherId)
        var nextLineOrder = (originalItems.maxOfOrNull { it.lineOrder } ?: 0) + 1

        // 2 & 3. Insert compensating reversal lines and reverse ledger balances
        val reversalItems = mutableListOf<JournalItemEntity>()
        for (item in originalItems) {
            val reversedType = if (item.type == DrCr.DEBIT) DrCr.CREDIT else DrCr.DEBIT

            reversalItems += JournalItemEntity(
                itemId = UUID.randomUUID().toString(),
                voucherId = item.voucherId,
                companyId = item.companyId,
                financialYearId = item.financialYearId,
                ledgerId = item.ledgerId,
                type = reversedType,
                amountPaise = item.amountPaise,
                narration = "Reversal: cancellation of voucher ${voucher.voucherNumber}",
                lineOrder = nextLineOrder++
            )

            val ledger = dao.getLedgerById(companyId, item.ledgerId)
            if (ledger != null) {
                val (newBalancePaise, newBalanceType) = applyLedgerDelta(ledger, reversedType, item.amountPaise)
                dao.updateLedgerBalance(companyId, ledger.ledgerId, newBalancePaise, newBalanceType)
            }
        }
        dao.insertJournalItems(reversalItems)

        // 3.5. Audit fix (accounting-flow audit) - reverse this voucher's GST facts too, if it had
        // any (Sale/Purchase/Credit-Debit Note). Previously cancellation only reversed
        // journal_items/stock movements, leaving gst_transactions untouched - a cancelled GST-
        // bearing voucher's tax liability stayed fully counted in GSTSummaryReport/filing figures
        // forever. Mirrors TradingWorkflowEngine.buildNote's own negation pattern exactly: NEGATED
        // taxable/CGST/SGST/IGST/CESS at the SAME direction as the original (never a new opposite-
        // direction transaction - the standard GST-return representation), never a mutation of the
        // original rows (Rule 12 - append-only), same voucherId as the cancelled voucher.
        val originalGstTransactions = dao.getGstTransactionsForVoucher(voucherId)
        val reversalGstTransactions = if (originalGstTransactions.isNotEmpty()) {
            val reversalGroupId = UUID.randomUUID().toString()
            originalGstTransactions.mapIndexed { index, gt ->
                gt.copy(
                    gstTransactionId = UUID.randomUUID().toString(),
                    taxableAmountPaise = -gt.taxableAmountPaise,
                    cgstPaise = -gt.cgstPaise,
                    sgstPaise = -gt.sgstPaise,
                    igstPaise = -gt.igstPaise,
                    cessPaise = -gt.cessPaise,
                    lineOrder = index + 1,
                    createdAt = System.currentTimeMillis(),
                    transactionGroupId = reversalGroupId
                )
            }.also { dao.insertGstTransactions(it) }
        } else {
            emptyList()
        }

        // 4. Mark voucher cancelled (append-only; header row itself is updated, never deleted)
        dao.cancelVoucher(companyId, voucherId, System.currentTimeMillis())

        // 5. Audit Log
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = financialYearId,
                action = AuditAction.CANCEL_VOUCHER,
                entityType = "VOUCHER",
                entityId = voucherId,
                description = "Cancelled voucher ${voucher.voucherNumber} via compensating reversal of ${originalItems.size} line(s)",
                performedBy = userId,
                timestamp = System.currentTimeMillis(),
                payloadJson = "{\"voucherId\":\"$voucherId\",\"idempotencyKey\":\"$idempotencyKey\"}"
            )
        )

        // 6. Outbox cancellation record - complete SyncEvent (Phase 6), carrying the compensating
        // reversal lines so the server can apply the exact same reversal, not just a bare voucherId.
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
                        journalLines = reversalItems.map {
                            SyncJournalLineDto(it.itemId, it.ledgerId, "", it.type.name, it.amountPaise, it.narration, it.lineOrder)
                        },
                        gstTransactions = reversalGstTransactions.map {
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

        // 7. Inventory (Phase 4, additive) - reverses stock movements the same compensating way;
        // a no-op if this voucher never had any stock lines.
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
