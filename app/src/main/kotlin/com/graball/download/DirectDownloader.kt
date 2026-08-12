package com.graball.download

import android.content.Context
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// -- pure helpers (unit-testable, no network/Android calls) --

/** filename* (RFC 5987, percent-encoded) wins over plain filename=; both may be quoted. */
internal fun parseContentDisposition(header: String?): String? {
    if (header == null) return null
    Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let {
        return runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
    }
    Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.let {
        return it.groupValues[1].trim()
    }
    return null
}

/** No path separators, no control chars, no directory traversal, capped at 100 chars. */
internal fun sanitizeFileName(name: String): String {
    val clean = name.filterNot { it == '/' || it == '\\' || it.code < 0x20 }
    return clean.trim().take(100).ifBlank { "download" }
}

/** Splits [0, total) into up to [parts] contiguous ranges; last range absorbs the remainder. */
internal fun planChunks(total: Long, parts: Int): List<LongRange> {
    if (total <= 0 || parts <= 0) return emptyList()
    val n = minOf(parts.toLong(), total).toInt()
    val size = total / n
    return (0 until n).map { i ->
        val start = i * size
        val end = if (i == n - 1) total - 1 else start + size - 1
        start..end
    }
}

/** Guard: a Range request must come back 206 with a matching Content-Range start, or resuming
 * would append onto a full-body response and corrupt the file. */
internal fun canResumeFrom(status: Int, contentRange: String?, expectedStart: Long): Boolean {
    if (status != 206) return false
    val start = contentRange?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
    return start == expectedStart
}

private fun resolveFinalName(baseName: String, disposition: String?, url: String, contentType: String?): String {
    parseContentDisposition(disposition)?.let { return sanitizeFileName(it) }
    val urlBase = runCatching { URL(url).path.substringAfterLast('/') }.getOrNull()?.takeIf { it.isNotBlank() }
    if (urlBase != null) return sanitizeFileName(urlBase)
    val ext = contentType?.substringBefore(';')?.trim()?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return sanitizeFileName(if (ext != null) "$baseName.$ext" else baseName)
}

/** Sums per-part byte counts and forwards to [onProgress] throttled to ~2/sec. Racy last-write on
 * [lastEmit] under concurrent parts is fine -- progress reporting is best-effort, not correctness. */
private class ProgressAggregator(private val total: Long, private val onProgress: (Long, Long) -> Unit, parts: Int) {
    private val perPart = AtomicLongArray(parts)
    @Volatile private var lastEmit = 0L
    fun update(part: Int, bytes: Long) {
        perPart.set(part, bytes)
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 500) {
            lastEmit = now
            var sum = 0L
            for (i in 0 until perPart.length()) sum += perPart.get(i)
            onProgress(sum, total)
        }
    }
}

object DirectDownloader {
    class Cancelled : Exception()

    /** Server answered a mid-file Range with a full body: chunking is impossible, not retryable.
     * Deliberately NOT an IOException so the per-part retry loop doesn't swallow it. */
    private class RangeIgnored : Exception()

    private val cancelledIds = ConcurrentHashMap.newKeySet<Long>()

    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_RETRIES = 3
    private const val RETRY_BACKOFF_MS = 1000L
    private const val CHUNK_THRESHOLD = 8L * 1024 * 1024
    private const val PARTS = 4
    private const val BUFFER_SIZE = 8 * 1024

    fun cancel(id: Long) {
        cancelledIds.add(id)
    }

    private suspend fun checkCancelled(id: Long) {
        if (cancelledIds.contains(id)) throw Cancelled()
        coroutineContext.ensureActive() // also honour plain coroutine cancellation
    }

    suspend fun download(
        context: Context,
        url: String,
        destDir: File,
        baseName: String,
        id: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        cancelledIds.remove(id) // a stale cancel from a previous call must not sabotage this one
        val ua = WebSettings.getDefaultUserAgent(context)
        val cookie = CookieManager.getInstance().getCookie(url) // never logged, never written out

        // ponytail: one extra round trip to probe size/resumability/filename instead of piping
        // the probe connection's body into the actual download -- simpler error handling
        val probe = connectFollowingRedirects(url, ua, cookie, 0, null)
        val total = if (probe.responseCode == 206) {
            probe.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: probe.contentLengthLong
        } else {
            probe.contentLengthLong
        }
        // only a real 206 proves ranges work; an Accept-Ranges header alone is a promise servers break
        val acceptsRanges = probe.responseCode == 206
        val finalName = resolveFinalName(baseName, probe.getHeaderField("Content-Disposition"), url, probe.getHeaderField("Content-Type"))
        probe.disconnect()

        val assembled = if (acceptsRanges && total >= CHUNK_THRESHOLD) {
            try {
                downloadChunked(url, ua, cookie, destDir, id, total, onProgress)
            } catch (e: RangeIgnored) {
                // stale part files would be read as valid resume state -- drop them before falling back
                destDir.listFiles()?.filter { it.name.startsWith("dl-$id.p") }?.forEach { it.delete() }
                downloadSingleStream(url, ua, cookie, destDir, id, total, onProgress)
            }
        } else {
            downloadSingleStream(url, ua, cookie, destDir, id, total, onProgress)
        }
        val finalFile = File(destDir, finalName)
        if (!assembled.renameTo(finalFile)) {
            FileInputStream(assembled).use { inp -> FileOutputStream(finalFile).use { inp.copyTo(it) } }
            assembled.delete()
        }
        onProgress(finalFile.length(), if (total > 0) total else finalFile.length())
        finalFile
    }

    private suspend fun downloadSingleStream(
        url: String, ua: String, cookie: String?, destDir: File, id: Long, total: Long,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val part = File(destDir, "dl-$id.part")
        val aggregator = ProgressAggregator(total, onProgress, 1)
        downloadPart(url, ua, cookie, part, id, 0, 0L, null, aggregator)
        return part
    }

    private suspend fun downloadChunked(
        url: String, ua: String, cookie: String?, destDir: File, id: Long, total: Long,
        onProgress: (Long, Long) -> Unit,
    ): File = coroutineScope {
        val ranges = planChunks(total, PARTS)
        val aggregator = ProgressAggregator(total, onProgress, ranges.size)
        val partFiles = ranges.indices.map { File(destDir, "dl-$id.p$it") }
        ranges.mapIndexed { i, r -> async { downloadPart(url, ua, cookie, partFiles[i], id, i, r.first, r.last, aggregator) } }.awaitAll()
        val merged = File(destDir, "dl-$id.merged")
        FileOutputStream(merged).use { out -> partFiles.forEach { pf -> FileInputStream(pf).use { it.copyTo(out) } } }
        partFiles.forEach { it.delete() }
        merged
    }

    /** Downloads one byte range (or, if [chunkEnd] is null, everything from [chunkStart] on) into
     * [partFile], resuming from the file's current length. Retries up to [MAX_RETRIES] times. */
    private suspend fun downloadPart(
        url: String, ua: String, cookie: String?, partFile: File, id: Long,
        partIndex: Int, chunkStart: Long, chunkEnd: Long?, aggregator: ProgressAggregator,
    ) {
        var attempt = 0
        while (true) {
            checkCancelled(id)
            val already = partFile.length()
            // a full part ends exactly at chunkEnd; asking for bytes=(chunkEnd+1)- would be a 416
            if (chunkEnd != null && chunkStart + already > chunkEnd) {
                aggregator.update(partIndex, already) // resumed run, this part was already done
                return
            }
            val absStart = chunkStart + already
            val conn = connectFollowingRedirects(url, ua, cookie, absStart, chunkEnd)
            try {
                // absStart, not `already`: chunk 2+ starts mid-file even with an empty part file, so a
                // 200 full-body response there would write the whole file into one chunk slot
                val safe = absStart == 0L || canResumeFrom(conn.responseCode, conn.getHeaderField("Content-Range"), absStart)
                if (!safe) {
                    if (absStart > 0 && chunkEnd != null) throw RangeIgnored() // caller falls back to one stream
                    partFile.writeBytes(ByteArray(0)) // server ignored our Range -- restart this part
                    continue
                }
                FileOutputStream(partFile, true).use { out ->
                    conn.inputStream.use { inp ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var written = already
                        while (true) {
                            checkCancelled(id)
                            val n = inp.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            written += n
                            aggregator.update(partIndex, written)
                        }
                    }
                }
                return
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_RETRIES) throw e
                delay(RETRY_BACKOFF_MS * attempt)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Manual redirect following (HttpURLConnection refuses cross-protocol redirects on its own),
     * re-sending headers on every hop, capped at [MAX_REDIRECTS]. */
    private fun connectFollowingRedirects(
        urlStr: String, ua: String, cookie: String?, rangeStart: Long, rangeEnd: Long?,
    ): HttpURLConnection {
        var current = urlStr
        repeat(MAX_REDIRECTS + 1) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", ua)
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            // gzip would be transparently decoded, so byte offsets stop matching Content-Length and
            // every resume would land at the wrong place -- ask for the bytes as they are on disk
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Range", if (rangeEnd != null) "bytes=$rangeStart-$rangeEnd" else "bytes=$rangeStart-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException("redirect with no Location")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            return conn
        }
        throw IOException("too many redirects")
    }
}
