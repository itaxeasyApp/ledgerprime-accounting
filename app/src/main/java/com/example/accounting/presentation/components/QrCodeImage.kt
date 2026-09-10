package com.example.accounting.presentation.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a real, locally-encoded QR code (zxing's [QRCodeWriter] - the same zxing
 * dependency this app already uses for barcode *scanning*, used here in the encode direction).
 * No network call, no placeholder image, no fake pixels - whatever string [content] is, this draws
 * exactly what a real UPI app would decode back out of it.
 */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier, sizePx: Int = 512) {
    val bitmap = remember(content, sizePx) { encodeQrBitmap(content, sizePx) }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR code", modifier = modifier)
    }
}

/**
 * Step 8 audit fix - the generated-barcode result dialog (MainAppScreen) content: a real,
 * scannable [QrCodeImage] plus the raw payload text underneath, so a "Generate barcode" tap on a
 * stock item is finally visible (it used to silently store a [com.example.accounting.domain.qrbarcode.BarcodeGenerationResult]
 * in state that nothing ever read). Kept here, not inline in MainAppScreen, purely so this file's
 * own imports (Alignment/Spacer/MaterialTheme/dp) cover it without adding those to that much
 * larger file.
 */
@Composable
fun GeneratedBarcodeContent(rawValue: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
        QrCodeImage(content = rawValue, modifier = Modifier.size(180.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(rawValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun encodeQrBitmap(content: String, sizePx: Int): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
