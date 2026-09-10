package com.example.accounting.presentation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Phase 7J UI: the bottom-nav is Home/Sales/Purchases/Money/Reports (5 items, per the UX spec) -
 * every other area (Party, Items, Cash/Bank, Outstanding, Profile, Import/OCR, Subscription,
 * Search) is reached through one of those 5 or through a top-bar entry point, never a 6th+ tab.
 * Existing routes (Dashboard/DayBook/ChartOfAccounts/LedgerStatement/SettingsAndSync) are kept
 * byte-identical in shape - only new routes are added.
 */
sealed class AppRoute(val path: String, val title: String) {
    object Dashboard : AppRoute("#dashboard", "Home")
    object DayBook : AppRoute("#daybook", "Day Book")
    object ChartOfAccounts : AppRoute("#chart-of-accounts", "Ledger Accounts")
    data class LedgerStatement(val ledgerId: String) : AppRoute("#statement/$ledgerId", "Ledger Statement")
    object Reports : AppRoute("#reports", "Reports Center")
    /** A genuinely separate top-level screen (not nested inside Reports Center's category menu) -
     * on a phone-sized screen, three stacked back-headers (Reports Center category -> GST menu ->
     * this screen's own step header) ate real vertical space before any content; a dedicated
     * route means only this screen's own header shows. */
    object GstDashboard : AppRoute("#gst-dashboard", "GST Dashboard")
    object SettingsAndSync : AppRoute("#settings-sync", "Settings")

    object Sales : AppRoute("#sales", "Sales")
    object Purchases : AppRoute("#purchases", "Purchases")
    object Money : AppRoute("#money", "Money")

    /** [role] is "CUSTOMER" or "SUPPLIER" (kept as a raw string, matching [LedgerStatement]'s own
     * raw-id convention - `navigation` never imports a `domain` type). */
    data class Parties(val role: String) : AppRoute("#parties/$role", "Parties")

    object Profile : AppRoute("#profile", "Profile & Business Setup")
    object ProfileWizard : AppRoute("#profile-wizard", "Business Setup Wizard")
    object Subscription : AppRoute("#subscription", "Subscription")
    object DataTools : AppRoute("#data-tools", "Import & Scan")
    data class Search(val query: String = "") : AppRoute("#search/$query", "Search")

    // Legal + Support drawer (Play Store readiness) - reached only from the new nav Drawer, never
    // a bottom-nav tab, matching every other secondary route's own convention.
    object About : AppRoute("#about", "About")
    object PrivacyPolicy : AppRoute("#privacy-policy", "Privacy Policy")
    object TermsAndConditions : AppRoute("#terms", "Terms & Conditions")
    object Support : AppRoute("#support", "Support")

    companion object {
        fun fromHash(hash: String): AppRoute {
            return when {
                hash.startsWith("#statement/") -> LedgerStatement(hash.removePrefix("#statement/"))
                hash.startsWith("#parties/") -> Parties(hash.removePrefix("#parties/"))
                hash.startsWith("#search/") -> Search(hash.removePrefix("#search/"))
                hash == "#daybook" -> DayBook
                hash == "#chart-of-accounts" -> ChartOfAccounts
                hash == "#reports" -> Reports
                hash == "#settings-sync" -> SettingsAndSync
                hash == "#sales" -> Sales
                hash == "#purchases" -> Purchases
                hash == "#money" -> Money
                hash == "#profile" -> Profile
                hash == "#profile-wizard" -> ProfileWizard
                hash == "#subscription" -> Subscription
                hash == "#data-tools" -> DataTools
                hash == "#about" -> About
                hash == "#privacy-policy" -> PrivacyPolicy
                hash == "#terms" -> TermsAndConditions
                hash == "#support" -> Support
                else -> Dashboard
            }
        }
    }
}

/**
 * HashRouter manages deterministic URL-like hash navigation state, backstack history,
 * and adaptive layout strategies for mobile, foldable, and tablet display form factors.
 */
class HashRouter(initialRoute: AppRoute = AppRoute.Dashboard) {

    private val history = ArrayDeque<AppRoute>()

    private val _currentRoute = MutableStateFlow<AppRoute>(initialRoute)
    val currentRoute: StateFlow<AppRoute> = _currentRoute.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    init {
        history.push(initialRoute)
    }

    fun navigate(destination: AppRoute) {
        if (_currentRoute.value != destination) {
            history.push(destination)
            _currentRoute.value = destination
            _canGoBack.value = history.size > 1
        }
    }

    fun goBack(): Boolean {
        return if (history.size > 1) {
            history.pop() // Pop current
            val previous = history.peek() ?: AppRoute.Dashboard
            _currentRoute.value = previous
            _canGoBack.value = history.size > 1
            true
        } else {
            false
        }
    }

    fun replace(destination: AppRoute) {
        if (history.isNotEmpty()) {
            history.pop()
        }
        history.push(destination)
        _currentRoute.value = destination
        _canGoBack.value = history.size > 1
    }

    fun currentHash(): String = _currentRoute.value.path
}
