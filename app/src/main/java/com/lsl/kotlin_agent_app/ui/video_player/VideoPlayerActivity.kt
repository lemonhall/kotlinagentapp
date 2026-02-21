package com.lsl.kotlin_agent_app.ui.video_player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.lsl.kotlin_agent_app.smb_media.SmbMediaActions
import com.lsl.kotlin_agent_app.ui.theme.KotlinAgentAppTheme
import java.io.File
import java.net.URLConnection

class VideoPlayerActivity : ComponentActivity() {

    private lateinit var vm: VideoPlayerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        val uriString = intent?.getStringExtra(EXTRA_URI)?.trim().orEmpty()
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME)?.trim().orEmpty().ifBlank { "视频" }
        val mime = intent?.getStringExtra(EXTRA_MIME)?.trim().orEmpty().ifBlank { null }
        val agentsPath = intent?.getStringExtra(EXTRA_AGENTS_PATH)?.trim().orEmpty().ifBlank { null }

        val uri =
            runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null || uriString.isBlank()) {
            Toast.makeText(this, "缺少播放地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        vm =
            ViewModelProvider(
                this,
                VideoPlayerViewModel.Factory(applicationContext),
            )[VideoPlayerViewModel::class.java]

        vm.load(uri = uri, mime = mime, displayName = displayName, agentsPath = agentsPath)

        val composeView =
            ComposeView(this).apply {
                setContent {
                    KotlinAgentAppTheme {
                        VideoPlayerScreen(
                            vm = vm,
                            onBack = { finish() },
                            onOpenExternal = {
                                val p = vm.currentAgentsPath.value ?: agentsPath
                                val dn = vm.displayName.value.ifBlank { displayName }
                                openExternal(
                                    agentsPath = p,
                                    displayName = dn,
                                )
                            },
                        )
                    }
                }
            }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        applyFullscreenForOrientation()
    }

    override fun onStop() {
        super.onStop()
        vm.pause()
    }

    private fun applyFullscreenForOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isLandscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun openExternal(
        agentsPath: String?,
        displayName: String,
    ) {
        if (agentsPath.isNullOrBlank()) {
            Toast.makeText(this, "缺少文件路径，无法外部打开", Toast.LENGTH_SHORT).show()
            return
        }

        if (isInNasSmbTree(agentsPath) && isVideoName(agentsPath)) {
            SmbMediaActions.openNasSmbVideoExternal(
                context = this,
                agentsPath = agentsPath,
                displayName = displayName,
            )
            return
        }

        openAgentsVideoExternal(agentsPath = agentsPath, displayName = displayName)
    }

    private fun openAgentsVideoExternal(
        agentsPath: String,
        displayName: String,
    ) {
        val rel = agentsPath.replace('\\', '/').trim()
        if (!rel.startsWith(".agents/")) return

        val file = File(filesDir, rel)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(this, "文件不存在：$displayName", Toast.LENGTH_SHORT).show()
            return
        }

        val uri =
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file,
            )

        val ext = file.extension.lowercase().takeIf { it.isNotBlank() }
        val mimeFromMap =
            ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val mime =
            mimeFromMap
                ?: URLConnection.guessContentTypeFromName(file.name)
                ?: "video/*"

        SmbMediaActions.openContentExternal(
            context = this,
            uri = uri,
            mime = mime,
            chooserTitle = "打开视频",
        )
    }

    private fun isInNasSmbTree(agentsPath: String): Boolean {
        val p = agentsPath.replace('\\', '/').trim().trimStart('/').trimEnd('/')
        if (p == ".agents/nas_smb") return true
        if (!p.startsWith(".agents/nas_smb/")) return false
        if (p.startsWith(".agents/nas_smb/secrets")) return false
        return true
    }

    private fun isVideoName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.endsWith(".mp4") ||
            n.endsWith(".m4v") ||
            n.endsWith(".mkv") ||
            n.endsWith(".webm") ||
            n.endsWith(".mov") ||
            n.endsWith(".avi") ||
            n.endsWith(".3gp") ||
            n.endsWith(".ts") ||
            n.endsWith(".flv") ||
            n.endsWith(".wmv") ||
            n.endsWith(".mpg") ||
            n.endsWith(".mpeg")
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_DISPLAY_NAME = "display_name"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_AGENTS_PATH = "agents_path"

        fun intentOf(
            context: Context,
            uri: Uri,
            displayName: String,
            mime: String?,
            agentsPath: String?,
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_AGENTS_PATH, agentsPath)
        }
    }
}
