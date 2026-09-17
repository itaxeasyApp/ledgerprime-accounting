package com.example.accounting.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * Explicit follow-up ("container width height are different... why don't following and making
 * component of it and using in theme") - [com.example.accounting.presentation.components.QuickAction]
 * (icon+label only) and [com.example.accounting.presentation.components.StatCard] (title+icon+
 * amount+optional subtitle) show different kinds of information, so leaving each to size itself
 * from its own content was never going to reliably match - a blank subtitle, a longer title, a
 * two-line label under different font scaling would all silently reintroduce a height mismatch
 * later. One theme-level fixed height, applied to both components directly (not left to each call
 * site to remember), is what actually guarantees every Dashboard tile - Quick Actions, Business
 * Snapshot, and Quick Report alike - renders at the exact same size regardless of which component
 * or how much text it holds.
 *
 * Compact pass ("significantly lower height... compact horizontal bar or low-profile tile style
 * ... reduce top and bottom interior padding, place icons inline with or closely fitted above
 * text") - 108dp down to 72dp, paired with tighter interior padding/spacing in both components
 * (never a font-size change - typography stays exactly as it was) so more of the dashboard fits
 * above the fold without shrinking or clipping either component's text.
 */
object DashboardTile {
    val height = 72.dp
}
