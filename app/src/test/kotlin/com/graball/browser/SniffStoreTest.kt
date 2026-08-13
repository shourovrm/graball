package com.graball.browser

import com.graball.resolve.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffStoreTest {

    @Test
    fun `drive thumbnail preview is dropped even with an image mime hint`() {
        val store = SniffStore()
        store.add(
            "https://lh3.googleusercontent.com/u/0/d/1AbC=w522-h391-p-k-nu-iv11",
            Source.NETWORK,
            mimeHint = "image/avif",
        )
        assertTrue(store.hits.isEmpty())
    }

    @Test
    fun `drive thumbnail preview dropped on lh4 sibling host and thumbnail endpoint`() {
        val store = SniffStore()
        store.add("https://lh4.googleusercontent.com/u/0/d/xyz=w100-h100", Source.NETWORK, mimeHint = "image/jpeg")
        store.add("https://drive.google.com/thumbnail?id=abc&sz=w200", Source.NETWORK, mimeHint = "image/jpeg")
        assertTrue(store.hits.isEmpty())
    }

    @Test
    fun `extensionless cdn image on another host still classifies via mime hint`() {
        val store = SniffStore()
        store.add("https://cdn.example.com/img/abc=w200-h200", Source.NETWORK, mimeHint = "image/jpeg")
        assertEquals(1, store.hits.size)
        val hit = store.hits.single()
        assertEquals(MediaKind.IMAGE, hit.kind)
        assertNull(hit.ext)
    }

    @Test
    fun `uppercase extension classifies as video with lowercased ext`() {
        val store = SniffStore()
        store.add("https://x.com/a/b/clip.MP4", Source.NETWORK)
        val hit = store.hits.single()
        assertEquals(MediaKind.VIDEO, hit.kind)
        assertEquals("mp4", hit.ext)
    }

    @Test
    fun `dedup by url`() {
        val store = SniffStore()
        val url = "https://x.com/a/b/clip.mp4"
        store.add(url, Source.NETWORK)
        store.add(url, Source.NETWORK)
        assertEquals(1, store.hits.size)
    }

    @Test
    fun `skip-ext noise like js is dropped`() {
        val store = SniffStore()
        store.add("https://x.com/app.js", Source.NETWORK)
        assertTrue(store.hits.isEmpty())
    }
}
