package com.example.accounting.domain.reports

import com.example.accounting.domain.rendering.TabularReportData

/**
 * Maps an already-generated report model to [TabularReportData] for PDF/Print - pure display
 * formatting (`Money.formatPlain()`, same as every other screen), never a recalculation. One
 * mapping per report, reused by whatever calls `TabularPdfRenderer.render()` - the same report
 * model View/JSON/CSV already consume, never a second source of truth for print.
 */
fun TrialBalanceReport.toPdfData(): TabularReportData = TabularReportData(
    title = "Trial Balance",
    subtitle = "$companyName - FY $financialYearCode - as of $asOfDate",
    columnHeaders = listOf("Ledger", "Group", "Opening Dr", "Opening Cr", "Txn Dr", "Txn Cr", "Closing Dr", "Closing Cr"),
    rows = rows.map {
        listOf(
            it.ledgerName, it.groupName,
            it.openingDebit.formatPlain(), it.openingCredit.formatPlain(),
            it.transactionDebit.formatPlain(), it.transactionCredit.formatPlain(),
            it.closingDebit.formatPlain(), it.closingCredit.formatPlain()
        )
    },
    totalsRow = listOf(
        "Total", "",
        totalOpeningDebit.formatPlain(), totalOpeningCredit.formatPlain(),
        totalTransactionDebit.formatPlain(), totalTransactionCredit.formatPlain(),
        totalClosingDebit.formatPlain(), totalClosingCredit.formatPlain()
    )
)

fun ProfitAndLossReport.toPdfData(): TabularReportData {
    val rows = mutableListOf(
        listOf("Sales Revenue", salesRevenue.formatPlain()),
        listOf("Direct Incomes", directIncomes.formatPlain())
    )
    if (isInventoryAware) {
        rows += listOf("Opening Stock", openingStock.formatPlain())
        rows += listOf("Purchases", purchases.formatPlain())
        rows += listOf("Closing Stock", closingStock.formatPlain())
        rows += listOf("Cost of Goods Sold", cogs.formatPlain())
    } else {
        rows += listOf("Purchases", purchases.formatPlain())
    }
    rows += listOf("Direct Expenses", directExpenses.formatPlain())
    rows += listOf("Gross Profit", grossProfit.formatPlain())
    rows += listOf("Indirect Incomes", indirectIncomes.formatPlain())
    rows += listOf("Indirect Expenses", indirectExpenses.formatPlain())
    return TabularReportData(
        title = "Profit & Loss",
        subtitle = "$companyName - FY $financialYearCode - $dateRange",
        columnHeaders = listOf("Particulars", "Amount"),
        rows = rows,
        totalsRow = listOf("Net Profit", netProfit.formatPlain())
    )
}

fun IncomeExpenditureReport.toPdfData(): TabularReportData = TabularReportData(
    title = "Income & Expenditure",
    subtitle = "$companyName - FY $financialYearCode - $dateRange",
    columnHeaders = listOf("Particulars", "Amount"),
    rows = listOf(
        listOf("Income", income.formatPlain()),
        listOf("Expenditure", expenditure.formatPlain())
    ),
    totalsRow = listOf(if (isSurplus) "Surplus" else "Deficit", surplusOrDeficit.formatPlain())
)

fun BalanceSheetReport.toPdfData(): TabularReportData = TabularReportData(
    title = "Balance Sheet",
    subtitle = "$companyName - FY $financialYearCode - as of $asOfDate",
    columnHeaders = listOf("Liabilities", "Amount", "Assets", "Amount"),
    rows = listOf(
        listOf("Capital Accounts", capitalAccounts.formatPlain(), "Fixed Assets", fixedAssets.formatPlain()),
        listOf("Reserves & Surplus", reservesAndSurplus.formatPlain(), "Investments", investments.formatPlain()),
        listOf("Net Profit for the Year", netProfitForYear.formatPlain(), "Current Assets", currentAssets.formatPlain()),
        listOf("Loans (Liability)", loansLiabilities.formatPlain(), "Sundry Debtors", sundryDebtors.formatPlain()),
        listOf("Current Liabilities", currentLiabilities.formatPlain(), "Bank Accounts", bankAccounts.formatPlain()),
        listOf("Duties & Taxes", dutiesAndTaxesLiability.formatPlain(), "Cash in Hand", cashInHand.formatPlain()),
        listOf("Branch/Divisions", branchDivisions.formatPlain(), "Stock in Hand", stockInHand.formatPlain())
    ),
    totalsRow = listOf("Total Liabilities", totalLiabilities.formatPlain(), "Total Assets", totalAssets.formatPlain())
)

/** Print/Download fix - Ledger Statement had no PDF mapping at all before this; same pure
 * display-formatting pattern as every other report here, reusing the exact rows/opening/closing
 * figures the on-screen [com.example.accounting.presentation.features.ledgers.LedgerStatementDetailView]
 * already shows. Empty (zero-transaction) ledgers produce a valid rows-less [TabularReportData] -
 * [TabularPdfRenderer] already renders "No data for this period." for that case, same as every
 * other report. */
fun LedgerStatementReport.toPdfData(): TabularReportData = TabularReportData(
    title = "Ledger Statement",
    subtitle = "$ledgerName - Opening: ${openingBalance.formatPlain()} ${openingType.code}",
    columnHeaders = listOf("Date", "Voucher No.", "Particulars", "Debit", "Credit", "Balance"),
    rows = rows.map {
        listOf(
            it.date.toString(), it.voucherNumber, it.particulars,
            it.debitAmount.formatPlain(), it.creditAmount.formatPlain(),
            "${it.runningBalance.formatPlain()} ${it.balanceType.code}"
        )
    },
    totalsRow = listOf(
        "", "", "Closing Balance",
        totalDebit.formatPlain(), totalCredit.formatPlain(),
        "${closingBalance.formatPlain()} ${closingType.code}"
    )
)

fun DayBookReport.toPdfData(): TabularReportData = TabularReportData(
    title = "Day Book",
    subtitle = dateRangeLabel,
    columnHeaders = listOf("Date", "Voucher No.", "Type", "Party", "Narration", "Amount", "Status"),
    rows = rows.map {
        listOf(
            it.date.toString(), it.voucherNumber, it.voucherType.displayName, it.partyName ?: "-",
            it.narration, it.totalAmount.formatPlain(), it.status.name
        )
    },
    totalsRow = listOf("", "", "", "", "Total", totalAmount.formatPlain(), "")
)
