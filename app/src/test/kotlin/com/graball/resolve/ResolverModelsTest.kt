package com.graball.resolve

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// fake data only — not a real video
private const val FIXTURE = """
{
  "id": "abc123",
  "title": "Test Video",
  "webpage_url": "https://example.com/watch?v=abc123",
  "thumbnail": "https://example.com/thumb.jpg",
  "duration": 125.4,
  "extractor_key": "Generic",
  "formats": [
    {"format_id": "sb0", "ext": "mhtml", "protocol": "mhtml", "vcodec": "none", "acodec": "none"},
    {"format_id": "137", "ext": "mp4", "height": 1080, "vcodec": "avc1", "acodec": "none", "filesize": 104857600},
    {"format_id": "18", "ext": "mp4", "height": 360, "vcodec": "avc1", "acodec": "mp4a", "filesize": 20971520}
  ]
}
"""

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

class ResolverModelsTest {
    private val resolver = Resolver()

    private fun parseItem() = resolver.toItem(json.decodeFromString(YtDlpInfo.serializer(), FIXTURE))

    @Test
    fun `storyboard format is dropped`() {
        val item = parseItem()
        assertTrue(item.variants.none { it.ext == "mhtml" })
        assertEquals(2, item.variants.size)
    }

    @Test
    fun `video-only format needs mux, muxed does not`() {
        val item = parseItem()
        val hd = item.variants.first { it.formatId == "137" }
        val sd = item.variants.first { it.formatId == "18" }
        assertTrue(hd.needsMux)
        assertFalse(hd.hasAudio)
        assertFalse(sd.needsMux)
        assertTrue(sd.hasAudio)
    }

    @Test
    fun `best variant prefers muxed over higher-res video-only`() {
        val item = parseItem()
        val best = item.bestVariant()
        assertEquals("18", best?.formatId)
    }

    @Test
    fun `kind is video when any format has video`() {
        assertEquals(MediaKind.VIDEO, parseItem().kind)
    }

    @Test
    fun `extOf reads last path segment, not the whole url`() {
        // regression: extensionless path used to fall through to the tail of the host
        assertEquals("", extOf("https://lh3.googleusercontent.com/u/0/d/1AbC=w522-h391"))
        assertEquals("mp4", extOf("https://x.com/a/b/file.MP4?y=1"))
        assertEquals("", extOf("https://x.com/a.b/c"))
    }

    @Test
    fun `GoogleDrive folderId accepts folder urls`() {
        assertEquals(
            "1dQ4sx0-__Nvg65rxTSgQrl7VyW_FZ9QI",
            GoogleDrive.folderId("https://drive.google.com/drive/folders/1dQ4sx0-__Nvg65rxTSgQrl7VyW_FZ9QI?usp=sharing"),
        )
        assertEquals(
            "1dQ4sx0-__Nvg65rxTSgQrl7VyW_FZ9QI",
            GoogleDrive.folderId("https://drive.google.com/drive/u/0/folders/1dQ4sx0-__Nvg65rxTSgQrl7VyW_FZ9QI"),
        )
    }

    @Test
    fun `GoogleDrive folderId rejects non-folder urls`() {
        assertNull(GoogleDrive.folderId("https://drive.google.com/file/d/1dQ4sx0-__Nvg65rxTSgQrl7VyW_FZ9QI"))
        assertNull(GoogleDrive.folderId("https://example.com/drive/folders/1dQ4sx0-__Nvg65rxTSgQrl7VyW_FZ9QI"))
        assertNull(GoogleDrive.folderId("https://drive.google.com/drive/folders/tooshort"))
    }

    // builds the fixture in the real escaped shape (\x22 for ", \/ for /) rather than hand-escaping
    private fun escapeJs(raw: String) = raw.replace("\\", "\\\\").replace("\"", "\\x22").replace("/", "\\/")

    @Test
    fun `parseIvd reads fileId, name, kind and size from the embedded listing`() {
        val rawJson = """
            [[
              ["1eE-5jm2G8N5FL4HHeexrL0-Lg15bc0-D", null, "A Walk [kQot7nZLeJo].webm", "video/webm", null, null, null, null, null, null, null, null, null, 338040226],
              ["1Jp0I0tS-qMxtXNehGQW5_hWhwgC0FeeB", null, "notes.pdf", "application/pdf", null, null, null, null, null, null, null, null, null, "12345"],
              ["1SubfolderId00000000000000000000", null, "Subfolder", "application/vnd.google-apps.folder", null, null, null, null, null, null, null, null, null, null]
            ]]
        """.trimIndent()
        val html = "<script>window['_DRIVE_ivd'] = '${escapeJs(rawJson)}';</script>"

        val items = GoogleDrive.parseIvd(html)

        assertEquals(2, items?.size) // folder entry skipped
        val video = items!![0]
        assertEquals("A Walk [kQot7nZLeJo].webm", video.title)
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals(338040226L, video.variants.single().sizeBytes)
        assertEquals("https://drive.google.com/file/d/1eE-5jm2G8N5FL4HHeexrL0-Lg15bc0-D", video.sourceUrl)
        assertEquals("source/best", video.variants.single().formatId)

        val doc = items[1]
        assertEquals(MediaKind.DOC, doc.kind)
        assertEquals(12345L, doc.variants.single().sizeBytes)
    }

    @Test
    fun `parseIvd returns null when the marker is absent`() {
        assertNull(GoogleDrive.parseIvd("<html>no drive listing here</html>"))
    }

    @Test
    fun `error classification`() {
        assertEquals(ResolveError.NEEDS_LOGIN, classifyError("ERROR: Sign in to confirm your age"))
        assertEquals(ResolveError.DRM, classifyError("This video is DRM protected"))
        assertEquals(ResolveError.GEO_BLOCKED, classifyError("The uploader has not made this video available in your country"))
        assertEquals(ResolveError.EXTRACTOR_BROKEN, classifyError("Unsupported URL: foo"))
        assertEquals(ResolveError.NETWORK, classifyError("Unable to resolve host \"example.com\""))
        assertEquals(ResolveError.UNKNOWN, classifyError("something weird happened"))
        assertEquals(ResolveError.UNKNOWN, classifyError(null))
    }
}
