package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.accounting.RoundOffEngine
import com.example.accounting.domain.taxation.gst.GSTRules
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstPricingMode
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransactionFacts
import com.example.accounting.domain.taxation.gst.TaxBreakdown
import java.util.UUID

internal data class LineFormState(
    val key: String = UUID.randomUUID().toString(),
    val itemId: String = "",
    val quantityInput: String = "1",
    val rateInput: String = "",
    /** Trade discount % on this line - reduces the taxable value before GST is computed, exactly
     * like a real invoice's Discount column. Blank/0 is byte-identical to before this field
     * existed. See [com.example.accounting.domain.trading.TradingLineInput.discountPercent]. */
    val discountInput: String = "",
    /** Sub-phase C (Phase 7J GST Integration) - per-line Cess rate %, same manual-entry shape as
     * [discountInput] (no `StockItem.cessRatePercent` field exists to default this from - adding
     * one would be a schema change, out of this sub-phase's "UI/data wiring only" scope). Blank/0
     * is byte-identical to before this field existed. See
     * [com.example.accounting.domain.trading.TradingLineInput.cessRatePercent], which already
     * accepted a real rate end-to-end (calculation, posting, duty ledger) before this UI existed -
     * only the input surface was missing. */
    val cessRateInput: String = "",
    val supplyNature: GstSupplyNature = GstSupplyNature.NORMAL,
    /** Rule 31 (Purchase/RCM Foundation) - only meaningful on a Purchase line with
     * [supplyNature] == NORMAL; [VoucherLineItemCard] only offers the control in that case. */
    val chargeType: GstChargeType = GstChargeType.FORWARD_CHARGE
)

/**
 * Sale/Purchase item-line form used inside [CreateVoucherDialog]. Split into its own file (Lightweight
 * pass) purely to keep `CreateVoucherDialog.kt` from growing into a single giant file - no behavior
 * change from the original inline version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TradingForm(
    isSale: Boolean,
    /** SERVICE-mode audit fix - see [com.example.accounting.presentation.components.CreateVoucherDialog]'s
     * own parameter of the same name. Relabels this form's Sale/Purchase wording to Income/
     * Expenditure for a SERVICE company; never changes which callback fires or what gets posted. */
    isServiceCompany: Boolean = false,
    ledgers: List<Ledger>,
    stockItems: List<StockItem>,
    itemsMap: Map<String, StockItem>,
    companyStateCode: String,
    partyLedgerId: String,
    onPartyLedgerChange: (String) -> Unit,
    tradeLedgerId: String,
    onTradeLedgerChange: (String) -> Unit,
    lines: List<LineFormState>,
    onLinesChange: (List<LineFormState>) -> Unit,
    isDebtorLedger: (Ledger) -> Boolean,
    isCreditorLedger: (Ledger) -> Boolean,
    isSalesLedger: (Ledger) -> Boolean,
    isPurchaseLedger: (Ledger) -> Boolean,
    ledgersMap: Map<String, Ledger>,
    partyDropdownExpanded: Boolean,
    onPartyDropdownExpandedChange: (Boolean) -> Unit,
    tradeDropdownExpanded: Boolean,
    onTradeDropdownExpandedChange: (Boolean) -> Unit,
    onAddNewParty: () -> Unit = {},
    /** Follow-up to the user's Bug #3 device-testing feedback - the Sales/Purchase Account picker
     * had no recovery path when its group had zero ledgers (a hard-blocking error with no way to
     * fix it without leaving the dialog). Mirrors [SettlementForm]'s existing "+ Add New Bank
     * Account" pattern exactly - opens the existing, unmodified `CreateLedgerDialog`, never a
     * second ledger-creation mechanism. */
    onAddNewTradeLedger: () -> Unit = {},
    /** D1a (Company Mode + Account-Only Sale/Purchase) - the single existing gating point
     * ([com.example.accounting.presentation.viewmodel.isInventoryEnabled]) also used everywhere
     * else Item UI is shown/hidden - never re-derived here. When `false` (the company's
     * [com.example.accounting.domain.company.AccountingMode] is `ACCOUNT_ONLY`), the entire
     * Item/Quantity/Rate/Tax-Treatment section is replaced by a single plain Amount field - no
     * Item is ever required, mandatory, or fabricated to satisfy this form. */
    isInventoryEnabled: Boolean = true,
    amountInput: String = "",
    onAmountChange: (String) -> Unit = {},
    /** Accounting-flow audit fix - Inventory Mode must only gate stock/COGS, never whether GST
     * accounting exists. This is the single point that decides whether the Account-Only amount
     * field above is joined by a GST Rate/HSN control - `true` only when the company's own
     * `GstOperatingMode` says GST applies (never inferred from `isInventoryEnabled`). Ignored
     * entirely when `isInventoryEnabled` is true (the item picker already carries GST per line). */
    gstApplicable: Boolean = false,
    gstRateInput: String = "0",
    onGstRateChange: (String) -> Unit = {},
    hsnSacInput: String = "",
    onHsnSacChange: (String) -> Unit = {},
    /** Sub-phase C (Phase 7J GST Integration) - Account-Only's manual CESS rate, same shape as
     * [gstRateInput]/[hsnSacInput] above (no item to source it from). Blank/0 is byte-identical to
     * before this field existed. */
    cessRateInput: String = "",
    onCessRateChange: (String) -> Unit = {},
    /** Centralized GST engine - whole-document GST Inclusive/Exclusive pricing (see
     * [GstPricingMode]). Defaults to EXCLUSIVE, byte-identical to every Sale/Purchase before this
     * toggle existed. Applied identically here (live preview) and in
     * [com.example.accounting.domain.trading.TradingWorkflowEngine] (posting) via the same
     * [GSTRules.extractTaxableFromInclusive] call - never a second inclusive/exclusive formula. */
    pricingMode: GstPricingMode = GstPricingMode.EXCLUSIVE,
    onPricingModeChange: (GstPricingMode) -> Unit = {}
) {
    Text(
        if (isSale) (if (isServiceCompany) "Income - Receipt" else "Sale - Tax Invoice") else (if (isServiceCompany) "Expenditure - Bill" else "Purchase - Supplier Bill"),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    )
    Spacer(modifier = Modifier.height(8.dp))

    // Reinstated per explicit user request (this was previously widened to "every ledger, Debtor/
    // Creditor just sorted to top" - see git history - to cover a straight cash sale/an
    // unregistered income-expense account; the user has since asked twice for the Customer/
    // Supplier picker to show ONLY real Debtor/Creditor-group ledgers on a Sale/Purchase, matching
    // the trade-ledger picker below (already Sales/Purchase-group only) - never a mixed unfiltered
    // list of tax/bank/duty ledgers a party could never actually be). A cash sale or a not-yet-
    // registered party still has "+ Add New Customer/Supplier" right below the list to create one.
    val partyLedgerOptions = remember(ledgers, isSale) {
        ledgers.filter { if (isSale) isDebtorLedger(it) else isCreditorLedger(it) }
    }
    ExposedDropdownMenuBox(expanded = partyDropdownExpanded, onExpandedChange = onPartyDropdownExpandedChange) {
        OutlinedTextField(
            value = ledgersMap[partyLedgerId]?.name ?: if (isSale) "Select Customer / Account" else "Select Supplier / Account",
            onValueChange = {}, readOnly = true,
            label = { Text(if (isSale) "Customer / Account" else "Supplier / Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = partyDropdownExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = partyDropdownExpanded, onDismissRequest = { onPartyDropdownExpandedChange(false) }) {
            partyLedgerOptions.forEach { led ->
                DropdownMenuItem(
                    text = { Text("${led.name} (${led.groupName})") },
                    onClick = { onPartyLedgerChange(led.ledgerId); onPartyDropdownExpandedChange(false) }
                )
            }
            DropdownMenuItem(
                text = { Text("+ Add New ${if (isSale) "Customer" else "Supplier"}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                onClick = { onPartyDropdownExpandedChange(false); onAddNewParty() }
            )
        }
    }

    val selectedPartyLedger = ledgersMap[partyLedgerId]

    // Phase 7J GST Integration, Sub-phase A - the selected party's real GST identity, inline,
    // never a popup/Toast/separate screen. Derived from the ledger's own real gstin field the
    // exact same way CreatePartyDialog now derives it (GSTIN present -> Registered, blank ->
    // Unregistered - never a stored/stale third "Unknown" state), so this can never disagree with
    // what CreatePartyDialog itself would show for the same party. Display only - does not gate
    // posting (that stays exactly as it is today; only the Place of Supply check below does that).
    if (selectedPartyLedger != null) {
        val partyGstin = selectedPartyLedger.gstin.trim()
        val partyIsRegistered = partyGstin.isNotBlank()
        val partyGstinInvalid = partyIsRegistered && !GSTRules.isValidGSTIN(partyGstin)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (partyGstinInvalid) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    if (partyIsRegistered) "GST Registration: Registered" else "GST Registration: Unregistered",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = if (partyGstinInvalid) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (partyIsRegistered) {
                    Text(
                        if (partyGstinInvalid) {
                            "GSTIN: $partyGstin - not a valid GSTIN, fix it on this ${if (isSale) "customer" else "supplier"}'s ledger"
                        } else {
                            "GSTIN: $partyGstin"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (partyGstinInvalid) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Rule 29 (Place of Supply): never silently guess a tax split when the party has no state on
    // file - warn here, and the per-line GST preview below skips the breakdown entirely rather than
    // showing a number computed against a fallback. Posting itself is also blocked for this same
    // reason (see AccountingViewModel.postTradingDocument / postAccountOnlyTradingDocument - both
    // gate on the exact same condition this mirrors: item mode always, Account-Only only once GST
    // actually applies (gstApplicable && a non-zero rate is selected) - an Account-Only posting
    // with No GST selected never computes tax, so Place of Supply is genuinely irrelevant to it).
    val placeOfSupplyMissing = partyLedgerId.isNotBlank() && selectedPartyLedger?.stateCode.isNullOrBlank() &&
        (isInventoryEnabled || (gstApplicable && (gstRateInput.toDoubleOrNull() ?: 0.0) > 0.0))
    if (placeOfSupplyMissing) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Place of Supply cannot be determined - set a State for this ${if (isSale) "customer" else "supplier"} before this can be posted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    val tradeLedgerWord = if (isSale) (if (isServiceCompany) "Income" else "Sales") else (if (isServiceCompany) "Expenditure" else "Purchase")
    ExposedDropdownMenuBox(expanded = tradeDropdownExpanded, onExpandedChange = onTradeDropdownExpandedChange) {
        OutlinedTextField(
            value = ledgersMap[tradeLedgerId]?.name ?: "Select $tradeLedgerWord Ledger",
            onValueChange = {}, readOnly = true,
            label = { Text("$tradeLedgerWord Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tradeDropdownExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = tradeDropdownExpanded, onDismissRequest = { onTradeDropdownExpandedChange(false) }) {
            ledgers.filter { if (isSale) isSalesLedger(it) else isPurchaseLedger(it) }.forEach { led ->
                DropdownMenuItem(text = { Text(led.name) }, onClick = { onTradeLedgerChange(led.ledgerId); onTradeDropdownExpandedChange(false) })
            }
            DropdownMenuItem(
                text = { Text("+ Add New $tradeLedgerWord Ledger", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                onClick = { onTradeDropdownExpandedChange(false); onAddNewTradeLedger() }
            )
        }
    }

    val tradeLedgerOptions = ledgers.filter { if (isSale) isSalesLedger(it) else isPurchaseLedger(it) }
    if (tradeLedgerOptions.isEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(
                "No $tradeLedgerWord account exists yet - create one from Ledgers before this can be posted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    if (!isInventoryEnabled) {
        // D1a: an ACCOUNT_ONLY company has no Item catalog to bill against - a plain amount is
        // the whole line. No Item/Quantity/Rate/Warehouse/Tax-Treatment control is shown, and
        // none is required for the form to be postable (see CreateVoucherDialog's isReady check).
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = amountInput,
            onValueChange = onAmountChange,
            label = { Text(if (isServiceCompany) "$tradeLedgerWord Amount" else if (isSale) "Sale Amount" else "Purchase Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        if (gstApplicable) {
            Spacer(modifier = Modifier.height(10.dp))
            GstPricingModeRow(pricingMode = pricingMode, onPricingModeChange = onPricingModeChange)
            Spacer(modifier = Modifier.height(10.dp))
            Text("GST Rate", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.example.accounting.core.common.Constants.GST_RATES.forEach { rate ->
                    val label = if (rate == 0.0) "No GST" else "${rate.toInt()}%"
                    androidx.compose.material3.FilterChip(
                        selected = gstRateInput.toDoubleOrNull() == rate,
                        onClick = { onGstRateChange(rate.toString()) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            val accountOnlyGstRate = gstRateInput.toDoubleOrNull() ?: 0.0
            if (accountOnlyGstRate > 0.0) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = hsnSacInput,
                    onValueChange = onHsnSacChange,
                    label = { Text("HSN/SAC Code (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                // Sub-phase C (Phase 7J GST Integration) - same manual-rate shape as the item-mode
                // per-line CESS field (no StockItem/account-only equivalent classification exists
                // to default this from), same "on top of GST, not backed out of Inclusive" rule.
                Spacer(modifier = Modifier.height(10.dp))
                val cessRateInputTrimmed = cessRateInput.trim()
                val accountOnlyCessRateInvalid = cessRateInputTrimmed.isNotBlank() &&
                    (cessRateInputTrimmed.toDoubleOrNull() == null || cessRateInputTrimmed.toDouble() < 0.0)
                OutlinedTextField(
                    value = cessRateInput,
                    onValueChange = onCessRateChange,
                    label = { Text("CESS % (Optional, over and above GST)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = accountOnlyCessRateInvalid,
                    supportingText = if (accountOnlyCessRateInvalid) { { Text("Enter a valid CESS rate (0 or more)") } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                // Sub-phase B (Phase 7J GST Integration) - same GstCalculationEngine call the
                // item-mode preview below makes (calculateDetailed), same Inclusive/Exclusive
                // backout GSTRules.extractTaxableFromInclusive already provides - never a second
                // tax formula. Skipped while placeOfSupplyMissing, same rule as item mode: never
                // show a split computed against a guessed Place of Supply.
                if (!placeOfSupplyMissing && selectedPartyLedger != null) {
                    val rawAmount = Money.parse(amountInput.ifBlank { "0" })
                    if (rawAmount.isPositive) {
                        val accountOnlyTaxable = if (pricingMode == GstPricingMode.INCLUSIVE) {
                            GSTRules.extractTaxableFromInclusive(rawAmount, accountOnlyGstRate)
                        } else {
                            rawAmount
                        }
                        val accountOnlyCessRate = (cessRateInputTrimmed.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                        val accountOnlyBreakdown = GstCalculationEngine.calculateDetailed(
                            GstTransactionFacts(
                                taxableAmount = accountOnlyTaxable,
                                gstRatePercent = accountOnlyGstRate,
                                supplierStateCode = companyStateCode,
                                placeOfSupply = selectedPartyLedger.stateCode,
                                supplyNature = GstSupplyNature.NORMAL,
                                cessRatePercent = accountOnlyCessRate
                            )
                        )
                        TradingTotalsSummary(
                            taxable = accountOnlyBreakdown.taxableAmount,
                            cgst = accountOnlyBreakdown.cgstAmount,
                            sgst = accountOnlyBreakdown.sgstAmount,
                            igst = accountOnlyBreakdown.igstAmount,
                            cess = accountOnlyBreakdown.cessAmount
                        )
                    }
                }
            }
        }
        return
    }

    Spacer(modifier = Modifier.height(10.dp))
    GstPricingModeRow(pricingMode = pricingMode, onPricingModeChange = onPricingModeChange)

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Items", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        TextButton(onClick = { onLinesChange(lines + LineFormState()) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Line")
        }
    }
    Spacer(modifier = Modifier.height(6.dp))

    if (stockItems.isEmpty()) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(
                "No items yet - add one from Ledgers > Items before billing ${if (isServiceCompany) "an Income/Expenditure entry" else "a Sale/Purchase"}.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp)
            )
        }
    }

    var runningTaxable = Money.ZERO
    var runningCgst = Money.ZERO
    var runningSgst = Money.ZERO
    var runningIgst = Money.ZERO
    var runningCess = Money.ZERO

    lines.forEachIndexed { index, line ->
        val item = itemsMap[line.itemId]
        val qty = line.quantityInput.toDoubleOrNull() ?: 0.0
        val rate = Money.parse(line.rateInput.ifBlank { "0" })
        val grossLineTaxable = Money.fromPaise((qty * rate.paise).toLong())
        val discountPercent = (line.discountInput.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
        val netLineAmount = if (discountPercent > 0.0) grossLineTaxable - grossLineTaxable.percentage(discountPercent) else grossLineTaxable
        // Centralized GST engine - the ONE place (shared with TradingWorkflowEngine.netTaxable)
        // that turns an Inclusive-priced line into a taxable value; Exclusive is a no-op.
        val lineTaxable = if (pricingMode == GstPricingMode.INCLUSIVE && item != null) {
            GSTRules.extractTaxableFromInclusive(netLineAmount, item.gstRatePercent)
        } else {
            netLineAmount
        }
        // Sub-phase C (Phase 7J GST Integration) - Cess is computed on the same already-extracted
        // taxable value as GST (GstCalculationEngine.calculateDetailed's own cess formula), never
        // backed out of the Inclusive amount a second way - TradingWorkflowEngine.lineAmounts
        // follows this exact same order (extract taxable from GST rate only, then Cess on that).
        val cessRateInput = line.cessRateInput.trim()
        val cessRateInvalid = cessRateInput.isNotBlank() && (cessRateInput.toDoubleOrNull() == null || cessRateInput.toDouble() < 0.0)
        val cessRatePercent = (cessRateInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        var lineCessAmount = Money.ZERO

        if (item != null && !placeOfSupplyMissing) {
            // UI-06: mirrors exactly what TradingWorkflowEngine.build() will compute at posting
            // time (same calculateDetailed call, same supplyNature) - so this preview can never
            // show a taxable total for a line the engine will actually post as zero-tax. When the
            // party's state is unknown (placeOfSupplyMissing), this is skipped entirely rather than
            // computing a guessed split - see the warning banner above.
            val breakdown = GstCalculationEngine.calculateDetailed(
                GstTransactionFacts(
                    taxableAmount = lineTaxable,
                    gstRatePercent = item.gstRatePercent,
                    supplierStateCode = companyStateCode,
                    placeOfSupply = selectedPartyLedger?.stateCode ?: "",
                    supplyNature = line.supplyNature,
                    cessRatePercent = cessRatePercent
                )
            )
            runningTaxable += breakdown.taxableAmount
            runningCgst += breakdown.cgstAmount
            runningSgst += breakdown.sgstAmount
            runningIgst += breakdown.igstAmount
            runningCess += breakdown.cessAmount
            lineCessAmount = breakdown.cessAmount
        }

        VoucherLineItemCard(
            isSale = isSale,
            line = line,
            item = item,
            lineTaxable = lineTaxable,
            discountPercent = discountPercent,
            cessAmount = lineCessAmount,
            cessRateInvalid = cessRateInvalid,
            stockItems = stockItems,
            canRemove = lines.size > 1,
            onLineChange = { updated -> onLinesChange(lines.toMutableList().also { it[index] = updated }) },
            onRemove = { onLinesChange(lines.filterIndexed { i, _ -> i != index }) }
        )
    }

    if (runningTaxable.isPositive) {
        TradingTotalsSummary(
            taxable = runningTaxable,
            cgst = runningCgst,
            sgst = runningSgst,
            igst = runningIgst,
            cess = runningCess
        )
    }
}

/** GST Inclusive/Exclusive pricing choice - Sub-phase B (Phase 7J GST Integration) extracted this
 * out of [TradingForm]'s item-mode-only body unchanged (same two `FilterChip`s, same labels) so
 * the identical markup can also be shown from the Account-Only branch above, without copy-pasting
 * the `Text`/`Row`/`FilterChip` block a second time. */
@Composable
private fun GstPricingModeRow(pricingMode: GstPricingMode, onPricingModeChange: (GstPricingMode) -> Unit) {
    Text("GST Pricing", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.FilterChip(
            selected = pricingMode == GstPricingMode.EXCLUSIVE,
            onClick = { onPricingModeChange(GstPricingMode.EXCLUSIVE) },
            label = { Text("Exclusive of GST", fontSize = 12.sp) }
        )
        androidx.compose.material3.FilterChip(
            selected = pricingMode == GstPricingMode.INCLUSIVE,
            onClick = { onPricingModeChange(GstPricingMode.INCLUSIVE) },
            label = { Text("Inclusive of GST", fontSize = 12.sp) }
        )
    }
}

/** One item line's editable fields - extracted since it repeats once per [LineFormState] in [TradingForm]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoucherLineItemCard(
    isSale: Boolean,
    line: LineFormState,
    item: StockItem?,
    lineTaxable: Money,
    discountPercent: Double = 0.0,
    /** Sub-phase C (Phase 7J GST Integration) - the same parsed/validated rate [TradingForm]
     * already computed for this line's GST breakdown, passed down purely for display (never
     * re-parsed here) so the card's own summary text can never disagree with the running totals. */
    cessAmount: Money = Money.ZERO,
    cessRateInvalid: Boolean = false,
    stockItems: List<StockItem>,
    canRemove: Boolean,
    onLineChange: (LineFormState) -> Unit,
    onRemove: () -> Unit
) {
    var itemDropdownExpanded by remember(line.key) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ExposedDropdownMenuBox(
                    expanded = itemDropdownExpanded, onExpandedChange = { itemDropdownExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = item?.name ?: "Select Item",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Item") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = itemDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = itemDropdownExpanded, onDismissRequest = { itemDropdownExpanded = false }) {
                        stockItems.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text("${candidate.name} (HSN ${candidate.hsnCode}, ${candidate.gstRatePercent}%)") },
                                onClick = {
                                    val defaultRate = if (isSale) candidate.standardSellingPrice else candidate.standardCost
                                    onLineChange(line.copy(itemId = candidate.itemId, rateInput = (defaultRate.paise / 100.0).toString()))
                                    itemDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove line")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = line.quantityInput,
                    onValueChange = { onLineChange(line.copy(quantityInput = it)) },
                    label = { Text("Qty${item?.let { " (${it.unit})" } ?: ""}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = line.rateInput,
                    onValueChange = { onLineChange(line.copy(rateInput = it)) },
                    label = { Text("Rate") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = line.discountInput,
                    onValueChange = { onLineChange(line.copy(discountInput = it)) },
                    label = { Text("Disc %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            if (item != null) {
                Spacer(modifier = Modifier.height(6.dp))
                SelectField(
                    label = "Tax Treatment",
                    options = GstSupplyNature.entries,
                    selectedOption = line.supplyNature,
                    optionLabel = { it.displayLabel },
                    onSelect = {
                        // Rule 31: Reverse Charge only ever makes sense on a Taxable line - moving
                        // away from NORMAL always resets it, rather than leaving a stale invalid combo.
                        onLineChange(line.copy(supplyNature = it, chargeType = if (it == GstSupplyNature.NORMAL) line.chargeType else GstChargeType.FORWARD_CHARGE))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Rule 31 (Purchase/RCM Foundation) - only offered for a Purchase, Taxable line;
                // RCM never applies to a Sale (this app models Sales as outward supply only) and
                // never applies to a zero-tax line (nothing to reverse-charge).
                if (!isSale && line.supplyNature == GstSupplyNature.NORMAL) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectField(
                        label = "Reverse Charge (RCM)",
                        options = GstChargeType.entries,
                        selectedOption = line.chargeType,
                        optionLabel = { if (it == GstChargeType.REVERSE_CHARGE) "Reverse Charge - self-assessed" else "Forward Charge - billed by supplier" },
                        onSelect = { onLineChange(line.copy(chargeType = it)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Sub-phase C (Phase 7J GST Integration) - Cess only ever applies on a Taxable
                // (NORMAL) line, same rule GST itself already follows here (no tax on Zero Rated/
                // Exempt/Nil Rated) - "when applicable" from a line with no per-item Cess
                // classification (no StockItem.cessRatePercent field exists) means a manual rate,
                // shown only where it could ever mean something.
                if (line.supplyNature == GstSupplyNature.NORMAL) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = line.cessRateInput,
                        onValueChange = { onLineChange(line.copy(cessRateInput = it)) },
                        label = { Text("CESS %${if (item.gstRatePercent <= 0.0) " (Optional)" else " (Optional, over and above GST)"}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = cessRateInvalid,
                        supportingText = if (cessRateInvalid) { { Text("Enter a valid CESS rate (0 or more)") } } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                val discountSuffix = if (discountPercent > 0.0) " (after ${discountPercent.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }}% discount)" else ""
                val cessSuffix = if (cessAmount.isPositive) " + CESS ${cessAmount.formatPlain()}" else ""
                Text(
                    text = if (line.supplyNature == GstSupplyNature.NORMAL) {
                        if (line.chargeType == GstChargeType.REVERSE_CHARGE) {
                            "Amount ${lineTaxable.formatPlain()}$discountSuffix - GST ${item.gstRatePercent}%$cessSuffix (Reverse Charge - self-assessed, not billed by supplier) - HSN ${item.hsnCode.ifBlank { "-" }}"
                        } else {
                            "Amount ${lineTaxable.formatPlain()}$discountSuffix - GST ${item.gstRatePercent}%$cessSuffix - HSN ${item.hsnCode.ifBlank { "-" }}"
                        }
                    } else {
                        "Amount ${lineTaxable.formatPlain()}$discountSuffix - ${item.gstRatePercent}% GST (${line.supplyNature.displayLabel} - no tax charged) - HSN ${item.hsnCode.ifBlank { "-" }}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Running Taxable/CGST/SGST/IGST/Round Off/Grand Total preview - shows only the applicable tax
 * columns (CGST+SGST for intra-state, IGST for inter-state - never both, matching what
 * [com.example.accounting.domain.trading.TradingWorkflowEngine] will actually post and what the
 * invoice PDF will actually print). Round Off here calls the exact same
 * [RoundOffEngine.roundInvoiceTotal] the engine calls at posting time - "approx." is gone because
 * this is no longer an approximation.
 */
@Composable
private fun TradingTotalsSummary(taxable: Money, cgst: Money, sgst: Money, igst: Money, cess: Money = Money.ZERO) {
    // Sub-phase C (Phase 7J GST Integration) - Cess folds into Total GST/Round Off/Grand Total
    // exactly as TradingWorkflowEngine's own rawTotal already does (taxableTotal + cgstTotal +
    // sgstTotal + igstTotal + cessTotal) - this preview can never disagree with what actually posts.
    val totalTax = cgst + sgst + igst + cess
    val roundOff = RoundOffEngine.roundInvoiceTotal(taxable + totalTax)
    Spacer(modifier = Modifier.height(4.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Taxable Value:", style = MaterialTheme.typography.bodySmall)
                Text(taxable.formatPlain(), style = MaterialTheme.typography.bodySmall)
            }
            // Same-state -> CGST+SGST; different-state -> IGST - never both, mirroring the posted
            // voucher and the printed invoice exactly.
            if (cgst.isPositive || sgst.isPositive) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CGST:", style = MaterialTheme.typography.bodySmall)
                    Text(cgst.formatPlain(), style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SGST:", style = MaterialTheme.typography.bodySmall)
                    Text(sgst.formatPlain(), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (igst.isPositive) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("IGST:", style = MaterialTheme.typography.bodySmall)
                    Text(igst.formatPlain(), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (cess.isPositive) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CESS:", style = MaterialTheme.typography.bodySmall)
                    Text(cess.formatPlain(), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total GST:", style = MaterialTheme.typography.bodySmall)
                Text(totalTax.formatPlain(), style = MaterialTheme.typography.bodySmall)
            }
            if (roundOff.roundOffAmount.paise != 0L) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Round Off:", style = MaterialTheme.typography.bodySmall)
                    Text(roundOff.roundOffAmount.formatPlain(), style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Grand Total:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                Text(roundOff.roundedTotal.formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
            }
        }
    }
}
