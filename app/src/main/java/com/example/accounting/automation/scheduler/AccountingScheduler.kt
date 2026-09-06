package com.example.accounting.automation.scheduler

import com.example.accounting.automation.compliance.GstComplianceChecker
import com.example.accounting.automation.compliance.GstReturnAutomationChecker
import com.example.accounting.automation.compliance.InvoiceReminderChecker
import com.example.accounting.automation.compliance.SuspenseBalanceChecker
import com.example.accounting.automation.compliance.YearEndValidator
import com.example.accounting.automation.jobs.AutomationEvent
import com.example.accounting.automation.jobs.DailyFailedSyncDetector
import com.example.accounting.automation.jobs.DailyGstReturnFilingReminderTask
import com.example.accounting.automation.jobs.DailyInvoiceReminderTask
import com.example.accounting.automation.jobs.DailyReportTask
import com.example.accounting.automation.jobs.DailySuspenseCheckTask
import com.example.accounting.automation.jobs.DailySyncTask
import com.example.accounting.automation.jobs.DailyUnreconciledCheckTask
import com.example.accounting.automation.jobs.EventDrivenAutomationProcessor
import com.example.accounting.automation.jobs.MonthlyFinancialSummaryTask
import com.example.accounting.automation.jobs.MonthlyGstPreparationTask
import com.example.accounting.automation.jobs.MonthlyGstReturnDraftPreparationTask
import com.example.accounting.automation.jobs.MonthlyGstReturnValidationTask
import com.example.accounting.automation.jobs.MonthlyRecurringVoucherGenerationTask
import com.example.accounting.automation.jobs.YearlyClosingCheckTask
import com.example.accounting.automation.jobs.YearlyClosingReminderTask
import com.example.accounting.automation.reports.ScheduledReportTasks
import com.example.accounting.automation.tasks.AutomationTaskResult
import com.example.accounting.automation.workers.DailyAutomationWorker
import com.example.accounting.automation.workers.MonthlyAutomationWorker
import com.example.accounting.automation.workers.YearlyAutomationWorker
import com.example.accounting.core.sync.OutboxProcessor
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.repository.AccountingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AccountingScheduler(
    private val dao: AccountingDao,
    private val repository: AccountingRepository,
    private val outboxProcessor: OutboxProcessor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val suspenseChecker = SuspenseBalanceChecker(dao)
    private val gstChecker = GstComplianceChecker(dao)
    private val gstReturnChecker = GstReturnAutomationChecker(dao, repository)
    private val yearEndValidator = YearEndValidator(dao)
    private val reportTasks = ScheduledReportTasks(dao)
    private val invoiceReminderChecker = InvoiceReminderChecker(repository)

    val dailyWorker = DailyAutomationWorker(
        syncTask = DailySyncTask(outboxProcessor),
        failedSyncDetector = DailyFailedSyncDetector(dao),
        suspenseCheckTask = DailySuspenseCheckTask(suspenseChecker),
        reportTask = DailyReportTask(reportTasks),
        unreconciledCheckTask = DailyUnreconciledCheckTask(dao),
        invoiceReminderTask = DailyInvoiceReminderTask(invoiceReminderChecker),
        gstReturnFilingReminderTask = DailyGstReturnFilingReminderTask(gstReturnChecker)
    )

    val monthlyWorker = MonthlyAutomationWorker(
        financialSummaryTask = MonthlyFinancialSummaryTask(reportTasks),
        gstPrepTask = MonthlyGstPreparationTask(gstChecker),
        recurringVoucherGenerationTask = MonthlyRecurringVoucherGenerationTask(repository),
        gstReturnDraftPreparationTask = MonthlyGstReturnDraftPreparationTask(gstReturnChecker),
        gstReturnValidationTask = MonthlyGstReturnValidationTask(gstReturnChecker)
    )

    val yearlyWorker = YearlyAutomationWorker(
        closingCheckTask = YearlyClosingCheckTask(yearEndValidator),
        closingReminderTask = YearlyClosingReminderTask(dao)
    )

    val eventProcessor = EventDrivenAutomationProcessor(suspenseChecker)

    private val _lastRunResults = MutableStateFlow<List<AutomationTaskResult>>(emptyList())
    val lastRunResults: StateFlow<List<AutomationTaskResult>> = _lastRunResults.asStateFlow()

    fun dispatchEvent(event: AutomationEvent) {
        scope.launch {
            val res = eventProcessor.handleEvent(event)
            _lastRunResults.value = listOf(res) + _lastRunResults.value.take(20)
        }
    }

    suspend fun runDailyJobs(companyId: String, financialYearId: String?): List<AutomationTaskResult> {
        val results = dailyWorker.runDailyCycle(companyId, financialYearId)
        _lastRunResults.value = results + _lastRunResults.value.take(20)
        return results
    }

    suspend fun runMonthlyJobs(companyId: String, financialYearId: String?): List<AutomationTaskResult> {
        val results = monthlyWorker.runMonthlyCycle(companyId, financialYearId)
        _lastRunResults.value = results + _lastRunResults.value.take(20)
        return results
    }

    suspend fun runYearlyJobs(companyId: String, financialYearId: String?): List<AutomationTaskResult> {
        val results = yearlyWorker.runYearlyCycle(companyId, financialYearId)
        _lastRunResults.value = results + _lastRunResults.value.take(20)
        return results
    }
}
