package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTest {
    @Test fun channels() {
        assertEquals(Channel.NeedsYou, channelFor("blocked"))
        assertEquals(Channel.NeedsYou, channelFor("error"))
        assertEquals(Channel.Summary, channelFor("done"))
        assertEquals(Channel.Summary, channelFor("working"))
        assertNull(channelFor("idle"))
    }

    // Only Needs you posts its own notification, with Quiet on or off.
    @Test fun onlyNeedsYouAlerts() {
        for (quiet in listOf(true, false)) {
            assertTrue(mayAlert("blocked", quiet))
            assertTrue(mayAlert("error", quiet))
            assertFalse(mayAlert("done", quiet))
            assertFalse(mayAlert("working", quiet))
            assertFalse(mayAlert("idle", quiet))
        }
    }

    // The bell: a Done alerts for a watched agent, but not with Quiet agents on.
    @Test fun aDoneAlertsOnlyWithTheBellOnAndQuietOff() {
        assertTrue(mayAlert("done", quiet = false, watched = true))
        assertFalse(mayAlert("done", quiet = true, watched = true))
        assertFalse(mayAlert("done", quiet = false, watched = false))
        assertFalse(mayAlert("working", quiet = false, watched = true))
        assertTrue(mayAlert("blocked", quiet = true, watched = true))
    }

    @Test fun tileSubtitle() {
        val d = WidgetData(true, "mac", 2, 4, 1, null, 0)
        assertEquals("2 need you", tileSubtitle(d))
        assertEquals("1 needs you", tileSubtitle(d.copy(needsYou = 1)))
        assertEquals("4 working", tileSubtitle(d.copy(needsYou = 0)))
        assertEquals("No link", tileSubtitle(d.copy(connected = false)))
        assertEquals("Open the app", tileSubtitle(null))
    }
}
