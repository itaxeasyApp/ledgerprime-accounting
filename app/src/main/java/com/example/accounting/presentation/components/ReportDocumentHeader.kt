package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Explicit design instruction - "real accounting-document appearance" for every report screen
 * (Trial Balance, P&L/Income & Expenditure, Balance Sheet, Cash Flow, Ledger Statement, Ratio
 * Analysis, GST Center): a document-style header - report name centered, then the selected
 * User/Business Profile name, then the Financial Year - followed by a divider, before the report's
 * own particulars/groups/totals begin. [businessName]/[financialYearLabel] are always passed in
 * from already-loaded [com.example.accounting.presentation.viewmodel.AccountingUiState] fields
 * (`businessProfile`/`currentCompany`, `currentFinancialYear`) - this composable never reads a
 * repository/ViewModel itself and never computes a figure, matching every other component in this
 * set. Low density, generous spacing, thin divider, normal font weight - no bold "app UI" chrome.
 */
@Composable
fun ReportDocumentHeader(
    reportName: String,
    businessName: String,
    financialYearLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = reportName,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = businessName,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = financialYearLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * One "document body" line under [ReportDocumentHeader] - a Particulars label on the left and one
 * or two right-aligned amount columns, with visible column separation (a leading vertical rule
 * before each amount column) - the "clear column separation" requirement. [isSubtotal]/[isTotal]
 * only ever change weight/a top divider, never the source of the amount itself (still always
 * passed in already-computed).
 */
@Composable
fun ReportParticularRow(
    label: String,
    amount: String,
    secondaryAmount: String? = null,
    depthLevel: Int = 0,
    isSubtotal: Boolean = false,
    isTotal: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (isTotal) {
            HorizontalDivider(thickness = if (isTotal) 1.5.dp else 1.dp, color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(6.dp))
        } else if (isSubtotal) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = (depthLevel * 16).dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSubtotal || isTotal) FontWeight.Bold else FontWeight.Normal
                ),
                modifier = Modifier.weight(1f)
            )
            if (secondaryAmount != null) {
                Text(
                    text = secondaryAmount,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSubtotal || isTotal) FontWeight.Bold else FontWeight.Normal),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .width(96.dp)
                )
            }
            Text(
                text = amount,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSubtotal || isTotal) FontWeight.Bold else FontWeight.Normal),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(96.dp)
            )
        }
    }
}
