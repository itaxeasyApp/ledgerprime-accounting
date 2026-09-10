package com.example.accounting.domain.rendering

import com.example.accounting.core.common.Money

/**
 * "Amount in words" for a printed invoice - the Indian numbering system (Lakh/Crore, not
 * Million/Billion), computed purely from [Money.paise] with no rounding beyond what [Money]
 * already carries. Pure/Room/Android-independent so it is directly unit-testable and reusable by
 * both [com.example.accounting.data.rendering.PdfDocumentRenderer] and any Compose preview -
 * never a second, independently-written words converter.
 */
object IndianCurrencyWords {

    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    )
    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    /** e.g. "Rupees One Thousand One Hundred Eighty Only" / "Rupees Ninety Nine and Fifty Paise Only". */
    fun toWords(amount: Money): String {
        val absPaise = kotlin.math.abs(amount.paise)
        val rupees = absPaise / 100L
        val paise = (absPaise % 100L).toInt()
        val sign = if (amount.paise < 0) "Minus " else ""

        val rupeeWords = if (rupees == 0L) "Zero" else convertWholeNumber(rupees)
        val builder = StringBuilder("${sign}Rupees $rupeeWords")
        if (paise > 0) {
            builder.append(" and ").append(convertBelowThousand(paise)).append(" Paise")
        }
        builder.append(" Only")
        return builder.toString()
    }

    /** Indian grouping: ...Crore,XX,Lakh,XX,Thousand,XXX (2-digit groups after the first 3 digits). */
    private fun convertWholeNumber(number: Long): String {
        if (number == 0L) return "Zero"
        var remaining = number
        val parts = mutableListOf<String>()

        val crore = remaining / 1_00_00_000L
        remaining %= 1_00_00_000L
        val lakh = remaining / 1_00_000L
        remaining %= 1_00_000L
        val thousand = remaining / 1_000L
        remaining %= 1_000L
        val belowThousand = remaining

        if (crore > 0) parts += "${convertBelowThousand(crore.toInt())} Crore"
        if (lakh > 0) parts += "${convertBelowThousand(lakh.toInt())} Lakh"
        if (thousand > 0) parts += "${convertBelowThousand(thousand.toInt())} Thousand"
        if (belowThousand > 0) parts += convertBelowThousand(belowThousand.toInt())

        return parts.joinToString(" ")
    }

    private fun convertBelowThousand(number: Int): String {
        if (number == 0) return ""
        val hundred = number / 100
        val rest = number % 100
        val parts = mutableListOf<String>()
        if (hundred > 0) parts += "${ones[hundred]} Hundred"
        if (rest > 0) parts += convertBelowHundred(rest)
        return parts.joinToString(" ")
    }

    private fun convertBelowHundred(number: Int): String {
        if (number < 20) return ones[number]
        val ten = number / 10
        val one = number % 10
        return if (one == 0) tens[ten] else "${tens[ten]} ${ones[one]}"
    }
}
