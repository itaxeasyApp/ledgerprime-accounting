package com.example.accounting.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.trading.OutstandingInvoice
import com.example.accounting.presentation.viewmodel.AccountingViewModel
import java.time.LocalDate

/** SERVICE-mode audit fix - the user-facing label for a [VoucherType] in the voucher-creation
 * workflow. Identical to [VoucherType.displayName] for every type/company except SALES/PURCHASE
 * on a SERVICE company, which read as "Income"/"Expenditure" instead - this app's canonical
 * [VoucherType] enum is never duplicated or renamed to achieve this, only its on-screen label. */
private fun voucherNatureLabel(type: VoucherType, isServiceCompany: Boolean): String = when {
    isServiceCompany && type == VoucherType.SALES -> "Income"
    isServiceCompany && type == VoucherType.PURCHASE -> "Expenditure"
    else -> type.displayName
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVoucherDialog(
    ledgers: List<Ledger>,
    /** Architecture correction (real Group hierarchy) - lets ledger classification below correctly
     * recognize a ledger filed under a company-created User Group nested under a System group
     * (e.g. Bank Accounts), not just one filed directly under the System group itself. Defaulted
     * to empty so every existing caller/preview keeps compiling - the classification functions
     * below all fall back to their original direct-groupId-prefix check regardless. */
    groups: List<com.example.accounting.domain.accounting.AccountGroup> = emptyList(),
    stockItems: List<StockItem> = emptyList(),
    vouchers: List<Voucher> = emptyList(),
    outstandingInvoices: List<OutstandingInvoice> = emptyList(),
    companyStateCode: String = "",
    /** D1a (Company Mode + Account-Only Sale/Purchase) - the same single gating point
     * ([com.example.accounting.presentation.viewmodel.isInventoryEnabled]) every other Items-
     * related call site reads. Defaults to `true` so any caller that doesn't yet pass it explicitly
     * keeps today's item-driven Sale/Purchase behavior unchanged. */
    isInventoryEnabled: Boolean = true,
    /** Accounting-flow audit fix - whether this company's [com.example.accounting.domain.company.GstOperatingMode]
     * says GST applies at all, independent of [isInventoryEnabled]. Only meaningful for the
     * Account-Only Sale/Purchase form (the item-driven form always carries GST per line
     * regardless of this flag). */
    gstApplicable: Boolean = false,
    defaultVoucherType: VoucherType = VoucherType.PAYMENT,
    /** SERVICE-mode audit fix - when this company's [com.example.accounting.domain.company.BusinessType]
     * is SERVICE, the Sale/Purchase workflow is presented to the user as Income/Expenditure (this
     * company has no Trading Account/Gross Profit concept - see [com.example.accounting.domain.company.BusinessType]).
     * Purely a display relabel: still posts the same canonical [VoucherType.SALES]/[VoucherType.PURCHASE]
     * through the same unmodified posting engine - never a third voucher type, never inferred from
     * direction. */
    isServiceCompany: Boolean = false,
    /** When true, this dialog was opened from a screen already dedicated to one voucher type (e.g.
     * Sales' "New Sale") - the "Voucher Nature" type switcher is hidden so the user can't wander
     * into a different voucher type by accident. Left false for genuinely generic entry points
     * (e.g. Day Book's FAB) where picking a type is the point. */
    lockedType: Boolean = false,
    /** Architecture correction (Voucher Correct workflow) - non-null only for a Contra/Journal
     * "Correct Voucher" repost (see [com.example.accounting.presentation.components.VOUCHER_CORRECTION_ELIGIBLE_TYPES]);
     * seeds the debit/credit ledgers, amount, reference number, and narration from the
     * already-cancelled original so the user only has to fix what was wrong, not retype everything.
     * `null` (every other caller) leaves the form's normal empty-state defaults untouched. */
    prefillFrom: Voucher? = null,
    /** Extend-correction-to-all-types fix - (gstRatePercent, hsnSacCode) for a Sale/Purchase
     * `prefillFrom`, sourced by the caller from the cancelled original's own GstTransaction (never
     * carried on `Voucher`/`JournalItem` itself - see `AccountingViewModel.correctVoucher`'s doc
     * comment). `null` whenever `prefillFrom` isn't a GST-bearing account-only Sale/Purchase. */
    prefillGstDetail: Pair<Double, String>? = null,
    onDismiss: () -> Unit,
    onAddNewParty: (PartyRole) -> Unit = {},
    onAddNewBankLedger: () -> Unit = {},
    /** Follow-up to the user's Bug #3 device-testing feedback - lets Sale/Purchase creation add a
     * missing Sales/Purchase Account ledger inline (mirrors [onAddNewBankLedger] exactly) instead
     * of a hard-blocking "create one from Ledgers" dead end. `true` = Sales, `false` = Purchase. */
    onAddNewTradeLedger: (Boolean) -> Unit = {},
    onPostQuickVoucher: (VoucherType, LocalDate, String, String, Money, String, String) -> Unit,
    /** Save this Contra/Journal/Receipt/Payment as a [com.example.accounting.application.voucher.VoucherDraft]
     * instead of posting - Phase 7J-B.1. Same flat (type, date, debitLedgerId, creditLedgerId, amount,
     * narration, refNumber) shape as [onPostQuickVoucher], since both draw from the same generic
     * double-entry form state. Not offered for Sale/Purchase/Credit-Debit Note this pass - those flows
     * build GST/stock detail only at post time, so a header-only draft would be lossy (see docs/54). */
    onSaveAsDraft: (VoucherType, LocalDate, String, String, Money, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    onPostSaleInvoice: (String, String, List<AccountingViewModel.TradingLineForm>, LocalDate, String, String, com.example.accounting.domain.taxation.gst.GstPricingMode) -> Unit = { _, _, _, _, _, _, _ -> },
    onPostPurchaseBill: (String, String, List<AccountingViewModel.TradingLineForm>, LocalDate, String, String, com.example.accounting.domain.taxation.gst.GstPricingMode) -> Unit = { _, _, _, _, _, _, _ -> },
    /** D1a - Sale/Purchase for an ACCOUNT_ONLY company: Party ledger, Trade ledger, amount, date,
     * reference number, narration, GST rate % (0.0 = no GST), HSN/SAC. Accounting-flow audit fix -
     * the trailing (Double, String) pair was added so Account-Only can still charge/claim GST when
     * [gstApplicable] is true; every existing caller that doesn't pass them keeps posting with
     * gstRatePercent = 0.0 (byte-identical to the pre-fix no-GST behavior). */
    onPostAccountOnlySale: (String, String, Money, LocalDate, String, String, Double, String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onPostAccountOnlyPurchase: (String, String, Money, LocalDate, String, String, Double, String) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onPostCreditNote: (String, LocalDate, String, String) -> Unit = { _, _, _, _ -> },
    onPostDebitNote: (String, LocalDate, String, String) -> Unit = { _, _, _, _ -> },
    onPostSettlement: (VoucherType, LocalDate, String, String, Money, String, String, String, List<Pair<String, Money>>) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onLoadOutstandingInvoices: (String) -> Unit = {},
    onClearOutstandingInvoices: () -> Unit = {},
    /** Bug #3 fix - QR/Barcode scan for Purchase Voucher creation, available regardless of
     * [isInventoryEnabled]/Accounting Mode (never gated by Items tab or Inventory Mode, per the
     * user's explicit instruction). Launches the same photo picker + [QrBarcodeAdapter.scanImage]
     * pipeline the Items tab already uses - never a second scan mechanism. `null` (the default)
     * hides the "Scan Bill" affordance entirely for callers that don't wire it. */
    onScanBarcode: (() -> Unit)? = null,
    /** The raw decoded value from the most recent scan requested via [onScanBarcode] - only ever
     * *populates* a field (Reference Number, or an Item line when a [StockItem] match exists); the
     * user still reviews/edits and explicitly taps "Post to Ledger". Never auto-posts, never
     * creates a party/ledger/item. Caller clears this (via [onScannedValueConsumed]) once applied
     * so it doesn't reapply on recomposition. */
    scannedBarcodeValue: String? = null,
    /** [com.example.accounting.domain.qrbarcode.BarcodeScanSuggestion.matchedStockItemId] - a
     * *suggestion* only, exactly as the frozen [QrBarcodeAdapter] contract promises; still just
     * pre-selects an existing item into a new line, never creates one. */
    scannedMatchedItemId: String? = null,
    onScannedValueConsumed: () -> Unit = {}
) {
    var selectedType by remember { mutableStateOf(defaultVoucherType) }
    // Post-safety fix - guards against a double-tap firing two separate post calls before
    // recomposition disables the button; onPostXXX callbacks are fire-and-forget (no in-flight
    // signal comes back to this Composable), so this is a purely local, one-shot latch.
    var isSubmitting by remember { mutableStateOf(false) }
    val isSaleFlow = selectedType == VoucherType.SALES
    val isPurchaseFlow = selectedType == VoucherType.PURCHASE
    val isTradingFlow = isSaleFlow || isPurchaseFlow
    val isCreditNoteFlow = selectedType == VoucherType.CREDIT_NOTE
    val isDebitNoteFlow = selectedType == VoucherType.DEBIT_NOTE
    val isNoteFlow = isCreditNoteFlow || isDebitNoteFlow
    val isReceiptFlow = selectedType == VoucherType.RECEIPT
    val isPaymentFlow = selectedType == VoucherType.PAYMENT
    val isSettlementFlow = isReceiptFlow || isPaymentFlow
    val isContra = selectedType == VoucherType.CONTRA

    // Contra is restricted to Cash/Bank ledgers only (Phase 4.5/5) - the domain layer
    // (VoucherPostingEngine) enforces this independently of this UI filter (Phase 5, Priority 6).
    // Architecture correction - a direct groupId-prefix check (the original, still-correct fast
    // path for every ledger filed straight under the System group) OR a StandardSystemGroups.isUnder
    // ancestor walk (covers a ledger filed under a nested User Group), never only one.
    val groupsById = remember(groups) { groups.associateBy { it.groupId } }
    fun isCashOrBankLedger(ledger: Ledger) =
        StandardSystemGroups.isExactSystemGroup(ledger.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
            StandardSystemGroups.isExactSystemGroup(ledger.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
            StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById) ||
            StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
    val cashBankLedgers = remember(ledgers, groupsById) { ledgers.filter(::isCashOrBankLedger) }
    fun isDebtorLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.DEBTORS_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.DEBTORS_GROUP_ID, groupsById)
    fun isCreditorLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.CREDITORS_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.CREDITORS_GROUP_ID, groupsById)
    fun isSalesLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.SALES_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.SALES_GROUP_ID, groupsById)
    fun isPurchaseLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.PURCHASE_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.PURCHASE_GROUP_ID, groupsById)

    // ==== Generic (Contra/Journal) form state ====
    // Architecture correction (Voucher Correct workflow) - a non-null prefillFrom (always Contra/
    // Journal, always exactly 2 journal items per postQuickVoucher's own construction) seeds every
    // field below directly from the cancelled original, keyed so a fresh prefillFrom re-seeds.
    val prefillDebitItem = remember(prefillFrom) { prefillFrom?.items?.firstOrNull { it.type == com.example.accounting.core.common.DrCr.DEBIT } }
    val prefillCreditItem = remember(prefillFrom) { prefillFrom?.items?.firstOrNull { it.type == com.example.accounting.core.common.DrCr.CREDIT } }
    var debitLedgerId by remember(prefillFrom) {
        mutableStateOf(
            prefillDebitItem?.ledgerId
                ?: if (selectedType == VoucherType.CONTRA) cashBankLedgers.firstOrNull()?.ledgerId ?: "" else ledgers.firstOrNull()?.ledgerId ?: ""
        )
    }
    var creditLedgerId by remember(prefillFrom) {
        mutableStateOf(
            prefillCreditItem?.ledgerId
                ?: if (selectedType == VoucherType.CONTRA) cashBankLedgers.lastOrNull()?.ledgerId ?: "" else ledgers.lastOrNull()?.ledgerId ?: ""
        )
    }
    // Correct-Voucher amount-prefill fix - a Sale/Purchase's totalDebits is the GRAND total
    // (taxable value + every GST duty line combined, e.g. Purchase Account 100000 + CGST 14000 +
    // SGST 14000 = totalDebits 128000), but the account-only Sale/Purchase amount FIELD below
    // means the TAXABLE base only (GST is added ON TOP of it again at posting time via
    // postAccountOnlyTradingDocument -> TradingWorkflowEngine). Prefilling this field with
    // totalDebits therefore silently double-counted tax on every corrected Sale/Purchase re-post
    // (a ₹1,28,000 purchase would repost as ₹1,63,840 if the user didn't notice and fix the figure
    // manually) - never a benign display quirk, an actual inflated repost. For a Sale/Purchase this
    // now seeds from the trade-ledger line's own amount (lineOrder != 1, the same line
    // [prefillTradeItem] below resolves for [tradeLedgerId]) - the true taxable value, matching
    // exactly what this field means when a user types a fresh voucher. Every other voucher type
    // (Contra/Journal/Receipt/Payment) keeps using totalDebits - unchanged, since those really do
    // mean the whole line amount with no separate GST component to double-count.
    var amountInput by remember(prefillFrom) {
        mutableStateOf(
            prefillFrom?.let { pf ->
                if (pf.voucherType == VoucherType.SALES || pf.voucherType == VoucherType.PURCHASE) {
                    pf.items.firstOrNull { it.lineOrder != 1 }?.amount
                } else {
                    pf.totalDebits
                }
            }?.takeIf { it.isPositive }?.formatPlain() ?: ""
        )
    }
    var narration by remember(prefillFrom) { mutableStateOf(prefillFrom?.narration ?: "") }
    var referenceNumber by remember(prefillFrom) { mutableStateOf(prefillFrom?.referenceNumber ?: "") }
    var debitDropdownExpanded by remember { mutableStateOf(false) }
    var creditDropdownExpanded by remember { mutableStateOf(false) }
    val ledgersMap = remember(ledgers) { ledgers.associateBy { it.ledgerId } }
    val amountMoney = remember(amountInput) { Money.parse(amountInput) }

    // ==== Sale/Purchase item-line form state ====
    // Extend-correction-to-all-types fix - a Sale/Purchase prefillFrom's party line is always
    // lineOrder == 1 (the same convention AccountingRepository.resolveTradeCounterparty relies on
    // elsewhere in this codebase, confirmed in TradingWorkflowEngine.build()/buildAccountOnly()),
    // the trade ledger is the other line.
    val isPrefillTrading = prefillFrom?.voucherType == VoucherType.SALES || prefillFrom?.voucherType == VoucherType.PURCHASE
    val prefillPartyItem = remember(prefillFrom) { prefillFrom?.items?.firstOrNull { it.lineOrder == 1 } }
    val prefillTradeItem = remember(prefillFrom) { prefillFrom?.items?.firstOrNull { it.lineOrder != 1 } }
    var partyLedgerId by remember(prefillFrom) {
        mutableStateOf(if (isPrefillTrading) prefillPartyItem?.ledgerId ?: "" else "")
    }
    var tradeLedgerId by remember(prefillFrom) {
        mutableStateOf(
            (if (isPrefillTrading) prefillTradeItem?.ledgerId else null)
                ?: ledgers.firstOrNull { if (isSaleFlow) isSalesLedger(it) else isPurchaseLedger(it) }?.ledgerId ?: ""
        )
    }
    var lines by remember { mutableStateOf(listOf(LineFormState())) }
    // Centralized GST engine - GST Inclusive/Exclusive pricing, whole-document (never per-line -
    // a real invoice is priced one way or the other). Defaults to EXCLUSIVE, byte-identical to
    // every existing Sale/Purchase before this toggle existed.
    var pricingMode by remember(selectedType) {
        mutableStateOf(com.example.accounting.domain.taxation.gst.GstPricingMode.EXCLUSIVE)
    }
    // Accounting-flow audit fix - Account-Only Sale/Purchase GST rate/HSN, only ever read when
    // isInventoryEnabled is false and gstApplicable is true (see TradingForm's own gating).
    // Extend-correction-to-all-types fix - seeded from prefillGstDetail when correcting a Sale/
    // Purchase (never carried on Voucher/JournalItem itself, see prefillGstDetail's doc comment).
    var accountOnlyGstRateInput by remember(prefillFrom) { mutableStateOf(prefillGstDetail?.first?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "0") }
    var accountOnlyHsnSacInput by remember(prefillFrom) { mutableStateOf(prefillGstDetail?.second ?: "") }
    var partyDropdownExpanded by remember { mutableStateOf(false) }
    var tradeDropdownExpanded by remember { mutableStateOf(false) }
    val itemsMap = remember(stockItems) { stockItems.associateBy { it.itemId } }

    // Follow-up fix - a visible summary of what a Purchase-flow scan actually found/applied
    // (the user's own feedback: a silent prefill with "no display and details" is not enough).
    // Cleared whenever a new scan starts or the user dismisses it.
    var lastScanSummary by remember { mutableStateOf<String?>(null) }

    // Bug #3 fix - apply a Purchase-flow barcode scan result: works identically in
    // ACCOUNT_ONLY and ACCOUNT_WITH_INVENTORY (never gated by isInventoryEnabled/Items tab). A
    // matched StockItem only ever pre-selects an existing item into a new line (never creates
    // one); a matched Supplier (by GSTIN found in the scanned text, against already-loaded
    // `ledgers` - no new lookup/service) only ever pre-selects an existing Supplier (never
    // creates one); otherwise the raw scanned value only ever prefills Reference Number when it
    // is still blank (never overwrites what the user already typed). Purely a form-state prefill
    // - posting still requires the user to review the form and explicitly tap "Post to Ledger".
    //
    // 13-point correctness pass, item 4 - a real e-invoice/IRP QR carries a structured JSON
    // payload (Seller GSTIN, Doc No, Total Value); [GstEInvoiceQrParser] is tried first and, when
    // it recognizes the payload, drives the same three prefills below from its parsed fields
    // instead of raw substring matching. A plain (non-JSON) barcode falls through to the exact
    // pre-existing behavior unchanged.
    LaunchedEffect(scannedBarcodeValue) {
        val scanned = scannedBarcodeValue ?: return@LaunchedEffect
        if (isPurchaseFlow) {
            val eInvoice = com.example.accounting.domain.scanning.GstEInvoiceQrParser.parse(scanned)
            val normalizedScan = (eInvoice?.sellerGstin ?: scanned).trim().uppercase()
            val matchedSupplier = ledgers.firstOrNull { isCreditorLedger(it) && it.gstin.isNotBlank() && normalizedScan.contains(it.gstin.trim().uppercase()) }
            if (matchedSupplier != null) partyLedgerId = matchedSupplier.ledgerId

            val matchedItem = scannedMatchedItemId?.let { itemsMap[it] }
            val itemApplied = if (isInventoryEnabled && matchedItem != null) {
                val alreadyOnALine = lines.any { it.itemId == matchedItem.itemId }
                if (!alreadyOnALine) {
                    val newLine = LineFormState(itemId = matchedItem.itemId, rateInput = (matchedItem.standardCost.paise / 100.0).toString())
                    lines = if (lines.size == 1 && lines[0].itemId.isBlank()) listOf(newLine) else lines + newLine
                }
                true
            } else false

            val refFromScan = eInvoice?.docNo?.takeIf { it.isNotBlank() } ?: scanned
            if (!itemApplied && referenceNumber.isBlank()) referenceNumber = refFromScan
            if (!isInventoryEnabled && eInvoice != null && eInvoice.totalInvoiceValue.isNotBlank()) {
                val parsedTotal = Money.parse(eInvoice.totalInvoiceValue)
                if (amountInput.isBlank() && parsedTotal.isPositive) amountInput = eInvoice.totalInvoiceValue
            }

            lastScanSummary = buildString {
                append(if (matchedSupplier != null) "Supplier: ${matchedSupplier.name}" else "No Supplier matched this code")
                if (itemApplied && matchedItem != null) append(" • Item: ${matchedItem.name}")
                if (eInvoice != null) {
                    if (eInvoice.docNo.isNotBlank()) append(" • Invoice: ${eInvoice.docNo}")
                    if (eInvoice.totalInvoiceValue.isNotBlank()) append(" • Value: ${eInvoice.totalInvoiceValue}")
                } else {
                    append(" • Ref: $scanned")
                }
            }
        }
        onScannedValueConsumed()
    }

    // ==== Credit/Debit Note form state ====
    var originalVoucherId by remember { mutableStateOf("") }
    var originalDropdownExpanded by remember { mutableStateOf(false) }
    val eligibleOriginals = remember(vouchers, isCreditNoteFlow) {
        val wantType = if (isCreditNoteFlow) VoucherType.SALES else VoucherType.PURCHASE
        vouchers.filter { it.voucherType == wantType && !it.isCancelled }
    }

    // ==== Receipt/Payment settlement form state ====
    // Extend-correction-to-all-types fix - prefills party ledger, cash/bank ledger, and amount from
    // the cancelled original; deliberately never the original invoice allocation (see
    // VOUCHER_CORRECTION_ELIGIBLE_TYPES's doc comment - cancelling the original settlement already
    // makes its invoice(s) outstanding again, so the user re-allocates on repost). Receipt debits
    // the cash/bank ledger (party is credited); Payment is the reverse - resolve each side by type,
    // not by lineOrder, since Receipt/Payment vouchers don't follow the trading lineOrder==1
    // convention.
    val isPrefillSettlement = prefillFrom?.voucherType == VoucherType.RECEIPT || prefillFrom?.voucherType == VoucherType.PAYMENT
    val prefillSettlementPartyItem = remember(prefillFrom) {
        prefillFrom?.items?.firstOrNull { if (prefillFrom.voucherType == VoucherType.RECEIPT) it.type == com.example.accounting.core.common.DrCr.CREDIT else it.type == com.example.accounting.core.common.DrCr.DEBIT }
    }
    val prefillSettlementCashBankItem = remember(prefillFrom) {
        prefillFrom?.items?.firstOrNull { if (prefillFrom.voucherType == VoucherType.RECEIPT) it.type == com.example.accounting.core.common.DrCr.DEBIT else it.type == com.example.accounting.core.common.DrCr.CREDIT }
    }
    var settlementPartyLedgerId by remember(prefillFrom) {
        mutableStateOf(if (isPrefillSettlement) prefillSettlementPartyItem?.ledgerId ?: "" else "")
    }
    var settlementCashBankLedgerId by remember(prefillFrom) {
        mutableStateOf(
            (if (isPrefillSettlement) prefillSettlementCashBankItem?.ledgerId else null)
                ?: cashBankLedgers.firstOrNull()?.ledgerId ?: ""
        )
    }
    // Extend-correction-to-all-types fix (Payment Mode gap) - settlementCashBankLedgerId above
    // already prefills the correct ledger from the cancelled original, but the Payment Mode pill
    // (persisted on Voucher.paymentMode, driving the "via CASH/BANK" text on VoucherDetailDialog)
    // was hardcoded to "BANK" even when correcting a CASH voucher - a real device-testing find.
    var paymentMode by remember(prefillFrom) {
        mutableStateOf(if (isPrefillSettlement) prefillFrom?.paymentMode?.takeIf { it.isNotBlank() } ?: "BANK" else "BANK")
    }
    var settlementAmountInput by remember(prefillFrom) {
        mutableStateOf(if (isPrefillSettlement) prefillFrom?.totalDebits?.takeIf { it.isPositive }?.formatPlain() ?: "" else "")
    }
    var allocationInputs by remember { mutableStateOf(mapOf<String, String>()) }
    var settlementPartyDropdownExpanded by remember { mutableStateOf(false) }
    val eligibleSettlementParties = remember(ledgers, isReceiptFlow) {
        ledgers.filter { if (isReceiptFlow) isDebtorLedger(it) else isCreditorLedger(it) }
    }
    val settlementAmountMoney = remember(settlementAmountInput) { Money.parse(settlementAmountInput) }
    val totalAllocated = remember(allocationInputs) {
        allocationInputs.values.fold(Money.ZERO) { acc, v -> acc + Money.parse(v) }
    }
    val unallocatedRemainder = remember(settlementAmountMoney, totalAllocated) { settlementAmountMoney - totalAllocated }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false - required for navigationBarsPadding() below to have any
        // effect inside a Dialog's separate window (see CreateLedgerDialog's fuller note).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                // Real-device QA fix - this Surface previously had no height bound, so
                // `weight(1f, fill = false)` on the scrollable body below (inside an
                // effectively unbounded-height Column) couldn't actually cap anything: for a
                // tall form (e.g. an item-based Sale/Purchase with the GST Pricing toggle and
                // live CGST/SGST/Total GST/Round Off breakdown), the whole dialog just grew
                // past the screen, pushing "Post to Ledger" underneath the on-screen
                // navigation bar - visible but untappable. Capping the Surface itself is what
                // makes that weight meaningful, so the footer always stays on-screen.
                .fillMaxHeight(0.92f)
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                // Hoisted out of the scrollable body below - also needed by the fixed footer's
                // Post button (`enabled = isReady && ...`), which now lives outside that scroll area.
                val isReady = when {
                    isTradingFlow -> partyLedgerId.isNotBlank() && tradeLedgerId.isNotBlank() &&
                        if (isInventoryEnabled) {
                            lines.any { it.itemId.isNotBlank() && (it.quantityInput.toDoubleOrNull() ?: 0.0) > 0.0 }
                        } else {
                            amountMoney.isPositive
                        }
                    isNoteFlow -> originalVoucherId.isNotBlank()
                    isSettlementFlow -> settlementPartyLedgerId.isNotBlank() && settlementCashBankLedgerId.isNotBlank() && settlementAmountMoney.isPositive && unallocatedRemainder.paise >= 0L
                    else -> amountMoney.isPositive && debitLedgerId.isNotBlank() && creditLedgerId.isNotBlank()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "New Accounting Voucher",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isServiceCompany) "Income, Expenditure, Receipt, Payment & More" else "Sale, Purchase, Receipt, Payment & More",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!lockedType) {
                Text(
                    text = "Voucher Nature",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        VoucherType.SALES,
                        VoucherType.PURCHASE,
                        VoucherType.RECEIPT,
                        VoucherType.PAYMENT,
                        VoucherType.CREDIT_NOTE,
                        VoucherType.DEBIT_NOTE,
                        VoucherType.CONTRA,
                        VoucherType.JOURNAL
                    ).forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = {
                                selectedType = type
                                when (type) {
                                    VoucherType.CONTRA -> {
                                        debitLedgerId = cashBankLedgers.firstOrNull {
                                            StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.BANK_GROUP_ID) ||
                                                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.BANK_GROUP_ID, groupsById)
                                        }?.ledgerId ?: cashBankLedgers.firstOrNull()?.ledgerId ?: ""
                                        creditLedgerId = cashBankLedgers.firstOrNull {
                                            StandardSystemGroups.isExactSystemGroup(it.groupId, StandardSystemGroups.CASH_GROUP_ID) ||
                                                StandardSystemGroups.isUnder(it.groupId, StandardSystemGroups.CASH_GROUP_ID, groupsById)
                                        }?.ledgerId ?: cashBankLedgers.lastOrNull()?.ledgerId ?: ""
                                    }
                                    VoucherType.SALES -> {
                                        partyLedgerId = ledgers.firstOrNull(::isDebtorLedger)?.ledgerId ?: ""
                                        tradeLedgerId = ledgers.firstOrNull(::isSalesLedger)?.ledgerId ?: ""
                                    }
                                    VoucherType.PURCHASE -> {
                                        partyLedgerId = ledgers.firstOrNull(::isCreditorLedger)?.ledgerId ?: ""
                                        tradeLedgerId = ledgers.firstOrNull(::isPurchaseLedger)?.ledgerId ?: ""
                                    }
                                    VoucherType.RECEIPT -> {
                                        settlementPartyLedgerId = ""
                                        settlementCashBankLedgerId = cashBankLedgers.firstOrNull()?.ledgerId ?: ""
                                        allocationInputs = emptyMap()
                                        onClearOutstandingInvoices()
                                    }
                                    VoucherType.PAYMENT -> {
                                        settlementPartyLedgerId = ""
                                        settlementCashBankLedgerId = cashBankLedgers.firstOrNull()?.ledgerId ?: ""
                                        allocationInputs = emptyMap()
                                        onClearOutstandingInvoices()
                                    }
                                    else -> {}
                                }
                            },
                            label = { Text(voucherNatureLabel(type, isServiceCompany), fontSize = 12.sp) }
                        )
                    }
                }
                }

                // Bug #3 fix - QR/Barcode scan for Purchase Voucher creation, available in both
                // ACCOUNT_ONLY and ACCOUNT_WITH_INVENTORY (never gated by Inventory Mode/Items
                // tab). Only ever populates the form above via LaunchedEffect(scannedBarcodeValue)
                // - the user still reviews/edits and explicitly taps "Post to Ledger".
                if (isPurchaseFlow && onScanBarcode != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { lastScanSummary = null; onScanBarcode() }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Bill Barcode / QR")
                    }
                    // Follow-up fix - a visible confirmation of what the scan found/applied,
                    // never a silent prefill. Dismissible; posting is still a separate, explicit step.
                    lastScanSummary?.let { summary ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Scanned - $summary",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { lastScanSummary = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                when {
                    isTradingFlow -> TradingForm(
                        isSale = isSaleFlow,
                        isServiceCompany = isServiceCompany,
                        ledgers = ledgers,
                        stockItems = stockItems,
                        itemsMap = itemsMap,
                        companyStateCode = companyStateCode,
                        partyLedgerId = partyLedgerId,
                        onPartyLedgerChange = { partyLedgerId = it },
                        tradeLedgerId = tradeLedgerId,
                        onTradeLedgerChange = { tradeLedgerId = it },
                        lines = lines,
                        onLinesChange = { lines = it },
                        isDebtorLedger = ::isDebtorLedger,
                        isCreditorLedger = ::isCreditorLedger,
                        isSalesLedger = ::isSalesLedger,
                        isPurchaseLedger = ::isPurchaseLedger,
                        ledgersMap = ledgersMap,
                        partyDropdownExpanded = partyDropdownExpanded,
                        onPartyDropdownExpandedChange = { partyDropdownExpanded = it },
                        tradeDropdownExpanded = tradeDropdownExpanded,
                        onTradeDropdownExpandedChange = { tradeDropdownExpanded = it },
                        onAddNewParty = { onAddNewParty(if (isSaleFlow) PartyRole.CUSTOMER else PartyRole.SUPPLIER) },
                        onAddNewTradeLedger = { onAddNewTradeLedger(isSaleFlow) },
                        isInventoryEnabled = isInventoryEnabled,
                        amountInput = amountInput,
                        onAmountChange = { amountInput = it },
                        gstApplicable = gstApplicable,
                        gstRateInput = accountOnlyGstRateInput,
                        onGstRateChange = { accountOnlyGstRateInput = it },
                        hsnSacInput = accountOnlyHsnSacInput,
                        onHsnSacChange = { accountOnlyHsnSacInput = it },
                        pricingMode = pricingMode,
                        onPricingModeChange = { pricingMode = it }
                    )

                    isNoteFlow -> NoteForm(
                        isCredit = isCreditNoteFlow,
                        eligibleOriginals = eligibleOriginals,
                        originalVoucherId = originalVoucherId,
                        onOriginalVoucherChange = { originalVoucherId = it },
                        expanded = originalDropdownExpanded,
                        onExpandedChange = { originalDropdownExpanded = it }
                    )

                    isSettlementFlow -> SettlementForm(
                        isReceipt = isReceiptFlow,
                        eligibleParties = eligibleSettlementParties,
                        partyLedgerId = settlementPartyLedgerId,
                        onPartyLedgerChange = {
                            settlementPartyLedgerId = it
                            allocationInputs = emptyMap()
                            onLoadOutstandingInvoices(it)
                        },
                        expanded = settlementPartyDropdownExpanded,
                        onExpandedChange = { settlementPartyDropdownExpanded = it },
                        cashBankLedgers = cashBankLedgers,
                        cashBankLedgerId = settlementCashBankLedgerId,
                        onCashBankLedgerChange = { settlementCashBankLedgerId = it },
                        paymentMode = paymentMode,
                        onPaymentModeChange = { paymentMode = it },
                        outstandingInvoices = outstandingInvoices,
                        allocationInputs = allocationInputs,
                        onAllocationChange = { voucherId, value -> allocationInputs = allocationInputs + (voucherId to value) },
                        amountInput = settlementAmountInput,
                        onAmountChange = { settlementAmountInput = it },
                        totalAllocated = totalAllocated,
                        unallocatedRemainder = unallocatedRemainder,
                        onAddNewParty = { onAddNewParty(if (isReceiptFlow) PartyRole.CUSTOMER else PartyRole.SUPPLIER) },
                        onAddNewBankLedger = onAddNewBankLedger
                    )

                    else -> {
                        val availableLedgers = if (isContra) cashBankLedgers else ledgers

                        ExposedDropdownMenuBox(expanded = debitDropdownExpanded, onExpandedChange = { debitDropdownExpanded = it }) {
                            OutlinedTextField(
                                value = ledgersMap[debitLedgerId]?.name ?: if (isContra) "Select From Account (Cash/Bank)" else "Select Source Account",
                                onValueChange = {}, readOnly = true,
                                label = { Text(if (isContra) "From Account" else "Source Account") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = debitDropdownExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = debitDropdownExpanded, onDismissRequest = { debitDropdownExpanded = false }) {
                                availableLedgers.forEach { led ->
                                    DropdownMenuItem(
                                        text = { Text("${led.name} [${led.groupName}]") },
                                        onClick = { debitLedgerId = led.ledgerId; debitDropdownExpanded = false }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        ExposedDropdownMenuBox(expanded = creditDropdownExpanded, onExpandedChange = { creditDropdownExpanded = it }) {
                            OutlinedTextField(
                                value = ledgersMap[creditLedgerId]?.name ?: if (isContra) "Select To Account (Cash/Bank)" else "Select Adjustment Account",
                                onValueChange = {}, readOnly = true,
                                label = { Text(if (isContra) "To Account" else "Adjustment Account") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = creditDropdownExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = creditDropdownExpanded, onDismissRequest = { creditDropdownExpanded = false }) {
                                availableLedgers.forEach { led ->
                                    DropdownMenuItem(
                                        text = { Text("${led.name} [${led.groupName}]") },
                                        onClick = { creditLedgerId = led.ledgerId; creditDropdownExpanded = false }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = amountInput, onValueChange = { amountInput = it },
                            label = { Text("Voucher Amount") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("voucher_amount_input")
                        )
                    }
                }

                if (!isSettlementFlow) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = referenceNumber, onValueChange = { referenceNumber = it },
                        label = {
                            Text(
                                when {
                                    isNoteFlow -> "Note Reference (defaults to original invoice no.)"
                                    isSaleFlow -> "Invoice Number (Optional)"
                                    isPurchaseFlow -> "Invoice Number (Optional)"
                                    else -> "Ref / Cheque / Invoice No. (Optional)"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = narration, onValueChange = { narration = it },
                        label = { Text("Accounting Narration") },
                        placeholder = { Text("Being amount paid / received for...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val readyTotal = when {
                    isTradingFlow -> if (isInventoryEnabled) {
                        lines.sumOf { line ->
                            val item = itemsMap[line.itemId]
                            val qty = line.quantityInput.toDoubleOrNull() ?: 0.0
                            val rate = Money.parse(line.rateInput.ifBlank { "0" }).paise
                            if (item != null) (qty * rate).toLong() else 0L
                        }.let { Money.fromPaise(it) }
                    } else {
                        amountMoney
                    }
                    isSettlementFlow -> settlementAmountMoney
                    else -> amountMoney
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isReady) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isReady) {
                                if (isNoteFlow) "Ready to Post" else "Ready to Post - Total ${readyTotal.formatPlain()}"
                            } else if (isSettlementFlow && unallocatedRemainder.paise < 0L) {
                                "Allocated amount exceeds the amount entered"
                            } else if (isTradingFlow && partyLedgerId.isBlank()) {
                                "Select a ${if (isSaleFlow) "Customer" else "Supplier"} to continue"
                            } else if (isTradingFlow && tradeLedgerId.isBlank()) {
                                "Select ${if (isSaleFlow) "a Sales" else "a Purchase"} Account to continue"
                            } else "Complete the fields to continue",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = if (isReady) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

            }

                // Save as Draft is offered only for the generic double-entry flows (Contra/Journal/
                // Receipt/Payment) where the form already holds a flat debit/credit ledger pair -
                // Sale/Purchase/Notes stay immediate-post-only this pass (see docs/54).
                val canSaveAsDraft = !isTradingFlow && !isNoteFlow && if (isSettlementFlow) {
                    settlementPartyLedgerId.isNotBlank() && settlementCashBankLedgerId.isNotBlank()
                } else {
                    debitLedgerId.isNotBlank() && creditLedgerId.isNotBlank()
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (!isTradingFlow && !isNoteFlow) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val (draftDebitId, draftCreditId, draftAmount) = if (isSettlementFlow) {
                                    Triple(
                                        if (isReceiptFlow) settlementCashBankLedgerId else settlementPartyLedgerId,
                                        if (isReceiptFlow) settlementPartyLedgerId else settlementCashBankLedgerId,
                                        settlementAmountMoney
                                    )
                                } else {
                                    Triple(debitLedgerId, creditLedgerId, amountMoney)
                                }
                                onSaveAsDraft(
                                    selectedType, LocalDate.now(), draftDebitId, draftCreditId, draftAmount,
                                    narration.ifBlank { "Being ${voucherNatureLabel(selectedType, isServiceCompany).lowercase()} transaction (draft)" },
                                    referenceNumber
                                )
                                onDismiss()
                            },
                            enabled = canSaveAsDraft,
                            modifier = Modifier.testTag("save_as_draft_button")
                        ) { Text("Save as Draft") }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onClick@{
                            if (isSubmitting) return@onClick
                            isSubmitting = true
                            when {
                                isSaleFlow && isInventoryEnabled -> onPostSaleInvoice(
                                    partyLedgerId, tradeLedgerId,
                                    lines.filter { it.itemId.isNotBlank() }.map {
                                        AccountingViewModel.TradingLineForm(
                                            it.itemId, it.quantityInput.toDoubleOrNull() ?: 0.0, Money.parse(it.rateInput.ifBlank { "0" }),
                                            it.supplyNature, it.chargeType, (it.discountInput.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
                                        )
                                    },
                                    LocalDate.now(), referenceNumber, narration, pricingMode
                                )
                                isSaleFlow -> onPostAccountOnlySale(partyLedgerId, tradeLedgerId, amountMoney, LocalDate.now(), referenceNumber, narration, accountOnlyGstRateInput.toDoubleOrNull() ?: 0.0, accountOnlyHsnSacInput)
                                isPurchaseFlow && isInventoryEnabled -> onPostPurchaseBill(
                                    partyLedgerId, tradeLedgerId,
                                    lines.filter { it.itemId.isNotBlank() }.map {
                                        AccountingViewModel.TradingLineForm(
                                            it.itemId, it.quantityInput.toDoubleOrNull() ?: 0.0, Money.parse(it.rateInput.ifBlank { "0" }),
                                            it.supplyNature, it.chargeType, (it.discountInput.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
                                        )
                                    },
                                    LocalDate.now(), referenceNumber, narration, pricingMode
                                )
                                isPurchaseFlow -> onPostAccountOnlyPurchase(partyLedgerId, tradeLedgerId, amountMoney, LocalDate.now(), referenceNumber, narration, accountOnlyGstRateInput.toDoubleOrNull() ?: 0.0, accountOnlyHsnSacInput)
                                isCreditNoteFlow -> onPostCreditNote(originalVoucherId, LocalDate.now(), referenceNumber, narration)
                                isDebitNoteFlow -> onPostDebitNote(originalVoucherId, LocalDate.now(), referenceNumber, narration)
                                isSettlementFlow -> {
                                    val allocations = allocationInputs.mapNotNull { (voucherId, input) ->
                                        val amt = Money.parse(input)
                                        if (amt.isPositive) voucherId to amt else null
                                    }
                                    val debitId = if (isReceiptFlow) settlementCashBankLedgerId else settlementPartyLedgerId
                                    val creditId = if (isReceiptFlow) settlementPartyLedgerId else settlementCashBankLedgerId
                                    onPostSettlement(
                                        selectedType, LocalDate.now(), debitId, creditId, settlementAmountMoney,
                                        narration.ifBlank { "Being ${selectedType.displayName.lowercase()} ${if (isReceiptFlow) "from" else "to"} ${ledgersMap[settlementPartyLedgerId]?.name ?: "party"}" },
                                        referenceNumber, paymentMode, allocations
                                    )
                                }
                                else -> onPostQuickVoucher(
                                    selectedType, LocalDate.now(), debitLedgerId, creditLedgerId, amountMoney,
                                    narration.ifBlank { "Being ${voucherNatureLabel(selectedType, isServiceCompany)} transaction" }, referenceNumber
                                )
                            }
                            onDismiss()
                        },
                        enabled = isReady && !isSubmitting,
                        modifier = Modifier.testTag("submit_voucher_button")
                    ) {
                        Text("Post to Ledger")
                    }
                }
            }
        }
    }
}
