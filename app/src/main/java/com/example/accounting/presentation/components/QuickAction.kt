package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.presentation.theme.DashboardTile
import com.example.accounting.presentation.theme.Spacing

/**
 * Phase UI-03: promoted from `DashboardScreen.kt` (previously a Dashboard-only, if already public,
 * composable) into the shared component set. Business data (which actions exist, their colors,
 * what they do) stays entirely in the caller - this component only ever renders one already-decided
 * action.
 *
 * Dashboard visual pass (new design reference) - every Quick Action tile now shares one neutral
 * "glossy" card shell (soft light surface, softly rounded, thin neutral border) with the action's
 * own [containerColor]/[contentColor] confined to a small icon chip inside it, rather than painting
 * the whole tile in that color - the "colorful icon chip on a neutral card" language the new
 * reference design uses throughout. Purely a rendering change: the data contract
 * (title/icon/containerColor/contentColor/onClick) is unchanged, so every existing call site keeps
 * compiling and every color choice callers already made still shows, just relocated onto the chip.
 */
private val QuickActionShape = RoundedCornerShape(18.dp)

@Composable
fun QuickAction(
    title: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Dashboard card style correction (user-supplied reference) - same white background + Royal
    // Purple outer ring as StatCard, so every Dashboard tile (Quick Actions and Business
    // Snapshot/Quick Report alike) shares one consistent card shell.
    Surface(
        shape = QuickActionShape,
        color = com.example.ui.theme.OffWhiteSurface,
        border = BorderStroke(1.5.dp, com.example.ui.theme.RoyalPurple),
        shadowElevation = 3.dp,
        modifier = modifier.height(DashboardTile.height).clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = containerColor, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(imageVector = icon, contentDescription = title, tint = contentColor, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * New in Phase UI-03: a thin row wrapper so a screen laying out several [QuickAction]s doesn't
 * hand-roll its own `Row` + `weight(1f)` per group (as `DashboardScreen` currently still does,
 * unretrofitted - this wrapper is additive, existing call sites are not migrated in this pass).
 * [items] carries all business data (label/icon/colors/click); this wrapper only arranges them.
 */
data class QuickActionSpec(
    val title: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit
)

@Composable
fun QuickActions(items: List<QuickActionSpec>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items.forEach { spec ->
            QuickAction(
                title = spec.title,
                icon = spec.icon,
                containerColor = spec.containerColor,
                contentColor = spec.contentColor,
                modifier = Modifier.weight(1f),
                onClick = spec.onClick
            )
        }
    }
}
