package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction

/**
 * Builds the GSTR-9 tables from a financial year's active GST rows. Each line is classified with
 * the very rules [Gstr3bBuilder] uses (zero-rated / nil-exempt / taxable), so the annual tables can
 * never classify a supply differently from the monthly returns. Pure.
 *
 * The portal fills Tables 4 and 5 from the filed GSTR-1/3B, 6A from GSTR-3B and 8A from GSTR-2A/2B;
 * this builder produces the figures a taxpayer would check or enter, for the tables the domain has
 * the data for, and says in [Gstr9ReturnData.coverage] exactly which it does not.
 */
object Gstr9Builder {

    private fun GstTransaction.amounts() = Gstr3bAmounts(taxableAmount, igst, cgst, sgst, cess)

    private fun List<GstTransaction>.sumAmounts() = fold(Gstr3bAmounts.ZERO) { acc, t -> acc + t.amounts() }

    private fun List<GstTransaction>.sumTaxable() = fold(Money.ZERO) { acc, t -> acc + t.taxableAmount }

    private fun isTaxable(t: GstTransaction) = !Gstr3bBuilder.isZeroRated(t) && !Gstr3bBuilder.isNilOrExempt(t)

    private fun isExportWithPayment(t: GstTransaction) = Gstr3bBuilder.isZeroRated(t) && t.igst.paise != 0L

    private fun isExportWithoutPayment(t: GstTransaction) = Gstr3bBuilder.isZeroRated(t) && t.igst.paise == 0L

    fun build(
        companyGstin: String,
        fyCode: String,
        fyEndYear: Int,
        transactions: List<GstTransaction>,
        gstr3b: Gstr3bReturnData,
        hsnOutward: List<Gstr1HsnRow>
    ): Gstr9ReturnData {
        val outward = transactions.filter { it.direction == GstDirection.OUTPUT }
        val supplies = outward.filter { it.voucherType != VoucherType.CREDIT_NOTE && it.voucherType != VoucherType.DEBIT_NOTE }
        val creditNotes = outward.filter { it.voucherType == VoucherType.CREDIT_NOTE }
        val debitNotes = outward.filter { it.voucherType == VoucherType.DEBIT_NOTE }

        val taxableSupplies = supplies.filter { isTaxable(it) }
        val table4 = Gstr9Table4(
            b2c = taxableSupplies.filter { it.partyGstin.isBlank() }.sumAmounts(),
            b2b = taxableSupplies.filter { it.partyGstin.isNotBlank() }.sumAmounts(),
            exportsWithPayment = supplies.filter { isExportWithPayment(it) }.sumAmounts(),
            inwardReverseCharge = gstr3b.inwardReverseCharge,
            creditNotes = creditNotes.filter { isTaxable(it) || isExportWithPayment(it) }.sumAmounts().abs(),
            debitNotes = debitNotes.filter { isTaxable(it) || isExportWithPayment(it) }.sumAmounts()
        )
        val nilSupplies = supplies.filter { Gstr3bBuilder.isNilOrExempt(it) }
        val table5 = Gstr9Table5(
            zeroRatedWithoutPayment = supplies.filter { isExportWithoutPayment(it) }.sumTaxable(),
            exempted = nilSupplies.filter { it.supplyNature != GstSupplyNature.NIL_RATED }.sumTaxable(),
            nilRated = nilSupplies.filter { it.supplyNature == GstSupplyNature.NIL_RATED }.sumTaxable(),
            creditNotes = creditNotes.filter { Gstr3bBuilder.isNilOrExempt(it) || isExportWithoutPayment(it) }.sumTaxable().abs()
        )

        val payment = gstr3b.payment
        return Gstr9ReturnData(
            companyGstin = companyGstin, fyCode = fyCode, fyEndYear = fyEndYear,
            table4 = table4, table5 = table5,
            taxPayable = Gstr3bHeads(
                igst = payment.outwardLiability.igst + payment.reverseChargeLiability.igst,
                cgst = payment.outwardLiability.cgst + payment.reverseChargeLiability.cgst,
                sgst = payment.outwardLiability.sgst + payment.reverseChargeLiability.sgst,
                cess = payment.outwardLiability.cess + payment.reverseChargeLiability.cess
            ),
            itcAvailedPerGstr3b = gstr3b.itcAvailable.fold(Gstr3bAmounts.ZERO) { acc, r -> acc + r.amounts },
            hsnOutward = hsnOutward,
            coverage = coverage()
        )
    }

    /** What this app can and cannot put in each GSTR-9 table, with the reason - shown with every prepared return. */
    fun coverage(): List<Gstr9Coverage> = listOf(
        Gstr9Coverage(
            "4", Gstr9CoverageStatus.PARTIAL,
            "Reported: B2C (4A), B2B (4B), exports with payment of tax (4C), inward supplies on reverse charge (4G), credit notes (4I) and debit notes (4J). " +
                "NOT recorded by this app, so not reported: SEZ supplies with payment (4D - any SEZ supply is counted in exports), deemed exports (4E), " +
                "advances (4F) and amendments (4K-4M, GSTR-1A)."
        ),
        Gstr9Coverage(
            "5", Gstr9CoverageStatus.PARTIAL,
            "Reported: exports without payment (5A), exempted (5D), nil rated (5E) and credit notes (5H). NOT recorded: SEZ without payment (5B), " +
                "supplies taxable in the recipient's hands (5C), non-GST supplies (5F), debit notes and amendments (5I-5L)."
        ),
        Gstr9Coverage(
            "6", Gstr9CoverageStatus.NOT_SUPPORTED,
            "6A is filled by the portal from GSTR-3B. 6B-6H need ITC split into inputs / capital goods / input services, imports and ISD credit; " +
                "this app has no goods-vs-services-vs-capital-goods classification and records no imports or ISD."
        ),
        Gstr9Coverage(
            "7", Gstr9CoverageStatus.NOT_SUPPORTED,
            "Needs ITC reversals by rule (rule 37, 39, 42, 43, section 17(5), TRAN-I/II). Only purchase-return reversals are recorded, which would misstate the table."
        ),
        Gstr9Coverage("8", Gstr9CoverageStatus.NOT_SUPPORTED, "Needs GSTR-2A/2B data (the portal fills 8A from it); the app holds none."),
        Gstr9Coverage(
            "9", Gstr9CoverageStatus.PARTIAL,
            "Tax payable (IGST, CGST, SGST, cess) is reported. The paid-in-cash and paid-through-ITC columns are filled by the portal from GSTR-3B; " +
                "interest, late fee, penalty and other are not recorded."
        ),
        Gstr9Coverage("10-13", Gstr9CoverageStatus.NOT_SUPPORTED, "Transactions of this year declared in the next year's returns are not modeled."),
        Gstr9Coverage("14", Gstr9CoverageStatus.NOT_SUPPORTED, "Differential tax on Tables 10-11 is not modeled."),
        Gstr9Coverage("15", Gstr9CoverageStatus.NOT_SUPPORTED, "Demands and refunds are not modeled."),
        Gstr9Coverage("16", Gstr9CoverageStatus.NOT_SUPPORTED, "Supplies from composition taxpayers, deemed supply by job worker and goods sent on approval are not recorded."),
        Gstr9Coverage("17", Gstr9CoverageStatus.SUPPORTED, "HSN-wise outward summary from the year's GSTR-1 HSN rows (net of credit notes)."),
        Gstr9Coverage(
            "18", Gstr9CoverageStatus.NOT_SUPPORTED,
            "Inward HSN summary is not produced: the table is optional for smaller taxpayers and its reporting rule (which HSN codes to list) is not verified."
        ),
        Gstr9Coverage("19", Gstr9CoverageStatus.NOT_SUPPORTED, "Late fee is calculated by the GST portal.")
    )
}

/**
 * GSTR-9's own consistency checks. The portal ties Table 17 to Tables 4 and 5 with a tolerance of
 * Rs 10 on tax, and Tables 4/5 to GSTR-3B; the same checks are applied here before JSON is generated.
 */
object Gstr9Reconciliation {
    private val HSN_TAX_TOLERANCE = Money.fromPaise(10_00L)

    private fun diff(label: String, left: String, a: Gstr3bAmounts, right: String, b: Gstr3bAmounts, includeTaxable: Boolean): String? {
        val parts = buildList {
            if (includeTaxable && a.taxable.paise != b.taxable.paise) add("taxable value: $left ${a.taxable.formatPlain()} vs $right ${b.taxable.formatPlain()}")
            if (a.igst.paise != b.igst.paise) add("IGST: $left ${a.igst.formatPlain()} vs $right ${b.igst.formatPlain()}")
            if (a.cgst.paise != b.cgst.paise) add("CGST: $left ${a.cgst.formatPlain()} vs $right ${b.cgst.formatPlain()}")
            if (a.sgst.paise != b.sgst.paise) add("SGST: $left ${a.sgst.formatPlain()} vs $right ${b.sgst.formatPlain()}")
            if (a.cess.paise != b.cess.paise) add("CESS: $left ${a.cess.formatPlain()} vs $right ${b.cess.formatPlain()}")
        }
        return if (parts.isEmpty()) null else "$label - ${parts.joinToString("; ")}"
    }

    /** Tables 4 and 5 against the year's GSTR-3B tables (3.1(a)-(d)). */
    fun tablesAgreeWithGstr3b(d: Gstr9ReturnData, g3b: Gstr3bReturnData): List<Gstr1ValidationIssue> {
        val issues = mutableListOf<Gstr1ValidationIssue>()
        val outward3b = g3b.outwardTaxable + g3b.outwardZeroRated
        // Tax only: GSTR-3B 3.1(b) also holds zero-rated exports WITHOUT payment, whose taxable value
        // belongs to Table 5 and is covered by the combined taxable-value check below.
        diff("Table 4 (4A+4B+4C-4I+4J) tax differs from GSTR-3B 3.1(a)+(b)", "GSTR-9", d.table4.netOutward, "GSTR-3B", outward3b, includeTaxable = false)?.let {
            issues += Gstr1ValidationIssue(Gstr1ValidationSeverity.ERROR, "GSTR9_TABLE4_GSTR3B_MISMATCH", it)
        }
        val taxable9 = d.table4.netOutward.taxable + d.table5.net
        val taxable3b = outward3b.taxable + g3b.outwardNilExempt.taxable
        if (taxable9.paise != taxable3b.paise) {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.ERROR, "GSTR9_TABLE5_GSTR3B_MISMATCH",
                "Tables 4 + 5 taxable value ${taxable9.formatPlain()} differs from GSTR-3B 3.1(a)+(b)+(c) ${taxable3b.formatPlain()}"
            )
        }
        diff("Table 4G differs from GSTR-3B 3.1(d)", "GSTR-9", d.table4.inwardReverseCharge, "GSTR-3B", g3b.inwardReverseCharge, includeTaxable = true)?.let {
            issues += Gstr1ValidationIssue(Gstr1ValidationSeverity.ERROR, "GSTR9_TABLE4G_GSTR3B_MISMATCH", it)
        }
        return issues
    }

    /** Table 17's tax against the tax of Tables 4 and 5 (the portal's own tolerance is Rs 10). */
    fun hsnAgreesWithTables(d: Gstr9ReturnData): List<Gstr1ValidationIssue> {
        val tablesTax = d.table4.netOutward.totalTax
        val hsnTax = d.hsnOutward.fold(Money.ZERO) { acc, r -> acc + r.cgst + r.sgst + r.igst + r.cess }
        if (d.hsnOutward.isEmpty() && tablesTax.paise == 0L) return emptyList()
        return if ((hsnTax - tablesTax).abs().paise > HSN_TAX_TOLERANCE.paise) {
            listOf(
                Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.ERROR, "GSTR9_HSN_MISMATCH",
                    "Table 17 tax ${hsnTax.formatPlain()} differs from Tables 4+5 tax ${tablesTax.formatPlain()} by more than the portal's Rs 10 tolerance"
                )
            )
        } else emptyList()
    }
}
