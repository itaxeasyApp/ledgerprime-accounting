package com.example.accounting.domain.export

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Wraps any export DTO's tree in the versioned envelope (Section 6) - `{"schemaVersion", "exportType",
 * "generatedAt", "companyId", "financialYearId", "applicationVersion", "data": {...}}`. Pure
 * Kotlin, no Android dependency - reuses the same `Moshi + generic Map<String,Any?> adapter`
 * approach `JsonDocumentRenderer` (Phase 7D) already established, so exported JSON stays
 * deterministic (a `LinkedHashMap` preserves the field order written below).
 */
object ExportJsonSerializer {
    private val moshi: Moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    // .serializeNulls() - a nullable field (e.g. GSTTransactionExportDto.isService, an explicit
    // never-fabricated extension point) must appear in the output AS null, never silently vanish
    // the way Moshi's default Map adapter omits null-valued entries. Schema consistency (Section
    // 21) also requires every record to carry the same keys regardless of which are null.
    private val adapter = moshi.adapter<Map<String, Any?>>(mapType).serializeNulls()

    fun serialize(metadata: ExportMetadata, dataTree: Map<String, Any?>): String = adapter.toJson(envelope(metadata, dataTree))

    fun envelope(metadata: ExportMetadata, dataTree: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to metadata.schemaVersion,
        "exportType" to metadata.exportType.name,
        "generatedAt" to metadata.generatedAt,
        "companyId" to metadata.companyId,
        "financialYearId" to metadata.financialYearId,
        "applicationVersion" to metadata.applicationVersion,
        "data" to dataTree
    )
}

// ==================== Per-DTO trees (deterministic field order, paise-exact) ====================

fun VoucherExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "voucherId" to voucherId, "voucherNumber" to voucherNumber, "voucherType" to voucherType.name,
    "date" to date.toString(), "referenceNumber" to referenceNumber, "narration" to narration,
    "totalAmountPaise" to totalAmountPaise, "isPosted" to isPosted, "isCancelled" to isCancelled,
    "referenceVoucherId" to referenceVoucherId,
    "journalLines" to journalLines.map {
        linkedMapOf(
            "ledgerId" to it.ledgerId, "ledgerName" to it.ledgerName, "type" to it.type.name,
            "amountPaise" to it.amountPaise, "narration" to it.narration, "lineOrder" to it.lineOrder
        )
    }
)

fun PartyExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "partyId" to partyId, "ledgerId" to ledgerId, "role" to role, "entityType" to entityType,
    "displayName" to displayName, "contactName" to contactName, "creditLimitPaise" to creditLimitPaise,
    "paymentTerms" to paymentTerms, "isActive" to isActive
)

fun LedgerExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "ledgerId" to ledgerId, "groupId" to groupId, "name" to name, "code" to code,
    "openingBalancePaise" to openingBalancePaise, "openingBalanceType" to openingBalanceType.name,
    "currentBalancePaise" to currentBalancePaise, "currentBalanceType" to currentBalanceType.name,
    "gstin" to gstin, "pan" to pan, "stateCode" to stateCode, "address" to address,
    "isSystem" to isSystem, "isActive" to isActive
)

fun InvoiceExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "documentId" to documentId, "documentType" to documentType, "documentNumber" to documentNumber,
    "documentDate" to documentDate.toString(), "dueDate" to dueDate?.toString(),
    "sellerName" to sellerName, "buyerName" to buyerName, "buyerGstin" to buyerGstin, "lineCount" to lineCount,
    "taxableAmountPaise" to taxableAmountPaise, "cgstPaise" to cgstPaise, "sgstPaise" to sgstPaise,
    "igstPaise" to igstPaise, "cessPaise" to cessPaise, "roundOffPaise" to roundOffPaise,
    "grandTotalPaise" to grandTotalPaise, "isPosted" to isPosted, "accountingVoucherNumber" to accountingVoucherNumber
)

fun TrialBalanceExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "companyName" to companyName, "financialYearCode" to financialYearCode, "asOfDate" to asOfDate.toString(),
    "totalClosingDebitPaise" to totalClosingDebitPaise, "totalClosingCreditPaise" to totalClosingCreditPaise,
    "isBalanced" to isBalanced,
    "rows" to rows.map {
        linkedMapOf(
            "ledgerId" to it.ledgerId, "ledgerName" to it.ledgerName, "groupId" to it.groupId, "groupName" to it.groupName,
            "primaryGroup" to it.primaryGroup.name, "openingDebitPaise" to it.openingDebitPaise,
            "openingCreditPaise" to it.openingCreditPaise, "transactionDebitPaise" to it.transactionDebitPaise,
            "transactionCreditPaise" to it.transactionCreditPaise, "closingDebitPaise" to it.closingDebitPaise,
            "closingCreditPaise" to it.closingCreditPaise
        )
    }
)

fun ProfitAndLossExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "companyName" to companyName, "financialYearCode" to financialYearCode, "dateRange" to dateRange,
    "salesRevenuePaise" to salesRevenuePaise, "directIncomesPaise" to directIncomesPaise,
    "purchasesPaise" to purchasesPaise, "directExpensesPaise" to directExpensesPaise,
    "grossProfitPaise" to grossProfitPaise, "indirectIncomesPaise" to indirectIncomesPaise,
    "indirectExpensesPaise" to indirectExpensesPaise, "netProfitPaise" to netProfitPaise,
    "isInventoryAware" to isInventoryAware
)

fun BalanceSheetExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "companyName" to companyName, "financialYearCode" to financialYearCode, "asOfDate" to asOfDate.toString(),
    "totalLiabilitiesPaise" to totalLiabilitiesPaise, "totalAssetsPaise" to totalAssetsPaise, "isBalanced" to isBalanced,
    "capitalAccountsPaise" to capitalAccountsPaise, "loansLiabilitiesPaise" to loansLiabilitiesPaise,
    "currentLiabilitiesPaise" to currentLiabilitiesPaise, "fixedAssetsPaise" to fixedAssetsPaise,
    "currentAssetsPaise" to currentAssetsPaise, "sundryDebtorsPaise" to sundryDebtorsPaise,
    "bankAccountsPaise" to bankAccountsPaise, "cashInHandPaise" to cashInHandPaise, "stockInHandPaise" to stockInHandPaise
)

fun OutstandingExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "totalOutstandingPaise" to totalOutstandingPaise,
    "rows" to rows.map {
        linkedMapOf(
            "invoiceId" to it.invoiceId, "invoiceNumber" to it.invoiceNumber, "invoiceType" to it.invoiceType.name,
            "partyId" to it.partyId, "partyName" to it.partyName, "voucherNumber" to it.voucherNumber,
            "date" to it.date.toString(), "dueDate" to it.dueDate?.toString(), "totalAmountPaise" to it.totalAmountPaise,
            "outstandingAmountPaise" to it.outstandingAmountPaise, "status" to it.status.name,
            "daysOutstanding" to it.daysOutstanding, "agingBucket" to it.agingBucket.name
        )
    }
)

fun GSTSummaryExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "companyName" to companyName, "gstin" to gstin, "period" to period,
    "totalTaxableOutwardPaise" to totalTaxableOutwardPaise, "totalTaxOutwardPaise" to totalTaxOutwardPaise,
    "totalTaxableInwardPaise" to totalTaxableInwardPaise, "totalTaxInwardItcPaise" to totalTaxInwardItcPaise,
    "netTaxPayablePaise" to netTaxPayablePaise, "totalCessPaise" to totalCessPaise, "netCessPayablePaise" to netCessPayablePaise
)

fun GSTTransactionExportDto.toTree(): Map<String, Any?> = linkedMapOf(
    "gstTransactionId" to gstTransactionId, "voucherId" to voucherId, "voucherType" to voucherType.name,
    "partyGstin" to partyGstin, "placeOfSupply" to placeOfSupply, "supplyType" to supplyType,
    "hsnSacCode" to hsnSacCode, "isService" to isService, "taxableAmountPaise" to taxableAmountPaise,
    "gstRatePercent" to gstRatePercent, "cgstPaise" to cgstPaise, "sgstPaise" to sgstPaise, "igstPaise" to igstPaise,
    "cessPaise" to cessPaise, "direction" to direction, "lineOrder" to lineOrder,
    "supplierDocumentNumber" to supplierDocumentNumber, "supplierDocumentDate" to supplierDocumentDate
)

fun List<GSTTransactionExportDto>.toTree(): Map<String, Any?> = linkedMapOf(
    "count" to size,
    "transactions" to map { it.toTree() }
)
