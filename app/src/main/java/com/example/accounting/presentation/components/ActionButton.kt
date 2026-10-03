package com.example.accounting.presentation.components

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Which visual weight this action carries - never a hardcoded color, always the current
 * `MaterialTheme.colorScheme` (Royal Purple primary for [PRIMARY]; the theme's error color for [DESTRUCTIVE]). */
enum class ActionButtonStyle { PRIMARY, SECONDARY, TEXT, DESTRUCTIVE }

/**
 * Phase 7J UI: the one button primitive every new screen's primary/secondary actions use -
 * `Button`/`OutlinedButton`/`TextButton` already pick up the Royal Purple + Off-White theme
 * entirely through `MaterialTheme.colorScheme`; this just standardizes which of the three a given
 * action should be, so screens stop mixing styles ad hoc.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ActionButtonStyle = ActionButtonStyle.PRIMARY,
    enabled: Boolean = true
) {
    when (style) {
        ActionButtonStyle.PRIMARY -> Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
        ActionButtonStyle.SECONDARY -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
        ActionButtonStyle.TEXT -> TextButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
        // Delete/Remove - the theme's existing error/onError tokens, nothing new.
        ActionButtonStyle.DESTRUCTIVE -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) { Text(text) }
    }
}
