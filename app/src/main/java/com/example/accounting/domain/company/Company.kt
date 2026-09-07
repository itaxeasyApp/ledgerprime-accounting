package com.example.accounting.domain.company

enum class CompanyStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
    ARCHIVED
}

/**
 * Production-grade Company entity acting as the root multi-tenant security boundary.
 * All financial data, ledgers, vouchers, financial years, and audit logs are strictly scoped by companyId.
 */
data class Company(
    val companyId: String,
    val name: String,
    val legalName: String = name,
    val tradeName: String = "",
    val cin: String = "",
    val gstin: String = "",
    val pan: String = "",
    val stateCode: String = "27",
    val stateName: String = "Maharashtra",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val currency: String = "INR",
    val financialYearStartMonth: Int = 4, // April (Indian FY)
    val accountingMode: AccountingMode = AccountingMode.ACCOUNT_ONLY,
    val businessType: BusinessType = BusinessType.TRADING,
    /**
     * Whether this company uses/enables GST functionality at all - a company-level
     * configuration/capability toggle, the same shape as [accountingMode]/[businessType], and
     * deliberately independent of [com.example.accounting.domain.accounting.Ledger.gstRegistrationStatus]
     * (a per-ledger *fact*, never inferred from or synced with this setting).
     *
     * Defaults to `true`, not `false`: every existing Sale/Purchase/Credit-Debit-Note voucher is
     * built with `Voucher.isGstApplicable = true` today regardless of any company setting (there
     * was none before this field), and GST amounts are always computed per line via
     * `GstCalculationEngine`. `true` is the only default that reproduces every existing company's
     * current behavior unchanged - `false` would silently flip every pre-existing company to a
     * "GST disabled" state nothing about their actual data or configuration ever chose.
     */
    val gstEnabled: Boolean = true,
    /**
     * D1a (Company Mode + Account-Only Sale/Purchase) - see [GstOperatingMode] for the full
     * rationale. Defaults to `ACCOUNT_WITH_GST`, not `ACCOUNT_ONLY` - the same reasoning as
     * [gstEnabled]'s own default: every company that existed before this field was added already
     * behaves as "GST applies to Sale/Purchase/Notes" today, so `ACCOUNT_WITH_GST` is the only
     * default that reproduces that unchanged. New companies may still choose `ACCOUNT_ONLY` at
     * creation time.
     */
    val gstOperatingMode: GstOperatingMode = GstOperatingMode.ACCOUNT_WITH_GST,
    /**
     * Rule 33 (GST Return Dashboard & Filing Foundation) - this company's GST registration scheme
     * (Regular/Composition/QRMP), the single authoritative source the Return Dashboard reads from.
     * No such field existed anywhere on the company's statutory profile before this - every
     * pre-existing company defaults to REGULAR, the ordinary/most common scheme and the only one
     * that reproduces every prior company's unchanged filing expectations (a silent default of
     * COMPOSITION or QRMP would misrepresent real companies that never chose either).
     */
    val gstScheme: com.example.accounting.domain.taxation.gstreturn.GstScheme =
        com.example.accounting.domain.taxation.gstreturn.GstScheme.REGULAR,
    /**
     * Rule 33 - filing frequency for a REGULAR company (Monthly, the statutory default, or QRMP's
     * quarterly option for taxpayers under the turnover threshold). Meaningless for COMPOSITION
     * (GSTR-4 is always quarterly) - reuses [com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity]
     * rather than a new enum, since it is exactly that same MONTHLY/QUARTERLY choice.
     */
    val gstFilingFrequency: com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity =
        com.example.accounting.domain.taxation.gstreturn.GstReturnPeriodicity.MONTHLY,
    val status: CompanyStatus = CompanyStatus.ACTIVE,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Optional postal PIN code - 13-point correctness pass, item 1. Company previously had no
     * field for this at all (only Ledger did); never required, mirrors [address]'s own optionality.
     * Appended last (never inserted mid-constructor) so every existing `Company(...)` call site
     * keeps compiling. */
    val pinCode: String = "",
    /** Phase 8A, Part 2 - whether [com.example.accounting.automation.compliance.GstReturnAutomationChecker.checkFilingReminder]
     * is allowed to emit GSTR-1 due-soon/overdue notifications for this company. Independent of
     * [gstEnabled]/[gstScheme] (draft preparation and validation automation stay on regardless -
     * this only gates the reminder notification, never the underlying prepare/validate checks).
     * Defaults to `true` so every existing company keeps its current (always-on) behavior
     * unchanged. */
    val gstr1ReminderEnabled: Boolean = true
) {
    init {
        require(companyId.isNotBlank()) { "Company ID must not be blank" }
        require(name.isNotBlank()) { "Company name must not be blank" }
    }

    val isActive: Boolean get() = status == CompanyStatus.ACTIVE
}
