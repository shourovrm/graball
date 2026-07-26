package com.graball.browser

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.graball.resolve.MediaKind

/** Where a hit was observed. */
enum class Source { NETWORK, DOM, MEDIA_HOOK }

data class SniffHit(val url: String, val source: Source, val ext: String?, val kind: MediaKind)

// noise: script/style/font/manifest assets are never download targets
private val SKIP_EXT = setOf("js", "css", "svg", "ico", "woff", "woff2", "ttf", "eot", "otf", "json", "map")

private val KIND_BY_EXT: Map<String, MediaKind> = buildMap {
    listOf("mp4", "webm", "mkv", "mov", "avi", "m3u8", "mpd", "ts").forEach { put(it, MediaKind.VIDEO) }
    listOf("mp3", "m4a", "aac", "flac", "wav", "ogg").forEach { put(it, MediaKind.AUDIO) }
    listOf("jpg", "jpeg", "png", "webp", "bmp", "gif").forEach { put(it, MediaKind.IMAGE) }
    listOf("pdf", "doc", "docx", "txt", "epub").forEach { put(it, MediaKind.DOC) }
    listOf("zip", "7z", "rar", "tar", "gz").forEach { put(it, MediaKind.ARCHIVE) }
}

private fun mimeKind(mime: String?): MediaKind? = when {
    mime == null -> null
    mime.startsWith("video/") -> MediaKind.VIDEO
    mime.startsWith("audio/") -> MediaKind.AUDIO
    mime.startsWith("image/") -> MediaKind.IMAGE
    else -> null
}

/**
 * Per-page sniffed hit collection. Dedup by URL, classify by file extension (mime hint as
 * fallback when the URL has none). Drops noise: tiny/static assets, tracking-pixel gifs seen
 * on the wire, and data:/blob: URLs (blob streams only ever surface via the MEDIA_HOOK marker).
 * ponytail: unclassifiable URLs (no known ext, no mime hint) are dropped rather than kept as
 * MediaKind.OTHER — otherwise every plain nav `<a href>` on a page would show up as a "hit".
 */
class SniffStore {
    val hits: SnapshotStateList<SniffHit> = mutableStateListOf()
    private val seen = HashSet<String>()

    fun add(url: String, source: Source, mimeHint: String? = null) {
        if (source == Source.MEDIA_HOOK) {
            if (seen.add(url)) hits.add(SniffHit(url, source, ext = null, kind = MediaKind.VIDEO))
            return
        }
        if (url.startsWith("data:") || url.startsWith("blob:")) return
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in SKIP_EXT) return
        if (ext == "gif" && source == Source.NETWORK) return // tracking pixels
        val kind = KIND_BY_EXT[ext] ?: mimeKind(mimeHint) ?: return
        if (seen.add(url)) hits.add(SniffHit(url, source, ext.ifBlank { null }, kind))
    }

    /** New page: drop everything the old one collected. */
    fun clearForPage(pageUrl: String) {
        hits.clear()
        seen.clear()
    }
}
