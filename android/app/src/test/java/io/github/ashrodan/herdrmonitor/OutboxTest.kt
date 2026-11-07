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

    private val S = "http://mac.ts.net:7400"
    private var now = 1_000L
    private fun box(mem: Mem = Mem()) = Outbox(mem) { now++ }

    @Test fun appendKeepsOrder() {
        val b = box()
        b.add(S, "t1", "one", false)
        b.add(S, "t2", "other", false)
        b.add(S, "t1", "two", false)
        assertEquals(listOf("one", "two"), b.forAddress(S, "t1").map { it.text })
        assertEquals(listOf("one", "other", "two"), b.items.value.map { it.text })
    }

    @Test fun aSuccessfulFlushRemovesOnlyTheSentItems() = runBlocking {
        val b = box()
        b.add(S, "t1", "one", false)
        b.add(S, "t1", "two", false)
        val sent = mutableListOf<String>()
        // The second send fails: only "one" goes.
        val n = b.flush(S, { false }) { item, _ -> if (item.text == "two") error("down"); sent += item.text }
        assertEquals(1, n)
        assertEquals(listOf("one"), sent)
        assertEquals(listOf("two"), b.items.value.map { it.text })
    }

    @Test fun aFailedFlushKeepsTheHeadAndDoesNotSendTheNext() = runBlocking {
        val b = box()
        b.add(S, "t1", "one", false)
        b.add(S, "t1", "two", false)
        val tried = mutableListOf<String>()
        val n = b.flush(S, { false }) { item, _ -> tried += item.text; error("HTTP 502") }
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
        b.add(S, "t1", "stuck", false)
        b.add(S, "t2", "fine", false)
        val sent = mutableListOf<String>()
        b.flush(S, { false }) { item, _ -> if (item.address == "t1") error("gone"); sent += item.text }
        assertEquals(listOf("fine"), sent)
        assertEquals(listOf("stuck"), b.items.value.map { it.text })
    }

    @Test fun twoFlushesAtOnceDoNotSendTheHeadTwice() = runBlocking {
        val b = box()
        b.add(S, "t1", "one", false)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val send: suspend (OutItem, Boolean) -> Unit = { _, _ -> calls++; gate.await() }
        val first = async { b.flush(S, { false }, send) }
        yield() // the first flush is now inside send, waiting on the gate
        val second = async { b.flush(S, { false }, send) }
        assertEquals(0, second.await())
        gate.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(1, calls)
        assertTrue(b.items.value.isEmpty())
    }

    @Test fun saveAndLoadRoundTripIncludingLastError() = runBlocking {
        val mem = Mem()
        val b = box(mem)
        b.add(S, "dai/t1", "quote \" back\\slash\nnew line\ttab é 🚀", true)
        b.add(S, "t2", "plain", false)
        b.flush(S, { true }) { item, _ -> if (item.address == "dai/t1") error("HTTP 409: \"busy\"") }
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
        b.add(S, "t1", "one", false)
        val two = b.add(S, "t1", "two", false)
        b.add(S, "t1", "three", false)
        b.remove(two.id)
        assertEquals(listOf("one", "three"), b.items.value.map { it.text })
    }

    @Test fun forceIsClearedWhenTheAgentIsNoLongerBlocked() = runBlocking {
        val b = box()
        b.add(S, "t1", "answer", true)
        b.add(S, "t2", "answer", true)
        b.add(S, "t3", "note", false)
        val forced = mutableMapOf<String, Boolean>()
        b.flush(S, { it == "t2" || it == "t3" }) { item, force -> forced[item.address] = force }
        assertFalse(forced["t1"]!!) // queued at a dialog, no longer blocked
        assertTrue(forced["t2"]!!)  // still blocked
        assertFalse(forced["t3"]!!) // not queued at a dialog
    }

    @Test fun anItemTheTranscriptShowsIsDropped() {
        val b = box()
        val item = b.add(S, "t1", "hello there", false) // createdMs = 1000
        b.add(S, "t2", "hello there", false)
        // An older message with the same text does not count.
        b.dropDelivered(S, "t1", listOf("hello there" to 500L))
        assertEquals(2, b.items.value.size)
        b.dropDelivered(S, "t1", listOf("  hello there \n" to item.createdMs + 10))
        assertEquals(listOf("t2"), b.items.value.map { it.address })
    }

    @Test fun aFlushSendsOnlyThisServersItems() = runBlocking {
        val b = box()
        b.add(S, "t1", "for this server", false)
        b.add("http://other:7400", "t1", "for the other server", false)
        val sent = mutableListOf<String>()
        assertEquals(1, b.flush(S, { false }) { item, _ -> sent += item.text })
        assertEquals(listOf("for this server"), sent)
        assertEquals(listOf("for the other server"), b.items.value.map { it.text })
        assertTrue(b.forAddress(S, "t1").isEmpty())
        // This server's transcript of t1 does not drop the other server's item.
        b.dropDelivered(S, "t1", listOf("for the other server" to 99_999L))
        assertEquals(1, b.items.value.size)
    }

    @Test fun anItemSavedWithoutAServerBelongsToTheLegacyServer() {
        val old = "[{\"id\":\"a\",\"address\":\"t1\",\"text\":\"hi\",\"force\":false,\"createdMs\":5,\"attempts\":0,\"lastError\":null}]"
        val b = Outbox(Mem(old), legacyServer = S)
        assertEquals(S, b.items.value.single().server)
    }

    @Test fun failuresAreSortedByWhatTheyMean() {
        assertEquals(Failure.Refused, failureOf(HttpError(404, "no such terminal")))
        assertEquals(Failure.Refused, failureOf(HttpError(409, "busy")))
        assertEquals(Failure.Refused, failureOf(HttpError(400, "text required")))
        assertEquals(Failure.Retry, failureOf(HttpError(429, "slow down")))
        assertEquals(Failure.Retry, failureOf(HttpError(502, "hc down")))
        assertEquals(Failure.Retry, failureOf(java.net.ConnectException("refused")))
        assertEquals(Failure.MaybeSent, failureOf(MaybeSentException(java.net.SocketTimeoutException("read timed out"))))
    }

    @Test fun aRefusedItemIsMarkedFailedAndDoesNotHoldBackTheNext() = runBlocking {
        val b = box()
        b.add(S, "t1", "refused", false)
        b.add(S, "t1", "next", false)
        val tried = mutableListOf<String>()
        val n = b.flush(S, { false }) { item, _ -> tried += item.text; if (item.text == "refused") throw HttpError(404, "no such terminal") }
        assertEquals(1, n)
        assertEquals(listOf("refused", "next"), tried)
        val left = b.items.value.single()
        assertEquals(OutState.FAILED, left.state)
        assertEquals("no such terminal", left.lastError)
        // A later flush does not try it again.
        tried.clear()
        assertEquals(0, b.flush(S, { false }) { item, _ -> tried += item.text })
        assertTrue(tried.isEmpty())
    }

    @Test fun anUnconfirmedSendIsNeverSentAgainByItself() = runBlocking {
        val b = box()
        val item = b.add(S, "t1", "maybe", false)
        var calls = 0
        b.flush(S, { false }) { _, _ -> calls++; throw MaybeSentException(java.net.SocketTimeoutException("read timed out")) }
        b.flush(S, { false }) { _, _ -> calls++ }
        assertEquals(1, calls)
        assertEquals(OutState.UNCONFIRMED, b.items.value.single().state)
        // The transcript shows it: it goes away.
        b.dropDelivered(S, "t1", listOf("maybe" to item.createdMs + 1))
        assertTrue(b.items.value.isEmpty())
    }

    @Test fun afterMaxAttemptsAnItemFailsAndTheNextGoes() = runBlocking {
        val b = box()
        b.add(S, "t1", "stuck", false)
        b.add(S, "t1", "next", false)
        val sent = mutableListOf<String>()
        repeat(MAX_ATTEMPTS - 1) {
            b.flush(S, { false }) { item, _ -> if (item.text == "stuck") error("HTTP 502"); sent += item.text }
        }
        assertTrue(sent.isEmpty())
        assertEquals(OutState.WAITING, b.items.value.first().state)
        b.flush(S, { false }) { item, _ -> if (item.text == "stuck") error("HTTP 502"); sent += item.text }
        assertEquals(listOf("next"), sent)
        val stuck = b.items.value.single()
        assertEquals(OutState.FAILED, stuck.state)
        assertEquals(MAX_ATTEMPTS, stuck.attempts)
        // Send again: it waits once more, and goes.
        b.retry(stuck.id)
        assertEquals(1, b.flush(S, { false }) { item, _ -> sent += item.text })
        assertEquals(listOf("next", "stuck"), sent)
    }

    @Test fun theStateSurvivesASaveAndALoad() = runBlocking {
        val mem = Mem()
        val b = box(mem)
        b.add(S, "t1", "x", false)
        b.flush(S, { false }) { _, _ -> throw HttpError(409, "busy") }
        val again = Outbox(Mem(mem.json))
        assertEquals(b.items.value, again.items.value)
        assertEquals(OutState.FAILED, again.items.value.single().state)
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
