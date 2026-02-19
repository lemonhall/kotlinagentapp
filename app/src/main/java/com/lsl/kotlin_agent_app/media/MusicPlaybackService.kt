package com.lsl.kotlin_agent_app.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lsl.kotlin_agent_app.MainActivity
import com.lsl.kotlin_agent_app.BuildConfig
 
class MusicPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

        val ua =
            "kotlin-agent-app/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"
        val http =
            DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        val p =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    val aa =
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build()
                    setAudioAttributes(aa, true)
                    setHandleAudioBecomingNoisy(true)
                }
        player = p

        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        mediaSession =
            MediaSession.Builder(this, p)
                .setSessionActivity(pendingIntent)
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        fun ensureStarted(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, MusicPlaybackService::class.java)
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (_: Throwable) {
                // Best-effort: service start may be rejected in some background states.
            }
        }
    }
}
