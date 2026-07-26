package com.graball.cookies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieExportTest {

    @Test fun `builds one 7-field netscape line per pair`() {
        val lines = netscapeLines(".example.com", "a=1; b=2")
        assertEquals(2, lines.size)
        assertEquals(".example.com\tTRUE\t/\tTRUE\t0\ta\t1", lines[0])
        assertEquals(".example.com\tTRUE\t/\tTRUE\t0\tb\t2", lines[1])
        lines.forEach { assertEquals(7, it.split("\t").size) }
    }

    @Test fun `value keeps every '=' after the first`() {
        val lines = netscapeLines(".x.com", "token=abc=def==; s=1")
        assertEquals("abc=def==", lines[0].split("\t")[6])
    }

    @Test fun `empty or junk header yields no lines`() {
        assertTrue(netscapeLines(".x.com", "").isEmpty())
        assertTrue(netscapeLines(".x.com", "   ").isEmpty())
        assertTrue(netscapeLines(".x.com", "novalue; =orphan").isEmpty())
    }

    @Test fun `lines carry no newlines of their own`() {
        netscapeLines(".x.com", "a=1; b=2").forEach { assertTrue("\n" !in it) }
    }

    @Test fun `registrable domain strips subdomains, honours two-part TLDs`() {
        assertEquals("example.com", registrableDomain("www.example.com"))
        assertEquals("example.com", registrableDomain("example.com"))
        assertEquals("example.co.uk", registrableDomain("m.videos.example.co.uk"))
        assertEquals("localhost", registrableDomain("localhost"))
    }
}
