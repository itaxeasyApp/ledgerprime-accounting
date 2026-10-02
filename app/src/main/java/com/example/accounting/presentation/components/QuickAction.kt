package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
        onClick = onClick,
        shape = Radius.shapeLg,
        color = containerColor,
        shadowElevation = DashboardTileElevation,
        border = DashboardCardBorder(),
        modifier = modifier.fillMaxHeight().heightIn(min = DashboardTileMinHeight)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(DashboardTilePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = contentColor, modifier = Modifier.size(DashboardTileIconSize))
            Spacer(modifier = Modifier.height(Spacing.xs))
            FitText(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = contentColor,
                modifier = Modifier.fillMaxWidth()
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
    Row(modifier = modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
