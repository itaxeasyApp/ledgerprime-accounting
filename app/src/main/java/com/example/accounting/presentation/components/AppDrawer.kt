package com.example.accounting.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.rendering.BusinessProfile
import com.example.accounting.presentation.navigation.AppRoute
import com.example.accounting.presentation.theme.Spacing

/**
 * Play Store readiness pass - Legal + Support drawer (the scope explicitly chosen over a full
 * secondary-nav drawer, which would have duplicated what the Profile/Search top-bar icons already
 * cover - see `docs/54_UI_UX_ARCHITECTURE.md`'s "secondary features reached through their
 * respective sections" rule, unchanged by this addition). Header shows the business's display
 * name/GSTIN - see docs/57_BUSINESS_IDENTITY_DISPLAY.md for the Business Profile-first, Company-
 * fallback precedence this mirrors from [com.example.accounting.presentation.components.AppTopBar].
 */
@Composable
fun AppDrawerContent(
    currentCompany: Company?,
    businessProfile: BusinessProfile? = null,
    currentRoute: AppRoute,
    onNavigate: (AppRoute) -> Unit,
    /** Opens this app's real Play Store listing (`market://details?id=<applicationId>`, web
     * fallback if the Play Store app isn't installed) - a genuine external intent, not a stub. */
    onOpenPlayStore: () -> Unit = {},
    /** Only shown when actually signed in to Cloud Sync ([isCloudSyncLoggedIn]) - there is nothing
     * to log out of otherwise, and this app has no separate primary login (offline-first, no
     * account required for normal use). Delegates to the exact same `AccountingViewModel.logoutCloudSync()`
     * Settings > Backup & Sync already uses - never a second, parallel logout path. */
    isCloudSyncLoggedIn: Boolean = false,
    onLogout: () -> Unit = {}
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            // App's own brand name (explicit follow-up: "write Ledger Prime as company brand
            // name") - distinct from the business identity below it, which is the user's own
            // business (editable from Profile & Business Setup), never this app's name.
            Text(
                "Ledger Prime",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                businessProfile?.businessName?.ifBlank { null } ?: currentCompany?.name ?: "My Business",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                "GSTIN: ${(businessProfile?.gstin?.ifBlank { null } ?: currentCompany?.gstin)?.ifBlank { "Unregistered / Composition" } ?: "--"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()

        val items = listOf(
            Triple(AppRoute.About, "About", Icons.Default.Info),
            Triple(AppRoute.PrivacyPolicy, "Privacy Policy", Icons.Default.Description),
            Triple(AppRoute.TermsAndConditions, "Terms & Conditions", Icons.Default.Gavel),
            Triple(AppRoute.Support, "Support", Icons.Default.SupportAgent)
        )
        items.forEach { (route, label, icon) ->
            NavigationDrawerItem(
                label = { Text(label) },
                icon = { Icon(icon, contentDescription = null) },
                selected = currentRoute == route,
                onClick = { onNavigate(route) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }

        NavigationDrawerItem(
            label = { Text("Rate on Play Store") },
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            selected = false,
            onClick = onOpenPlayStore,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        if (isCloudSyncLoggedIn) {
            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text("Log Out") },
                icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                selected = false,
                onClick = onLogout,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }
}
