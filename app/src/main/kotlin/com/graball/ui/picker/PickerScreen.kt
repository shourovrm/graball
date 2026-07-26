package com.graball.ui.picker

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.graball.resolve.MediaKind
import com.graball.resolve.ResolvedItem
import com.graball.resolve.Variant
import com.graball.resolve.buildVariantLabel
import com.graball.resolve.humanSize

private fun iconFor(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.AUDIO -> Icons.Filled.AudioFile
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.DOC -> Icons.Filled.Description
    MediaKind.ARCHIVE -> Icons.Filled.FolderZip
    MediaKind.OTHER -> Icons.Filled.InsertDriveFile
}

/** DownThemAll-style flat result picker: filter chips, multi-select, expandable variants. */
@Composable
fun PickerScreen(
    items: List<ResolvedItem>,
    onDownload: (List<Pair<ResolvedItem, Variant>>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(items) { PickerState(items) }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column {
            ContextHeader(state, onCancel)
            if (state.searchOpen) SearchRow(state)
            ChipRow(state)
            LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
                items(state.visibleItems(), key = { it.sourceUrl }) { item ->
                    ItemRow(state, item)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            BottomBar(state, onDownload, onCancel)
        }
    }
}

@Composable
private fun ContextHeader(state: PickerState, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        Text("${state.selectedCount} selected", modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { state.searchOpen = !state.searchOpen }) {
            Icon(Icons.Filled.Search, contentDescription = "Filter by text")
        }
        IconButton(onClick = { state.selectAll() }) {
            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
        }
        IconButton(onClick = { state.invertSelection() }) {
            Icon(Icons.Filled.FlipToBack, contentDescription = "Invert selection")
        }
        IconButton(onClick = { state.clearSelection() }) {
            Icon(Icons.Filled.Deselect, contentDescription = "Clear selection")
        }
        SortMenuButton(state)
    }
}

@Composable
private fun SortMenuButton(state: PickerState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(SortBy.TITLE to "Title", SortBy.SIZE to "Size", SortBy.KIND to "Kind").forEach { (by, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { state.sortBy = by; open = false },
                    trailingIcon = { if (state.sortBy == by) Icon(Icons.Filled.Check, null) },
                )
            }
        }
    }
}

@Composable
private fun SearchRow(state: PickerState) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { state.query = it },
        placeholder = { Text("Filter by title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ChipRow(state: PickerState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KindFilter.entries.filter { it == KindFilter.ALL || state.countFor(it) > 0 }.forEach { f ->
            val count = state.countFor(f)
            FilterChip(
                selected = state.filter == f,
                onClick = { state.filter = f },
                label = { Text("${f.label} $count") },
                leadingIcon = if (state.filter == f) {
                    { Icon(Icons.Filled.Check, null) }
                } else null,
            )
        }
    }
}

@Composable
private fun ItemRow(state: PickerState, item: ResolvedItem) {
    val chosen = state.chosenVariant[item.sourceUrl] ?: item.bestVariant()
    val subtitle = buildString {
        chosen?.let { append(buildVariantLabel(it.height, it.ext, it.sizeBytes)) }
        append(" · ")
        append(hostOf(item.sourceUrl))
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { state.toggle(item) },
                    onLongClick = { state.toggle(item) },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.isSelected(item), onCheckedChange = { state.toggle(item) })
            Icon(
                iconFor(item.kind),
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.variants.size > 1) {
                IconButton(onClick = { state.toggleExpanded(item) }) {
                    Icon(
                        if (state.isExpanded(item)) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Variants",
                    )
                }
            }
        }
        if (item.variants.size > 1 && state.isExpanded(item)) {
            item.variants.forEach { variant ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { state.chooseVariant(item, variant) })
                        .padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = chosen?.formatId == variant.formatId,
                        onClick = { state.chooseVariant(item, variant) },
                    )
                    Text(
                        buildVariantLabel(variant.height, variant.ext, variant.sizeBytes),
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: PickerState,
    onDownload: (List<Pair<ResolvedItem, Variant>>) -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val size = humanSize(state.selectedSizeBytes)
        Text(
            if (size != null) "${state.selectedCount} selected · $size" else "${state.selectedCount} selected",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onDownload(state.selection()) }, enabled = state.selectedCount > 0) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Download ${state.selectedCount}")
        }
    }
}
