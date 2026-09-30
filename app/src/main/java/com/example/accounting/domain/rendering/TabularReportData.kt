package com.example.accounting.domain.rendering

/** One printable financial report as a plain title/subtitle + column-header/rows/optional-totals
 * table - deliberately NOT [DocumentData] (that type's seller/buyer/line-item shape is for trade
 * documents; a Trial Balance/P&L/Balance Sheet/Day Book has no buyer or seller). Every field here
 * is already-formatted display text - whatever builds this (`domain/reports/ReportPdfMapping.kt`)
 * performs no accounting/GST calculation of its own; every value is sourced from an
 * already-generated report model (`domain/reports/ReportModels.kt`), the same one View/JSON/CSV
 * already consume, never a second calculation for print. Kept in `domain/rendering` (no Android
 * dependency) so the mapping stays testable without a `Context`, mirroring [DocumentData]'s own
 * data/domain split from [com.example.accounting.data.rendering.PdfDocumentRenderer].
 */
data class TabularReportData(
    val title: String,
    val subtitle: String,
    val columnHeaders: List<String>,
    val rows: List<List<String>>,
    val totalsRow: List<String>? = null,
    /** Opt-in branded header/footer. Null keeps the original plain title/subtitle layout, so
     * every report that predates [TabularReportChrome] prints exactly as before. */
    val chrome: TabularReportChrome? = null
)

/**
 * Company-branded page furniture for [TabularReportData] (Voucher Registers): a first-page
 * header with logo/company name/GSTIN/FY/period, a compact "(continued)" header on later pages,
 * repeated column headers, a footer with "Page X of Y", and - for any column listed in
 * [carryForwardColumnPaise] - "Brought forward"/"Carried forward" running totals at each page
 * break. Display data only; the running totals are sums of the same paise values the rows show.
 */
data class TabularReportChrome(
    val companyName: String,
    val gstin: String?,
    val financialYearLabel: String,
    val periodLabel: String = "",
    /** Local file path of the company's uploaded logo (Business Profile), or null for none. */
    val logoPath: String? = null,
    val generatedOn: String = "",
    /** Relative column widths; null or a size mismatch means equal widths. */
    val columnWeights: List<Float>? = null,
    /** Column indexes drawn right-aligned (amounts/counts). */
    val rightAlignedColumns: Set<Int> = emptySet(),
    /** Column index -> per-row paise values (parallel to [TabularReportData.rows]). */
    val carryForwardColumnPaise: Map<Int, List<Long>> = emptyMap()
)
