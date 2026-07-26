package com.graball.engine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.graball.download.GraballDb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared consent copy for both the banner and the settings screen's manual check. */
@Composable
fun EngineUpdateConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update yt-dlp?") },
        text = { Text("Download and install yt-dlp update? ~3 MB from github.com/yt-dlp.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Polls active-download count -- simplest observer, no new DAO Flow needed. */
@Composable
internal fun activeDownloadCount(): State<Int> {
    val context = LocalContext.current
    return produceState(initialValue = 0) {
        val dao = GraballDb.getInstance(context).downloadDao()
        while (true) {
            value = dao.countActive()
            delay(3_000)
        }
    }
}

/** M3 banner: checks for a yt-dlp update once, offers consent + install, hides when dismissed/up to date. */
@Composable
fun EngineUpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var latest by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val activeCount by activeDownloadCount()

    LaunchedEffect(Unit) {
        val l = EngineUpdater.checkLatest(context)
        val installed = EngineUpdater.installedVersion(context)
        if (l != null && installed != null && l != installed) latest = l
    }

    if (dismissed || latest == null) return

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("yt-dlp $latest available", style = MaterialTheme.typography.bodyMedium)
                val subtitle = resultText
                    ?: if (activeCount > 0) "Waiting for downloads to finish" else null
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                updating -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                resultText == null -> TextButton(onClick = { showConsent = true }, enabled = activeCount == 0) {
                    Text("Update")
                }
            }
            IconButton(onClick = { dismissed = true }) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }

    if (showConsent) {
        EngineUpdateConsentDialog(
            onConfirm = {
                showConsent = false
                scope.launch {
                    updating = true
                    val r = EngineUpdater.update(context)
                    updating = false
                    resultText = r.fold(
                        onSuccess = { "Updated to $it" },
                        onFailure = { e -> "Update failed: ${e.message}" },
                    )
                }
            },
            onDismiss = { showConsent = false },
        )
    }
}
