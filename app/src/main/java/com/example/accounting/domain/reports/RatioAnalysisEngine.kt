package com.example.accounting.domain.reports

/**
 * Pure ratio computation (Phase 7C) - deliberately Room-independent, same pattern as
 * [com.example.accounting.domain.accounting.RoundOffEngine]/[GroupAggregationEngine]: every input
 * is an already-generated [BalanceSheetReport]/[ProfitAndLossReport], so this never reads a
 * ledger/journal itself. Division by a zero denominator returns `0.0` rather than throwing or
 * producing `Infinity`/`NaN` - a ratio report must never crash a caller over a young company with
 * no liabilities yet.
 */
object RatioAnalysisEngine {

    private fun safeDivide(numeratorPaise: Long, denominatorPaise: Long): Double =
        if (denominatorPaise == 0L) 0.0 else numeratorPaise.toDouble() / denominatorPaise.toDouble()

    private fun safePercent(numeratorPaise: Long, denominatorPaise: Long): Double = safeDivide(numeratorPaise, denominatorPaise) * 100.0

    fun compute(balanceSheet: BalanceSheetReport, profitAndLoss: ProfitAndLossReport): RatioAnalysisReport {
        // BalanceSheetReport.currentAssets/currentLiabilities are RESIDUAL buckets (generateBalanceSheet
        // subtracts Debtors/Bank/Cash/Stock and Duties&Taxes out into their own named fields for
        // display) - the true totals a Current/Quick Ratio needs must add those named buckets back in.
        val totalCurrentAssetsPaise = balanceSheet.currentAssets.paise + balanceSheet.sundryDebtors.paise +
            balanceSheet.bankAccounts.paise + balanceSheet.cashInHand.paise + balanceSheet.stockInHand.paise +
            balanceSheet.gstRecoverable.paise
        val totalCurrentLiabilitiesPaise = balanceSheet.currentLiabilities.paise + balanceSheet.dutiesAndTaxesLiability.paise
        val quickAssetsPaise = totalCurrentAssetsPaise - balanceSheet.stockInHand.paise

        val equityPaise = balanceSheet.capitalAccounts.paise + balanceSheet.reservesAndSurplus.paise + balanceSheet.netProfitForYear.paise
        val capitalEmployedPaise = equityPaise + balanceSheet.loansLiabilities.paise

        val netSalesPaise = profitAndLoss.salesRevenue.paise + profitAndLoss.directIncomes.paise
        val operatingCostPaise = (if (profitAndLoss.isInventoryAware) profitAndLoss.cogs.paise else profitAndLoss.purchases.paise) +
            profitAndLoss.directExpenses.paise + profitAndLoss.indirectExpenses.paise

        return RatioAnalysisReport(
            companyName = balanceSheet.companyName,
            financialYearCode = balanceSheet.financialYearCode,
            asOfDate = balanceSheet.asOfDate,
            currentRatio = safeDivide(totalCurrentAssetsPaise, totalCurrentLiabilitiesPaise),
            quickRatio = safeDivide(quickAssetsPaise, totalCurrentLiabilitiesPaise),
            debtEquityRatio = safeDivide(balanceSheet.loansLiabilities.paise, equityPaise),
            grossProfitRatioPercent = safePercent(profitAndLoss.grossProfit.paise, netSalesPaise),
            netProfitRatioPercent = safePercent(profitAndLoss.netProfit.paise, netSalesPaise),
            operatingRatioPercent = safePercent(operatingCostPaise, netSalesPaise),
            returnOnCapitalEmployedPercent = safePercent(profitAndLoss.netProfit.paise, capitalEmployedPaise)
        )
    }
}
