package com.example.accounting.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.core.common.Money
import com.example.accounting.presentation.theme.Radius
import com.example.accounting.presentation.theme.Spacing

/**
 * One shared border style for every Dashboard container (StatCard, QuickAction) - the theme's own
 * theme's own `outlineVariant` (no new color token) so it follows light/dark and stays a quiet
 * hairline - the purple `primary` is kept for icons and actions, not for 24 box outlines.
 */
@Composable
fun DashboardCardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** Shared Dashboard tile metrics - StatCard and QuickAction use these so both card types share one
 * height, icon size, padding, elevation and centered alignment. */
val DashboardTileMinHeight = 80.dp
val DashboardTileIconSize = 20.dp
val DashboardTileElevation = 1.dp
val DashboardTileLabelFontSize = 11.sp
val DashboardTilePadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs)

/**
 * Single-line text that steps its font size down (to [minSize]) until it fits the width, so tight
 * 4-per-row tiles on 320-360dp phones never clip a title or amount mid-glyph.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    minSize: TextUnit = 8.sp
) {
    var size by remember(text, style) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        style = style.copy(fontSize = size),
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && size.value > minSize.value) size = (size.value * 0.92f).sp },
        modifier = modifier
    )
}

/**
 * Renders one already-computed [amount] via [Money.formatPlain] - never a calculation of its own.
 * [onClick] (optional) uses Card's own clickable overload so ripple/touch follow the card shape.
 */
@Composable
fun StatCard(
    title: String,
    amount: Money,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = Radius.shapeLg
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = DashboardTileElevation)
    val tileModifier = modifier.fillMaxHeight().heightIn(min = DashboardTileMinHeight)
    val content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(DashboardTilePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(DashboardTileIconSize))
            Spacer(modifier = Modifier.height(Spacing.xs))
            FitText(title, MaterialTheme.typography.labelSmall, Modifier.fillMaxWidth(), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(Spacing.xs))
            // Indian grouping with the rupee sign (Money.format) - whole rupees on the tile, the exact
            // paise are one tap away in the report. A negative position (e.g. an overdrawn bank) reads red.
            FitText(
                amount.format().removeSuffix(".00"),
                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                Modifier.fillMaxWidth(),
                if (amount.paise < 0) MaterialTheme.colorScheme.error else Color.Unspecified
            )
            // Blank subtitle renders nothing - the row's equal-height rule keeps siblings aligned.
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                FitText(subtitle, MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), Modifier.fillMaxWidth(), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, shape = shape, colors = colors, elevation = elevation, border = DashboardCardBorder(), modifier = tileModifier, content = content)
    } else {
        Card(shape = shape, colors = colors, elevation = elevation, border = DashboardCardBorder(), modifier = tileModifier, content = content)
    }
}
