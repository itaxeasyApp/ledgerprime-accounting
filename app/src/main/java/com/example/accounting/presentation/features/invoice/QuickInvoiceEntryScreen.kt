package com.example.accounting.presentation.features.invoice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.StandardSystemGroups
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.domain.taxation.gst.GstPricingMode
import com.example.accounting.presentation.components.LineFormState
import com.example.accounting.presentation.components.TradingForm
import com.example.accounting.presentation.viewmodel.AccountingViewModel
import java.time.LocalDate

/**
 * "Build a real invoice UI, easiest to use by anyone" - a dedicated, full-screen (never a popup)
 * Sale/Purchase entry point. Deliberately NOT a rewrite of [com.example.accounting.presentation.components.CreateVoucherDialog]/
 * [TradingForm]'s calculation or posting logic - both are reused byte-for-byte here (same
 * [TradingForm] item-line UI + GST preview, same `AccountingViewModel.postSaleInvoice`/
 * `postPurchaseBill`/`postAccountOnlySale`/`postAccountOnlyPurchase` posting calls the existing
 * dialog already uses) so there is exactly one place that computes a trading document's GST/total
 * and exactly one place that posts it - never a second, divergent invoice pipeline. What changes is
 * only the chrome around that same form: a full screen instead of a cramped modal, one fixed title
 * (the voucher type is already known from how this screen was opened, so the 8-way type picker is
 * gone), and an always-visible bottom bar showing the live total with one big "Save Invoice" button
 * - no scrolling to hunt for Save on a small dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickInvoiceEntryScreen(
    isSale: Boolean,
    ledgers: List<Ledger>,
    groups: List<AccountGroup> = emptyList(),
    stockItems: List<StockItem> = emptyList(),
    companyStateCode: String = "",
    isInventoryEnabled: Boolean = true,
    gstApplicable: Boolean = false,
    isServiceCompany: Boolean = false,
    onDismiss: () -> Unit,
    onAddNewParty: (PartyRole) -> Unit,
    onAddNewTradeLedger: (Boolean) -> Unit,
    onPostSaleInvoice: (String, String, List<AccountingViewModel.TradingLineForm>, LocalDate, String, String, GstPricingMode) -> Unit,
    onPostPurchaseBill: (String, String, List<AccountingViewModel.TradingLineForm>, LocalDate, String, String, GstPricingMode) -> Unit,
    onPostAccountOnlySale: (String, String, Money, LocalDate, String, String, Double, String, GstPricingMode, Double) -> Unit,
    onPostAccountOnlyPurchase: (String, String, Money, LocalDate, String, String, Double, String, GstPricingMode, Double) -> Unit
) {
    val groupsById = remember(groups) { groups.associateBy { it.groupId } }
    fun isDebtorLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.DEBTORS_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.DEBTORS_GROUP_ID, groupsById)
    fun isCreditorLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.CREDITORS_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.CREDITORS_GROUP_ID, groupsById)
    fun isSalesLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.SALES_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.SALES_GROUP_ID, groupsById)
    fun isPurchaseLedger(ledger: Ledger) = ledger.groupId.startsWith("${StandardSystemGroups.PURCHASE_GROUP_ID}_") ||
        StandardSystemGroups.isUnder(ledger.groupId, StandardSystemGroups.PURCHASE_GROUP_ID, groupsById)

    var isSubmitting by remember { mutableStateOf(false) }
    var partyLedgerId by remember { mutableStateOf("") }
    var tradeLedgerId by remember {
        mutableStateOf(ledgers.firstOrNull { if (isSale) isSalesLedger(it) else isPurchaseLedger(it) }?.ledgerId ?: "")
    }
    var lines by remember { mutableStateOf(listOf(LineFormState())) }
    var pricingMode by remember { mutableStateOf(GstPricingMode.EXCLUSIVE) }
    var amountInput by remember { mutableStateOf("") }
    var accountOnlyGstRateInput by remember { mutableStateOf("0") }
    var accountOnlyHsnSacInput by remember { mutableStateOf("") }
    // Sub-phase C (Phase 7J GST Integration).
    var accountOnlyCessRateInput by remember { mutableStateOf("") }
    var referenceNumber by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    var partyDropdownExpanded by remember { mutableStateOf(false) }
    var tradeDropdownExpanded by remember { mutableStateOf(false) }
    val ledgersMap = remember(ledgers) { ledgers.associateBy { it.ledgerId } }
    val itemsMap = remember(stockItems) { stockItems.associateBy { it.itemId } }
    val amountMoney = remember(amountInput) { Money.parse(amountInput) }

    val isReady = partyLedgerId.isNotBlank() && tradeLedgerId.isNotBlank() &&
        if (isInventoryEnabled) {
            lines.any { it.itemId.isNotBlank() && (it.quantityInput.toDoubleOrNull() ?: 0.0) > 0.0 }
        } else {
            amountMoney.isPositive
        }

    val readyTotal = if (isInventoryEnabled) {
        lines.sumOf { line ->
            val item = itemsMap[line.itemId]
            val qty = line.quantityInput.toDoubleOrNull() ?: 0.0
            val rate = Money.parse(line.rateInput.ifBlank { "0" }).paise
            if (item != null) (qty * rate).toLong() else 0L
        }.let { Money.fromPaise(it) }
    } else {
        amountMoney
    }

    val screenTitle = if (isServiceCompany) {
        if (isSale) "New Income Entry" else "New Expenditure Entry"
    } else {
        if (isSale) "New Sale Invoice" else "New Purchase Bill"
    }

    // Rendered via Dialog(usePlatformDefaultWidth = false) - the same, already-proven overlay
    // mechanism every other create-flow in this app uses (CreateVoucherDialog/CreateLedgerDialog/
    // etc.), so it stacks/dismisses correctly regardless of whatever container MainAppScreen's
    // caller placed it in. The Surface below fills the entire screen with no card/rounded corners/
    // width margin, so visually this reads as a real full screen, never a small modal popup.
    // Manual Column layout (not Scaffold) - mirrors CreateVoucherDialog's own proven structure
    // exactly, since a plain Scaffold's bottomBar slot does not reliably receive navigation-bar
    // insets inside a separate Dialog window (confirmed live on-device: the Save button rendered
    // underneath the system gesture bar with Scaffold). statusBarsPadding()/navigationBarsPadding()
    // applied directly to the header/footer, same as CreateVoucherDialog's own footer Row.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    // systemBarsPadding() alone measured ZERO bottom inset on-device (MIUI/HyperOS, 3-button nav,
    // confirmed via uiautomator: the Save button's bounds always ended exactly at the physical
    // screen edge, fully overlapping the real navigation bar, which then silently swallowed every
    // tap meant for the button). DialogProperties(decorFitsSystemWindows = false) is evidently not
    // enough on its own for a raw Dialog window on this OEM skin. Rather than fight that further,
    // an explicit 48dp floor - Android's own standard 3-button nav bar height - is added on top as
    // a deterministic fallback; systemBarsPadding() is kept too for gesture-nav devices where it
    // may still correctly report a non-zero inset (stacking harmlessly as a bit of extra margin).
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 88.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") }
            Spacer(modifier = Modifier.width(4.dp))
            Text(screenTitle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }
        HorizontalDivider()

        // weight(1f) with the default fill=true (NOT fill=false) - this screen fills the entire
        // display (fillMaxSize(), no CreateVoucherDialog-style 92%-height margin buffer), so the
        // scrollable area must always occupy exactly the remaining space and pin the footer below
        // to that boundary. fill=false (CreateVoucherDialog's own choice, safe there only because
        // its Surface is height-capped) let this footer's position drift with form content length -
        // confirmed live on-device: short content left the footer floating with empty space below
        // it, longer content (after selecting an item) pushed the same footer down far enough to
        // overlap the real navigation bar and swallow taps meant for Save.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            TradingForm(
                isSale = isSale,
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
                onAddNewParty = { onAddNewParty(if (isSale) PartyRole.CUSTOMER else PartyRole.SUPPLIER) },
                onAddNewTradeLedger = { onAddNewTradeLedger(isSale) },
                isInventoryEnabled = isInventoryEnabled,
                amountInput = amountInput,
                onAmountChange = { amountInput = it },
                gstApplicable = gstApplicable,
                gstRateInput = accountOnlyGstRateInput,
                onGstRateChange = { accountOnlyGstRateInput = it },
                hsnSacInput = accountOnlyHsnSacInput,
                onHsnSacChange = { accountOnlyHsnSacInput = it },
                cessRateInput = accountOnlyCessRateInput,
                onCessRateChange = { accountOnlyCessRateInput = it },
                pricingMode = pricingMode,
                onPricingModeChange = { pricingMode = it }
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = referenceNumber, onValueChange = { referenceNumber = it },
                label = { Text("Invoice Number (Optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = narration, onValueChange = { narration = it },
                label = { Text("Note (Optional)") },
                placeholder = { Text("Being goods sold / purchased...") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        HorizontalDivider()
        Surface(color = if (isReady) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReady) {
                        "Ready to Save - Total ${readyTotal.formatPlain()}"
                    } else if (partyLedgerId.isBlank()) {
                        "Select a ${if (isSale) "Customer" else "Supplier"} to continue"
                    } else if (tradeLedgerId.isBlank()) {
                        "Select ${if (isSale) "a Sales" else "a Purchase"} Account to continue"
                    } else {
                        "Add at least one item to continue"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = if (isReady) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Button(
                onClick = onClick@{
                    if (isSubmitting) return@onClick
                    isSubmitting = true
                    when {
                        isInventoryEnabled -> {
                            val postedLines = lines.filter { it.itemId.isNotBlank() }.map {
                                AccountingViewModel.TradingLineForm(
                                    it.itemId, it.quantityInput.toDoubleOrNull() ?: 0.0, Money.parse(it.rateInput.ifBlank { "0" }),
                                    it.supplyNature, it.chargeType, (it.discountInput.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0),
                                    (it.cessRateInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                                )
                            }
                            if (isSale) {
                                onPostSaleInvoice(partyLedgerId, tradeLedgerId, postedLines, LocalDate.now(), referenceNumber, narration, pricingMode)
                            } else {
                                onPostPurchaseBill(partyLedgerId, tradeLedgerId, postedLines, LocalDate.now(), referenceNumber, narration, pricingMode)
                            }
                        }
                        isSale -> onPostAccountOnlySale(partyLedgerId, tradeLedgerId, amountMoney, LocalDate.now(), referenceNumber, narration, accountOnlyGstRateInput.toDoubleOrNull() ?: 0.0, accountOnlyHsnSacInput, pricingMode, (accountOnlyCessRateInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0))
                        else -> onPostAccountOnlyPurchase(partyLedgerId, tradeLedgerId, amountMoney, LocalDate.now(), referenceNumber, narration, accountOnlyGstRateInput.toDoubleOrNull() ?: 0.0, accountOnlyHsnSacInput, pricingMode, (accountOnlyCessRateInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0))
                    }
                    onDismiss()
                },
                enabled = isReady && !isSubmitting,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (isSale) "Save Sale Invoice" else "Save Purchase Bill", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    }
    }
}
