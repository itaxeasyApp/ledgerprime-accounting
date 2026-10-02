package com.example.accounting.domain.export

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Builds a GSTR-shaped JSON document from already-persisted [GSTTransactionExportDto] facts
 * (Section 10/11) - never reconstructs GST from ledger names, never invents a statutory field this
 * domain doesn't actually have. `hsnSacCode` is reported as-is; whether it's an HSN (goods) or SAC
 * (services) code is NOT determined here - `isService` stays whatever the DTO already carries
 * (always `null` today, since no domain model anywhere stores that classification - Section 12/29's
 * "create an extension point rather than fabricating it").
 *
 * Grouped by `direction` (OUTPUT -> outward supplies / GSTR-1-shaped section, INPUT -> inward
 * supplies / ITC / GSTR-3B-shaped section) since that split is the one piece of real GSTR structure
 * this project's existing `GstTransaction.direction` fact already supports; nothing beyond that is
 * fabricated.
 */
object GstrJsonSerializer {
    private val moshi: Moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    // .serializeNulls() - `isService` must appear as null, never silently omitted (see the
    // identical reasoning in ExportJsonSerializer).
    private val adapter = moshi.adapter<Map<String, Any?>>(mapType).serializeNulls()

    fun serialize(metadata: ExportMetadata, transactions: List<GSTTransactionExportDto>): String {
        val outward = transactions.filter { it.direction == "OUTPUT" }
        val inward = transactions.filter { it.direction == "INPUT" }
        val dataTree = linkedMapOf<String, Any?>(
            "gstin" to (transactions.firstOrNull { it.partyGstin.isNotBlank() }?.partyGstin ?: ""),
            "outwardSupplies" to outward.map { it.toGstrLineTree() },
            "inwardSupplies" to inward.map { it.toGstrLineTree() },
            "totals" to linkedMapOf(
                "totalTaxableOutwardPaise" to outward.sumOf { it.taxableAmountPaise },
                "totalTaxOutwardPaise" to outward.sumOf { it.cgstPaise + it.sgstPaise + it.igstPaise },
                "totalCessOutwardPaise" to outward.sumOf { it.cessPaise },
                "totalTaxableInwardPaise" to inward.sumOf { it.taxableAmountPaise },
                "totalTaxInwardPaise" to inward.sumOf { it.cgstPaise + it.sgstPaise + it.igstPaise },
                "totalCessInwardPaise" to inward.sumOf { it.cessPaise }
            )
        )
        return adapter.toJson(ExportJsonSerializer.envelope(metadata, dataTree))
    }

    private fun GSTTransactionExportDto.toGstrLineTree(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "voucherId" to voucherId, "voucherType" to voucherType.name, "partyGstin" to partyGstin,
        "placeOfSupply" to placeOfSupply, "supplyType" to supplyType, "hsnSacCode" to hsnSacCode,
        "isService" to isService, "taxableAmountPaise" to taxableAmountPaise, "gstRatePercent" to gstRatePercent,
        "cgstPaise" to cgstPaise, "sgstPaise" to sgstPaise, "igstPaise" to igstPaise, "cessPaise" to cessPaise
    ).also {
        // Step 17: the supplier's document identity belongs to inward (purchase) lines only - an outward line
        // never carries the keys, so a sale or a note can never be read as having a supplier document.
        if (direction == "INPUT") {
            it["supplierDocumentNumber"] = supplierDocumentNumber
            it["supplierDocumentDate"] = supplierDocumentDate
        }
    }
}
