package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTest {
    private val now = 10_000_000L
    private val space = Space("s1", "herdr-monitor", "#ff8800", branch = "feat/android-app",
        tabs = listOf(SpaceTab("tab1", "worker", true, listOf("t1", "t2", "t3", "t4", "t5", "t6"))))
    private fun t(addr: String, status: String?, changed: Long = now - 1000, name: String? = null) =
        Terminal(addr, name = name, agent = "claude-code", status = status, changedMs = changed, space = "s1", tab = "tab1")
    private fun ask(header: String = "Colours") = Part.Ask("q1", listOf(Question(header, "Which accent?", false,
        listOf(Option("Teal", ""), Option("Amber", ""), Option("Red", ""), Option("Blue", "")))), false, null)

    private fun build(terms: List<Terminal>, digests: Map<String, Digest> = emptyMap(), prs: List<OverviewPr> = emptyList(),
                      opened: Map<String, Long> = emptyMap(), sent: Map<String, SentMark> = emptyMap(), pinned: Set<String> = emptySet()) =
        buildSessions(terms, listOf(space), prs, emptyMap(), opened, 0L, digests, pinned, sent, now)

    @Test fun bucketsAndOrder() {
        val s = build(listOf(t("t1", "working"), t("t2", "blocked"), t("t3", "error"), t("t4", "done"), t("t5", "idle"), t("t6", null)))
        assertEquals(listOf(Bucket.NeedsYou, Bucket.NeedsYou, Bucket.Working, Bucket.Ready, Bucket.Idle), s.map { it.bucket })
        assertEquals(Status.Waiting, s.first { it.address == "t2" }.status)
        assertEquals(Status.Failed, s.first { it.address == "t3" }.status)
        // A terminal with no status is a shell: not a session.
        assertTrue(s.none { it.address == "t6" })
        val c = counts(s)
        assertEquals(Counts(5, 2, 1, 1, 1), c)
    }

    @Test fun idleWithAnOpenPrIsReadyToReview() {
        val pr = OverviewPr("s1", "herdr-monitor", null, "feat/android-app", Pr(7, "x", "https://x/7", false, "feat/android-app", null, null), "ready", 0, 0)
        assertEquals(Bucket.Ready, build(listOf(t("t5", "idle")), prs = listOf(pr)).single().bucket)
        val draft = pr.copy(pr = pr.pr.copy(isDraft = true))
        assertEquals(Bucket.Idle, build(listOf(t("t5", "idle")), prs = listOf(draft)).single().bucket)
    }

    @Test fun nameDropsARepeatedSuffix() {
        assertEquals("MNT-1 Android app", dedupName("MNT-1 Android app · android app"))
        assertEquals("herdr-monitor · worker", dedupName("herdr-monitor · worker"))
        assertEquals("plain", dedupName("plain"))
        assertEquals("MNT-1 Android app", build(listOf(t("t1", "working", name = "MNT-1 Android app · android app"))).single().name)
    }

    @Test fun taskIsTheQuestionHeaderWhenWaitingElseTheBranch() {
        val d = Digest("v1", "Pick one.", now - 500, ask("Status colours"))
        val s = build(listOf(t("t2", "blocked"), t("t1", "working")), digests = mapOf("t2" to d, "t1" to d.copy(ask = null)))
        assertEquals("Status colours", s.first { it.address == "t2" }.task)
        assertEquals(ask("Status colours"), s.first { it.address == "t2" }.ask)
        assertEquals("feat/android-app", s.first { it.address == "t1" }.task)
        // A working agent never carries a question.
        assertNull(s.first { it.address == "t1" }.ask)
    }

    @Test fun unreadIsOutputAfterTheLastOpen() {
        val s = build(listOf(t("t1", "working", changed = now - 1000), t("t2", "working", changed = now - 1000)),
            opened = mapOf("t1" to now - 500, "t2" to now - 5000))
        assertFalse(s.first { it.address == "t1" }.unread)
        assertTrue(s.first { it.address == "t2" }.unread)
    }

    @Test fun staleAfterThirtyMinutesWithoutOutput() {
        val old = Digest("v", "x", now - STALE_MS - 60_000, null)
        val fresh = Digest("v", "x", now - 60_000, null)
        val s = build(listOf(t("t1", "working", changed = now - STALE_MS * 3), t("t2", "working")), digests = mapOf("t1" to old, "t2" to fresh))
        assertEquals(STALE_MS + 60_000, s.first { it.address == "t1" }.staleForMs)
        assertNull(s.first { it.address == "t2" }.staleForMs)
        assertEquals("31m", duration(STALE_MS + 60_000))
        assertEquals("3h", duration(3 * 3_600_000L))
    }

    @Test fun anAnswerFromTheCardShowsSentUntilTheNextOutput() {
        val d = Digest("v", null, now - 5000, ask())
        val sent = mapOf("t2" to SentMark("Teal", now - 1000))
        val s = build(listOf(t("t2", "blocked", changed = now - 5000)), digests = mapOf("t2" to d), sent = sent).single()
        assertEquals(Bucket.Working, s.bucket)
        assertEquals(Status.Working, s.status)
        assertEquals("Teal", s.sent)
        assertNull(s.ask)
        // New output after the answer: the mark goes.
        val later = build(listOf(t("t2", "working", changed = now - 10)), digests = mapOf("t2" to d.copy(lastAtMs = now - 10)), sent = sent).single()
        assertNull(later.sent)
    }

    @Test fun pinnedSitsFirstInItsSection() {
        val s = build(listOf(t("t1", "working", changed = now - 10), t("t2", "working", changed = now - 9000)), pinned = setOf("t2"))
        assertEquals(listOf("t2", "t1"), s.map { it.address })
        assertTrue(s.first().pinned)
    }

    @Test fun lastProseIsTheLastTwoSentencesWithoutMarkdown() {
        val m = listOf(
            Message("1", "assistant", null, listOf(Part.Text("First."), Part.Tool("bash", "ls", null),
                Part.Text("## Done\nI **fixed** the `build`. Tests pass now! See [the PR](https://x/1).\n```\ncode\n```"))),
            Message("2", "user", null, listOf(Part.Text("thanks"))),
        )
        assertEquals("Tests pass now! See the PR.", lastProse(m))
        assertNull(lastProse(listOf(Message("1", "assistant", null, listOf(Part.Tool("bash", "ls", null))))))
    }

    @Test fun theFilterIsLocalAndMatchesSeveralFields() {
        val s = build(listOf(t("t1", "working", name = "MNT-1 Android app"))).single()
        assertTrue(matches(s, "android"))
        assertTrue(matches(s, "feat/android"))
        assertTrue(matches(s, "  "))
        assertFalse(matches(s, "nothing-like-this"))
    }
}
