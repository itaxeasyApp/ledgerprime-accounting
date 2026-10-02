package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Builds the GSTR-9C reconciliation from the GSTR-9 tables, the books' turnover and the GST ledgers.
 * No GST is recalculated here (GSTR-9, GSTR-3B and GSTR-1 supply every figure on the return side)
 * and nothing is estimated: a value the app does not hold is absent and listed in [coverage].
 */
object Gstr9cBuilder {

    /** Aggregate-turnover threshold above which GSTR-9C is required: Rs 5 crore (CBIC; self-certified since FY 2020-21). */
    val TURNOVER_THRESHOLD: Money = Money.fromPaise(5_00_00_000_00L)

    fun build(
        companyGstin: String,
        fyCode: String,
        data9: Gstr9ReturnData,
        turnoverPerBooks: Money,
        ledgers: Gstr3bReconciliation.LedgerTotals
    ): Gstr9cReturnData {
        val turnoverPerGstr9 = data9.table4.netOutward.taxable + data9.table5.net

        val applicability = if (turnoverPerGstr9.paise > TURNOVER_THRESHOLD.paise) {
            Gstr9cApplicability(
                Gstr9cApplicabilityStatus.REQUIRED_ON_THIS_GSTIN, turnoverPerGstr9, TURNOVER_THRESHOLD,
                "This GSTIN's turnover exceeds Rs 5 crore, so GSTR-9C (self-certified reconciliation statement) is required."
            )
        } else {
            Gstr9cApplicability(
                Gstr9cApplicabilityStatus.NOT_REQUIRED_ON_THIS_GSTIN, turnoverPerGstr9, TURNOVER_THRESHOLD,
                "This GSTIN's turnover does not exceed Rs 5 crore. Aggregate turnover is counted across every GSTIN of the PAN, " +
                    "which this app cannot see - confirm it before deciding GSTR-9C is not required."
            )
        }

        val taxable = Gstr9cTaxableTurnoverReconciliation(
            turnoverPerBooks = turnoverPerBooks,
            exemptedAndNilRated = data9.table5.exempted + data9.table5.nilRated,
            zeroRatedWithoutPayment = data9.table5.zeroRatedWithoutPayment,
            taxableTurnoverPerGstr9 = data9.table4.netOutward.taxable
        )

        // Tax per the books = output tax + reverse-charge liability (the two things GSTR-9 Table 9 "payable" holds).
        val taxPaid = listOf(
            Gstr9cTaxHeadRow("IGST", ledgers.outputTax.igst + ledgers.reverseChargeLiability.igst, data9.taxPayable.igst),
            Gstr9cTaxHeadRow("CGST", ledgers.outputTax.cgst + ledgers.reverseChargeLiability.cgst, data9.taxPayable.cgst),
            Gstr9cTaxHeadRow("SGST", ledgers.outputTax.sgst + ledgers.reverseChargeLiability.sgst, data9.taxPayable.sgst)
        )
        fun shortfall(head: String) = Money.fromPaise(maxOf(0L, -taxPaid.first { it.head == head }.unreconciled.paise))

        return Gstr9cReturnData(
            companyGstin = companyGstin, fyCode = fyCode,
            applicability = applicability,
            turnover = Gstr9cTurnoverReconciliation(turnoverPerBooks, turnoverPerGstr9),
            taxableTurnover = taxable,
            taxPaid = taxPaid,
            additionalLiability = Gstr9cAdditionalLiability(shortfall("IGST"), shortfall("CGST"), shortfall("SGST")),
            partB = Gstr9cPartB(
                gstin = companyGstin, financialYear = fyCode,
                inputsRequiredFromTaxpayer = listOf(
                    "Name and designation of the person certifying", "Place and date", "The declaration itself",
                    "Reasons for every unreconciled difference (Tables 6, 8 and 10)", "The recommendation on additional liability (Part V)"
                )
            ),
            coverage = coverage()
        )
    }

    /** What this app can and cannot put in each GSTR-9C table, with the reason - stored with every prepared return.
     * Table numbers are those of the GSTN's GSTR-9C offline-utility manual (Part II Tables 5-8, Part III Tables 9-11,
     * Part IV Tables 12-16, Part V, Part B). */
    fun coverage(): List<Gstr9Coverage> = listOf(
        Gstr9Coverage(
            "Applicability", Gstr9cCoverage.PARTIAL,
            "Judged on this GSTIN's own turnover against Rs 5 crore; the threshold applies to aggregate turnover across the PAN, which this app cannot see."
        ),
        Gstr9Coverage(
            "5", Gstr9cCoverage.PARTIAL,
            "5A is taken from the BOOKS (Sales + Direct Income ledgers, net) - there is no audited financial statement in this app (GSTN's manual accepts books of account " +
                "where the turnover is derived GSTIN-wise). 5Q is the turnover declared in GSTR-9 and 5R the difference (5Q - 5P). The reconciling items 5B-5O are NOT recorded and " +
                "are not reported: unbilled revenue (5B, 5H), unadjusted advances (5C, 5I), deemed supply under Schedule I (5D), credit notes after year end (5E), " +
                "trade discounts (5F), April-June 2017 turnover (5G), credit notes not permissible under section 34 (5J), SEZ-to-DTA goods (5K), composition-period turnover (5L), " +
                "section 15 valuation (5M), foreign exchange (5N) and other reasons (5O). Any difference is therefore reported as unreconciled."
        ),
        Gstr9Coverage("6", Gstr9cCoverage.NOT_SUPPORTED, "The reasons for an unreconciled turnover difference can only come from the taxpayer."),
        Gstr9Coverage(
            "7", Gstr9cCoverage.PARTIAL,
            "7A is 5P. 7B is exempted and nil-rated supplies and 7C zero-rated supplies without payment, both from GSTR-9 Table 5; 7F is GSTR-9 Table 4 (net of credit notes, " +
                "without amendments or Tables 10-11, which are not modeled); 7G is 7F - 7E. Non-GST supplies and no-supply turnover (7B), SEZ supplies (7C) and supplies on which tax is " +
                "payable by the recipient (7D) are not recorded, so they are not deducted."
        ),
        Gstr9Coverage("8", Gstr9cCoverage.NOT_SUPPORTED, "The reasons for an unreconciled taxable-turnover difference can only come from the taxpayer."),
        Gstr9Coverage(
            "9", Gstr9cCoverage.PARTIAL,
            "Tax per the GST ledgers (output tax + reverse-charge liability) against GSTR-9 Table 9 payable, for IGST, CGST and SGST. The books hold ledger totals, " +
                "not rate-wise liability, so the rate-wise reconciliation and the cess head are not produced."
        ),
        Gstr9Coverage("10", Gstr9cCoverage.NOT_SUPPORTED, "The reasons for an unreconciled payment of tax can only come from the taxpayer."),
        Gstr9Coverage(
            "11", Gstr9cCoverage.PARTIAL,
            "Additional amount payable but not paid: the positive part of the per-head tax difference (books above GSTR-9) is shown as a candidate for the taxpayer to confirm. " +
                "Interest is calculated by the GST portal and is not computed here."
        ),
        Gstr9Coverage(
            "12-16", Gstr9cCoverage.NOT_SUPPORTED,
            "ITC reconciliation and the tax payable on its difference: it starts from GSTR-9 Tables 6-8, which this app cannot produce (no inputs / capital goods / " +
                "services split, no reversals by rule, no GSTR-2A/2B), so a reconciliation built on them would misstate the ITC."
        ),
        Gstr9Coverage("Part V", Gstr9cCoverage.NOT_SUPPORTED, "The recommendation on additional liability is the certifier's own."),
        Gstr9Coverage("17", Gstr9cCoverage.NOT_SUPPORTED, "Late fee payable and paid is calculated by the GST portal."),
        Gstr9Coverage(
            "Part B", Gstr9cCoverage.PARTIAL,
            "Only the GSTIN and the financial year are filled. The certification (signatory, designation, place, date, declaration) is the taxpayer's own and is never filled in by the app."
        ),
        Gstr9Coverage(
            "JSON", Gstr9cCoverage.NOT_SUPPORTED,
            "No GSTR-9C JSON structure could be verified: GSTN publishes it only inside the offline utility, no developer documentation was found, and GSTN's own manual has the " +
                "JSON digitally signed by the certifier (emSigner) and verified unchanged on upload, which an app cannot do. No upload file is produced; " +
                "the working-paper export is for review and is not a GSTN upload file."
        )
    )
}
/** Shorthand so the 9C coverage list reads the same as the GSTR-9 one without a second status enum. */
internal object Gstr9cCoverage {
    val PARTIAL = Gstr9CoverageStatus.PARTIAL
    val NOT_SUPPORTED = Gstr9CoverageStatus.NOT_SUPPORTED
}

/** GSTR-9C's own findings. Nothing here is an ERROR by itself: a difference between books and the return is a
 * disclosure the taxpayer must explain, not a reason to block a working paper. The blocking checks come from GSTR-9. */
object Gstr9cReconciliation {

    fun issues(d: Gstr9cReturnData): List<Gstr1ValidationIssue> {
        val issues = mutableListOf<Gstr1ValidationIssue>()
        if (d.applicability.status == Gstr9cApplicabilityStatus.NOT_REQUIRED_ON_THIS_GSTIN) {
            issues += Gstr1ValidationIssue(Gstr1ValidationSeverity.WARNING, "GSTR9C_NOT_REQUIRED", d.applicability.note)
        }
        if (d.turnover.unreconciled.paise != 0L) {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "GSTR9C_TURNOVER_UNRECONCILED",
                "Table 5: turnover per books ${d.turnover.turnoverPerBooks.formatPlain()} differs from GSTR-9 ${d.turnover.turnoverPerGstr9.formatPlain()} " +
                    "by ${d.turnover.unreconciled.formatPlain()}. The reconciling items are not recorded in this app; the reasons (Table 6) must come from the taxpayer."
            )
        }
        if (d.taxableTurnover.unreconciled.paise != 0L) {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "GSTR9C_TAXABLE_TURNOVER_UNRECONCILED",
                "Table 7: taxable turnover per books ${d.taxableTurnover.taxableTurnoverPerBooks.formatPlain()} differs from GSTR-9 " +
                    "${d.taxableTurnover.taxableTurnoverPerGstr9.formatPlain()} by ${d.taxableTurnover.unreconciled.formatPlain()}; the reasons (Table 8) must come from the taxpayer."
            )
        }
        d.taxPaid.filter { it.unreconciled.paise != 0L }.forEach {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "GSTR9C_TAX_UNRECONCILED",
                "Table 9 ${it.head}: tax per the GST ledgers ${it.perBooks.formatPlain()} differs from GSTR-9 payable ${it.perGstr9.formatPlain()} " +
                    "by ${it.unreconciled.formatPlain()} (GSTR-9 minus books); the reasons (Table 10) must come from the taxpayer."
            )
        }
        if (d.additionalLiability.total.paise > 0L) {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "GSTR9C_ADDITIONAL_LIABILITY",
                "The books show more tax than GSTR-9 declares (IGST ${d.additionalLiability.igst.formatPlain()}, CGST ${d.additionalLiability.cgst.formatPlain()}, " +
                    "SGST ${d.additionalLiability.sgst.formatPlain()}): a candidate for additional liability under Part V. Interest is calculated by the portal."
            )
        }
        return issues
    }
}
