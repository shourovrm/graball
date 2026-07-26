package com.graball.ui.downloads

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graball.download.DownloadDao
import com.graball.download.DownloadEntity
import com.graball.download.DownloadService
import com.graball.download.GraballDb
import com.graball.download.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads list: Room-backed, one card per row, DownThemAll-style segmented progress. */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { GraballDb.getInstance(context).downloadDao() }
    val downloads by remember { dao.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var showDeleteAll by remember { mutableStateOf(false) }

    if (downloads.isEmpty()) {
        EmptyState(modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showDeleteAll = true }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete all downloads")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(downloads, key = { it.id }) { d ->
                DownloadCard(
                    entity = d,
                    onCancel = { DownloadService.cancel(context, d.id) },
                    onRetry = { DownloadService.retry(context, d.id) },
                    onDelete = { scope.launch { dao.delete(d.id) } },
                    onDeleteFile = { scope.launch { deleteFile(context, d.mediaUri); dao.delete(d.id) } },
                    onOpen = { openMedia(context, d.mediaUri) },
                )
            }
        }
    }

    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Delete all downloads?") },
            text = { Text("Files already downloaded can be kept or deleted too.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    scope.launch { deleteAllDownloads(context, dao, downloads, deleteFiles = true) }
                }) { Text("Also delete files") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteAll = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showDeleteAll = false
                        scope.launch { deleteAllDownloads(context, dao, downloads, deleteFiles = false) }
                    }) { Text("Remove list only") }
                }
            },
        )
    }
}

/** Stops anything still running/queued before wiping rows -- a live download must not keep
 * writing to a row the DB no longer has. */
private suspend fun deleteAllDownloads(
    context: android.content.Context,
    dao: DownloadDao,
    rows: List<DownloadEntity>,
    deleteFiles: Boolean,
) {
    rows.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
        .forEach { DownloadService.cancel(context, it.id) }
    if (deleteFiles) {
        rows.filter { it.status == Status.DONE }.forEach { deleteFile(context, it.mediaUri) }
    }
    dao.deleteAll()
}

// mediaUri may already point at a missing file -- deletion failure is expected and ignored.
// ponytail: legacy (<29) fallback rows can hold a file:// uri, which ContentResolver.delete
// can't touch -- those files are one-way, only the list entry goes.
private suspend fun deleteFile(context: android.content.Context, mediaUri: String?) {
    if (mediaUri == null) return
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(Uri.parse(mediaUri), null, null)
        } catch (e: Exception) {
            // ignore: file may already be gone
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Link,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Share a link to Graball to start",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// mediaUri never logged: it's a MediaStore content:// uri, not sensitive by itself, but no Log calls regardless.
private fun openMedia(context: android.content.Context, mediaUri: String?) {
    if (mediaUri == null) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mediaUri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show()
    }
}
