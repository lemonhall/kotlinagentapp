package com.lsl.kotlin_agent_app.radio_recordings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lsl.kotlin_agent_app.R
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.radio_transcript.RecordingPipelineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RadioRecordingService : Service() {
    private val sessions = LinkedHashMap<String, RecordingSession>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            val ids = sessions.keys.toList()
            for (id in ids) stopSession(id, completed = false)
        } catch (_: Throwable) {
        }
        sessions.clear()
        job.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                if (sessionId.isNotBlank()) {
                    startSession(sessionId)
                }
            }
            ACTION_STOP -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
                if (sessionId.isNotBlank()) {
                    stopSession(sessionId, completed = true)
                }
            }
            ACTION_STOP_ALL -> {
                val ids = sessions.keys.toList()
                for (id in ids) stopSession(id, completed = true)
            }
            else -> Unit
        }

        updateNotification()
        if (sessions.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun startSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) return
        if (sessions.size >= MAX_CONCURRENT) {
            rejectMaxConcurrent(sessionId)
            return
        }
        val s = RecordingSession(applicationContext, sessionId)
        sessions[sessionId] = s
        s.start(scope) { sid ->
            sessions.remove(sid)
            updateNotification()
            if (sessions.isEmpty()) stopSelf()
        }
    }

    private fun rejectMaxConcurrent(sessionId: String) {
        try {
            val ws = AgentsWorkspace(applicationContext)
            val store = RadioRecordingsStore(ws)
            val metaPath = RadioRecordingsPaths.sessionMetaJson(sessionId)
            if (!ws.exists(metaPath)) return
            val raw = ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
            val prev = RecordingMetaV1.parse(raw)
            val next =
                prev.copy(
                    state = "failed",
                    updatedAt = RecordingMetaV1.nowIso(),
                    error =
                        RecordingMetaV1.ErrorInfo(
                            code = "MaxConcurrentRecordings",
                            message = "Already recording $MAX_CONCURRENT sessions",
                        ),
                )
            store.writeSessionMeta(sessionId, next)
            store.writeSessionStatus(sessionId, ok = false, note = "MaxConcurrentRecordings")
        } catch (_: Throwable) {
        }
    }

    private fun stopSession(sessionId: String, completed: Boolean) {
        val s = sessions.remove(sessionId) ?: RecordingSession(applicationContext, sessionId)
        if (completed) {
            s.stopCompleted(note = "completed (service)")
            maybeEnqueueOfflinePipeline(sessionId)
        } else {
            s.stopCancelled(note = "cancelled (service)")
        }
    }

    private fun maybeEnqueueOfflinePipeline(sessionId: String) {
        try {
            val sid = sessionId.trim()
            if (sid.isBlank()) return
            val ws = AgentsWorkspace(applicationContext)
            val metaPath = RadioRecordingsPaths.sessionMetaJson(sid)
            if (!ws.exists(metaPath)) return
            val raw = ws.readTextFile(metaPath, maxBytes = 2L * 1024L * 1024L)
            val meta = runCatching { RecordingMetaV1.parse(raw) }.getOrNull() ?: return
            val pipe = meta.pipeline ?: return
            RecordingPipelineManager(appContext = applicationContext).enqueue(
                sessionId = sid,
                targetLanguage = pipe.targetLanguage,
                replace = false,
            )
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val ch =
                NotificationChannel(
                    CHANNEL_ID,
                    "Radio recording",
                    NotificationManager.IMPORTANCE_LOW,
                )
            ch.description = "Radio recording foreground service"
            nm.createNotificationChannel(ch)
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val title = "Radio recording"
        val text = if (sessions.isEmpty()) "idle" else "recording ${sessions.size} session(s)"
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
        private const val CHANNEL_ID = "radio_recording"
        private const val NOTIF_ID = 91039

        private const val MAX_CONCURRENT = 2

        private const val ACTION_START = "com.lsl.kotlin_agent_app.radio_recordings.START"
        private const val ACTION_STOP = "com.lsl.kotlin_agent_app.radio_recordings.STOP"
        private const val ACTION_STOP_ALL = "com.lsl.kotlin_agent_app.radio_recordings.STOP_ALL"
        private const val EXTRA_SESSION_ID = "session_id"

        fun requestStart(
            context: Context,
            sessionId: String,
        ) {
            val app = context.applicationContext
            val i =
                Intent(app, RadioRecordingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }

        fun requestStop(
            context: Context,
            sessionId: String,
        ) {
            val app = context.applicationContext
            val i =
                Intent(app, RadioRecordingService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }

        fun requestStopAll(context: Context) {
            val app = context.applicationContext
            val i =
                Intent(app, RadioRecordingService::class.java).apply {
                    action = ACTION_STOP_ALL
                }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {
            }
        }
    }
}
