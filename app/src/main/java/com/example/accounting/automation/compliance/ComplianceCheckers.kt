package com.example.accounting.automation.compliance

import com.example.accounting.automation.notifications.AutomationNotification
import com.example.accounting.automation.notifications.AutomationNotificationCenter
import com.example.accounting.automation.notifications.NotificationCategory
import com.example.accounting.automation.notifications.NotificationPriority
import com.example.accounting.automation.tasks.AutomationTaskResult
import com.example.accounting.automation.tasks.TaskExecutionStatus
import com.example.accounting.automation.tasks.TaskFrequency
import com.example.accounting.core.common.Money
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.domain.reports.TrialBalanceReport
import com.example.accounting.domain.taxation.gst.GstLedgerIds
import com.example.accounting.domain.taxation.gstreturn.GstFilingMode
import com.example.accounting.domain.taxation.gstreturn.GstPeriod
import com.example.accounting.domain.taxation.gstreturn.GstQuarter
import com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity
import com.example.accounting.domain.taxation.gstreturn.GstReturnStatus
import com.example.accounting.domain.taxation.gstreturn.GstReturnType
import com.example.accounting.domain.taxation.gstreturn.GstScheme
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class SuspenseBalanceChecker(private val dao: AccountingDao) {

    suspend fun checkSuspenseBalance(companyId: String): AutomationTaskResult {
        val suspenseLedgerId = "${StandardSystemGroups.SUSPENSE_LEDGER_ID}_$companyId"
        val suspense = dao.getLedgerById(companyId, suspenseLedgerId)

        return if (suspense == null) {
            AutomationTaskResult(
                taskName = "Check Suspense Account Balance",
                frequency = TaskFrequency.DAILY,
                status = TaskExecutionStatus.WARNING,
                message = "Suspense ledger account not found for company $companyId."
            )
        } else if (suspense.currentBalancePaise != 0L) {
            val balance = Money(suspense.currentBalancePaise)
            val notification = AutomationNotification(
                companyId = companyId,
                title = "Suspense A/c Non-Zero Balance Warning",
                message = "Suspense Account has an uncleared balance of ${balance.format()} ${suspense.currentBalanceType}. Reconcile pending vouchers.",
                priority = NotificationPriority.HIGH,
                category = NotificationCategory.SUSPENSE_WARNING,
                actionDeepLink = "#statement/$suspenseLedgerId"
            )
            AutomationNotificationCenter.emitNotification(notification)

            AutomationTaskResult(
                taskName = "Check Suspense Account Balance",
                frequency = TaskFrequency.DAILY,
                status = TaskExecutionStatus.WARNING,
                message = "Suspense balance is non-zero (${balance.format()} ${suspense.currentBalanceType}). Reconcile immediately.",
                itemsProcessed = 1,
                details = mapOf("balance" to balance.format(), "type" to suspense.currentBalanceType.name)
            )
        } else {
            AutomationTaskResult(
                taskName = "Check Suspense Account Balance",
                frequency = TaskFrequency.DAILY,
                status = TaskExecutionStatus.SUCCESS,
                message = "Suspense account balance is zero (₹0.00). Ledger is clear.",
                itemsProcessed = 1
            )
        }
    }
}

class GstComplianceChecker(private val dao: AccountingDao) {

    suspend fun checkGstPreparation(companyId: String, financialYearId: String?): AutomationTaskResult {
        val ledgers = dao.getLedgersByCompany(companyId).first()
        // Exact groupId-prefix / exact ledgerId match (Phase 5 final audit finding) - was
        // `groupId.contains("DEBTORS"/"CREDITORS")` and `name.contains("GST", ...)`, the same
        // name-matching anti-pattern fixed everywhere else in the app.
        val debtorsAndCreditors = ledgers.filter {
            it.groupId.startsWith(StandardSystemGroups.DEBTORS_GROUP_ID) || it.groupId.startsWith(StandardSystemGroups.CREDITORS_GROUP_ID)
        }

        var missingGstinCount = 0
        debtorsAndCreditors.forEach { ledger ->
            if (ledger.gstin.isBlank()) {
                missingGstinCount++
            }
        }

        val gstLedgers = ledgers.filter { ledger -> GstLedgerIds.ALL_BARE_IDS.any { ledger.ledgerId == "${it}_$companyId" } }
        val gstDetails = gstLedgers.associate { it.name to Money(it.currentBalancePaise).format() }

        val status = if (missingGstinCount > 0) TaskExecutionStatus.WARNING else TaskExecutionStatus.SUCCESS
        val message = if (missingGstinCount > 0) {
            "GST Readiness Check: $missingGstinCount party ledgers do not have GSTIN configured."
        } else {
            "GST Readiness Check: All party ledgers compliant. GST balances computed."
        }

        if (missingGstinCount > 0) {
            AutomationNotificationCenter.emitNotification(
                AutomationNotification(
                    companyId = companyId,
                    title = "Monthly GST Preparation Notice",
                    message = "$missingGstinCount trading parties lack GSTIN for GSTR filing.",
                    priority = NotificationPriority.NORMAL,
                    category = NotificationCategory.COMPLIANCE_ALERT,
                    actionDeepLink = "#chart-of-accounts"
                )
            )
        }

        return AutomationTaskResult(
            taskName = "Monthly GST Preparation Check",
            frequency = TaskFrequency.MONTHLY,
            status = status,
            message = message,
            itemsProcessed = debtorsAndCreditors.size,
            details = gstDetails
        )
    }
}

class YearEndValidator(private val dao: AccountingDao) {

    suspend fun validateYearEndReadiness(companyId: String, financialYearId: String): AutomationTaskResult {
        val periods = dao.getPeriodsByFinancialYear(financialYearId).first()
        val allPeriodsClosed = periods.all { it.status == PeriodStatus.LOCKED || it.status == PeriodStatus.AUDIT_LOCKED }
        val pendingOutboxCount = dao.countPendingOutbox(companyId)

        val unpostedCount = dao.getVouchersByFinancialYear(companyId, financialYearId).first().count { !it.isPosted || it.isCancelled }

        val warnings = mutableListOf<String>()
        if (!allPeriodsClosed) {
            warnings.add("Not all 12 accounting periods are locked.")
        }
        if (pendingOutboxCount > 0) {
            warnings.add("$pendingOutboxCount pending outbox sync items must be cleared.")
        }
        if (unpostedCount > 0) {
            warnings.add("$unpostedCount unposted/cancelled vouchers detected.")
        }

        val status = if (warnings.isEmpty()) TaskExecutionStatus.SUCCESS else TaskExecutionStatus.WARNING
        val msg = if (warnings.isEmpty()) {
            "Year-End Validation: Financial year $financialYearId is fully verified and eligible for closing."
        } else {
            "Year-End Validation: ${warnings.joinToString("; ")}"
        }

        return AutomationTaskResult(
            taskName = "Year-End Validation & Closing Check",
            frequency = TaskFrequency.YEARLY,
            status = status,
            message = msg,
            itemsProcessed = periods.size,
            details = mapOf("openPeriods" to periods.count { it.status == PeriodStatus.OPEN }.toString(), "pendingOutbox" to pendingOutboxCount.toString())
        )
    }
}

/**
 * Phase 8A, Part 1 - GSTR-1 return-period automation (Rule 33's own foundation extended, never
 * duplicated): draft preparation, a validation/check run, missing/error notification, and a
 * statutory filing-due-date reminder. Strictly read/prepare/validate-only, matching every other
 * checker in this file - NEVER calls [AccountingRepository.markGstReturnFiled] or
 * [AccountingRepository.submitGstReturnOnline]; a return only ever moves to FILED/SUBMITTED through
 * an explicit, human-driven action elsewhere in the app.
 */
class GstReturnAutomationChecker(
    private val dao: AccountingDao,
    private val repository: AccountingRepository
) {
    /** How many days before the statutory due date a "due soon" reminder starts firing - mirrors
     * [InvoiceReminderChecker.dueSoonWindowDays]'s own convention. */
    private val dueSoonWindowDays = 5L

    /** Resolves (financial year, quarter, month) for the most recently COMPLETED GST return
     * period - GSTR-1 is always prepared for a period that has already ended, never the one still
     * in progress. Returns `null` when GSTR-1 does not apply at all (GST disabled, or Composition
     * scheme - see [GstReturnApplicability]) or when no [FinancialYear] on file actually contains
     * that period's dates (a FY-boundary case this automation honestly declines to guess, rather
     * than silently attributing the period to the wrong year). */
    private suspend fun resolveCompletedPeriod(companyId: String, today: LocalDate): Triple<FinancialYear, GstQuarter, Int?>? {
        val company = dao.getCompanyById(companyId) ?: return null
        if (!company.gstEnabled || company.gstScheme != GstScheme.REGULAR) return null
        val periodicity = company.gstFilingFrequency
        val targetMonthStart = if (periodicity == GstReturnPeriodicity.MONTHLY) {
            today.withDayOfMonth(1).minusMonths(1)
        } else {
            val currentQuarterFirstMonth = GstQuarter.ofMonth(today.monthValue).months.first()
            LocalDate.of(today.year, currentQuarterFirstMonth, 1).minusMonths(1)
        }
        val fy = repository.getFinancialYears(companyId).first().firstOrNull { it.contains(targetMonthStart) } ?: return null
        val quarter = GstQuarter.ofMonth(targetMonthStart.monthValue)
        val month = if (periodicity == GstReturnPeriodicity.MONTHLY) targetMonthStart.monthValue else null
        return Triple(fy, quarter, month)
    }

    /** The statutory due date for a completed period - 11th of the following month (MONTHLY, per
     * CGST Rule 59), or 13th of the month after quarter-end (QUARTERLY/QRMP, per the same rule). */
    private fun statutoryDueDate(period: GstPeriod): LocalDate {
        val periodEnd = period.dateRange().endInclusive
        val dueDay = if (period.month != null) 11 else 13
        return periodEnd.plusDays(1).withDayOfMonth(dueDay)
    }

    suspend fun prepareCurrentPeriodDraft(companyId: String): AutomationTaskResult {
        val name = "GSTR-1 Return-Period Draft Preparation"
        val target = resolveCompletedPeriod(companyId, LocalDate.now())
            ?: return AutomationTaskResult(taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.SKIPPED, message = "GSTR-1 does not apply to this company (GST disabled, Composition scheme, or the completed period falls outside any financial year on file).")
        val (fy, quarter, month) = target
        val gstReturn = repository.getOrCreateGstReturn(companyId, fy, quarter, month, GstScheme.REGULAR, GstReturnType.GSTR1, if (month != null) GstReturnPeriodicity.MONTHLY else GstReturnPeriodicity.QUARTERLY, GstFilingMode.OFFLINE)
        return when (val result = repository.prepareGstReturn(companyId, gstReturn.gstReturnId, fy)) {
            is com.example.accounting.core.common.AccountingResult.Success -> AutomationTaskResult(
                taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.SUCCESS,
                message = "GSTR-1 draft prepared for period ${GstPeriod.of(fy, quarter, month).periodKey}.",
                itemsProcessed = 1, details = mapOf("gstReturnId" to gstReturn.gstReturnId, "periodKey" to GstPeriod.of(fy, quarter, month).periodKey)
            )
            is com.example.accounting.core.common.AccountingResult.Failure -> AutomationTaskResult(
                taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.FAILED,
                message = "GSTR-1 draft preparation failed: ${result.error.message}"
            )
        }
    }

    suspend fun runValidationCheck(companyId: String): AutomationTaskResult {
        val name = "GSTR-1 Validation & Error Check"
        val target = resolveCompletedPeriod(companyId, LocalDate.now())
            ?: return AutomationTaskResult(taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.SKIPPED, message = "GSTR-1 does not apply to this company for the completed period.")
        val (fy, quarter, month) = target
        val periodKey = GstPeriod.of(fy, quarter, month).periodKey
        val existing = dao.findGstReturn(companyId, periodKey, GstReturnType.GSTR1.name, GstScheme.REGULAR.name)
            ?: return AutomationTaskResult(taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.WARNING, message = "No GSTR-1 draft exists yet for period $periodKey - run draft preparation first.")

        return when (val result = repository.validateGstReturn(companyId, existing.gstReturnId, fy)) {
            is com.example.accounting.core.common.AccountingResult.Success -> {
                val gr = result.data
                if (gr.status == GstReturnStatus.VALIDATION_FAILED) {
                    AutomationNotificationCenter.emitNotification(
                        AutomationNotification(
                            companyId = companyId, title = "GSTR-1 Validation Failed",
                            message = "Period $periodKey: ${gr.errorMessage ?: "one or more validation issues were found"}.",
                            priority = NotificationPriority.HIGH, category = NotificationCategory.GST_RETURN_FILING,
                            actionDeepLink = "#gst-return/${gr.gstReturnId}"
                        )
                    )
                    AutomationTaskResult(taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.WARNING, message = "GSTR-1 for $periodKey failed validation: ${gr.errorMessage ?: ""}", itemsProcessed = 1)
                } else {
                    AutomationTaskResult(taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.SUCCESS, message = "GSTR-1 for $periodKey passed validation and is READY.", itemsProcessed = 1)
                }
            }
            is com.example.accounting.core.common.AccountingResult.Failure -> AutomationTaskResult(
                taskName = name, frequency = TaskFrequency.MONTHLY, status = TaskExecutionStatus.FAILED,
                message = "GSTR-1 validation run failed: ${result.error.message}"
            )
        }
    }

    suspend fun checkFilingReminder(companyId: String, today: LocalDate = LocalDate.now()): AutomationTaskResult {
        val name = "GSTR-1 Filing-Due Reminder"
        val target = resolveCompletedPeriod(companyId, today)
            ?: return AutomationTaskResult(taskName = name, frequency = TaskFrequency.DAILY, status = TaskExecutionStatus.SKIPPED, message = "GSTR-1 does not apply to this company.")
        // Phase 8A, Part 2 - user-facing reminder control (Company.gstr1ReminderEnabled). Gates
        // ONLY this reminder notification, never draft preparation/validation above - those two
        // automation checks are unaffected by this toggle.
        if (dao.getCompanyById(companyId)?.gstr1ReminderEnabled == false) {
            return AutomationTaskResult(taskName = name, frequency = TaskFrequency.DAILY, status = TaskExecutionStatus.SKIPPED, message = "GSTR-1 reminders are turned off for this company.")
        }
        val (fy, quarter, month) = target
        val period = GstPeriod.of(fy, quarter, month)
        val existing = dao.findGstReturn(companyId, period.periodKey, GstReturnType.GSTR1.name, GstScheme.REGULAR.name)
        if (existing?.status == GstReturnStatus.FILED) {
            return AutomationTaskResult(taskName = name, frequency = TaskFrequency.DAILY, status = TaskExecutionStatus.SUCCESS, message = "GSTR-1 for ${period.periodKey} is already FILED.")
        }
        val dueDate = statutoryDueDate(period)
        val daysToDue = ChronoUnit.DAYS.between(today, dueDate)
        return when {
            daysToDue < 0 -> {
                AutomationNotificationCenter.emitNotification(
                    AutomationNotification(
                        companyId = companyId, title = "GSTR-1 Overdue",
                        message = "GSTR-1 for ${period.periodKey} was due on $dueDate and has not been marked FILED - file it as soon as possible to avoid late fees.",
                        priority = NotificationPriority.CRITICAL, category = NotificationCategory.GST_RETURN_FILING,
                        actionDeepLink = "#gst-return"
                    )
                )
                AutomationTaskResult(taskName = name, frequency = TaskFrequency.DAILY, status = TaskExecutionStatus.WARNING, message = "GSTR-1 for ${period.periodKey} is OVERDUE (due $dueDate).", itemsProcessed = 1)
            }
            daysToDue <= dueSoonWindowDays -> {
                AutomationNotificationCenter.emitNotification(
                    AutomationNotification(
                        companyId = companyId, title = "GSTR-1 Due Soon",
                        message = "GSTR-1 for ${period.periodKey} is due on $dueDate (in $daysToDue day(s)).",
                        priority = NotificationPriority.NORMAL, category = NotificationCategory.GST_RETURN_FILING,
                        actionDeepLink = "#gst-return"
                    )
                )
                AutomationTaskResult(taskName = name, frequency = TaskFrequency.DAILY, status = TaskExecutionStatus.SUCCESS, message = "GSTR-1 for ${period.periodKey} due in $daysToDue day(s) ($dueDate).", itemsProcessed = 1)
            }
            else -> AutomationTaskResult(taskName = name, frequency = TaskFrequency.DAILY, status = TaskExecutionStatus.SUCCESS, message = "GSTR-1 for ${period.periodKey} is not yet due ($dueDate).")
        }
    }
}
