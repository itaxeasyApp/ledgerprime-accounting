package com.example.accounting.domain.accounting

import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money

/**
 * A Ledger's GST registration status - a statutory tax-identity fact, the same family as
 * [Ledger.gstin]/[Ledger.pan]/[Ledger.stateCode], so it lives here rather than on
 * [com.example.accounting.domain.party.Party] (see `docs/35_PARTY_INVOICE_DOMAIN.md`'s own
 * "GSTIN/PAN/address/bank details stay on Ledger, never duplicated onto Party" principle).
 * Deliberately only two values - `COMPOSITION`/`SEZ` are not justified by anything in the current
 * architecture and would be a guessed-ahead addition, not a derived one; this is a plain enum
 * specifically so a future value is additive, never a rewrite, matching this codebase's existing
 * `VoucherType`/`ExportFormat` precedent.
 */
enum class GstRegistrationStatus {
    REGISTERED,
    UNREGISTERED
}

data class Ledger(
    val ledgerId: String,
    val companyId: String,
    val groupId: String,
    val groupName: String = "",
    val primaryGroup: PrimaryGroup = PrimaryGroup.ASSETS,
    val name: String,
    val code: String = "",
    val openingBalance: Money = Money.ZERO,
    val openingBalanceType: DrCr = DrCr.DEBIT,
    val currentBalance: Money = Money.ZERO,
    val currentBalanceType: DrCr = DrCr.DEBIT,
    val gstin: String = "",
    /** `null` means UNKNOWN / NOT PROVIDED - never treated as either [GstRegistrationStatus.REGISTERED]
     * or [GstRegistrationStatus.UNREGISTERED]. Every ledger that existed before this field was added
     * has no source data for it, so `null` (never a guessed default) is the only honest value. */
    val gstRegistrationStatus: GstRegistrationStatus? = null,
    val pan: String = "",
    val stateCode: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    /** Audit fix (Company/Profile/Ledger Setup) - a Bank-group ledger previously had no field for
     * the bank's own name, even though [bankAccountNumber]/[bankIfsc] already existed; the UI
     * never actually collected any of the three. [bankBranch] is the fourth, matching what
     * [com.example.accounting.domain.rendering.BusinessProfile] already carries for the same
     * concept (document-branding scope, never conflated with this per-ledger fact). */
    val bankName: String = "",
    val bankAccountNumber: String = "",
    val bankIfsc: String = "",
    val bankBranch: String = "",
    val isSystem: Boolean = false,
    val isActive: Boolean = true,
    val hsnSacCode: String = "",
    val defaultTaxRate: Double = 0.0
) {
    /**
     * Calculates signed balance: Positive if natural balance matches current type, negative if opposite.
     */
    fun signedBalancePaise(): Long {
        val sign = if (currentBalanceType == primaryGroup.naturalBalance) 1L else -1L
        return currentBalance.paise * sign
    }
}
