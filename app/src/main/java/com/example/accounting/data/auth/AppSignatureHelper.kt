package com.example.accounting.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Google's standard SMS Retriever API "AppSignatureHelper" sample (Week 1, Play Store update
 * plan) - computes the 11-character app signature hash the OTP SMS body must end with for
 * Android to auto-detect and auto-fill it (see [SmsRetrieverManager]'s own doc comment). Debug and
 * release builds sign with different certificates, so this prints a DIFFERENT hash per build
 * variant - call [getAppSignatures] once (logged at debug startup - see `LedgerPrimeApplication`)
 * and put the printed value into the server's `ANDROID_SMS_RETRIEVER_HASH` setting for whichever
 * build you're testing against.
 */
class AppSignatureHelper(context: Context) : ContextWrapper(context) {

    fun getAppSignatures(): List<String> {
        val appCodes = mutableListOf<String>()
        try {
            @Suppress("DEPRECATION")
            val signatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            signatures?.forEach { signature ->
                hash(packageName, signature.toCharsString())?.let { appCodes.add(it) }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Unable to find package to obtain signature hash.", e)
        }
        return appCodes
    }

    companion object {
        private const val TAG = "AppSignatureHelper"
        private const val HASH_TYPE = "SHA-256"
        private const val NUM_HASHED_BYTES = 9
        private const val NUM_BASE64_CHAR = 11

        private fun hash(packageName: String, signature: String): String? {
            val appInfo = "$packageName $signature"
            return try {
                val digest = MessageDigest.getInstance(HASH_TYPE).apply { update(appInfo.toByteArray(Charsets.UTF_8)) }.digest()
                val truncated = digest.copyOfRange(0, NUM_HASHED_BYTES)
                Base64.encodeToString(truncated, Base64.NO_PADDING or Base64.NO_WRAP).substring(0, NUM_BASE64_CHAR)
            } catch (e: NoSuchAlgorithmException) {
                Log.e(TAG, "hash: NoSuchAlgorithm", e)
                null
            }
        }
    }
}
