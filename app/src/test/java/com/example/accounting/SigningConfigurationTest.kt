package com.example.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phase 8 Step 24 - release signing is configured explicitly and safely; signing material is gitignored. */
class SigningConfigurationTest {

    private val gradle = File("build.gradle.kts").readText()
    private val ignore = File("../.gitignore").readText().lines().map { it.trim() }
    private val readme = File("../README.md").readText()

    @Test fun noDefaultKeystorePath_orFallbackToTheRepo() {
        assertFalse("no `?: \"...\"` default for KEYSTORE_PATH", Regex("""KEYSTORE_PATH[^\n]*\?:\s*"""").containsMatchIn(gradle))
        assertFalse(gradle.contains("\${rootDir}/my-upload-key"))
        assertFalse(gradle.contains("storeFile = file(\"\${rootDir}/my-upload"))
    }

    @Test fun releaseSigningIsOnlyPopulatedWhenPathAndPasswordAreBothSupplied() {
        assertTrue(gradle.contains("if (releaseKeystorePath != null && releaseStorePassword != null)"))
        assertTrue(gradle.contains("providers.environmentVariable(name).orElse(providers.gradleProperty(name))"))
    }

    @Test fun releaseBuildsFailClearly_forMissingInRepoOneDriveAndCompromisedKeystores() {
        listOf(
            "KEYSTORE_PATH is not set", "STORE_PASSWORD is not set", "does not exist", "must live OUTSIDE the repository",
            "must not live in OneDrive", "my-upload-key.jks is the compromised"
        ).forEach { assertTrue("guard message missing: $it", gradle.contains(it)) }
        assertTrue(gradle.contains("throw GradleException"))
        assertTrue(gradle.contains("gradle.taskGraph.whenReady"))
    }

    @Test fun noPasswordIsHardCodedForTheReleaseKey_andTheGuardNeverPrintsOne() {
        val passwordLiterals = Regex("""(storePassword|keyPassword)\s*=\s*"[^"]*"""").findAll(gradle).map { it.value }.toList()
        // the only literals allowed are the standard, non-secret Android debug keystore credentials
        assertTrue(passwordLiterals.toString(), passwordLiterals.all { it.endsWith("\"android\"") })
        val guard = gradle.substringAfter("gradle.taskGraph.whenReady")
        assertFalse("the failure message must not interpolate a password", Regex("""\$\{?(releaseStorePassword|signingValue\("(STORE|KEY)_PASSWORD"\))""").containsMatchIn(guard.substringAfter("throw GradleException")))
    }

    @Test fun debugSigningIsPreserved() {
        assertTrue(gradle.contains("create(\"debugConfig\")"))
        assertTrue(gradle.contains("storeFile = file(\"\${rootDir}/debug.keystore\")"))
        assertTrue(gradle.contains("debug { signingConfig = signingConfigs.getByName(\"debugConfig\") }"))
    }

    @Test fun signingMaterialPatternsAreGitignored() {
        listOf("*.jks", "*.keystore", "*.p12", "*.pfx", "*.pem", "keystore.properties", "app/google-services.json").forEach {
            assertTrue("missing .gitignore pattern $it", it in ignore)
        }
    }

    @Test fun readmeDocumentsTheVariableNamesButNoSecrets() {
        assertTrue(readme.contains("KEYSTORE_PATH") && readme.contains("STORE_PASSWORD"))
        assertFalse(Regex("""(?i)(store|key)[_ ]?password\s*[=:]\s*\S{4,}""").containsMatchIn(readme.substringAfter("**Release signing")))
    }

    @Test fun applicationIdIsTheNewLedgerPrimeApp() {
        assertEquals(1, Regex("""applicationId = "com\.ledgerprime\.app"""").findAll(gradle).count())
    }
}
