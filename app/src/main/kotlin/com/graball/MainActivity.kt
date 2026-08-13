package com.graball

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.graball.browser.BrowserScreen
import com.graball.engine.EngineUpdateBanner
import com.graball.ui.downloads.DownloadsScreen
import com.graball.ui.settings.SettingsScreen
import com.graball.ui.theme.GraballThemeFromPrefs

class MainActivity : ComponentActivity() {

    companion object {
        /** Tab index to open on. ShareActivity uses it to land the user on Downloads after queueing. */
        const val EXTRA_TAB = "com.graball.tab"
        const val TAB_DOWNLOADS = 0
    }

    // singleTask: a second launch reuses this instance, so the tab request arrives here, not in a
    // fresh onCreate. Compose reads it through a state holder rather than the stale `intent` field.
    private var requestedTab by mutableIntStateOf(-1)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.getIntExtra(EXTRA_TAB, -1)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional: downloads run either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 13+: without this grant the download progress notification is silently dropped
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            GraballThemeFromPrefs {
                // ponytail: 3 fixed tabs, plain index state — NavHost when deep links/backstack needed
                var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra(EXTRA_TAB, TAB_DOWNLOADS)) }
                // a re-launch (share sheet queueing a download) switches tabs on the live instance
                LaunchedEffect(requestedTab) { if (requestedTab >= 0) tab = requestedTab }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 },
                                { Icon(Icons.Filled.Download, null) }, label = { Text("Downloads") })
                            NavigationBarItem(tab == 1, { tab = 1 },
                                { Icon(Icons.Filled.Language, null) }, label = { Text("Browser") })
                            NavigationBarItem(tab == 2, { tab = 2 },
                                { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        if (tab == 0) EngineUpdateBanner()
                        when (tab) {
                            0 -> DownloadsScreen()
                            1 -> BrowserScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
