package com.lsl.kotlin_agent_app.recorder

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
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lsl.kotlin_agent_app.R

internal class RecorderService : Service() {
    private val sessions = LinkedHashMap<String, MicRecordingSession>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            val ids = sessions.keys.toList()
            for (id in ids) {
                sessions.remove(id)?.stop(cancelled = true)
            }
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startAsMicrophoneForeground()

        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                if (sessionId.isNotBlank()) startSession(sessionId)
            }
            ACTION_PAUSE -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                sessions[sessionId]?.pause()
            }
            ACTION_RESUME -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                sessions[sessionId]?.resume()
            }
            ACTION_STOP -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                if (sessionId.isNotBlank()) stopSession(sessionId, cancelled = false)
            }
            ACTION_CANCEL -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                if (sessionId.isNotBlank()) stopSession(sessionId, cancelled = true)
            }
            else -> Unit
        }

        updateNotification()
        if (sessions.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun startAsMicrophoneForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) return
        if (sessions.size >= MAX_CONCURRENT) return

        val s =
            MicRecordingSession(
                appContext = applicationContext,
                sessionId = sessionId,
                onState = { st ->
                    sendStateBroadcast(st)
                    updateNotification()
                },
                onStopped = { sid ->
                    sessions.remove(sid)
                    updateNotification()
                    if (sessions.isEmpty()) stopSelf()
                },
            )
        sessions[sessionId] = s
        s.start()
    }

    private fun stopSession(sessionId: String, cancelled: Boolean) {
        val s = sessions[sessionId] ?: return
        s.stop(cancelled = cancelled)
    }

    private fun sendStateBroadcast(st: RecorderRuntimeState) {
        try {
            val i =
                Intent(ACTION_STATE).apply {
                    `package` = packageName
                    putExtra(EXTRA_SESSION_ID, st.sessionId)
                    putExtra(EXTRA_STATE, st.state)
                    putExtra(EXTRA_ELAPSED_MS, st.elapsedMs)
                    putExtra(EXTRA_LEVEL_01, st.level01)
                }
            sendBroadcast(i)
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
                    "Recorder",
                    NotificationManager.IMPORTANCE_LOW,
                )
            ch.description = "Microphone recording foreground service"
            nm.createNotificationChannel(ch)
        } catch (_: Throwable) {
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val title = "录音"
        val text = if (sessions.isEmpty()) "idle" else "录音中 (${sessions.size})"
        val firstSessionId = sessions.keys.firstOrNull()?.trim()?.ifBlank { null }
        val intent =
            if (firstSessionId == null) {
                RecorderActivity.intentStart(this)
            } else {
                RecorderActivity.intentOpen(this, firstSessionId)
            }
        val piFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, intent, piFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record_24)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recorder"
        private const val NOTIF_ID = 91035
        private const val MAX_CONCURRENT = 1

        const val ACTION_START = "com.lsl.kotlin_agent_app.recorder.START"
        const val ACTION_PAUSE = "com.lsl.kotlin_agent_app.recorder.PAUSE"
        const val ACTION_RESUME = "com.lsl.kotlin_agent_app.recorder.RESUME"
        const val ACTION_STOP = "com.lsl.kotlin_agent_app.recorder.STOP"
        const val ACTION_CANCEL = "com.lsl.kotlin_agent_app.recorder.CANCEL"

        const val ACTION_STATE = "com.lsl.kotlin_agent_app.recorder.STATE"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_LEVEL_01 = "level_01"

        fun requestStart(context: Context, sessionId: String) {
            val app = context.applicationContext
            val i =
                Intent(app, RecorderService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }

        fun requestPause(context: Context, sessionId: String) {
            val app = context.applicationContext
            val i =
                Intent(app, RecorderService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }

        fun requestResume(context: Context, sessionId: String) {
            val app = context.applicationContext
            val i =
                Intent(app, RecorderService::class.java).apply {
                    action = ACTION_RESUME
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }

        fun requestStop(context: Context, sessionId: String) {
            val app = context.applicationContext
            val i =
                Intent(app, RecorderService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }

        fun requestCancel(context: Context, sessionId: String) {
            val app = context.applicationContext
            val i =
                Intent(app, RecorderService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }
    }
}
