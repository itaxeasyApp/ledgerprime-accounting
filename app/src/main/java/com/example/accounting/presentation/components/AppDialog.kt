package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.presentation.theme.Radius
import com.example.accounting.presentation.theme.Spacing

/**
 * Phase UI-03: the one generic modal-dialog shell - consolidates the title/scrollable-content/
 * Cancel+Confirm-actions layout every `Create*Dialog.kt` file (`CreatePartyDialog`,
 * `CreateLedgerDialog`, `CreateStockItemDialog`, `CreateBankUpiProfileDialog`,
 * and formerly `CreateCompanyDialog`) already hand-builds identically. [content] holds only the dialog's own
 * form fields - this component has no knowledge of what any specific dialog collects, per "widgets
 * accept data through parameters, never hardcoded business data." Existing dialogs are not
 * retrofitted in this pass (out of scope - all five already work and are already tested).
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel",
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = Radius.shapeXl,
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg - Spacing.xs)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(Spacing.md))

                content()

                Spacer(modifier = Modifier.height(Spacing.lg - Spacing.xs))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton(text = dismissLabel, style = ActionButtonStyle.TEXT, onClick = onDismiss)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    ActionButton(text = confirmLabel, enabled = confirmEnabled, onClick = onConfirm)
                }
            }
        }
    }
}
