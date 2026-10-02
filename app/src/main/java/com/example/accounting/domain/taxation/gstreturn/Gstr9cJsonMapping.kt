package com.example.accounting.domain.taxation.gstreturn

/**
 * The shapes of a [Gstr9cReturnData]: [toSections] is what a prepared GSTR-9C stores per table, and
 * [toWorkingPaperTree] is the readable working-paper export.
 *
 * There is deliberately NO GSTN upload serializer for GSTR-9C: its JSON structure could not be
 * verified (see [Gstr9cBuilder.coverage]), and GSTN has that JSON digitally signed by the certifier
 * and checked unchanged on upload. Producing an unverified lookalike would be inventing a schema.
 */

/** The prepared return's stored sections - keys `APPLICABILITY`, `5`, `7`, `9`, `11`, `PART_B` and `COVERAGE`. */
fun Gstr9cReturnData.toSections(): Map<String, Map<String, Any?>> = linkedMapOf(
    "APPLICABILITY" to linkedMapOf<String, Any?>(
        "status" to applicability.status.name,
        "turnoverOfThisGstinPaise" to applicability.turnoverOfThisGstin.paise,
        "thresholdPaise" to applicability.threshold.paise,
        "note" to applicability.note
    ),
    "5" to linkedMapOf<String, Any?>(
        "turnoverPerBooksPaise" to turnover.turnoverPerBooks.paise,
        "turnoverPerGstr9Paise" to turnover.turnoverPerGstr9.paise,
        "unreconciledPaise" to turnover.unreconciled.paise,
        "basis" to "Books of account (Sales + Direct Income ledgers, net) - not an audited figure; reconciling items 5B-5O are not recorded."
    ),
    "7" to linkedMapOf<String, Any?>(
        "a_turnoverPerBooksPaise" to taxableTurnover.turnoverPerBooks.paise,
        "b_exemptedAndNilRatedPaise" to taxableTurnover.exemptedAndNilRated.paise,
        "c_zeroRatedWithoutPaymentPaise" to taxableTurnover.zeroRatedWithoutPayment.paise,
        "e_taxableTurnoverPerBooksPaise" to taxableTurnover.taxableTurnoverPerBooks.paise,
        "f_taxableTurnoverPerGstr9Paise" to taxableTurnover.taxableTurnoverPerGstr9.paise,
        "g_unreconciledPaise" to taxableTurnover.unreconciled.paise
    ),
    "9" to linkedMapOf<String, Any?>(
        "rows" to taxPaid.map {
            linkedMapOf(
                "head" to it.head, "perBooksPaise" to it.perBooks.paise, "perGstr9Paise" to it.perGstr9.paise,
                "unreconciledPaise" to it.unreconciled.paise
            )
        }
    ),
    "11" to linkedMapOf<String, Any?>(
        "candidateAdditionalLiability" to linkedMapOf(
            "igstPaise" to additionalLiability.igst.paise, "cgstPaise" to additionalLiability.cgst.paise,
            "sgstPaise" to additionalLiability.sgst.paise, "totalPaise" to additionalLiability.total.paise
        ),
        "interest" to "Not computed - calculated by the GST portal."
    ),
    "PART_B" to linkedMapOf<String, Any?>(
        "gstin" to partB.gstin, "financialYear" to partB.financialYear,
        "inputsRequiredFromTaxpayer" to partB.inputsRequiredFromTaxpayer
    ),
    "COVERAGE" to linkedMapOf<String, Any?>(
        "rows" to coverage.map { linkedMapOf("table" to it.table, "status" to it.status.name, "note" to it.note) }
    )
)

/** The readable working paper - labelled as such, so it can never be mistaken for a GSTN upload file. */
fun Gstr9cReturnData.toWorkingPaperTree(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "document" to "LedgerPrime GSTR-9C working paper - NOT a GSTN upload file",
    "gstin" to companyGstin,
    "financialYear" to fyCode
).apply { putAll(toSections()) }
