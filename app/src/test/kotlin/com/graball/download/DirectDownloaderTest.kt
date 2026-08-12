package com.graball.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDownloaderTest {
    @Test fun `plain quoted filename`() {
        assertEquals("foo.pdf", parseContentDisposition("""attachment; filename="foo.pdf""""))
    }

    @Test fun `unquoted filename`() {
        assertEquals("foo.pdf", parseContentDisposition("attachment; filename=foo.pdf"))
    }

    @Test fun `rfc5987 encoded filename wins over plain filename`() {
        val header = """attachment; filename="fallback.pdf"; filename*=UTF-8''na%C3%AFve%20file.pdf"""
        assertEquals("naïve file.pdf", parseContentDisposition(header))
    }

    @Test fun `no filename present`() {
        assertNull(parseContentDisposition("attachment"))
        assertNull(parseContentDisposition(null))
    }

    @Test fun `sanitize strips path traversal and separators`() {
        assertEquals("....etcpasswd", sanitizeFileName("../../etc/passwd"))
        assertEquals("etcpasswd", sanitizeFileName("/etc/passwd"))
    }

    @Test fun `sanitize strips control chars and caps at 100`() {
        val withTab = "a" + '\t'.toString() + "b"
        assertEquals("ab", sanitizeFileName(withTab))
        assertEquals(100, sanitizeFileName("x".repeat(500)).length)
    }

    @Test fun `sanitize falls back on blank`() {
        assertEquals("download", sanitizeFileName("   "))
    }

    @Test fun `planChunks divides evenly`() {
        val ranges = planChunks(8, 4)
        assertEquals(listOf(0L..1L, 2L..3L, 4L..5L, 6L..7L), ranges)
    }

    @Test fun `planChunks remainder goes to last chunk`() {
        val ranges = planChunks(10, 4)
        assertEquals(listOf(0L..1L, 2L..3L, 4L..5L, 6L..9L), ranges)
        assertEquals(10L, ranges.sumOf { it.last - it.first + 1 })
    }

    @Test fun `planChunks total smaller than parts yields one range per byte`() {
        val ranges = planChunks(3, 4)
        assertEquals(listOf(0L..0L, 1L..1L, 2L..2L), ranges)
    }

    @Test fun `planChunks non-positive inputs yield empty list`() {
        assertTrue(planChunks(0, 4).isEmpty())
        assertTrue(planChunks(10, 0).isEmpty())
    }

    @Test fun `canResumeFrom true on matching 206`() {
        assertTrue(canResumeFrom(206, "bytes 100-999/1000", 100))
    }

    @Test fun `canResumeFrom false on 200 -- server ignored the Range header`() {
        assertFalse(canResumeFrom(200, null, 100))
    }

    @Test fun `canResumeFrom false when Content-Range start does not match`() {
        assertFalse(canResumeFrom(206, "bytes 0-999/1000", 100))
    }

    @Test fun `canResumeFrom false when Content-Range missing on a 206`() {
        assertFalse(canResumeFrom(206, null, 100))
    }
}
