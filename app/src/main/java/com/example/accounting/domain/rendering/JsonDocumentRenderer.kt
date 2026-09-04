package com.example.accounting.domain.rendering

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Renders [DocumentData] to a versioned JSON representation (Phase 7D, Section 18) - a plain,
 * deterministic tree of the document's own already-authoritative fields. Deliberately does not
 * expose internal database structure (no `voucherId`/`companyId`/internal row ids beyond what a
 * consumer legitimately needs) and never recomputes anything - every value here is read straight
 * off [DocumentData]. [SCHEMA_VERSION] is bumped whenever the shape changes; old consumers can key
 * off it rather than the JSON breaking silently.
 *
 * Pure Kotlin - no Android/Compose/PDF dependency, fully unit-testable, matching
 * [com.example.accounting.domain.rendering.DocumentRenderer]'s render-only boundary.
 */
object JsonDocumentRenderer : DocumentRenderer<String> {
    const val SCHEMA_VERSION = 1

    private val moshi: Moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val adapter = moshi.adapter<Map<String, Any?>>(mapType)

    override fun render(data: DocumentData, template: DocumentTemplate): String = adapter.toJson(toTree(data))

    /** Exposed separately from [render] so a caller (or a test) can inspect the structured tree
     * without re-parsing the JSON string it produces. */
    fun toTree(data: DocumentData): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "documentType" to data.documentType.name,
        "documentNumber" to data.documentNumber,
        "date" to data.documentDate.toString(),
        "dueDate" to data.dueDate?.toString(),
        "isPosted" to data.isPosted,
        "accountingVoucherNumber" to data.accountingVoucherNumber,
        "seller" to partyTree(data.seller),
        "buyer" to partyTree(data.buyer),
        "items" to data.items.map { line ->
            linkedMapOf(
                "itemId" to line.itemId,
                "description" to line.description,
                "hsnSacCode" to line.hsnSacCode,
                "quantity" to line.quantity?.let { it.rawValue / 1000.0 },
                "unit" to line.unit,
                "ratePaise" to line.rate.paise,
                "discountPaise" to line.discount.paise,
                "taxableAmountPaise" to line.taxableAmount.paise,
                "gstRatePercent" to line.gstRatePercent,
                "cgstPaise" to line.cgst.paise,
                "sgstPaise" to line.sgst.paise,
                "igstPaise" to line.igst.paise,
                "cessPaise" to line.cess.paise,
                "lineTotalPaise" to line.lineTotal.paise
            )
        },
        "tax" to linkedMapOf(
            "taxableAmountPaise" to data.totals.taxableAmount.paise,
            "cgstPaise" to data.totals.cgst.paise,
            "sgstPaise" to data.totals.sgst.paise,
            "igstPaise" to data.totals.igst.paise,
            "cessPaise" to data.totals.cess.paise
        ),
        "totals" to linkedMapOf(
            "taxableAmountPaise" to data.totals.taxableAmount.paise,
            "roundOffPaise" to data.totals.roundOff.paise,
            "grandTotalPaise" to data.totals.grandTotal.paise
        ),
        "paymentInformation" to linkedMapOf(
            "bankName" to data.paymentInformation.bankName,
            "bankAccountNumber" to data.paymentInformation.bankAccountNumber,
            "bankIfsc" to data.paymentInformation.bankIfsc,
            "bankBranch" to data.paymentInformation.bankBranch,
            "upiId" to data.paymentInformation.upiId
        ),
        "references" to linkedMapOf(
            "referenceDocumentId" to data.references.referenceDocumentId,
            "referenceDocumentNumber" to data.references.referenceDocumentNumber,
            "referenceDocumentDate" to data.references.referenceDocumentDate?.toString()
        ),
        "terms" to data.terms
    )

    private fun partyTree(party: DocumentPartySnapshot): Map<String, Any?> = linkedMapOf(
        "name" to party.name,
        "address" to party.address,
        "gstin" to party.gstin,
        "pan" to party.pan,
        "phone" to party.phone,
        "email" to party.email,
        "stateCode" to party.stateCode,
        "stateName" to party.stateName
    )
}
