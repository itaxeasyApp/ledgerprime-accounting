package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.core.common.Money
import com.example.accounting.presentation.theme.DashboardTile

/**
 * One shared border style for every Dashboard container. Kept as a distinct function (rather than
 * inlined) since [RecentTransactions]/other Dashboard-adjacent containers outside this pass's scope
 * may still reference it.
 */
@Composable
fun DashboardCardBorder(): BorderStroke = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))

private val StatCardShape = RoundedCornerShape(18.dp)

/**
 * Phase UI-03: promoted from `DashboardScreen.kt`'s previously Dashboard-local `MetricCard` into
 * the shared component set. Renders one already-computed [amount] via [Money.formatPlain] - never a
 * calculation of its own, per "components must not contain accounting calculations."
 *
 * Dashboard visual pass (new design reference) - a softer "glossy" neutral card (rounder corners,
 * thin low-alpha border, light elevation) with [icon] moved into a small colored chip matching
 * [QuickAction]'s own new chip treatment, and [subtitle] now tinted with [iconTint] instead of a
 * flat gray - the reference design colors each card's caption line to match its icon (e.g. a green
 * "You are owed" under Receivables, a red "You owe" under Payables).
 */
@Composable
fun StatCard(
    title: String,
    amount: Money,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    // Dashboard card style correction (user-supplied reference) - white card background with a
    // solid Royal Purple outer ring, a bit more elevation for the "glossy" lift off the page.
    // Icon-chip color, spacing, and typography are unchanged from the prior pass - "nothing else
    // changed" per the same reference.
    Card(
        shape = StatCardShape,
        colors = CardDefaults.cardColors(containerColor = com.example.ui.theme.OffWhiteSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.5.dp, com.example.ui.theme.RoyalPurple),
        modifier = modifier.height(DashboardTile.height)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(shape = RoundedCornerShape(8.dp), color = iconTint.copy(alpha = 0.14f), modifier = Modifier.size(20.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = amount.formatPlain(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1
            )
            // Blank subtitle renders nothing at all (explicit "too much screen for writing the
            // things" follow-up) - not an empty Text still reserving its line's height.
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = iconTint, maxLines = 1)
            }
        }
    }
}
