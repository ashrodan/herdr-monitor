package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.file.Files

class CacheTest {
    private fun dir() = Files.createTempDirectory("cache").toFile()

    @Test fun writesAndReadsPerServer() {
        var now = 1_000L
        val c = Cache(dir()) { now }
        c.write("http://10.0.2.2:7410", Cache.SNAPSHOT, "{\"a\":1}\nsecond line")
        assertEquals(1_000L to "{\"a\":1}\nsecond line", c.read("http://10.0.2.2:7410", Cache.SNAPSHOT))
        assertNull(c.read("https://other:7400", Cache.SNAPSHOT))
        now += Cache.MAX_AGE_MS + 1
        assertNull("older than a day", c.read("http://10.0.2.2:7410", Cache.SNAPSHOT))
    }

    @Test fun slotIsAFolderName() {
        val c = Cache(dir())
        assertEquals("https_host_tail_ts_net_7400", c.slot("https://Host.tail.ts.net:7400"))
        assertEquals("default", c.slot("://"))
    }

    @Test fun missingOrBrokenFileIsNull() {
        val d = dir()
        val c = Cache(d)
        assertNull(c.read("http://x", Cache.OVERVIEW))
        java.io.File(d, c.slot("http://x")).mkdirs()
        java.io.File(java.io.File(d, c.slot("http://x")), "overview.txt").writeText("not a time\n{}")
        assertNull(c.read("http://x", Cache.OVERVIEW))
    }

    @Test fun chatCacheDropsLeastRecentlyUsed() {
        val c = ChatCache(2)
        val a = Chat(true, version = "1")
        val b = Chat(true, version = "2")
        val d = Chat(true, version = "3")
        c.put("s", "a", a); c.put("s", "b", b)
        assertSame(a, c.get("s", "a"))
        c.put("s", "d", d)
        assertNull("b was used least recently", c.get("s", "b"))
        assertSame(a, c.get("s", "a"))
        assertNull("another server", c.get("t", "a"))
    }
}
