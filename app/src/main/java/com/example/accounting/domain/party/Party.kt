package com.example.accounting.domain.party

enum class PartyRole {
    CUSTOMER,
    SUPPLIER
}

enum class PartyEntityType {
    INDIVIDUAL,
    BUSINESS
}

/**
 * A thin, additive extension of an existing [com.example.accounting.domain.accounting.Ledger]
 * (Phase 7A) - every Party points at exactly one Ledger already created under the Sundry
 * Debtors/Creditors group via the existing `createLedger()`. GSTIN/PAN/address/bank fields stay
 * on Ledger, never duplicated here; Party adds only what a plain ledger doesn't carry: role,
 * entity type, credit limit, and payment terms. An entity that is genuinely both a customer and a
 * supplier gets two Party rows (one per role), matching standard double-entry practice of keeping
 * debtor and creditor tracking on separate ledgers.
 */
data class Party(
    val partyId: String,
    val companyId: String,
    val ledgerId: String,
    val role: PartyRole,
    val entityType: PartyEntityType,
    val displayName: String,
    val contactName: String = "",
    val creditLimitPaise: Long? = null,
    val paymentTerms: PaymentTerms = PaymentTerms.DUE_ON_RECEIPT,
    val isActive: Boolean = true,
    /** User-marked favorite (real, persisted field - a starred Customer/Supplier sorts first in
     * the picker so a frequently-invoiced party doesn't get lost in a long list). Never inferred
     * from transaction frequency or anything else - only ever set by an explicit tap. */
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
