package com.graball.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadServiceTest {
    @Test fun `strips plain id suffix before extension`() {
        assertEquals("artemis-press-kit.pdf", publishName("artemis-press-kit-42.pdf", 42))
    }

    @Test fun `strips id but keeps fragment extension chain intact`() {
        assertEquals("title.f137.mp4", publishName("title-42.f137.mp4", 42))
    }

    @Test fun `does not touch a name with no id suffix`() {
        assertEquals("clean-name.jpg", publishName("clean-name.jpg", 42))
    }

    @Test fun `only strips the trailing id-shaped suffix, not an earlier lookalike`() {
        assertEquals("Update-42.1 Notes.pdf", publishName("Update-42.1 Notes-42.pdf", 42))
    }

    @Test fun `different id does not match`() {
        assertEquals("title-7.pdf", publishName("title-7.pdf", 42))
    }

    // Drive titles already end in the extension, so yt-dlp's ".%(ext)s" doubles it
    @Test fun `collapses a doubled extension left by a title that already had one`() {
        assertEquals(
            "Bryce Canyon National Park [7uKucJejmTc].webm",
            publishName("Bryce Canyon National Park [7uKucJejmTc].webm-42.webm", 42),
        )
    }

    @Test fun `collapses a doubled extension regardless of case`() {
        assertEquals("clip.MP4", publishName("clip.MP4-42.mp4", 42))
    }

    @Test fun `leaves a non-repeating extension chain alone`() {
        assertEquals("archive.tar.gz", publishName("archive.tar-42.gz", 42))
    }
}
