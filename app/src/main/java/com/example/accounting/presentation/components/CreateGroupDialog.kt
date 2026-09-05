package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.accounting.domain.accounting.AccountGroup

/**
 * Architecture correction (real Group hierarchy) - lets a company create its own Group (a "User
 * Group", [AccountGroup.isSystem] = false) nested under any existing Group, completing the
 * Primary Group -> System Group -> User Group -> Ledger hierarchy the data model
 * ([AccountGroup.parentGroupId]) and [com.example.accounting.data.repository.AccountingRepository.createGroup]
 * already supported but had no UI reachable from anywhere. [parentCandidates] is every existing
 * Group (System or User) the new one may nest under - the new group always inherits its parent's
 * [AccountGroup.primaryGroup] (never separately chosen here), so the hierarchy can never end up
 * with a child whose statutory nature disagrees with its own parent's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupDialog(
    parentCandidates: List<AccountGroup>,
    initialParentGroupId: String? = null,
    onDismiss: () -> Unit,
    onCreateGroup: (name: String, parentGroupId: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf(initialParentGroupId ?: parentCandidates.firstOrNull()?.groupId ?: "") }
    var parentDropdownExpanded by remember { mutableStateOf(false) }
    val parentsById = remember(parentCandidates) { parentCandidates.associateBy { it.groupId } }
    val selectedParent = parentsById[selectedParentId]

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("New Group", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "A sub-group under an existing System or Group account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name *") },
                    supportingText = { Text("e.g. \"HDFC Bank Accounts\" or \"Working Capital CC/OD\"") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                ExposedDropdownMenuBox(
                    expanded = parentDropdownExpanded,
                    onExpandedChange = { parentDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedParent?.let { "${it.name} (${it.primaryGroup.displayName})" } ?: "Select Parent Group",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Under Group *") },
                        supportingText = { Text("Ledgers/vouchers everywhere recognize this new group's nature from its parent - always inherited, never chosen separately") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = parentDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = parentDropdownExpanded,
                        onDismissRequest = { parentDropdownExpanded = false }
                    ) {
                        parentCandidates.forEach { grp ->
                            DropdownMenuItem(
                                text = { Text("${grp.name} (${grp.primaryGroup.displayName})") },
                                onClick = {
                                    selectedParentId = grp.groupId
                                    parentDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onCreateGroup(name, selectedParentId)
                            onDismiss()
                        },
                        enabled = name.isNotBlank() && selectedParentId.isNotBlank(),
                        modifier = Modifier.testTag("submit_group_button")
                    ) {
                        Text("Save Group")
                    }
                }
            }
        }
    }
}
