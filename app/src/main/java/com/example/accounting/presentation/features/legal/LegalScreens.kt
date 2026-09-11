package com.example.accounting.presentation.features.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.theme.Brand
import com.example.accounting.presentation.theme.Spacing

/**
 * Play Store readiness pass - Legal/Support content reached only from the new nav Drawer
 * ([com.example.accounting.presentation.components.AppDrawerContent]), never a bottom-nav tab.
 *
 * Publisher/contact strings now live in [Brand] (shared with [com.example.accounting.presentation.features.splash.SplashScreen]),
 * all real, confirmed details - "Itax Easy Pvt Ltd" is a registered organization with a DUNS
 * number (confirmed on its own Play Console developer account, separate from the GitHub account
 * this repo is pushed from).
 */
private const val APP_NAME = Brand.APP_NAME
private const val PUBLISHER_LEGAL_NAME = Brand.PUBLISHER_LEGAL_NAME
private const val PUBLISHER_ADDRESS = Brand.PUBLISHER_ADDRESS
private const val SUPPORT_EMAIL = Brand.SUPPORT_EMAIL
private const val SUPPORT_PHONE = Brand.SUPPORT_PHONE

@Composable
fun AboutScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            Text(APP_NAME, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Text("Version 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard(title = "What this app is") {
                Text(
                    "$APP_NAME is an offline-first accounting app for Indian small businesses - Sale, " +
                        "Purchase, Receipts, Payments, GST-ready reports, and Customer/Supplier " +
                        "management, all working with zero internet connection required. An optional " +
                        "Cloud Sync feature can back up your data online if you choose to turn it on.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        item {
            SectionCard(title = "Publisher") {
                Text(PUBLISHER_LEGAL_NAME, style = MaterialTheme.typography.bodyMedium)
                Text(PUBLISHER_ADDRESS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SupportScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item { Text("Support", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
        item {
            SectionCard(title = "Contact") {
                Text("For help, bug reports, or data questions, email:", style = MaterialTheme.typography.bodyMedium)
                Text(SUPPORT_EMAIL, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Text("Or call:", style = MaterialTheme.typography.bodyMedium)
                Text(SUPPORT_PHONE, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            SectionCard(title = "Your data") {
                Text(
                    "Your accounting data lives on this device. If you'd like a copy of it, or want it " +
                        "deleted (including from Cloud Sync, if you ever turned it on), email the address " +
                        "above and it will be handled directly.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun PrivacyPolicyScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item { Text("Privacy Policy", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
        item { Text("Last updated: [date this is published]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(privacyPolicySections) { section ->
            SectionCard(title = section.heading) {
                Text(section.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun TermsAndConditionsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item { Text("Terms & Conditions", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) }
        item { Text("Last updated: [date this is published]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(termsSections) { section ->
            SectionCard(title = section.heading) {
                Text(section.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class LegalSection(val heading: String, val body: String)

private val privacyPolicySections = listOf(
    LegalSection(
        "What we collect",
        "$APP_NAME is offline-first: the accounting data you enter (companies, ledgers, vouchers, " +
            "customers/suppliers, GST figures) is stored locally on your device using an encrypted " +
            "local database. We do not collect this data ourselves unless you explicitly turn on " +
            "Cloud Sync, in which case it is sent to our sync server solely so your own devices can " +
            "stay in sync - it is never sold, shared with advertisers, or used for any purpose other " +
            "than providing the app's own features to you."
    ),
    LegalSection(
        "Permissions",
        "The app requests Internet and Network State access only, used for the optional Cloud Sync " +
            "feature and to check connectivity. It does not request Camera, Contacts, SMS, Location, " +
            "Storage, or Microphone permissions. Receipt photos for scanning use Android's Photo " +
            "Picker, which does not grant the app broad access to your photo library."
    ),
    LegalSection(
        "Third-party services",
        "The app does not show ads and does not use third-party analytics or advertising SDKs. " +
            "[If Firebase App Check/other Google services remain enabled at publish time, list them " +
            "and what they're used for here - currently present in the project but not yet wired to " +
            "any feature.]"
    ),
    LegalSection(
        "Data retention & deletion",
        "Your data stays on your device until you delete it yourself, or (if Cloud Sync is enabled) " +
            "until you request deletion by contacting us at $SUPPORT_EMAIL."
    ),
    LegalSection(
        "Children's privacy",
        "This app is a business accounting tool and is not directed at children. We do not knowingly " +
            "collect data from children."
    ),
    LegalSection(
        "Changes to this policy",
        "If this policy changes, the updated version will be published here with a new \"Last " +
            "updated\" date."
    ),
    LegalSection(
        "Contact",
        "Questions about this policy or your data: $SUPPORT_EMAIL or $SUPPORT_PHONE. Published by $PUBLISHER_LEGAL_NAME, $PUBLISHER_ADDRESS."
    )
)

private val termsSections = listOf(
    LegalSection(
        "Acceptance",
        "By using $APP_NAME, you agree to these terms. If you don't agree, please don't use the app."
    ),
    LegalSection(
        "What the app does - and doesn't do",
        "$APP_NAME helps you record and organize your own business's accounting and GST data, and " +
            "prepare GST return figures/JSON files. It does not file GST returns, ITR, or any other " +
            "return with any government authority on your behalf - GSTR JSON files it produces must " +
            "be uploaded by you (or your tax professional) directly on the official GST portal. It is " +
            "a record-keeping and preparation tool, not a substitute for professional tax/legal advice."
    ),
    LegalSection(
        "Your responsibility",
        "You are responsible for the accuracy of the data you enter and for meeting your own " +
            "statutory filing obligations and deadlines. $PUBLISHER_LEGAL_NAME is not liable for " +
            "penalties, interest, or losses arising from incorrect data entry, missed filings, or " +
            "reliance on the app in place of professional advice."
    ),
    LegalSection(
        "Your data",
        "Your accounting data belongs to you. See the Privacy Policy for how it is stored and, if " +
            "you opt into Cloud Sync, how it is synced."
    ),
    LegalSection(
        "Changes",
        "We may update these terms as the app evolves; continued use after an update means you " +
            "accept the revised terms."
    ),
    LegalSection(
        "Contact",
        "$SUPPORT_EMAIL or $SUPPORT_PHONE."
    )
)
