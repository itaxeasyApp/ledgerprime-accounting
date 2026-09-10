package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.accounting.presentation.theme.Radius

/**
 * One shared border style for every Dashboard container (StatCard, QuickAction, the "Reports"
 * card) - explicit follow-up ("dashboard container are not similar"): these previously each tuned
 * their own border color/shape independently (`outlineVariant` here, a bare `outline` or an
 * ad-hoc onSurface alpha elsewhere, 12dp vs 14dp corners), which is exactly what read as
 * inconsistent. A fixed low-alpha dark stroke reads consistently against every one of this app's
 * container colors - the near-white StatCard/Reports-card background as well as QuickAction's
 * colorful tiles (where this app's actual `colorScheme.outline`/`outlineVariant` tokens are pale
 * lavender-grays that visually disappear, confirmed live on-device) - so every container uses this
 * one function rather than picking its own token.
 */
@Composable
fun DashboardCardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))

/**
 * Phase UI-03: promoted from `DashboardScreen.kt`'s previously Dashboard-local `MetricCard` into
 * the shared component set - same behavior/appearance, zero visual change ([Radius.lg] matches the
 * pre-promotion literal `RoundedCornerShape(14.dp)` exactly). Renders one already-computed
 * [amount] via [Money.formatPlain] - never a calculation of its own, per "components must not
 * contain accounting calculations."
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
    // A visible border, not just tonal elevation - ElevatedCard has no `border` parameter in this
    // Compose Material3 version, and its shadow-only boundary is too subtle to read as a container
    // edge on several device/theme combinations (reported as "no outline of container" against
    // these exact Dashboard tiles). Card (base) reproduces ElevatedCard's look (same container tone
    // + 1dp default elevation) while also accepting an explicit border.
    Card(
        shape = Radius.shapeLg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = DashboardCardBorder(),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = amount.formatPlain(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1
            )
            // Blank subtitle renders nothing at all (explicit "too much screen for writing the
            // things" follow-up) - not an empty Text still reserving its line's height.
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
