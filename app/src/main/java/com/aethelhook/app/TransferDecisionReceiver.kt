package com.aethelhook.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

// Handles the Approve/Deny actions on the "New device wants to connect" notification
// (see AethelHookWebSocket.showTransferRequestNotification). Kept separate from
// DecisionBroadcastReceiver since the payload shape differs (request_id/approve,
// not session_id/respond_url) and there's no HTTP fallback path here.
class TransferDecisionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_APPROVE = "com.aethelhook.app.ACTION_TRANSFER_APPROVE"
        const val ACTION_DENY    = "com.aethelhook.app.ACTION_TRANSFER_DENY"

        const val EXTRA_REQUEST_ID      = "request_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val notifId   = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val approve   = when (intent.action) {
            ACTION_APPROVE -> true
            ACTION_DENY    -> false
            else           -> return
        }

        Log.d("ÆthelHook", "Transfer decision: request=$requestId approve=$approve")

        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)

        AethelHookWebSocket.sendTransferDecision(requestId, approve)
    }
}
