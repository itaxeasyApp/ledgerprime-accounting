package com.example.accounting.domain.inventory

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity

/**
 * Direction of a stock movement. IN increases quantity on hand, OUT decreases it.
 */
enum class StockDirection { IN, OUT }

/**
 * Business reason for a stock movement - drives COGS classification (Section 12/13 of the
 * Phase 4 spec) and audit narration. Distinct from [com.example.accounting.domain.accounting.VoucherType]
 * because one voucher type can imply different movement semantics (e.g. a PURCHASE return is
 * still voucher type PURCHASE but movement type PURCHASE_RETURN).
 */
enum class StockMovementType {
    OPENING,
    PURCHASE,
    PURCHASE_RETURN,
    SALE,
    SALES_RETURN,
    STOCK_JOURNAL_IN,
    STOCK_JOURNAL_OUT,
    ADJUSTMENT_IN,
    ADJUSTMENT_OUT,
    CANCELLATION_REVERSAL
}

/**
 * Costing method used by [com.example.accounting.domain.inventory.engine.StockValuationEngine].
 * Only WEIGHTED_AVERAGE is implemented (Section 6 of the Phase 4 spec: do not build valuation
 * methods the project hasn't selected). The enum stays open so FIFO can be added later without
 * changing every call site's shape.
 */
enum class StockValuationMethod { WEIGHTED_AVERAGE }

/**
 * A single immutable stock ledger entry (Section 4 of the Phase 4 spec: stock movement history
 * is authoritative and is NEVER overwritten or deleted - only appended to, including reversals).
 * [ratePaise]/[amountPaise] carry the COST basis: for IN movements this is the transaction rate
 * (purchase cost); for OUT movements this is the weighted-average cost AT THE TIME of the
 * movement, which is what makes it usable directly as a COGS figure - deliberately NOT the
 * voucher's selling price.
 */
data class StockMovement(
    val movementId: String,
    val companyId: String,
    val financialYearId: String,
    val itemId: String,
    val voucherId: String?,
    val date: java.time.LocalDate,
    val direction: StockDirection,
    val movementType: StockMovementType,
    val quantity: Quantity,
    val rate: Money,
    val amount: Money,
    val runningAverageCostAfter: Money,
    val reference: String = "",
    val narration: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String = "SYSTEM_USER"
)

/**
 * One line on a voucher describing its stock effect (Section 3 of the Phase 4 spec) - the
 * INPUT to [com.example.accounting.domain.inventory.engine.InventoryEngine], as opposed to
 * [StockMovement] which is the engine's immutable OUTPUT. [rate] here is the transaction rate
 * (purchase cost or selling price as entered on the voucher), used for the invoice/journal
 * amount - not necessarily the same as the movement's cost-basis rate.
 */
data class VoucherStockLine(
    val lineId: String,
    val voucherId: String,
    val companyId: String,
    val financialYearId: String,
    val itemId: String,
    val itemName: String = "",
    val direction: StockDirection,
    val quantity: Quantity,
    val rate: Money,
    val amount: Money,
    val lineOrder: Int = 0,
    /** The discount actually subtracted (Rate x Qty x Discount%) before tax - see
     * [com.example.accounting.domain.trading.TradingWorkflowEngine]'s line-amount computation.
     * Defaults to ZERO, matching every stock line created before Discount existed. Stored
     * verbatim (never re-derived) so an invoice PDF can display it without recalculating. */
    val discount: Money = Money.ZERO
) {
    companion object {
        /** amount = quantity x rate, computed in integer paise (qty is thousandths, so /1000). */
        fun computeAmount(quantity: Quantity, rate: Money): Money = Money.fromPaise(quantity.rawValue * rate.paise / 1000L)
    }
}
