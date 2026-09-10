package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.document.DocumentType
import com.example.accounting.domain.rendering.DocumentBrandingSnapshot
import com.example.accounting.domain.rendering.DocumentData
import com.example.accounting.domain.rendering.DocumentLineData
import com.example.accounting.domain.rendering.DocumentPartySnapshot
import com.example.accounting.domain.rendering.DocumentPaymentInfo
import com.example.accounting.domain.rendering.DocumentReferenceInfo
import com.example.accounting.domain.rendering.DocumentTotals
import com.example.accounting.domain.rendering.IndianCurrencyWords
import com.example.accounting.domain.rendering.InvoiceSummaryCalculator
import com.example.accounting.domain.rendering.InvoiceTemplatePresets
import com.example.accounting.domain.rendering.InvoiceTemplateStyle
import com.example.accounting.domain.rendering.TaxColumnMode
import com.example.accounting.domain.rendering.TaxColumnSelector
import com.example.accounting.domain.rendering.TemplateConfigSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * "5 Invoice PDF Templates" (separate task from the GST engine work) - pure, Android-independent
 * coverage of everything the PDF renderer/preview depend on: amount-in-words, which tax columns to
 * print, the 5 preset configs being genuinely distinct, and the summary totals aggregation never
 * recalculating GST (only summing/formatting what [DocumentData] already carries).
 */
class InvoiceTemplateTestSuite {

    // ---------------- IndianCurrencyWords ----------------

    @Test
    fun words_simpleAmount() {
        assertEquals("Rupees One Thousand One Hundred Eighty Only", IndianCurrencyWords.toWords(Money.fromRupees(1180L)))
    }

    @Test
    fun words_withPaise() {
        assertEquals(
            "Rupees Ninety Nine and Fifty Paise Only",
            IndianCurrencyWords.toWords(Money.fromPaise(99_50L))
        )
    }

    @Test
    fun words_zero() {
        assertEquals("Rupees Zero Only", IndianCurrencyWords.toWords(Money.ZERO))
    }

    @Test
    fun words_lakhAndCrore() {
        // 1,23,45,678 -> One Crore Twenty Three Lakh Forty Five Thousand Six Hundred Seventy Eight
        assertEquals(
            "Rupees One Crore Twenty Three Lakh Forty Five Thousand Six Hundred Seventy Eight Only",
            IndianCurrencyWords.toWords(Money.fromRupees(1_23_45_678L))
        )
    }

    @Test
    fun words_negativeAmount_prefixesMinus() {
        assertTrue(IndianCurrencyWords.toWords(Money.fromRupees(-500L)).startsWith("Minus Rupees"))
    }

    // ---------------- TaxColumnSelector ----------------

    @Test
    fun taxColumn_intraState_selectsCgstSgst() {
        val totals = DocumentTotals(
            taxableAmount = Money.fromRupees(1000L), cgst = Money.fromRupees(90L), sgst = Money.fromRupees(90L),
            igst = Money.ZERO, cess = Money.ZERO, roundOff = Money.ZERO, grandTotal = Money.fromRupees(1180L)
        )
        assertEquals(TaxColumnMode.CGST_SGST, TaxColumnSelector.resolve(totals))
    }

    @Test
    fun taxColumn_interState_selectsIgst() {
        val totals = DocumentTotals(
            taxableAmount = Money.fromRupees(1000L), cgst = Money.ZERO, sgst = Money.ZERO,
            igst = Money.fromRupees(180L), cess = Money.ZERO, roundOff = Money.ZERO, grandTotal = Money.fromRupees(1180L)
        )
        assertEquals(TaxColumnMode.IGST, TaxColumnSelector.resolve(totals))
    }

    @Test
    fun taxColumn_zeroRated_selectsNone() {
        val totals = DocumentTotals(
            taxableAmount = Money.fromRupees(1000L), cgst = Money.ZERO, sgst = Money.ZERO,
            igst = Money.ZERO, cess = Money.ZERO, roundOff = Money.ZERO, grandTotal = Money.fromRupees(1000L)
        )
        assertEquals(TaxColumnMode.NONE, TaxColumnSelector.resolve(totals))
    }

    // ---------------- InvoiceTemplatePresets ----------------

    @Test
    fun presets_all5StylesHaveDistinctPrimaryColors() {
        val colors = InvoiceTemplatePresets.ALL_STYLES.map { InvoiceTemplatePresets.seedConfigFor(it).colors.primary }
        assertEquals(5, InvoiceTemplatePresets.ALL_STYLES.size)
        assertEquals("Every style's primary color must be distinct - otherwise they are not visually different", 5, colors.toSet().size)
    }

    @Test
    fun presets_all5StylesHaveDistinctFontFamilies() {
        val fonts = InvoiceTemplatePresets.ALL_STYLES.map { InvoiceTemplatePresets.seedConfigFor(it).typography.fontFamily }
        assertEquals(5, fonts.toSet().size)
    }

    @Test
    fun presets_configRoundTripsThroughSerializer() {
        InvoiceTemplatePresets.ALL_STYLES.forEach { style ->
            val config = InvoiceTemplatePresets.seedConfigFor(style)
            val json = TemplateConfigSerializer.toJson(config)
            val restored = TemplateConfigSerializer.fromJson(json)
            assertEquals(style, restored.style)
            assertEquals(config.colors.primary, restored.colors.primary)
        }
    }

    @Test
    fun presets_eachStyleTagsItselfCorrectly() {
        InvoiceTemplatePresets.ALL_STYLES.forEach { style ->
            assertEquals(style, InvoiceTemplatePresets.seedConfigFor(style).style)
        }
    }

    // ---------------- InvoiceSummaryCalculator ----------------

    private fun sampleDocumentData(cgst: Money, sgst: Money, igst: Money): DocumentData {
        val line1 = DocumentLineData(
            itemId = "ITEM_A", description = "Widget", hsnSacCode = "8471",
            quantity = Quantity.fromLong(2), rate = Money.fromRupees(600L), discount = Money.fromRupees(20L),
            taxableAmount = Money.fromRupees(1000L), gstRatePercent = 18.0,
            cgst = cgst, sgst = sgst, igst = igst, cess = Money.ZERO,
            lineTotal = Money.fromRupees(1000L) + cgst + sgst + igst
        )
        val totals = DocumentTotals(
            taxableAmount = Money.fromRupees(1000L), cgst = cgst, sgst = sgst, igst = igst, cess = Money.ZERO,
            roundOff = Money.ZERO, grandTotal = Money.fromRupees(1000L) + cgst + sgst + igst
        )
        return DocumentData(
            documentId = "V1", companyId = "C1", documentType = DocumentType.SALES_INVOICE,
            documentNumber = "SI-0001", documentDate = LocalDate.of(2026, 1, 1),
            seller = DocumentPartySnapshot(name = "Seller Co"), buyer = DocumentPartySnapshot(name = "Buyer Co"),
            items = listOf(line1), totals = totals, paymentInformation = DocumentPaymentInfo(),
            references = DocumentReferenceInfo(), branding = DocumentBrandingSnapshot(), isPosted = true
        )
    }

    @Test
    fun summary_intraState_aggregatesWithoutRecalculatingGst() {
        val data = sampleDocumentData(cgst = Money.fromRupees(90L), sgst = Money.fromRupees(90L), igst = Money.ZERO)
        val summary = InvoiceSummaryCalculator.from(data)

        assertEquals(2.0, summary.totalQuantity, 0.0001)
        // Item Amount = rate x qty = 600 x 2 = 1200 (gross, before the 20-rupee discount)
        assertEquals(1200_00L, summary.itemAmount.paise)
        assertEquals(20_00L, summary.totalDiscount.paise)
        assertEquals(1000_00L, summary.taxableAmount.paise)
        assertEquals(180_00L, summary.totalGst.paise)
        assertEquals(TaxColumnMode.CGST_SGST, summary.taxColumnMode)
        assertEquals(1180_00L, summary.grandTotal.paise)
        assertEquals(IndianCurrencyWords.toWords(Money.fromRupees(1180L)), summary.amountInWords)
    }

    @Test
    fun summary_interState_selectsIgstColumn() {
        val data = sampleDocumentData(cgst = Money.ZERO, sgst = Money.ZERO, igst = Money.fromRupees(180L))
        val summary = InvoiceSummaryCalculator.from(data)
        assertEquals(TaxColumnMode.IGST, summary.taxColumnMode)
        assertEquals(180_00L, summary.totalGst.paise)
    }

    @Test
    fun classicAndModernBanner_presetsAreNotIdentical() {
        assertNotEquals(
            InvoiceTemplatePresets.seedConfigFor(InvoiceTemplateStyle.CLASSIC),
            InvoiceTemplatePresets.seedConfigFor(InvoiceTemplateStyle.MODERN_BANNER)
        )
    }
}
