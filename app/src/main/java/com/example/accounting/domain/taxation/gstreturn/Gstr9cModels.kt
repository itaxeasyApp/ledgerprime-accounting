package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Phase 8, Step 9 (GSTR-9C) - the reconciliation statement between this app's books of account and
 * the annual return ([Gstr9ReturnData]), for the tables the domain has the data for.
 *
 * What is NOT here, and why (each is also in [Gstr9cReturnData.coverage]):
 *  - there is no audited financial statement in this app. "Turnover per books" is the books of
 *    account (Sales + Direct Income ledgers, net), and is labelled that way everywhere - never "audited";
 *  - the reconciling items between books and the return (unbilled revenue, advances, deemed supply,
 *    trade discounts, foreign-exchange adjustments, ...) are not recorded, so they are not fields:
 *    the difference is reported as UNRECONCILED, never as reconciled and never as zero;
 *  - the reasons for a difference, and the Part B certification, can only come from the taxpayer;
 *  - interest and the Table 17 late fee are calculated by the GST portal and are never computed here.
 */
enum class Gstr9cApplicabilityStatus {
    /** This GSTIN's own turnover exceeds the threshold, so GSTR-9C is required. */
    REQUIRED_ON_THIS_GSTIN,

    /** This GSTIN's own turnover does not exceed the threshold. Aggregate turnover is PAN-wide, which this app cannot see. */
    NOT_REQUIRED_ON_THIS_GSTIN
}

data class Gstr9cApplicability(
    val status: Gstr9cApplicabilityStatus,
    /** This GSTIN's turnover for the year, as declared in GSTR-9 (Tables 4 + 5, net of credit notes). */
    val turnoverOfThisGstin: Money,
    val threshold: Money,
    val note: String
)

/** Table 5 - reconciliation of turnover per books with the turnover declared in GSTR-9. */
data class Gstr9cTurnoverReconciliation(
    /** Sales + Direct Income ledgers for the year, net. NOT an audited figure. */
    val turnoverPerBooks: Money,
    /** Turnover declared in GSTR-9: Tables 4 and 5, net of credit notes and excluding inward reverse charge. */
    val turnoverPerGstr9: Money
) {
    /** Table 5R: declared in GSTR-9 minus per books (the manual's Q - P), with every reconciling item unrecorded - so any non-zero value is unreconciled. */
    val unreconciled: Money get() = turnoverPerGstr9 - turnoverPerBooks
}

/** Table 7 - reconciliation of taxable turnover. */
data class Gstr9cTaxableTurnoverReconciliation(
    val turnoverPerBooks: Money,
    /** Deduction: exempted and nil-rated supplies (GSTR-9 Tables 5D + 5E). */
    val exemptedAndNilRated: Money,
    /** Deduction: zero-rated exports without payment of tax (GSTR-9 Table 5A). */
    val zeroRatedWithoutPayment: Money,
    /** Taxable turnover declared in GSTR-9 (Table 4, net of credit notes). */
    val taxableTurnoverPerGstr9: Money
) {
    val taxableTurnoverPerBooks: Money get() = turnoverPerBooks - exemptedAndNilRated - zeroRatedWithoutPayment
    /** Table 7G: declared in GSTR-9 minus per books (the manual's F - E). */
    val unreconciled: Money get() = taxableTurnoverPerGstr9 - taxableTurnoverPerBooks
}

/** One tax head of Table 9: tax per the GST ledgers against tax payable per GSTR-9 Table 9. */
data class Gstr9cTaxHeadRow(val head: String, val perBooks: Money, val perGstr9: Money) {
    /** Declared in GSTR-9 minus per the books, the same sign as Tables 5R and 7G. */
    val unreconciled: Money get() = perGstr9 - perBooks
}

/** Table 11 candidate ("additional amount payable but not paid"): tax per the books above what GSTR-9 declares as payable. Interest is NOT computed. */
data class Gstr9cAdditionalLiability(val igst: Money, val cgst: Money, val sgst: Money) {
    val total: Money get() = igst + cgst + sgst
}

/** Part B - only what the app authoritatively knows; the certification itself is the taxpayer's. */
data class Gstr9cPartB(
    val gstin: String,
    val financialYear: String,
    /** Fields that only the taxpayer can supply and that this app will never fill in. */
    val inputsRequiredFromTaxpayer: List<String>
)

data class Gstr9cReturnData(
    val companyGstin: String,
    val fyCode: String,
    val applicability: Gstr9cApplicability,
    val turnover: Gstr9cTurnoverReconciliation,
    val taxableTurnover: Gstr9cTaxableTurnoverReconciliation,
    val taxPaid: List<Gstr9cTaxHeadRow>,
    val additionalLiability: Gstr9cAdditionalLiability,
    val partB: Gstr9cPartB,
    val coverage: List<Gstr9Coverage>
)
