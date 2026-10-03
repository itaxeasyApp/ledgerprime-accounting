package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Height of the main bottom bar's item row (Material's own NavigationBar fixes this at 80dp). */
val CompactNavigationBarHeight = 56.dp

/**
 * A NavigationBar with a shorter item row. Same container color, elevation, system-bar inset and
 * [androidx.compose.material3.NavigationBarItem] content as the Material one - only the fixed
 * 80dp row height is replaced by [CompactNavigationBarHeight].
 */
@Composable
fun CompactNavigationBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        color = NavigationBarDefaults.containerColor,
        tonalElevation = NavigationBarDefaults.Elevation,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(CompactNavigationBarHeight)
                .selectableGroup(),
            content = content
        )
    }
}
