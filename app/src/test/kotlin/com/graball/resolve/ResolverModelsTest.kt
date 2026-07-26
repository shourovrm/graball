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
