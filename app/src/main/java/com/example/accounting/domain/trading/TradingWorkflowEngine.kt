package com.example.accounting.domain.trading

import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.core.common.Quantity
import com.example.accounting.domain.accounting.GstRegistrationStatus
import com.example.accounting.domain.accounting.JournalItem
import com.example.accounting.domain.accounting.RoundOffEngine
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.inventory.StockDirection
import com.example.accounting.domain.inventory.VoucherStockLine
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstDirection
import com.example.accounting.domain.taxation.gst.GstPricingMode
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransaction
import com.example.accounting.domain.taxation.gst.GstTransactionFacts
import com.example.accounting.domain.taxation.gst.SupplyType
import java.time.LocalDate
import java.util.UUID

/** A Sale/Purchase voucher with a computed, non-zero outstanding balance (Phase 5, Priority 2) -
 * `total - Σ allocations - Σ note adjustments`, never a stored balance. */
data class OutstandingInvoice(
    val voucherId: String,
    val voucherNumber: String,
    val voucherType: VoucherType,
    val date: LocalDate,
    val totalAmount: Money,
    val outstandingAmount: Money
)

/** One item line as entered on a Sale/Purchase document - GST rate/HSN come from the selected
 * stock item, never typed freely by the user (Phase 5, Priority 1/3). */
data class TradingLineInput(
    val itemId: String,
    val itemName: String,
    val hsnSacCode: String,
    val quantity: Quantity,
    val rate: Money,
    val gstRatePercent: Double,
    val cessRatePercent: Double = 0.0,
    /** Trade discount on this line (Discount %, entered on the Sale/Purchase form) - reduces the
     * taxable value BEFORE GST is calculated, exactly like a real invoice's Discount column: GST
     * is charged on the net (post-discount) value, never on the gross list price. Defaults to 0.0,
     * i.e. byte-identical behavior to before this field existed. Deliberately not persisted as its
     * own column - [VoucherStockLine.amount]/[GstTransaction.taxableAmount] already store the
     * resulting net value directly, which is all GST/reporting correctness needs; only the exact
     * percentage itself is not separately recoverable later (Rate x Qty vs. Amount already shows
     * a discount was applied). */
    val discountPercent: Double = 0.0,
    /** Tax Treatment (UI-06) - defaults to NORMAL (Taxable), i.e. byte-identical behavior to
     * before this field existed: geography (company vs place-of-supply) still decides Intra/Inter.
     * EXPORT/EXEMPT/NIL_RATED bypass geography entirely via [GstCalculationEngine.calculateDetailed]. */
    val supplyNature: GstSupplyNature = GstSupplyNature.NORMAL,
    /**
     * Rule 31 (Purchase/RCM Foundation) - who is liable to remit this line's tax. Lives here, per
     * line, deliberately never on the supplier [com.example.accounting.domain.accounting.Ledger] -
     * RCM is a fact about a specific supply, not a permanent property of who you bought it from.
     * [com.example.accounting.domain.accounting.Ledger.gstRegistrationStatus] is never read to set
     * this; a user must explicitly choose REVERSE_CHARGE. Defaults to FORWARD_CHARGE, i.e.
     * byte-identical behavior to before this field existed. Only meaningful on a Purchase line
     * (`build()` rejects it on a Sale) and only combinable with [GstSupplyNature.NORMAL] - there is
     * no tax to reverse-charge on a zero-tax supply.
     */
    val chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE
)

/** A resolved ledger reference (companyId-suffixed id + display name) - supplied by the caller
 * (which has DAO access) so this engine itself stays pure/Room-independent. */
data class LedgerRef(val ledgerId: String, val name: String)

data class TradingGstLedgers(
    val outputCgst: LedgerRef, val outputSgst: LedgerRef, val outputIgst: LedgerRef,
    val inputCgst: LedgerRef, val inputSgst: LedgerRef, val inputIgst: LedgerRef,
    val cess: LedgerRef,
    /** Rule 31 (Purchase/RCM Foundation) - deliberately separate ledgers from [inputCgst]/etc.
     * (ordinary forward-charge Input Tax Credit) and from the supplier payable ledger. See
     * [com.example.accounting.domain.taxation.gst.GstLedgerIds] for the full rationale. */
    val rcmLiabilityCgst: LedgerRef, val rcmLiabilitySgst: LedgerRef, val rcmLiabilityIgst: LedgerRef,
    val rcmInputCgst: LedgerRef, val rcmInputSgst: LedgerRef, val rcmInputIgst: LedgerRef
)

data class TradingWorkflowResult(
    val journalItems: List<JournalItem>,
    val stockLines: List<VoucherStockLine>,
    val gstTransactions: List<GstTransaction>,
    val totalAmount: Money
)

/**
 * Builds the journal lines, stock lines, and GST-transaction facts for Sale/Purchase documents
 * from item-level facts (Phase 5, Priority 1) - pure, no Room/Android, mirroring
 * [com.example.accounting.core.database.VoucherPostingEngine]'s design. The ViewModel assembles a
 * [com.example.accounting.domain.accounting.Voucher] from this output and calls
 * [com.example.accounting.data.repository.AccountingRepository.postVoucher] exactly as it already
 * does for every other voucher type - VoucherPostingEngine/InventoryEngine/DoubleEntryValidator are
 * not modified.
 *
 * Round Off (Priority 7) is computed here, never typed in by the UI: the raw taxable+tax total is
 * rounded to the nearest rupee, and any non-zero difference becomes one more journal line to the
 * Round Off ledger, keeping the voucher balanced without the receivable/payable line carrying a
 * fractional-rupee figure the customer/supplier didn't actually see on the rounded invoice total.
 */
object TradingWorkflowEngine {

    /**
     * Gross (Rate x Qty) less the line's Discount %, then - only in [GstPricingMode.INCLUSIVE] -
     * backed out to a pre-tax taxable value via [GSTRules.extractTaxableFromInclusive]. The one
     * shared computation every build path below uses instead of calling
     * [VoucherStockLine.computeAmount] directly, so [GstPricingMode.EXCLUSIVE] (every line before
     * both this field and Discount existed) is byte-identical to the old gross value. Discount is
     * applied on whatever basis [line.rate] already is (inclusive or exclusive) before the
     * inclusive-mode backout - the discount itself is never treated as a tax-bearing adjustment.
     */
    private fun netTaxable(line: TradingLineInput, pricingMode: GstPricingMode): Money = lineAmounts(line, pricingMode).taxable

    /** The discount amount actually subtracted (Rate x Qty x Discount% - one computation, read
     * from here by both [netTaxable] callers and the [VoucherStockLine.discount] persisted on the
     * stock line, never re-derived a second way for display). */
    private data class LineAmounts(val gross: Money, val discountAmount: Money, val taxable: Money)

    private fun lineAmounts(line: TradingLineInput, pricingMode: GstPricingMode): LineAmounts {
        val gross = VoucherStockLine.computeAmount(line.quantity, line.rate)
        val discountAmount = if (line.discountPercent > 0.0) gross.percentage(line.discountPercent) else Money.ZERO
        val net = gross - discountAmount
        val taxable = when (pricingMode) {
            GstPricingMode.EXCLUSIVE -> net
            GstPricingMode.INCLUSIVE -> GSTRules.extractTaxableFromInclusive(net, line.gstRatePercent)
        }
        return LineAmounts(gross, discountAmount, taxable)
    }

    fun buildSale(
        voucherId: String, companyId: String, financialYearId: String,
        customerLedgerId: String, customerName: String, customerGstin: String,
        salesLedgerId: String, salesLedgerName: String,
        companyStateCode: String, placeOfSupply: String,
        lines: List<TradingLineInput>,
        gstLedgers: TradingGstLedgers,
        roundOffLedgerId: String, roundOffLedgerName: String,
        /** Accounting-flow audit fix - Inventory Mode must only affect stock/COGS, never whether
         * GST accounting exists (see [buildAccountOnlySale]'s own doc comment for the bug this
         * closes). `true` (every existing caller, unchanged) produces [VoucherStockLine]s exactly
         * as before; `false` (Account-Only + GST-applicable) skips them while still computing the
         * full CGST/SGST/IGST/CESS breakdown and tax-ledger postings via the same code below. */
        trackInventory: Boolean = true,
        /** GST Inclusive/Exclusive pricing (whole document, not per-line - a real invoice is
         * priced one way or the other) - defaults to EXCLUSIVE, i.e. byte-identical behavior to
         * before this parameter existed. See [netTaxable]. */
        pricingMode: GstPricingMode = GstPricingMode.EXCLUSIVE
    ): TradingWorkflowResult = build(
        isSale = true, voucherId = voucherId, companyId = companyId, financialYearId = financialYearId,
        partyLedgerId = customerLedgerId, partyName = customerName, partyGstin = customerGstin,
        tradeLedgerId = salesLedgerId, tradeLedgerName = salesLedgerName,
        companyStateCode = companyStateCode, placeOfSupply = placeOfSupply, lines = lines,
        gstLedgers = gstLedgers, roundOffLedgerId = roundOffLedgerId, roundOffLedgerName = roundOffLedgerName,
        trackInventory = trackInventory, pricingMode = pricingMode
    )

    /**
     * GST-only Sale (Architecture Checkpoint follow-up, Option B continuation) - for a company
     * that does not maintain accounting at all. Computes exactly the same per-line GST facts
     * [buildSale] computes internally (same [GstCalculationEngine.calculateDetailed] call, same
     * [GstSupplyNature] handling) but produces ONLY [GstTransaction] rows - no [JournalItem]s, no
     * [com.example.accounting.domain.inventory.VoucherStockLine]s, no GST-duty-ledger references,
     * no Round Off. There is deliberately no `voucherId` parameter - every returned row's
     * `voucherId` is `null`, matching [GstTransaction.voucherId]'s Architecture-Checkpoint
     * nullability. This is intentionally a separate, smaller function rather than a refactor of
     * [build] - `build`'s loop also computes running totals for Round Off and constructs stock
     * lines, none of which apply here, and reshaping that already-tested function carries far more
     * regression risk than this ~15-line, self-contained addition that calls the same engine.
     */
    fun buildGstOnlySale(
        companyId: String,
        financialYearId: String,
        customerLedgerId: String,
        customerGstin: String,
        companyStateCode: String,
        placeOfSupply: String,
        lines: List<TradingLineInput>,
        /** D1b - the real invoice/business date for this GST-only Sale; see [GstTransaction.transactionDate]. */
        date: LocalDate,
        /** D1b - the customer's [GstRegistrationStatus] at posting time; see [GstTransaction.partyGstRegistrationStatus]. */
        partyGstRegistrationStatus: GstRegistrationStatus?,
        /** See [buildSale]'s parameter of the same name. */
        pricingMode: GstPricingMode = GstPricingMode.EXCLUSIVE
    ): List<GstTransaction> {
        require(lines.isNotEmpty()) { "At least one line item is required." }
        // D1b: one correlation id shared by every line of THIS Sale - see GstTransaction.transactionGroupId.
        val groupId = UUID.randomUUID().toString()
        return lines.mapIndexed { index, line ->
            val lineTaxable = netTaxable(line, pricingMode)
            val breakdown = GstCalculationEngine.calculateDetailed(
                GstTransactionFacts(
                    taxableAmount = lineTaxable,
                    gstRatePercent = line.gstRatePercent,
                    cessRatePercent = line.cessRatePercent,
                    supplierStateCode = companyStateCode,
                    placeOfSupply = placeOfSupply,
                    supplyNature = line.supplyNature
                )
            )
            GstTransaction(
                gstTransactionId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = financialYearId,
                voucherId = null,
                voucherType = VoucherType.SALES,
                partyLedgerId = customerLedgerId,
                partyGstin = customerGstin,
                placeOfSupply = placeOfSupply,
                supplyType = breakdown.supplyType,
                itemId = line.itemId,
                hsnSacCode = line.hsnSacCode,
                quantity = line.quantity,
                taxableAmount = breakdown.taxableAmount,
                gstRatePercent = line.gstRatePercent,
                cgst = breakdown.cgstAmount,
                sgst = breakdown.sgstAmount,
                igst = breakdown.igstAmount,
                cess = breakdown.cessAmount,
                direction = GstDirection.OUTPUT,
                lineOrder = index + 1,
                supplyNature = line.supplyNature,
                transactionGroupId = groupId,
                transactionDate = date,
                partyGstRegistrationStatus = partyGstRegistrationStatus
            )
        }
    }

    /**
     * D1b (GST-Only Purchase + Sales/Purchase Return + GST Fact Hardening) - the Purchase
     * counterpart of [buildGstOnlySale], reusing the exact same [GstCalculationEngine] call and
     * per-line facts. Unlike Sale, a Purchase line's [TradingLineInput.chargeType] is meaningful
     * (Rule 31/RCM) and is threaded through onto each [GstTransaction] exactly as [build] already
     * does for the accounting-integrated Purchase path - RCM here is exactly as explicit and
     * un-inferred as it already is there (same `require()` backstop, never derived from
     * [GstRegistrationStatus]/party type/vendor type/GSTIN/tax rate).
     */
    fun buildGstOnlyPurchase(
        companyId: String,
        financialYearId: String,
        supplierLedgerId: String,
        supplierGstin: String,
        companyStateCode: String,
        placeOfSupply: String,
        lines: List<TradingLineInput>,
        date: LocalDate,
        partyGstRegistrationStatus: GstRegistrationStatus?,
        /** See [buildSale]'s parameter of the same name. */
        pricingMode: GstPricingMode = GstPricingMode.EXCLUSIVE
    ): List<GstTransaction> {
        require(lines.isNotEmpty()) { "At least one line item is required." }
        // Rule 31 (Purchase/RCM Foundation): the same authoritative backstop `build()` enforces for
        // the accounting-integrated Purchase path - RCM only ever valid on a Taxable line.
        require(lines.none { it.chargeType == GstChargeType.REVERSE_CHARGE && it.supplyNature != GstSupplyNature.NORMAL }) {
            "Reverse charge requires a Taxable line - it cannot be combined with Zero Rated/Exempt/Nil Rated."
        }
        val groupId = UUID.randomUUID().toString()
        return lines.mapIndexed { index, line ->
            val lineTaxable = netTaxable(line, pricingMode)
            val breakdown = GstCalculationEngine.calculateDetailed(
                GstTransactionFacts(
                    taxableAmount = lineTaxable,
                    gstRatePercent = line.gstRatePercent,
                    cessRatePercent = line.cessRatePercent,
                    supplierStateCode = companyStateCode,
                    placeOfSupply = placeOfSupply,
                    supplyNature = line.supplyNature
                )
            )
            GstTransaction(
                gstTransactionId = UUID.randomUUID().toString(),
                companyId = companyId,
                financialYearId = financialYearId,
                voucherId = null,
                voucherType = VoucherType.PURCHASE,
                partyLedgerId = supplierLedgerId,
                partyGstin = supplierGstin,
                placeOfSupply = placeOfSupply,
                supplyType = breakdown.supplyType,
                itemId = line.itemId,
                hsnSacCode = line.hsnSacCode,
                quantity = line.quantity,
                taxableAmount = breakdown.taxableAmount,
                gstRatePercent = line.gstRatePercent,
                cgst = breakdown.cgstAmount,
                sgst = breakdown.sgstAmount,
                igst = breakdown.igstAmount,
                cess = breakdown.cessAmount,
                direction = GstDirection.INPUT,
                lineOrder = index + 1,
                chargeType = line.chargeType,
                supplyNature = line.supplyNature,
                transactionGroupId = groupId,
                transactionDate = date,
                partyGstRegistrationStatus = partyGstRegistrationStatus
            )
        }
    }

    /**
     * D1b - GST-only Credit Note (against a GST-only Sale) / Debit Note (against a GST-only
     * Purchase). The voucherless counterpart of [buildNote]'s GST-transaction reversal: since a
     * GST-only transaction has no [JournalItem]/[VoucherStockLine] to reverse, this only replicates
     * that function's GST-row semantics - NEGATED taxable/CGST/SGST/IGST/CESS at the SAME direction
     * as the original (never a new opposite-direction transaction; see [buildNote]'s own KDoc for
     * why this, and not a naive full-field negation, is the correct GST-return representation), with
     * [noteVoucherType] stamped onto every row exactly as [buildNote] already does. [partyGstin]/
     * [GstTransaction.partyGstRegistrationStatus] are carried forward from each original row
     * unchanged (the note describes an adjustment against that exact original transaction, not a
     * new independent supply) - only [GstTransaction.transactionDate] (the note's own posting date)
     * and the identifiers ([gstTransactionId]/[GstTransaction.transactionGroupId]) are new.
     */
    fun buildGstOnlyNote(
        noteVoucherType: VoucherType,
        originalGstTransactions: List<GstTransaction>,
        date: LocalDate
    ): List<GstTransaction> {
        require(originalGstTransactions.isNotEmpty()) { "The original GST-only transaction has no lines to reverse." }
        val groupId = UUID.randomUUID().toString()
        return originalGstTransactions.mapIndexed { index, gt ->
            gt.copy(
                gstTransactionId = UUID.randomUUID().toString(),
                voucherId = null,
                voucherType = noteVoucherType,
                taxableAmount = -gt.taxableAmount,
                cgst = -gt.cgst,
                sgst = -gt.sgst,
                igst = -gt.igst,
                cess = -gt.cess,
                lineOrder = index + 1,
                transactionGroupId = groupId,
                transactionDate = date
            )
        }
    }

    /**
     * D1a (Company Mode + Account-Only Sale/Purchase) - a Sale for a company whose
     * [com.example.accounting.domain.company.AccountingMode] is `ACCOUNT_ONLY` (no inventory
     * tracking): Party ledger + Sales ledger + a single amount, no Item/Quantity/Rate, no
     * [com.example.accounting.domain.inventory.VoucherStockLine]s, no GST calculation/
     * [GstTransaction]s. A plain two-line balanced posting, the same shape [build] produces for its
     * party+trade lines, just without the item-driven tax/stock machinery this company has no use
     * for. Deliberately a separate, small function (mirrors [buildGstOnlySale]'s own precedent)
     * rather than threading a nullable-item branch through [build], which stays exactly as
     * item-driven as before for every inventory-tracking company.
     */
    fun buildAccountOnlySale(
        voucherId: String, companyId: String, financialYearId: String,
        customerLedgerId: String, customerName: String,
        salesLedgerId: String, salesLedgerName: String,
        amount: Money
    ): TradingWorkflowResult = buildAccountOnly(
        isSale = true, voucherId = voucherId, companyId = companyId, financialYearId = financialYearId,
        partyLedgerId = customerLedgerId, partyName = customerName,
        tradeLedgerId = salesLedgerId, tradeLedgerName = salesLedgerName, amount = amount
    )

    /** D1a - Purchase counterpart of [buildAccountOnlySale]; see its doc comment. */
    fun buildAccountOnlyPurchase(
        voucherId: String, companyId: String, financialYearId: String,
        supplierLedgerId: String, supplierName: String,
        purchaseLedgerId: String, purchaseLedgerName: String,
        amount: Money
    ): TradingWorkflowResult = buildAccountOnly(
        isSale = false, voucherId = voucherId, companyId = companyId, financialYearId = financialYearId,
        partyLedgerId = supplierLedgerId, partyName = supplierName,
        tradeLedgerId = purchaseLedgerId, tradeLedgerName = purchaseLedgerName, amount = amount
    )

    private fun buildAccountOnly(
        isSale: Boolean, voucherId: String, companyId: String, financialYearId: String,
        partyLedgerId: String, partyName: String,
        tradeLedgerId: String, tradeLedgerName: String,
        amount: Money
    ): TradingWorkflowResult {
        require(amount.isPositive) { "Amount must be greater than zero." }
        val partyLineType = if (isSale) DrCr.DEBIT else DrCr.CREDIT
        val tradeLineType = if (isSale) DrCr.CREDIT else DrCr.DEBIT
        val journalItems = listOf(
            JournalItem(
                itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
                financialYearId = financialYearId, ledgerId = partyLedgerId, ledgerName = partyName,
                type = partyLineType, amount = amount,
                narration = if (isSale) "Customer invoice debit" else "Supplier bill credit", lineOrder = 1
            ),
            JournalItem(
                itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
                financialYearId = financialYearId, ledgerId = tradeLedgerId, ledgerName = tradeLedgerName,
                type = tradeLineType, amount = amount,
                narration = if (isSale) "Sales revenue (account-only)" else "Purchase value (account-only)", lineOrder = 2
            )
        )
        return TradingWorkflowResult(journalItems, emptyList(), emptyList(), amount)
    }

    fun buildPurchase(
        voucherId: String, companyId: String, financialYearId: String,
        supplierLedgerId: String, supplierName: String, supplierGstin: String,
        purchaseLedgerId: String, purchaseLedgerName: String,
        companyStateCode: String, placeOfSupply: String,
        lines: List<TradingLineInput>,
        gstLedgers: TradingGstLedgers,
        roundOffLedgerId: String, roundOffLedgerName: String,
        trackInventory: Boolean = true,
        /** See [buildSale]'s parameter of the same name. */
        pricingMode: GstPricingMode = GstPricingMode.EXCLUSIVE
    ): TradingWorkflowResult = build(
        isSale = false, voucherId = voucherId, companyId = companyId, financialYearId = financialYearId,
        partyLedgerId = supplierLedgerId, partyName = supplierName, partyGstin = supplierGstin,
        tradeLedgerId = purchaseLedgerId, tradeLedgerName = purchaseLedgerName,
        companyStateCode = companyStateCode, placeOfSupply = placeOfSupply, lines = lines,
        gstLedgers = gstLedgers, roundOffLedgerId = roundOffLedgerId, roundOffLedgerName = roundOffLedgerName,
        trackInventory = trackInventory, pricingMode = pricingMode
    )

    private fun build(
        isSale: Boolean, voucherId: String, companyId: String, financialYearId: String,
        partyLedgerId: String, partyName: String, partyGstin: String,
        tradeLedgerId: String, tradeLedgerName: String,
        companyStateCode: String, placeOfSupply: String,
        lines: List<TradingLineInput>,
        gstLedgers: TradingGstLedgers,
        roundOffLedgerId: String, roundOffLedgerName: String,
        trackInventory: Boolean = true,
        pricingMode: GstPricingMode = GstPricingMode.EXCLUSIVE
    ): TradingWorkflowResult {
        require(lines.isNotEmpty()) { "At least one line item is required." }
        // Rule 31 (Purchase/RCM Foundation): authoritative backstops, matching the
        // lines.isNotEmpty() convention above. The real, graceful, user-facing check is
        // AccountingViewModel.postTradingDocument's pre-check (never reached in normal use); these
        // exist so any other caller of this pure engine can never silently produce a wrong posting.
        if (isSale) {
            require(lines.none { it.chargeType == GstChargeType.REVERSE_CHARGE }) {
                "Reverse charge is not applicable to a Sale."
            }
        }
        require(lines.none { it.chargeType == GstChargeType.REVERSE_CHARGE && it.supplyNature != GstSupplyNature.NORMAL }) {
            "Reverse charge requires a Taxable line - it cannot be combined with Zero Rated/Exempt/Nil Rated."
        }

        val direction = if (isSale) GstDirection.OUTPUT else GstDirection.INPUT
        val taxLineType = if (isSale) DrCr.CREDIT else DrCr.DEBIT
        val tradeLineType = if (isSale) DrCr.CREDIT else DrCr.DEBIT
        val partyLineType = if (isSale) DrCr.DEBIT else DrCr.CREDIT
        val stockDirection = if (isSale) StockDirection.OUT else StockDirection.IN
        val voucherType = if (isSale) VoucherType.SALES else VoucherType.PURCHASE

        var taxableTotal = Money.ZERO
        // Forward-charge tax only - what the supplier actually bills, and what the ordinary
        // Input/Output duty ledgers receive, exactly as before Rule 31.
        var cgstTotal = Money.ZERO
        var sgstTotal = Money.ZERO
        var igstTotal = Money.ZERO
        var cessTotal = Money.ZERO
        // Rule 31 (Purchase/RCM Foundation): reverse-charge tax, self-assessed by the recipient -
        // never billed by the supplier, never mixed into the forward-charge totals above.
        var rcmCgstTotal = Money.ZERO
        var rcmSgstTotal = Money.ZERO
        var rcmIgstTotal = Money.ZERO
        var resolvedSupplyType = SupplyType.INTRA_STATE

        val stockLines = mutableListOf<VoucherStockLine>()
        val gstTransactions = mutableListOf<GstTransaction>()

        lines.forEachIndexed { index, line ->
            val lineComputed = lineAmounts(line, pricingMode)
            val lineTaxable = lineComputed.taxable
            val breakdown = GstCalculationEngine.calculateDetailed(
                GstTransactionFacts(
                    taxableAmount = lineTaxable,
                    gstRatePercent = line.gstRatePercent,
                    cessRatePercent = line.cessRatePercent,
                    supplierStateCode = companyStateCode,
                    placeOfSupply = placeOfSupply,
                    supplyNature = line.supplyNature
                )
            )
            // UI-06: only a NORMAL-nature line may decide the invoice-level CGST/SGST-vs-IGST
            // routing below - EXPORT/EXEMPT/NIL_RATED lines always resolve to a zero-tax
            // SupplyType.EXPORT/EXEMPT and must never be allowed to overwrite this with a line
            // that comes later in the list. Before this field existed every line was always
            // NORMAL, so this condition was always true and this is a no-op for old behavior;
            // once a line can be non-NORMAL, letting it win here would silently drop the
            // CGST/SGST/IGST postings for every real taxable line already accumulated in this
            // same invoice (the party line's total already includes that tax - the ledger entries
            // for it would simply vanish, unbalancing the voucher).
            if (line.supplyNature == GstSupplyNature.NORMAL) resolvedSupplyType = breakdown.supplyType
            taxableTotal += breakdown.taxableAmount
            cessTotal += breakdown.cessAmount

            // Rule 31: route this line's tax into the forward-charge or RCM bucket - the tax
            // amount itself came from the exact same calculateDetailed() call either way.
            if (line.chargeType == GstChargeType.REVERSE_CHARGE) {
                rcmCgstTotal += breakdown.cgstAmount
                rcmSgstTotal += breakdown.sgstAmount
                rcmIgstTotal += breakdown.igstAmount
            } else {
                cgstTotal += breakdown.cgstAmount
                sgstTotal += breakdown.sgstAmount
                igstTotal += breakdown.igstAmount
            }

            if (trackInventory) {
                stockLines += VoucherStockLine(
                    lineId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
                    financialYearId = financialYearId, itemId = line.itemId, itemName = line.itemName,
                    direction = stockDirection, quantity = line.quantity, rate = line.rate,
                    amount = lineTaxable, lineOrder = index + 1, discount = lineComputed.discountAmount
                )
            }

            gstTransactions += GstTransaction(
                gstTransactionId = UUID.randomUUID().toString(), companyId = companyId, financialYearId = financialYearId,
                voucherId = voucherId, voucherType = voucherType,
                partyLedgerId = partyLedgerId, partyGstin = partyGstin, placeOfSupply = placeOfSupply,
                supplyType = breakdown.supplyType, itemId = line.itemId, hsnSacCode = line.hsnSacCode,
                quantity = line.quantity, taxableAmount = breakdown.taxableAmount, gstRatePercent = line.gstRatePercent,
                cgst = breakdown.cgstAmount, sgst = breakdown.sgstAmount, igst = breakdown.igstAmount, cess = breakdown.cessAmount,
                direction = direction, lineOrder = index + 1, chargeType = line.chargeType,
                supplyNature = line.supplyNature,
                // D1b: the accounting-integrated path already has an unambiguous correlation id -
                // its own real voucherId - so this is just that same value, never a second one.
                transactionGroupId = voucherId
            )
        }

        // Rule 31: the amount owed to the party excludes reverse-charge tax entirely - the
        // supplier never billed it, so it is never part of what the recipient owes them. Only
        // taxable value + forward-charge tax + cess enters the invoice total/Round Off, exactly as
        // before RCM existed.
        val rawTotal = taxableTotal + cgstTotal + sgstTotal + igstTotal + cessTotal
        val roundOff = RoundOffEngine.roundInvoiceTotal(rawTotal)

        val journalItems = mutableListOf<JournalItem>()
        var order = 1

        journalItems += JournalItem(
            itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
            financialYearId = financialYearId, ledgerId = partyLedgerId, ledgerName = partyName,
            type = partyLineType, amount = roundOff.roundedTotal,
            narration = if (isSale) "Customer invoice debit" else "Supplier bill credit", lineOrder = order++
        )
        journalItems += JournalItem(
            itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
            financialYearId = financialYearId, ledgerId = tradeLedgerId, ledgerName = tradeLedgerName,
            type = tradeLineType, amount = taxableTotal,
            narration = if (isSale) "Taxable sales revenue" else "Taxable purchase value", lineOrder = order++
        )

        fun taxLine(ref: LedgerRef, amount: Money, type: DrCr, label: String) {
            if (amount.isPositive) {
                journalItems += JournalItem(
                    itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
                    financialYearId = financialYearId, ledgerId = ref.ledgerId, ledgerName = ref.name,
                    type = type, amount = amount, narration = label, lineOrder = order++
                )
            }
        }

        val directionLabel = if (isSale) "Output" else "Input"
        when (resolvedSupplyType) {
            SupplyType.INTRA_STATE -> {
                taxLine(if (isSale) gstLedgers.outputCgst else gstLedgers.inputCgst, cgstTotal, taxLineType, "$directionLabel CGST")
                taxLine(if (isSale) gstLedgers.outputSgst else gstLedgers.inputSgst, sgstTotal, taxLineType, "$directionLabel SGST")
            }
            SupplyType.INTER_STATE -> {
                taxLine(if (isSale) gstLedgers.outputIgst else gstLedgers.inputIgst, igstTotal, taxLineType, "$directionLabel IGST")
            }
            SupplyType.EXPORT, SupplyType.EXEMPT -> { /* zero-rated/exempt: nothing to post */ }
        }
        taxLine(gstLedgers.cess, cessTotal, taxLineType, "CESS")

        // Rule 31 (Purchase/RCM Foundation): the reverse-charge pair is fully self-balancing (the
        // exact same amount posted to both sides) - it never needs Round Off and never touches the
        // supplier-payable line. Uses the same resolvedSupplyType already resolved above, since RCM
        // is only ever valid on a NORMAL-nature line (enforced by the require() above), and a
        // NORMAL line's geography-derived supply type is shared across the whole invoice.
        when (resolvedSupplyType) {
            SupplyType.INTRA_STATE -> {
                taxLine(gstLedgers.rcmInputCgst, rcmCgstTotal, DrCr.DEBIT, "RCM Input CGST")
                taxLine(gstLedgers.rcmLiabilityCgst, rcmCgstTotal, DrCr.CREDIT, "RCM Liability CGST")
                taxLine(gstLedgers.rcmInputSgst, rcmSgstTotal, DrCr.DEBIT, "RCM Input SGST")
                taxLine(gstLedgers.rcmLiabilitySgst, rcmSgstTotal, DrCr.CREDIT, "RCM Liability SGST")
            }
            SupplyType.INTER_STATE -> {
                taxLine(gstLedgers.rcmInputIgst, rcmIgstTotal, DrCr.DEBIT, "RCM Input IGST")
                taxLine(gstLedgers.rcmLiabilityIgst, rcmIgstTotal, DrCr.CREDIT, "RCM Liability IGST")
            }
            SupplyType.EXPORT, SupplyType.EXEMPT -> { /* RCM never applies here - the rcm totals are zero (require() above enforces NORMAL nature) */ }
        }

        // Round Off (Priority 7): the party line already carries the rounded total while the
        // trade+tax lines sum to the raw total, so whichever side is now short needs the
        // difference posted to Round Off. Sale's party line is the Debit side; Purchase's is Credit
        // - the sign flips accordingly.
        if (roundOff.roundOffAmount.paise != 0L) {
            val type = if (isSale) {
                if (roundOff.roundOffAmount.isPositive) DrCr.CREDIT else DrCr.DEBIT
            } else {
                if (roundOff.roundOffAmount.isPositive) DrCr.DEBIT else DrCr.CREDIT
            }
            journalItems += JournalItem(
                itemId = UUID.randomUUID().toString(), voucherId = voucherId, companyId = companyId,
                financialYearId = financialYearId, ledgerId = roundOffLedgerId, ledgerName = roundOffLedgerName,
                type = type, amount = roundOff.roundOffAmount.abs(), narration = "Invoice total rounding adjustment", lineOrder = order++
            )
        }

        return TradingWorkflowResult(journalItems, stockLines, gstTransactions, roundOff.roundedTotal)
    }

    /**
     * Builds a Credit Note (against an original Sale) or Debit Note (against an original Purchase)
     * as a full reversal of the original document (Phase 5, Priority 1) - opposite-sign journal
     * lines on the same ledgers, opposite-direction stock lines at the SAME quantity/rate as the
     * original (so the stock value removed/added by the original posting is restored exactly), and
     * GST-transaction rows carrying NEGATED amounts at the SAME direction as the original (the
     * standard GST-return representation: a credit/debit note nets against the original outward/
     * inward supply figure, it does not become a new opposite-direction transaction - confirmed
     * correct by [com.example.accounting.data.repository.AccountingRepository.buildGstReturnSections],
     * which buckets purely by direction/supplyType/partyGstin, so this was never changed). The
     * original voucher/journal items/stock lines/GST transactions are never read back INTO this
     * function's output - only used to derive it; nothing about the original row is ever modified.
     *
     * Rule 32A hardening - [noteVoucherType] (CREDIT_NOTE/DEBIT_NOTE, supplied by the caller, the
     * same value it puts on the actual [com.example.accounting.domain.accounting.Voucher]) is now
     * stamped onto every copied GST row too. Before this, `gt.copy()` silently inherited the
     * ORIGINAL Sale/Purchase's `voucherType`, so a Credit Note's GST rows still read `SALES` -
     * harmless for today's section bucketing (which never reads this field) but a latent trap for
     * any future CDNR-specific reporting that would naturally filter by it.
     */
    fun buildNote(
        noteVoucherId: String,
        noteVoucherType: VoucherType,
        originalJournalItems: List<JournalItem>,
        originalStockLines: List<VoucherStockLine>,
        originalGstTransactions: List<GstTransaction>
    ): TradingWorkflowResult {
        val journalItems = originalJournalItems.mapIndexed { index, item ->
            item.copy(
                itemId = UUID.randomUUID().toString(),
                voucherId = noteVoucherId,
                type = if (item.type == DrCr.DEBIT) DrCr.CREDIT else DrCr.DEBIT,
                narration = "Reversal: ${item.narration}".trim(),
                lineOrder = index + 1
            )
        }
        val stockLines = originalStockLines.mapIndexed { index, line ->
            line.copy(
                lineId = UUID.randomUUID().toString(),
                voucherId = noteVoucherId,
                direction = if (line.direction == StockDirection.IN) StockDirection.OUT else StockDirection.IN,
                lineOrder = index + 1
            )
        }
        val gstTransactions = originalGstTransactions.mapIndexed { index, gt ->
            gt.copy(
                gstTransactionId = UUID.randomUUID().toString(),
                voucherId = noteVoucherId,
                voucherType = noteVoucherType,
                taxableAmount = -gt.taxableAmount,
                cgst = -gt.cgst,
                sgst = -gt.sgst,
                igst = -gt.igst,
                cess = -gt.cess,
                lineOrder = index + 1,
                // D1b: the note is its own real voucher, so it is its own correlation id - same
                // "transactionGroupId mirrors the real voucherId" rule as the rest of this path.
                transactionGroupId = noteVoucherId
            )
        }
        val totalAmount = originalJournalItems.filter { it.type == DrCr.DEBIT }.fold(Money.ZERO) { acc, i -> acc + i.amount }
        return TradingWorkflowResult(journalItems, stockLines, gstTransactions, totalAmount)
    }
}
