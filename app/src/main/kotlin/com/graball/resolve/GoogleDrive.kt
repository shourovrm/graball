package com.graball.resolve

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/** Native Google Drive folder listing — yt-dlp's GoogleDrive:Folder extractor is dead upstream
 *  (verified: `expected string or bytes-like object, got 'bool'`). The folder page itself embeds
 *  the whole listing in a JS global, so we scrape that instead of the API. */
object GoogleDrive {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val FOLDER_URL = Regex("""https?://(?:drive|docs)\.google\.com/drive/(?:u/\d+/)?folders/([\w-]{20,})""")
    private val IVD_RE = Regex("""window\['_DRIVE_ivd'\]\s*=\s*'((?:\\.|[^'\\])*)'""")

    /** Drive folder id, or null when [url] is not a Drive folder link. */
    fun folderId(url: String): String? = FOLDER_URL.find(url)?.groupValues?.get(1)

    /** Folder contents from the folder page's embedded listing.
     *  Returns null on ANY failure (non-folder url, network, shape drift) so callers fall back. */
    suspend fun listFolder(url: String): List<ResolvedItem>? = withContext(Dispatchers.IO) {
        runCatching {
            val id = folderId(url) ?: return@runCatching null
            val html = fetch("https://drive.google.com/drive/folders/$id") ?: return@runCatching null
            parseIvd(html)
        }.getOrNull()
    }

    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            )
            if (conn.responseCode !in 200..299) return null
            // readNBytes(int) needs API 33; minSdk here is 26, so read+cap manually
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { inp ->
                while (out.size() < MAX_BYTES) {
                    val n = inp.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    /** Un-escapes the JS single-quoted string literal wrapping the JSON (\x22, \/, \\, ...) then
     *  parses element [0] of the array as the item list. Index-based JSON access is a heuristic
     *  against Google's page shape and MUST degrade to null rather than throw -- wrapped by caller. */
    internal fun parseIvd(html: String): List<ResolvedItem>? {
        val escaped = IVD_RE.find(html)?.groupValues?.get(1) ?: return null
        val jsonStr = unescapeJsString(escaped)
        val root = Json.parseToJsonElement(jsonStr).jsonArray
        val items = root[0].jsonArray
        // one odd entry must cost one row, not the whole folder
        return items.mapIndexedNotNull { i, el ->
            (el as? JsonArray)?.let { runCatching { toResolvedItem(it, i) }.getOrNull() }
        }.ifEmpty { null }
    }

    private fun unescapeJsString(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'x' -> {
                        val hex = s.substring(i + 2, i + 4)
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    else -> { out.append(n); i += 2 } // \/, \\, \', \" -> the literal char
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Field read that never throws: a nested array/object at an index we expected to be scalar
     *  must yield null, not blow up the whole listing. */
    private fun JsonArray.str(i: Int): String? =
        (getOrNull(i) as? JsonPrimitive)?.takeIf { it != JsonNull }?.content

    private fun toResolvedItem(item: JsonArray, index: Int): ResolvedItem? {
        val fileId = item.str(0) ?: return null
        val name = item.str(2) ?: return null
        val mime = item.str(3) ?: ""
        if (mime == "application/vnd.google-apps.folder") return null // nested folders: no recursion
        // size comes through as either a JSON number or a numeric string -- .content is the raw text
        // either way, so a single toLongOrNull covers both without a type check
        val size = item.str(13)?.toLongOrNull()
        val kind = kindOf(mime)
        val ext = extOf(name)
        val variant = Variant(
            // "source/best": a real yt-dlp format selector (Drive's own `source` format, falling back
            // to `best`) -- NOT DIRECT_FORMAT, so DownloadService passes it to `-f` and yt-dlp keeps
            // handling the virus-scan confirmation page, cookies and resume.
            formatId = "source/best",
            label = ext.ifEmpty { mime },
            ext = ext,
            sizeBytes = size,
            hasVideo = kind == MediaKind.VIDEO,
            hasAudio = kind == MediaKind.VIDEO || kind == MediaKind.AUDIO,
            height = null,
            needsMux = false,
        )
        return ResolvedItem(
            sourceUrl = "https://drive.google.com/file/d/$fileId",
            title = name,
            thumbnail = null,
            durationSec = null,
            kind = kind,
            id = index,
            variants = listOf(variant),
        )
    }

    private fun kindOf(mime: String): MediaKind = when {
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime == "application/pdf" || mime == "application/msword" ||
            mime.contains("officedocument") || mime.startsWith("text/") -> MediaKind.DOC
        listOf("zip", "x-7z", "rar", "tar", "gzip").any { it in mime } -> MediaKind.ARCHIVE
        else -> MediaKind.OTHER
    }
}
