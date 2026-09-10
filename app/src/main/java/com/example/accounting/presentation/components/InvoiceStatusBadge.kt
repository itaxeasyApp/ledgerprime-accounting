package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.invoice.InvoiceStatus

/**
 * Payment-status chip for a Sale/Purchase - the one UI surface for [InvoiceStatus], a pure
 * derivation ([com.example.accounting.domain.invoice.InvoiceStatusEngine]) that was previously
 * computed nowhere in the UI. Never a stored field, never settable - purely a label over
 * `outstandingPaise`/`totalAmountPaise`/`isCancelled`/`dueDate`, all real data the voucher and its
 * settlements already carry.
 */
@Composable
fun InvoiceStatusBadge(status: InvoiceStatus, modifier: Modifier = Modifier) {
    val (label, containerColor, contentColor) = when (status) {
        InvoiceStatus.DRAFT -> Triple("Draft", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        InvoiceStatus.POSTED -> Triple("Unpaid", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
        InvoiceStatus.PARTIALLY_PAID -> Triple("Partially Paid", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary)
        InvoiceStatus.PAID -> Triple("Paid", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        InvoiceStatus.OVERDUE -> Triple("Overdue", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        InvoiceStatus.CANCELLED -> Triple("Cancelled", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = containerColor, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = contentColor),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
