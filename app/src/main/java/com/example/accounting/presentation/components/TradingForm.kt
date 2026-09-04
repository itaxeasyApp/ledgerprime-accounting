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
import com.example.accounting.domain.taxation.gst.GstCalculationEngine
import com.example.accounting.domain.taxation.gst.GstChargeType
import com.example.accounting.domain.taxation.gst.GstSupplyNature
import com.example.accounting.domain.taxation.gst.GstTransactionFacts
import java.util.UUID

internal data class LineFormState(
    val key: String = UUID.randomUUID().toString(),
    val itemId: String = "",
    val quantityInput: String = "1",
    val rateInput: String = "",
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
    onHsnSacChange: (String) -> Unit = {}
) {
    Text(
        if (isSale) (if (isServiceCompany) "Income - Receipt" else "Sale - Tax Invoice") else (if (isServiceCompany) "Expenditure - Bill" else "Purchase - Supplier Bill"),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    )
    Spacer(modifier = Modifier.height(8.dp))

    // PARTY/COUNTERPARTY audit fix - Customer/Supplier is an optional role, never a mandatory
    // gate: this list used to be filtered down to only Debtors-group (Sale) / Creditors-group
    // (Purchase) ledgers, which made it impossible to post against any other valid existing
    // ledger (e.g. a straight cash sale posted to the Cash ledger, or an income/expense account
    // that was never formally registered as a Party). Every ledger is offered; Debtor/Creditor-
    // group ledgers (the common case) are just sorted to the top for convenience.
    val partyLedgerOptions = remember(ledgers, isSale) {
        ledgers.sortedByDescending { if (isSale) isDebtorLedger(it) else isCreditorLedger(it) }
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
    // Rule 29 (Place of Supply): never silently guess a tax split when the party has no state on
    // file - warn here, and the per-line GST preview below skips the breakdown entirely rather than
    // showing a number computed against a fallback. Posting itself is also blocked for this same
    // reason (see AccountingViewModel.postTradingDocument / AccountingRepository.postGstOnlySale).
    // D1a: an ACCOUNT_ONLY posting never computes GST, so Place of Supply is irrelevant to it -
    // showing this banner ("...before this can be posted") would be misleading since posting is
    // never actually blocked on it for this company mode.
    val placeOfSupplyMissing = isInventoryEnabled && partyLedgerId.isNotBlank() && selectedPartyLedger?.stateCode.isNullOrBlank()
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
            if ((gstRateInput.toDoubleOrNull() ?: 0.0) > 0.0) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = hsnSacInput,
                    onValueChange = onHsnSacChange,
                    label = { Text("HSN/SAC Code (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        return
    }

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
    var runningTax = Money.ZERO

    lines.forEachIndexed { index, line ->
        val item = itemsMap[line.itemId]
        val qty = line.quantityInput.toDoubleOrNull() ?: 0.0
        val rate = Money.parse(line.rateInput.ifBlank { "0" })
        val lineTaxable = Money.fromPaise((qty * rate.paise).toLong())

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
                    supplyNature = line.supplyNature
                )
            )
            runningTaxable += breakdown.taxableAmount
            runningTax += breakdown.totalTax
        }

        VoucherLineItemCard(
            isSale = isSale,
            line = line,
            item = item,
            lineTaxable = lineTaxable,
            stockItems = stockItems,
            canRemove = lines.size > 1,
            onLineChange = { updated -> onLinesChange(lines.toMutableList().also { it[index] = updated }) },
            onRemove = { onLinesChange(lines.filterIndexed { i, _ -> i != index }) }
        )
    }

    if (runningTaxable.isPositive) {
        TradingTotalsSummary(taxable = runningTaxable, tax = runningTax)
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (line.supplyNature == GstSupplyNature.NORMAL) {
                        if (line.chargeType == GstChargeType.REVERSE_CHARGE) {
                            "Amount ${lineTaxable.formatPlain()} - GST ${item.gstRatePercent}% (Reverse Charge - self-assessed, not billed by supplier) - HSN ${item.hsnCode.ifBlank { "-" }}"
                        } else {
                            "Amount ${lineTaxable.formatPlain()} - GST ${item.gstRatePercent}% - HSN ${item.hsnCode.ifBlank { "-" }}"
                        }
                    } else {
                        "Amount ${lineTaxable.formatPlain()} - ${item.gstRatePercent}% GST (${line.supplyNature.displayLabel} - no tax charged) - HSN ${item.hsnCode.ifBlank { "-" }}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Running Taxable/GST/Total preview - extracted from [TradingForm] since it is pure display logic. */
@Composable
private fun TradingTotalsSummary(taxable: Money, tax: Money) {
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("GST:", style = MaterialTheme.typography.bodySmall)
                Text(tax.formatPlain(), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total (approx.):", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                Text((taxable + tax).formatPlain(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
            }
        }
    }
}
