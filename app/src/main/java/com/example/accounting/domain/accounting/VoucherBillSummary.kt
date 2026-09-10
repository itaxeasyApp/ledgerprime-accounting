package com.example.accounting.domain.accounting

import com.example.accounting.core.common.Money

/**
 * Plain-business-document view of a posted [Voucher] - no Dr/Cr, no ledger jargon. Product
 * correction (docs/CORRECTIONS_LOG.md, "THIS APPLICATION IS NOT AN ERP"): the posted-voucher
 * detail screen must show a Bill/Invoice a non-accountant recognizes by default, never
 * "Particulars/Ledger, Debit, Credit". The existing journal-line table is never removed - it moves
 * behind an explicit Advanced -> View Accounting Entries action in
 * [com.example.accounting.presentation.components.VoucherDetailDialog].
 *
 * This is the fallback shape used whenever a richer, real item-level
 * [com.example.accounting.domain.rendering.DocumentData] isn't available (an account-only Sale/
 * Purchase, or any non-trading voucher type: Receipt/Payment/Contra/Journal/Notes) - assembled
 * from [Voucher.items] (party identity - a pure, synchronous read, no DB call) plus this voucher's
 * own [com.example.accounting.data.local.entity.GstTransactionEntity] rows (GST breakdown, present
 * for both item-level and account-only-with-GST postings) via
 * [com.example.accounting.data.repository.AccountingRepository.getVoucherBillSummary]. Every field
 * is a real fact already posted - this never recomputes GST or invents a value.
 */
data class VoucherBillSummary(
    /** "Customer"/"Supplier"/"Received From"/"Paid To"/"Transfer"/"Entry" - a plain business role,
     * never "Dr"/"Cr"/a ledger type name. */
    val partyLabel: String,
    val partyName: String,
    val taxableAmount: Money? = null,
    val cgst: Money = Money.ZERO,
    val sgst: Money = Money.ZERO,
    val igst: Money = Money.ZERO,
    val cess: Money = Money.ZERO,
    val hsnSacCode: String = "",
    val gstRatePercent: Double? = null,
    val totalAmount: Money
) {
    val hasGst: Boolean get() = cgst.isPositive || sgst.isPositive || igst.isPositive || cess.isPositive
}
