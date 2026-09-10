package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.example.accounting.presentation.theme.Radius
import com.example.accounting.presentation.theme.Spacing

/**
 * Phase UI-03: promoted from `DashboardScreen.kt` (previously a Dashboard-only, if already public,
 * composable) into the shared component set - same behavior/appearance, zero visual change (still
 * [Radius.md], matching the pre-promotion literal `RoundedCornerShape(12.dp)` exactly). Business
 * data (which actions exist, their colors, what they do) stays entirely in the caller - this
 * component only ever renders one already-decided action.
 */
@Composable
fun QuickAction(
    title: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        // Radius.shapeLg (14dp), same as StatCard/the Dashboard's "Reports" card - explicit
        // follow-up ("dashboard container are not similar"): every Dashboard container now shares
        // one corner radius and one border treatment, not three independently-tuned looks.
        shape = Radius.shapeLg,
        color = containerColor,
        // A low-alpha dark stroke, not a fixed theme token - this app's `colorScheme.outline`
        // (`PurpleGrayOutline`, #C9C2D6) is a pale lavender-gray that visually disappears against
        // these same-toned pastel container colors (confirmed live on-device: no visible ring on
        // any tile with either `outline` or `outlineVariant`). onSurface at low alpha stays a
        // visible dark ring against every one of this row's colors, not just the near-white ones -
        // the same [DashboardCardBorder] token StatCard/the Reports card now also use.
        border = DashboardCardBorder(),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = contentColor, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = contentColor,
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
