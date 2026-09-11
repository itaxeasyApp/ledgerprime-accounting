package com.example.accounting.presentation.features.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.accounting.data.auth.SmsRetrieverManager
import com.example.accounting.presentation.viewmodel.AccountingUiState
import kotlinx.coroutines.flow.collectLatest

/**
 * Phone/OTP Cloud Sync login (Week 1, Play Store update plan) - a dedicated full page (not the
 * small inline form Settings used to have), matching the app's own Material 3 theme/branding
 * (same [ElevatedCard]/[RoundedCornerShape]-14dp/typography conventions as [com.example.accounting.presentation.features.settings.SettingsAndSyncScreen]'s
 * other cards). Two steps in one screen, driven by [AccountingUiState.otpPendingPhone] rather than
 * a second nav route, since "enter phone" -> "enter code" is one continuous flow, not two
 * independently-reachable destinations.
 *
 * OTP auto-read: while the code step is shown, [SmsRetrieverManager] listens for the incoming SMS
 * with no SMS permission at all; a matching code both fills the field AND immediately submits it
 * (explicit "read the otp and auto fill and login" requirement) - the field stays editable
 * regardless, since auto-fill can't be guaranteed (wrong SMS format, Play Services unavailable,
 * timeout).
 */
@Composable
fun LoginScreen(
    uiState: AccountingUiState,
    onRequestOtp: (String) -> Unit,
    onVerifyOtp: (phone: String, code: String) -> Unit,
    onCancelOtp: () -> Unit,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(uiState.isCloudSyncLoggedIn) {
        if (uiState.isCloudSyncLoggedIn) onLoggedIn()
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Spacer(modifier = Modifier.height(24.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = com.example.R.drawable.ic_ledgerprime_brandmark),
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("LedgerPrime", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Sign in to enable Cloud Sync - your books work fully offline either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        val pendingPhone = uiState.otpPendingPhone
        if (pendingPhone == null) {
            PhoneEntryStep(inFlight = uiState.otpRequestInFlight, onRequestOtp = onRequestOtp)
        } else {
            OtpEntryStep(
                phone = pendingPhone,
                inFlight = uiState.otpVerifyInFlight,
                onVerifyOtp = { code -> onVerifyOtp(pendingPhone, code) },
                onChangeNumber = onCancelOtp
            )
        }
    }
}

@Composable
private fun PhoneEntryStep(inFlight: Boolean, onRequestOtp: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }

    ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Phone Number", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("e.g. +91 98765 43210") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = { onRequestOtp(phone.trim()) },
                enabled = phone.trim().length >= 8 && !inFlight,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (inFlight) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Send Code")
                }
            }
        }
    }
}

@Composable
private fun OtpEntryStep(phone: String, inFlight: Boolean, onVerifyOtp: (String) -> Unit, onChangeNumber: () -> Unit) {
    var code by remember(phone) { mutableStateOf("") }
    var autoSubmitted by remember(phone) { mutableStateOf(false) }
    val context = LocalContext.current
    val currentOnVerify by rememberUpdatedState(onVerifyOtp)

    LaunchedEffect(phone) {
        SmsRetrieverManager(context).listenForCode().collectLatest { detected ->
            code = detected
            if (!autoSubmitted) {
                autoSubmitted = true
                currentOnVerify(detected)
            }
        }
    }

    ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Enter Code", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                "Sent to $phone - auto-fills if we can read it from your messages.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter { ch -> ch.isDigit() } },
                label = { Text("6-digit code") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onChangeNumber) { Text("Change Number") }
                Button(onClick = { onVerifyOtp(code) }, enabled = code.length == 6 && !inFlight) {
                    if (inFlight) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Verify")
                    }
                }
            }
        }
    }
}
