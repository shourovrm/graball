package com.graball.resolve

import android.content.Context
import com.graball.cookies.CookieExport
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface ResolveResult {
    data class Success(val items: List<ResolvedItem>) : ResolveResult
    data class Failure(val error: ResolveError, val rawLog: String) : ResolveResult
}

enum class ResolveError { NEEDS_LOGIN, DRM, GEO_BLOCKED, EXTRACTOR_BROKEN, NETWORK, UNKNOWN }

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// direct-file extensions we don't need yt-dlp for
private val DIRECT_KINDS: Map<String, MediaKind> = buildMap {
    listOf("mp4", "webm", "mkv", "mov", "avi").forEach { put(it, MediaKind.VIDEO) }
    listOf("mp3", "m4a", "aac", "flac", "wav", "ogg").forEach { put(it, MediaKind.AUDIO) }
    listOf("jpg", "jpeg", "png", "gif", "webp", "bmp").forEach { put(it, MediaKind.IMAGE) }
    listOf("pdf", "doc", "docx", "txt", "epub").forEach { put(it, MediaKind.DOC) }
    listOf("zip", "7z", "rar", "tar", "gz").forEach { put(it, MediaKind.ARCHIVE) }
    put("apk", MediaKind.OTHER)
}

/** [context] is only needed for the cookie export; callers that never pass withCookies can omit it. */
class Resolver(private val context: Context? = null) {
    suspend fun resolve(url: String, withCookies: Boolean = false): ResolveResult = withContext(Dispatchers.IO) {
        directFile(url)?.let { return@withContext ResolveResult.Success(listOf(it)) }

        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-warnings")
            addOption("--playlist-items", "1-25") // cap playlist size, keep per-video formats
        }
        val ctx = context
        if (withCookies && ctx != null && CookieExport.haveCookiesFor(url)) {
            CookieExport.withCookieFile(ctx, url) { path ->
                if (path != null) request.addOption("--cookies", path)
                exec(request)
            }
        } else {
            exec(request)
        }
    }

    /** rawLog is yt-dlp stderr only — it may echo the cookie file *path*, never its contents. */
    private fun exec(request: YoutubeDLRequest): ResolveResult =
        try {
            val response = YoutubeDL.getInstance().execute(request, null, null)
            val info = json.decodeFromString(YtDlpInfo.serializer(), response.out)
            ResolveResult.Success(toItems(info))
        } catch (e: YoutubeDLException) {
            val firstLine = e.message?.lineSequence()?.firstOrNull() ?: ""
            ResolveResult.Failure(classifyError(e.message), "${e::class.simpleName}: $firstLine")
        }

    internal fun toItems(info: YtDlpInfo): List<ResolvedItem> =
        if (info.type == "playlist" && info.entries != null) {
            info.entries.map(::toItem)
        } else {
            listOf(toItem(info))
        }

    internal fun toItem(info: YtDlpInfo): ResolvedItem {
        val variants = (info.formats ?: emptyList())
            .filterNot { it.protocol == "mhtml" || it.ext == "mhtml" }
            .filterNot { it.vcodec == "none" && it.acodec == "none" }
            .map { f ->
                val hasVideo = f.vcodec != null && f.vcodec != "none"
                val hasAudio = f.acodec != null && f.acodec != "none"
                Variant(
                    formatId = f.format_id ?: "",
                    label = buildVariantLabel(f.height, f.ext ?: "?", f.filesize ?: f.filesize_approx),
                    ext = f.ext ?: "",
                    sizeBytes = f.filesize ?: f.filesize_approx,
                    hasVideo = hasVideo,
                    hasAudio = hasAudio,
                    height = f.height,
                    needsMux = hasVideo && !hasAudio,
                )
            }
        val kind = when {
            variants.any { it.hasVideo } -> MediaKind.VIDEO
            variants.any { it.hasAudio } -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
        return ResolvedItem(
            sourceUrl = info.webpage_url ?: info.url ?: "",
            title = info.title ?: info.id ?: "untitled",
            thumbnail = info.thumbnail,
            durationSec = info.duration?.toLong(),
            kind = kind,
            variants = variants,
        )
    }

    private fun directFile(url: String): ResolvedItem? {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = DIRECT_KINDS[ext] ?: return null
        return ResolvedItem(
            sourceUrl = url,
            title = name,
            thumbnail = null,
            durationSec = null,
            kind = kind,
            variants = listOf(Variant("direct", ext, ext, null, kind == MediaKind.VIDEO, kind == MediaKind.AUDIO, null, false)),
        )
    }
}

/** Classifies a yt-dlp failure message into a user-facing bucket. Never pass URL/cookie text in here. */
internal fun classifyError(message: String?): ResolveError {
    val m = message?.lowercase() ?: return ResolveError.UNKNOWN
    return when {
        listOf("login", "sign in", "cookies", "account").any { it in m } -> ResolveError.NEEDS_LOGIN
        "drm" in m -> ResolveError.DRM
        listOf("geo", "in your country", "region").any { it in m } -> ResolveError.GEO_BLOCKED
        listOf("unable to extract", "unsupported url").any { it in m } -> ResolveError.EXTRACTOR_BROKEN
        listOf("network", "timed out", "connection", "resolve host").any { it in m } -> ResolveError.NETWORK
        else -> ResolveError.UNKNOWN
    }
}
