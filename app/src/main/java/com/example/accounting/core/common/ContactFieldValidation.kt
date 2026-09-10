package com.example.accounting.core.common

/**
 * Play Store readiness correction (docs/CORRECTIONS_LOG.md, 2026-09-09) - phone/email/PAN had zero
 * format validation anywhere in the app (unlike GSTIN, which already had
 * [com.example.accounting.domain.taxation.gst.GSTRules.isValidGSTIN]). One shared, pure validator
 * for all three, reused by every form that collects them (Add Customer/Supplier, Business/
 * Individual Profile, Create Company/Ledger) - never a second, divergent regex per screen.
 *
 * Every check is **format-only, UI-hint level** - it says whether a string is *shaped* like a
 * valid phone/email/PAN, never whether it's real (no network lookup, no OTP, no verification
 * email). A blank value is always treated as valid here (these fields are optional almost
 * everywhere they appear); callers that need a field to be required check `isNotBlank()`
 * separately, same convention [GSTRules.isValidGSTIN] already uses.
 */
object ContactFieldValidation {

    private val panRegex = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$")
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    // Indian mobile numbers: 10 digits, first digit 6-9 (TRAI numbering plan) - an optional +91/91/0
    // prefix is stripped before checking, never required.
    private val mobileRegex = Regex("^[6-9][0-9]{9}$")

    fun isValidPan(pan: String): Boolean = pan.isBlank() || panRegex.matches(pan.trim().uppercase())

    fun isValidEmail(email: String): Boolean = email.isBlank() || emailRegex.matches(email.trim())

    fun isValidIndianMobile(phone: String): Boolean {
        if (phone.isBlank()) return true
        val digitsOnly = phone.trim().removePrefix("+").let {
            when {
                it.startsWith("91") && it.length == 12 -> it.removePrefix("91")
                it.startsWith("0") && it.length == 11 -> it.removePrefix("0")
                else -> it
            }
        }
        return mobileRegex.matches(digitsOnly)
    }

    /** A GSTIN's own structure ([GSTRules.isValidGSTIN]: 2-digit state code + 10-char PAN + 1-char
     * entity number + literal 'Z' + 1-char checksum) already contains the holder's real PAN -
     * characters 3-12. Real gap fix (docs/CORRECTIONS_LOG.md, user request: "Extract Pan No from
     * GSTIN no after state code 10 alpha numeric are pan") - lets a caller auto-fill a still-blank
     * PAN field the moment a GSTIN is entered, never a second/guessed PAN. Returns null (never a
     * malformed guess) unless the extracted 10 characters are themselves shaped like a real PAN. */
    fun extractPanFromGstin(gstin: String): String? {
        val trimmed = gstin.trim().uppercase()
        if (trimmed.length < 12) return null
        val candidate = trimmed.substring(2, 12)
        return if (panRegex.matches(candidate)) candidate else null
    }

    /** PAN's own 4th character encodes the holder type by law (P=Individual, H=HUF, C=Company,
     * F=Firm/LLP, A=AOP, T=Trust, B=BOI, L=Local Authority, G=Government, J=Artificial Judicial
     * Person) - real gap fix (docs/CORRECTIONS_LOG.md, user request). `true` for a blank PAN (this
     * stays a format-only hint on an optional field, same convention every other check here uses);
     * `false` only for a well-formed PAN whose 4th character contradicts [expected]. */
    fun isValidPanForHolderType(pan: String, expected: Char): Boolean {
        if (pan.isBlank()) return true
        if (!isValidPan(pan)) return true // a malformed PAN is already flagged by isValidPan itself
        return pan.trim().uppercase()[3] == expected.uppercaseChar()
    }

    // A 5th-character (surname/entity-name letter) check was added here, then explicitly retracted
    // by the user after live testing (docs/CORRECTIONS_LOG.md) - too unreliable across real naming
    // conventions to enforce as a hard validation. Only the 4th-character holder-type check above
    // remains. Don't re-add a 5th-character check without the user asking for it again.
}
