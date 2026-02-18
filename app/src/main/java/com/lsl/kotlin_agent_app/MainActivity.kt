package com.lsl.kotlin_agent_app

import android.os.Bundle
import android.view.View
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.lsl.kotlin_agent_app.databinding.ActivityMainBinding
import com.lsl.kotlin_agent_app.config.SharedPreferencesProxyConfigRepository
import com.lsl.kotlin_agent_app.net.ProxyManager
import com.lsl.kotlin_agent_app.web.WebViewControllerProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastHandledIncomingUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("kotlin-agent-app", android.content.Context.MODE_PRIVATE)
        val proxyConfig = SharedPreferencesProxyConfigRepository(prefs).get()
        ProxyManager.apply(applicationContext, proxyConfig)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val navHostView = findViewById<View>(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home,
                R.id.navigation_dashboard,
                R.id.navigation_notifications,
                R.id.navigation_web,
                R.id.navigation_terminal,
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        WebViewControllerProvider.instance.bind(
            webView = binding.webView,
            urlEditText = binding.inputWebUrl,
            goButton = binding.buttonWebGo,
            backButton = binding.buttonWebBack,
            forwardButton = binding.buttonWebForward,
            reloadButton = binding.buttonWebReload,
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val showWeb = destination.id == R.id.navigation_web
            // Keep WebView "shown" (not INVISIBLE) so preview capture (draw-to-bitmap) stays fresh even when
            // the Web tab isn't the active destination. We hide it by z-order instead of visibility.
            binding.webOverlay.visibility = View.VISIBLE
            if (showWeb) {
                binding.webOverlay.alpha = 1f
                binding.webOverlay.bringToFront()
            } else {
                // Avoid showing the WebView behind other tabs when their UI is transparent.
                binding.webOverlay.alpha = 0f
                navHostView.bringToFront()
            }
            binding.navView.bringToFront()
        }

        maybeHandleIncomingFileIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleIncomingFileIntent(intent)
    }

    private fun maybeHandleIncomingFileIntent(intent: Intent?) {
        val uri = extractIncomingUri(intent) ?: return
        val uriKey = uri.toString()
        if (uriKey == lastHandledIncomingUri) return
        lastHandledIncomingUri = uriKey

        val (name, sizeBytes) = queryIncomingFileMeta(uri)
        val label = name ?: "unknown"
        val sizeLabel = sizeBytes?.let { "（${it} bytes）" } ?: ""

        MaterialAlertDialogBuilder(this)
            .setTitle("导入到工作区")
            .setMessage("导入文件：$label$sizeLabel\n\n目标目录：.agents/workspace/inbox/")
            .setNegativeButton("取消") { _, _ -> }
            .setPositiveButton("导入") { _, _ ->
                importIncomingUriToInbox(uri)
            }
            .show()
    }

    private fun extractIncomingUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }

    private fun queryIncomingFileMeta(uri: Uri): Pair<String?, Long?> {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null to null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx)?.trim()?.ifBlank { null } else null
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx).takeIf { it >= 0L } else null
                name to size
            } ?: (null to null)
        } catch (_: Throwable) {
            null to null
        }
    }

    private fun importIncomingUriToInbox(uri: Uri) {
        val appContext = applicationContext
        val ws = AgentsWorkspace(appContext)
        val inboxDir = ".agents/workspace/inbox"

        lifecycleScope.launch {
            try {
                val importedName =
                    withContext(Dispatchers.IO) {
                        ws.ensureInitialized()
                        ws.mkdir(inboxDir)

                        val displayName = queryIncomingFileMeta(uri).first ?: ("import_" + System.currentTimeMillis())
                        val safeName = sanitizeFileName(displayName)
                        val finalName = allocateNonConflictingName(ws, dir = inboxDir, fileName = safeName)
                        val destPath = ws.joinPath(inboxDir, finalName)
                        val destFile = File(appContext.filesDir, destPath)
                        destFile.parentFile?.mkdirs()

                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        } ?: error("无法读取来源文件")

                        finalName
                    }

                binding.navView.selectedItemId = R.id.navigation_dashboard
                Toast.makeText(this@MainActivity, "已导入：$importedName", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "导入失败：${t.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned =
            name
                .trim()
                .replace('\u0000', ' ')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
        return cleaned.ifBlank { "imported" }
    }

    private fun allocateNonConflictingName(
        ws: AgentsWorkspace,
        dir: String,
        fileName: String,
    ): String {
        val base = fileName.trim().ifBlank { "imported" }
        val dot = base.lastIndexOf('.').takeIf { it > 0 && it < base.length - 1 }
        val stem = dot?.let { base.substring(0, it) } ?: base
        val ext = dot?.let { base.substring(it) } ?: ""

        var n = 0
        while (true) {
            val candidate = if (n == 0) base else "${stem}_$n$ext"
            val path = ws.joinPath(dir, candidate)
            if (!ws.exists(path)) return candidate
            n++
            if (n >= 1000) error("同名文件过多")
        }
    }
}
