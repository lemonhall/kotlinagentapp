package com.lsl.kotlin_agent_app.smb_media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lsl.kotlin_agent_app.R

class SmbMediaStreamingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var activeSessions: Int = 0
    private var prepared: Boolean = false

    private val stopRunnable =
        Runnable {
            prepared = false
            activeSessions = 0
            stopSelf()
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_PREPARE -> {
                prepared = true
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, PREPARE_TIMEOUT_MS)
            }
            ACTION_SESSION_OPENED -> {
                prepared = false
                handler.removeCallbacks(stopRunnable)
                activeSessions += 1
            }
            ACTION_SESSION_RELEASED -> {
                activeSessions -= 1
                if (activeSessions <= 0) {
                    activeSessions = 0
                    handler.removeCallbacks(stopRunnable)
                    handler.postDelayed(stopRunnable, RELEASE_DELAY_MS)
                }
            }
            else -> Unit
        }

        updateNotification()
        if (!prepared && activeSessions == 0) {
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, RELEASE_DELAY_MS)
        }

        return START_NOT_STICKY
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val ch =
                NotificationChannel(
                    CHANNEL_ID,
                    "SMB media streaming",
                    NotificationManager.IMPORTANCE_LOW,
                )
            ch.description = "Keepalive for external SMB video streaming"
            nm.createNotificationChannel(ch)
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val title = "正在串流媒体"
        val text =
            when {
                activeSessions > 0 -> "活动串流会话：$activeSessions"
                prepared -> "准备串流…"
                else -> "idle"
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "smb_media_streaming"
        private const val NOTIF_ID = 91045

        private const val ACTION_PREPARE = "com.lsl.kotlin_agent_app.smb_media.PREPARE"
        private const val ACTION_SESSION_OPENED = "com.lsl.kotlin_agent_app.smb_media.SESSION_OPENED"
        private const val ACTION_SESSION_RELEASED = "com.lsl.kotlin_agent_app.smb_media.SESSION_RELEASED"

        private const val RELEASE_DELAY_MS = 60_000L
        private const val PREPARE_TIMEOUT_MS = 120_000L

        fun requestPrepare(context: Context) {
            start(context, ACTION_PREPARE)
        }

        fun requestSessionOpened(context: Context) {
            start(context, ACTION_SESSION_OPENED)
        }

        fun requestSessionReleased(context: Context) {
            start(context, ACTION_SESSION_RELEASED)
        }

        private fun start(context: Context, action: String) {
            val app = context.applicationContext
            val i =
                Intent(app, SmbMediaStreamingService::class.java).apply {
                    this.action = action
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }
    }
}

