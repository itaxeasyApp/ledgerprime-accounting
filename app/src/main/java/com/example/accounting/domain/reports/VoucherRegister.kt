package com.example.accounting.domain.reports

import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.rendering.TabularReportChrome
import com.example.accounting.domain.rendering.TabularReportData
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four trade-voucher registers shown in Reports Center > Sales/Purchase. Each is a pure
 * regroup of [DayBookReport] rows (the existing, authoritative per-company/per-date-range voucher
 * listing) by one [VoucherType] - never a second voucher query or a recalculated amount.
 */
enum class VoucherRegisterType(val voucherType: VoucherType, val title: String) {
    SALES(VoucherType.SALES, "Sales Register"),
    PURCHASE(VoucherType.PURCHASE, "Purchase Register"),
    CREDIT_NOTE(VoucherType.CREDIT_NOTE, "Credit Note Register"),
    DEBIT_NOTE(VoucherType.DEBIT_NOTE, "Debit Note Register");

    companion object {
        fun fromTitle(title: String): VoucherRegisterType? = entries.firstOrNull { it.title == title }
    }
}

/** One month of a register - [rows] are the month's real, non-cancelled vouchers of the
 * register's type, oldest first; [voucherCount]/[totalAmount] are derived from exactly those rows. */
data class VoucherRegisterMonth(
    val month: YearMonth,
    val rows: List<DayBookRow>
) {
    val voucherCount: Int get() = rows.size
    val totalAmount: Money get() = Money.fromPaise(rows.sumOf { it.totalAmount.paise })
    val label: String get() = month.format(MONTH_LABEL_FORMAT)
}

data class VoucherRegisterReport(
    val registerType: VoucherRegisterType,
    val financialYearCode: String,
    val fyStart: LocalDate,
    val fyEnd: LocalDate,
    /** Every month of the financial year, in FY order (April first) - a month with no vouchers is
     * kept with a real count of 0 rather than hidden, so the monthly summary always reads as a
     * complete year. */
    val months: List<VoucherRegisterMonth>
) {
    val totalCount: Int get() = months.sumOf { it.voucherCount }
    val totalAmount: Money get() = Money.fromPaise(months.sumOf { it.totalAmount.paise })

    fun month(month: YearMonth): VoucherRegisterMonth? = months.firstOrNull { it.month == month }
}

private val MONTH_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)

/**
 * Builds a register from an already-generated [DayBookReport] covering [fyStart]..[fyEnd].
 * Cancelled vouchers ([DayBookEntryStatus.CANCELLED]) and any row dated outside the FY are
 * excluded; only rows of [type]'s voucher type are kept.
 */
fun buildVoucherRegister(
    type: VoucherRegisterType,
    dayBook: DayBookReport,
    financialYearCode: String,
    fyStart: LocalDate,
    fyEnd: LocalDate
): VoucherRegisterReport {
    val byMonth = dayBook.rows
        .asSequence()
        .filter { it.voucherType == type.voucherType }
        .filter { it.status == DayBookEntryStatus.POSTED }
        .filter { !it.date.isBefore(fyStart) && !it.date.isAfter(fyEnd) }
        .sortedWith(compareBy<DayBookRow> { it.date }.thenBy { it.voucherNumber })
        .groupBy { YearMonth.from(it.date) }

    val months = generateSequence(YearMonth.from(fyStart)) { it.plusMonths(1) }
        .takeWhile { !it.isAfter(YearMonth.from(fyEnd)) }
        .map { VoucherRegisterMonth(it, byMonth[it].orEmpty()) }
        .toList()

    return VoucherRegisterReport(type, financialYearCode, fyStart, fyEnd, months)
}

/** Monthly-summary PDF: one row per FY month with its voucher count and total. */
fun VoucherRegisterReport.toMonthlySummaryPdfData(chrome: TabularReportChrome): TabularReportData = TabularReportData(
    title = registerType.title,
    subtitle = "Monthly summary - FY $financialYearCode",
    columnHeaders = listOf("Month", "Vouchers", "Amount"),
    rows = months.map { listOf(it.label, it.voucherCount.toString(), it.totalAmount.formatPlain()) },
    totalsRow = listOf("Total", totalCount.toString(), totalAmount.formatPlain()),
    chrome = chrome.copy(
        periodLabel = "${fyStart.format(DATE_FORMAT)} to ${fyEnd.format(DATE_FORMAT)}",
        columnWeights = listOf(2f, 1f, 1.5f),
        rightAlignedColumns = setOf(1, 2)
    )
)

/** Detailed PDF for one month (or, when [month] is null, the whole FY): one row per voucher,
 * with Amount carried forward across page breaks. */
fun VoucherRegisterReport.toDetailPdfData(month: YearMonth?, chrome: TabularReportChrome): TabularReportData {
    val selected = if (month == null) months else listOfNotNull(month(month))
    val rows = selected.flatMap { it.rows }
    val periodStart = month?.atDay(1)?.coerceAtLeast(fyStart) ?: fyStart
    val periodEnd = month?.atEndOfMonth()?.coerceAtMost(fyEnd) ?: fyEnd
    return TabularReportData(
        title = registerType.title,
        subtitle = if (month == null) "All vouchers - FY $financialYearCode" else "${month.format(MONTH_LABEL_FORMAT)} - FY $financialYearCode",
        columnHeaders = listOf("Date", "Voucher No.", "Party", "Narration", "Amount"),
        rows = rows.map {
            listOf(it.date.format(DATE_FORMAT), it.voucherNumber, it.partyName ?: "-", it.narration, it.totalAmount.formatPlain())
        },
        totalsRow = listOf("", "", "", "Total (${rows.size} vouchers)", Money.fromPaise(rows.sumOf { it.totalAmount.paise }).formatPlain()),
        chrome = chrome.copy(
            periodLabel = "${periodStart.format(DATE_FORMAT)} to ${periodEnd.format(DATE_FORMAT)}",
            columnWeights = listOf(1.1f, 1.4f, 2.4f, 2.8f, 1.4f),
            rightAlignedColumns = setOf(4),
            carryForwardColumnPaise = mapOf(4 to rows.map { it.totalAmount.paise })
        )
    )
}
