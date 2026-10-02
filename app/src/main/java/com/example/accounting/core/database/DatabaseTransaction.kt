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
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.PurchaseDocumentIdentity
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Phase 8 Step 13 - rejects a Purchase whose supplier document (supplier + document number, see
 * [PurchaseDocumentIdentity]) is already booked, in the same company and financial year, by a live
 * (non-cancelled) purchase. Used by both posting paths (voucher-backed and GST-only), so the rule has a
 * single definition. A row with no document number has nothing to match on and is never rejected -
 * unknown is not a duplicate. Credit/Debit Notes are never compared (they carry no supplier document).
 */
internal object PurchaseDocumentGuard {
    suspend fun requireNoDuplicate(dao: AccountingDao, incoming: List<GstTransactionEntity>) {
        val purchases = incoming.filter { it.direction == GstDirection.INPUT && it.voucherType == VoucherType.PURCHASE }
        if (purchases.none { PurchaseDocumentIdentity.normalizeNumber(it.supplierDocumentNumber) != null }) return
        fun GstTransactionEntity.key() = PurchaseDocumentIdentity.duplicateKey(partyGstin, partyLedgerId, supplierDocumentNumber)
        val incomingGroups = purchases.map { it.transactionGroupId.ifBlank { it.voucherId ?: it.gstTransactionId } }.toSet()
        val incomingVouchers = purchases.mapNotNull { it.voucherId }.toSet()
        val first = purchases.first()
        val live = dao.getGstTransactionsForCompanyFY(first.companyId, first.financialYearId).filter {
            it.direction == GstDirection.INPUT && it.voucherType == VoucherType.PURCHASE &&
                it.transactionGroupId !in incomingGroups && (it.voucherId == null || it.voucherId !in incomingVouchers)
        }
        for (row in purchases) {
            val key = row.key() ?: continue
            val clash = live.firstOrNull { it.key() == key } ?: continue
            throw AccountingTransactionException(
                AppError.BusinessRuleViolation(
                    "Supplier document '${row.supplierDocumentNumber!!.trim()}' from this supplier is already booked in this financial year " +
                        "(${clash.voucherId ?: "GST-only purchase ${clash.transactionGroupId}"}). Cancel that purchase first if it was entered by mistake."
                )
            )
        }
    }
}

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

    private fun VoucherType.isGstNote() = this == VoucherType.CREDIT_NOTE || this == VoucherType.DEBIT_NOTE

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

        // 1.6. GST flag/fact agreement (Phase 8, B8) - a voucher flagged GST-applicable must carry
        // the GstTransaction facts GST reporting reads; the flag alone drives no report.
        if (voucher.isGstApplicable && gstTransactions.isEmpty()) {
            throw AccountingTransactionException(
                AppError.BusinessRuleViolation(
                    "Voucher ${voucher.voucherNumber} is flagged GST-applicable but carries no GST transaction rows."
                )
            )
        }

        // 1.65. One valid note per original (Phase 8, B6) - a Credit/Debit Note is a full mirror of
        // the voucher it references, so a second live one would reverse the same sale/purchase twice
        // in the ledgers, the GST rows and the stock at once (the engine writes all three together,
        // which is why rejecting it here, before anything is written, covers all three). A CANCELLED
        // note is out of the books and does not block - cancelling it is how a note is corrected.
        // Only notes are checked: `referenceVoucherId` is also how a corrected same-type repost is
        // linked to the voucher it corrects, and that must stay unaffected.
        if (voucher.voucherType.isGstNote() && voucher.referenceVoucherId != null) {
            val existing = dao.getAllVouchersByCompany(voucher.companyId).first().firstOrNull {
                it.referenceVoucherId == voucher.referenceVoucherId && it.voucherType.isGstNote() &&
                    !it.isCancelled && it.voucherId != voucher.voucherId
            }
            if (existing != null) {
                throw AccountingTransactionException(
                    AppError.BusinessRuleViolation(
                        "${existing.voucherType.displayName} ${existing.voucherNumber} already reverses voucher ${voucher.referenceVoucherId}. " +
                            "Only one valid Credit/Debit Note can be issued against a voucher - cancel ${existing.voucherNumber} first to issue a correction."
                    )
                )
            }
        }

        // 1.66. Duplicate purchase document (Phase 8, Step 13) - the same supplier's invoice number
        // booked twice in one financial year would claim its ITC twice.
        PurchaseDocumentGuard.requireNoDuplicate(dao, gstTransactions)

        // 1.7. GST period gate (Phase 8, B9) - a GST-bearing voucher may not be posted into a GST
        // filing period that is locked, or into a month/quarter whose GSTR-1/GSTR-3B is already
        // FILED (the books would change, the filed return would not). Outward supplies are gated
        // by GSTR-1 and GSTR-3B; inward supplies only by GSTR-3B.
        if (gstTransactions.isNotEmpty()) {
            val postingDate = runCatching { java.time.LocalDate.parse(voucher.date) }.getOrNull()
            if (postingDate != null) {
                val lockedPeriod = dao.getGstFilingPeriodsByCompany(voucher.companyId).first()
                    .firstOrNull { it.isLocked && voucher.date >= it.startDate && voucher.date <= it.endDate }
                if (lockedPeriod != null) {
                    throw AccountingTransactionException(
                        AppError.BusinessRuleViolation(
                            "GST filing period '${lockedPeriod.periodLabel}' is locked; a GST voucher cannot be posted on ${voucher.date}."
                        )
                    )
                }
                val gatingReturnTypes = buildSet {
                    add(GstReturnType.GSTR3B)
                    if (gstTransactions.any { it.direction == GstDirection.OUTPUT }) add(GstReturnType.GSTR1)
                }
                val quarter = GstQuarter.ofMonth(postingDate.monthValue).name
                val filedReturn = dao.getGstReturnsForCompany(voucher.companyId).first().firstOrNull {
                    it.status == GstReturnStatus.FILED && it.financialYearId == voucher.financialYearId &&
                        it.returnType in gatingReturnTypes &&
                        (it.month == postingDate.monthValue || (it.month == null && it.quarter == quarter))
                }
                if (filedReturn != null) {
                    throw AccountingTransactionException(
                        AppError.BusinessRuleViolation(
                            "${filedReturn.returnType.name} for period ${filedReturn.periodKey} is already filed; a GST voucher cannot be posted on ${voucher.date}."
                        )
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
                                it.cgstPaise, it.sgstPaise, it.igstPaise, it.cessPaise, it.direction.name, it.lineOrder,
                                chargeType = it.chargeType.name, supplyNature = it.supplyNature.name,
                                transactionGroupId = it.transactionGroupId.ifBlank { voucher.voucherId },
                                transactionDate = it.transactionDate,
                                partyGstRegistrationStatus = it.partyGstRegistrationStatus,
                                supplierDocumentNumber = it.supplierDocumentNumber, supplierDocumentDate = it.supplierDocumentDate
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
     * Auditable soft-cancellation (Step 3 device-testing fix - reverts a prior "real
     * cancellation"/hard-delete pass that silently broke three things at once: (a) a cancelled
     * voucher's own number became free for [AccountingRepository.generateNextVoucherNumber]'s
     * plain COUNT(*) to hand straight back out to the very next voucher of that type - live-
     * reproduced on device: correcting Receipt RCT-2026-0002 posted its replacement as ANOTHER
     * "RCT-2026-0002", two different real transactions sharing one number; (b) every place already
     * built to show a cancelled voucher - [VoucherDetailDialog]'s own "CANCELLED" badge, its
     * `correctedByVoucher`/`correctsOriginal` links, the Day Book's CANCELLED status - went
     * permanently unreachable, since the row it depends on no longer existed; (c) the
     * already-declared, already-correct [AccountingDao.cancelVoucher] soft-flag method sat
     * completely unused. None of that was a real "never leave a same-voucher offsetting entry
     * visible" problem (the actual, valid complaint about the still-earlier "Rule 12" design this
     * hard-delete itself replaced) - this restores the flag-and-keep approach WITHOUT reintroducing
     * that: no offsetting/reversal JournalItem is ever inserted anywhere, on this voucher or any
     * other; a cancelled voucher's own detail view still shows only its one original set of lines,
     * now with a CANCELLED badge instead of no longer existing at all.
     * [AccountingRepository.deleteVoucherSafely] already blocks this whole function from ever
     * running once the voucher's period has a PROCESSING/FILED GST return - the only correct
     * correction past that point is a real, separate Credit/Debit Note.
     * 0. Idempotent replay guard.
     * 1. Reverses ledger balance mutations using the same delta helper as posting (this math is
     *    unchanged from the old design - only proven-correct here, nothing new).
     * 2. Journal Items and GST transactions are left exactly as posted - never deleted, never
     *    offset by a new row - so the voucher's own detail view keeps showing real history. Every
     *    report/summary query that aggregates ACROSS vouchers (Trial Balance, P&L, Balance Sheet,
     *    Ledger Statement, GST Summary/GSTR/export) now excludes a cancelled voucher's rows itself
     *    at the DAO level (see [AccountingDao.getAllJournalItems]'s own comment) - so a cancellation
     *    still nets to zero everywhere it's supposed to, without erasing the rows themselves.
     * 3. Soft-cancels the voucher row via the existing [AccountingDao.cancelVoucher] (`isCancelled
     *    = 1`) - never a delete. `generateNextVoucherNumber`'s COUNT(*) now correctly keeps counting
     *    it, so its number can never be reissued to a different voucher.
     * 4. Appends Audit Log (CANCEL_VOUCHER) - a second, independent record of the same fact; the
     *    voucher row itself remains the primary one now that it isn't deleted.
     * 5. Enqueues Outbox deletion entry (sync-only concept; the local row itself is untouched).
     * Stock movements (Phase 4, inventory-tracked vouchers only) still use their own existing
     * compensating-reversal path below (step 6) - unrelated to this change.
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

        // Soft-cancel fix - the old hard-delete design got double-cancellation rejection "for
        // free" (a deleted row can never be found again, so the lookup above threw "not found").
        // Now that the row persists, that same protection must be explicit: without this guard, a
        // second cancel call (a genuinely different idempotency key, so the replay guard above
        // doesn't already catch it) would reverse this voucher's ledger balances a second time.
        if (voucher.isCancelled) {
            throw IllegalArgumentException("Voucher $voucherId is already cancelled")
        }

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

        // 2. Journal Items and GST transactions are left exactly as posted - see this function's
        // own KDoc for why (audit trail + report-query filtering, not a same-voucher offsetting
        // entry).

        // 3. Soft-cancel the voucher row via the existing isCancelled flag - never a delete, so its
        // number can never be reissued and its own detail view can still show it (CANCELLED badge).
        dao.cancelVoucher(companyId, voucherId, System.currentTimeMillis())

        // 4. Audit Log - a second, independent record of the same fact; the voucher row itself
        // (now merely flagged, not gone) remains the primary audit trail.
        dao.insertAuditLog(
            AuditLogEntity(
                logId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = financialYearId,
                action = AuditAction.CANCEL_VOUCHER,
                entityType = "VOUCHER",
                entityId = voucherId,
                description = "Cancelled voucher ${voucher.voucherNumber} (${originalItems.size} journal line(s) reversed, history preserved)",
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
     * No-mock-data audit fix (2026-09) - posts an Invoice's Voucher and links the Invoice to it
     * in ONE atomic transaction, re-checking "not already posted" from inside that same
     * transaction. [AccountingRepository.postInvoice] previously read the Invoice's `voucherId`
     * in a separate, earlier call before ever reaching [postVoucherAtomic]'s own transaction - a
     * genuine TOCTOU race (two concurrent posts of the same Invoice, e.g. a rapid double-tap)
     * could both read `voucherId == null`, both pass, and both post a real voucher, with the
     * second `linkInvoiceToVoucher` silently overwriting the first link while both hit the ledger.
     * The re-check now happens with the exact same atomicity guarantee [VoucherPostingEngine.post]'s
     * own idempotency/duplicate-number guards already have (step 0 inside `database.withTransaction`) -
     * never a second, weaker copy of that discipline for this one caller.
     */
    suspend fun postInvoiceVoucherAtomic(
        companyId: String,
        invoiceId: String,
        voucher: VoucherEntity,
        items: List<JournalItemEntity>,
        idempotencyKey: String = UUID.randomUUID().toString(),
        userId: String = "SYSTEM_USER",
        stockLines: List<VoucherStockLineEntity> = emptyList(),
        gstTransactions: List<GstTransactionEntity> = emptyList(),
        linkedAt: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        database.withTransaction {
            val invoiceEntity = dao.getInvoiceById(companyId, invoiceId)
                ?: throw AccountingTransactionException(AppError.ResourceNotFound("Invoice", invoiceId))
            if (invoiceEntity.voucherId != null) {
                throw AccountingTransactionException(
                    AppError.BusinessRuleViolation("Invoice '$invoiceId' has already been posted as voucher '${invoiceEntity.voucherId}'.")
                )
            }
            VoucherPostingEngine.post(dao, voucher, items, idempotencyKey, userId, stockLines, gstTransactions)
            dao.linkInvoiceToVoucher(companyId, invoiceId, voucher.voucherId, linkedAt)
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
                PurchaseDocumentGuard.requireNoDuplicate(dao, gstTransactions)
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
