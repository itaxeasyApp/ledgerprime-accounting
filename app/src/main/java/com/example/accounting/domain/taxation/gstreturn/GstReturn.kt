package com.example.accounting.domain.taxation.gstreturn

/**
 * A single GST return's identity, period, scheme, filing progress, and lifecycle status (Rule 33,
 * Section 8) - the persisted record the GST Return Dashboard lists/reopens. Deliberately carries
 * only [GstPeriod]'s already-resolved facts (never re-derives them from a display string), and only
 * the LATEST request/response artifact pointers - full history lives in
 * [GstReturnSubmission]/[GstReturnArtifact] rows keyed by [gstReturnId], never duplicated here.
 */
data class GstReturn(
    val gstReturnId: String,
    val companyId: String,
    val financialYearId: String,
    val fyCode: String,
    val quarter: GstQuarter,
    val month: Int?,
    val periodKey: String,
    val scheme: GstScheme,
    val returnType: GstReturnType,
    val periodicity: GstReturnPeriodicity,
    val filingMode: GstFilingMode,
    val status: GstReturnStatus = GstReturnStatus.DRAFT,
    val createdAt: Long,
    val updatedAt: Long,
    val submittedAt: Long? = null,
    val acknowledgementNumber: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val latestRequestArtifactId: String? = null,
    val latestResponseArtifactId: String? = null,
    val schemaVersion: String = "1.0",
    /** Phase 8A, Part 2 - the user's own explicit declaration that this period genuinely has zero
     * outward supplies (a real GST concept - a taxpayer files GSTR-1 as "Nil" rather than simply
     * leaving every table empty with no acknowledgement). Never inferred automatically from empty
     * sections - [Gstr1Validator] instead WARNS when every section is empty and this is still
     * `false`, so the user either fixes a real data gap or explicitly confirms Nil; this flag is
     * what suppresses that warning once they do. */
    val isNilReturn: Boolean = false
)

/**
 * One versioned JSON artifact belonging to a [GstReturn] (Rule 33, Section 9) - a return's generated
 * request or an imported response, never overwritten in place: each call to generate/import inserts
 * a NEW row, and [GstReturn.latestRequestArtifactId]/[GstReturn.latestResponseArtifactId] simply
 * point at whichever is current. Older rows remain queryable by [gstReturnId] for history (Section
 * 16).
 */
data class GstReturnArtifact(
    val artifactId: String,
    val gstReturnId: String,
    val artifactType: GstReturnArtifactType,
    val schemaVersion: String,
    val jsonContent: String,
    val createdAt: Long
)

/**
 * One section's preparation/validation result within a [GstReturn] (Rule 33, Section 10) -
 * deliberately generic: [sectionKey] is a caller-supplied identifier, never a statutory GSTR table
 * name this foundation invents. [resultDataJson] is an opaque, already-computed snapshot (e.g. a
 * serialized GST summary for the period) - never a second tax calculation.
 */
data class GstReturnSection(
    val sectionId: String,
    val gstReturnId: String,
    val sectionKey: String,
    val status: GstReturnSectionStatus = GstReturnSectionStatus.PENDING,
    val resultDataJson: String? = null,
    val errorsJson: String? = null,
    val updatedAt: Long
)

/**
 * One online-filing attempt's history (Rule 33, Section 16) - a return may be (re)submitted more
 * than once; every attempt is preserved here rather than overwriting [GstReturn]'s own
 * status/acknowledgement fields, which only ever reflect the LATEST attempt.
 */
data class GstReturnSubmission(
    val submissionId: String,
    val gstReturnId: String,
    val attemptNumber: Int,
    val requestArtifactId: String?,
    val responseArtifactId: String?,
    val status: GstReturnStatus,
    val acknowledgementNumber: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val submittedAt: Long,
    val respondedAt: Long? = null
)
