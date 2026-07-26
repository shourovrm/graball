package com.graball.resolve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors yt-dlp `-J` output. Field names match json keys (yt-dlp uses snake_case). */
@Serializable
data class YtDlpInfo(
    val id: String? = null,
    val title: String? = null,
    val webpage_url: String? = null,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val extractor_key: String? = null,
    @SerialName("_type") val type: String? = null,
    val entries: List<YtDlpInfo>? = null,
    val formats: List<YtDlpFormat>? = null,
    val url: String? = null,
    val ext: String? = null,
    val filesize: Long? = null,
    val filesize_approx: Long? = null,
)

@Serializable
data class YtDlpFormat(
    val format_id: String? = null,
    val ext: String? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val abr: Double? = null,
    val vbr: Double? = null,
    val tbr: Double? = null,
    val filesize: Long? = null,
    val filesize_approx: Long? = null,
    val format_note: String? = null,
    val protocol: String? = null,
    val url: String? = null,
)
