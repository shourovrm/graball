package com.graball.resolve

/** UI-facing domain models — decoupled from yt-dlp's json shape. */
enum class MediaKind { VIDEO, AUDIO, IMAGE, DOC, ARCHIVE, OTHER }

/** Sentinel formatId: not a yt-dlp selector — download service must skip `-f` for these. */
const val DIRECT_FORMAT = "direct"

data class Variant(
    val formatId: String,
    val label: String,
    val ext: String,
    val sizeBytes: Long?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val height: Int?,
    val needsMux: Boolean,
)

data class ResolvedItem(
    val sourceUrl: String,
    val title: String,
    val thumbnail: String?,
    val durationSec: Long?,
    val kind: MediaKind,
    // stable list identity: playlists can repeat the same webpage_url, so url is not a key
    val id: Int = 0,
    val variants: List<Variant>,
) {
    /** Highest-res variant with audio already muxed in; else highest-res video-only (needs mux). */
    fun bestVariant(): Variant? =
        variants.filter { it.hasAudio }.maxByOrNull { it.height ?: -1 }
            ?: variants.maxByOrNull { it.height ?: -1 }
}

/** Extension of the URL's last path segment, lowercased, "" if none. Must NOT be taken from the
 *  whole URL: an extensionless path would otherwise return the tail of the host ("com/u/0/d/..."). */
fun extOf(url: String): String =
    url.substringBefore('?').substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()

/** "1080p · mp4 · 213 MB" style label. */
fun humanSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes ${units[unit]}" else "%.0f %s".format(value, units[unit])
}

fun buildVariantLabel(height: Int?, ext: String, sizeBytes: Long?): String {
    val parts = mutableListOf<String>()
    if (height != null) parts += "${height}p"
    parts += ext
    humanSize(sizeBytes)?.let { parts += it }
    return parts.joinToString(" · ")
}
