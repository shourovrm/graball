package com.graball.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.graball.engine.EngineUpdateConsentDialog
import com.graball.engine.EngineUpdater
import com.graball.engine.activeDownloadCount
import kotlinx.coroutines.launch

// placeholder until the repo has a real public URL
private const val SOURCE_URL = "https://github.com/graball/graball-android"

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val activeCount by activeDownloadCount()

    var installed by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { installed = EngineUpdater.installedVersion(context) }

    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    Column(modifier.fillMaxSize()) {
        SectionLabel("ENGINE")
        ListItem(
            headlineContent = { Text("yt-dlp ${installed ?: "…"}") },
            supportingContent = {
                Text(
                    statusText
                        ?: if (activeCount > 0) "Blocked while downloads run" else "Updates only when you ask",
                )
            },
            trailingContent = {
                if (checking || updating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(
                        enabled = activeCount == 0,
                        onClick = {
                            scope.launch {
                                checking = true
                                statusText = null
                                val latest = EngineUpdater.checkLatest(context, force = true)
                                checking = false
                                statusText = when {
                                    latest == null -> "Couldn't check for updates"
                                    latest == installed -> "Already up to date"
                                    else -> null
                                }
                                if (latest != null && latest != installed) showConsent = true
                            }
                        },
                    ) { Text("Check for updates") }
                }
            },
        )

        SectionLabel("ABOUT")
        ListItem(headlineContent = { Text("Version $appVersion") })
        ListItem(
            headlineContent = { Text("GPLv3") },
            supportingContent = { Text(SOURCE_URL) },
            modifier = Modifier.clickable { uriHandler.openUri(SOURCE_URL) },
        )
    }

    if (showConsent) {
        EngineUpdateConsentDialog(
            onConfirm = {
                showConsent = false
                scope.launch {
                    updating = true
                    val r = EngineUpdater.update(context)
                    updating = false
                    statusText = r.fold(
                        onSuccess = { installed = it; "Updated to $it" },
                        onFailure = { e -> "Update failed: ${e.message}" },
                    )
                }
            },
            onDismiss = { showConsent = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
