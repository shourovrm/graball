package com.graball.ui.picker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.graball.resolve.MediaKind
import com.graball.resolve.ResolvedItem
import com.graball.resolve.Variant
import java.net.URI

enum class KindFilter(val label: String, val kind: MediaKind?) {
    ALL("All", null),
    VIDEO("Video", MediaKind.VIDEO),
    AUDIO("Audio", MediaKind.AUDIO),
    IMAGES("Images", MediaKind.IMAGE),
    DOCS("Docs", MediaKind.DOC),
    ARCHIVES("Archives", MediaKind.ARCHIVE),
}

enum class SortBy { TITLE, SIZE, KIND }

/** Best-guess host for subtitle display, e.g. "vimeo.com". */
fun hostOf(url: String): String =
    runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

/** Plain holder for picker UI state — transient sheet, no need to survive process death. */
class PickerState(val items: List<ResolvedItem>) {
    // selection + chosen variant per item, keyed by sourceUrl. Pre-selected per DownThemAll convention.
    val selected = mutableStateMapOf<String, Boolean>().apply {
        items.forEach { put(it.sourceUrl, true) }
    }
    val chosenVariant = mutableStateMapOf<String, Variant>().apply {
        items.forEach { item -> item.bestVariant()?.let { put(item.sourceUrl, it) } }
    }
    val expanded = mutableStateMapOf<String, Boolean>()

    var filter by mutableStateOf(KindFilter.ALL)
    var sortBy by mutableStateOf(SortBy.TITLE)
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")

    fun countFor(f: KindFilter): Int =
        if (f.kind == null) items.size else items.count { it.kind == f.kind }

    /** Filtered + sorted view of items for the list. */
    fun visibleItems(): List<ResolvedItem> {
        val byFilter = if (filter.kind == null) items else items.filter { it.kind == filter.kind }
        val byQuery = if (query.isBlank()) byFilter else byFilter.filter {
            it.title.contains(query, ignoreCase = true)
        }
        return when (sortBy) {
            SortBy.TITLE -> byQuery.sortedBy { it.title.lowercase() }
            SortBy.KIND -> byQuery.sortedBy { it.kind.ordinal }
            SortBy.SIZE -> byQuery.sortedByDescending { chosenVariant[it.sourceUrl]?.sizeBytes ?: -1 }
        }
    }

    fun isSelected(item: ResolvedItem) = selected[item.sourceUrl] == true

    fun toggle(item: ResolvedItem) {
        selected[item.sourceUrl] = !isSelected(item)
    }

    fun toggleExpanded(item: ResolvedItem) {
        expanded[item.sourceUrl] = expanded[item.sourceUrl] != true
    }

    fun isExpanded(item: ResolvedItem) = expanded[item.sourceUrl] == true

    fun chooseVariant(item: ResolvedItem, variant: Variant) {
        chosenVariant[item.sourceUrl] = variant
    }

    fun selectAll() = items.forEach { selected[it.sourceUrl] = true }
    fun clearSelection() = items.forEach { selected[it.sourceUrl] = false }
    fun invertSelection() = items.forEach { selected[it.sourceUrl] = !isSelected(it) }

    val selectedCount: Int get() = items.count { isSelected(it) }

    val selectedSizeBytes: Long get() = items.filter { isSelected(it) }
        .sumOf { chosenVariant[it.sourceUrl]?.sizeBytes ?: 0L }

    fun selection(): List<Pair<ResolvedItem, Variant>> =
        items.mapNotNull { item ->
            val v = chosenVariant[item.sourceUrl] ?: item.bestVariant()
            if (isSelected(item) && v != null) item to v else null
        }
}
