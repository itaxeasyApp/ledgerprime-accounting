package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 8A, Part 1 - two distinct JSON shapes for the same [Gstr1ReturnData], matching this
 * project's existing "readable internal tree vs. statutory portal tree" split (see
 * `domain.export.ExportJsonSerializer` vs. `domain.export.GstrJsonSerializer` for the identical
 * precedent on the older flat GST-transaction export).
 *
 * [toTree] - readable field names, used for [GstReturnSection.resultDataJson] persistence and the
 * plain JSON export format. [Gstr1PortalJsonSerializer] - the real GST Network GSTR-1 JSON field
 * names (`ctin`, `inv`, `inum`, `idt`, `val`, `pos`, `rchrg`, `itms`, `txval`, `camt`, `samt`,
 * `iamt`, `csamt`, `rt`), used for the GSTR_JSON offline-upload export format. Both read the exact
 * same already-computed [Gstr1ReturnData] - this file only ever re-shapes it, never recalculates.
 */

private fun Gstr1RateLine.toTree(): Map<String, Any?> = linkedMapOf(
    "gstRatePercent" to gstRatePercent, "taxableValuePaise" to taxableValue.paise,
    "cgstPaise" to cgst.paise, "sgstPaise" to sgst.paise, "igstPaise" to igst.paise, "cessPaise" to cess.paise,
    "invoiceValuePaise" to invoiceValue.paise
)

private fun Gstr1Invoice.toTree(): Map<String, Any?> = linkedMapOf(
    "voucherId" to voucherId, "invoiceNumber" to invoiceNumber, "invoiceDate" to invoiceDate.toString(),
    "posStateCode" to posStateCode, "reverseCharge" to reverseCharge, "invoiceValuePaise" to invoiceValue.paise,
    "rateLines" to rateLines.map { it.toTree() }
)

fun Gstr1B2bParty.toTree(): Map<String, Any?> = linkedMapOf(
    "recipientGstin" to recipientGstin, "invoices" to invoices.map { it.toTree() }
)

fun Gstr1B2clInvoice.toTree(): Map<String, Any?> = linkedMapOf(
    "voucherId" to voucherId, "invoiceNumber" to invoiceNumber, "invoiceDate" to invoiceDate.toString(),
    "posStateCode" to posStateCode, "invoiceValuePaise" to invoiceValue.paise, "rateLines" to rateLines.map { it.toTree() }
)

fun Gstr1B2csRow.toTree(): Map<String, Any?> = linkedMapOf(
    "posStateCode" to posStateCode, "gstRatePercent" to gstRatePercent, "taxableValuePaise" to taxableValue.paise,
    "cgstPaise" to cgst.paise, "sgstPaise" to sgst.paise, "igstPaise" to igst.paise, "cessPaise" to cess.paise
)

private fun Gstr1Note.toTree(): Map<String, Any?> = linkedMapOf(
    "voucherId" to voucherId, "noteNumber" to noteNumber, "noteDate" to noteDate.toString(), "noteType" to noteType.name,
    "originalInvoiceNumber" to originalInvoiceNumber, "originalInvoiceDate" to originalInvoiceDate.toString(),
    "posStateCode" to posStateCode, "reverseCharge" to reverseCharge, "noteValuePaise" to noteValue.paise,
    "amendsFiledPeriod" to amendsFiledPeriod, "rateLines" to rateLines.map { it.toTree() }
)

fun Gstr1CdnrParty.toTree(): Map<String, Any?> = linkedMapOf(
    "recipientGstin" to recipientGstin, "notes" to notes.map { it.toTree() }
)

fun List<Gstr1Note>.toCdnurTree(): List<Map<String, Any?>> = map { it.toTree() }

fun Gstr1ExportInvoice.toTree(): Map<String, Any?> = linkedMapOf(
    "voucherId" to voucherId, "invoiceNumber" to invoiceNumber, "invoiceDate" to invoiceDate.toString(),
    "taxableValuePaise" to taxableValue.paise
)

fun Gstr1NilRatedRow.toTree(): Map<String, Any?> = linkedMapOf(
    "bucket" to bucket.name, "interState" to interState, "taxableValuePaise" to taxableValue.paise
)

fun Gstr1HsnRow.toTree(): Map<String, Any?> = linkedMapOf(
    "hsnSacCode" to hsnSacCode, "gstRatePercent" to gstRatePercent, "totalQuantity" to totalQuantity,
    "taxableValuePaise" to taxableValue.paise, "cgstPaise" to cgst.paise, "sgstPaise" to sgst.paise,
    "igstPaise" to igst.paise, "cessPaise" to cess.paise, "totalValuePaise" to totalValue.paise
)

fun Gstr1DocumentSeriesRow.toTree(): Map<String, Any?> = linkedMapOf(
    "natureOfDocument" to natureOfDocument, "seriesFrom" to seriesFrom, "seriesTo" to seriesTo,
    "totalCount" to totalCount, "cancelledCount" to cancelledCount, "netIssued" to netIssued
)

fun Gstr1ValidationIssue.toTree(): Map<String, Any?> = linkedMapOf(
    "severity" to severity.name, "code" to code, "message" to message, "voucherId" to voucherId
)

fun Gstr1ReturnData.toTree(): Map<String, Any?> = linkedMapOf(
    "companyGstin" to companyGstin, "periodKey" to periodKey,
    "b2b" to b2b.map { it.toTree() }, "b2cl" to b2cl.map { it.toTree() }, "b2cs" to b2cs.map { it.toTree() },
    "cdnr" to cdnr.map { it.toTree() }, "cdnur" to cdnur.toCdnurTree(), "exports" to exports.map { it.toTree() },
    "nilRated" to nilRated.map { it.toTree() }, "hsn" to hsn.map { it.toTree() },
    "documentsIssued" to documentsIssued.map { it.toTree() },
    "validationIssues" to validationIssues.map { it.toTree() },
    "hasBlockingErrors" to hasBlockingErrors
)

/**
 * The real GST Network GSTR-1 JSON field convention (publicly documented in the GSTN offline-tool
 * schema) - `itms` rate-wise item breakup uses `itm_det` for the tax figures, one `itms` entry per
 * rate. Money fields are rupees with 2 decimals (GSTN's own convention, unlike this project's own
 * paise-Long convention elsewhere) - converted ONLY at this final serialization boundary, never
 * upstream.
 *
 * Phase 8, Step 5 - GSTN conventions applied here: `fp` is MMYYYY (a quarter is reported as its last
 * month); every date is dd-MM-yyyy; `gt`/`cur_gt` carry the aggregate turnover; B2CS rows carry
 * `sply_ty` (INTRA/INTER); CDNUR entries are flat note objects typed B2CL/EXPWP/EXPWOP; Nil `sply_ty`
 * is the INTRB2B/INTRB2C/INTRAB2B/INTRAB2C enumeration (one row per code); HSN rows carry `uqc`;
 * export invoices carry `itms`; Table 13 `doc_num` is the GSTN nature-of-document code; and a note
 * (Credit/Debit) is reported with POSITIVE values - its type is given by `ntty`, not by a minus sign.
 * A B2CS row is a net summary and may legitimately be negative.
 */
object Gstr1PortalJsonSerializer {
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    private fun LocalDate.gstn(): String = format(DATE_FORMAT)

    private fun Long.toRupees(): Double = this / 100.0

    /** GSTN `fp`: MMYYYY. A monthly key ("202604") becomes "042026"; a quarterly key ("2026-27-Q1")
     * becomes its last month ("062026"). Anything unrecognised is returned unchanged. */
    fun toGstnReturnPeriod(periodKey: String): String {
        Regex("^(\\d{4})(\\d{2})$").matchEntire(periodKey)?.let { return it.groupValues[2] + it.groupValues[1] }
        Regex("^(\\d{4})-\\d{2}-Q([1-4])$").matchEntire(periodKey)?.let {
            val startYear = it.groupValues[1].toInt()
            return when (it.groupValues[2]) {
                "1" -> "06$startYear"
                "2" -> "09$startYear"
                "3" -> "12$startYear"
                else -> "03${startYear + 1}"
            }
        }
        return periodKey
    }

    private fun Gstr1RateLine.toItm(num: Int, absolute: Boolean = false): Map<String, Any?> {
        fun Money.v(): Double = (if (absolute) abs() else this).paise.toRupees()
        return linkedMapOf(
            "num" to num,
            "itm_det" to linkedMapOf(
                "rt" to gstRatePercent, "txval" to taxableValue.v(),
                "camt" to cgst.v(), "samt" to sgst.v(),
                "iamt" to igst.v(), "csamt" to cess.v()
            )
        )
    }

    private fun Gstr1Invoice.toInv(): Map<String, Any?> = linkedMapOf(
        "inum" to invoiceNumber, "idt" to invoiceDate.gstn(), "val" to invoiceValue.paise.toRupees(),
        "pos" to posStateCode, "rchrg" to if (reverseCharge) "Y" else "N", "inv_typ" to "R",
        "itms" to rateLines.mapIndexed { i, l -> l.toItm(i + 1) }
    )

    private fun Gstr1B2clInvoice.toInv(): Map<String, Any?> = linkedMapOf(
        "inum" to invoiceNumber, "idt" to invoiceDate.gstn(), "val" to invoiceValue.paise.toRupees(),
        "pos" to posStateCode, "itms" to rateLines.mapIndexed { i, l -> l.toItm(i + 1) }
    )

    /** Credit/Debit Note fields, all values positive - `ntty` carries the direction. */
    private fun Gstr1Note.noteFields(): LinkedHashMap<String, Any?> = linkedMapOf(
        "ntty" to if (noteType == NoteType.CREDIT) "C" else "D",
        "nt_num" to noteNumber, "nt_dt" to noteDate.gstn(), "val" to noteValue.abs().paise.toRupees(),
        "pos" to posStateCode,
        "itms" to rateLines.mapIndexed { i, l -> l.toItm(i + 1, absolute = true) }
    )

    private fun Gstr1Note.toCdnrNote(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(noteFields())
        put("rchrg", if (reverseCharge) "Y" else "N")
        put("inv_typ", "R")
    }

    private fun Gstr1Note.toCdnurNote(): Map<String, Any?> {
        val typ = when {
            !isExport -> "B2CL"
            rateLines.any { it.igst.paise != 0L } -> "EXPWP"
            else -> "EXPWOP"
        }
        return linkedMapOf<String, Any?>("typ" to typ).apply { putAll(noteFields()) }
    }

    private fun splyTy(pos: String, companyStateCode: String): String =
        if (companyStateCode.length == 2 && pos.isNotBlank() && pos != companyStateCode) "INTER" else "INTRA"

    private fun nilSplyTy(interState: Boolean, registered: Boolean): String =
        (if (interState) "INTR" else "INTRA") + (if (registered) "B2B" else "B2C")

    fun serialize(data: Gstr1ReturnData): Map<String, Any?> {
        val companyStateCode = data.companyGstin.trim().take(2)
        return linkedMapOf(
            "gstin" to data.companyGstin,
            "fp" to toGstnReturnPeriod(data.periodKey),
            "gt" to data.aggregateTurnoverPrevFy.paise.toRupees(),
            "cur_gt" to data.cumulativeTurnoverCurrentFy.paise.toRupees(),
            "b2b" to data.b2b.map { party ->
                linkedMapOf("ctin" to party.recipientGstin, "inv" to party.invoices.map { it.toInv() })
            },
            "b2cl" to data.b2cl.groupBy { it.posStateCode }.map { (pos, invoices) ->
                linkedMapOf("pos" to pos, "inv" to invoices.map { it.toInv() })
            },
            "b2cs" to data.b2cs.map { row ->
                linkedMapOf(
                    "sply_ty" to splyTy(row.posStateCode, companyStateCode),
                    "typ" to "OE", "pos" to row.posStateCode, "rt" to row.gstRatePercent,
                    "txval" to row.taxableValue.paise.toRupees(), "camt" to row.cgst.paise.toRupees(),
                    "samt" to row.sgst.paise.toRupees(), "iamt" to row.igst.paise.toRupees(), "csamt" to row.cess.paise.toRupees()
                )
            },
            "cdnr" to data.cdnr.map { party ->
                linkedMapOf("ctin" to party.recipientGstin, "nt" to party.notes.map { it.toCdnrNote() })
            },
            "cdnur" to data.cdnur.map { it.toCdnurNote() },
            "exp" to data.exports.groupBy { if (it.igst.paise != 0L) "WPAY" else "WOPAY" }.map { (expTyp, invoices) ->
                linkedMapOf(
                    "exp_typ" to expTyp,
                    "inv" to invoices.map { inv ->
                        linkedMapOf(
                            "inum" to inv.invoiceNumber, "idt" to inv.invoiceDate.gstn(),
                            "val" to (inv.taxableValue + inv.igst).paise.toRupees(),
                            "itms" to listOf(
                                linkedMapOf(
                                    "txval" to inv.taxableValue.paise.toRupees(), "rt" to inv.gstRatePercent,
                                    "iamt" to inv.igst.paise.toRupees(), "csamt" to 0.0
                                )
                            )
                        )
                    }
                )
            },
            "nil" to linkedMapOf(
                "inv" to data.nilRated.groupBy { nilSplyTy(it.interState, it.registered) }.map { (sply, rows) ->
                    linkedMapOf(
                        "sply_ty" to sply,
                        "expt_amt" to rows.filter { it.bucket == SupplyNatureBucket.EXEMPT }.sumOf { it.taxableValue.paise }.toRupees(),
                        "nil_amt" to rows.filter { it.bucket == SupplyNatureBucket.NIL_RATED }.sumOf { it.taxableValue.paise }.toRupees(),
                        "ngsup_amt" to 0.0
                    )
                }
            ),
            "hsn" to linkedMapOf(
                "data" to data.hsn.mapIndexed { index, row ->
                    linkedMapOf(
                        "num" to index + 1, "hsn_sc" to row.hsnSacCode, "uqc" to row.uqc, "qty" to (row.totalQuantity ?: 0.0),
                        "rt" to row.gstRatePercent, "val" to row.totalValue.paise.toRupees(), "txval" to row.taxableValue.paise.toRupees(),
                        "camt" to row.cgst.paise.toRupees(), "samt" to row.sgst.paise.toRupees(),
                        "iamt" to row.igst.paise.toRupees(), "csamt" to row.cess.paise.toRupees()
                    )
                }
            ),
            "doc_issue" to linkedMapOf(
                "doc_det" to data.documentsIssued.map { row ->
                    linkedMapOf(
                        "doc_num" to row.natureCode, "docs" to listOf(
                            linkedMapOf(
                                "num" to 1, "from" to row.seriesFrom, "to" to row.seriesTo,
                                "totnum" to row.totalCount, "cancel" to row.cancelledCount, "net_issue" to row.netIssued
                            )
                        )
                    )
                }
            )
        )
    }
}
