package com.nova.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps active downloads in a visible foreground-service lifecycle while the browser is backgrounded. */
class NovaDownloadService : Service() {
    private var foreground = false
    private var activeCount = 0

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Nova downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows active browser downloads"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = (intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1).coerceAtLeast(0)
        if (count == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeCount = count
        val notification = buildNotification(count)
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (count == 1) "1 download in progress" else "$count downloads in progress"
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Nova Browser")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nova_downloads"
        private const val NOTIFICATION_ID = 7301
        private const val EXTRA_COUNT = "active_count"

        fun update(context: Context, activeCount: Int) {
            val intent = Intent(context, NovaDownloadService::class.java).putExtra(EXTRA_COUNT, activeCount)
            runCatching {
                if (activeCount > 0) {
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                } else context.stopService(intent)
            }
        }
    }
}
