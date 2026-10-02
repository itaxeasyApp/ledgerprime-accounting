package com.example.accounting.domain.taxation.gst

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.accounting.VoucherType
import java.time.LocalDate

/**
 * One taxable line's worth of GST facts (Phase 5, Priority 4) - the dedicated record that makes
 * GST reporting independent of ledger names entirely. Built by the trading workflow engines
 * ([com.example.accounting.domain.trading.TradingWorkflowEngine]) alongside the journal/stock
 * lines for the same voucher, and persisted 1:1 with [com.example.accounting.data.local.entity.GstTransactionEntity].
 */
data class GstTransaction(
    val gstTransactionId: String,
    val companyId: String,
    val financialYearId: String,
    /** UI-06/Architecture Checkpoint: nullable (was non-null) - a GST-only company's transaction
     * has no accounting Voucher at all. See [com.example.accounting.data.local.entity.GstTransactionEntity.voucherId]
     * for the persistence-side rationale. */
    val voucherId: String?,
    val voucherType: VoucherType,
    val partyLedgerId: String,
    val partyGstin: String,
    val placeOfSupply: String,
    val supplyType: SupplyType,
    val itemId: String?,
    val hsnSacCode: String,
    val quantity: Quantity?,
    val taxableAmount: Money,
    val gstRatePercent: Double,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money,
    val direction: GstDirection,
    val lineOrder: Int,
    /** Rule 31 (Purchase/RCM Foundation) - who is liable to remit this line's tax. Defaults to
     * FORWARD_CHARGE so every pre-existing construction site (buildGstOnlySale, buildNote's
     * `.copy()`, tests) keeps compiling and behaving identically. */
    val chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE,
    /** D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - the raw, pre-collapse
     * tax-treatment the user actually chose (Taxable/Zero Rated/Exempt/Nil Rated). [supplyType] is
     * the geography+treatment-derived value [GstCalculationEngine] actually taxed against (and
     * deliberately collapses Exempt/Nil Rated into one zero-tax bucket - see its own KDoc) - this
     * field preserves the un-collapsed source fact for any future GSTR report that needs to tell
     * Exempt and Nil Rated apart. Defaults to NORMAL, matching every line's behavior before this
     * field existed (every pre-existing row really was Taxable when it resolved to INTRA_STATE/
     * INTER_STATE, and really was the corresponding nature when it resolved to EXPORT/EXEMPT - see
     * `MIGRATION_18_19`'s backfill for the exact, disclosed mapping). */
    val supplyNature: GstSupplyNature = GstSupplyNature.NORMAL,
    /** D1b - the single, explicit identifier that ties every line of ONE business transaction
     * together, for both the accounting-integrated path (where it is always the real [voucherId])
     * and the GST-only path (which has no [voucherId] at all, so this is the only correlation
     * available for future GSTR reporting to know which rows belong together). Never a second/
     * polymorphic [voucherId] - deliberately a distinct, always-non-null plain identifier with no
     * FK and no accounting meaning whatsoever. Defaults to "" only so existing call sites that
     * predate this field keep compiling; every real construction site (accounting or GST-only)
     * always supplies a real value. */
    val transactionGroupId: String = "",
    /** D1b - the real business/invoice date this GST fact belongs to. For the accounting-integrated
     * path this stays `null` - that path already has an unambiguous source of truth for the date
     * (the joined [com.example.accounting.domain.accounting.Voucher.date]), so duplicating it here
     * would just be a second value that could drift from the first. For the GST-only path there is
     * no Voucher to join to at all (the exact gap this field closes) - `postGstOnlySale`/
     * `postGstOnlyPurchase`/the GST-only note path always supply a real, explicit value; never
     * `createdAt` (a row-insert timestamp, not a business date) used as a substitute. */
    val transactionDate: LocalDate? = null,
    /** D1b - the party's [GstRegistrationStatus] AT THE TIME this transaction was recorded, copied
     * from the resolved party [com.example.accounting.domain.accounting.Ledger] at construction
     * time - deliberately never re-derived from the (mutable) Ledger master at report time, so a
     * later change to the party's registration status can never silently rewrite a historical GST
     * fact. `null` means the status was genuinely unknown at that time (the same "unknown, never
     * guessed" semantics [Ledger.gstRegistrationStatus] itself already uses) - never defaulted to
     * either real value. */
    val partyGstRegistrationStatus: GstRegistrationStatus? = null,
    /** Phase 8 Step 13 - the SUPPLIER's own invoice/bill number, as printed on the supplier's document
     * (what GSTR-2B lists). Only ever set on a Purchase's INPUT rows; `null` means NOT_RECORDED - never
     * a guess and never derived from this app's own voucher number. Every row that existed before this
     * field has `null`. Credit/Debit Notes never inherit it (a note is its own document). */
    val supplierDocumentNumber: String? = null,
    /** Phase 8 Step 13 - the date printed on the supplier's document (NOT the booking date, which is
     * [transactionDate]/the voucher date). `null` means NOT_RECORDED. */
    val supplierDocumentDate: LocalDate? = null
) {
    /** What is known about this row's supplier document identity - see [DocumentIdentityStatus]. */
    val documentIdentityStatus: DocumentIdentityStatus
        get() = when {
            supplierDocumentNumber == null && supplierDocumentDate == null -> DocumentIdentityStatus.NOT_RECORDED
            supplierDocumentNumber != null && supplierDocumentDate != null -> DocumentIdentityStatus.RECORDED
            else -> DocumentIdentityStatus.PARTIAL
        }
}

/** Whether a purchase row carries the supplier's document number and date a GSTR-2B match needs. */
enum class DocumentIdentityStatus { NOT_RECORDED, PARTIAL, RECORDED }

/**
 * Supplier document identity rules, shared by the posting path and (later) the GSTR-2B matcher so they
 * can never disagree. The identity of a purchase document is supplier + document number, within one
 * financial year: the supplier is its GSTIN (upper-cased) and, only when the supplier has no GSTIN,
 * its ledger; the number is compared trimmed and case-insensitively. Nothing else is normalised -
 * no stripping of zeros or punctuation, which would be a guess about the supplier's numbering.
 */
object PurchaseDocumentIdentity {
    /** `null` when [raw] is blank, i.e. NOT_RECORDED. */
    fun normalizeNumber(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** The supplier half of the key: GSTIN if the supplier has one, else the supplier ledger. */
    fun supplierKey(partyGstin: String, partyLedgerId: String): String =
        partyGstin.trim().uppercase().takeIf { it.isNotEmpty() }?.let { "GSTIN:$it" } ?: "LEDGER:$partyLedgerId"

    /** `null` when the row has no supplier document number (nothing to detect a duplicate on). */
    fun duplicateKey(partyGstin: String, partyLedgerId: String, number: String?): String? =
        normalizeNumber(number)?.let { "${supplierKey(partyGstin, partyLedgerId)}|${it.uppercase()}" }
}
