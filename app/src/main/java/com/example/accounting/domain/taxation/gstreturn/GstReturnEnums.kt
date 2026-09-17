package com.example.accounting.domain.taxation.gstreturn

/**
 * A company's GST registration scheme (Rule 33 - GST Return Dashboard & Filing Foundation).
 * Statutorily just Regular or Composition - QRMP is not a separate registration scheme, it is a
 * *filing frequency* a Regular taxpayer below the turnover threshold opts into (see
 * [com.example.accounting.domain.company.Company.gstFilingFrequency]). Deliberately separate from
 * [com.example.accounting.domain.company.Company.gstEnabled] (whether GST applies to this company at
 * all) and from [com.example.accounting.domain.accounting.Ledger.gstRegistrationStatus] (a *party's*
 * fact, never the reporting company's own scheme). The company's own statutory profile is the single
 * authoritative source for this.
 */
enum class GstScheme { REGULAR, COMPOSITION }

/**
 * Extensible set of return types this foundation can carry (Rule 33). Only the identity/lifecycle
 * plumbing is implemented here - none of these compute their actual statutory sections yet (Section
 * 4/23 of the Rule 33 spec: that is explicitly future-rule work, never fabricated here).
 *
 * GSTR9/GSTR9C (GST Settings refactor) are visibility-only additions - [GstReturnApplicability]
 * lists them under Regular so the Dashboard's return list is a complete, correct set for that
 * taxpayer type, but neither has a real [GstReturnApplicabilityRule]/preparation flow behind it
 * yet, so they stay non-actionable ("coming soon") in the UI exactly like before this change,
 * never a fabricated filing capability.
 */
enum class GstReturnType { GSTR1, GSTR3B, GSTR4, CMP08, GSTR9, GSTR9C }

/** How a return's period is filed - a whole quarter (QRMP/Composition option) or a single calendar
 * month (Regular's default). For a REGULAR company this mirrors
 * [com.example.accounting.domain.company.Company.gstFilingFrequency]; COMPOSITION is always
 * QUARTERLY under GSTR-4. */
enum class GstReturnPeriodicity {
    MONTHLY, QUARTERLY,
    /** Phase 8 - GSTR-9 only; statutorily always a full financial year, never monthly/quarterly. */
    ANNUALLY
}

/** [com.example.accounting.domain.taxation.gst.GstFilingPeriod] already exists for compliance-period
 * *locking*; this is a distinct concern - how THIS return is being prepared/filed. */
enum class GstFilingMode { OFFLINE, ONLINE }

enum class GstReturnArtifactType { REQUEST, RESPONSE }

enum class GstReturnSectionStatus { PENDING, PREPARED, VALIDATION_PASSED, VALIDATION_FAILED }

/**
 * Explicit return lifecycle (Rule 33, Section 7). Transitions are controlled by
 * [GstReturnStatusTransitions] - nothing in this module ever sets [status] on a
 * [com.example.accounting.domain.taxation.gstreturn.GstReturn] without going through it.
 */
enum class GstReturnStatus {
    DRAFT,
    VALIDATION_FAILED,
    READY,
    SUBMITTING,
    SUBMITTED,
    PROCESSING,
    FILED,
    FAILED,
    REJECTED
}

/**
 * The only allowed [GstReturnStatus] transitions (Rule 33, Section 7) - a return can never jump
 * straight from DRAFT to FILED, and FILED is terminal (a real GST filing is never un-filed by this
 * application). FAILED/REJECTED both allow returning to DRAFT/READY so a corrected return can be
 * re-attempted, matching the offline/online flows' own "fix and retry" shape.
 */
object GstReturnStatusTransitions {
    private val allowed: Map<GstReturnStatus, Set<GstReturnStatus>> = mapOf(
        GstReturnStatus.DRAFT to setOf(GstReturnStatus.READY, GstReturnStatus.VALIDATION_FAILED),
        GstReturnStatus.VALIDATION_FAILED to setOf(GstReturnStatus.DRAFT, GstReturnStatus.READY),
        GstReturnStatus.READY to setOf(GstReturnStatus.SUBMITTING, GstReturnStatus.DRAFT, GstReturnStatus.PROCESSING),
        GstReturnStatus.SUBMITTING to setOf(GstReturnStatus.SUBMITTED, GstReturnStatus.FAILED),
        GstReturnStatus.SUBMITTED to setOf(GstReturnStatus.PROCESSING, GstReturnStatus.FAILED),
        GstReturnStatus.PROCESSING to setOf(GstReturnStatus.FILED, GstReturnStatus.REJECTED, GstReturnStatus.FAILED),
        GstReturnStatus.FILED to emptySet(),
        GstReturnStatus.FAILED to setOf(GstReturnStatus.DRAFT, GstReturnStatus.READY),
        GstReturnStatus.REJECTED to setOf(GstReturnStatus.DRAFT, GstReturnStatus.READY)
    )

    fun isAllowed(from: GstReturnStatus, to: GstReturnStatus): Boolean = to in (allowed[from] ?: emptySet())
}
