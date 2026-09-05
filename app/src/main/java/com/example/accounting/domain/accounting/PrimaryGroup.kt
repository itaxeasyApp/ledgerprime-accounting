package com.example.accounting.domain.accounting

import com.example.accounting.core.common.Constants
import com.example.accounting.core.common.DrCr

enum class PrimaryGroup(
    val code: String,
    val displayName: String,
    val naturalBalance: DrCr,
    val statementType: StatementType,
    val isSpecialControl: Boolean = false
) {
    ASSETS("ASSETS", "Assets", DrCr.DEBIT, StatementType.BALANCE_SHEET),
    LIABILITIES("LIABILITIES", "Liabilities", DrCr.CREDIT, StatementType.BALANCE_SHEET),
    EQUITY("EQUITY_CAPITAL", "Capital & Equity", DrCr.CREDIT, StatementType.BALANCE_SHEET),
    INCOME("INCOME_REVENUE", "Income & Revenue", DrCr.CREDIT, StatementType.PROFIT_AND_LOSS),
    EXPENSES("EXPENSES", "Expenses", DrCr.DEBIT, StatementType.PROFIT_AND_LOSS),
    SPECIAL_CONTROL("SPECIAL_CONTROL", "Special Control Accounts", DrCr.DEBIT, StatementType.BALANCE_SHEET, isSpecialControl = true);

    companion object {
        fun fromCode(code: String): PrimaryGroup {
            return entries.find { it.code.equals(code, ignoreCase = true) || it.name.equals(code, ignoreCase = true) } ?: ASSETS
        }
    }
}

enum class StatementType {
    BALANCE_SHEET,
    PROFIT_AND_LOSS
}

data class AccountGroup(
    val groupId: String,
    val companyId: String,
    val name: String,
    val primaryGroup: PrimaryGroup,
    val parentGroupId: String? = null,
    val isSystem: Boolean = true,
    val affectsGrossProfit: Boolean = false,
    val displayOrder: Int = 0
)

typealias Group = AccountGroup

object StandardSystemGroups {
    const val SUSPENSE_GROUP_ID = "GRP_SYS_SUSPENSE"
    const val SUSPENSE_LEDGER_ID = "LED_SYS_SUSPENSE"

    /** Round Off (Phase 5, Priority 7) - protected identically to Suspense (system, non-deletable,
     * non-renamable, non-reparentable) but conceptually distinct: Suspense holds unresolved
     * accounting differences, Round Off holds legitimate invoice-total rounding differences the
     * trading engine computes itself. Never combined into one ledger. */
    const val ROUND_OFF_GROUP_ID = "GRP_SYS_ROUND_OFF"
    const val ROUND_OFF_LEDGER_ID = "LED_SYS_ROUND_OFF"

    // Bare (company-suffix-free) standard group IDs. The full ID for a given company is always
    // "${THIS}_$companyId" - these are the same literal prefixes used below in
    // getStandardGroupsForCompany, centralized here so financial-statement classification can
    // check exact groupId ancestry instead of matching on the (mutable, user-facing) group name.
    const val CAPITAL_GROUP_ID = "GRP_CAPITAL"
    const val RESERVES_GROUP_ID = "GRP_RESERVES"
    const val CURRENT_ASSETS_GROUP_ID = "GRP_CURRENT_ASSETS"
    const val BANK_GROUP_ID = "GRP_BANK"
    const val CASH_GROUP_ID = "GRP_CASH"
    const val DEPOSITS_GROUP_ID = "GRP_DEPOSITS"
    const val LOANS_ADVANCES_GROUP_ID = "GRP_LOANS_ADV"
    const val STOCK_GROUP_ID = "GRP_STOCK"
    const val DEBTORS_GROUP_ID = "GRP_DEBTORS"
    const val FIXED_ASSETS_GROUP_ID = "GRP_FIXED_ASSETS"
    const val INVESTMENTS_GROUP_ID = "GRP_INVESTMENTS"
    const val MISC_EXPENSES_GROUP_ID = "GRP_MISC_EXP"
    const val CURRENT_LIABILITIES_GROUP_ID = "GRP_CURRENT_LIAB"
    const val DUTIES_GROUP_ID = "GRP_DUTIES"
    const val PROVISIONS_GROUP_ID = "GRP_PROVISIONS"
    const val CREDITORS_GROUP_ID = "GRP_CREDITORS"
    const val LOANS_GROUP_ID = "GRP_LOANS"
    const val BANK_OD_GROUP_ID = "GRP_BANK_OD"
    const val SECURED_LOANS_GROUP_ID = "GRP_SECURED_LOANS"
    const val UNSECURED_LOANS_GROUP_ID = "GRP_UNSECURED_LOANS"
    const val BRANCH_DIVISIONS_GROUP_ID = "GRP_BRANCH_DIV"
    const val SALES_GROUP_ID = "GRP_SALES"
    const val DIRECT_INCOME_GROUP_ID = "GRP_DIR_INCOME"
    const val INDIRECT_INCOME_GROUP_ID = "GRP_INDIR_INCOME"
    const val PURCHASE_GROUP_ID = "GRP_PURCHASE"
    const val DIRECT_EXPENSE_GROUP_ID = "GRP_DIR_EXP"
    const val INDIRECT_EXPENSE_GROUP_ID = "GRP_INDIR_EXP"

    // System group definitions adhering to double-entry standards (28 Standard Indian ERP Groups)
    fun getStandardGroupsForCompany(companyId: String): List<AccountGroup> {
        return listOf(
            // EQUITY
            AccountGroup("GRP_CAPITAL_$companyId", companyId, Constants.SYS_CAPITAL_ACCOUNT, PrimaryGroup.EQUITY, null, true, false, 10),
            AccountGroup("GRP_RESERVES_$companyId", companyId, Constants.SYS_RESERVES_SURPLUS, PrimaryGroup.EQUITY, null, true, false, 20),
            
            // ASSETS
            AccountGroup("GRP_CURRENT_ASSETS_$companyId", companyId, Constants.SYS_CURRENT_ASSETS, PrimaryGroup.ASSETS, null, true, false, 30),
            AccountGroup("GRP_BANK_$companyId", companyId, Constants.SYS_BANK_ACCOUNTS, PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true, false, 31),
            AccountGroup("GRP_CASH_$companyId", companyId, Constants.SYS_CASH_IN_HAND, PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true, false, 32),
            AccountGroup("GRP_DEPOSITS_$companyId", companyId, Constants.SYS_DEPOSITS_ASSET, PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true, false, 33),
            AccountGroup("GRP_LOANS_ADV_$companyId", companyId, Constants.SYS_LOANS_ADVANCES, PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true, false, 34),
            AccountGroup("GRP_STOCK_$companyId", companyId, Constants.SYS_STOCK_IN_HAND, PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true, true, 35),
            AccountGroup("GRP_DEBTORS_$companyId", companyId, Constants.SYS_SUNDRY_DEBTORS, PrimaryGroup.ASSETS, "GRP_CURRENT_ASSETS_$companyId", true, false, 36),
            AccountGroup("GRP_FIXED_ASSETS_$companyId", companyId, Constants.SYS_FIXED_ASSETS, PrimaryGroup.ASSETS, null, true, false, 40),
            AccountGroup("GRP_INVESTMENTS_$companyId", companyId, Constants.SYS_INVESTMENTS, PrimaryGroup.ASSETS, null, true, false, 50),
            AccountGroup("GRP_MISC_EXP_$companyId", companyId, Constants.SYS_MISC_EXPENSES, PrimaryGroup.ASSETS, null, true, false, 55),
            
            // LIABILITIES
            AccountGroup("GRP_CURRENT_LIAB_$companyId", companyId, Constants.SYS_CURRENT_LIABILITIES, PrimaryGroup.LIABILITIES, null, true, false, 60),
            AccountGroup("GRP_DUTIES_$companyId", companyId, Constants.SYS_DUTIES_TAXES, PrimaryGroup.LIABILITIES, "GRP_CURRENT_LIAB_$companyId", true, false, 61),
            AccountGroup("GRP_PROVISIONS_$companyId", companyId, Constants.SYS_PROVISIONS, PrimaryGroup.LIABILITIES, "GRP_CURRENT_LIAB_$companyId", true, false, 62),
            AccountGroup("GRP_CREDITORS_$companyId", companyId, Constants.SYS_SUNDRY_CREDITORS, PrimaryGroup.LIABILITIES, "GRP_CURRENT_LIAB_$companyId", true, false, 63),
            AccountGroup("GRP_LOANS_$companyId", companyId, Constants.SYS_LOANS_LIABILITY, PrimaryGroup.LIABILITIES, null, true, false, 70),
            AccountGroup("GRP_BANK_OD_$companyId", companyId, Constants.SYS_BANK_OD, PrimaryGroup.LIABILITIES, "GRP_LOANS_$companyId", true, false, 71),
            AccountGroup("GRP_SECURED_LOANS_$companyId", companyId, Constants.SYS_SECURED_LOANS, PrimaryGroup.LIABILITIES, "GRP_LOANS_$companyId", true, false, 72),
            AccountGroup("GRP_UNSECURED_LOANS_$companyId", companyId, Constants.SYS_UNSECURED_LOANS, PrimaryGroup.LIABILITIES, "GRP_LOANS_$companyId", true, false, 73),
            AccountGroup("GRP_BRANCH_DIV_$companyId", companyId, Constants.SYS_BRANCH_DIVISIONS, PrimaryGroup.LIABILITIES, null, true, false, 75),
            
            // INCOME
            AccountGroup("GRP_SALES_$companyId", companyId, Constants.SYS_SALES_ACCOUNTS, PrimaryGroup.INCOME, null, true, true, 80),
            AccountGroup("GRP_DIR_INCOME_$companyId", companyId, Constants.SYS_DIRECT_INCOMES, PrimaryGroup.INCOME, null, true, true, 90),
            AccountGroup("GRP_INDIR_INCOME_$companyId", companyId, Constants.SYS_INDIRECT_INCOMES, PrimaryGroup.INCOME, null, true, false, 100),
            
            // EXPENSES
            AccountGroup("GRP_PURCHASE_$companyId", companyId, Constants.SYS_PURCHASE_ACCOUNTS, PrimaryGroup.EXPENSES, null, true, true, 110),
            AccountGroup("GRP_DIR_EXP_$companyId", companyId, Constants.SYS_DIRECT_EXPENSES, PrimaryGroup.EXPENSES, null, true, true, 120),
            AccountGroup("GRP_INDIR_EXP_$companyId", companyId, Constants.SYS_INDIRECT_EXPENSES, PrimaryGroup.EXPENSES, null, true, false, 130),
            
            // SPECIAL CONTROL (SUSPENSE, ROUND OFF)
            AccountGroup("${SUSPENSE_GROUP_ID}_$companyId", companyId, Constants.SYS_SUSPENSE_ACCOUNT, PrimaryGroup.SPECIAL_CONTROL, null, true, false, 999),
            AccountGroup("${ROUND_OFF_GROUP_ID}_$companyId", companyId, Constants.SYS_ROUND_OFF_ACCOUNT, PrimaryGroup.SPECIAL_CONTROL, null, true, false, 998)
        )
    }

    /**
     * Architecture correction (real Group hierarchy) - the correct way to answer "is this ledger's
     * group a Bank/Cash/Debtor/etc. account?": walk [AccountGroup.parentGroupId] all the way to the
     * root, checking each ancestor's `groupId` against [rootGroupIdPrefix], instead of the flat
     * `ledgerGroupId.startsWith(...)` check every call site used to do independently. A User Group
     * nested arbitrarily deep under a System Group (e.g. a company's own "HDFC Current A/c" group
     * filed under the System "Bank Accounts" group) is now correctly recognized, not just a ledger
     * filed directly under the System group itself. [groupsById] is always the caller's own
     * already-loaded `groups` list keyed by `groupId` - never a second fetch. Cycle-safe (a
     * `parentGroupId` chain can never legitimately cycle, but a corrupt one must not infinite-loop).
     */
    fun isUnder(ledgerGroupId: String, rootGroupIdPrefix: String, groupsById: Map<String, AccountGroup>): Boolean {
        var current: AccountGroup? = groupsById[ledgerGroupId]
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.groupId)) {
            if (current.groupId.startsWith("${rootGroupIdPrefix}_")) return true
            current = current.parentGroupId?.let { groupsById[it] }
        }
        return false
    }
}
