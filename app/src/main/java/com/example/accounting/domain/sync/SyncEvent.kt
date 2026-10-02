package com.example.accounting.domain.sync

import com.squareup.moshi.JsonClass

/**
 * The versioned command/event Outbox payload (Phase 6, Priority 6.4) - replaces the previous
 * hand-built ad-hoc strings (`{"voucherNumber":"...","amount":1000}`) that carried none of Phase
 * 4/5's data. Every mutation enqueued to [com.example.accounting.data.local.entity.OutboxSyncEntity.payloadJson]
 * is one of these, serialized via [SyncEventSerializer]. `schemaVersion` lets the server (and future
 * Android versions) evolve the shape without breaking already-queued/in-flight events.
 *
 * Built directly from Room entities at the posting/cancellation/ledger-mutation call sites (not
 * domain objects) since that's what [com.example.accounting.core.database.VoucherPostingEngine]
 * and [com.example.accounting.data.repository.AccountingRepository] already have on hand at those
 * points - no new domain-to-entity round trip needed.
 */
enum class SyncOperation {
    POST_SALES_INVOICE,
    POST_PURCHASE_BILL,
    POST_RECEIPT,
    POST_PAYMENT,
    POST_CONTRA,
    POST_JOURNAL,
    POST_CREDIT_NOTE,
    POST_DEBIT_NOTE,
    /** Any voucher type without a dedicated business-level operation above (e.g. Stock Journal,
     * Receipt Note, Delivery Note) - still a complete, versioned event, just not one of the
     * named business operations 6.13/6.17 call out individually. */
    POST_VOUCHER,
    CANCEL_VOUCHER,
    CREATE_LEDGER,
    UPDATE_LEDGER,
    DELETE_LEDGER,
    /** Phase 7A - Party + Invoice Domain Foundation. Purely additive: every operation above is
     * unchanged, and posting a Sales Invoice/Purchase Bill/Credit/Debit Note still produces
     * exactly the POST_* operation it already did via [toPostOperation] - these five only cover
     * the new pre-posting Party/draft-Invoice lifecycle. */
    CREATE_PARTY,
    UPDATE_PARTY,
    CREATE_DRAFT_INVOICE,
    CANCEL_DRAFT_INVOICE,
    LINK_INVOICE_VOUCHER,
    /** Phase 7B - Document/Voucher Lifecycle Architecture. Purely additive: every operation above
     * is unchanged; these four only cover the new non-posting TradeDocument lifecycle
     * (Quotation/Proforma/Sales-Purchase-Order/Delivery-Receipt-Note). */
    CREATE_TRADE_DOCUMENT,
    ISSUE_TRADE_DOCUMENT,
    CONVERT_TRADE_DOCUMENT,
    CANCEL_TRADE_DOCUMENT,
    /** Architecture Checkpoint (GST-only path) - a [SyncGstTransactionDto] batch with no
     * [SyncEvent.voucher] at all. Every other POST_* operation above always carries a real
     * voucher; this is the only operation that deliberately never does, so a future server
     * handler for it must never expect or require one. */
    POST_GST_TRANSACTION
}

enum class SyncAggregateType { VOUCHER, LEDGER, PARTY, INVOICE, TRADE_DOCUMENT, GST_TRANSACTION }

@JsonClass(generateAdapter = true)
data class SyncJournalLineDto(
    val itemId: String,
    val ledgerId: String,
    val ledgerName: String,
    val type: String,
    val amountPaise: Long,
    val narration: String,
    val lineOrder: Int
)

@JsonClass(generateAdapter = true)
data class SyncStockLineDto(
    val lineId: String,
    val itemId: String,
    val direction: String,
    val quantityRaw: Long,
    val ratePaise: Long,
    val amountPaise: Long,
    val lineOrder: Int
)

@JsonClass(generateAdapter = true)
data class SyncGstTransactionDto(
    val gstTransactionId: String,
    /** Transaction/Contract Hardening - the explicit business/document classification (a real
     * [com.example.accounting.domain.accounting.VoucherType] name, e.g. "SALES") this GST fact
     * belongs to. Required, never derived by the reader from [direction] - OUTPUT does not always
     * mean SALES (a Credit Note is also OUTPUT-direction), so a server or future consumer must
     * never infer this. For the accounting path this mirrors [GstTransaction.voucherType], which
     * already existed but was previously only reachable via the top-level `SyncEvent.voucher`
     * object - not present at all for the GST-only path (`voucher` is null there). Carrying it
     * per-line here means every consumer of this DTO can read the classification the same way
     * regardless of which path produced it. */
    val voucherType: String,
    val partyLedgerId: String,
    val partyGstin: String,
    val placeOfSupply: String,
    val supplyType: String,
    val itemId: String?,
    val hsnSacCode: String,
    val quantityRaw: Long?,
    val taxableAmountPaise: Long,
    val gstRatePercent: Double,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
    val direction: String,
    val lineOrder: Int,
    /** D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - see
     * [com.example.accounting.domain.taxation.gst.GstTransaction.chargeType]. Optional at the
     * schema level ONLY for backward compatibility with an already-queued pre-D1b event (mirrors
     * [voucherType]'s own precedent) - defaults to "FORWARD_CHARGE", the only value that reproduces
     * every such event's actual, real behavior (RCM did not exist for GST-only before this). */
    val chargeType: String = "FORWARD_CHARGE",
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.supplyNature]. Same
     * backward-compatibility shape as [chargeType]; defaults to "NORMAL". */
    val supplyNature: String = "NORMAL",
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.transactionGroupId].
     * Optional only for the same pre-D1b backward-compatibility reason as [chargeType]/[supplyNature] -
     * every real D1b-or-later event always supplies it. A `null`/blank value falls back to this
     * line's own [gstTransactionId] server-side (a genuine single-row group), never fabricated. */
    val transactionGroupId: String? = null,
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.transactionDate].
     * `null` for the accounting-integrated path (unchanged - that path's real date is
     * [SyncVoucherDto.date]); always a real ISO-8601 value for the GST-only path. */
    val transactionDate: String? = null,
    /** D1b - see [com.example.accounting.domain.taxation.gst.GstTransaction.partyGstRegistrationStatus].
     * `null` means UNKNOWN, never guessed. */
    val partyGstRegistrationStatus: String? = null,
    /** Step 17 - see [com.example.accounting.domain.taxation.gst.GstTransaction.supplierDocumentNumber]. `null` =
     * NOT_RECORDED (also what an already-queued pre-Step-13 event reads as) - never guessed. Purchase rows only. */
    val supplierDocumentNumber: String? = null,
    /** Step 17 - ISO-8601 supplier document date; `null` = NOT_RECORDED. Purchase rows only. */
    val supplierDocumentDate: String? = null
)

@JsonClass(generateAdapter = true)
data class SyncSettlementDto(
    val allocationId: String,
    val settlementVoucherId: String,
    val invoiceVoucherId: String?,
    val allocatedAmountPaise: Long
)

@JsonClass(generateAdapter = true)
data class SyncVoucherDto(
    val voucherId: String,
    val voucherNumber: String,
    val voucherType: String,
    val date: String,
    val referenceNumber: String,
    val narration: String,
    val totalAmountPaise: Long,
    val isCancelled: Boolean,
    val createdBy: String,
    val partyGstin: String,
    val isGstApplicable: Boolean,
    val referenceVoucherId: String?,
    val paymentMode: String
)

@JsonClass(generateAdapter = true)
data class SyncLedgerDto(
    val ledgerId: String,
    val groupId: String,
    val name: String,
    val code: String,
    val openingBalancePaise: Long,
    val openingBalanceType: String,
    val gstin: String,
    val pan: String,
    val stateCode: String,
    val hsnSacCode: String,
    val defaultTaxRate: Double
)

/** Phase 7A - Party + Invoice Domain Foundation. */
@JsonClass(generateAdapter = true)
data class SyncPartyDto(
    val partyId: String,
    val ledgerId: String,
    val role: String,
    val entityType: String,
    val displayName: String,
    val contactName: String = "",
    val creditLimitPaise: Long? = null,
    val paymentTermsType: String = "DUE_ON_RECEIPT",
    val paymentTermsCustomDays: Int? = null,
    val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class SyncInvoiceLineDto(
    val lineId: String,
    val itemId: String,
    val itemName: String,
    val hsnSacCode: String,
    val quantityRaw: Long,
    val ratePaise: Long,
    val gstRatePercent: Double,
    val cessRatePercent: Double,
    val lineOrder: Int
)

/** Fields beyond [invoiceId]/[voucherId]/[invoiceNumber] default blank so the same DTO covers both
 * a full CREATE_DRAFT_INVOICE payload and the minimal LINK_INVOICE_VOUCHER follow-up event. */
@JsonClass(generateAdapter = true)
data class SyncInvoiceDto(
    val invoiceId: String,
    val invoiceType: String = "",
    val invoiceNumber: String? = null,
    val partyId: String = "",
    val date: String = "",
    val dueDate: String? = null,
    val voucherId: String? = null,
    val referenceInvoiceId: String? = null,
    /** Phase 7B - the TradeDocument this Invoice was converted from. Null when created directly. */
    val sourceTradeDocumentId: String? = null,
    val narration: String = "",
    val lines: List<SyncInvoiceLineDto> = emptyList()
)

/** Phase 7B - Document/Voucher Lifecycle Architecture. */
@JsonClass(generateAdapter = true)
data class SyncTradeDocumentLineDto(
    val lineId: String,
    val itemId: String,
    val itemName: String,
    val hsnSacCode: String,
    val quantityRaw: Long,
    val ratePaise: Long,
    val gstRatePercent: Double,
    val cessRatePercent: Double,
    val lineOrder: Int
)

/** Fields beyond tradeDocumentId/status default blank so the same DTO covers CREATE/ISSUE/
 * CONVERT/CANCEL events, mirroring [SyncInvoiceDto]'s pattern. */
@JsonClass(generateAdapter = true)
data class SyncTradeDocumentDto(
    val tradeDocumentId: String,
    val documentType: String = "",
    val documentNumber: String? = null,
    val partyId: String = "",
    val date: String = "",
    val status: String = "DRAFT",
    val sourceTradeDocumentId: String? = null,
    val narration: String = "",
    val lines: List<SyncTradeDocumentLineDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SyncEvent(
    val schemaVersion: Int = 1,
    val eventId: String,
    val idempotencyKey: String,
    val companyId: String,
    val financialYearId: String,
    val operation: String,
    val aggregateType: String,
    val aggregateId: String,
    val voucher: SyncVoucherDto? = null,
    val journalLines: List<SyncJournalLineDto> = emptyList(),
    val stockLines: List<SyncStockLineDto> = emptyList(),
    val gstTransactions: List<SyncGstTransactionDto> = emptyList(),
    val settlements: List<SyncSettlementDto> = emptyList(),
    val ledger: SyncLedgerDto? = null,
    val party: SyncPartyDto? = null,
    val invoice: SyncInvoiceDto? = null,
    val tradeDocument: SyncTradeDocumentDto? = null
)
