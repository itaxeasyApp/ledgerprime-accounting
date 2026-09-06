package com.example.accounting.automation.jobs

import com.example.accounting.automation.compliance.GstReturnAutomationChecker
import com.example.accounting.automation.compliance.InvoiceReminderChecker
import com.example.accounting.automation.compliance.SuspenseBalanceChecker
import com.example.accounting.automation.notifications.AutomationNotification
import com.example.accounting.automation.notifications.AutomationNotificationCenter
import com.example.accounting.automation.notifications.NotificationCategory
import com.example.accounting.automation.notifications.NotificationPriority
import com.example.accounting.automation.reports.ScheduledReportTasks
import com.example.accounting.automation.tasks.AutomationTask
import com.example.accounting.automation.tasks.AutomationTaskResult
import com.example.accounting.automation.tasks.TaskExecutionStatus
import com.example.accounting.automation.tasks.TaskFrequency
import com.example.accounting.core.sync.OutboxProcessor
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.accounting.VoucherType
import kotlinx.coroutines.flow.first

class DailySyncTask(
    private val outboxProcessor: OutboxProcessor
) : AutomationTask {
    override val taskId: String = "DAILY_OUTBOX_SYNC"
    override val name: String = "Sync Pending Outbox Records"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult {
        return try {
            val syncResult = outboxProcessor.processPendingOutbox(companyId)
            val processed = syncResult.getOrDefault(0)
            AutomationTaskResult(
                taskName = name,
                frequency = frequency,
                status = TaskExecutionStatus.SUCCESS,
                message = "Successfully synchronized $processed pending outbox mutation records.",
                itemsProcessed = processed
            )
        } catch (e: Exception) {
            AutomationTaskResult(
                taskName = name,
                frequency = frequency,
                status = TaskExecutionStatus.FAILED,
                message = "Outbox sync failed: ${e.message}"
            )
        }
    }
}

class DailyFailedSyncDetector(
    private val dao: AccountingDao
) : AutomationTask {
    override val taskId: String = "DAILY_FAILED_SYNC_DETECTOR"
    override val name: String = "Detect Failed Outbox Sync Items"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult {
        val outbox = dao.getOutboxItemsByCompany(companyId, 100)
        val failedItems = outbox.filter { it.syncState == SyncState.FAILED || it.syncState == SyncState.CONFLICT }

        if (failedItems.isNotEmpty()) {
            AutomationNotificationCenter.emitNotification(
                AutomationNotification(
                    companyId = companyId,
                    title = "Failed Sync Mutations Detected",
                    message = "${failedItems.size} outbox mutations require review or re-sync.",
                    priority = NotificationPriority.HIGH,
                    category = NotificationCategory.SYNC_STATUS,
                    actionDeepLink = "#settings-sync"
                )
            )

            return AutomationTaskResult(
                taskName = name,
                frequency = frequency,
                status = TaskExecutionStatus.WARNING,
                message = "Detected ${failedItems.size} failed or conflicting outbox synchronization records.",
                itemsProcessed = failedItems.size,
                details = failedItems.associate { it.syncId to "${it.entityType} ${it.operation}: ${it.lastError ?: "Error"}" }
            )
        }

        return AutomationTaskResult(
            taskName = name,
            frequency = frequency,
            status = TaskExecutionStatus.SUCCESS,
            message = "All outbox mutations are clean and synchronized.",
            itemsProcessed = 0
        )
    }
}

class DailySuspenseCheckTask(
    private val suspenseChecker: SuspenseBalanceChecker
) : AutomationTask {
    override val taskId: String = "DAILY_SUSPENSE_CHECK"
    override val name: String = "Verify Suspense Account Zero Balance"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult {
        return suspenseChecker.checkSuspenseBalance(companyId)
    }
}

class DailyInvoiceReminderTask(
    private val invoiceReminderChecker: InvoiceReminderChecker
) : AutomationTask {
    override val taskId: String = "DAILY_INVOICE_REMINDERS"
    override val name: String = "Invoice & Compliance Reminders"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult =
        invoiceReminderChecker.checkInvoiceReminders(companyId)
}

/** Phase 8A, Part 1 - "filing-period reminder" automation requirement, checked daily (mirroring
 * [DailyInvoiceReminderTask]'s own daily due-date-threshold pattern) so the statutory due date is
 * never missed by a full month between MONTHLY-cadence runs. */
class DailyGstReturnFilingReminderTask(
    private val gstReturnChecker: GstReturnAutomationChecker
) : AutomationTask {
    override val taskId: String = "DAILY_GSTR1_FILING_REMINDER"
    override val name: String = "GSTR-1 Filing-Due Reminder"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult =
        gstReturnChecker.checkFilingReminder(companyId)
}

class DailyReportTask(
    private val reportTasks: ScheduledReportTasks
) : AutomationTask {
    override val taskId: String = "DAILY_REPORT_SNAPSHOT"
    override val name: String = "Generate Daily Liquidity Position"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult {
        return reportTasks.generateDailyFinancialSnapshot(companyId)
    }
}

/**
 * Bank reconciliation is explicitly QUARANTINED (Phase 7F safety decision): this task never
 * imports a bank feed, never auto-matches anything, and never creates a reconciliation/accounting
 * entry - it only surfaces a read-only, human-facing SUGGESTION (a notification) that certain
 * already-posted Payment/Receipt/Contra vouchers have no reference number recorded, which would
 * make them harder to match against a bank statement during a later, human-performed
 * reconciliation. This is strictly "detect and notify," matching the existing
 * `SuspenseBalanceChecker`/`GstComplianceChecker` pattern - there is no bank-feed-import or
 * auto-match capability anywhere in this codebase, before or after this task.
 *
 * (Corrected from a prior version of this class whose doc comment/name claimed this exact check
 * but whose implementation actually read unrelated outbox sync items - a mislabeling bug found
 * during the Phase 7F structural audit and fixed here, not a behavior change to anything that
 * previously worked correctly.)
 */
class DailyUnreconciledCheckTask(
    private val dao: AccountingDao
) : AutomationTask {
    override val taskId: String = "DAILY_UNRECONCILED_CHECK"
    override val name: String = "Detect Unreconciled Bank & Cash Transactions"
    override val frequency: TaskFrequency = TaskFrequency.DAILY

    private val bankCashVoucherTypes = setOf(VoucherType.PAYMENT, VoucherType.RECEIPT, VoucherType.CONTRA)

    override suspend fun execute(companyId: String, financialYearId: String?): AutomationTaskResult {
        val vouchers = dao.getVouchersByCompany(companyId).first()
        val unreconciled = vouchers.filter { it.voucherType in bankCashVoucherTypes && !it.isCancelled && it.referenceNumber.isBlank() }

        if (unreconciled.isNotEmpty()) {
            AutomationNotificationCenter.emitNotification(
                AutomationNotification(
                    companyId = companyId,
                    title = "Unreconciled Bank/Cash Transactions",
                    message = "${unreconciled.size} Payment/Receipt/Contra voucher(s) have no bank reference recorded - review before reconciling against a bank statement.",
                    priority = NotificationPriority.NORMAL,
                    category = NotificationCategory.UNRECONCILED_TRANSACTIONS,
                    actionDeepLink = "#daybook"
                )
            )
            return AutomationTaskResult(
                taskName = name,
                frequency = frequency,
                status = TaskExecutionStatus.WARNING,
                message = "${unreconciled.size} bank/cash voucher(s) are missing a reference number.",
                itemsProcessed = unreconciled.size,
                details = unreconciled.associate { it.voucherNumber to it.voucherType.name }
            )
        }

        return AutomationTaskResult(
            taskName = name,
            frequency = frequency,
            status = TaskExecutionStatus.SUCCESS,
            message = "All posted bank/cash transactions carry a reference number.",
            itemsProcessed = vouchers.count { it.voucherType in bankCashVoucherTypes }
        )
    }
}
