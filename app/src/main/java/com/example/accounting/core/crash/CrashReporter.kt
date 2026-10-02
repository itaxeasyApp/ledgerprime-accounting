package com.example.accounting.core.crash

import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single place the app talks to Firebase Crashlytics (crashes and non-fatal exceptions only - no
 * Analytics, no custom keys, no user identifier, no logs/breadcrumbs). Everything sent goes through
 * [CrashSanitizer], so no exception message (which may hold GSTIN/PAN/bank/voucher/amount text) is uploaded.
 * Never throws: if Firebase is not configured or not initialised, reporting silently does nothing.
 */
object CrashReporter {
    private val installed = AtomicBoolean(false)

    /** Wraps the process's uncaught-exception handler (Crashlytics installs its own first, at provider init)
     * so that a fatal crash is handed to Crashlytics in redacted form. Idempotent. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        if (runCatching { FirebaseCrashlytics.getInstance() }.isFailure) return
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            previous.uncaughtException(thread, CrashSanitizer.sanitize(error))
        }
    }

    /** Reports a handled-but-unexpected exception as a non-fatal, redacted. */
    fun recordNonFatal(error: Throwable) {
        runCatching { FirebaseCrashlytics.getInstance().recordException(CrashSanitizer.sanitize(error)) }
    }
}
