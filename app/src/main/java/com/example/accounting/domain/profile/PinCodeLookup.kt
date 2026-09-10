package com.example.accounting.domain.profile

/** Result of one PIN-code-to-address lookup - every field a real fact returned by the lookup
 * source, never guessed/fabricated. [success] false means the caller should leave City/State/
 * Country for manual entry, never fall back to a default. */
data class PinCodeLookupResult(
    val pinCode: String,
    val city: String = "",
    val state: String = "",
    val country: String = "",
    val success: Boolean = false,
    val errorMessage: String? = null,
    /** Real bug fix (docs/CORRECTIONS_LOG.md, "nothing happing when i type 6 digit pin code") -
     * Business and Individual Profile (and every open Create Ledger dialog) all share one
     * [com.example.accounting.presentation.viewmodel.AccountingViewModel]-level
     * `pinCodeLookupResult`. Looking up a PIN code that resolves to the exact same result as the
     * current one (the common real case: home PIN == business PIN for a sole proprietor, or simply
     * looking the same PIN up twice) produced a structurally-`equal` [PinCodeLookupResult] -
     * `MutableStateFlow` never emits when a new value `equals()` the value it already holds, so the
     * second screen's own `LaunchedEffect(pinCodeLookupResult)` silently never re-ran and its City/
     * State/Country stayed blank, with no spinner and no error (a true no-op, not a network
     * failure). This field is stamped with a fresh value on every single [lookupPinCode] call
     * (cache hit or not) purely so no two calls ever produce an `equal` result, forcing the
     * `StateFlow` to always emit - it carries no real-world meaning and no UI ever reads it. */
    val requestId: Long = 0
)

/**
 * Adapter boundary for PIN-code-to-City/State/Country lookup (Business/Individual Profile address
 * fields) - a pure Kotlin interface, no Android/network-library dependency here, mirroring
 * [com.example.accounting.domain.ocr.OcrIngestionAdapter]'s shape. The one real implementation
 * (`data/network/PostalPinCodeLookupAdapter`) calls a public, unauthenticated third-party API -
 * this is the one deliberate exception to this app's offline-first design, scoped to exactly this
 * lookup; it never blocks Business/Individual Profile from being saved when unavailable (City/
 * State/Country simply stay editable, empty, or whatever the user already typed).
 */
interface PinCodeLookupAdapter {
    suspend fun lookup(pinCode: String): PinCodeLookupResult
}
