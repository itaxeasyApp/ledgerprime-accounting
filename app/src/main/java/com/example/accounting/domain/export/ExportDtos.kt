package com.example.accounting.domain.export

import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.invoice.InvoiceStatus
import com.example.accounting.domain.invoice.InvoiceType
import com.example.accounting.domain.reports.AgingBucket
import java.time.LocalDate

/**
 * Export DTOs (Phase 7E) - the public export contract. Deliberately a distinct type from any Room
 * entity or internal domain model, even where the shape is a close 1:1 mirror of an existing
 * report model (Section 4/19: "do not serialize Room entities directly... this protects the
 * database schema from becoming the public export contract"). Every mapper that produces one of
 * these (in `AccountingRepository`) reads already-authoritative data - none of these types, or
 * their mappers, perform any accounting/GST calculation.
 */

data class JournalLineExportDto(
    val ledgerId: String,
    val ledgerName: String,
    val type: DrCr,
    val amountPaise: Long,
    val narration: String,
    val lineOrder: Int
)

data class VoucherExportDto(
    val voucherId: String,
    val voucherNumber: String,
    val voucherType: VoucherType,
    val date: LocalDate,
    val referenceNumber: String,
    val narration: String,
    val totalAmountPaise: Long,
    val isPosted: Boolean,
    val isCancelled: Boolean,
    val referenceVoucherId: String?,
    val journalLines: List<JournalLineExportDto>
)

data class PartyExportDto(
    val partyId: String,
    val ledgerId: String,
    val role: String,
    val entityType: String,
    val displayName: String,
    val contactName: String,
    val creditLimitPaise: Long?,
    val paymentTerms: String,
    val isActive: Boolean
)

data class LedgerExportDto(
    val ledgerId: String,
    val groupId: String,
    val name: String,
    val code: String,
    val openingBalancePaise: Long,
    val openingBalanceType: DrCr,
    val currentBalancePaise: Long,
    val currentBalanceType: DrCr,
    val gstin: String,
    val pan: String,
    val stateCode: String,
    val address: String,
    val isSystem: Boolean,
    val isActive: Boolean
)

/** Mirrors `com.example.accounting.domain.rendering.DocumentData` - see
 * `docs/47_JSON_EXPORT.md` for why this is a thin, distinct wrapper rather than a type alias. */
data class InvoiceExportDto(
    val documentId: String,
    val documentType: String,
    val documentNumber: String,
    val documentDate: LocalDate,
    val dueDate: LocalDate?,
    val sellerName: String,
    val buyerName: String,
    val buyerGstin: String,
    val lineCount: Int,
    val taxableAmountPaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
    val roundOffPaise: Long,
    val grandTotalPaise: Long,
    val isPosted: Boolean,
    val accountingVoucherNumber: String?
)

data class TrialBalanceRowExportDto(
    val ledgerId: String,
    val ledgerName: String,
    val groupId: String,
    val groupName: String,
    val primaryGroup: PrimaryGroup,
    val openingDebitPaise: Long,
    val openingCreditPaise: Long,
    val transactionDebitPaise: Long,
    val transactionCreditPaise: Long,
    val closingDebitPaise: Long,
    val closingCreditPaise: Long
)

data class TrialBalanceExportDto(
    val companyName: String,
    val financialYearCode: String,
    val asOfDate: LocalDate,
    val rows: List<TrialBalanceRowExportDto>,
    val totalClosingDebitPaise: Long,
    val totalClosingCreditPaise: Long,
    val isBalanced: Boolean
)

data class ProfitAndLossExportDto(
    val companyName: String,
    val financialYearCode: String,
    val dateRange: String,
    val salesRevenuePaise: Long,
    val directIncomesPaise: Long,
    val purchasesPaise: Long,
    val directExpensesPaise: Long,
    val grossProfitPaise: Long,
    val indirectIncomesPaise: Long,
    val indirectExpensesPaise: Long,
    val netProfitPaise: Long,
    val isInventoryAware: Boolean
)

data class BalanceSheetExportDto(
    val companyName: String,
    val financialYearCode: String,
    val asOfDate: LocalDate,
    val totalLiabilitiesPaise: Long,
    val totalAssetsPaise: Long,
    val isBalanced: Boolean,
    val capitalAccountsPaise: Long,
    val loansLiabilitiesPaise: Long,
    val currentLiabilitiesPaise: Long,
    val fixedAssetsPaise: Long,
    val currentAssetsPaise: Long,
    val sundryDebtorsPaise: Long,
    val bankAccountsPaise: Long,
    val cashInHandPaise: Long,
    val stockInHandPaise: Long
)

data class OutstandingRowExportDto(
    val invoiceId: String,
    val invoiceNumber: String?,
    val invoiceType: InvoiceType,
    val partyId: String,
    val partyName: String,
    val voucherNumber: String,
    val date: LocalDate,
    val dueDate: LocalDate?,
    val totalAmountPaise: Long,
    val outstandingAmountPaise: Long,
    val status: InvoiceStatus,
    val daysOutstanding: Int,
    val agingBucket: AgingBucket
)

data class OutstandingExportDto(
    val rows: List<OutstandingRowExportDto>,
    val totalOutstandingPaise: Long
)

data class GSTSummaryExportDto(
    val companyName: String,
    val gstin: String,
    val period: String,
    val totalTaxableOutwardPaise: Long,
    val totalTaxOutwardPaise: Long,
    val totalTaxableInwardPaise: Long,
    val totalTaxInwardItcPaise: Long,
    val netTaxPayablePaise: Long,
    val totalCessPaise: Long,
    val netCessPayablePaise: Long
)

/**
 * One [com.example.accounting.domain.taxation.gst.GstTransaction] fact, unmodified - the sole
 * source for both a plain GST-transaction JSON/CSV export and the GSTR JSON serializer (Section
 * 10/11). [isService] is an explicit, currently-always-null extension point (Section 12/29's
 * discipline: "do not invent unavailable statutory fields") - no domain model anywhere today
 * stores a goods-vs-service classification for an HSN/SAC code, so this field exists to be filled
 * in by a future phase rather than guessed from the code string.
 */
data class GSTTransactionExportDto(
    val gstTransactionId: String,
    /** Architecture Checkpoint: nullable - a GST-only company's transaction has no Voucher. */
    val voucherId: String?,
    val voucherType: VoucherType,
    val partyGstin: String,
    val placeOfSupply: String,
    val supplyType: String,
    val hsnSacCode: String,
    val isService: Boolean? = null,
    val taxableAmountPaise: Long,
    val gstRatePercent: Double,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
    val direction: String,
    val lineOrder: Int,
    /** Step 17 - the supplier's own invoice number/date from the Purchase GST fact (see
     * [com.example.accounting.domain.taxation.gst.GstTransaction.supplierDocumentNumber]); `null` = NOT_RECORDED,
     * and always `null` for sales, notes and every pre-Step-13 row. [supplierDocumentDate] is ISO-8601. The supplier
     * GSTIN is the existing [partyGstin]. */
    val supplierDocumentNumber: String? = null,
    val supplierDocumentDate: String? = null
)
