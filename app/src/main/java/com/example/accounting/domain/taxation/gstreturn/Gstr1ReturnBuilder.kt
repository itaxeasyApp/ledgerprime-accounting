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
 * Explicitly out of scope for this pass (never fabricated): Table 11A/11B advances received/
 * adjusted (no advance-receipt model exists anywhere in this codebase), export shipping-bill
 * detail, e-commerce operator GSTIN, and a full B2BA/CDNRA-style separate amendment table (see
 * [Gstr1Note.amendsFiledPeriod]'s own KDoc for the one honest amendment signal this pass does
 * compute).
 */
object Gstr1ReturnBuilder {
    /** Table 5's statutory threshold (Rule 33 follow-up) - an unregistered, inter-state invoice
     * strictly above this value is B2CL; at or below it is folded into the B2CS state+rate summary. */
    val B2CL_THRESHOLD: Money = Money.fromPaise(2_50_000_00L)

    /**
     * @param transactions Active, period-filtered, OUTPUT-direction [GstTransaction] rows for one
     *   return period (every row's own [GstTransaction.voucherId] must key into [allVouchersById]).
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
        // One row per (voucherId, gstRatePercent) - a single invoice/note can carry more than one
        // tax rate across its lines (Section 12's own reasoning), never collapsed into one blended rate.
        val byVoucherAndRate: Map<String, List<GstTransaction>> = transactions
            .filter { it.voucherId != null }
            .groupBy { it.voucherId!! }

        val saleLikeTypes = setOf(VoucherType.SALES)
        val noteTypes = setOf(VoucherType.CREDIT_NOTE)

        val b2bParties = linkedMapOf<String, MutableList<Gstr1Invoice>>()
        val b2cl = mutableListOf<Gstr1B2clInvoice>()
        val b2csRows = linkedMapOf<Pair<String, Double>, Gstr1B2csRow>()
        val cdnrParties = linkedMapOf<String, MutableList<Gstr1Note>>()
        val cdnur = mutableListOf<Gstr1Note>()
        val exports = mutableListOf<Gstr1ExportInvoice>()
        val nilRows = linkedMapOf<Pair<SupplyNatureBucket, Boolean>, Money>()
        val hsnRows = linkedMapOf<Pair<String, Double>, MutableList<GstTransaction>>()

        for ((voucherId, lines) in byVoucherAndRate) {
            val voucher = allVouchersById[voucherId] ?: continue
            val first = lines.first()
            val isRegistered = first.partyGstin.isNotBlank()
            val reverseCharge = lines.any { it.chargeType == GstChargeType.REVERSE_CHARGE }

            // HSN summary (Table 12) always includes every outward line, regardless of nature.
            lines.forEach { gt ->
                hsnRows.getOrPut(gt.hsnSacCode to gt.gstRatePercent) { mutableListOf() }.add(gt)
            }

            when {
                first.supplyNature == GstSupplyNature.EXPORT || first.supplyType == SupplyType.EXPORT -> {
                    if (voucher.voucherType in saleLikeTypes) {
                        exports += Gstr1ExportInvoice(
                            voucherId = voucherId,
                            invoiceNumber = voucher.voucherNumber,
                            invoiceDate = voucher.date,
                            taxableValue = lines.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount }
                        )
                    }
                }
                first.supplyNature == GstSupplyNature.EXEMPT || first.supplyNature == GstSupplyNature.NIL_RATED ||
                    first.supplyType == SupplyType.EXEMPT -> {
                    val bucket = if (first.supplyNature == GstSupplyNature.NIL_RATED) SupplyNatureBucket.NIL_RATED else SupplyNatureBucket.EXEMPT
                    val interState = false // geography bypassed for EXEMPT/NIL_RATED (see GstCalculationEngine.calculateDetailed) - never guessed.
                    val key = bucket to interState
                    val taxable = lines.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount }
                    nilRows[key] = (nilRows[key] ?: Money.ZERO) + taxable
                }
                voucher.voucherType in noteTypes -> {
                    val original = voucher.referenceVoucherId?.let { allVouchersById[it] }
                    val rateLines = lines.groupBy { it.gstRatePercent }.map { (rate, rows) -> rows.toRateLine(rate) }
                    val note = Gstr1Note(
                        voucherId = voucherId,
                        noteNumber = voucher.voucherNumber,
                        noteDate = voucher.date,
                        noteType = NoteType.CREDIT,
                        originalInvoiceNumber = original?.voucherNumber ?: "",
                        originalInvoiceDate = original?.date ?: voucher.date,
                        posStateCode = first.placeOfSupply,
                        reverseCharge = reverseCharge,
                        rateLines = rateLines,
                        amendsFiledPeriod = if (original != null) originalInvoicePeriodFiled(original) else false
                    )
                    if (isRegistered) {
                        cdnrParties.getOrPut(first.partyGstin) { mutableListOf() }.add(note)
                    } else {
                        cdnur += note
                    }
                }
                voucher.voucherType in saleLikeTypes -> {
                    val rateLines = lines.groupBy { it.gstRatePercent }.map { (rate, rows) -> rows.toRateLine(rate) }
                    val invoiceValue = rateLines.fold(Money.ZERO) { acc, l -> acc + l.invoiceValue }
                    if (isRegistered) {
                        val invoice = Gstr1Invoice(voucherId, voucher.voucherNumber, voucher.date, first.placeOfSupply, reverseCharge, rateLines)
                        b2bParties.getOrPut(first.partyGstin) { mutableListOf() }.add(invoice)
                    } else if (first.supplyType == SupplyType.INTER_STATE && invoiceValue > B2CL_THRESHOLD) {
                        b2cl += Gstr1B2clInvoice(voucherId, voucher.voucherNumber, voucher.date, first.placeOfSupply, rateLines)
                    } else {
                        rateLines.forEach { rl ->
                            val key = first.placeOfSupply to rl.gstRatePercent
                            val existing = b2csRows[key]
                            b2csRows[key] = if (existing == null) {
                                Gstr1B2csRow(first.placeOfSupply, rl.gstRatePercent, rl.taxableValue, rl.cgst, rl.sgst, rl.igst, rl.cess)
                            } else {
                                existing.copy(
                                    taxableValue = existing.taxableValue + rl.taxableValue, cgst = existing.cgst + rl.cgst,
                                    sgst = existing.sgst + rl.sgst, igst = existing.igst + rl.igst, cess = existing.cess + rl.cess
                                )
                            }
                        }
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
            val (hsnCode, rate) = key
            Gstr1HsnRow(
                hsnSacCode = hsnCode, gstRatePercent = rate,
                totalQuantity = rows.mapNotNull { it.quantity?.doubleValue }.takeIf { it.size == rows.size }?.sum(),
                taxableValue = rows.fold(Money.ZERO) { acc, l -> acc + l.taxableAmount },
                cgst = rows.fold(Money.ZERO) { acc, l -> acc + l.cgst },
                sgst = rows.fold(Money.ZERO) { acc, l -> acc + l.sgst },
                igst = rows.fold(Money.ZERO) { acc, l -> acc + l.igst },
                cess = rows.fold(Money.ZERO) { acc, l -> acc + l.cess }
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
            nilRated = nilRows.map { (key, value) -> Gstr1NilRatedRow(key.first, key.second, value) },
            hsn = hsn,
            documentsIssued = documentsIssued
        )
    }

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
                    cancelledCount = list.count { it.isCancelled }
                )
            }
}
