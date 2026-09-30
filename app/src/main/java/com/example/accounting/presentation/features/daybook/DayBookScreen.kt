package com.example.accounting.presentation.features.daybook

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Voucher
import com.example.accounting.domain.accounting.VoucherType
import com.example.accounting.presentation.features.dashboard.VoucherSummaryCard
import com.example.accounting.presentation.viewmodel.AccountingUiState

@Composable
fun DayBookScreen(
    uiState: AccountingUiState,
    onVoucherClick: (Voucher) -> Unit,
    onOpenCreateVoucher: (VoucherType) -> Unit,
    onFilterTypeSelected: (VoucherType?) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onPrint: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredVouchers = remember(uiState.vouchers, uiState.selectedVoucherTypeFilter, searchQuery) {
        uiState.vouchers.filter { voucher ->
            val matchesType = uiState.selectedVoucherTypeFilter == null || voucher.voucherType == uiState.selectedVoucherTypeFilter
            val matchesQuery = searchQuery.isBlank() ||
                voucher.voucherNumber.contains(searchQuery, ignoreCase = true) ||
                voucher.narration.contains(searchQuery, ignoreCase = true) ||
                voucher.referenceNumber.contains(searchQuery, ignoreCase = true) ||
                voucher.items.any { it.ledgerName.contains(searchQuery, ignoreCase = true) }
            matchesType && matchesQuery
        }
    }

    val totalTurnover = remember(filteredVouchers) {
        filteredVouchers.fold(Money.ZERO) { acc, v -> acc + v.totalDebits }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchQueryChanged(it)
                },
                placeholder = { Text("Search by voucher #, account, or narration...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("daybook_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Type Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedVoucherTypeFilter == null,
                        onClick = { onFilterTypeSelected(null) },
                        label = { Text("All Vouchers (${uiState.vouchers.size})") }
                    )
                }
                items(VoucherType.entries.toTypedArray()) { type ->
                    val count = uiState.vouchers.count { it.voucherType == type }
                    FilterChip(
                        selected = uiState.selectedVoucherTypeFilter == type,
                        onClick = { onFilterTypeSelected(if (uiState.selectedVoucherTypeFilter == type) null else type) },
                        label = { Text("${type.displayName} ($count)") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Summary Header
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Showing ${filteredVouchers.size} Entries",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Total: ${totalTurnover.format()}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onPrint, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Print, contentDescription = "Print Day Book", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // DayBook List
            if (filteredVouchers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No matching accounting vouchers found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredVouchers) { voucher ->
                        VoucherSummaryCard(
                            voucher = voucher,
                            onClick = { onVoucherClick(voucher) }
                        )
                    }
                }
            }
        }

        // Floating Action Button to Add Voucher
        FloatingActionButton(
            onClick = { onOpenCreateVoucher(VoucherType.PAYMENT) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 20.dp, end = 20.dp)
                .testTag("add_voucher_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Voucher")
        }
    }
}
