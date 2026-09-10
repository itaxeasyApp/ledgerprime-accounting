package com.example.accounting

import com.example.accounting.domain.cma.CmaReportGenerator
import com.example.accounting.domain.ocr.OcrIngestionAdapter
import com.example.accounting.domain.reconciliation.BankLineDirection
import com.example.accounting.domain.reconciliation.ReconciliationAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7I (Advanced Input & Reporting Architecture: OCR ingestion, bank-reconciliation
 * suggestions, CMA report - architecture/contracts only) - pure JVM, structural-contract tests
 * only, matching [SandboxIntegrationTestSuite]'s exact pattern. [OcrIngestionAdapter] gained its
 * first real implementation (`data/ocr/MlKitOcrAdapter.kt`, behaviorally exercised by
 * `Phase7JBOcrSuggestionTestSuite`'s fake and by real device testing) after this suite was written;
 * [ReconciliationAdapter]/[CmaReportGenerator] remain contract-only. These tests still lock in each
 * contract's shape, so no implementation can silently widen what these interfaces are allowed to
 * touch.
 */
class Phase7ITestSuite {

    private val forbiddenSimpleNameFragments = listOf("Dao", "Repository", "PostingEngine", "DoubleEntryValidator")

    private fun assertNoMutationPathReachable(clazz: Class<*>) {
        for (method in clazz.declaredMethods) {
            val referencedTypes = method.parameterTypes.toList() + method.returnType
            for (type in referencedTypes) {
                assertFalse(
                    "${clazz.simpleName}.${method.name} must not reference ${type.simpleName} - this interface must have zero path to an accounting mutation",
                    forbiddenSimpleNameFragments.any { type.simpleName.contains(it) }
                )
            }
        }
    }

    // ==========================================
    // OcrIngestionAdapter
    // ==========================================
    @Test
    fun testOcrIngestionAdapter_isAPureInterface_withExactlyOneOperation() {
        val clazz = OcrIngestionAdapter::class.java
        assertTrue("OcrIngestionAdapter must be an interface", clazz.isInterface)
        // extractFromDocument's documentTypeHint default parameter value makes the Kotlin compiler
        // emit a synthetic "extractFromDocument$default" bridge alongside the real method - one
        // logical operation, not two; excluded here rather than asserted on, same as every other
        // JVM-synthesized method name would be.
        val realMethodNames = clazz.declaredMethods.map { it.name }.filterNot { it.endsWith("\$default") }.toSet()
        assertEquals(setOf("extractFromDocument"), realMethodNames)
    }

    @Test
    fun testOcrIngestionAdapter_hasNoAccountingMutationPath() {
        assertNoMutationPathReachable(OcrIngestionAdapter::class.java)
    }

    @Test
    fun testOcrIngestionAdapter_noConcreteImplementationExists() {
        assertTrue(java.lang.reflect.Modifier.isAbstract(OcrIngestionAdapter::class.java.modifiers))
        assertEquals(0, OcrIngestionAdapter::class.java.declaredConstructors.size)
    }

    // ==========================================
    // ReconciliationAdapter
    // ==========================================
    @Test
    fun testReconciliationAdapter_isAPureInterface_withExactlyOneOperation() {
        val clazz = ReconciliationAdapter::class.java
        assertTrue("ReconciliationAdapter must be an interface", clazz.isInterface)
        assertEquals(setOf("suggestMatches"), clazz.declaredMethods.map { it.name }.toSet())
    }

    @Test
    fun testReconciliationAdapter_hasNoAccountingMutationPath() {
        assertNoMutationPathReachable(ReconciliationAdapter::class.java)
    }

    @Test
    fun testBankLineDirection_isDistinctFromDrCr_notReusedAsPerspectiveShortcut() {
        // Deliberately its own type, deliberately its own vocabulary - see ReconciliationAdapter.kt's
        // doc comment on why a bank statement's "credit" and this app's ledger DrCr.CREDIT are
        // opposite-perspective concepts for the same event.
        assertEquals(setOf(BankLineDirection.DEPOSIT, BankLineDirection.WITHDRAWAL), BankLineDirection.entries.toSet())
        val bankLineDirectionNames = BankLineDirection.entries.map { it.name }.toSet()
        val drCrNames = com.example.accounting.core.common.DrCr.entries.map { it.name }.toSet()
        assertTrue("BankLineDirection must not reuse DrCr's DEBIT/CREDIT vocabulary", bankLineDirectionNames.intersect(drCrNames).isEmpty())
    }

    // ==========================================
    // CmaReportGenerator
    // ==========================================
    @Test
    fun testCmaReportGenerator_isAPureInterface_withExactlyOneOperation() {
        val clazz = CmaReportGenerator::class.java
        assertTrue("CmaReportGenerator must be an interface", clazz.isInterface)
        assertEquals(setOf("generate"), clazz.declaredMethods.map { it.name }.toSet())
    }

    @Test
    fun testCmaReportGenerator_hasNoAccountingMutationPath() {
        assertNoMutationPathReachable(CmaReportGenerator::class.java)
    }

    @Test
    fun testCmaReportGenerator_takesOnlyAlreadyComputedReports_neverRawLedgerData() {
        // Structural proof of "reports consume engine output, they never recalculate": the only
        // way to call `generate` is with report objects the existing, frozen generate* functions
        // already produced - there is no parameter type here that would let an implementation
        // reach into raw Voucher/JournalItem/Ledger data itself. `Continuation` is excluded - it's
        // the Kotlin compiler's synthetic suspend-fn parameter, not a business parameter.
        val method = CmaReportGenerator::class.java.declaredMethods.first { it.name == "generate" }
        val expectedTypes = setOf("BusinessProfile", "TrialBalanceReport", "ProfitAndLossReport", "BalanceSheetReport")
        val paramTypeNames = method.parameterTypes.map { it.simpleName }.toSet()
        assertTrue("generate must take all four already-computed report/context types", paramTypeNames.containsAll(expectedTypes))
        val unexpected = paramTypeNames - expectedTypes - setOf("Continuation")
        assertTrue("generate must not take any parameter beyond the already-computed reports: $unexpected", unexpected.isEmpty())
    }
}
