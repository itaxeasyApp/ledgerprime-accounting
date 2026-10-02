package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.SupplyType

/**
 * Phase 8A, Part 1 - builds the real, statutorily-named GSTR-1 tables from already-persisted
 * [GstTransaction]/[Voucher] facts (never a second GST calculation - every rupee here is a
 * regroup/aggregate of numbers [com.example.accounting.domain.taxation.gst.GstCalculationEngine]
 * already computed at posting time). GSTR-1 is OUTWARD supplies only - callers pass the already
 * period-filtered, already-active (non-cancelled) OUTPUT-direction transactions
 * (see [com.example.accounting.data.repository.AccountingRepository.getActiveGstTransactionsForPeriod]).
 *
 * Phase 8, Step 5 corrections: each LINE is classified on its own (a mixed taxable/exempt invoice
 * no longer follows its first line); a GST-only row (no [Voucher]) is grouped by its transaction
 * group and reaches the tables that need no invoice number (B2CS, Nil, HSN); a Credit Note against
 * an export is reported (CDNUR, export type) instead of dropped; and an unregistered Credit Note
 * nets into B2CS unless it is a genuine B2CL note.
 *
 * Explicitly out of scope for this pass (never fabricated): Table 11A/11B advances received/
 * adjusted (no advance-receipt model exists anywhere in this codebase), export shipping-bill
 * detail, e-commerce operator GSTIN, a full B2BA/CDNRA-style separate amendment table (see
 * [Gstr1Note.amendsFiledPeriod]'s own KDoc for the one honest amendment signal this pass does
 * compute), and any table that needs a document number for a GST-only row (a GST-only B2B/B2CL/
 * CDNR/EXP row has no invoice number to report, so it is not placed in those tables).
 */
object Gstr1ReturnBuilder {
    /** Table 5's statutory threshold (Rule 33 follow-up) - an unregistered, inter-state invoice
     * strictly above this value is B2CL; at or below it is folded into the B2CS state+rate summary. */
    val B2CL_THRESHOLD: Money = Money.fromPaise(2_50_000_00L)

    /**
     * @param transactions Active, period-filtered, OUTPUT-direction [GstTransaction] rows for one
     *   return period. A row with a [GstTransaction.voucherId] must key into [allVouchersById]; a
     *   GST-only row (null voucherId) is grouped by its own transaction group.
     * @param allVouchersById EVERY voucher for the company, by id - deliberately not period-scoped:
     *   a Credit Note's original invoice ([Voucher.referenceVoucherId]) is frequently from an
     *   earlier period (or already cancelled by a later correction), so this must be a superset,
     *   never just the current period's active vouchers.
     * @param allVouchersInPeriodForDocSummary EVERY voucher (including cancelled) dated within the
     *   period, for Table 13 only - deliberately a separate parameter (every other table excludes
     *   cancelled vouchers entirely; Table 13 is the one exception).
     * @param originalInvoicePeriodFiled resolves whether the ORIGINAL invoice a note references
     *   belongs to an already-FILED [GstReturn] period - the sole signal behind
     *   [Gstr1Note.amendsFiledPeriod]. Passed as a function (not a repository call) so this builder
     *   stays pure/testable; the repository wires the real lookup.
     */
    suspend fun build(
        companyGstin: String,
        periodKey: String,
        transactions: List<GstTransaction>,
        allVouchersById: Map<String, Voucher>,
        allVouchersInPeriodForDocSummary: List<Voucher>,
        originalInvoicePeriodFiled: suspend (Voucher) -> Boolean = { false }
    ): Gstr1ReturnData {
        val companyStateCode = companyGstin.trim().take(2)
        fun isInterState(pos: String) = companyStateCode.length == 2 && pos.isNotBlank() && pos != companyStateCode

        // One document per voucher; a GST-only row (no voucher) is keyed by its transaction group,
        // or - for a row with no group id - by its own id, so it is never silently dropped.
        val byDocument: Map<String, List<GstTransaction>> = transactions.groupBy {
            it.voucherId ?: it.transactionGroupId.ifBlank { it.gstTransactionId }
        }

        val b2bParties = linkedMapOf<String, MutableList<Gstr1Invoice>>()
        val b2cl = mutableListOf<Gstr1B2clInvoice>()
        val b2csRows = linkedMapOf<Pair<String, Double>, Gstr1B2csRow>()
        val cdnrParties = linkedMapOf<String, MutableList<Gstr1Note>>()
        val cdnur = mutableListOf<Gstr1Note>()
        val exports = mutableListOf<Gstr1ExportInvoice>()
        val nilRows = linkedMapOf<Triple<SupplyNatureBucket, Boolean, Boolean>, Money>()
        val hsnRows = linkedMapOf<Triple<String, Double, String>, MutableList<GstTransaction>>()

        fun netIntoB2cs(pos: String, rateLines: List<Gstr1RateLine>) {
            rateLines.forEach { rl ->
                val key = pos to rl.gstRatePercent
                val existing = b2csRows[key]
                b2csRows[key] = if (existing == null) {
                    Gstr1B2csRow(pos, rl.gstRatePercent, rl.taxableValue, rl.cgst, rl.sgst, rl.igst, rl.cess)
                } else {
                    existing.copy(
                        taxableValue = existing.taxableValue + rl.taxableValue, cgst = existing.cgst + rl.cgst,
                        sgst = existing.sgst + rl.sgst, igst = existing.igst + rl.igst, cess = existing.cess + rl.cess
                    )
                }
            }
        }

        suspend fun noteOf(voucher: Voucher, ls: List<GstTransaction>, isExport: Boolean): Gstr1Note {
            val first = ls.first()
            val original = voucher.referenceVoucherId?.let { allVouchersById[it] }
            return Gstr1Note(
                voucherId = voucher.voucherId,
                noteNumber = voucher.voucherNumber,
                noteDate = voucher.date,
                noteType = NoteType.CREDIT,
                originalInvoiceNumber = original?.voucherNumber ?: "",
                originalInvoiceDate = original?.date ?: voucher.date,
                posStateCode = first.placeOfSupply,
                reverseCharge = ls.any { it.chargeType == GstChargeType.REVERSE_CHARGE },
                rateLines = ls.groupBy { it.gstRatePercent }.map { (rate, rows) -> rows.toRateLine(rate) },
                amendsFiledPeriod = if (original != null) originalInvoicePeriodFiled(original) else false,
                isExport = isExport
            )
        }

        for ((_, lines) in byDocument) {
            val first = lines.first()
            val voucher: Voucher? = first.voucherId?.let { allVouchersById[it] }
            if (first.voucherId != null && voucher == null) continue
            val docType = first.voucherType
            val isRegistered = first.partyGstin.isNotBlank()

            // HSN summary (Table 12) always includes every outward line, regardless of nature.
            lines.forEach { gt ->
                hsnRows.getOrPut(Triple(gt.hsnSacCode, gt.gstRatePercent, GstnUqc.fromUnit(gt.quantity?.unit))) { mutableListOf() }.add(gt)
            }

            // Each line is classified on its own - a mixed invoice is split, never judged by line 1.
            val exportLines = lines.filter { it.isExportLine() }
            val nilLines = lines.filter { !it.isExportLine() && it.isNilLine() }
            val taxLines = lines.filter { !it.isExportLine() && !it.isNilLine() }

            if (exportLines.isNotEmpty() && voucher != null) {
                when (docType) {
                    VoucherType.SALES -> exports += Gstr1ExportInvoice(
                        voucherId = voucher.voucherId,
                        invoiceNumber = voucher.voucherNumber,
                        invoiceDate = voucher.date,
                        taxableValue = exportLines.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
                        igst = exportLines.fold(Money.ZERO) { acc, l -> acc + l.igst },
                        gstRatePercent = exportLines.first().gstRatePercent
                    )
                    VoucherType.CREDIT_NOTE -> cdnur += noteOf(voucher, exportLines, isExport = true)
                    else -> Unit
                }
            }

            nilLines.groupBy { Triple(it.nilBucket(), isInterState(it.placeOfSupply), it.partyGstin.isNotBlank()) }
                .forEach { (key, ls) ->
                    nilRows[key] = (nilRows[key] ?: Money.ZERO) + ls.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount }
                }

            if (taxLines.isEmpty()) continue
            val taxFirst = taxLines.first()
            val rateLines = taxLines.groupBy { it.gstRatePercent }.map { (rate, rows) -> rows.toRateLine(rate) }
            val reverseCharge = taxLines.any { it.chargeType == GstChargeType.REVERSE_CHARGE }

            when (docType) {
                VoucherType.CREDIT_NOTE -> {
                    if (isRegistered) {
                        if (voucher != null) cdnrParties.getOrPut(first.partyGstin) { mutableListOf() }.add(noteOf(voucher, taxLines, isExport = false))
                    } else {
                        // CDNUR is only for notes against B2CL invoices; every other unregistered note
                        // nets (as negative amounts) into the B2CS state+rate summary.
                        val noteValue = rateLines.fold(Money.ZERO) { acc, l -> acc + l.invoiceValue }.abs()
                        val originalValue = voucher?.referenceVoucherId?.let { allVouchersById[it] }?.totalAmount ?: Money.ZERO
                        val isB2clNote = taxFirst.supplyType == SupplyType.INTER_STATE &&
                            (noteValue > B2CL_THRESHOLD || originalValue > B2CL_THRESHOLD)
                        if (isB2clNote && voucher != null) cdnur += noteOf(voucher, taxLines, isExport = false)
                        else if (!isB2clNote) netIntoB2cs(taxFirst.placeOfSupply, rateLines)
                    }
                }
                VoucherType.SALES -> {
                    val invoiceValue = rateLines.fold(Money.ZERO) { acc, l -> acc + l.invoiceValue }
                    val isB2clInvoice = taxFirst.supplyType == SupplyType.INTER_STATE && invoiceValue > B2CL_THRESHOLD
                    if (isRegistered) {
                        if (voucher != null) {
                            b2bParties.getOrPut(first.partyGstin) { mutableListOf() }
                                .add(Gstr1Invoice(voucher.voucherId, voucher.voucherNumber, voucher.date, taxFirst.placeOfSupply, reverseCharge, rateLines))
                        }
                    } else if (isB2clInvoice) {
                        if (voucher != null) b2cl += Gstr1B2clInvoice(voucher.voucherId, voucher.voucherNumber, voucher.date, taxFirst.placeOfSupply, rateLines)
                    } else {
                        netIntoB2cs(taxFirst.placeOfSupply, rateLines)
                    }
                }
                // Any other outward voucher type carrying GST (none exist today - createsGst is only
                // true for SALES/PURCHASE/CREDIT_NOTE/DEBIT_NOTE, and PURCHASE/DEBIT_NOTE are INPUT-
                // direction, already excluded by the caller) - defensively ignored rather than
                // silently misclassified into B2B/B2C.
                else -> Unit
            }
        }

        val hsn = hsnRows.map { (key, rows) ->
            val (hsnCode, rate, uqc) = key
            Gstr1HsnRow(
                hsnSacCode = hsnCode, gstRatePercent = rate,
                totalQuantity = rows.mapNotNull { it.quantity?.doubleValue }.takeIf { it.size == rows.size }?.sum(),
                taxableValue = rows.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
                cgst = rows.fold(Money.ZERO) { acc, l -> acc + l.cgst },
                sgst = rows.fold(Money.ZERO) { acc, l -> acc + l.sgst },
                igst = rows.fold(Money.ZERO) { acc, l -> acc + l.igst },
                cess = rows.fold(Money.ZERO) { acc, l -> acc + l.cess },
                uqc = uqc
            )
        }

        val documentsIssued = buildDocumentSummary(allVouchersInPeriodForDocSummary)

        return Gstr1ReturnData(
            companyGstin = companyGstin,
            periodKey = periodKey,
            b2b = b2bParties.map { (gstin, invoices) -> Gstr1B2bParty(gstin, invoices) },
            b2cl = b2cl,
            b2cs = b2csRows.values.toList(),
            cdnr = cdnrParties.map { (gstin, notes) -> Gstr1CdnrParty(gstin, notes) },
            cdnur = cdnur,
            exports = exports,
            nilRated = nilRows.map { (key, value) -> Gstr1NilRatedRow(key.first, key.second, value, key.third) },
            hsn = hsn,
            documentsIssued = documentsIssued
        )
    }

    private fun GstTransaction.isExportLine(): Boolean = supplyNature == GstSupplyNature.EXPORT || supplyType == SupplyType.EXPORT

    private fun GstTransaction.isNilLine(): Boolean =
        supplyNature == GstSupplyNature.EXEMPT || supplyNature == GstSupplyNature.NIL_RATED || supplyType == SupplyType.EXEMPT

    private fun GstTransaction.nilBucket(): SupplyNatureBucket =
        if (supplyNature == GstSupplyNature.NIL_RATED) SupplyNatureBucket.NIL_RATED else SupplyNatureBucket.EXEMPT

    private fun List<GstTransaction>.toRateLine(rate: Double): Gstr1RateLine = Gstr1RateLine(
        gstRatePercent = rate,
        taxableValue = fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
        cgst = fold(Money.ZERO) { acc, l -> acc + l.cgst },
        sgst = fold(Money.ZERO) { acc, l -> acc + l.sgst },
        igst = fold(Money.ZERO) { acc, l -> acc + l.igst },
        cess = fold(Money.ZERO) { acc, l -> acc + l.cess }
    )

    /** Table 13 - see [Gstr1DocumentSeriesRow]'s own KDoc for the exact scope/limitations. */
    private val documentVoucherTypes = setOf(VoucherType.SALES, VoucherType.CREDIT_NOTE, VoucherType.DEBIT_NOTE)

    /** GSTN Table 13 nature-of-document codes for the document types this domain issues. */
    private fun natureCodeOf(type: VoucherType): Int = when (type) {
        VoucherType.SALES -> 1
        VoucherType.DEBIT_NOTE -> 4
        VoucherType.CREDIT_NOTE -> 5
        else -> 1
    }

    private fun buildDocumentSummary(vouchers: List<Voucher>): List<Gstr1DocumentSeriesRow> =
        vouchers.filter { it.voucherType in documentVoucherTypes }
            .groupBy { it.voucherType }
            .map { (type, list) ->
                val sorted = list.sortedBy { it.voucherNumber }
                Gstr1DocumentSeriesRow(
                    natureOfDocument = type.displayName,
                    seriesFrom = sorted.firstOrNull()?.voucherNumber ?: "",
                    seriesTo = sorted.lastOrNull()?.voucherNumber ?: "",
                    totalCount = list.size,
                    cancelledCount = list.count { it.isCancelled },
                    natureCode = natureCodeOf(type)
                )
            }
}
