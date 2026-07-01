package io.shayne.fogvisitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TrackingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInForeground()
            ACTION_STOP -> stopSelfSafely()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // TODO:
        // 1. 接入 FusedLocationProviderClient
        // 2. 将轨迹点写入与 Web 版本兼容的数据结构
        // 3. 将缓存/存储层从 WebView 独立出来
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fog Visitor Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "io.shayne.fogvisitor.action.START_TRACKING"
        const val ACTION_STOP = "io.shayne.fogvisitor.action.STOP_TRACKING"

        private const val CHANNEL_ID = "fog_visitor_tracking"
        private const val NOTIFICATION_ID = 1001
    }
}
