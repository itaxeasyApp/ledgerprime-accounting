package com.example.accounting.presentation.features.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.RoundOffEngine
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.ActionButtonStyle
import com.example.accounting.presentation.components.Amount
import com.example.accounting.presentation.components.FormField
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.theme.Spacing
import java.time.LocalDate

/**
 * Phase 7J UI: Receive Money / Pay Money / Transfer entry - a simple, single-amount form (no
 * outstanding-invoice allocation; that richer flow stays on the existing `CreateVoucherDialog`,
 * reachable from Home's Quick Actions and Day Book, untouched by this screen). Adds an opt-in
 * "Round off" toggle, OFF by default, computed by calling the existing, frozen
 * `RoundOffEngine.roundInvoiceTotal` directly for a live preview - the same calling pattern
 * `CreateVoucherDialog`'s own GST live-preview already uses - never a second rounding rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyVoucherEntryScreen(
    voucherType: VoucherType,
    ledgers: List<Ledger>,
    onBack: () -> Unit,
    onSubmit: (VoucherType, LocalDate, debitLedgerId: String, creditLedgerId: String, amount: Money, narration: String, refNumber: String, applyRoundOff: Boolean) -> Unit,
    /** Phase 7J UI fix: lets Receive Money/Pay Money open Customer/Supplier creation inline
     * instead of forcing the user back out to the Sales/Purchases tab first - reuses the same
     * `CreatePartyDialog` trigger every other screen already uses, never a second creation path.
     * Null/no-op for Transfer, which has no counterparty (Cash/Bank only). */
    onAddParty: ((PartyRole) -> Unit)? = null,
    /** Bug #3 fix - QR/Barcode scan for the Receive Payment flow, available regardless of
     * Accounting Mode/Inventory setting (never gated by Items tab). Reuses the exact same photo
     * picker + `QrBarcodeAdapter.scanImage` pipeline the Items tab already uses - never a second
     * scan mechanism. Only offered for RECEIPT (Receive Money); null/no-op for Pay Money/Transfer.
     */
    onScanBarcode: (() -> Unit)? = null,
    /** The raw decoded value from the most recent scan requested via [onScanBarcode] - only ever
     * prefills Reference (never overwrites text the user already typed). The user still reviews/
     * edits and explicitly taps the primary action button; nothing here posts anything or creates
     * a party/ledger. Caller clears this once applied (see [onScannedValueConsumed]). */
    scannedBarcodeValue: String? = null,
    onScannedValueConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val title = when (voucherType) {
        VoucherType.RECEIPT -> "Receive Money"
        VoucherType.PAYMENT -> "Pay Money"
        else -> "Transfer"
    }

    val cashBankLedgers = remember(ledgers) {
        ledgers.filter { it.groupId.startsWith(StandardSystemGroups.BANK_GROUP_ID) || it.groupId.startsWith(StandardSystemGroups.CASH_GROUP_ID) }
    }
    // PARTY/COUNTERPARTY audit fix - Customer/Supplier is an optional role, never a mandatory
    // gate: this used to restrict Receive Money/Pay Money to Debtors-group/Creditors-group
    // ledgers only, which made it impossible to receive against (e.g.) a general Income ledger
    // or pay a general Expense ledger directly unless it had also been registered as a formal
    // Customer/Supplier. Every non-Cash/Bank ledger is offered (Cash/Bank is excluded only
    // because it is already the other side of this voucher, picked separately above); Debtor/
    // Creditor-group ledgers (the common case) are sorted to the top for convenience.
    val counterpartyLedgers = remember(ledgers, voucherType) {
        val preferredGroupId = when (voucherType) {
            VoucherType.RECEIPT -> StandardSystemGroups.DEBTORS_GROUP_ID
            VoucherType.PAYMENT -> StandardSystemGroups.CREDITORS_GROUP_ID
            else -> null
        }
        if (preferredGroupId == null) {
            cashBankLedgers
        } else {
            ledgers.filterNot {
                it.groupId.startsWith(StandardSystemGroups.BANK_GROUP_ID) || it.groupId.startsWith(StandardSystemGroups.CASH_GROUP_ID)
            }.sortedByDescending { it.groupId.startsWith(preferredGroupId) }
        }
    }

    var cashBankLedgerId by remember(voucherType) { mutableStateOf(cashBankLedgers.firstOrNull()?.ledgerId ?: "") }
    var counterpartyLedgerId by remember(voucherType) { mutableStateOf(counterpartyLedgers.firstOrNull()?.ledgerId ?: "") }
    var amountInput by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    var refNumber by remember { mutableStateOf("") }
    var applyRoundOff by remember { mutableStateOf(false) }
    var cashBankExpanded by remember { mutableStateOf(false) }
    var counterpartyExpanded by remember { mutableStateOf(false) }
    // Post-safety fix - same local double-tap latch as CreateVoucherDialog's Post button.
    var isSubmitting by remember { mutableStateOf(false) }

    val amountMoney = remember(amountInput) { Money.parse(amountInput) }
    val roundOffPreview = remember(amountMoney, applyRoundOff) {
        if (applyRoundOff) RoundOffEngine.roundInvoiceTotal(amountMoney) else null
    }

    // Follow-up fix - a visible summary of what a Receive Payment scan actually found/applied
    // (the user's own feedback: a silent prefill with "no display and details" is not enough).
    var lastScanSummary by remember { mutableStateOf<String?>(null) }

    // Bug #3 fix - apply a Receive Payment barcode scan result: a matched Customer (by GSTIN
    // found in the scanned text, against already-loaded `ledgers` - no new lookup/service) only
    // ever pre-selects an existing counterparty (never creates one); the raw scanned value only
    // ever prefills Reference when it is still blank (never overwrites what the user already
    // typed). Never posts, never creates a party/ledger.
    LaunchedEffect(scannedBarcodeValue) {
        val scanned = scannedBarcodeValue ?: return@LaunchedEffect
        if (voucherType == VoucherType.RECEIPT) {
            val normalizedScan = scanned.trim().uppercase()
            val matchedCustomer = counterpartyLedgers.firstOrNull { it.gstin.isNotBlank() && normalizedScan.contains(it.gstin.trim().uppercase()) }
            if (matchedCustomer != null) counterpartyLedgerId = matchedCustomer.ledgerId
            if (refNumber.isBlank()) refNumber = scanned
            lastScanSummary = buildString {
                append(if (matchedCustomer != null) "Receiving from: ${matchedCustomer.name}" else "No Customer matched this code")
                append(" • Ref: $scanned")
            }
        }
        onScannedValueConsumed()
    }

    // Receive Money: Dr Cash/Bank, Cr Customer. Pay Money: Dr Supplier, Cr Cash/Bank.
    // Transfer: Dr destination account, Cr source account (both Cash/Bank).
    val (debitLedgerId, creditLedgerId) = when (voucherType) {
        VoucherType.RECEIPT -> cashBankLedgerId to counterpartyLedgerId
        VoucherType.PAYMENT -> counterpartyLedgerId to cashBankLedgerId
        else -> counterpartyLedgerId to cashBankLedgerId
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(Spacing.md))

        FormField(
            value = amountInput,
            onValueChange = { amountInput = it },
            label = "Amount",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        val cashBankLabel = if (voucherType == VoucherType.PAYMENT) "Pay from" else if (voucherType == VoucherType.RECEIPT) "Receive into" else "Transfer from"
        LedgerDropdown(
            label = cashBankLabel,
            options = cashBankLedgers,
            selectedId = cashBankLedgerId,
            expanded = cashBankExpanded,
            onExpandedChange = { cashBankExpanded = it },
            onSelect = { cashBankLedgerId = it }
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        val counterpartyLabel = when (voucherType) {
            VoucherType.RECEIPT -> "From Customer"
            VoucherType.PAYMENT -> "To Supplier"
            else -> "Transfer to"
        }
        val counterpartyRole = when (voucherType) {
            VoucherType.RECEIPT -> PartyRole.CUSTOMER
            VoucherType.PAYMENT -> PartyRole.SUPPLIER
            else -> null
        }
        val addNewCounterpartyLabel = when (counterpartyRole) {
            PartyRole.CUSTOMER -> "Add new Customer"
            PartyRole.SUPPLIER -> "Add new Supplier"
            null -> null
        }
        LedgerDropdown(
            label = counterpartyLabel,
            options = counterpartyLedgers,
            selectedId = counterpartyLedgerId,
            expanded = counterpartyExpanded,
            onExpandedChange = { counterpartyExpanded = it },
            addNewLabel = if (onAddParty != null) addNewCounterpartyLabel else null,
            onAddNew = counterpartyRole?.let { role -> { onAddParty?.invoke(role) } },
            onSelect = { counterpartyLedgerId = it }
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        FormField(value = refNumber, onValueChange = { refNumber = it }, label = "Reference (optional)", modifier = Modifier.fillMaxWidth())
        if (voucherType == VoucherType.RECEIPT && onScanBarcode != null) {
            TextButton(onClick = { lastScanSummary = null; onScanBarcode() }) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan QR / Barcode")
            }
            // Follow-up fix - a visible confirmation of what the scan found/applied, never a
            // silent prefill. Dismissible; the primary action button below is still a separate,
            // explicit step - nothing here posts anything.
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
        Spacer(modifier = Modifier.height(Spacing.sm))
        FormField(value = narration, onValueChange = { narration = it }, label = "Note (optional)", modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(Spacing.md))

        SectionCard(elevated = true) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Round off", style = MaterialTheme.typography.bodyLarge)
                    Text("Adjust to the nearest rupee; the difference is recorded separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = applyRoundOff, onCheckedChange = { applyRoundOff = it })
            }
            if (roundOffPreview != null && roundOffPreview.roundOffAmount.paise != 0L) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Rounded amount", style = MaterialTheme.typography.bodySmall)
                    Amount(roundOffPreview.roundedTotal, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Round off amount", style = MaterialTheme.typography.bodySmall)
                    Amount(roundOffPreview.roundOffAmount, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.lg))

        ActionButton(
            text = title,
            style = ActionButtonStyle.PRIMARY,
            enabled = amountMoney.isPositive && debitLedgerId.isNotBlank() && creditLedgerId.isNotBlank() && !isSubmitting,
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick@{
                if (isSubmitting) return@onClick
                isSubmitting = true
                onSubmit(voucherType, LocalDate.now(), debitLedgerId, creditLedgerId, amountMoney, narration, refNumber, applyRoundOff)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerDropdown(
    label: String,
    options: List<Ledger>,
    selectedId: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    /** Phase 7J UI fix: an optional "Add new Customer/Supplier" row pinned above the ledger list -
     * without this, a picker with zero (or an incomplete set of) parties was a dead end. */
    addNewLabel: String? = null,
    onAddNew: (() -> Unit)? = null
) {
    val selectedName = options.firstOrNull { it.ledgerId == selectedId }?.name ?: "Select"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (addNewLabel != null && onAddNew != null) {
                DropdownMenuItem(
                    text = { Text(addNewLabel, color = MaterialTheme.colorScheme.primary) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onExpandedChange(false); onAddNew() }
                )
                if (options.isNotEmpty()) HorizontalDivider()
            }
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onExpandedChange(false) },
                    enabled = false
                )
            }
            options.forEach { ledger ->
                DropdownMenuItem(
                    text = { Text(ledger.name) },
                    onClick = { onSelect(ledger.ledgerId); onExpandedChange(false) }
                )
            }
        }
    }
}
