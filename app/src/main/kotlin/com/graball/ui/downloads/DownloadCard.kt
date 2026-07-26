package com.graball.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.graball.download.DownloadEntity

private const val MAX_SEGMENTS = 50

@Composable
fun DownloadCard(
    entity: DownloadEntity,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(enabled = entity.status == "DONE", onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Thumbnail(entity.ext)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        entity.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        statusLine(entity),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(entity.status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RowAction(entity, onCancel)
            }

            if (entity.status == "RUNNING") {
                Spacer(Modifier.height(10.dp))
                if (entity.fragCount > 1) {
                    FragmentBar(entity.fragIndex, entity.fragCount)
                } else {
                    LinearProgressIndicator(
                        progress = { entity.progressPct / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }

            if (entity.status == "FAILED") {
                FailedDetails(entity, onRetry, onDelete)
            }
            if (entity.status == "DONE") {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDelete) { Text("Remove from list") }
            }
        }
    }
}

@Composable
private fun RowAction(entity: DownloadEntity, onCancel: () -> Unit) {
    when (entity.status) {
        "RUNNING", "QUEUED" -> IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Cancel, contentDescription = "Cancel")
        }
        "DONE" -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        "FAILED" -> Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        "MUXING", "MOVING" -> Icon(
            Icons.Filled.HourglassTop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // RESOLVING: no action, no icon — matches the resolve sheet's plain progress row
        else -> {}
    }
}

@Composable
private fun FailedDetails(entity: DownloadEntity, onRetry: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Retry")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Delete")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Details") }
        }
        if (expanded && !entity.rawLog.isNullOrBlank()) {
            SelectionContainer {
                Text(
                    entity.rawLog,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }
    }
}

/** DownThemAll-style segmented bar: one segment per fragment, capped at 50 (grouped beyond that). */
@Composable
private fun FragmentBar(fragIndex: Int, fragCount: Int) {
    val segments = fragCount.coerceAtMost(MAX_SEGMENTS)
    val filled = if (fragCount == 0) 0 else ((fragIndex.toFloat() / fragCount) * segments).toInt().coerceIn(0, segments)
    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        if (i < filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(1.5.dp),
                    ),
            )
        }
    }
}

@Composable
private fun Thumbnail(ext: String) {
    Box(
        Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconForExt(ext), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ponytail: small local ext->icon map, duplicates Resolver.kt's DIRECT_KINDS (private there) — fine at this size.
private fun iconForExt(ext: String): ImageVector = when (ext.lowercase()) {
    "mp4", "webm", "mkv", "mov", "avi" -> Icons.Filled.Movie
    "mp3", "m4a", "aac", "flac", "wav", "ogg" -> Icons.Filled.AudioFile
    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Filled.Image
    "pdf", "doc", "docx", "txt", "epub" -> Icons.Filled.Description
    "zip", "7z", "rar", "tar", "gz" -> Icons.Filled.FolderZip
    else -> Icons.Filled.InsertDriveFile
}

@Composable
private fun statusColor(status: String) = when (status) {
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "MUXING", "MOVING" -> MaterialTheme.colorScheme.secondary
    "DONE" -> MaterialTheme.colorScheme.tertiary
    "FAILED" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLine(d: DownloadEntity): String = when (d.status) {
    "QUEUED" -> "Queued"
    "RESOLVING" -> "Resolving…"
    "RUNNING" -> "${d.progressPct.toInt()}% · ${fmtBytes(d.downloadedBytes)}/${fmtBytes(d.totalBytes)} · ${fmtEta(d.etaSec)} left"
    "MUXING" -> "Merging…"
    "MOVING" -> "Saving…"
    "DONE" -> "Done · tap to open"
    "FAILED" -> errorSentence(d.errorClass)
    else -> d.status
}

private fun errorSentence(errorClass: String?): String = when (errorClass) {
    "NEEDS_LOGIN" -> "Sign-in needed."
    "DRM" -> "DRM-protected."
    "GEO_BLOCKED" -> "Not available in your region."
    "EXTRACTOR_BROKEN" -> "Extractor needs an update."
    "NETWORK" -> "Network problem."
    "STORAGE_FULL" -> "Storage full."
    "CANCELLED" -> "Cancelled."
    else -> "Failed."
}

private fun fmtBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.0f %s".format(value, units[unit])
}

private fun fmtEta(etaSec: Long): String {
    val s = etaSec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
