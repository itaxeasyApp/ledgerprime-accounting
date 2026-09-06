package com.example.accounting.domain.export

import com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData

/**
 * A generic, report/DTO-agnostic CSV engine (Phase 7E, Section 8/9) - distinct from and never
 * modifying `domain.rendering.CsvExporter` (Phase 7D, frozen, document-line-specific). Every
 * monetary value is written as an exact paise `Long` (never `.toRupeesDouble()`/floating-point,
 * unlike the 7D exporter it deliberately does not reuse) - Section 7's "never export a
 * floating-point representation as the authoritative amount" applies to CSV exactly as it does to
 * JSON. Handles commas/quotes/newlines/Unicode/empty/null per RFC 4180; column and row order are
 * always deterministic (the caller's own list order - never re-sorted).
 */
object CsvEngine {
    /** [rows] must all have the same length as [headers] - callers build rows via each DTO's own
     * `toCsvRow()`/`toCsvRows()` extension, never hand-rolled string concatenation. */
    fun write(headers: List<String>, rows: List<List<String?>>): String {
        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { field(it) }).append("\r\n")
        for (row in rows) {
            sb.append(row.joinToString(",") { field(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** RFC 4180 field escaping: null/empty become an empty field; a field containing a comma,
     * double-quote, or CR/LF is wrapped in quotes with internal quotes doubled. Unicode passes
     * through untouched (no transliteration/encoding narrowing). */
    private fun field(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }
}

// ==================== Per-DTO CSV row mappers (deterministic column order) ====================

fun VoucherExportDto.toCsvHeaders(): List<String> = listOf(
    "voucherId", "voucherNumber", "voucherType", "date", "referenceNumber", "narration",
    "totalAmountPaise", "isPosted", "isCancelled", "ledgerId", "ledgerName", "drCr", "lineAmountPaise", "lineNarration", "lineOrder"
)

/** One row per journal line (a voucher with N lines produces N CSV rows, all sharing the voucher's
 * own header columns) - this is the standard "denormalized ledger export" shape accounting tools
 * expect, not a single summarized row that would lose per-line detail. */
fun VoucherExportDto.toCsvRows(): List<List<String?>> = journalLines.map { line ->
    listOf(
        voucherId, voucherNumber, voucherType.name, date.toString(), referenceNumber, narration,
        totalAmountPaise.toString(), isPosted.toString(), isCancelled.toString(),
        line.ledgerId, line.ledgerName, line.type.name, line.amountPaise.toString(), line.narration, line.lineOrder.toString()
    )
}

// Named distinctly (not `List<T>.toCsvHeaders()`/`toCsvRows()`) - generic extension functions on
// `List<T>` erase to the same JVM signature regardless of `T` ("platform declaration clash"),
// so these can't be overloaded the way the per-DTO-type extensions above are.
fun List<PartyExportDto>.toPartyCsvHeaders(): List<String> = listOf(
    "partyId", "ledgerId", "role", "entityType", "displayName", "contactName", "creditLimitPaise", "paymentTerms", "isActive"
)

fun List<PartyExportDto>.toPartyCsvRows(): List<List<String?>> = map {
    listOf(it.partyId, it.ledgerId, it.role, it.entityType, it.displayName, it.contactName, it.creditLimitPaise?.toString(), it.paymentTerms, it.isActive.toString())
}

fun List<LedgerExportDto>.toLedgerCsvHeaders(): List<String> = listOf(
    "ledgerId", "groupId", "name", "code", "openingBalancePaise", "openingBalanceType",
    "currentBalancePaise", "currentBalanceType", "gstin", "pan", "stateCode", "address", "isSystem", "isActive"
)

fun List<LedgerExportDto>.toLedgerCsvRows(): List<List<String?>> = map {
    listOf(
        it.ledgerId, it.groupId, it.name, it.code, it.openingBalancePaise.toString(), it.openingBalanceType.name,
        it.currentBalancePaise.toString(), it.currentBalanceType.name, it.gstin, it.pan, it.stateCode, it.address,
        it.isSystem.toString(), it.isActive.toString()
    )
}

fun TrialBalanceExportDto.toCsvHeaders(): List<String> = listOf(
    "ledgerId", "ledgerName", "groupId", "groupName", "primaryGroup",
    "openingDebitPaise", "openingCreditPaise", "transactionDebitPaise", "transactionCreditPaise", "closingDebitPaise", "closingCreditPaise"
)

fun TrialBalanceExportDto.toCsvRows(): List<List<String?>> = rows.map {
    listOf(
        it.ledgerId, it.ledgerName, it.groupId, it.groupName, it.primaryGroup.name,
        it.openingDebitPaise.toString(), it.openingCreditPaise.toString(), it.transactionDebitPaise.toString(),
        it.transactionCreditPaise.toString(), it.closingDebitPaise.toString(), it.closingCreditPaise.toString()
    )
}

fun ProfitAndLossExportDto.toCsvHeaders(): List<String> = listOf(
    "companyName", "financialYearCode", "dateRange", "salesRevenuePaise", "directIncomesPaise",
    "purchasesPaise", "directExpensesPaise", "grossProfitPaise", "indirectIncomesPaise", "indirectExpensesPaise",
    "netProfitPaise", "isInventoryAware"
)

fun ProfitAndLossExportDto.toCsvRows(): List<List<String?>> = listOf(
    listOf(
        companyName, financialYearCode, dateRange, salesRevenuePaise.toString(), directIncomesPaise.toString(),
        purchasesPaise.toString(), directExpensesPaise.toString(), grossProfitPaise.toString(),
        indirectIncomesPaise.toString(), indirectExpensesPaise.toString(), netProfitPaise.toString(), isInventoryAware.toString()
    )
)

fun BalanceSheetExportDto.toCsvHeaders(): List<String> = listOf(
    "companyName", "financialYearCode", "asOfDate", "totalLiabilitiesPaise", "totalAssetsPaise", "isBalanced",
    "capitalAccountsPaise", "loansLiabilitiesPaise", "currentLiabilitiesPaise", "fixedAssetsPaise",
    "currentAssetsPaise", "sundryDebtorsPaise", "bankAccountsPaise", "cashInHandPaise", "stockInHandPaise"
)

fun BalanceSheetExportDto.toCsvRows(): List<List<String?>> = listOf(
    listOf(
        companyName, financialYearCode, asOfDate.toString(), totalLiabilitiesPaise.toString(), totalAssetsPaise.toString(), isBalanced.toString(),
        capitalAccountsPaise.toString(), loansLiabilitiesPaise.toString(), currentLiabilitiesPaise.toString(), fixedAssetsPaise.toString(),
        currentAssetsPaise.toString(), sundryDebtorsPaise.toString(), bankAccountsPaise.toString(), cashInHandPaise.toString(), stockInHandPaise.toString()
    )
)

fun OutstandingExportDto.toCsvHeaders(): List<String> = listOf(
    "invoiceId", "invoiceNumber", "invoiceType", "partyId", "partyName", "voucherNumber",
    "date", "dueDate", "totalAmountPaise", "outstandingAmountPaise", "status", "daysOutstanding", "agingBucket"
)

fun OutstandingExportDto.toCsvRows(): List<List<String?>> = rows.map {
    listOf(
        it.invoiceId, it.invoiceNumber, it.invoiceType.name, it.partyId, it.partyName, it.voucherNumber,
        it.date.toString(), it.dueDate?.toString(), it.totalAmountPaise.toString(), it.outstandingAmountPaise.toString(),
        it.status.name, it.daysOutstanding.toString(), it.agingBucket.name
    )
}

fun GSTSummaryExportDto.toCsvHeaders(): List<String> = listOf(
    "companyName", "gstin", "period", "totalTaxableOutwardPaise", "totalTaxOutwardPaise",
    "totalTaxableInwardPaise", "totalTaxInwardItcPaise", "netTaxPayablePaise", "totalCessPaise", "netCessPayablePaise"
)

fun GSTSummaryExportDto.toCsvRows(): List<List<String?>> = listOf(
    listOf(
        companyName, gstin, period, totalTaxableOutwardPaise.toString(), totalTaxOutwardPaise.toString(),
        totalTaxableInwardPaise.toString(), totalTaxInwardItcPaise.toString(), netTaxPayablePaise.toString(),
        totalCessPaise.toString(), netCessPayablePaise.toString()
    )
)

fun List<GSTTransactionExportDto>.toGstTransactionCsvHeaders(): List<String> = listOf(
    "gstTransactionId", "voucherId", "voucherType", "partyGstin", "placeOfSupply", "supplyType",
    "hsnSacCode", "isService", "taxableAmountPaise", "gstRatePercent", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise", "direction", "lineOrder"
)

fun List<GSTTransactionExportDto>.toGstTransactionCsvRows(): List<List<String?>> = map {
    listOf(
        it.gstTransactionId, it.voucherId, it.voucherType.name, it.partyGstin, it.placeOfSupply, it.supplyType,
        it.hsnSacCode, it.isService?.toString(), it.taxableAmountPaise.toString(), it.gstRatePercent.toString(),
        it.cgstPaise.toString(), it.sgstPaise.toString(), it.igstPaise.toString(), it.cessPaise.toString(),
        it.direction, it.lineOrder.toString()
    )
}

/**
 * Phase 8A, Part 1 - a single flattened CSV over every GSTR-1 table, one row per (invoice/note/
 * summary-row, rate-line), discriminated by the leading "section" column - CSV has no native
 * concept of "multiple sheets," so this is the one-file-per-export convention this project's
 * [CsvEngine] already establishes, applied to a multi-table source the same way [OutstandingExportDto]
 * already flattens its own rows. Irrelevant columns for a given section are simply blank, never a
 * fabricated value.
 */
fun Gstr1ReturnData.toCsvHeaders(): List<String> = listOf(
    "section", "recipientGstinOrPos", "documentNumber", "documentDate", "originalDocumentNumber", "originalDocumentDate",
    "hsnSacCode", "gstRatePercent", "taxableValuePaise", "cgstPaise", "sgstPaise", "igstPaise", "cessPaise",
    "totalValuePaise", "reverseCharge", "noteType"
)

fun Gstr1ReturnData.toCsvRows(): List<List<String?>> {
    val rows = mutableListOf<List<String?>>()
    b2b.forEach { party ->
        party.invoices.forEach { inv ->
            inv.rateLines.forEach { rl ->
                rows += listOf(
                    "B2B", party.recipientGstin, inv.invoiceNumber, inv.invoiceDate.toString(), null, null,
                    null, rl.gstRatePercent.toString(), rl.taxableValue.paise.toString(), rl.cgst.paise.toString(),
                    rl.sgst.paise.toString(), rl.igst.paise.toString(), rl.cess.paise.toString(),
                    rl.invoiceValue.paise.toString(), inv.reverseCharge.toString(), null
                )
            }
        }
    }
    b2cl.forEach { inv ->
        inv.rateLines.forEach { rl ->
            rows += listOf(
                "B2CL", inv.posStateCode, inv.invoiceNumber, inv.invoiceDate.toString(), null, null,
                null, rl.gstRatePercent.toString(), rl.taxableValue.paise.toString(), rl.cgst.paise.toString(),
                rl.sgst.paise.toString(), rl.igst.paise.toString(), rl.cess.paise.toString(),
                rl.invoiceValue.paise.toString(), null, null
            )
        }
    }
    b2cs.forEach { row ->
        rows += listOf(
            "B2CS", row.posStateCode, null, null, null, null, null, row.gstRatePercent.toString(),
            row.taxableValue.paise.toString(), row.cgst.paise.toString(), row.sgst.paise.toString(),
            row.igst.paise.toString(), row.cess.paise.toString(), null, null, null
        )
    }
    cdnr.forEach { party ->
        party.notes.forEach { note ->
            note.rateLines.forEach { rl ->
                rows += listOf(
                    "CDNR", party.recipientGstin, note.noteNumber, note.noteDate.toString(),
                    note.originalInvoiceNumber, note.originalInvoiceDate.toString(), null, rl.gstRatePercent.toString(),
                    rl.taxableValue.paise.toString(), rl.cgst.paise.toString(), rl.sgst.paise.toString(),
                    rl.igst.paise.toString(), rl.cess.paise.toString(), rl.invoiceValue.paise.toString(),
                    note.reverseCharge.toString(), note.noteType.name
                )
            }
        }
    }
    cdnur.forEach { note ->
        note.rateLines.forEach { rl ->
            rows += listOf(
                "CDNUR", note.posStateCode, note.noteNumber, note.noteDate.toString(),
                note.originalInvoiceNumber, note.originalInvoiceDate.toString(), null, rl.gstRatePercent.toString(),
                rl.taxableValue.paise.toString(), rl.cgst.paise.toString(), rl.sgst.paise.toString(),
                rl.igst.paise.toString(), rl.cess.paise.toString(), rl.invoiceValue.paise.toString(),
                note.reverseCharge.toString(), note.noteType.name
            )
        }
    }
    exports.forEach { exp ->
        rows += listOf(
            "EXP", null, exp.invoiceNumber, exp.invoiceDate.toString(), null, null, null, null,
            exp.taxableValue.paise.toString(), null, null, null, null, null, null, null
        )
    }
    nilRated.forEach { row ->
        rows += listOf(
            "NIL", if (row.interState) "INTER" else "INTRA", null, null, null, null, null, null,
            row.taxableValue.paise.toString(), null, null, null, null, null, null, row.bucket.name
        )
    }
    hsn.forEach { row ->
        rows += listOf(
            "HSN", null, null, null, null, null, row.hsnSacCode, row.gstRatePercent.toString(),
            row.taxableValue.paise.toString(), row.cgst.paise.toString(), row.sgst.paise.toString(),
            row.igst.paise.toString(), row.cess.paise.toString(), row.totalValue.paise.toString(), null, null
        )
    }
    documentsIssued.forEach { row ->
        rows += listOf(
            "DOC_ISSUED", row.natureOfDocument, row.seriesFrom, null, row.seriesTo, null, null, null,
            null, null, null, null, null, null, null, "${row.totalCount}/${row.cancelledCount}/${row.netIssued}"
        )
    }
    return rows
}
