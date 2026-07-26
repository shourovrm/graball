package com.graball.cookies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieExportTest {

    @Test fun `builds one 7-field netscape line per pair`() {
        val lines = netscapeLines("example.com", "a=1; b=2")
        assertEquals(2, lines.size)
        assertEquals("example.com\tFALSE\t/\tTRUE\t0\ta\t1", lines[0])
        assertEquals("example.com\tFALSE\t/\tTRUE\t0\tb\t2", lines[1])
        lines.forEach { assertEquals(7, it.split("\t").size) }
    }

    @Test fun `value keeps every '=' after the first`() {
        val lines = netscapeLines("x.com", "token=abc=def==; s=1")
        assertEquals("abc=def==", lines[0].split("\t")[6])
    }

    @Test fun `empty or junk header yields no lines`() {
        assertTrue(netscapeLines("x.com", "").isEmpty())
        assertTrue(netscapeLines("x.com", "   ").isEmpty())
        assertTrue(netscapeLines("x.com", "novalue; =orphan").isEmpty())
    }

    @Test fun `control chars in a pair drop that pair, not the file`() {
        // a tab inside a value would make yt-dlp's parser echo the line into stderr
        val lines = netscapeLines("x.com", "bad=a\tb; good=1")
        assertEquals(1, lines.size)
        assertEquals("good", lines[0].split("\t")[5])
    }

    @Test fun `lines carry no newlines of their own`() {
        netscapeLines("x.com", "a=1; b=2").forEach { assertTrue("\n" !in it) }
    }
}
