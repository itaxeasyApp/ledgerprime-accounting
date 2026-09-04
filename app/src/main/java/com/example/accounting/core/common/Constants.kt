package com.example.accounting.core.common

object Constants {
    const val APP_VERSION = "1.0.0-prod"
    const val DEFAULT_CURRENCY = "INR"
    const val DEFAULT_CURRENCY_SYMBOL = "₹"
    const val DEFAULT_STATE_CODE = "27" // Maharashtra
    const val DEFAULT_STATE_NAME = "Maharashtra"

    // Standard Indian GST Tax Rates
    val GST_RATES = listOf(0.0, 5.0, 12.0, 18.0, 28.0)

    /** The fixed, government-published GST state-code table (01-38) - reference data, not a
     * guess. Used to derive [com.example.accounting.domain.company.Company.stateName] from a
     * user-entered [com.example.accounting.domain.company.Company.stateCode] at company-creation
     * time, replacing what was previously always the hardcoded "Maharashtra" default regardless
     * of the actual state code entered. */
    val GST_STATE_CODES: Map<String, String> = mapOf(
        "01" to "Jammu and Kashmir", "02" to "Himachal Pradesh", "03" to "Punjab",
        "04" to "Chandigarh", "05" to "Uttarakhand", "06" to "Haryana", "07" to "Delhi",
        "08" to "Rajasthan", "09" to "Uttar Pradesh", "10" to "Bihar", "11" to "Sikkim",
        "12" to "Arunachal Pradesh", "13" to "Nagaland", "14" to "Manipur", "15" to "Mizoram",
        "16" to "Tripura", "17" to "Meghalaya", "18" to "Assam", "19" to "West Bengal",
        "20" to "Jharkhand", "21" to "Odisha", "22" to "Chhattisgarh", "23" to "Madhya Pradesh",
        "24" to "Gujarat", "25" to "Daman and Diu", "26" to "Dadra and Nagar Haveli",
        "27" to "Maharashtra", "28" to "Andhra Pradesh (Old)", "29" to "Karnataka",
        "30" to "Goa", "31" to "Lakshadweep", "32" to "Kerala", "33" to "Tamil Nadu",
        "34" to "Puducherry", "35" to "Andaman and Nicobar Islands", "36" to "Telangana",
        "37" to "Andhra Pradesh", "38" to "Ladakh"
    )

    // Primary Accounting Categories (Nature of Accounts)
    const val GROUP_ASSETS = "ASSETS"
    const val GROUP_LIABILITIES = "LIABILITIES"
    const val GROUP_EQUITY = "EQUITY_CAPITAL"
    const val GROUP_INCOME = "INCOME_REVENUE"
    const val GROUP_EXPENSES = "EXPENSES"
    const val GROUP_SPECIAL_CONTROL = "SPECIAL_CONTROL"

    // Default 28 System Groups
    const val SYS_BANK_ACCOUNTS = "Bank Accounts"
    const val SYS_CASH_IN_HAND = "Cash-in-Hand"
    const val SYS_SUNDRY_DEBTORS = "Sundry Debtors"
    const val SYS_SUNDRY_CREDITORS = "Sundry Creditors"
    const val SYS_DUTIES_TAXES = "Duties & Taxes"
    const val SYS_SALES_ACCOUNTS = "Sales Accounts"
    const val SYS_PURCHASE_ACCOUNTS = "Purchase Accounts"
    const val SYS_DIRECT_EXPENSES = "Direct Expenses"
    const val SYS_INDIRECT_EXPENSES = "Indirect Expenses"
    const val SYS_DIRECT_INCOMES = "Direct Incomes"
    const val SYS_INDIRECT_INCOMES = "Indirect Incomes"
    const val SYS_FIXED_ASSETS = "Fixed Assets"
    const val SYS_CURRENT_ASSETS = "Current Assets"
    const val SYS_CURRENT_LIABILITIES = "Current Liabilities"
    const val SYS_CAPITAL_ACCOUNT = "Capital Account"
    const val SYS_LOANS_LIABILITY = "Loans (Liability)"
    const val SYS_STOCK_IN_HAND = "Stock-in-Hand"
    const val SYS_PROVISIONS = "Provisions"
    const val SYS_DEPOSITS_ASSET = "Deposits (Asset)"
    const val SYS_INVESTMENTS = "Investments"
    const val SYS_BANK_OD = "Bank OD A/c"
    const val SYS_SECURED_LOANS = "Secured Loans"
    const val SYS_UNSECURED_LOANS = "Unsecured Loans"
    const val SYS_RESERVES_SURPLUS = "Reserves & Surplus"
    const val SYS_SUSPENSE_ACCOUNT = "Suspense A/c"
    const val SYS_ROUND_OFF_ACCOUNT = "Round Off A/c"
    const val SYS_MISC_EXPENSES = "Misc. Expenses (Asset)"
    const val SYS_BRANCH_DIVISIONS = "Branch / Divisions"
    const val SYS_LOANS_ADVANCES = "Loans & Advances (Asset)"
}
