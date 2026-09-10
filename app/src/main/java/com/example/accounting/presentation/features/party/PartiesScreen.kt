package com.example.accounting.presentation.features.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.party.Party
import com.example.accounting.domain.party.PartyRole
import com.example.accounting.presentation.components.SectionCard

/**
 * Phase 7J UI: Customer or Supplier list, reached from the Sales/Purchases tabs (never its own
 * bottom-nav item, per the UX spec's 5-item nav). Read-only view + create for most Party-specific
 * fields (credit limit/payment terms/contact name - still no general-purpose `updateParty` UI);
 * [Party.isFavorite] is the one exception (Contacts + Favorites correction,
 * docs/CORRECTIONS_LOG.md), via the real, persisted [onToggleFavorite]. Architecture correction:
 * GSTIN/phone/address/opening-balance all live on the underlying Ledger, not the Party record, so
 * [onEditLedger] (when provided) opens the existing generic ledger-edit flow for that party's
 * ledger - a different, already-correct path that was simply never reachable from this screen
 * before.
 */
@Composable
fun PartiesScreen(
    role: PartyRole,
    parties: List<Party>,
    ledgers: List<Ledger>,
    onAddParty: () -> Unit,
    onPartyClick: (Party) -> Unit,
    onEditLedger: ((Ledger) -> Unit)? = null,
    onToggleFavorite: (Party) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val roleLabel = if (role == PartyRole.CUSTOMER) "Customers" else "Suppliers"
    // Favorites first (stable sort - ties keep their original relative order), so a starred
    // Customer/Supplier never gets lost in a long list.
    val filtered = parties.filter { it.role == role && it.isActive }.sortedByDescending { it.isFavorite }
    val ledgersMap = ledgers.associateBy { it.ledgerId }

    Box(modifier = modifier.fillMaxSize()) {
        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(6.dp))
                Text("No $roleLabel yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a $roleLabel to start billing them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filtered, key = { it.partyId }) { party ->
                    val ledger = ledgersMap[party.ledgerId]
                    SectionCard(
                        onClick = { onPartyClick(party) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onToggleFavorite(party) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        if (party.isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                                        contentDescription = if (party.isFavorite) "Unmark favorite" else "Mark favorite",
                                        tint = if (party.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = ledger?.currentBalance?.formatPlain() ?: "--",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                )
                                if (onEditLedger != null && ledger != null) {
                                    IconButton(onClick = { onEditLedger(ledger) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit $roleLabel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(party.displayName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        Text(
                            listOfNotNull(
                                party.entityType.name.lowercase().replaceFirstChar { it.uppercase() },
                                ledger?.gstin?.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddParty,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add $roleLabel")
        }
    }
}
