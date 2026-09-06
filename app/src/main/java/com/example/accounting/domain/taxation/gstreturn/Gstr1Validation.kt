package com.example.accounting.domain.taxation.gstreturn

import com.example.accounting.core.common.Constants
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstTransaction
import kotlin.math.abs

/**
 * Phase 8A, Part 1 - GSTIN checksum arithmetic (the real GSTN check-digit algorithm; [GSTRules.isValidGSTIN]
 * only checks the 15-character format via regex, never the check digit). A GSTIN's 15th character
 * is a MOD-36 checksum over the first 14 characters, using the same alphabet GSTN itself documents
 * (0-9 then A-Z). This is publicly documented arithmetic, not a guess or approximation.
 */
object GstinChecksum {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** `false` for a blank GSTIN too (mirrors [GSTRules.isValidGSTIN]'s own "optional for non-GST
     * parties" stance - a blank value is a missing fact, never a failed checksum). Only meaningful
     * for a value that already passes [GSTRules.isValidGSTIN]'s 15-character format check. */
    fun isValidChecksum(gstin: String): Boolean {
        val g = gstin.trim().uppercase()
        if (g.isBlank()) return false
        if (!GSTRules.isValidGSTIN(g)) return false
        val expected = computeCheckDigit(g.substring(0, 14)) ?: return false
        return expected == g[14]
    }

    private fun computeCheckDigit(first14: String): Char? {
        var factor = 2
        var sum = 0
        for (i in first14.length - 1 downTo 0) {
            val code = ALPHABET.indexOf(first14[i])
            if (code < 0) return null
            var digit = factor * code
            digit = (digit / 36) + (digit % 36)
            sum += digit
            factor = if (factor == 2) 1 else 2
        }
        val checkCodePoint = (36 - (sum % 36)) % 36
        return ALPHABET[checkCodePoint]
    }
}

/**
 * Phase 8A, Part 1 - GSTR-1-specific validation, layered ON TOP of
 * [com.example.accounting.data.repository.AccountingRepository.validateGstReturn]'s existing
 * unresolved-Place-of-Supply check (kept, never duplicated here). Every check here reuses facts
 * this domain already has - no new field is invented to make a check possible.
 */
object Gstr1Validator {
    /** A line's stored tax must reproduce from its own taxable value/rate within 1 paisa per
     * component (Money's own paise-rounding tolerance) - anything wider indicates the row was
     * corrupted/hand-edited after posting, not a genuine rounding artifact. */
    private const val TOLERANCE_PAISE = 1L

    fun validate(
        data: Gstr1ReturnData,
        transactions: List<GstTransaction>,
        allVouchersById: Map<String, Voucher>,
        companyScheme: GstScheme = GstScheme.REGULAR,
        isNilReturn: Boolean = false
    ): List<Gstr1ValidationIssue> {
        val issues = mutableListOf<Gstr1ValidationIssue>()

        // ---- Company scheme must actually support GSTR-1 (Composition files GSTR-4, never GSTR-1 -
        // see GstReturnApplicability) - this is a hard block, never a return quietly built anyway. ----
        if (companyScheme == GstScheme.COMPOSITION) {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.ERROR, "SCHEME_DOES_NOT_FILE_GSTR1",
                "This company is registered under the Composition scheme, which files GSTR-4, not GSTR-1 - a Composition dealer must not collect/report tax via GSTR-1 tables."
            )
        }

        // ---- Zero outward supplies for the whole period is either a genuine Nil month or a real
        // data gap (Sales not entered, wrong period selected, etc.) - never silently indistinguishable
        // from a fully-reviewed, deliberately-empty return. Suppressed only by the user's own
        // explicit isNilReturn declaration (see GstReturn.isNilReturn's KDoc), never inferred. ----
        val hasAnyOutwardSupply = data.b2b.isNotEmpty() || data.b2cl.isNotEmpty() || data.b2cs.isNotEmpty() ||
            data.cdnr.isNotEmpty() || data.cdnur.isNotEmpty() || data.exports.isNotEmpty() || data.nilRated.isNotEmpty()
        if (!hasAnyOutwardSupply && !isNilReturn) {
            issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.WARNING, "ZERO_OUTWARD_SUPPLIES_NOT_DECLARED_NIL",
                "This period shows zero outward supplies (no Sales/Credit Notes/Exports found). If that's correct, mark this return as a Nil Return before filing; otherwise check you've selected the right period or that Sales data for it was actually posted."
            )
        }

        // ---- A registered recipient's GSTIN must be captured consistently for every invoice of
        // theirs, never blank on some lines and populated on others for the same party (a mixed
        // signal usually means the ledger's GSTIN was added/edited mid-way through the period). ----
        transactions.filter { it.voucherId != null }.groupBy { it.partyLedgerId }
            .filter { (_, rows) -> rows.map { it.partyGstin.isNotBlank() }.distinct().size > 1 }
            .forEach { (partyLedgerId, _) ->
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.WARNING, "INCONSISTENT_PARTY_GSTIN",
                    "Party ledger $partyLedgerId has some invoices with a GSTIN recorded and others without - verify its registration status/GSTIN."
                )
            }

        // ---- The same GSTIN must not resolve to more than one distinct party ledger - that is
        // either a duplicate party record or a copy-paste GSTIN error, never a normal fact. ----
        transactions.filter { it.partyGstin.isNotBlank() }
            .groupBy { it.partyGstin }
            .filter { (_, rows) -> rows.map { it.partyLedgerId }.distinct().size > 1 }
            .forEach { (gstin, rows) ->
                val parties = rows.map { it.partyLedgerId }.distinct()
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.WARNING, "DUPLICATE_GSTIN_ACROSS_PARTIES",
                    "GSTIN '$gstin' is recorded against ${parties.size} different party ledgers (${parties.joinToString(", ")}) - verify these are not duplicate party records."
                )
            }

        // ---- A ledger's own recorded GstRegistrationStatus must agree with whether a GSTIN was
        // actually captured for it - a REGISTERED party with no GSTIN, or an UNREGISTERED party
        // that somehow has one, is a real data mismatch, never fabricated or silently accepted. ----
        transactions.filter { it.partyGstRegistrationStatus != null }.distinctBy { it.partyLedgerId }.forEach { gt ->
            val status = gt.partyGstRegistrationStatus
            if (status == GstRegistrationStatus.REGISTERED && gt.partyGstin.isBlank()) {
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.ERROR, "REGISTERED_PARTY_MISSING_GSTIN",
                    "Party ledger ${gt.partyLedgerId} is marked REGISTERED but has no GSTIN recorded on its invoice(s)."
                )
            } else if (status == GstRegistrationStatus.UNREGISTERED && gt.partyGstin.isNotBlank()) {
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.WARNING, "UNREGISTERED_PARTY_HAS_GSTIN",
                    "Party ledger ${gt.partyLedgerId} is marked UNREGISTERED but a GSTIN was recorded on its invoice(s) - verify its registration status."
                )
            }
        }

        // ---- GSTIN checksum (B2B/CDNR recipients) ----
        (data.b2b.map { it.recipientGstin } + data.cdnr.map { it.recipientGstin }).distinct().forEach { gstin ->
            if (gstin.isNotBlank() && !GstinChecksum.isValidChecksum(gstin)) {
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.ERROR, "INVALID_GSTIN_CHECKSUM",
                    "Recipient GSTIN '$gstin' fails the GSTN check-digit algorithm - verify it was entered correctly."
                )
            }
        }

        // ---- Place of Supply must be a real, government-published state code ----
        transactions.forEach { gt ->
            if (gt.placeOfSupply.isNotBlank() && gt.placeOfSupply !in Constants.GST_STATE_CODES) {
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.ERROR, "INVALID_PLACE_OF_SUPPLY",
                    "Place of Supply '${gt.placeOfSupply}' on voucher ${gt.voucherId ?: "(GST-only)"} is not a recognized GST state code.",
                    gt.voucherId
                )
            }
        }

        // ---- Tax cross-check: stored CGST/SGST/IGST must reproduce from taxable value x rate ----
        transactions.forEach { gt ->
            if (gt.gstRatePercent > 0.0) {
                val expected = GSTRules.calculateTax(gt.taxableAmount.abs(), gt.gstRatePercent, gt.supplyType)
                val sign = if (gt.taxableAmount.paise < 0) -1L else 1L
                val cgstOk = abs(gt.cgst.paise - sign * expected.cgstAmount.paise) <= TOLERANCE_PAISE
                val sgstOk = abs(gt.sgst.paise - sign * expected.sgstAmount.paise) <= TOLERANCE_PAISE
                val igstOk = abs(gt.igst.paise - sign * expected.igstAmount.paise) <= TOLERANCE_PAISE
                if (!cgstOk || !sgstOk || !igstOk) {
                    issues += Gstr1ValidationIssue(
                        Gstr1ValidationSeverity.ERROR, "TAX_RECOMPUTATION_MISMATCH",
                        "Voucher ${gt.voucherId ?: "(GST-only)"}: stored tax does not reproduce from taxable value × rate " +
                            "(expected CGST ${expected.cgstAmount.paise}p/SGST ${expected.sgstAmount.paise}p/IGST ${expected.igstAmount.paise}p, " +
                            "found ${gt.cgst.paise}p/${gt.sgst.paise}p/${gt.igst.paise}p).",
                        gt.voucherId
                    )
                }
            }
        }

        // ---- Duplicate invoice-number detection (same series, same number, more than once active) ----
        val activeSaleLikeVouchers = allVouchersById.values.filter {
            !it.isCancelled && (it.voucherType == com.example.accounting.domain.accounting.VoucherType.SALES ||
                it.voucherType == com.example.accounting.domain.accounting.VoucherType.CREDIT_NOTE)
        }
        activeSaleLikeVouchers.groupBy { it.voucherType to it.voucherNumber }
            .filter { it.value.size > 1 }
            .forEach { (key, dupes) ->
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.ERROR, "DUPLICATE_INVOICE_NUMBER",
                    "${dupes.size} active ${key.first.displayName} vouchers share the same number '${key.second}' - invoice numbers must be unique within a series.",
                    dupes.first().voucherId
                )
            }

        // ---- Missing invoice number ----
        (data.b2b.flatMap { it.invoices }.map { it.invoiceNumber } +
            data.b2cl.map { it.invoiceNumber } + data.exports.map { it.invoiceNumber })
            .let { numbers -> if (numbers.any { it.isBlank() }) issues += Gstr1ValidationIssue(
                Gstr1ValidationSeverity.ERROR, "MISSING_INVOICE_NUMBER", "One or more invoices have no invoice number recorded."
            ) }

        // ---- CDNR/CDNUR notes must resolve a real original invoice ----
        (data.cdnr.flatMap { it.notes } + data.cdnur).forEach { note ->
            if (note.originalInvoiceNumber.isBlank()) {
                issues += Gstr1ValidationIssue(
                    Gstr1ValidationSeverity.WARNING, "NOTE_MISSING_ORIGINAL_INVOICE",
                    "Note ${note.noteNumber} does not resolve to an original invoice - it cannot be reported against a specific invoice in CDNR/CDNUR.",
                    note.voucherId
                )
            }
        }

        return issues
    }
}
