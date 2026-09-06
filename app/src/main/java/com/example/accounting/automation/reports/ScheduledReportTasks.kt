package com.example.accounting.automation.reports

import com.example.accounting.automation.tasks.AutomationTaskResult
import com.example.accounting.automation.tasks.TaskExecutionStatus
import com.example.accounting.automation.tasks.TaskFrequency
import com.example.accounting.core.common.Money
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.StandardSystemGroups
import kotlinx.coroutines.flow.first

class ScheduledReportTasks(private val dao: AccountingDao) {

    suspend fun generateDailyFinancialSnapshot(companyId: String): AutomationTaskResult {
        val ledgers = dao.getLedgersByCompany(companyId).first()
        val groupsById = dao.getGroupsByCompany(companyId).first().associate {
            it.groupId to AccountGroup(
                groupId = it.groupId,
                companyId = it.companyId,
                name = it.name,
                primaryGroup = it.primaryGroup,
                parentGroupId = it.parentGroupId,
                isSystem = it.isSystem,
                affectsGrossProfit = it.affectsGrossProfit,
                displayOrder = it.displayOrder
            )
        }
        // Exact groupId-prefix check (Phase 5, Priority 11) - was `groupId.contains("BANK"/"CASH")`,
        // the same name-matching anti-pattern Phase 3 eliminated everywhere else in the app.
        // Falls back to isUnder() ancestor-walk so ledgers nested under a custom subgroup
        // (e.g. a bank ledger under a user-created sub-group of Bank Accounts) are still counted.
        val bankAndCash = ledgers.filter {
            StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
                StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById) ||
                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
        }
        
        var totalLiquidPaise = 0L
        for (acc in bankAndCash) {
            totalLiquidPaise += acc.currentBalancePaise
        }

        val totalLiquid = Money(totalLiquidPaise)

        return AutomationTaskResult(
            taskName = "Daily Cash & Bank Liquidity Snapshot",
            frequency = TaskFrequency.DAILY,
            status = TaskExecutionStatus.SUCCESS,
            message = "Daily Snapshot: Total liquid cash/bank balance: ${totalLiquid.format()} across ${bankAndCash.size} accounts.",
            itemsProcessed = bankAndCash.size,
            details = bankAndCash.associate { it.name to Money(it.currentBalancePaise).format() }
        )
    }

    suspend fun generateMonthlyOutstandingReport(companyId: String): AutomationTaskResult {
        val ledgers = dao.getLedgersByCompany(companyId).first()
        val debtors = ledgers.filter { it.groupId.startsWith(StandardSystemGroups.DEBTORS_GROUP_ID) }
        val creditors = ledgers.filter { it.groupId.startsWith(StandardSystemGroups.CREDITORS_GROUP_ID) }

        val totalReceivablesPaise = debtors.sumOf { it.currentBalancePaise }
        val totalPayablesPaise = creditors.sumOf { it.currentBalancePaise }

        val receivables = Money(totalReceivablesPaise)
        val payables = Money(totalPayablesPaise)

        return AutomationTaskResult(
            taskName = "Monthly Outstanding Receivables & Payables",
            frequency = TaskFrequency.MONTHLY,
            status = TaskExecutionStatus.SUCCESS,
            message = "Monthly Summary: Total Receivables: ${receivables.format()} (${debtors.size} debtors) | Total Payables: ${payables.format()} (${creditors.size} creditors).",
            itemsProcessed = debtors.size + creditors.size,
            details = mapOf(
                "totalReceivables" to receivables.format(),
                "totalPayables" to payables.format(),
                "debtorsCount" to debtors.size.toString(),
                "creditorsCount" to creditors.size.toString()
            )
        )
    }
}
