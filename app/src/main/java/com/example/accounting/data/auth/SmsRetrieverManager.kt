package com.example.accounting.data.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Phone/OTP login (Week 1, Play Store update plan) - Android's SMS Retriever API auto-detects and
 * reads back the login SMS with NO `SEND_SMS`/`READ_SMS` permission at all (this app deliberately
 * never requests either - Play Store restricts broad SMS permissions to default SMS/dialer apps).
 * Google Play Services matches the incoming SMS against this app's own signature hash (computed by
 * [AppSignatureHelper] - see its own doc comment for how to get that value into the server's
 * `ANDROID_SMS_RETRIEVER_HASH` config) before ever delivering it here; every other SMS on the
 * device stays invisible to this receiver.
 */
class SmsRetrieverManager(private val context: Context) {

    /** Starts a one-time (5-minute, per the SMS Retriever API's own limit) listener and emits the
     * extracted 6-digit code the instant a matching SMS arrives, then completes. Emits nothing on
     * timeout/failure/mismatch - auto-fill is a convenience on top of manual entry, never a
     * replacement for it, so the caller's own OTP field must stay editable regardless. */
    fun listenForCode(): Flow<String> = callbackFlow {
        val client = SmsRetriever.getClient(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode != CommonStatusCodes.SUCCESS) return
                val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                Regex("\\b(\\d{6})\\b").find(message)?.groupValues?.get(1)?.let { code -> trySend(code) }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION), ContextCompat.RECEIVER_EXPORTED
        )
        client.startSmsRetriever()
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
