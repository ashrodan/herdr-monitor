package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiTest {
    @Test fun normalizeServer() {
        assertEquals("https://box.ts.net:7400", normalizeServer("box.ts.net:7400"))
        assertEquals("https://box.ts.net:7400", normalizeServer(" https://box.ts.net:7400/ "))
        assertEquals("http://10.0.2.2:7410", normalizeServer("http://10.0.2.2:7410"))
        assertNull(normalizeServer(""))
        assertNull(normalizeServer("ftp://box"))
    }

    // The server's sameOrigin check: new URL(origin).host === Host header.
    @Test fun originMatchesWhatTheHostHeaderWillBe() {
        assertEquals("https://box.ts.net:7400", originOf("https://box.ts.net:7400"))
        assertEquals("https://box.ts.net", originOf("https://box.ts.net:443"))
        assertEquals("https://box.ts.net", originOf("https://box.ts.net"))
        assertEquals("http://10.0.2.2:7410", originOf("http://10.0.2.2:7410"))
    }

    @Test fun pathPartEncodes() {
        assertEquals("t1x", pathPart("t1x"))
        assertEquals("a%20b%2Fc", pathPart("a b/c"))
    }
}
