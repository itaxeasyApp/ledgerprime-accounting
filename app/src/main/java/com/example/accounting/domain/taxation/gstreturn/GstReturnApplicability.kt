package com.example.accounting.domain.taxation.gstreturn

/** One return type this [GstScheme] can file, and at what [GstReturnPeriodicity] (Rule 33,
 * Section 1/4). */
data class GstReturnApplicabilityRule(val returnType: GstReturnType, val periodicity: GstReturnPeriodicity)

/**
 * The GST Return Dashboard's Return-selector applicability layer (Rule 33, Section 1) - kept as a
 * single, isolated lookup so it can depend on more than just [GstScheme] later (turnover-based
 * thresholds, etc.) without the Dashboard/ViewModel changing at all. Deliberately NOT a hard-coded
 * identical list for every scheme (Section 1's explicit instruction).
 *
 * QRMP is not a separate [GstScheme] - it is a REGULAR taxpayer's choice of quarterly filing
 * frequency (see [Company.gstFilingFrequency]), so [filingFrequency] (rather than a third scheme
 * value) is what switches GSTR-1/GSTR-3B between Monthly and Quarterly here. COMPOSITION always
 * files both its real statutory returns - CMP-08 (quarterly statement-cum-challan) and GSTR-4
 * (annual return) - regardless of [filingFrequency], since neither is ever filed monthly. Not an
 * attempt at complete statutory coverage (turnover thresholds etc. are explicitly out of Rule 33's
 * scope and belong to a future rule that actually implements each return's real logic).
 */
object GstReturnApplicability {
    fun availableReturns(scheme: GstScheme, filingFrequency: GstReturnPeriodicity = GstReturnPeriodicity.MONTHLY): List<GstReturnApplicabilityRule> =
        when (scheme) {
            GstScheme.REGULAR -> listOf(
                GstReturnApplicabilityRule(GstReturnType.GSTR1, filingFrequency),
                GstReturnApplicabilityRule(GstReturnType.GSTR3B, filingFrequency)
            )
            GstScheme.COMPOSITION -> listOf(
                GstReturnApplicabilityRule(GstReturnType.CMP08, GstReturnPeriodicity.QUARTERLY),
                GstReturnApplicabilityRule(GstReturnType.GSTR4, GstReturnPeriodicity.QUARTERLY)
            )
        }
}
