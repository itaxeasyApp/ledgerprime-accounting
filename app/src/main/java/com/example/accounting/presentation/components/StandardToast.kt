package com.example.accounting.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Mobile Workflow Correction (item 7) - "Do not create separate toast implementations for each
 * feature. Use one reusable notification system." This app already routes every short message
 * through exactly one channel (`AccountingViewModel`'s private `_snackbarEvents` -> the single
 * [SnackbarHostState] `MainAppScreen` owns) - there was never a second, per-feature toast
 * implementation to consolidate. What was missing was Success/Error/Warning visual distinction:
 * every message rendered identically regardless of outcome. Classifying by the message text itself
 * (rather than adding a severity parameter to the ~100 existing `emitMessage(...)` call sites
 * across the ViewModel) keeps this a pure, additive presentation-layer change - zero risk to any
 * existing call site, since none of them change at all.
 */
private enum class ToastSeverity { SUCCESS, ERROR, WARNING }

private val ERROR_MARKERS = listOf(
    "fail", "error", "reject", "could not", "cannot", "unable", "invalid", "not found", "crash"
)
private val WARNING_MARKERS = listOf(
    "must ", "required", "please ", "first.", "select a", "select an", "complete ", "review ", "enter a"
)

private fun classifyToast(message: String): ToastSeverity {
    val lower = message.lowercase()
    return when {
        ERROR_MARKERS.any { lower.contains(it) } -> ToastSeverity.ERROR
        WARNING_MARKERS.any { lower.contains(it) } -> ToastSeverity.WARNING
        else -> ToastSeverity.SUCCESS
    }
}

/** Drop-in replacement for `SnackbarHost(hostState)` - same single [SnackbarHostState] every
 * screen already shares, only the rendering of each [SnackbarData] gains a severity-appropriate
 * color. Success/neutral messages use the default Snackbar look (no change in appearance from
 * before this fix). */
@Composable
fun StandardToastHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data ->
        val severity = classifyToast(data.visuals.message)
        val (container, content) = when (severity) {
            ToastSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            ToastSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            ToastSeverity.SUCCESS -> MaterialTheme.colorScheme.inverseSurface to MaterialTheme.colorScheme.inverseOnSurface
        }
        Snackbar(containerColor = container, contentColor = content) {
            Text(data.visuals.message)
        }
    }
}
