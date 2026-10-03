package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.accounting.presentation.theme.Spacing

/** Dashboard "Quick Report" grid width - always exactly four equal columns. */
const val ReportCardColumns = 4

/**
 * The one Quick Report tile. It renders through [QuickAction] so a report tile has exactly the
 * same height, padding, icon size, label token, shape, border and elevation as every other
 * Dashboard tile - this only fixes the neutral report colors in one place.
 */
@Composable
fun ReportCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    QuickAction(
        title = title,
        icon = icon,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        onClick = onClick
    )
}

data class ReportCardSpec(val title: String, val icon: ImageVector, val onClick: () -> Unit)

/** Lays [items] out in rows of [ReportCardColumns] equal-weight [ReportCard]s. */
@Composable
fun ReportCardGrid(items: List<ReportCardSpec>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items.chunked(ReportCardColumns).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                rowItems.forEach { spec ->
                    ReportCard(spec.title, spec.icon, Modifier.weight(1f), spec.onClick)
                }
            }
        }
    }
}
