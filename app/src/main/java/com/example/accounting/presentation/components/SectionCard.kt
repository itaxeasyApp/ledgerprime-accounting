package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase 7J UI: the one shared rectangular-container component for every new screen this phase
 * adds - covers the two shapes the existing screens already repeat by hand (a static
 * `ElevatedCard` form/summary section - see `SettingsAndSyncScreen.kt`'s Company/Accounting-Setup
 * cards - and a tappable `OutlinedCard` list row - see `ChartOfAccountsScreen.kt`'s
 * `LedgerRowCard`). Existing screens are left completely untouched - `SectionCard` is additive,
 * used only by new Phase 7J UI screens.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable { onClick() } else it }

    val inner: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(14.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // weight(1f, fill = false): reserves trailing's own natural width FIRST (an
                    // unweighted sibling in a Row always measures before a weighted one), so a long
                    // subtitle wraps within its own remaining space instead of squeezing trailing
                    // (e.g. a StatusBadge) down to near-zero width, where its text would wrap
                    // letter-by-letter - same fix as BackRow's own title Modifier.weight(1f, fill = false).
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }

    if (elevated) {
        // See StatCard.kt's same fix - ElevatedCard has no `border` param in this Compose
        // Material3 version, and its shadow-only boundary reads as "no outline" on several
        // device/theme combinations; Card (base) reproduces the same look plus a real border.
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = cardModifier
        ) { inner() }
    } else {
        OutlinedCard(shape = shape, modifier = cardModifier) { inner() }
    }
}
