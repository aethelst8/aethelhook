package com.aethelhook.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the LAN WebSocket connection alive even when
 * the app is backgrounded or the screen is off.
 *
 * Without this, Android may kill the OkHttp threads after a few minutes.
 * The persistent notification lets the OS know this process is intentional.
 */
class AethelHookWebSocketService : Service() {

    companion object {
        private const val CHANNEL_ID     = "aethelhook_ws_service"
        private const val NOTIFICATION_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, AethelHookWebSocketService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AethelHookWebSocketService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        // Start (or reconnect) the WebSocket
        AethelHookWebSocket.connect(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if killed by OS
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AethelHookWebSocket.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ÆthelHook LAN Connection",
            NotificationManager.IMPORTANCE_LOW   // silent, no sound
        ).apply {
            description = "Keeps the LAN WebSocket alive for instant approval notifications"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ÆthelHook")
            .setContentText("LAN connected, waiting for approvals")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .build()
    }
}
