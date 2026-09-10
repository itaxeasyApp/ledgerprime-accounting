package com.example.accounting.presentation.features.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.banking.BankUpiProfile
import com.example.accounting.presentation.components.SectionCard

/** Phase 7J UI: Bank/UPI settlement-details list - settlement metadata only, backed by
 * `BankUpiProfileService` (Phase 7J-B). Never touches a ledger balance. */
@Composable
fun UpiProfilesScreen(
    profiles: List<BankUpiProfile>,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Delete-safety audit fix - this used to remove a bank/UPI profile on a single icon tap with
    // no confirmation at all, the same class of accidental-tap risk already fixed for
    // ledgers/vouchers/business delete elsewhere in this app.
    var pendingDelete by remember { mutableStateOf<BankUpiProfile?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove '${target.bankName}'?") },
            text = { Text("This removes this bank/UPI detail from your business. It does not affect any past transactions. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(target.bankUpiProfileId); pendingDelete = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No bank/UPI details yet", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(profiles, key = { it.bankUpiProfileId }) { profile ->
                    SectionCard(
                        title = profile.bankName,
                        subtitle = "${profile.accountHolderName} • ${profile.accountNumber.takeLast(4).padStart(profile.accountNumber.length, 'X')}",
                        trailing = {
                            IconButton(onClick = { pendingDelete = profile }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    ) {
                        if (profile.upi != null) {
                            Text("UPI: ${profile.upi.upiId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add bank/UPI details")
        }
    }
}
