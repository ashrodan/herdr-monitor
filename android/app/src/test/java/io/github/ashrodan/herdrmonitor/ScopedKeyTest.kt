package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Seen, posted, opened and pinned are kept per server: the same address on
// two servers has two records.
class ScopedKeyTest {
    @Test fun theSameRecordOnTwoServersHasTwoKeys() {
        val a = scopedKey("posted", "https://mac.ts.net:7400")
        val b = scopedKey("posted", "http://localhost:7410")
        assertNotEquals(a, b)
        assertEquals("posted@https://mac.ts.net:7400", a)
        assertNotEquals(scopedKey("seen", "x"), scopedKey("posted", "x"))
    }
}
