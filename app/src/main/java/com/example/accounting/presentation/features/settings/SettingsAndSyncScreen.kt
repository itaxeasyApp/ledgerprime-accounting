package com.example.accounting.presentation.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.domain.accounting.SyncState
import com.example.accounting.domain.company.AccountingMode
import com.example.accounting.domain.company.BusinessType
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.financialyear.AccountingPeriod
import com.example.accounting.domain.financialyear.FinancialYear
import com.example.accounting.domain.financialyear.PeriodStatus
import com.example.accounting.presentation.viewmodel.AccountingUiState

@Composable
fun SettingsAndSyncScreen(
    uiState: AccountingUiState,
    onCompanySwitch: (Company) -> Unit,
    onOpenCreateCompany: () -> Unit,
    /** Edit-Company fix - previously there was no UI anywhere to change a company's own name/
     * GSTIN/PAN/address/phone/email after creation, so the app-bar/Dashboard's "GSTIN:
     * Unregistered" could never be corrected even after the real GSTIN was known. */
    onEditCompany: (Company) -> Unit = {},
    onTogglePeriodLock: (AccountingPeriod) -> Unit,
    onTriggerSync: () -> Unit,
    onUpdateAccountingConfiguration: (AccountingMode?, BusinessType?) -> Unit = { _, _ -> },
    isCloudSyncLoggedIn: Boolean = false,
    onCloudSyncLogin: (String, String) -> Unit = { _, _ -> },
    onCloudSyncLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentCompany = uiState.currentCompany

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Company Tenant Card
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Company", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text("Switch between businesses you manage here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenCreateCompany) {
                        Icon(Icons.Default.Add, contentDescription = "Add Company", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                uiState.companies.forEach { comp ->
                    val isSelected = comp.companyId == currentCompany?.companyId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onCompanySwitch(comp) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(comp.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                Text(
                                    "GSTIN: ${comp.gstin.ifBlank { "Unregistered" }} • ${comp.stateName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onEditCompany(comp) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit ${comp.name}", modifier = Modifier.size(18.dp))
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Data Sync - moved here from the app-wide top bar (per explicit instruction: a sync
        // status icon has no business being permanently visible on every single screen). Reuses
        // the exact same [onTriggerSync]/[AccountingUiState.pendingSyncCount]/[AccountingUiState.isSyncing]
        // this screen's own param already carried (previously unused here) - no new sync mechanism.
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Data Sync", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (uiState.pendingSyncCount > 0) "${uiState.pendingSyncCount} change(s) waiting to sync" else "Everything is synced",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onTriggerSync, enabled = !uiState.isSyncing, modifier = Modifier.testTag("sync_action_button")) {
                    Icon(
                        imageVector = if (uiState.isSyncing) Icons.Default.Sync else Icons.Default.CloudDone,
                        contentDescription = null, modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (uiState.isSyncing) "Syncing..." else "Sync Now")
                }
            }
        }

        // Accounting configuration - Account Only vs Account + Inventory, Trading vs Service.
        // A capability toggle only: switching never deletes or hides underlying vouchers, stock
        // movements, or history (Phase 4.5, Section 1/3).
        if (currentCompany != null) {
            ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Accounting Setup", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Controls which features and reports are available for this company", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Track Inventory", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                if (currentCompany.accountingMode == AccountingMode.ACCOUNT_WITH_INVENTORY) "Stock, valuation & COGS are active" else "Accounts only - no stock tracking",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = currentCompany.accountingMode == AccountingMode.ACCOUNT_WITH_INVENTORY,
                            onCheckedChange = { checked ->
                                onUpdateAccountingConfiguration(
                                    if (checked) AccountingMode.ACCOUNT_WITH_INVENTORY else AccountingMode.ACCOUNT_ONLY,
                                    null
                                )
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Service Business", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                if (currentCompany.businessType == BusinessType.SERVICE) "Reports show Income & Expenditure" else "Reports show Trading Profit & Loss",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = currentCompany.businessType == BusinessType.SERVICE,
                            onCheckedChange = { checked ->
                                onUpdateAccountingConfiguration(
                                    null,
                                    if (checked) BusinessType.SERVICE else BusinessType.TRADING
                                )
                            }
                        )
                    }
                }
            }
        }

        // Cloud Sync login (Phase 6, Priority 6.7/6.10) - optional and sync-gated only. The app
        // works fully offline whether or not this is ever used; logging in only enables the
        // Outbox to reach the server. Never a mandatory app-wide login gate.
        ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Cloud Sync", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Optional - your books work fully offline either way. Sign in only to back up and sync to the cloud.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isCloudSyncLoggedIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Signed in", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                        TextButton(onClick = onCloudSyncLogout, modifier = Modifier.testTag("cloud_sync_logout_button")) {
                            Text("Sign Out")
                        }
                    }
                } else {
                    var email by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("Email") }, modifier = Modifier.fillMaxWidth().testTag("cloud_sync_email_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("cloud_sync_password_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onCloudSyncLogin(email, password) },
                        enabled = email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.testTag("cloud_sync_login_button")
                    ) {
                        Text("Sign In")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(60.dp))
    }
}
