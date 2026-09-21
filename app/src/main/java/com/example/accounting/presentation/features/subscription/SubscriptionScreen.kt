package com.example.accounting.presentation.features.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.accounting.domain.subscription.CompanySubscription
import com.example.accounting.domain.subscription.EntitlementFeature
import com.example.accounting.domain.subscription.SubscriptionPlanType
import com.example.accounting.domain.subscription.isImplementedInThisBuild
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.SectionCard
import com.example.accounting.presentation.theme.Spacing

/** A business-facing label for each [EntitlementFeature] - never the raw enum name, per the
 * project's "no raw internal label surfaced as UI copy" rule. */
private fun EntitlementFeature.displayLabel(): String = when (this) {
    EntitlementFeature.ACCOUNTING -> "Accounting"
    EntitlementFeature.GSTR -> "GST Returns"
    EntitlementFeature.E_INVOICE -> "E-Invoice"
    EntitlementFeature.ITR -> "Income Tax Returns"
    EntitlementFeature.AUDIT_REPORT -> "Audit Report"
    EntitlementFeature.CMA -> "CMA Report"
    EntitlementFeature.OCR -> "Scan & Import"
    EntitlementFeature.INVENTORY -> "Inventory"
    EntitlementFeature.ADVANCED_REPORTS -> "Advanced Reports"
    EntitlementFeature.API_ACCESS -> "API Access"
}

/** Phase 7J UI: plan status + upgrade/renew - backed by `SubscriptionManagementService` (Phase
 * 7J-B). No other new screen this phase checks an entitlement before offering its action; that
 * gating decision is explicitly deferred to a later phase, per the UX round-trip on this exact
 * point. Subscription state never changes or deletes any accounting record. */
@Composable
fun SubscriptionScreen(
    subscription: CompanySubscription?,
    onUpgradeOrRenew: (SubscriptionPlanType, String, Set<EntitlementFeature>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Subscription", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

        val planLabel = if (subscription?.planType == SubscriptionPlanType.PAID) "Paid Plan" else "Free Plan"
        SectionCard(elevated = true, title = subscription?.planName ?: planLabel, subtitle = planLabel) {
            Text(
                if (subscription?.isActive == true) "Active for this financial year" else "No paid plan active for this financial year",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text("What's included", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xs), contentPadding = PaddingValues(bottom = 60.dp)) {
            items(EntitlementFeature.entries) { feature ->
                // A feature the plan entitles but this build hasn't actually shipped yet must
                // never show as "Included" - that would promise something no screen can deliver
                // (found live: CMA/Advanced Reports had zero backend/UI wiring behind their
                // checkmark). "Coming soon" is the same honest-disabled language ReportsCenter's
                // own disabled tiles already use.
                val has = feature.isImplementedInThisBuild && subscription?.isActive == true && feature in subscription.entitlements
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(feature.displayLabel(), style = MaterialTheme.typography.bodyMedium)
                        if (!feature.isImplementedInThisBuild) {
                            Text("Coming soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(
                        if (has) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = if (has) "Included" else "Not included",
                        tint = if (has) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ActionButton(
            text = "Upgrade / Renew Paid Plan",
            onClick = { onUpgradeOrRenew(SubscriptionPlanType.PAID, "Paid Plan", EntitlementFeature.entries.toSet()) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
