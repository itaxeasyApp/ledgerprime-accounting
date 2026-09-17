package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.domain.financialyear.FinancialYear
import java.time.LocalDate

/**
 * The four GST-statutory quarters (Rule 33, Section 2) - fixed calendar months, always
 * Apr-Jun/Jul-Sep/Oct-Dec/Jan-Mar regardless of a company's [FinancialYear.startDate] month, since
 * Indian GST return periods are defined this way in law, not by a company's own books-closing
 * convention. Deliberately NOT a new Financial Year model - it is derived arithmetic over the
 * existing [FinancialYear], never a second source of truth for what a financial year is.
 */
enum class GstQuarter(val label: String, val months: List<Int>) {
    Q1("Q1", listOf(4, 5, 6)),
    Q2("Q2", listOf(7, 8, 9)),
    Q3("Q3", listOf(10, 11, 12)),
    Q4("Q4", listOf(1, 2, 3));

    companion object {
        fun ofMonth(calendarMonth: Int): GstQuarter =
            entries.first { calendarMonth in it.months }
    }
}

/**
 * A resolved (Financial Year, Quarter, optional Month) selection for the GST Return Dashboard
 * (Rule 33, Section 2) - a pure value computed from the existing [FinancialYear], never a persisted
 * or independent period record. [month] is `null` for a whole-quarter selection (QRMP/quarterly
 * GSTR-1); a specific calendar month (1-12) otherwise.
 *
 * [periodKey] is the stable, machine-readable representation the spec asks for
 * (`fromDate <= transactionDate <= toDate` filtering and persistence keys must never rely on a
 * display string like "April") - `YYYYMM` for a month (e.g. "202604"), `fyCode-Qn` for a whole
 * quarter (e.g. "2026-27-Q1").
 *
 * [fyStartCalendarYear] is the calendar year the financial year actually starts in (e.g. `2026` for
 * FY "2026-27") - required (rather than parsed back out of [fyCode]) because [FinancialYear.fyCode]
 * is a free-form display string, not a guaranteed `YYYY-YY` format; callers always have the real
 * [FinancialYear.startDate] on hand when constructing this.
 */
data class GstPeriod(
    val financialYearId: String,
    val fyCode: String,
    val fyStartCalendarYear: Int,
    val quarter: GstQuarter,
    val month: Int? = null,
    /** Phase 8 - GSTR-9 is statutorily an ANNUAL return, never monthly/quarterly, so it does not fit
     * the (Quarter, optional Month) shape every prior return type used. Defaults `false` (zero
     * behavior change for every pre-existing caller); [quarter]/[month] are still required by this
     * class's shape but become inert placeholders when `true` - [dateRange]/[periodKey] both switch
     * to the whole financial year instead. */
    val isAnnual: Boolean = false
) {
    init {
        require(month == null || month in 1..12) { "month must be 1-12 or null, was $month" }
        require(isAnnual || month == null || month in quarter.months) {
            "month $month does not belong to quarter ${quarter.label} (${quarter.months})"
        }
    }

    /** Apr-Dec fall in [fyStartCalendarYear] itself; Jan-Mar fall in the following calendar year. */
    private val monthCalendarYear: Int?
        get() = month?.let { if (it >= 4) fyStartCalendarYear else fyStartCalendarYear + 1 }

    val periodKey: String
        get() = when {
            isAnnual -> fyCode
            month != null -> "$monthCalendarYear${month.toString().padStart(2, '0')}"
            else -> "$fyCode-${quarter.label}"
        }

    /** The real calendar date range this period covers, for `fromDate <= transactionDate <= toDate`
     * filtering - never approximated from display labels. [isAnnual] spans the whole financial year
     * (fixed Apr 1 - Mar 31, the same assumption [GstQuarter]'s own fixed month lists already make),
     * regardless of [quarter]/[month]'s inert placeholder values. */
    fun dateRange(): ClosedRange<LocalDate> {
        if (isAnnual) {
            return LocalDate.of(fyStartCalendarYear, 4, 1)..LocalDate.of(fyStartCalendarYear + 1, 3, 31)
        }
        val months = month?.let { listOf(it) } ?: quarter.months
        val first = months.first()
        val last = months.last()
        val firstYear = if (first >= 4) fyStartCalendarYear else fyStartCalendarYear + 1
        val lastYear = if (last >= 4) fyStartCalendarYear else fyStartCalendarYear + 1
        val start = LocalDate.of(firstYear, first, 1)
        val end = LocalDate.of(lastYear, last, 1).plusMonths(1).minusDays(1)
        return start..end
    }

    companion object {
        /** Resolves the calendar year a [FinancialYear] starts in directly from its real
         * [FinancialYear.startDate] - never parsed back out of the display [FinancialYear.fyCode]. */
        fun of(fy: FinancialYear, quarter: GstQuarter, month: Int? = null, isAnnual: Boolean = false): GstPeriod =
            GstPeriod(fy.financialYearId, fy.fyCode, fy.startDate.year, quarter, month, isAnnual)
    }
}
