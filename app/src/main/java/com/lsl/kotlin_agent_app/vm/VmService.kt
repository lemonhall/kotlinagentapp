package com.lsl.kotlin_agent_app.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lemonhall.jediterm.android.tinyemu.RomManager
import com.lemonhall.jediterm.android.tinyemu.TinyEmuTtyConnector
import com.lsl.kotlin_agent_app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class VmMode { IDLE, TERMINAL, AGENT }

class VmService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_BOOT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.trim().orEmpty()
                if (profileId.isNotBlank()) handleBoot(profileId)
            }
            ACTION_SHUTDOWN -> handleShutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handleShutdown()
        job.cancel()
        super.onDestroy()
    }

    private fun handleBoot(profileId: String) {
        if (_connector.value != null) return // already running
        scope.launch(Dispatchers.IO) {
            try {
                val catalog = RomManager.ensureExtracted(applicationContext)
                val profile = catalog.profiles.find { it.id == profileId }
                    ?: catalog.profiles.firstOrNull()
                    ?: run {
                        Log.e(TAG, "No ROM profiles found")
                        stopSelf()
                        return@launch
                    }
                val conn = TinyEmuTtyConnector(
                    biosPath = profile.biosPath,
                    kernelPath = profile.kernelPath,
                    rootfsPath = profile.rootfsPath,
                    ramMb = profile.ramMb,
                ).also { it.start() }
                _connector.value = conn
                _isRunning.value = true
                Log.i(TAG, "VM booted: profile=${profile.id}")
            } catch (t: Throwable) {
                Log.e(TAG, "VM boot failed", t)
                stopSelf()
            }
        }
    }

    private fun handleShutdown() {
        val conn = _connector.value
        _connector.value = null
        _isRunning.value = false
        _mode.value = VmMode.IDLE
        runCatching { conn?.close() }
        Log.i(TAG, "VM shut down")
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(CHANNEL_ID, "VM Service", NotificationManager.IMPORTANCE_LOW)
            ch.description = "TinyEMU VM foreground service"
            nm.createNotificationChannel(ch)
        } catch (_: Throwable) {}
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VM running")
            .setContentText("TinyEMU RISC-V VM is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "VmService"
        private const val CHANNEL_ID = "vm_service"
        private const val NOTIF_ID = 91040

        private const val ACTION_BOOT = "com.lsl.kotlin_agent_app.vm.BOOT"
        private const val ACTION_SHUTDOWN = "com.lsl.kotlin_agent_app.vm.SHUTDOWN"
        private const val EXTRA_PROFILE_ID = "profile_id"

        private val _connector = MutableStateFlow<TinyEmuTtyConnector?>(null)
        val connector: StateFlow<TinyEmuTtyConnector?> = _connector

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _mode = MutableStateFlow(VmMode.IDLE)
        val mode: StateFlow<VmMode> = _mode

        fun switchMode(newMode: VmMode) {
            _mode.value = newMode
        }

        fun boot(context: Context, profileId: String) {
            val app = context.applicationContext
            val i = Intent(app, VmService::class.java).apply {
                action = ACTION_BOOT
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {}
        }

        fun shutdown(context: Context) {
            val app = context.applicationContext
            val i = Intent(app, VmService::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (_: Throwable) {}
        }
    }
}