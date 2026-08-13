package com.graball.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graball.download.GraballDb
import com.graball.engine.EngineUpdateConsentDialog
import com.graball.engine.EngineUpdater
import com.graball.engine.activeDownloadCount
import com.graball.prefs.Prefs
import kotlinx.coroutines.launch

private const val SOURCE_URL = "https://github.com/shourovrm/graball"

private val SEARCH_ENGINE_LABELS = linkedMapOf(
    "google" to "Google (English)",
    "ddg" to "DuckDuckGo",
    "brave" to "Brave Search",
)
private val THEME_LABELS = linkedMapOf(
    "system" to "System",
    "light" to "Light",
    "dark" to "Dark",
)
private val FOLDER_KINDS = listOf(
    "video" to "Videos",
    "audio" to "Audio",
    "image" to "Images",
    "other" to "Other",
)

/** SAF tree URI's last path segment, e.g. "primary:Movies" -> "Movies". */
private fun displayFolderName(treeUri: String?): String {
    if (treeUri == null) return "Default (Media library)"
    val segment = Uri.parse(treeUri).lastPathSegment ?: return "Default (Media library)"
    return segment.substringAfter(':', segment)
}

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

    val searchEngine by Prefs.searchEngine(context).collectAsStateWithLifecycle(initialValue = "google")
    val adblock by Prefs.adblock(context).collectAsStateWithLifecycle(initialValue = true)
    val httpsOnly by Prefs.httpsOnly(context).collectAsStateWithLifecycle(initialValue = false)
    val theme by Prefs.theme(context).collectAsStateWithLifecycle(initialValue = "system")
    var showSearchDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearHistory by remember { mutableStateOf(false) }
    // saveable: the picker is a separate task — process death mid-pick must not drop the choice
    var pendingFolderKind by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val kind = pendingFolderKind
        pendingFolderKind = null
        if (uri != null && kind != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch { Prefs.setFolder(context, kind, uri.toString()) }
        }
    }

    LaunchedEffect(Unit) { installed = EngineUpdater.installedVersion(context) }

    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    Column(modifier.fillMaxSize()) {
        SectionLabel("BROWSER")
        ListItem(
            headlineContent = { Text("Search engine") },
            supportingContent = { Text(SEARCH_ENGINE_LABELS[searchEngine] ?: searchEngine) },
            modifier = Modifier.clickable { showSearchDialog = true },
        )
        ListItem(
            headlineContent = { Text("Block ads") },
            trailingContent = {
                Switch(checked = adblock, onCheckedChange = { scope.launch { Prefs.setAdblock(context, it) } })
            },
        )
        ListItem(
            headlineContent = { Text("HTTPS only") },
            supportingContent = { Text("Upgrade http links to https") },
            trailingContent = {
                Switch(checked = httpsOnly, onCheckedChange = { scope.launch { Prefs.setHttpsOnly(context, it) } })
            },
        )

        SectionLabel("APPEARANCE")
        ListItem(
            headlineContent = { Text("Theme") },
            supportingContent = { Text(THEME_LABELS[theme] ?: theme) },
            modifier = Modifier.clickable { showThemeDialog = true },
        )

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

        SectionLabel("DOWNLOADS")
        FOLDER_KINDS.forEach { (kind, label) ->
            val folderUri by Prefs.folderFor(context, kind).collectAsStateWithLifecycle(initialValue = null)
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Text(displayFolderName(folderUri)) },
                trailingContent = {
                    IconButton(onClick = { scope.launch { Prefs.setFolder(context, kind, null) } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Reset to default")
                    }
                },
                modifier = Modifier.clickable {
                    pendingFolderKind = kind
                    folderLauncher.launch(null)
                },
            )
        }
        ListItem(
            headlineContent = { Text("Clear history") },
            supportingContent = { Text("Remove all download entries. Files stay.") },
            modifier = Modifier.clickable { showClearHistory = true },
        )

        SectionLabel("PRIVACY")
        var cookiesCleared by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(if (cookiesCleared) "Cookies cleared" else "Clear cookies") },
            supportingContent = { Text("Signs you out of every site in the in-app browser") },
            modifier = Modifier.clickable(enabled = !cookiesCleared) {
                com.graball.cookies.CookieExport.clearAll()
                cookiesCleared = true
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

    if (showSearchDialog) {
        RadioDialog(
            title = "Search engine",
            options = SEARCH_ENGINE_LABELS.entries.map { it.key to it.value },
            selected = searchEngine,
            onDismiss = { showSearchDialog = false },
            onSelect = {
                scope.launch { Prefs.setSearchEngine(context, it) }
                showSearchDialog = false
            },
        )
    }

    if (showThemeDialog) {
        RadioDialog(
            title = "Theme",
            options = THEME_LABELS.entries.map { it.key to it.value },
            selected = theme,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                scope.launch { Prefs.setTheme(context, it) }
                showThemeDialog = false
            },
        )
    }

    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Clear history?") },
            text = { Text("Remove all download history? Files stay.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistory = false
                    scope.launch { GraballDb.getInstance(context).downloadDao().deleteAll() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text("Cancel") } },
        )
    }
}

/** Shared single-choice dialog for search engine / theme pickers. */
@Composable
private fun RadioDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selected, onClick = { onSelect(id) })
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
