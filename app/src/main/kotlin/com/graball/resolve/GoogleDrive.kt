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
    // Drive redirects mobile user agents to /drive/mobile/folders/<id>, so the in-app WebView's
    // URL never carries the plain /drive/folders/ form -- both must match or the browser's Grab
    // button silently falls back to sniffing the page's icon assets.
    private val FOLDER_URL =
        Regex("""https?://(?:drive|docs)\.google\.com/drive/(?:u/\d+/)?(?:mobile/)?folders/([\w-]{20,})""")
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

    // Google-native docs (Sheets/Docs/Slides/Drawings) have no file bytes -- yt-dlp 400s trying to
    // fetch them as a video, and there's nothing to download at drive.google.com/file/d/<id> either.
    // They must go through the docs.google.com export endpoint instead. mime -> (export path segment, ext).
    private const val COLAB_MIME = "application/vnd.google.colaboratory"

    private val GOOGLE_EXPORT = mapOf(
        "application/vnd.google-apps.spreadsheet" to ("spreadsheets" to "xlsx"),
        "application/vnd.google-apps.document" to ("document" to "docx"),
        "application/vnd.google-apps.presentation" to ("presentation" to "pptx"),
        "application/vnd.google-apps.drawing" to ("drawings" to "png"),
    )

    private fun toResolvedItem(item: JsonArray, index: Int): ResolvedItem? {
        val fileId = item.str(0) ?: return null
        val name = item.str(2) ?: return null
        val mime = item.str(3) ?: ""
        if (mime == "application/vnd.google-apps.folder") return null // nested folders: no recursion

        val export = GOOGLE_EXPORT[mime]
        // forms/sites/maps/scripts/shortcuts: no file bytes and no export endpoint -- not downloadable
        if (export == null && mime.startsWith("application/vnd.google-apps.")) return null

        val sourceUrl: String
        val ext: String
        val size: Long?
        val title: String
        if (export != null) {
            val (docType, exportExt) = export
            sourceUrl = "https://docs.google.com/$docType/d/$fileId/export?format=$exportExt"
            ext = exportExt
            // the listing's size [13] is the Google-native size, not the export size -- known wrong
            // (verified: a Sheet listed 375772 exported to 1010514 bytes), so don't show it at all
            size = null
            // native docs carry no extension in their name; append the export one, once
            title = if (name.endsWith(".$exportExt", ignoreCase = true)) name else "$name.$exportExt"
        } else {
            // real file bytes -- Drive's own uploads-served-as-uploads endpoint. confirm=t skips the
            // small "can't scan for viruses" click-through; NOT verified against the large-file
            // interstitial (server-side virus scan on files >~100MB), which serves an HTML warning
            // page instead of bytes. DirectDownloader has no HTML sniff, so a big file here could
            // land on disk as an interstitial page rather than the real download.
            sourceUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            // Colab notebooks are real files but Drive often stores the name without the suffix,
            // so they'd otherwise be saved extensionless
            ext = extOf(name).ifEmpty { if (mime == COLAB_MIME) "ipynb" else "" }
            // size comes through as either a JSON number or a numeric string -- .content is the raw
            // text either way, so a single toLongOrNull covers both without a type check
            size = item.str(13)?.toLongOrNull()
            title = if (ext.isNotEmpty() && !name.endsWith(".$ext", ignoreCase = true)) "$name.$ext" else name
        }
        val kind = kindOf(mime)
        val variant = Variant(
            formatId = DIRECT_FORMAT,
            label = ext,
            ext = ext,
            sizeBytes = size,
            hasVideo = kind == MediaKind.VIDEO,
            hasAudio = kind == MediaKind.VIDEO || kind == MediaKind.AUDIO,
            height = null,
            needsMux = false,
        )
        return ResolvedItem(
            sourceUrl = sourceUrl,
            title = title,
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
        mime.startsWith("image/") || mime == "application/vnd.google-apps.drawing" -> MediaKind.IMAGE
        mime == "application/pdf" || mime == "application/msword" ||
            mime.contains("officedocument") || mime.startsWith("text/") ||
            mime == "application/vnd.google-apps.spreadsheet" ||
            mime == "application/vnd.google-apps.document" ||
            mime == "application/vnd.google-apps.presentation" ||
            mime == "application/vnd.google.colaboratory" || mime == "application/json" -> MediaKind.DOC
        listOf("zip", "x-7z", "rar", "tar", "gzip").any { it in mime } -> MediaKind.ARCHIVE
        else -> MediaKind.OTHER
    }
}
