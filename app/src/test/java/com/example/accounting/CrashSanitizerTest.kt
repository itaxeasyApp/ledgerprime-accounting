package com.example.accounting

import com.example.accounting.core.crash.CrashReporter
import com.example.accounting.core.crash.CrashSanitizer
import com.example.accounting.core.crash.RedactedThrowable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8 Step 20 - what Crashlytics is allowed to receive: class names and code locations, never message text. */
class CrashSanitizerTest {

    private val sensitive = listOf(
        "27AAPFU0939F1ZV",            // GSTIN
        "ABCDE1234F",                 // PAN
        "123456789012",               // Aadhaar-like / bank account number
        "HDFC0001234",                // IFSC
        "INV-2026-0042",              // invoice number
        "118000.00",                  // tax amount
        "Bearer eyJhbGciOiJIUzI1NiJ9" // token
    )

    private fun everyText(t: Throwable?): String = generateSequence(t) { it.cause }.joinToString("|") { "${it.javaClass.name}:${it.message}:${it.localizedMessage}:$it" }

    @Test
    fun noMessageTextSurvives_inTheThrowableOrAnyCause() {
        val inner = IllegalArgumentException("supplier ${sensitive[0]} PAN ${sensitive[1]} acct ${sensitive[2]} ${sensitive[3]}")
        val outer = IllegalStateException("posting ${sensitive[4]} total ${sensitive[5]} auth ${sensitive[6]}", inner)
        val text = everyText(CrashSanitizer.sanitize(outer))
        sensitive.forEach { assertFalse("leaked: $it", text.contains(it)) }
    }

    @Test
    fun keepsTheOriginalClassNameAndStackTrace_forEveryLinkOfTheChain() {
        val inner = ArithmeticException("x")
        val outer = RuntimeException("y", inner)
        val s = CrashSanitizer.sanitize(outer) as RedactedThrowable
        assertEquals("java.lang.RuntimeException", s.originalType)
        assertEquals("java.lang.RuntimeException", s.message)
        assertTrue(s.stackTrace.contentEquals(outer.stackTrace))
        val c = s.cause as RedactedThrowable
        assertEquals("java.lang.ArithmeticException", c.originalType)
        assertTrue(c.stackTrace.contentEquals(inner.stackTrace))
    }

    @Test
    fun anExceptionWithNoMessage_andANullCause_areHandled() {
        val s = CrashSanitizer.sanitize(NullPointerException()) as RedactedThrowable
        assertEquals("java.lang.NullPointerException", s.originalType)
        assertEquals(null, s.cause)
    }

    @Test
    fun aCyclicOrVeryDeepCauseChain_terminates() {
        val a = RuntimeException("a ${sensitive[0]}"); val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a ...
        val depth = generateSequence(CrashSanitizer.sanitize(a)) { it.cause }.count()
        assertTrue("chain must be cut, was $depth", depth <= 8)
        assertFalse(everyText(CrashSanitizer.sanitize(a)).contains(sensitive[0]))
    }

    @Test
    fun suppressedExceptions_areNotCarried() {
        val e = RuntimeException("main"); e.addSuppressed(RuntimeException("secret ${sensitive[0]}"))
        assertEquals(0, CrashSanitizer.sanitize(e).suppressed.size)
    }

    @Test
    fun reporting_withoutAConfiguredFirebase_neverThrows() {
        CrashReporter.recordNonFatal(IllegalStateException("anything ${sensitive[0]}"))
        CrashReporter.install()
        CrashReporter.install() // idempotent
    }

    @Test
    fun productionCode_neverSendsAnythingToCrashlyticsExceptThroughTheSanitizer() {
        // The only file allowed to reference the Crashlytics API is CrashReporter, and it must sanitize every call.
        val offenders = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "CrashReporter.kt" && it.readText().contains("FirebaseCrashlytics") }.map { it.name }.toList()
        assertEquals("Crashlytics API used outside CrashReporter: $offenders", emptyList<String>(), offenders)
        val reporter = java.io.File("src/main/java/com/example/accounting/core/crash/CrashReporter.kt").readText()
        assertFalse("no custom keys / user id / logs", Regex("setCustomKey|setUserId|\\.log\\(|setCustomKeys").containsMatchIn(reporter))
        assertTrue(reporter.contains("recordException(CrashSanitizer.sanitize(error))"))
        val deps = listOf("build.gradle.kts", "../gradle/libs.versions.toml").joinToString("\n") { java.io.File(it).readText() }
        assertFalse("no Analytics dependency was added", Regex("firebase[-.]analytics|play-services-measurement", RegexOption.IGNORE_CASE).containsMatchIn(deps))
    }

    @Test
    fun theDebugTestCrash_existsOnlyInTheDebugSourceSet() {
        assertFalse(java.io.File("src/main").walkTopDown().any { it.name.contains("DebugCrash") || (it.isFile && it.extension == "xml" && it.readText().contains("DEBUG_TEST_CRASH")) })
    }
}

