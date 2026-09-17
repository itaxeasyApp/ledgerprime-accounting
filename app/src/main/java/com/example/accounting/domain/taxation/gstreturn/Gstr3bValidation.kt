package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Constants
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstTransaction
import kotlin.math.abs

/**
 * Phase 8 - GSTR-3B validation, same three checks [Gstr1Validator] already runs (GSTIN/POS/tax
 * cross-check) applied to GSTR-3B's own scope: the COMPANY's own filing GSTIN (never a recipient's -
 * GSTR-3B is filed under one GSTIN, this company's), and BOTH directions of transaction (GSTR-1 only
 * ever validates outward). Never re-implements the tax-recomputation math itself - delegates to the
 * same [GSTRules.calculateTax] [Gstr1Validator] already calls.
 */
object Gstr3bValidator {
    private const val TOLERANCE_PAISE = 1L

    fun validate(companyGstin: String, transactions: List<GstTransaction>, isNilReturn: Boolean = false): List<Gstr3bValidationIssue> {
        val issues = mutableListOf<Gstr3bValidationIssue>()

        // ---- This return's own filing GSTIN must be a real, checksum-valid GSTIN - GSTR-3B has
        // no meaning without one, unlike GSTR-1's per-recipient checks. ----
        if (companyGstin.isBlank()) {
            issues += Gstr3bValidationIssue(Gstr1ValidationSeverity.ERROR, "MISSING_COMPANY_GSTIN", "This company has no GSTIN recorded - GSTR-3B cannot be filed without one.")
        } else if (!GstinChecksum.isValidChecksum(companyGstin)) {
            issues += Gstr3bValidationIssue(Gstr1ValidationSeverity.ERROR, "INVALID_COMPANY_GSTIN_CHECKSUM", "This company's own GSTIN '$companyGstin' fails the GSTN check-digit algorithm - verify it was entered correctly in Company settings.")
        }

        // ---- Place of Supply must be a real, government-published state code (both directions). ----
        transactions.forEach { gt ->
            if (gt.placeOfSupply.isNotBlank() && gt.placeOfSupply !in Constants.GST_STATE_CODES) {
                issues += Gstr3bValidationIssue(
                    Gstr1ValidationSeverity.ERROR, "INVALID_PLACE_OF_SUPPLY",
                    "Place of Supply '${gt.placeOfSupply}' on voucher ${gt.voucherId ?: "(GST-only)"} is not a recognized GST state code."
                )
            }
        }

        // ---- Tax cross-check: stored CGST/SGST/IGST must reproduce from taxable value x rate. ----
        transactions.forEach { gt ->
            if (gt.gstRatePercent > 0.0) {
                val expected = GSTRules.calculateTax(gt.taxableAmount.abs(), gt.gstRatePercent, gt.supplyType)
                val sign = if (gt.taxableAmount.paise < 0) -1L else 1L
                val cgstOk = abs(gt.cgst.paise - sign * expected.cgstAmount.paise) <= TOLERANCE_PAISE
                val sgstOk = abs(gt.sgst.paise - sign * expected.sgstAmount.paise) <= TOLERANCE_PAISE
                val igstOk = abs(gt.igst.paise - sign * expected.igstAmount.paise) <= TOLERANCE_PAISE
                if (!cgstOk || !sgstOk || !igstOk) {
                    issues += Gstr3bValidationIssue(
                        Gstr1ValidationSeverity.ERROR, "TAX_RECOMPUTATION_MISMATCH",
                        "Voucher ${gt.voucherId ?: "(GST-only)"}: stored tax does not reproduce from taxable value x rate."
                    )
                }
            }
        }

        // ---- Zero-activity-not-declared-Nil, same discipline as Gstr1Validator's own check -
        // never inferred, only warned until the user explicitly confirms via isNilReturn. ----
        if (transactions.isEmpty() && !isNilReturn) {
            issues += Gstr3bValidationIssue(
                Gstr1ValidationSeverity.WARNING, "ZERO_ACTIVITY_NOT_DECLARED_NIL",
                "This period shows no GST-bearing transactions at all (outward or inward). If that's correct, mark this return as Nil before filing; otherwise check the right period was selected."
            )
        }

        return issues
    }
}
