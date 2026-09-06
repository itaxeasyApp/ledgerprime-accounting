package com.example.accounting.domain.taxation.gstreturn

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
 */
object Gstr1PortalJsonSerializer {
    private fun Long.toRupees(): Double = this / 100.0

    private fun Gstr1RateLine.toItm(): Map<String, Any?> = linkedMapOf(
        "num" to null,
        "itm_det" to linkedMapOf(
            "rt" to gstRatePercent, "txval" to taxableValue.paise.toRupees(),
            "camt" to cgst.paise.toRupees(), "samt" to sgst.paise.toRupees(),
            "iamt" to igst.paise.toRupees(), "csamt" to cess.paise.toRupees()
        )
    )

    private fun Gstr1Invoice.toInv(): Map<String, Any?> = linkedMapOf(
        "inum" to invoiceNumber, "idt" to invoiceDate.toString(), "val" to invoiceValue.paise.toRupees(),
        "pos" to posStateCode, "rchrg" to if (reverseCharge) "Y" else "N", "inv_typ" to "R",
        "itms" to rateLines.map { it.toItm() }
    )

    private fun Gstr1B2clInvoice.toInv(): Map<String, Any?> = linkedMapOf(
        "inum" to invoiceNumber, "idt" to invoiceDate.toString(), "val" to invoiceValue.paise.toRupees(),
        "pos" to posStateCode, "itms" to rateLines.map { it.toItm() }
    )

    private fun Gstr1Note.toNt(): Map<String, Any?> = linkedMapOf(
        "ntty" to if (noteType == NoteType.CREDIT) "C" else "D",
        "nt_num" to noteNumber, "nt_dt" to noteDate.toString(), "val" to noteValue.paise.toRupees(),
        "pos" to posStateCode, "rchrg" to if (reverseCharge) "Y" else "N",
        "itms" to rateLines.map { it.toItm() }
    )

    fun serialize(data: Gstr1ReturnData): Map<String, Any?> = linkedMapOf(
        "gstin" to data.companyGstin,
        "fp" to data.periodKey,
        "b2b" to data.b2b.map { party ->
            linkedMapOf("ctin" to party.recipientGstin, "inv" to party.invoices.map { it.toInv() })
        },
        "b2cl" to data.b2cl.groupBy { it.posStateCode }.map { (pos, invoices) ->
            linkedMapOf("pos" to pos, "inv" to invoices.map { it.toInv() })
        },
        "b2cs" to data.b2cs.map { row ->
            linkedMapOf(
                "typ" to "OE", "pos" to row.posStateCode, "rt" to row.gstRatePercent,
                "txval" to row.taxableValue.paise.toRupees(), "camt" to row.cgst.paise.toRupees(),
                "samt" to row.sgst.paise.toRupees(), "iamt" to row.igst.paise.toRupees(), "csamt" to row.cess.paise.toRupees()
            )
        },
        "cdnr" to data.cdnr.map { party ->
            linkedMapOf("ctin" to party.recipientGstin, "nt" to party.notes.map { it.toNt() })
        },
        "cdnur" to data.cdnur.map { note ->
            linkedMapOf("typ" to "B2CL", "nt" to listOf(note.toNt()))
        },
        "exp" to data.exports.groupBy { "" }.flatMap { (_, invoices) ->
            listOf(
                linkedMapOf(
                    "exp_typ" to "WPAY",
                    "inv" to invoices.map { linkedMapOf("inum" to it.invoiceNumber, "idt" to it.invoiceDate.toString(), "val" to it.taxableValue.paise.toRupees()) }
                )
            )
        },
        "nil" to linkedMapOf(
            "inv" to data.nilRated.map { row ->
                linkedMapOf(
                    "sply_ty" to if (row.interState) "INTER" else "INTRA",
                    "expt_amt" to (if (row.bucket == SupplyNatureBucket.EXEMPT) row.taxableValue.paise.toRupees() else 0.0),
                    "nil_amt" to (if (row.bucket == SupplyNatureBucket.NIL_RATED) row.taxableValue.paise.toRupees() else 0.0),
                    "ngsup_amt" to 0.0
                )
            }
        ),
        "hsn" to linkedMapOf(
            "data" to data.hsn.mapIndexed { index, row ->
                linkedMapOf(
                    "num" to index + 1, "hsn_sc" to row.hsnSacCode, "qty" to row.totalQuantity,
                    "rt" to row.gstRatePercent, "txval" to row.taxableValue.paise.toRupees(),
                    "camt" to row.cgst.paise.toRupees(), "samt" to row.sgst.paise.toRupees(),
                    "iamt" to row.igst.paise.toRupees(), "csamt" to row.cess.paise.toRupees()
                )
            }
        ),
        "doc_issue" to linkedMapOf(
            "doc_det" to data.documentsIssued.mapIndexed { index, row ->
                linkedMapOf(
                    "doc_num" to index + 1, "docs" to listOf(
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
