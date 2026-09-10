package com.example.accounting.presentation.features.datatools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.dataimport.ImportFileFormat
import com.example.accounting.domain.dataimport.ImportResult
import com.example.accounting.domain.dataimport.ImportRowSuggestion
import com.example.accounting.domain.dataimport.ImportSuggestionType
import com.example.accounting.presentation.components.SectionCard

/**
 * Phase 7J UI: "Import" - CSV/JSON import, strictly File -> Parser -> Validation -> Draft/
 * Suggestion -> User Review -> Explicit Create per the UX spec's Section 9/10. Nothing here ever
 * calls `createParty`/`createLedger`/`createStockItem` directly - only
 * `AccountingViewModel.reviewAndCreateImportRow` (one suggestion at a time, human-triggered) does.
 *
 * The OCR "Scan Document" entry point that used to live here was removed - every scan type now has
 * a contextual home on the screen it belongs to instead (Sales/Purchases/Profile/Money - see
 * docs/59_CONTEXTUAL_OCR_ENTRY_POINTS.md, docs/CORRECTIONS_LOG.md), and its review step is now a
 * modal `OcrReviewDialog` shown from `MainAppScreen` itself rather than embedded in this one screen
 * - a scan started from Sales must be reviewable without navigating here first.
 */
@Composable
fun DataToolsScreen(
    lastImportResult: ImportResult?,
    lastImportRowOutcomes: Map<Int, String>,
    /** Import-review group-picker fix - the company's real, already-existing Groups (same list
     * [com.example.accounting.presentation.components.CreateLedgerDialog] uses), so a LEDGER
     * suggestion can be filed under one of them by a human choice, never by trusting whatever
     * string the imported file's own "group"/"groupid" column happened to contain. */
    groups: List<AccountGroup>,
    onPickCsvFile: () -> Unit,
    onPickJsonFile: () -> Unit,
    /** Third parameter is the reviewer's chosen Group id for a LEDGER row (from the picker below),
     * always null for PARTY/STOCK_ITEM rows, which need no group. */
    onReviewAndCreateRow: (ImportRowSuggestion, ImportSuggestionType, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Import Data", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

        SectionCard(elevated = true, title = "Import Party/Ledger/Item data") {
            Text(
                "Pick a CSV or JSON file - every row becomes a suggestion for you to review before anything is created.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickCsvFile) { Text("Pick CSV") }
                OutlinedButton(onClick = onPickJsonFile) { Text("Pick JSON") }
            }
        }

        if (lastImportResult != null) {
            Text(
                "Review: ${lastImportResult.suggestions.size} suggestion(s) from ${lastImportResult.sourceFileName}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(lastImportResult.suggestions, key = { it.rowNumber }) { suggestion ->
                    ImportRowCard(suggestion, lastImportRowOutcomes[suggestion.rowNumber], groups, onReviewAndCreateRow)
                }
            }
        }
    }
}

/**
 * Import-review group-picker fix - a LEDGER suggestion used to be created straight from whatever
 * raw "group"/"groupid" value the imported file happened to contain, which had to already be this
 * app's own internal groupId string (e.g. "GRP_BANK_OD_COMP123") - never something a real external
 * Balance Sheet export would contain, so correctly classifying an imported Bank OD/Loan/etc. ledger
 * was effectively impossible. Clicking "Create as LEDGER" now reveals a dropdown of the company's
 * real, already-existing Groups (same list [com.example.accounting.presentation.components.CreateLedgerDialog]
 * uses) instead of creating immediately - the file's own group column is never trusted blindly.
 * PARTY/STOCK_ITEM rows need no group and are still created immediately on tap, unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRowCard(
    suggestion: ImportRowSuggestion,
    outcome: String?,
    groups: List<AccountGroup>,
    onReviewAndCreateRow: (ImportRowSuggestion, ImportSuggestionType, String?) -> Unit
) {
    var pickingGroupForLedger by remember(suggestion.rowNumber) { mutableStateOf(false) }
    var selectedGroupId by remember(suggestion.rowNumber) { mutableStateOf<String?>(null) }
    var groupDropdownExpanded by remember(suggestion.rowNumber) { mutableStateOf(false) }

    SectionCard(title = "Row ${suggestion.rowNumber}", subtitle = "Suggested: ${suggestion.suggestionType.name}") {
        suggestion.fieldValues.entries.take(4).forEach { (key, value) ->
            Text("$key: $value", style = MaterialTheme.typography.bodySmall)
        }
        if (suggestion.validationWarnings.isNotEmpty()) {
            Text(suggestion.validationWarnings.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            outcome != null -> Text(outcome, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            pickingGroupForLedger -> {
                Text(
                    "Pick the existing Account Group this ledger belongs under:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                val selectedGroup = groups.firstOrNull { it.groupId == selectedGroupId }
                ExposedDropdownMenuBox(
                    expanded = groupDropdownExpanded,
                    onExpandedChange = { groupDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedGroup?.let { "${it.name} (${it.primaryGroup.displayName})" } ?: "Select Group",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = groupDropdownExpanded,
                        onDismissRequest = { groupDropdownExpanded = false }
                    ) {
                        groups.forEach { grp ->
                            DropdownMenuItem(
                                text = { Text("${grp.name} (${grp.primaryGroup.displayName})") },
                                onClick = { selectedGroupId = grp.groupId; groupDropdownExpanded = false }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { pickingGroupForLedger = false; selectedGroupId = null }) { Text("Cancel") }
                    Button(
                        onClick = { onReviewAndCreateRow(suggestion, ImportSuggestionType.LEDGER, selectedGroupId) },
                        enabled = selectedGroupId != null
                    ) { Text("Confirm & Create Ledger") }
                }
            }
            else -> {
                // Step 6 live-device fix - a plain Row here squeezed all three buttons to fit the
                // available width instead of scrolling, and "Create as STOCK_ITEM" (the longest
                // label) rendered its text one character per line, unreadable and barely tappable.
                // Same proven fix as VoucherDetailDialog's own action row: fillMaxWidth +
                // horizontalScroll lets every button keep its natural width and the row scrolls
                // instead of compressing.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ImportSuggestionType.entries.forEach { type ->
                        OutlinedButton(
                            onClick = {
                                if (type == ImportSuggestionType.LEDGER) {
                                    pickingGroupForLedger = true
                                } else {
                                    onReviewAndCreateRow(suggestion, type, null)
                                }
                            }
                        ) { Text("Create as ${type.name}") }
                    }
                }
            }
        }
    }
}
