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

    @Test fun shortHostAndPort() {
        assertEquals("ashleys-macbook-pro-2", shortHost("https://ashleys-macbook-pro-2.tail5265d4.ts.net:7400"))
        assertEquals("10.0.2.2", shortHost("http://10.0.2.2:7410"))
        assertEquals(7400, portOf("https://box.ts.net:7400"))
        assertEquals(443, portOf("https://box.ts.net"))
    }

    @Test fun appUrlUsesTheMachineTailnetNameNeverLocalhost() {
        val local = Terminal("t8t", listen = Listen("http", 5223, null))
        assertEquals("http://box.ts.net:5223", appUrl(local, "https://box.ts.net:7400"))
        assertEquals("https://herdr-1.ts.net:8443", appUrl(Terminal("dai/t1", listen = Listen("https", 8443, "herdr-1.ts.net")), "https://box.ts.net:7400"))
        assertEquals(null, appUrl(Terminal("dai/t2", listen = Listen("http", 3000, null)), "https://box.ts.net:7400"))
        assertEquals(null, appUrl(Terminal("t1"), "https://box.ts.net:7400"))
    }

    @Test fun pathPartEncodes() {
        assertEquals("t1x", pathPart("t1x"))
        assertEquals("a%20b%2Fc", pathPart("a b/c"))
    }
}
