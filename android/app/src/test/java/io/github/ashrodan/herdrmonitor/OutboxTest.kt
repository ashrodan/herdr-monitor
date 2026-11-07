package io.github.ashrodan.herdrmonitor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    private class Mem(var json: String? = null) : OutboxStorage {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private var now = 1_000L
    private fun box(mem: Mem = Mem()) = Outbox(mem) { now++ }

    @Test fun appendKeepsOrder() {
        val b = box()
        b.add("t1", "one", false)
        b.add("t2", "other", false)
        b.add("t1", "two", false)
        assertEquals(listOf("one", "two"), b.forAddress("t1").map { it.text })
        assertEquals(listOf("one", "other", "two"), b.items.value.map { it.text })
    }

    @Test fun aSuccessfulFlushRemovesOnlyTheSentItems() = runBlocking {
        val b = box()
        b.add("t1", "one", false)
        b.add("t1", "two", false)
        val sent = mutableListOf<String>()
        // The second send fails: only "one" goes.
        val n = b.flush({ false }) { item, _ -> if (item.text == "two") error("down"); sent += item.text }
        assertEquals(1, n)
        assertEquals(listOf("one"), sent)
        assertEquals(listOf("two"), b.items.value.map { it.text })
    }

    @Test fun aFailedFlushKeepsTheHeadAndDoesNotSendTheNext() = runBlocking {
        val b = box()
        b.add("t1", "one", false)
        b.add("t1", "two", false)
        val tried = mutableListOf<String>()
        val n = b.flush({ false }) { item, _ -> tried += item.text; error("HTTP 502") }
        assertEquals(0, n)
        assertEquals(listOf("one"), tried)
        val head = b.items.value.first()
        assertEquals("one", head.text)
        assertEquals(1, head.attempts)
        assertEquals("HTTP 502", head.lastError)
        assertEquals(2, b.items.value.size)
    }

    @Test fun aFailureForOneAgentDoesNotHoldBackAnother() = runBlocking {
        val b = box()
        b.add("t1", "stuck", false)
        b.add("t2", "fine", false)
        val sent = mutableListOf<String>()
        b.flush({ false }) { item, _ -> if (item.address == "t1") error("gone"); sent += item.text }
        assertEquals(listOf("fine"), sent)
        assertEquals(listOf("stuck"), b.items.value.map { it.text })
    }

    @Test fun twoFlushesAtOnceDoNotSendTheHeadTwice() = runBlocking {
        val b = box()
        b.add("t1", "one", false)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val send: suspend (OutItem, Boolean) -> Unit = { _, _ -> calls++; gate.await() }
        val first = async { b.flush({ false }, send) }
        yield() // the first flush is now inside send, waiting on the gate
        val second = async { b.flush({ false }, send) }
        assertEquals(0, second.await())
        gate.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(1, calls)
        assertTrue(b.items.value.isEmpty())
    }

    @Test fun saveAndLoadRoundTripIncludingLastError() = runBlocking {
        val mem = Mem()
        val b = box(mem)
        b.add("dai/t1", "quote \" back\\slash\nnew line\ttab é 🚀", true)
        b.add("t2", "plain", false)
        b.flush({ true }) { item, _ -> if (item.address == "dai/t1") error("HTTP 409: \"busy\"") }
        val again = Outbox(Mem(mem.json))
        assertEquals(b.items.value, again.items.value)
        val item = again.items.value.single()
        assertEquals("HTTP 409: \"busy\"", item.lastError)
        assertEquals(1, item.attempts)
        assertTrue(item.force)
        assertEquals("quote \" back\\slash\nnew line\ttab é 🚀", item.text)
    }

    @Test fun aBadStoredValueLoadsAsEmpty() {
        assertTrue(Outbox(Mem("not json")).items.value.isEmpty())
        assertTrue(Outbox(Mem(null)).items.value.isEmpty())
        assertTrue(Outbox(Mem("[]")).items.value.isEmpty())
    }

    @Test fun deleteRemovesOneItemAndLeavesTheRest() {
        val b = box()
        b.add("t1", "one", false)
        val two = b.add("t1", "two", false)
        b.add("t1", "three", false)
        b.remove(two.id)
        assertEquals(listOf("one", "three"), b.items.value.map { it.text })
    }

    @Test fun forceIsClearedWhenTheAgentIsNoLongerBlocked() = runBlocking {
        val b = box()
        b.add("t1", "answer", true)
        b.add("t2", "answer", true)
        b.add("t3", "note", false)
        val forced = mutableMapOf<String, Boolean>()
        b.flush({ it == "t2" || it == "t3" }) { item, force -> forced[item.address] = force }
        assertFalse(forced["t1"]!!) // queued at a dialog, no longer blocked
        assertTrue(forced["t2"]!!)  // still blocked
        assertFalse(forced["t3"]!!) // not queued at a dialog
    }

    @Test fun anItemTheTranscriptShowsIsDropped() {
        val b = box()
        val item = b.add("t1", "hello there", false) // createdMs = 1000
        b.add("t2", "hello there", false)
        // An older message with the same text does not count.
        b.dropDelivered("t1", listOf("hello there" to 500L))
        assertEquals(2, b.items.value.size)
        b.dropDelivered("t1", listOf("  hello there \n" to item.createdMs + 10))
        assertEquals(listOf("t2"), b.items.value.map { it.address })
    }

    @Test fun userTextsReadsTimes() {
        val m = listOf(
            Message("1", "user", "2026-11-07T08:00:00Z", listOf(Part.Text("hi"))),
            Message("2", "assistant", "2026-11-07T08:00:01Z", listOf(Part.Text("yo"))),
            Message("3", "user", null, listOf(Part.Text("no time"))),
        )
        val u = userTexts(m)
        assertEquals(2, u.size)
        assertEquals("hi" to 1_794_038_400_000L, u[0])
        assertNull(u[1].second)
    }
}
