package com.graball.resolve

import java.net.URLDecoder

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

// filename= (or RFC 5987 filename*=) inside a decoded disposition string
private val DISPOSITION_NAME = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";&]+)", RegexOption.IGNORE_CASE)

/** Name the URL will download as, percent-decoded. A filename= smuggled in the query string
 *  (signed S3/Azure/GitHub links put Content-Disposition there) wins over the last path segment,
 *  whose tail can be a bare uuid ("release-assets.githubusercontent.com/.../d68bd505-..."). */
fun fileNameOf(url: String): String {
    val query = url.substringAfter('?', "").substringBefore('#')
    if ("filename" in query.lowercase()) {
        val decoded = runCatching { URLDecoder.decode(query, "UTF-8") }.getOrDefault("")
        DISPOSITION_NAME.find(decoded)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    val seg = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    // '+' is a literal plus in a path (only queries encode spaces as '+') -- shield it from decode
    return runCatching { URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") }.getOrDefault(seg)
}

/** Extension of the name [fileNameOf] resolves, lowercased, "" if none. Must NOT be taken from the
 *  whole URL: an extensionless path would otherwise return the tail of the host ("com/u/0/d/..."). */
fun extOf(url: String): String =
    fileNameOf(url).substringAfterLast('.', "").lowercase()

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
    if (ext.isNotBlank()) parts += ext // an extensionless file must not render a leading " · "
    humanSize(sizeBytes)?.let { parts += it }
    return parts.joinToString(" · ")
}
