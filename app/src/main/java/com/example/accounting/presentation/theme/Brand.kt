package com.example.accounting.presentation.theme

/**
 * Single source of truth for the product/publisher identity strings shown across
 * [com.example.accounting.presentation.features.legal.LegalScreens] (About/Support/Privacy/Terms)
 * and [com.example.accounting.presentation.features.splash.SplashScreen] - extracted once these
 * became needed in a second place, so the company name/contact details can never silently drift
 * between screens.
 */
object Brand {
    const val APP_NAME = "LedgerPrime"
    const val TAGLINE = "Smart Accounting, Simplified"
    const val PUBLISHER_LEGAL_NAME = "Itax Easy Pvt Ltd"
    const val PUBLISHER_ADDRESS = "G 41, Gandhi Nagar, Padav, Gwalior, Madhya Pradesh, India 474002"
    const val SUPPORT_EMAIL = "info@itaxeasy.com"
    const val SUPPORT_PHONE = "+91 9425113371"
}
