package com.aethelhook.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DecisionBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALLOW_ONCE            = "com.aethelhook.app.ACTION_ALLOW_ONCE"
        const val ACTION_ALWAYS_ALLOW_PROJECT  = "com.aethelhook.app.ACTION_ALWAYS_ALLOW_PROJECT"
        const val ACTION_DENY                  = "com.aethelhook.app.ACTION_DENY"
        // Legacy
        const val ACTION_APPROVE               = "com.aethelhook.app.ACTION_APPROVE"
        const val ACTION_DECLINE               = "com.aethelhook.app.ACTION_DECLINE"

        const val EXTRA_SESSION_ID      = "session_id"
        const val EXTRA_RESPOND_URL     = "respond_url"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TOOL_NAME       = "tool_name"
        const val EXTRA_COMMAND_PREVIEW = "command_preview"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId  = intent.getStringExtra(EXTRA_SESSION_ID)      ?: return
        val respondUrl = intent.getStringExtra(EXTRA_RESPOND_URL)     ?: return
        val notifId    = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val toolName   = intent.getStringExtra(EXTRA_TOOL_NAME)       ?: ""
        val cmdPreview = intent.getStringExtra(EXTRA_COMMAND_PREVIEW) ?: ""

        val decision = when (intent.action) {
            ACTION_ALLOW_ONCE, ACTION_APPROVE  -> "allow_once"
            ACTION_ALWAYS_ALLOW_PROJECT        -> "always_allow_project"
            ACTION_DENY, ACTION_DECLINE        -> "deny"
            else                               -> return
        }

        Log.d("ÆthelHook", "Decision: $decision for session: $sessionId")

        AppPrefs.addRecord(
            context,
            ApprovalRecord(
                sessionId   = sessionId,
                toolName    = toolName,
                preview     = cmdPreview,
                decision    = decision,
                timestampMs = System.currentTimeMillis()
            )
        )

        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)
        NotificationRegistry.forget(sessionId)

        // WebSocket is the primary path - works even when phone is the hotspot provider
        // (the socket is bound to the hotspot interface, bypassing cellular routing).
        if (AethelHookWebSocket.isConnected) {
            AethelHookWebSocket.sendDecision(sessionId, decision)
            return
        }

        // HTTP fallback: WebSocket wasn't connected (e.g. FCM delivery).
        // Uses a hotspot-aware OkHttpClient with bound socket factory.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = """{"session_id":"$sessionId","decision":"$decision"}"""
                val body    = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(respondUrl)
                    .addHeader("X-AethelHook-Token", AppPrefs.getApiToken(context))
                    .post(body)
                    .build()

                AethelHookWebSocket.newBoundHttpClient(context).newCall(request).execute().use { response ->
                    Log.d("ÆthelHook", "POST $respondUrl → HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("ÆthelHook", "Failed to POST decision: ${e.message}")
            }
        }
    }
}
