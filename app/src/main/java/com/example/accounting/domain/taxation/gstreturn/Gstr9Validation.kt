package com.example.accounting.domain.taxation.gstreturn

/**
 * Phase 8 - GSTR-9 validation. Per-transaction GSTIN/POS/tax-recomputation checks already ran when
 * each period's own GSTR-1/GSTR-3B was validated - re-running them here would be a duplicate check,
 * not a new one, so this validator focuses on what is genuinely GSTR-9-specific: the company's own
 * filing GSTIN, and whether the underlying period returns this annual return aggregates were
 * actually filed (an annual return built from un-filed periods is a draft preview, not yet a
 * statement of what was actually declared - GSTR-9 statutorily reconciles what WAS filed).
 */
object Gstr9Validator {
    fun validate(companyGstin: String, filedPeriodCount: Int, totalPeriodCount: Int, isNilReturn: Boolean, hasAnyActivity: Boolean): List<Gstr9ValidationIssue> {
        val issues = mutableListOf<Gstr9ValidationIssue>()

        if (companyGstin.isBlank()) {
            issues += Gstr9ValidationIssue(Gstr1ValidationSeverity.ERROR, "MISSING_COMPANY_GSTIN", "This company has no GSTIN recorded - GSTR-9 cannot be filed without one.")
        } else if (!GstinChecksum.isValidChecksum(companyGstin)) {
            issues += Gstr9ValidationIssue(Gstr1ValidationSeverity.ERROR, "INVALID_COMPANY_GSTIN_CHECKSUM", "This company's own GSTIN '$companyGstin' fails the GSTN check-digit algorithm - verify it was entered correctly in Company settings.")
        }

        if (filedPeriodCount < totalPeriodCount) {
            issues += Gstr9ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "UNDERLYING_PERIODS_NOT_ALL_FILED",
                "Only $filedPeriodCount of $totalPeriodCount GSTR-1/GSTR-3B periods for this year are marked FILED - this annual return reflects what your books show, which may not yet match what was actually declared for the unfiled period(s)."
            )
        }

        if (!hasAnyActivity && !isNilReturn) {
            issues += Gstr9ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "ZERO_ACTIVITY_NOT_DECLARED_NIL",
                "This financial year shows no GST-bearing transactions at all. If that's correct, mark this return as Nil before filing; otherwise check the right year was selected."
            )
        }

        return issues
    }
}
