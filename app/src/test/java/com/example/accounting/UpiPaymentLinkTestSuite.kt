package com.example.accounting

import com.example.accounting.core.common.Money
import com.example.accounting.domain.banking.UpiPaymentLink
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Get paid via UPI" (Receive Money) - [UpiPaymentLink] builds a real NPCI `upi://pay?...` deep
 * link off the user's own saved VPA, and [com.example.accounting.presentation.components.QrCodeImage]
 * renders it via zxing's [QRCodeWriter]. This suite checks both ends without touching Android
 * Bitmap: the exact query string [UpiPaymentLink.build] produces, and that zxing's own encoder ->
 * decoder round-trip recovers that exact string - i.e. a real UPI app really would read back what
 * this feature intends to say, not merely "some black and white pixels."
 */
class UpiPaymentLinkTestSuite {

    @Test
    fun build_withAmountAndNote_producesExpectedUpiUri() {
        val link = UpiPaymentLink.build(
            payeeVpa = "business@okaxis",
            payeeName = "Acme Traders",
            amount = Money.fromRupees(500L),
            note = "Invoice 123"
        )
        assertEquals("upi://pay?pa=business%40okaxis&pn=Acme+Traders&cu=INR&am=500.00&tn=Invoice+123", link)
    }

    @Test
    fun build_withoutAmountOrNote_omitsThoseParams() {
        val link = UpiPaymentLink.build(payeeVpa = "business@okaxis", payeeName = "Acme Traders")
        assertEquals("upi://pay?pa=business%40okaxis&pn=Acme+Traders&cu=INR", link)
    }

    @Test
    fun build_blankVpa_returnsNull() {
        assertNull(UpiPaymentLink.build(payeeVpa = "", payeeName = "Acme Traders"))
    }

    @Test
    fun build_vpaMissingAtSign_returnsNull() {
        // Real VPAs are always handle@bank - reject obviously-not-a-VPA input rather than
        // encoding a QR no UPI app could ever pay.
        assertNull(UpiPaymentLink.build(payeeVpa = "not-a-vpa", payeeName = "Acme Traders"))
    }

    @Test
    fun encodedQr_decodesBackToTheExactUpiLink() {
        val link = UpiPaymentLink.build(
            payeeVpa = "mukulbedi@paytsm",
            payeeName = "TestCo Accounting",
            amount = Money.fromRupees(500L),
            note = "Invoice 123"
        )!!

        val size = 256
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        val decoded = MultiFormatReader().decode(bitmap).text

        assertEquals(link, decoded)
        assertTrue(decoded.startsWith("upi://pay?"))
        assertTrue(decoded.contains("pa=mukulbedi%40paytsm"))
    }
}
