package com.example.accounting.presentation.features.ledgers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCode2
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.domain.inventory.StockItem
import com.example.accounting.domain.reports.LedgerStatementReport
import com.example.accounting.presentation.viewmodel.AccountingUiState

enum class CoaTab(val label: String) {
    LEDGERS("Ledgers"),
    GROUPS("Groups Tree"),
    ITEMS("Items")
}

@Composable
fun ChartOfAccountsScreen(
    uiState: AccountingUiState,
    onLedgerClick: (Ledger) -> Unit,
    onBackFromStatement: () -> Unit,
    onOpenCreateLedger: () -> Unit,
    onOpenCreateStockItem: () -> Unit = {},
    onDeleteLedger: ((Ledger) -> Unit)? = null,
    /** Architecture correction - ledger editing was previously reachable only from the Money tab's
     * Cash/Bank list; every ledger (Customer/Supplier/expense/etc.) shown here now gets the same
     * Edit affordance, opening [com.example.accounting.presentation.components.CreateLedgerDialog]
     * in edit mode. */
    onEditLedger: ((Ledger) -> Unit)? = null,
    /** Architecture correction (real Group hierarchy) - opens [com.example.accounting.presentation.components.CreateGroupDialog]
     * so a company can create its own Group (nested under any existing System or User Group),
     * completing Primary Group -> System Group -> User Group -> Ledger - the data model and
     * repository already supported this, it just had no UI reachable from anywhere before. */
    onOpenCreateGroup: (() -> Unit)? = null,
    /** Phase 7J UI: Items only shows when the company is [com.example.accounting.domain.company.AccountingMode.ACCOUNT_WITH_INVENTORY]
     * - the single gating point for this screen (mirrors every other Items-related gate, all
     * reading `AccountingUiState.currentCompany?.accountingMode` via the same
     * `isInventoryEnabled` check, never re-derived per call site). */
    showItemsTab: Boolean = true,
    /** Phase 7J UI: pure utility, no accounting logic - `QrBarcodeManagementService.generateForStockItem`. */
    onGenerateBarcode: (String) -> Unit = {},
    /** Phase 7J UI fix: pure utility, no accounting logic - `QrBarcodeManagementService.scanImage`
     * via `AccountingViewModel.scanBarcodeImage`. Was implemented in the ViewModel but had no UI
     * entry point anywhere in the app before this fix. */
    onScanBarcode: () -> Unit = {},
    /** Phase 7J UI fix: discoverability hint for the Account-Mode toggle - shown only when Items is
     * hidden, since a hidden tab gives the user no clue the setting exists. Navigates to the
     * existing, untouched "Accounting Setup" section of Settings - never a second toggle. */
    onOpenAccountingSetup: (() -> Unit)? = null,
    /** D2: opens the existing [com.example.accounting.presentation.components.VoucherDetailDialog]
     * for a ledger-statement row's voucher - the row only carries [com.example.accounting.domain.reports.LedgerStatementRow.voucherId],
     * never a full [com.example.accounting.domain.accounting.Voucher], so resolution against
     * `uiState.vouchers` happens at the call site, same as every other `onVoucherClick` site. */
    onVoucherClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val visibleTabs = remember(showItemsTab) { CoaTab.entries.filter { it != CoaTab.ITEMS || showItemsTab } }
    var selectedCoaTab by remember { mutableStateOf(CoaTab.LEDGERS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroupFilter by remember { mutableStateOf<PrimaryGroup?>(null) }
    // Zero-balance, no-entry ledgers are hidden by default (Phase 4.5, Section E.1) - "hidden" is
    // never "deleted"; this is a UI default only, the underlying master data is untouched.
    var showZeroBalanceLedgers by remember { mutableStateOf(false) }

    val statement = uiState.selectedLedgerStatement

    if (statement != null) {
        // Detailed Ledger Statement View
        LedgerStatementDetailView(
            statement = statement,
            onBack = onBackFromStatement,
            onVoucherClick = onVoucherClick,
            modifier = modifier
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // Top Section: Segmented Navigation - only ever the tabs AccountingMode allows
            // (Phase 7J UI gating fix; ITEMS omitted entirely, never shown-then-disabled).
            val effectiveTab = if (selectedCoaTab in visibleTabs) selectedCoaTab else CoaTab.LEDGERS
            TabRow(
                selectedTabIndex = visibleTabs.indexOf(effectiveTab).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().testTag("coa_tab_row")
            ) {
                visibleTabs.forEach { tab ->
                    when (tab) {
                        CoaTab.LEDGERS -> Tab(
                            selected = effectiveTab == CoaTab.LEDGERS,
                            onClick = { selectedCoaTab = CoaTab.LEDGERS },
                            text = { Text("Ledgers (${uiState.ledgers.size})") },
                            icon = { Icon(Icons.Default.List, contentDescription = "Ledgers") }
                        )
                        CoaTab.GROUPS -> Tab(
                            selected = effectiveTab == CoaTab.GROUPS,
                            onClick = { selectedCoaTab = CoaTab.GROUPS },
                            text = { Text("Groups (${uiState.groups.size})") },
                            icon = { Icon(Icons.Default.AccountTree, contentDescription = "Groups Hierarchy") }
                        )
                        CoaTab.ITEMS -> Tab(
                            selected = effectiveTab == CoaTab.ITEMS,
                            onClick = { selectedCoaTab = CoaTab.ITEMS },
                            text = { Text("Items (${uiState.stockItems.size})") },
                            icon = { Icon(Icons.Default.Inventory2, contentDescription = "Items") }
                        )
                    }
                }
            }

            if (!showItemsTab && onOpenAccountingSetup != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAccountingSetup() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Selling items? Turn on inventory tracking in Company Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            when (effectiveTab) {
                CoaTab.LEDGERS -> {
                    // Chart of Accounts Browser
                    val filteredLedgers = remember(uiState.ledgers, selectedGroupFilter, searchQuery, showZeroBalanceLedgers) {
                        uiState.ledgers.filter { led ->
                            val matchesGroup = selectedGroupFilter == null || led.primaryGroup == selectedGroupFilter
                            val matchesQuery = searchQuery.isBlank() ||
                                led.name.contains(searchQuery, ignoreCase = true) ||
                                led.groupName.contains(searchQuery, ignoreCase = true) ||
                                led.gstin.contains(searchQuery, ignoreCase = true) ||
                                led.code.contains(searchQuery, ignoreCase = true)
                            // Zero-balance, no-entry ledgers hidden by default (Section E.1) unless
                            // explicitly requested via the "Show all" chip, or actively matched by search.
                            val hasBalance = led.currentBalance.isPositive || led.openingBalance.isPositive
                            val matchesVisibility = showZeroBalanceLedgers || hasBalance || searchQuery.isNotBlank()
                            matchesGroup && matchesQuery && matchesVisibility
                        }
                    }
                    val hiddenZeroBalanceCount = uiState.ledgers.size - uiState.ledgers.count { it.currentBalance.isPositive || it.openingBalance.isPositive }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search ledger account or group...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ledger_search_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Primary Group Filter Chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    FilterChip(
                                        selected = selectedGroupFilter == null,
                                        onClick = { selectedGroupFilter = null },
                                        label = { Text("All Accounts (${uiState.ledgers.size})") }
                                    )
                                }
                                if (hiddenZeroBalanceCount > 0) {
                                    item {
                                        FilterChip(
                                            selected = showZeroBalanceLedgers,
                                            onClick = { showZeroBalanceLedgers = !showZeroBalanceLedgers },
                                            label = { Text(if (showZeroBalanceLedgers) "Hide unused accounts" else "Show unused ($hiddenZeroBalanceCount)") }
                                        )
                                    }
                                }
                                items(PrimaryGroup.entries.toTypedArray()) { group ->
                                    val count = uiState.ledgers.count { it.primaryGroup == group }
                                    FilterChip(
                                        selected = selectedGroupFilter == group,
                                        onClick = { selectedGroupFilter = if (selectedGroupFilter == group) null else group },
                                        label = { Text("${group.displayName} ($count)") }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // List of Ledgers
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 80.dp)
                            ) {
                                items(filteredLedgers) { ledger ->
                                    LedgerRowCard(
                                        ledger = ledger,
                                        onClick = { onLedgerClick(ledger) },
                                        onEdit = onEditLedger?.let { edit -> { edit(ledger) } },
                                        onDelete = if (!ledger.isSystem && onDeleteLedger != null) {
                                            { onDeleteLedger(ledger) }
                                        } else null
                                    )
                                }
                            }
                        }

                        FloatingActionButton(
                            onClick = onOpenCreateLedger,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 20.dp, end = 20.dp)
                                .testTag("add_ledger_fab")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Ledger")
                        }
                    }
                }

                CoaTab.GROUPS -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupsHierarchyView(
                            groups = uiState.groups,
                            ledgers = uiState.ledgers,
                            onLedgerClick = onLedgerClick
                        )
                        if (onOpenCreateGroup != null) {
                            FloatingActionButton(
                                onClick = onOpenCreateGroup,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = 20.dp, end = 20.dp)
                                    .testTag("add_group_fab")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Group")
                            }
                        }
                    }
                }

                CoaTab.ITEMS -> {
                    ItemsListView(items = uiState.stockItems, onOpenCreateStockItem = onOpenCreateStockItem, onGenerateBarcode = onGenerateBarcode, onScanBarcode = onScanBarcode)
                }
            }
        }
    }
}

/**
 * Stock item browser (Phase 5, Priority 1) - the prerequisite screen for item-driven GST: an item
 * must exist here, with its HSN/SAC and GST rate on file, before it can be picked on a Sale/
 * Purchase line. No delete affordance yet (matches the deletion-restraint pattern used everywhere
 * else in this app - items referenced by posted stock movements must never disappear).
 */
@Composable
fun ItemsListView(
    items: List<StockItem>,
    onOpenCreateStockItem: () -> Unit,
    onGenerateBarcode: (String) -> Unit = {},
    onScanBarcode: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Phase 7J UI fix: "Scan Barcode" was implemented end-to-end in the ViewModel
            // (scanBarcodeImage) but had no UI entry point anywhere - this is the fix.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.TextButton(onClick = onScanBarcode) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Barcode")
                }
            }

            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No items yet", style = MaterialTheme.typography.titleMedium)
                    Text("Add an item to enable item-driven Sale/Purchase billing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(items) { item ->
                        OutlinedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                    Text(
                                        "HSN/SAC ${item.hsnCode.ifBlank { "-" }} - GST ${item.gstRatePercent}% - Unit ${item.unit}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.currentQuantity.format(), style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { onGenerateBarcode(item.itemId) }) {
                                        Icon(Icons.Default.QrCode2, contentDescription = "Generate barcode")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onOpenCreateStockItem,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 20.dp).testTag("add_item_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Item")
        }
    }
}

@Composable
fun GroupsHierarchyView(
    groups: List<AccountGroup>,
    ledgers: List<Ledger>,
    onLedgerClick: (Ledger) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        items(PrimaryGroup.entries.toTypedArray()) { primary ->
            val primaryGroups = groups.filter { it.primaryGroup == primary }
            val primaryLedgers = ledgers.filter { it.primaryGroup == primary }
            PrimaryGroupCard(
                primaryGroup = primary,
                groups = primaryGroups,
                ledgers = primaryLedgers,
                onLedgerClick = onLedgerClick
            )
        }
    }
}

@Composable
fun PrimaryGroupCard(
    primaryGroup: PrimaryGroup,
    groups: List<AccountGroup>,
    ledgers: List<Ledger>,
    onLedgerClick: (Ledger) -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }

    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = primaryGroup.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Natural: ${primaryGroup.naturalBalance.code} • ${groups.size} Groups • ${ledgers.size} Ledgers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand"
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider()
                    groups.forEach { grp ->
                        val childLedgers = ledgers.filter { it.groupId == grp.groupId }
                        GroupRowItem(
                            group = grp,
                            childLedgers = childLedgers,
                            onLedgerClick = onLedgerClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GroupRowItem(
    group: AccountGroup,
    childLedgers: List<Ledger>,
    onLedgerClick: (Ledger) -> Unit
) {
    var showChildren by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChildren = !showChildren },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📁", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${childLedgers.size} a/c",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (childLedgers.isNotEmpty()) {
                        Icon(
                            imageVector = if (showChildren) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (showChildren && childLedgers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    childLedgers.forEach { led ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLedgerClick(led) }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• ${led.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${led.currentBalance.formatPlain()} ${led.currentBalanceType.code}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun LedgerRowCard(
    ledger: Ledger,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("ledger_item_${ledger.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ledger.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Text(
                    text = "${ledger.groupName} • ${ledger.primaryGroup.displayName}${if (ledger.gstin.isNotBlank()) " • GSTIN: ${ledger.gstin}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = ledger.currentBalance.formatPlain(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (ledger.currentBalanceType == DrCr.DEBIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (ledger.currentBalanceType == DrCr.DEBIT) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = if (ledger.currentBalanceType == DrCr.DEBIT) "DEBIT" else "CREDIT",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (onEdit != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Ledger",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (onDelete != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Delete Ledger",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LedgerStatementDetailView(
    statement: LedgerStatementReport,
    onBack: () -> Unit,
    onVoucherClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = statement.ledgerName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Account Ledger Statement & Running Balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Opening & Closing summary banner
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Opening Balance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${statement.openingBalance.formatPlain()} ${statement.openingType.code}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Closing Balance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${statement.closingBalance.formatPlain()} ${statement.closingType.code}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Statement Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Date / Voucher", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.2f))
            Text("Debit", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.8f))
            Text("Credit", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.8f))
            Text("Balance", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        }

        HorizontalDivider()

        if (statement.rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No transactions found for this ledger", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                items(statement.rows) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVoucherClick(row.voucherId) }
                            .testTag("ledger_statement_row_${row.voucherId}")
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text(row.voucherNumber, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            Text("${row.date} • ${row.voucherType}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = if (row.debitAmount.isPositive) row.debitAmount.formatPlain() else "--",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(0.8f)
                        )
                        Text(
                            text = if (row.creditAmount.isPositive) row.creditAmount.formatPlain() else "--",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(0.8f)
                        )
                        Text(
                            text = "${row.runningBalance.formatPlain()} ${row.balanceType.code}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = if (row.balanceType == DrCr.DEBIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}
