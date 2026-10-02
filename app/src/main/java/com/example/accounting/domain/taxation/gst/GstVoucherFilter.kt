package com.example.accounting.domain.taxation.gst

import com.example.accounting.domain.accounting.Voucher
import java.time.LocalDate

/**
 * Which vouchers belong in the GST Dashboard / GST return views (Phase 8, Step 4). One shared,
 * pure rule so no screen decides it for itself:
 *
 *  - the voucher's type must create GST ([com.example.accounting.domain.accounting.VoucherType.createsGst]:
 *    Sales, Purchase, Credit Note, Debit Note - an RCM purchase is a Purchase whose GST lines are
 *    marked reverse-charge, so it is covered by Purchase);
 *  - it must be flagged [Voucher.isGstApplicable], i.e. it really carries GstTransaction facts
 *    (the posting engine rejects that flag without facts), so a Sales/Purchase posted without GST
 *    (the item-free Account-Only path) and Contra/Payment/Receipt/Journal vouchers stay out;
 *  - it must not be cancelled;
 *  - each voucher appears once, however many GST lines it has.
 *
 * The GST return sources themselves already read GstTransaction rows directly
 * ([com.example.accounting.data.repository.AccountingRepository.getActiveGstTransactionsForPeriod]);
 * this filter is for the places that only have the [Voucher] list.
 */
object GstVoucherFilter {

    fun isGstRelevant(voucher: Voucher): Boolean =
        !voucher.isCancelled && voucher.voucherType.createsGst && voucher.isGstApplicable

    fun filter(vouchers: List<Voucher>, dateRange: ClosedRange<LocalDate>? = null): List<Voucher> =
        vouchers
            .filter { isGstRelevant(it) && (dateRange == null || it.date in dateRange) }
            .distinctBy { it.voucherId }
}
