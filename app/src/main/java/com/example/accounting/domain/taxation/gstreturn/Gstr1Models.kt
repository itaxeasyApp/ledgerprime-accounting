package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import java.time.LocalDate

/**
 * Phase 8A, Part 1 (GSTR-1 Foundation) - the real, statutorily-named GSTR-1 tables, built ONLY from
 * facts [com.example.accounting.domain.taxation.gst.GstTransaction]/
 * [com.example.accounting.domain.accounting.Voucher] already carry (see [Gstr1ReturnBuilder]).
 * Deliberately does NOT attempt full GST Network schema parity - fields this domain has no source
 * of truth for (shipping bill number/date for exports, e-commerce operator GSTIN, advances
 * received/adjusted - Table 11A/11B, since no advance-receipt model exists anywhere in this
 * codebase) are simply absent rather than fabricated as null/zero placeholders that would look like
 * real, checked facts. Every list is empty (never null) when a company has no data for that table -
 * matching this project's established "always-present, possibly-empty" JSON/export discipline.
 */

/** One tax-rate line within an invoice or note - GSTR-1's tables are all "one row per (invoice,
 * rate)", never one row per invoice regardless of how many rates it carries. */
data class Gstr1RateLine(
    val gstRatePercent: Double,
    val taxableValue: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money
) {
    val totalTax: Money get() = cgst + sgst + igst + cess
    val invoiceValue: Money get() = taxableValue + totalTax
}

/** One B2B invoice (Table 4A/4B/6B/6C) - always carries a real recipient GSTIN (grouped under
 * [Gstr1B2bParty]), reverse-charge flag (Rule 31's [com.example.accounting.domain.taxation.gst.GstChargeType]
 * fact, never re-derived), and its rate-wise breakup. */
data class Gstr1Invoice(
    val voucherId: String,
    val invoiceNumber: String,
    val invoiceDate: LocalDate,
    val posStateCode: String,
    val reverseCharge: Boolean,
    val rateLines: List<Gstr1RateLine>
) {
    val invoiceValue: Money get() = rateLines.fold(Money.ZERO) { acc, l -> acc + l.invoiceValue }
}

/** Table 4A/4B - one registered recipient's full set of B2B invoices for the period. */
data class Gstr1B2bParty(
    val recipientGstin: String,
    val invoices: List<Gstr1Invoice>
)

/** Table 5A/5B - unregistered, inter-state, invoice value > the B2CL threshold
 * ([Gstr1ReturnBuilder.B2CL_THRESHOLD]) - reported invoice-wise (no recipient GSTIN to group by),
 * unlike ordinary B2C. */
data class Gstr1B2clInvoice(
    val voucherId: String,
    val invoiceNumber: String,
    val invoiceDate: LocalDate,
    val posStateCode: String,
    val rateLines: List<Gstr1RateLine>
) {
    val invoiceValue: Money get() = rateLines.fold(Money.ZERO) { acc, l -> acc + l.invoiceValue }
}

/** Table 7 - every other unregistered B2C supply, reported as one state+rate-wise summary row per
 * (POS, rate) - never invoice-level (that is precisely what distinguishes B2CS from B2CL). */
data class Gstr1B2csRow(
    val posStateCode: String,
    val gstRatePercent: Double,
    val taxableValue: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money
)

/** Table 9B - one Credit/Debit Note, always carrying the ORIGINAL invoice's number/date (a
 * statutorily REQUIRED field, resolved here from [com.example.accounting.domain.accounting.Voucher.referenceVoucherId] -
 * never guessed). [noteType] mirrors the issuing [com.example.accounting.domain.accounting.VoucherType]
 * (CREDIT_NOTE/DEBIT_NOTE) exactly; GSTR-1 only ever carries CREDIT_NOTE rows issued against a Sale
 * (outward) - a DEBIT_NOTE reversing a Purchase is an inward fact and never appears here (see
 * [Gstr1ReturnBuilder]'s own filtering). */
data class Gstr1Note(
    val voucherId: String,
    val noteNumber: String,
    val noteDate: LocalDate,
    val noteType: NoteType,
    val originalInvoiceNumber: String,
    val originalInvoiceDate: LocalDate,
    val posStateCode: String,
    val reverseCharge: Boolean,
    val rateLines: List<Gstr1RateLine>,
    /** True only when the ORIGINAL invoice's own GST period already has a GstReturn on file with
     * status FILED - the one honest, non-fabricated signal this domain can compute for "this note
     * is amendment-relevant" (see [Gstr1ReturnBuilder]'s KDoc for why a full B2BA/CDNRA-style
     * amendment table is explicitly out of scope for this pass). */
    val amendsFiledPeriod: Boolean = false
) {
    val noteValue: Money get() = rateLines.fold(Money.ZERO) { acc, l -> acc + l.invoiceValue }
}

enum class NoteType { CREDIT, DEBIT }

/** Table 9B, registered recipient - grouped exactly like [Gstr1B2bParty]. */
data class Gstr1CdnrParty(
    val recipientGstin: String,
    val notes: List<Gstr1Note>
)

/** Table 6A - export invoices (zero-rated, [com.example.accounting.domain.taxation.gst.SupplyType.EXPORT]).
 * `withPayment` is deliberately absent - this domain has no "LUT/Bond vs. with-IGST-payment" fact
 * anywhere (every export line already carries zero tax per [com.example.accounting.domain.taxation.gst.GSTRules.calculateTax]'s
 * EXPORT branch), so it is never guessed. */
data class Gstr1ExportInvoice(
    val voucherId: String,
    val invoiceNumber: String,
    val invoiceDate: LocalDate,
    val taxableValue: Money
)

/** Table 8 - Nil-rated/Exempt/Non-GST outward supplies, summarized by [SupplyNatureBucket] x
 * intra/inter-state (the two axes the real Table 8 splits on) - never invoice-level, matching the
 * statutory table's own shape. */
enum class SupplyNatureBucket { NIL_RATED, EXEMPT }

data class Gstr1NilRatedRow(
    val bucket: SupplyNatureBucket,
    val interState: Boolean,
    val taxableValue: Money
)

/** Table 12 - HSN/SAC-wise summary of ALL outward supplies (taxable, nil, exempt, export alike) -
 * the one table that is genuinely rate+HSN invariant of registration status. [uqc] (unit of
 * measure) is absent - this domain has no per-line unit fact on [com.example.accounting.domain.taxation.gst.GstTransaction]
 * itself (only [com.example.accounting.domain.itemclassification.HsnSacCode] carries a UQC, and
 * only for stock items - a GST-only or service line has none), so it is never fabricated. */
data class Gstr1HsnRow(
    val hsnSacCode: String,
    val gstRatePercent: Double,
    val totalQuantity: Double?,
    val taxableValue: Money,
    val cgst: Money,
    val sgst: Money,
    val igst: Money,
    val cess: Money
) {
    val totalValue: Money get() = taxableValue + cgst + sgst + igst + cess
}

/** Table 13 - Documents issued summary, one row per [natureOfDocument] (this pass covers the GST-
 * bearing outward document types this domain actually posts: Sales Invoice, Credit Note, Debit
 * Note - Receipt/Payment/Refund vouchers and Delivery Challans are non-GST documents in this
 * codebase and are explicitly out of scope, never silently reported as zero). [seriesFrom]/
 * [seriesTo] are the first/last [com.example.accounting.domain.accounting.Voucher.voucherNumber]
 * in the period by simple string order - a best-effort presentation field, not authoritative for a
 * non-sequential numbering scheme (documented, never silently wrong-but-confident). Cancelled
 * vouchers ARE included in [totalCount]/[cancelledCount] (Table 13 explicitly requires this, unlike
 * every other GSTR-1 table which excludes cancelled vouchers entirely). */
data class Gstr1DocumentSeriesRow(
    val natureOfDocument: String,
    val seriesFrom: String,
    val seriesTo: String,
    val totalCount: Int,
    val cancelledCount: Int
) {
    val netIssued: Int get() = totalCount - cancelledCount
}

enum class Gstr1ValidationSeverity { ERROR, WARNING }

/** One validation finding (Section 13/29-style, mirroring [GstReturnSection.errorsJson]'s existing
 * shape) - [severity] ERROR blocks READY (mirrors [AccountingRepository.validateGstReturn]'s
 * existing unresolved-Place-of-Supply check), WARNING is surfaced but does not block. */
data class Gstr1ValidationIssue(
    val severity: Gstr1ValidationSeverity,
    val code: String,
    val message: String,
    val voucherId: String? = null
)

/** The complete Part 1 GSTR-1 draft - one instance per (GstReturn, period). Every list defaults to
 * empty (never omitted) so JSON/CSV export always emits every table with a consistent schema,
 * matching [ExportJsonSerializer]'s own "every record carries the same keys" discipline. */
data class Gstr1ReturnData(
    val companyGstin: String,
    val periodKey: String,
    val b2b: List<Gstr1B2bParty> = emptyList(),
    val b2cl: List<Gstr1B2clInvoice> = emptyList(),
    val b2cs: List<Gstr1B2csRow> = emptyList(),
    val cdnr: List<Gstr1CdnrParty> = emptyList(),
    val cdnur: List<Gstr1Note> = emptyList(),
    val exports: List<Gstr1ExportInvoice> = emptyList(),
    val nilRated: List<Gstr1NilRatedRow> = emptyList(),
    val hsn: List<Gstr1HsnRow> = emptyList(),
    val documentsIssued: List<Gstr1DocumentSeriesRow> = emptyList(),
    val validationIssues: List<Gstr1ValidationIssue> = emptyList()
) {
    val hasBlockingErrors: Boolean get() = validationIssues.any { it.severity == Gstr1ValidationSeverity.ERROR }
}
