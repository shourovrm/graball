package com.graball.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Mirrors DownloadService.PROGRESS_TEMPLATE JSON shape. Numeric fields are Double -- yt-dlp's
 * `|0` fallback can render either an int or float depending on the field, so accept both. */
@Serializable
data class ProgressLine(
    val pct: String = "",
    val dl: Double = 0.0,
    val tot: Double = 0.0,
    val eta: Double = 0.0,
    val fi: Int = 0,
    val fc: Int = 0,
)

/** Parses one yt-dlp callback line. Null if it's not a progress-template JSON line
 * (e.g. a plain log line like "[Merger] ..."). Never touches stderr. */
internal fun parseProgressLine(line: String): ProgressLine? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        json.decodeFromString(ProgressLine.serializer(), trimmed)
    } catch (e: Exception) {
        null
    }
}

/** " 42.3%" / "100%" -> 42.3f / 100f. */
internal fun ProgressLine.percent(): Float =
    pct.trim().removeSuffix("%").toFloatOrNull() ?: 0f
