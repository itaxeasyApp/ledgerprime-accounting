import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}


// ---- Release signing (Phase 8, Step 24) ------------------------------------------------------------
// The upload keystore must live OUTSIDE this repository and outside OneDrive. There is deliberately no
// default keystore path and no default password. Provide, as environment variables or Gradle properties
// (-P..., or ~/.gradle/gradle.properties - never a file inside the repo):
//   KEYSTORE_PATH    absolute path of the keystore
//   STORE_PASSWORD   keystore password
//   KEY_PASSWORD     optional - defaults to STORE_PASSWORD (PKCS12 keystores use one password)
//   KEY_ALIAS        optional - defaults to "upload"
// Only the release tasks that actually sign (assembleRelease / bundleRelease / packageRelease / signRelease*
// / validateSigningRelease) require them; debug builds, unit tests and lint do not.
fun signingValue(name: String): String? =
  providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }

val releaseKeystorePath: String? = signingValue("KEYSTORE_PATH")
val releaseStorePassword: String? = signingValue("STORE_PASSWORD")
android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.ledgerprime.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      // No default path and no default password: both must be supplied explicitly (see the
      // "Release signing" block at the top of this file). Left unset when absent - the guard at the bottom
      // of this file then fails any release build with a clear message, while debug builds and tests are unaffected.
      if (releaseKeystorePath != null && releaseStorePassword != null) {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseStorePassword
        keyAlias = signingValue("KEY_ALIAS") ?: "upload"
        keyPassword = signingValue("KEY_PASSWORD") ?: releaseStorePassword
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  // implementation(libs.androidx.compose.material3.windowsizeclass) - was only used to switch
  // MainAppScreen's bottom nav to a NavigationRail on tablet width; removed per explicit product
  // decision (docs/58_SINGLE_NAVIGATION_LAYOUT.md) to use one consistent bottom-bar layout on
  // every screen size.
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  // Crashlytics only (no Analytics): crash + non-fatal reporting, redacted by core/crash/CrashSanitizer.
  implementation(libs.firebase.crashlytics)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // Phone/OTP login (Week 1) - SmsRetriever, no SEND_SMS/READ_SMS permission needed at all (see
  // data/auth/SmsRetrieverManager.kt).
  implementation(libs.play.services.auth)
  implementation(libs.retrofit)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.zxing.core)
  // OCR (Document/Image Scan feature) - Play Services-backed on-device text recognition, no
  // Firebase project/API key/billing needed (unlike Firebase AI/Gemini, which does) - see
  // data/ocr/MlKitOcrAdapter.kt.
  implementation(libs.mlkit.text.recognition)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// Fails a release build that would sign with a missing, in-repo, OneDrive or known-compromised keystore -
// with a message that never contains a password.
gradle.taskGraph.whenReady {
  val needsReleaseSigning = allTasks.any {
    it.path.startsWith(":app:") && Regex("^(assemble|bundle|package|sign|validateSigning)(.*)Release").containsMatchIn(it.name) ||
      it.path.startsWith(":app:") && it.name.matches(Regex("(assemble|bundle|package)Release.*"))
  }
  if (needsReleaseSigning) {
    val problems = mutableListOf<String>()
    if (releaseKeystorePath == null) problems += "KEYSTORE_PATH is not set (absolute path of the upload keystore, outside the repository)"
    if (releaseStorePassword == null) problems += "STORE_PASSWORD is not set (the keystore password)"
    if (releaseKeystorePath != null) {
      val ks = file(releaseKeystorePath).canonicalFile
      val repo = rootDir.canonicalFile
      if (!ks.isFile) problems += "the keystore file does not exist: ${ks.path}"
      if (ks.path.startsWith(repo.path + File.separator)) problems += "the keystore must live OUTSIDE the repository, but is inside it: ${ks.path}"
      if (ks.path.contains("${File.separator}OneDrive", ignoreCase = true)) problems += "the keystore must not live in OneDrive: ${ks.path}"
      if (ks.name.equals("my-upload-key.jks", ignoreCase = true)) problems += "my-upload-key.jks is the compromised, publicly exposed key and must not be used"
    }
    if (problems.isNotEmpty()) {
      throw GradleException(
        "Release signing is not configured safely:\n - " + problems.joinToString("\n - ") +
          "\nSet KEYSTORE_PATH and STORE_PASSWORD (environment variables or Gradle properties; KEY_PASSWORD/KEY_ALIAS optional)."
      )
    }
  }
}