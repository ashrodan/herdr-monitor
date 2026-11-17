package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class MachinesTest {
    @Test fun line_saysWhatThePhoneCanDo() {
        assertEquals("This server · 2 terminals", machineLine(Machine("mac.t.ts.net", self = true, online = true, terminals = 2), "mac"))
        assertEquals("Through hc on mac · 1 terminal, 1 agent",
            machineLine(Machine("box.t.ts.net", online = true, hcName = "dai", hcState = "connected", hcVersion = "0.1.16", terminals = 1, agents = 1), "mac"))
        assertEquals("Through hc on mac · no terminals", machineLine(Machine("box.t.ts.net", online = true, hcName = "dai", hcState = "connected"), "mac"))
        assertEquals("hc on mac: lost", machineLine(Machine("box.t.ts.net", online = true, hcName = "dai", hcState = "lost"), "mac"))
        assertEquals("Has its own herdr-monitor", machineLine(Machine("box.t.ts.net", online = true, server = "https://box.t.ts.net:7400"), "mac"))
        assertEquals("Online · no Herdr link", machineLine(Machine("box.t.ts.net", online = true), "mac"))
        assertEquals("Offline", machineLine(Machine("ipad.t.ts.net"), "mac"))
    }

    @Test fun terminals_areTheLiveOnesOfThatMachine_agentsFirst() {
        val all = listOf(
            Terminal("t1", agent = "claude-code"),
            Terminal("dai/t2", agent = "bash"),
            Terminal("dai/t1", agent = "claude-code", status = "done"),
            Terminal("dai/t3", agent = "claude-code", state = "finished"),
            Terminal("daily/t1", agent = "claude-code"),
        )
        val m = Machine("box.t.ts.net", online = true, hcName = "dai", hcState = "connected")
        assertEquals(listOf("dai/t1", "dai/t2"), machineTerminals(m, all).map { it.address })
        assertEquals(emptyList<Terminal>(), machineTerminals(Machine("other.t.ts.net", online = true), all))
        assertEquals("box", m.short)
    }
}
