package com.example.accounting.presentation.features.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.application.voucher.VoucherDraft
import com.example.accounting.application.voucher.VoucherDraftLine
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.presentation.components.SectionCard

/**
 * Phase 7J UI: review queue for [VoucherDraft]s - the Draft/Suggestion/Review/Post workflow the
 * UX spec requires for imports/OCR, and the general voucher-draft path Money-tab quick actions can
 * also create. Posting always goes through `AccountingViewModel.postVoucherDraft`, a direct
 * delegation to `VoucherManagementServiceImpl.postDraft` -> the existing, unmodified
 * `AccountingRepository.postVoucher` - never a second posting path.
 */
@Composable
fun VoucherDraftReviewScreen(
    drafts: List<VoucherDraft>,
    onBack: () -> Unit,
    onSelect: (VoucherDraft) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(4.dp))
            Text("Pending Reviews", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }
        if (drafts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Nothing pending review", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                items(drafts, key = { it.draftId }) { draft ->
                    SectionCard(
                        onClick = { onSelect(draft) },
                        title = draft.voucherType.displayName,
                        subtitle = "${draft.date} • ${draft.lines.size} line(s)"
                    ) {
                        if (draft.narration.isNotBlank()) {
                            Text(draft.narration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherDraftEditorScreen(
    draft: VoucherDraft,
    ledgers: List<Ledger>,
    onBack: () -> Unit,
    onSaveLines: (VoucherDraft, List<VoucherDraftLine>) -> Unit,
    onPost: (VoucherDraft) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var lines by remember(draft.draftId) { mutableStateOf(if (draft.lines.isEmpty()) listOf(DraftLineForm()) else draft.lines.map { it.toForm() }) }
    val ledgersMap = ledgers.associateBy { it.ledgerId }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(4.dp))
            Text("Review ${draft.voucherType.displayName}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(modifier = Modifier.height(12.dp))

        lines.forEachIndexed { index, line ->
            SectionCard(elevated = true) {
                var ledgerDropdownExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = ledgerDropdownExpanded, onExpandedChange = { ledgerDropdownExpanded = it }) {
                    OutlinedTextField(
                        value = ledgersMap[line.ledgerId]?.name ?: "Select ledger",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Ledger") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ledgerDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("draft_line_ledger_$index")
                    )
                    ExposedDropdownMenu(expanded = ledgerDropdownExpanded, onDismissRequest = { ledgerDropdownExpanded = false }) {
                        ledgers.forEach { ledger ->
                            DropdownMenuItem(text = { Text(ledger.name) }, onClick = {
                                lines = lines.toMutableList().also { it[index] = line.copy(ledgerId = ledger.ledgerId) }
                                ledgerDropdownExpanded = false
                            })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = line.type == DrCr.DEBIT, onClick = { lines = lines.toMutableList().also { it[index] = line.copy(type = DrCr.DEBIT) } }, label = { Text("Debit") })
                    FilterChip(selected = line.type == DrCr.CREDIT, onClick = { lines = lines.toMutableList().also { it[index] = line.copy(type = DrCr.CREDIT) } }, label = { Text("Credit") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = line.amountInput,
                        onValueChange = { lines = lines.toMutableList().also { l -> l[index] = line.copy(amountInput = it) } },
                        label = { Text("Amount") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { lines = lines.toMutableList().also { it.removeAt(index) } }, enabled = lines.size > 1) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove line")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(onClick = { lines = lines + DraftLineForm() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add line")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onDiscard(draft.draftId) }, modifier = Modifier.weight(1f)) { Text("Discard") }
            OutlinedButton(
                onClick = { onSaveLines(draft, lines.toDomainLines()) },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
            Button(
                onClick = {
                    val saved = draft.copy(lines = lines.toDomainLines())
                    onSaveLines(draft, saved.lines)
                    onPost(saved)
                },
                enabled = lines.all { it.ledgerId.isNotBlank() && it.amountInput.isNotBlank() },
                modifier = Modifier.weight(1f)
            ) { Text("Post") }
        }
    }
}

private data class DraftLineForm(
    val ledgerId: String = "",
    val type: DrCr = DrCr.DEBIT,
    val amountInput: String = "",
    val narration: String = ""
)

private fun VoucherDraftLine.toForm() = DraftLineForm(ledgerId, type, Money.fromPaise(amountPaise).formatPlain(), narration)

private fun List<DraftLineForm>.toDomainLines(): List<VoucherDraftLine> =
    mapIndexed { index, f -> VoucherDraftLine(ledgerId = f.ledgerId, type = f.type, amountPaise = Money.parse(f.amountInput).paise, narration = f.narration, lineOrder = index) }
