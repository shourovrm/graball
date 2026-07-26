package com.graball.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test fun `parses full progress line`() {
        val p = parseProgressLine("""{"pct":" 42.3%","dl":1048576,"tot":2097152,"eta":12,"fi":3,"fc":10}""")
        requireNotNull(p)
        assertEquals(42.3f, p.percent(), 0.001f)
        assertEquals(1048576L, p.dl.toLong())
        assertEquals(2097152L, p.tot.toLong())
        assertEquals(12L, p.eta.toLong())
        assertEquals(3, p.fi)
        assertEquals(10, p.fc)
    }

    @Test fun `missing fragment fields default to zero`() {
        val p = parseProgressLine("""{"pct":"100%","dl":500,"tot":500,"eta":0}""")
        requireNotNull(p)
        assertEquals(0, p.fi)
        assertEquals(0, p.fc)
    }

    @Test fun `non-json lines are ignored, never treated as progress`() {
        assertNull(parseProgressLine("[Merger] Merging formats into \"foo.mp4\""))
        assertNull(parseProgressLine(""))
        assertNull(parseProgressLine("WARNING: some yt-dlp warning text"))
    }

    @Test fun `percent string with leading spaces parses`() {
        val p = parseProgressLine("""{"pct":"  7%","dl":0,"tot":0,"eta":0,"fi":0,"fc":0}""")
        assertEquals(7f, p!!.percent(), 0.001f)
    }

    @Test fun `malformed json does not throw`() {
        assertNull(parseProgressLine("{not valid json"))
    }
}
