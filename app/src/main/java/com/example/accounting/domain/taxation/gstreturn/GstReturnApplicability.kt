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

    /**
     * GST Settings refactor - "Taxpayer Type is the single source of truth" for which return
     * types are ever shown at all (Dashboard, Select Return). Deliberately a SEPARATE list from
     * [availableReturns]: GSTR-9/GSTR-9C are real Regular-scheme returns and must be visible for a
     * Regular taxpayer, but this codebase has no preparation logic for either yet (see
     * [GstReturnType]'s own KDoc), so they are listed here (visible) but intentionally absent from
     * [availableReturns] (the actionable/periodicity-bearing set the Dashboard's "File" buttons and
     * Select Return's tappability both key off) - same "real gap, never a fabricated
     * implementation" precedent this codebase already established for CMP-08's tax-liability figure.
     *
     * Never mixed across schemes: a Composition taxpayer never sees GSTR-1/3B/9/9C, and a Regular
     * taxpayer never sees CMP-08/GSTR-4, by construction of this single `when`.
     */
    fun visibleReturns(scheme: GstScheme): List<GstReturnType> = when (scheme) {
        GstScheme.REGULAR -> listOf(GstReturnType.GSTR1, GstReturnType.GSTR3B, GstReturnType.GSTR9, GstReturnType.GSTR9C)
        GstScheme.COMPOSITION -> listOf(GstReturnType.CMP08, GstReturnType.GSTR4)
    }

    /** Every [GstReturnType] this app can ever actually create/prepare a [GstReturn] for, across
     * both schemes - used where a UI needs to enumerate real, possibly-already-filed return types
     * (e.g. Return History's filter chips) rather than the current company's own visible set. */
    val allActionableTypes: Set<GstReturnType> =
        (availableReturns(GstScheme.REGULAR).map { it.returnType } + availableReturns(GstScheme.COMPOSITION).map { it.returnType }).toSet()
}
