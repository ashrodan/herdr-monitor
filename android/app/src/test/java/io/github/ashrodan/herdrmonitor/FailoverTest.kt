package io.github.ashrodan.herdrmonitor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {
    private val mac = "https://mac.ts.net:7400"
    private val dai = "https://herdr-1.ts.net:7400"
    private val box = "https://box.ts.net:7400"
    private val list = listOf(mac, dai, box)

    @Test fun aPickedServerGoesFirstAndTheRestKeepTheirOrder() {
        assertEquals(listOf(dai, mac, box), pickFirst(list, dai))
        assertEquals(list, pickFirst(list, mac))
        assertEquals(listOf("https://new:7400") + list, pickFirst(list, "https://new:7400"))
    }

    @Test fun fallbacksAreAddedAtTheEndAndTheFirstStays() {
        assertEquals(listOf(mac, dai), toggleFallback(listOf(mac), dai))
        assertEquals(listOf(mac, box), toggleFallback(list, dai))
        // The preferred server is not taken out by a toggle.
        assertEquals(list, toggleFallback(list, mac))
    }

    @Test fun failOverOnlyAfterAMinuteAndOnlyWithSomewhereToGo() {
        assertFalse(shouldFailOver(list, mac, 0, 1_000_000))
        assertFalse(shouldFailOver(list, mac, 1_000_000, 1_000_000 + FAILOVER_AFTER_MS - 1))
        assertTrue(shouldFailOver(list, mac, 1_000_000, 1_000_000 + FAILOVER_AFTER_MS))
        // One server: nowhere to go.
        assertFalse(shouldFailOver(listOf(mac), mac, 1, 1 + FAILOVER_AFTER_MS))
    }

    @Test fun theNextServerIsTheFirstHealthyOneThatIsNotInUse() = runBlocking {
        assertEquals(dai, nextServer(list, mac) { true })
        assertEquals(box, nextServer(list, mac) { it == box })
        // From a fallback, the preferred server comes first again when it answers.
        assertEquals(mac, nextServer(list, dai) { true })
        assertNull(nextServer(list, mac) { false })
    }

    @Test fun theServersAboveAreTheOnesToMoveBackTo() {
        assertEquals(emptyList<String>(), above(list, mac))
        assertEquals(listOf(mac), above(list, dai))
        assertEquals(listOf(mac, dai), above(list, box))
        assertFalse(onFallback(list, mac))
        assertTrue(onFallback(list, dai))
    }
}
