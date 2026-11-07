package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTest {
    private fun bash(cmd: String) = Part.Tool("bash", cmd, ToolResult("", false, false))
    private fun edit(f: String) = Part.Tool("Edit", f, ToolResult("", false, false))
    private fun run(vararg parts: Part) = listOf(Message("u", "user", null, listOf(Part.Text("go"))), Message("a", "assistant", null, parts.toList()))

    @Test fun channels() {
        assertEquals(Channel.NeedsYou, channelFor("blocked"))
        assertEquals(Channel.NeedsYou, channelFor("error"))
        assertEquals(Channel.Finished, channelFor("done"))
        assertEquals(Channel.Running, channelFor("working"))
        assertNull(channelFor("idle"))
    }

    @Test fun quietAgentsLetOnlyNeedsYouAlert() {
        assertTrue(mayAlert("blocked", quiet = true))
        assertTrue(mayAlert("error", quiet = true))
        assertFalse(mayAlert("done", quiet = true))
        assertFalse(mayAlert("working", quiet = true))
        assertTrue(mayAlert("done", quiet = false))
        assertFalse(mayAlert("idle", quiet = false))
    }

    @Test fun stageFollowsTheToolCallsOfThisRun() {
        assertEquals(0, progressStage(run(bash("ls"), bash("grep -rn x src"))))
        assertEquals(1, progressStage(run(bash("ls"), edit("Theme.kt"))))
        assertEquals(2, progressStage(run(edit("Theme.kt"), bash("./gradlew testDebugUnitTest"))))
        // A new edit after the tests: back to Edit.
        assertEquals(1, progressStage(run(edit("a"), bash("npm test"), edit("b"))))
        assertEquals(3, progressStage(run(edit("a"), bash("npm test"), bash("git push -u origin x"))))
        // A test run before any edit is not Verify.
        assertEquals(0, progressStage(run(bash("npm test"))))
        // Only the run since your last message counts.
        val older = run(edit("a"), bash("gh pr create --draft")) + Message("u2", "user", null, listOf(Part.Text("next")))
        assertEquals(0, progressStage(older))
    }

    @Test fun currentStepIsTheLastTool() {
        assertEquals("bash: npm test", currentStep(run(edit("a"), bash("npm test"), Part.Text("ok"))))
        assertNull(currentStep(run(Part.Text("hi"))))
    }

    @Test fun tileSubtitle() {
        val d = WidgetData(true, "mac", 2, 4, 1, null, 0)
        assertEquals("2 need you", tileSubtitle(d))
        assertEquals("1 needs you", tileSubtitle(d.copy(needsYou = 1)))
        assertEquals("4 working", tileSubtitle(d.copy(needsYou = 0)))
        assertEquals("No link", tileSubtitle(d.copy(connected = false)))
        assertEquals("Open the app", tileSubtitle(null))
    }

    @Test fun finishedLineAddsThePr() {
        val a = AgentInfo("t1", "MNT-1", "done", null, null, 1, null)
        assertEquals("Finished its turn · PR #11", finishedLine(a, "done", Pr(11, "x", "u", false, "b", null, null)))
        assertEquals("Stopped with an error", finishedLine(a.copy(state = "error"), "error", null))
        assertEquals("Tests pass", finishedLine(a.copy(msg = "Tests pass"), "done", null))
    }
}

class RunningRuleTest {
    @Test fun onlyPinnedWorkingSessionsGetARunningNotification() {
        assertTrue(wantsRunning("working", quiet = false, pinned = true))
        assertFalse(wantsRunning("working", quiet = false, pinned = false))
        assertFalse(wantsRunning("working", quiet = true, pinned = true))
        assertFalse(wantsRunning("done", quiet = false, pinned = true))
        assertEquals(3, MAX_RUNNING)
    }
}
