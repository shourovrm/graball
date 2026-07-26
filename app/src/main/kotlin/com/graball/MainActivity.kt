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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.graball.browser.BrowserScreen
import com.graball.engine.EngineUpdateBanner
import com.graball.ui.downloads.DownloadsScreen
import com.graball.ui.settings.SettingsScreen
import com.graball.ui.theme.GraballTheme

class MainActivity : ComponentActivity() {

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
            GraballTheme {
                // ponytail: 3 fixed tabs, plain index state — NavHost when deep links/backstack needed
                var tab by rememberSaveable { mutableIntStateOf(0) }
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
