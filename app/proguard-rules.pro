# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Week 3 (release engineering): first-ever minified release build for this app. ---
# Preserve line numbers so Crashlytics-symbolicated (once google-services.json lands) and manual
# stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Retrofit/OkHttp - official rules (github.com/square/retrofit/blob/master/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro),
# most AARs ship their own consumer-rules.pro, but Retrofit's use of service-interface generics is
# easy for R8 to strip incorrectly without these.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# Moshi codegen (this project uses moshi-kotlin-codegen, not reflection - see the "ksp" block in
# build.gradle.kts - so generated adapters are compile-time-linked and normally survive shrinking
# on their own; these two rules just protect the @JsonClass models themselves from having fields
# that Moshi's generated adapter looks up by name renamed out from under it).
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * { @com.squareup.moshi.Json <fields>; }

# Room entities/DAOs are accessed through Room's compile-time-generated (KSP) implementations,
# not reflection, so no extra keep rules are needed here - flagged only so a future contributor
# doesn't add speculative Room rules that aren't actually necessary.
