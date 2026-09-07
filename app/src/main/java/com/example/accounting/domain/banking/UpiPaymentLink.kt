package com.example.accounting.domain.banking

import com.example.accounting.core.common.Money
import java.net.URLEncoder

/**
 * Builds a standard NPCI UPI deep link (`upi://pay?...`) from the user's own real, saved payee
 * details - the same URI scheme every UPI app (GPay, PhonePe, Paytm, BHIM, etc.) generates for its
 * own merchant QR codes, so any of them can scan and complete a real payment against it. There is
 * no payment gateway, sandbox, or simulated response involved: this is pure string construction
 * off values the user typed into their own [BankUpiProfile]/business profile.
 *
 * Deliberately one-way. Nothing in this app can detect that a payment against this link actually
 * arrived (that requires a bank/PSP webhook integration this app does not have) - the user still
 * records the real Receipt voucher by hand once the money has landed, exactly as before this
 * feature existed.
 */
object UpiPaymentLink {

    /**
     * @param payeeVpa the payee's real UPI ID/VPA (e.g. "business@okaxis"), required.
     * @param payeeName shown by the paying app as the recipient name; omitted from the link if blank.
     * @param amount pre-fills the amount in the paying app; omitted (customer enters it) if null or not positive.
     * @param note pre-fills a transaction note; omitted if blank.
     */
    fun build(payeeVpa: String, payeeName: String = "", amount: Money? = null, note: String = ""): String? {
        val vpa = payeeVpa.trim()
        if (vpa.isBlank() || !vpa.contains("@")) return null
        val params = LinkedHashMap<String, String>()
        params["pa"] = vpa
        if (payeeName.isNotBlank()) params["pn"] = payeeName.trim()
        params["cu"] = "INR"
        if (amount != null && amount.isPositive) params["am"] = amount.formatPlain()
        if (note.isNotBlank()) params["tn"] = note.trim().take(50)
        val query = params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return "upi://pay?$query"
    }
}
