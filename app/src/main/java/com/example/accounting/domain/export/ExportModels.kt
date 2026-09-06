package com.example.accounting.domain.export

/**
 * Export Architecture (Phase 7E) - a reusable serialization layer over ALREADY-AUTHORITATIVE
 * output from the accounting/report/GST/document engines. This layer never calculates anything;
 * it reads, maps to a DTO, and serializes. See `docs/46_EXPORT_ARCHITECTURE.md`.
 */
enum class ExportFormat { JSON, CSV, GSTR_JSON }

/** What kind of authoritative data is being exported - used both for the JSON envelope's
 * `exportType` field and for `ExportFormatUnsupported` error messages (e.g. GSTR_JSON only makes
 * sense for `GST_TRANSACTIONS`, not `VOUCHER`). */
enum class ExportType {
    VOUCHER, PARTY, LEDGER, INVOICE,
    TRIAL_BALANCE, PROFIT_AND_LOSS, BALANCE_SHEET, OUTSTANDING, GST_SUMMARY, GST_TRANSACTIONS,
    /** Phase 8A, Part 1 - the prepared [com.example.accounting.domain.taxation.gstreturn.Gstr1ReturnData]
     * draft for one [com.example.accounting.domain.taxation.gstreturn.GstReturn]. */
    GST_RETURN
}

/**
 * Every export format an [ExportType] supports - `resolveFormat` is how a route/repository
 * function rejects an unsupported combination (Section 24: `EXPORT_FORMAT_UNSUPPORTED`) rather
 * than silently producing something wrong or crashing on a `when` that doesn't cover a case.
 */
object ExportFormatSupport {
    private val TABULAR_TYPES = setOf(
        ExportType.VOUCHER, ExportType.PARTY, ExportType.LEDGER,
        ExportType.TRIAL_BALANCE, ExportType.PROFIT_AND_LOSS, ExportType.BALANCE_SHEET,
        ExportType.OUTSTANDING, ExportType.GST_SUMMARY, ExportType.GST_TRANSACTIONS, ExportType.GST_RETURN
    )

    fun supports(exportType: ExportType, format: ExportFormat): Boolean = when (format) {
        ExportFormat.JSON -> true
        ExportFormat.CSV -> exportType in TABULAR_TYPES
        ExportFormat.GSTR_JSON -> exportType == ExportType.GST_TRANSACTIONS || exportType == ExportType.GST_RETURN
    }
}

/**
 * The versioned envelope every JSON/GSTR-JSON export is wrapped in (Section 6) - `schemaVersion`
 * lets a future consumer detect an incompatible shape rather than silently misparsing it;
 * `generatedAt`/`companyId`/`financialYearId` are metadata about THIS export event, never part of
 * the authoritative accounting data itself.
 */
data class ExportMetadata(
    val schemaVersion: String = "1.0",
    val exportType: ExportType,
    val generatedAt: Long = System.currentTimeMillis(),
    val companyId: String,
    val financialYearId: String? = null,
    val applicationVersion: String = "7.5.0"
)

data class ExportRequest(
    val companyId: String,
    val financialYearId: String? = null,
    val exportType: ExportType,
    val format: ExportFormat,
    val resourceId: String? = null
)

/** [content] is the fully-serialized artifact (a JSON string, a CSV string, ...) - never a partial
 * or streaming result; export is always read-map-serialize in one pass (Section 23). */
data class ExportResult(
    val metadata: ExportMetadata,
    val format: ExportFormat,
    val content: String
)
