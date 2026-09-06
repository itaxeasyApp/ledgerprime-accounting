package com.example.accounting.automation.workers

import com.example.accounting.automation.jobs.DailyFailedSyncDetector
import com.example.accounting.automation.jobs.DailyGstReturnFilingReminderTask
import com.example.accounting.automation.jobs.DailyInvoiceReminderTask
import com.example.accounting.automation.jobs.DailyReportTask
import com.example.accounting.automation.jobs.DailySuspenseCheckTask
import com.example.accounting.automation.jobs.DailySyncTask
import com.example.accounting.automation.jobs.DailyUnreconciledCheckTask
import com.example.accounting.automation.jobs.MonthlyFinancialSummaryTask
import com.example.accounting.automation.jobs.MonthlyGstPreparationTask
import com.example.accounting.automation.jobs.MonthlyGstReturnDraftPreparationTask
import com.example.accounting.automation.jobs.MonthlyGstReturnValidationTask
import com.example.accounting.automation.jobs.MonthlyRecurringVoucherGenerationTask
import com.example.accounting.automation.jobs.YearlyClosingCheckTask
import com.example.accounting.automation.jobs.YearlyClosingReminderTask
import com.example.accounting.automation.tasks.AutomationTaskResult

class DailyAutomationWorker(
    private val syncTask: DailySyncTask,
    private val failedSyncDetector: DailyFailedSyncDetector,
    private val suspenseCheckTask: DailySuspenseCheckTask,
    private val reportTask: DailyReportTask,
    private val unreconciledCheckTask: DailyUnreconciledCheckTask,
    private val invoiceReminderTask: DailyInvoiceReminderTask,
    private val gstReturnFilingReminderTask: DailyGstReturnFilingReminderTask
) {
    suspend fun runDailyCycle(companyId: String, financialYearId: String?): List<AutomationTaskResult> {
        val results = mutableListOf<AutomationTaskResult>()
        results.add(syncTask.execute(companyId, financialYearId))
        results.add(failedSyncDetector.execute(companyId, financialYearId))
        results.add(suspenseCheckTask.execute(companyId, financialYearId))
        results.add(reportTask.execute(companyId, financialYearId))
        results.add(unreconciledCheckTask.execute(companyId, financialYearId))
        results.add(invoiceReminderTask.execute(companyId, financialYearId))
        results.add(gstReturnFilingReminderTask.execute(companyId, financialYearId))
        return results
    }
}

class MonthlyAutomationWorker(
    private val financialSummaryTask: MonthlyFinancialSummaryTask,
    private val gstPrepTask: MonthlyGstPreparationTask,
    private val recurringVoucherGenerationTask: MonthlyRecurringVoucherGenerationTask,
    private val gstReturnDraftPreparationTask: MonthlyGstReturnDraftPreparationTask,
    private val gstReturnValidationTask: MonthlyGstReturnValidationTask
) {
    suspend fun runMonthlyCycle(companyId: String, financialYearId: String?): List<AutomationTaskResult> {
        val results = mutableListOf<AutomationTaskResult>()
        results.add(financialSummaryTask.execute(companyId, financialYearId))
        results.add(gstPrepTask.execute(companyId, financialYearId))
        results.add(recurringVoucherGenerationTask.execute(companyId, financialYearId))
        results.add(gstReturnDraftPreparationTask.execute(companyId, financialYearId))
        results.add(gstReturnValidationTask.execute(companyId, financialYearId))
        return results
    }
}

class YearlyAutomationWorker(
    private val closingCheckTask: YearlyClosingCheckTask,
    private val closingReminderTask: YearlyClosingReminderTask
) {
    suspend fun runYearlyCycle(companyId: String, financialYearId: String?): List<AutomationTaskResult> {
        val results = mutableListOf<AutomationTaskResult>()
        results.add(closingCheckTask.execute(companyId, financialYearId))
        results.add(closingReminderTask.execute(companyId, financialYearId))
        return results
    }
}
