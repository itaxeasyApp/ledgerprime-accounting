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
    /** Week 3 (Ledger Report professional output) - column indices whose cells should be
     * right-aligned within their column width instead of the default left alignment - correct for
     * a Debit/Credit/Balance amount column, wrong for a text column like Particulars. Defaults to
     * empty so every existing report (Trial Balance/P&L/Balance Sheet/Day Book) keeps its current,
     * unaffected left-aligned rendering. */
    val rightAlignColumnIndices: Set<Int> = emptySet(),
    /** Week 3 (Multi-page Ledger printing) - when set, [com.example.accounting.data.rendering.TabularPdfRenderer]
     * inserts a "Carried Over" row at the bottom of a page it is about to fill and a matching
     * "Brought Forward" row at the top of the next one, using the cumulative Debit/Credit totals
     * already computed here (plain running sums of each row's own already-known amount - never a
     * new accounting calculation). `null` (every other report) keeps today's plain page-break
     * behaviour unchanged. */
    val pageBreakCarryForward: PageBreakCarryForward? = null
)

data class PageBreakCarryForward(
    val particularsColumnIndex: Int,
    val debitColumnIndex: Int,
    val creditColumnIndex: Int,
    val balanceColumnIndex: Int,
    /** One entry per [TabularReportData.rows] entry - the running total of that column's amounts
     * from row 0 through this row, inclusive, formatted exactly as [com.example.accounting.core.common.Money.formatPlain]
     * already formats every other amount in this table. */
    val cumulativeDebitFormatted: List<String>,
    val cumulativeCreditFormatted: List<String>
)
