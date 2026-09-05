package com.example.accounting.domain.scanning

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * A successfully-parsed GST e-invoice QR payload (per the GSTN e-invoice/IRP signed-QR JSON
 * shape) - every field is exactly what the scanned code carried, never guessed/completed. Any
 * field the QR omitted stays blank; callers decide what (if anything) to prefill from a blank
 * value, this parser never fabricates one.
 */
data class GstEInvoiceQrData(
    val sellerGstin: String = "",
    val docNo: String = "",
    val docDate: String = "",
    val totalInvoiceValue: String = "",
    val irn: String = ""
)

/**
 * 13-point correctness pass, item 4 (Purchase Bill QR/e-invoice parsing) - a real e-invoice/IRP QR
 * code carries a JSON payload (keys vary slightly by generator: `SellerGstin`/`Seller Gstin`,
 * `DocNo`/`Docno`, `DocDt`/`Docdt`, `TotInvVal`/`Totinvval`, `Irn`), not a bare string. Pure
 * Kotlin, no Android/network dependency (mirrors [com.example.accounting.domain.profile.PinCodeLookupAdapter]'s
 * separation) - [parse] never throws, returning `null` for anything that isn't a JSON object with
 * at least one recognizable e-invoice field, so a caller can always fall back to treating the
 * scanned value as a plain barcode (the pre-existing behavior, byte-for-byte unchanged for that
 * case).
 */
object GstEInvoiceQrParser {

    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    private fun firstOf(map: Map<String, Any?>, vararg keys: String): String {
        val lowered = map.mapKeys { it.key.lowercase().replace(" ", "") }
        for (key in keys) {
            val value = lowered[key.lowercase().replace(" ", "")]
            if (value != null) return value.toString()
        }
        return ""
    }

    fun parse(scanned: String): GstEInvoiceQrData? {
        val trimmed = scanned.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val map = try {
            mapAdapter.fromJson(trimmed)
        } catch (e: Exception) {
            null
        } ?: return null

        val data = GstEInvoiceQrData(
            sellerGstin = firstOf(map, "SellerGstin", "Seller Gstin", "Gstin"),
            docNo = firstOf(map, "DocNo", "Docno", "InvoiceNo"),
            docDate = firstOf(map, "DocDt", "Docdt", "InvoiceDate"),
            totalInvoiceValue = firstOf(map, "TotInvVal", "Totinvval", "TotalValue"),
            irn = firstOf(map, "Irn")
        )
        val hasAnyRecognizedField = data.sellerGstin.isNotBlank() || data.docNo.isNotBlank() ||
            data.totalInvoiceValue.isNotBlank() || data.irn.isNotBlank()
        return if (hasAnyRecognizedField) data else null
    }
}
