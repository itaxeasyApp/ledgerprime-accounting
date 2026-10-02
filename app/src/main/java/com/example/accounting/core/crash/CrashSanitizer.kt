package com.example.accounting.core.crash

/**
 * What is reported to Crashlytics instead of the original exception. Carries only the original exception
 * class name (as [message]) and the original stack trace; the original message is deliberately dropped,
 * because messages in this app can contain GSTINs, PAN, bank details, voucher/invoice numbers or amounts.
 */
class RedactedThrowable(val originalType: String, cause: Throwable?) : RuntimeException(originalType, cause)

/**
 * Strips every free-text message from a [Throwable] chain before it leaves the device. Pure; no Android or
 * Firebase dependency, so the redaction rule is unit-tested on the JVM. Keeps, per link of the cause chain,
 * the exception class name and the stack trace (code locations only) - nothing else. Suppressed exceptions
 * are dropped and the chain is cut at [MAX_DEPTH] links, which also makes cyclic causes safe.
 */
object CrashSanitizer {
    private const val MAX_DEPTH = 8

    fun sanitize(throwable: Throwable): Throwable = sanitize(throwable, 0)

    private fun sanitize(t: Throwable, depth: Int): Throwable {
        val cause = t.cause?.takeIf { it !== t && depth + 1 < MAX_DEPTH }?.let { sanitize(it, depth + 1) }
        return RedactedThrowable(t.javaClass.name, cause).also { it.stackTrace = t.stackTrace }
    }
}
