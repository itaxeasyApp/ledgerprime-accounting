package com.example.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Phase 8 Step 22 - the Android backup/restore policy: an explicit allowlist (main database file + the
 * automation prefs), identical for Android 12+ cloud backup, Android 12+ device transfer and Android 11-
 * backup, with the Keystore-backed secure prefs, WAL files, attachments, generated documents and
 * Crashlytics/Firebase state excluded. Parses the real rule files and manifest.
 */
class BackupPolicyTest {

    private val res = File("src/main/res/xml")
    private val allowedIncludes = setOf("database:ledgerprime_accounting.db", "sharedpref:ledgerprime_automation_cycle_prefs.xml")

    private fun parse(f: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement

    private fun Element.rules(tag: String): Set<String> = (0 until childNodes.length).map { childNodes.item(it) }
        .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }
        .map { (it as Element).let { e -> "${e.getAttribute("domain")}:${e.getAttribute("path")}" } }.toSet()

    private fun section(name: String): Element {
        val root = parse(File(res, "data_extraction_rules.xml"))
        return root.getElementsByTagName(name).item(0) as Element
    }

    private val fullBackup get() = parse(File(res, "backup_rules.xml"))

    @Test fun legacyBackup_android11AndBelow_includesExactlyTheAllowlist() {
        assertEquals("full-backup-content", fullBackup.nodeName)
        assertEquals(allowedIncludes, fullBackup.rules("include"))
    }

    @Test fun android12CloudBackup_includesExactlyTheAllowlist_andRequiresEncryptionCapability() {
        val cloud = section("cloud-backup")
        assertEquals(allowedIncludes, cloud.rules("include"))
        assertEquals("true", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
    }

    @Test fun android12DeviceTransfer_includesExactlyTheAllowlist_separatelyFromCloudBackup() {
        val root = parse(File(res, "data_extraction_rules.xml"))
        assertEquals("both sections must be present - an absent <device-transfer> would fall back to the defaults", 1, root.getElementsByTagName("device-transfer").length)
        assertEquals(1, root.getElementsByTagName("cloud-backup").length)
        assertEquals(allowedIncludes, section("device-transfer").rules("include"))
    }

    @Test fun allThreeSections_haveTheSameIncludeAndExcludeLists() {
        val sets = listOf(fullBackup, section("cloud-backup"), section("device-transfer"))
        assertEquals(1, sets.map { it.rules("include") }.toSet().size)
        assertEquals(1, sets.map { it.rules("exclude") }.toSet().size)
    }

    @Test fun neverBacksUp_walShmJournal_orAnythingOutsideTheAllowlist() {
        listOf(fullBackup, section("cloud-backup"), section("device-transfer")).forEach { s ->
            val inc = s.rules("include")
            assertTrue("only the main db file, never -wal/-shm/-journal: $inc", inc.none { it.contains("-wal") || it.contains("-shm") || it.contains("-journal") })
            assertTrue("no wildcard or whole-domain include: $inc", inc.all { it.substringAfter(':').isNotBlank() && !it.contains("*") && !it.endsWith(":.") })
            assertTrue(inc.all { it in allowedIncludes })
            val exc = s.rules("exclude")
            listOf("database:ledgerprime_accounting.db-wal", "database:ledgerprime_accounting.db-shm").forEach { assertTrue("missing explicit exclude $it", it in exc) }
        }
    }

    /** True if some include in [s] would back up domain:path (same file, or a directory that contains it). */
    private fun covered(s: Element, domain: String, path: String) =
        s.rules("include").any { inc ->
            val (d, p) = inc.split(":", limit = 2)
            d == domain && (path == p || path.startsWith("$p/"))
        }

    private val sections get() = listOf(fullBackup, section("cloud-backup"), section("device-transfer"))

    @Test fun keystoreBackedSecureData_isNotCoveredByAnyInclude_inEverySection() {
        // Excluded by omission (Android lint rejects an <exclude> outside an included path).
        listOf(
            "ledgerprime_secure_prefs.xml",
            "ledgerprime_secure_prefs_fallback.xml",
            "__androidx_security_crypto_encrypted_prefs__.xml" // the Tink keyset file EncryptedSharedPreferences writes
        ).forEach { name -> sections.forEach { assertFalse("$name must not be backed up", covered(it, "sharedpref", name)) } }
        sections.forEach { assertTrue(it.rules("include").none { r -> r.contains("secure") || r.contains("security_crypto") }) }
    }

    @Test fun attachmentsDocumentsFirebaseAndCache_areNotCoveredByAnyInclude_inEverySection() {
        sections.forEach { s ->
            listOf("voucher_attachments", "voucher_attachments/receipt.jpg", "documents", "documents/invoice.pdf", ".crashlytics.v3", ".crashlytics.v3/x").forEach {
                assertFalse("files/$it must not be backed up", covered(s, "file", it))
            }
            assertFalse(covered(s, "root", "anything"))
            assertFalse(covered(s, "external", "anything"))
            assertFalse("cache is not a backed-up domain", covered(s, "cache", "anything"))
            assertFalse(covered(s, "sharedpref", "com.google.firebase.crashlytics.xml"))
            assertFalse(covered(s, "database", "androidx.work.workdb"))
            assertFalse(covered(s, "database", "com.google.android.datatransport.events"))
        }
    }

    @Test fun databaseSideFiles_areNotCovered_butTheMainDbAndAutomationPrefsAre() {
        sections.forEach { s ->
            assertTrue(covered(s, "database", "ledgerprime_accounting.db"))
            assertTrue(covered(s, "sharedpref", "ledgerprime_automation_cycle_prefs.xml"))
            listOf("-wal", "-shm", "-journal").forEach { assertFalse(covered(s, "database", "ledgerprime_accounting.db$it")) }
        }
    }
    @Test fun manifest_keepsAllowBackupTrue_andPointsAtBothRuleFiles() {
        val app = parse(File("src/main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        assertEquals("true", app.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttribute("android:fullBackupContent"))
    }

    @Test fun theNamesInTheRules_matchTheNamesTheCodeActuallyUses() {
        // Drift guard: if any of these is renamed, the rules would silently stop matching.
        val main = File("src/main/java")
        fun src(rel: String) = File(main, rel).readText()
        assertTrue(src("com/example/accounting/core/database/AppDatabase.kt").contains("\"ledgerprime_accounting.db\""))
        assertTrue(src("com/example/accounting/core/security/SecureStorage.kt").contains("\"ledgerprime_secure_prefs\""))
        assertTrue(src("com/example/accounting/core/security/SecureStorage.kt").contains("\"\${PREFS_FILE}_fallback\""))
        assertTrue(src("com/example/accounting/automation/scheduler/work/AutomationCycleWorker.kt").contains("\"ledgerprime_automation_cycle_prefs\""))
        assertTrue(src("com/example/accounting/data/storage/AttachmentStorageAdapter.kt").contains("\"voucher_attachments\""))
        assertTrue(src("com/example/accounting/data/rendering/PdfDocumentRenderer.kt").contains("\"documents\""))
    }

    @Test fun noBackupAgentOrCustomBackupFrameworkWasAdded() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("backupAgent"))
        assertFalse(File("src/main/java").walkTopDown().any { it.isFile && it.extension == "kt" && Regex("BackupAgent|BackupManager").containsMatchIn(it.readText()) })
    }
}
