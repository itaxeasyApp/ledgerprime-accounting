package com.example.accounting.application.banking

import com.example.accounting.data.repository.AccountingRepository
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import kotlinx.coroutines.flow.first

/**
 * Read-only Cash/Bank ledger query facade (Phase 7J-B, "Cash/Bank") - Cash/Bank is not a separate
 * domain model, it is ordinary [Ledger] rows under the Cash/Bank system groups (Phase 0, frozen).
 * Reuses the exact `StandardSystemGroups.isExactSystemGroup(groupId, BANK_GROUP_ID)`/`CASH_GROUP_ID`
 * filter already independently duplicated in `CreateVoucherDialog.kt` and
 * `automation/reports/ScheduledReportTasks.kt` - a fourth call-site of the same rule, not new logic.
 * (Bank/UPI *settlement metadata* - account numbers, IFSC, UPI handles - is a separate concept, see
 * [com.example.accounting.application.banking.BankUpiProfileService].)
 */
class CashBankLedgerService(private val repository: AccountingRepository) {

    suspend fun getCashAndBankLedgers(companyId: String): List<Ledger> {
        val groupsById = repository.getGroups(companyId).first().associateBy { it.groupId }
        return repository.getLedgers(companyId).first().filter {
            StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
                StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById) ||
                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
        }
    }
}
