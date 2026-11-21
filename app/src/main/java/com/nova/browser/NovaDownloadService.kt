package com.nova.browser

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper

/** Keeps active downloads in a visible foreground-service lifecycle while the browser is backgrounded. */
class NovaDownloadService : Service() {
    private var foreground = false
    private var activeCount = 0

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Nova downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows active browser downloads"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = (intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1).coerceAtLeast(0)
        if (count == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeCount = count
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)
        val downloaded = intent?.getLongExtra(EXTRA_DOWNLOADED, 0L) ?: 0L
        val total = intent?.getLongExtra(EXTRA_TOTAL, 0L) ?: 0L
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)
        val notification = buildNotification(count, fileName, downloaded, total, downloadId)
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } else if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int, fileName: String?, downloaded: Long, total: Long, downloadId: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val display = DownloadNotificationPolicy.display(count, fileName, downloaded, total)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(display.title)
            .setContentText(display.text)
            .setProgress(100, display.progress, display.indeterminate)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (!downloadId.isNullOrBlank()) {
            val pause = PendingIntent.getActivity(
                this, downloadId.hashCode(), Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_PAUSE_DOWNLOAD).putExtra(MainActivity.EXTRA_DOWNLOAD_ID, downloadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cancel = PendingIntent.getActivity(
                this, downloadId.hashCode() xor 0x55AA, Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_CANCEL_DOWNLOAD).putExtra(MainActivity.EXTRA_DOWNLOAD_ID, downloadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        }
        return builder.build()
    }

    private fun notifyResultInternal(item: NovaDownloadManager.Item) {
        val pending = PendingIntent.getActivity(
            this, item.id.hashCode(), Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val completed = item.state == "COMPLETE"
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (completed) "Download complete" else "Download failed")
            .setContentText(if (completed) item.fileName else "${item.fileName}: ${item.error}".take(180))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(item.id.hashCode() and 0x7fffffff, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Nova downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows active browser downloads"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "nova_downloads"
        private const val NOTIFICATION_ID = 7301
        private const val EXTRA_COUNT = "active_count"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_DOWNLOADED = "downloaded_bytes"
        private const val EXTRA_TOTAL = "total_bytes"
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        fun notifyResult(context: Context, item: NovaDownloadManager.Item) {
            runCatching {
                val service = Intent(context, NovaDownloadService::class.java)
                context.startForegroundService(service)
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        val channel = NotificationChannel(CHANNEL_ID, "Nova downloads", NotificationManager.IMPORTANCE_LOW)
                        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
                        val openIntent = Intent(context, MainActivity::class.java)
                        val pending = PendingIntent.getActivity(context, item.id.hashCode(), openIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val completed = item.state == "COMPLETE"
                        val notification = Notification.Builder(context, CHANNEL_ID)
                            .setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                            .setContentTitle(if (completed) "Download complete" else "Download failed")
                            .setContentText(if (completed) item.fileName else "${item.fileName}: ${item.error}".take(180))
                            .setContentIntent(pending).setAutoCancel(true).build()
                        context.getSystemService(NotificationManager::class.java)
                            .notify(item.id.hashCode() and 0x7fffffff, notification)
                    }
                }, 300L)
            }
        }

        fun update(context: Context, activeCount: Int, fileName: String? = null, downloaded: Long = 0L, total: Long = 0L, downloadId: String? = null) {
            val intent = Intent(context, NovaDownloadService::class.java)
                .putExtra(EXTRA_COUNT, activeCount)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_DOWNLOADED, downloaded)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            runCatching {
                if (activeCount > 0) {
                    context.startForegroundService(intent)
                } else context.stopService(intent)
            }
        }
    }
}
