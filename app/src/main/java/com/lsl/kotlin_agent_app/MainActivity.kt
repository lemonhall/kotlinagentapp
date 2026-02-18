package com.lsl.kotlin_agent_app

import android.os.Bundle
import android.view.View
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
    }
}
