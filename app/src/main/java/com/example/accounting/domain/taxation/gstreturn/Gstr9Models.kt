package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money

/**
 * Phase 8, Step 8 (GSTR-9) - the annual return's tables this app can honestly produce, built ONLY
 * from the financial year's active [com.example.accounting.domain.taxation.gst.GstTransaction] rows
 * and the GSTR-1 / GSTR-3B tables built from those same rows (see [Gstr9Builder]); no tax is ever
 * recomputed. Every table or row the domain has no data for is listed in [Gstr9ReturnData.coverage]
 * with the reason - it is never silently emitted as zero, and the GSTN JSON simply leaves it out
 * (see [Gstr9PortalJsonSerializer]) so the portal's own auto-populated figures stand.
 */
enum class Gstr9CoverageStatus { SUPPORTED, PARTIAL, NOT_SUPPORTED }

data class Gstr9Coverage(val table: String, val status: Gstr9CoverageStatus, val note: String)

/**
 * Table 4 - supplies and advances on which tax is payable. Credit notes are carried as positive
 * amounts (the form subtracts them), exactly as GSTR-1 reports a note.
 */
data class Gstr9Table4(
    /** 4A - supplies to unregistered persons. */
    val b2c: Gstr3bAmounts,
    /** 4B - supplies to registered persons. */
    val b2b: Gstr3bAmounts,
    /** 4C - exports on which IGST was paid. SEZ supplies are not recorded separately and so fall here. */
    val exportsWithPayment: Gstr3bAmounts,
    /** 4G - inward supplies on which tax is payable under reverse charge. */
    val inwardReverseCharge: Gstr3bAmounts,
    /** 4I - credit notes against the supplies above. */
    val creditNotes: Gstr3bAmounts,
    /** 4J - debit notes against the supplies above. */
    val debitNotes: Gstr3bAmounts
) {
    /** Supplies + debit notes - credit notes (4A + 4B + 4C - 4I + 4J), before the reverse-charge row. */
    val netOutward: Gstr3bAmounts get() = b2c + b2b + exportsWithPayment - creditNotes + debitNotes
}

/** Table 5 - outward supplies on which tax is not payable (taxable value only). */
data class Gstr9Table5(
    /** 5A - zero-rated exports without payment of tax. */
    val zeroRatedWithoutPayment: Money,
    /** 5D - exempted. */
    val exempted: Money,
    /** 5E - nil rated. */
    val nilRated: Money,
    /** 5H - credit notes against the supplies above. */
    val creditNotes: Money
) {
    val net: Money get() = zeroRatedWithoutPayment + exempted + nilRated - creditNotes
}

data class Gstr9ReturnData(
    val companyGstin: String,
    val fyCode: String,
    /** The calendar year the financial year ends in - GSTN's annual `fp` is `03` + this. */
    val fyEndYear: Int,
    val table4: Gstr9Table4,
    val table5: Gstr9Table5,
    /** Table 9, "tax payable" column: tax on outward supplies plus tax payable under reverse charge. */
    val taxPayable: Gstr3bHeads,
    /** Table 6A for reconciliation only: ITC availed through GSTR-3B over the year (the portal fills 6A itself). */
    val itcAvailedPerGstr3b: Gstr3bAmounts,
    /** Table 17 - HSN-wise summary of outward supplies (the GSTR-1 Table 12 rows, for the year). */
    val hsnOutward: List<Gstr1HsnRow>,
    val coverage: List<Gstr9Coverage>
)
