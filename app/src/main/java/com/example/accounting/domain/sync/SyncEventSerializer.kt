package com.example.accounting.domain.sync

import com.example.accounting.domain.accounting.VoucherType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Maps a posted voucher's type to its business-level sync operation (Phase 6, Priority 6.13) -
 * exhaustive `when` with a safe [SyncOperation.POST_VOUCHER] fallback for any type without a named
 * business operation, so this never breaks if a new [VoucherType] is added later. */
fun VoucherType.toPostOperation(): SyncOperation = when (this) {
    VoucherType.SALES -> SyncOperation.POST_SALES_INVOICE
    VoucherType.PURCHASE -> SyncOperation.POST_PURCHASE_BILL
    VoucherType.RECEIPT -> SyncOperation.POST_RECEIPT
    VoucherType.PAYMENT -> SyncOperation.POST_PAYMENT
    VoucherType.CONTRA -> SyncOperation.POST_CONTRA
    VoucherType.JOURNAL -> SyncOperation.POST_JOURNAL
    VoucherType.CREDIT_NOTE -> SyncOperation.POST_CREDIT_NOTE
    VoucherType.DEBIT_NOTE -> SyncOperation.POST_DEBIT_NOTE
    else -> SyncOperation.POST_VOUCHER
}

/** Single Moshi instance for [SyncEvent] serialization - the same converter [com.example.accounting.core.network.ApiClient]
 * already uses for the transport layer, so the payload embedded in `OutboxSyncEntity.payloadJson`
 * and the wire format the server eventually deserializes agree byte-for-byte. */
object SyncEventSerializer {
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    // .serializeNulls() - same rationale as GstrJsonSerializer/ExportJsonSerializer: a
    // GST-only posting's `voucher = null` (also `ledger`/`party`/`invoice`/`tradeDocument`) must
    // appear on the wire as an explicit null, never silently omitted - the class doc comment above
    // requires this payload to agree with the server's expected wire format byte-for-byte, and a
    // server reading a missing key can't distinguish "explicitly absent" from "field not sent."
    private val adapter = moshi.adapter(SyncEvent::class.java).serializeNulls()

    fun toJson(event: SyncEvent): String = adapter.toJson(event)
}
