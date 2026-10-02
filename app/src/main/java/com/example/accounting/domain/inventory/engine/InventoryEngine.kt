package com.example.accounting.domain.inventory.engine

import com.example.accounting.core.common.AppError
import com.example.accounting.core.database.AccountingTransactionException
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.StockMovementEntity
import com.example.accounting.data.local.entity.VoucherEntity
import com.example.accounting.data.local.entity.VoucherStockLineEntity
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.StockMovementType
import java.util.UUID

/**
 * Room-independent inventory posting/cancellation engine (same design as
 * [com.example.accounting.core.database.VoucherPostingEngine] and for the same reason: it can be
 * unit-tested directly against a fake [AccountingDao] without Robolectric, while
 * [com.example.accounting.core.database.DatabaseTransaction] wraps it in a real Room transaction
 * for production atomicity).
 *
 * This is a PARALLEL system to double-entry journal posting, not a replacement for any part of
 * it - Phase 0-3's journal/ledger logic is never touched. `VoucherPostingEngine.post()`/`cancel()`
 * call into here as an ADDITIONAL step only when stock lines are supplied (empty by default, so
 * existing non-inventory callers see zero behavior change).
 */
object InventoryEngine {

    private data class ItemState(val quantityRaw: Long, val avgCostPaise: Long)

    /**
     * Classifies a voucher-level stock line into a [StockMovementType] for COGS/reporting
     * purposes. Voucher type alone is not enough (e.g. a PURCHASE voucher with an OUT line is a
     * purchase return), so direction is also considered - per Section 7 of the Phase 4 spec.
     */
    private fun classifyMovement(voucherType: VoucherType, direction: StockDirection): StockMovementType = when (voucherType) {
        VoucherType.PURCHASE -> if (direction == StockDirection.IN) StockMovementType.PURCHASE else StockMovementType.PURCHASE_RETURN
        VoucherType.SALES -> if (direction == StockDirection.OUT) StockMovementType.SALE else StockMovementType.SALES_RETURN
        VoucherType.CREDIT_NOTE -> StockMovementType.SALES_RETURN
        VoucherType.DEBIT_NOTE -> StockMovementType.PURCHASE_RETURN
        VoucherType.STOCK_JOURNAL -> if (direction == StockDirection.IN) StockMovementType.STOCK_JOURNAL_IN else StockMovementType.STOCK_JOURNAL_OUT
        VoucherType.RECEIPT_NOTE -> StockMovementType.STOCK_JOURNAL_IN
        VoucherType.DELIVERY_NOTE -> StockMovementType.STOCK_JOURNAL_OUT
        else -> if (direction == StockDirection.IN) StockMovementType.ADJUSTMENT_IN else StockMovementType.ADJUSTMENT_OUT
    }

    /**
     * Validates and applies a voucher's stock lines: for each line, computes the correct cost
     * basis (transaction rate for IN, current weighted-average cost for OUT - never the voucher's
     * selling price), appends an immutable [StockMovementEntity], and updates the per-item cache.
     * Multiple lines for the same item within one voucher are applied in order against
     * continuously-updated state, so valuation stays correct.
     *
     * @throws AccountingTransactionException wrapping [AppError.InvalidStockQuantity] (zero/negative
     *   quantity), [AppError.StockItemNotFound], or [AppError.InsufficientStock] (OUT exceeds
     *   quantity on hand).
     */
    suspend fun applyStockLines(
        dao: AccountingDao,
        voucher: VoucherEntity,
        stockLines: List<VoucherStockLineEntity>,
        userId: String
    ) {
        if (stockLines.isEmpty()) return

        val stateByItem = mutableMapOf<String, ItemState>()

        for (line in stockLines) {
            if (line.quantityRaw <= 0L) {
                throw AccountingTransactionException(
                    AppError.InvalidStockQuantity("Stock line quantity must be positive; got ${line.quantityRaw} for item '${line.itemId}'.")
                )
            }

            val item = dao.getStockItemById(voucher.companyId, line.itemId)
                ?: throw AccountingTransactionException(AppError.StockItemNotFound(line.itemId))

            val current = stateByItem[line.itemId] ?: ItemState(item.currentQuantity, item.currentAvgCostPaise)

            val (newState, movementRatePaise, movementAmountPaise) = when (line.direction) {
                StockDirection.IN -> {
                    // A Credit Note's returned goods re-enter stock at the cost they left at, never at
                    // the Credit Note's own (selling-price) line rate - see saleReturnCostRatePaise.
                    val returnCostRatePaise = saleReturnCostRatePaise(dao, voucher, line)
                    val receiptRatePaise = returnCostRatePaise ?: line.ratePaise
                    val receiptAmountPaise = if (returnCostRatePaise != null) {
                        StockValuationEngine.amountFor(line.quantityRaw, returnCostRatePaise)
                    } else {
                        line.amountPaise
                    }
                    val newAvgCost = StockValuationEngine.weightedAverageCostAfterReceipt(
                        current.quantityRaw, current.avgCostPaise, line.quantityRaw, receiptRatePaise
                    )
                    val newQty = current.quantityRaw + line.quantityRaw
                    Triple(ItemState(newQty, newAvgCost), receiptRatePaise, receiptAmountPaise)
                }
                StockDirection.OUT -> {
                    if (current.quantityRaw < line.quantityRaw) {
                        throw AccountingTransactionException(
                            AppError.InsufficientStock(
                                itemName = item.name,
                                availableQuantity = (current.quantityRaw / 1000.0).toString(),
                                requestedQuantity = (line.quantityRaw / 1000.0).toString()
                            )
                        )
                    }
                    val newQty = current.quantityRaw - line.quantityRaw
                    // OUT movements cost at the CURRENT weighted-average cost - this is what makes
                    // the movement directly usable as a COGS figure, independent of the voucher's
                    // (possibly very different) selling price.
                    val costAmount = StockValuationEngine.amountFor(line.quantityRaw, current.avgCostPaise)
                    Triple(ItemState(newQty, current.avgCostPaise), current.avgCostPaise, costAmount)
                }
            }

            stateByItem[line.itemId] = newState

            dao.insertStockMovement(
                StockMovementEntity(
                    movementId = UUID.randomUUID().toString(),
                    companyId = voucher.companyId,
                    financialYearId = voucher.financialYearId,
                    itemId = line.itemId,
                    voucherId = voucher.voucherId,
                    date = voucher.date,
                    direction = line.direction,
                    movementType = classifyMovement(voucher.voucherType, line.direction),
                    quantityRaw = line.quantityRaw,
                    ratePaise = movementRatePaise,
                    amountPaise = movementAmountPaise,
                    runningAvgCostAfterPaise = newState.avgCostPaise,
                    reference = voucher.voucherNumber,
                    narration = "Voucher ${voucher.voucherNumber} (${voucher.voucherType.displayName})",
                    createdAt = System.currentTimeMillis(),
                    createdBy = userId
                )
            )
            dao.updateStockCache(voucher.companyId, line.itemId, newState.quantityRaw, newState.avgCostPaise)
        }
    }

    /**
     * The cost rate (paise per unit) the goods of a Credit Note's [line] originally left stock at:
     * the weighted rate of the referenced Sales voucher's OUT movements for the same item. `null`
     * for anything that isn't a Credit Note against a Sales voucher that moved this item, in which
     * case the caller keeps the line's own rate unchanged.
     */
    private suspend fun saleReturnCostRatePaise(dao: AccountingDao, voucher: VoucherEntity, line: VoucherStockLineEntity): Long? {
        if (voucher.voucherType != VoucherType.CREDIT_NOTE) return null
        val originalVoucherId = voucher.referenceVoucherId ?: return null
        val originalOuts = dao.getStockMovementsForVoucher(originalVoucherId).filter {
            it.itemId == line.itemId && it.direction == StockDirection.OUT && it.movementType != StockMovementType.CANCELLATION_REVERSAL
        }
        val totalQtyRaw = originalOuts.sumOf { it.quantityRaw }
        if (totalQtyRaw <= 0L) return null
        return if (originalOuts.size == 1) originalOuts.single().ratePaise else originalOuts.sumOf { it.amountPaise } * 1000L / totalQtyRaw
    }

    /**
     * Compensating reversal of every stock movement tied to [voucherId] (Section 8 of the Phase 4
     * spec - mirrors [com.example.accounting.core.database.VoucherPostingEngine.cancel] exactly:
     * original [StockMovementEntity] rows are never touched or deleted; new opposite-direction
     * rows are appended at the SAME cost basis the original movement used, so valuation stays
     * consistent and the net quantity/value effect returns to the pre-posting state).
     */
    suspend fun reverseStockMovements(
        dao: AccountingDao,
        companyId: String,
        voucherId: String,
        reversalDate: String,
        userId: String
    ) {
        val originalMovements = dao.getStockMovementsForVoucher(voucherId)
            .filter { it.movementType != StockMovementType.CANCELLATION_REVERSAL }
        if (originalMovements.isEmpty()) return

        val stateByItem = mutableMapOf<String, ItemState>()

        for (m in originalMovements) {
            val item = dao.getStockItemById(companyId, m.itemId) ?: continue
            val current = stateByItem[m.itemId] ?: ItemState(item.currentQuantity, item.currentAvgCostPaise)
            val reversedDirection = if (m.direction == StockDirection.IN) StockDirection.OUT else StockDirection.IN

            val newState = when (reversedDirection) {
                StockDirection.IN -> {
                    val newAvgCost = StockValuationEngine.weightedAverageCostAfterReceipt(
                        current.quantityRaw, current.avgCostPaise, m.quantityRaw, m.ratePaise
                    )
                    ItemState(current.quantityRaw + m.quantityRaw, newAvgCost)
                }
                StockDirection.OUT -> {
                    if (current.quantityRaw < m.quantityRaw) {
                        throw AccountingTransactionException(
                            AppError.InsufficientStock(
                                itemName = item.name,
                                availableQuantity = (current.quantityRaw / 1000.0).toString(),
                                requestedQuantity = (m.quantityRaw / 1000.0).toString()
                            )
                        )
                    }
                    ItemState(current.quantityRaw - m.quantityRaw, current.avgCostPaise)
                }
            }
            stateByItem[m.itemId] = newState

            dao.insertStockMovement(
                StockMovementEntity(
                    movementId = UUID.randomUUID().toString(),
                    companyId = companyId,
                    financialYearId = m.financialYearId,
                    itemId = m.itemId,
                    voucherId = voucherId,
                    date = reversalDate,
                    direction = reversedDirection,
                    movementType = StockMovementType.CANCELLATION_REVERSAL,
                    quantityRaw = m.quantityRaw,
                    ratePaise = m.ratePaise,
                    amountPaise = m.amountPaise,
                    runningAvgCostAfterPaise = newState.avgCostPaise,
                    reference = m.reference,
                    narration = "Reversal: cancellation of voucher ${m.reference}",
                    createdAt = System.currentTimeMillis(),
                    createdBy = userId
                )
            )
            dao.updateStockCache(companyId, m.itemId, newState.quantityRaw, newState.avgCostPaise)
        }
    }
}
