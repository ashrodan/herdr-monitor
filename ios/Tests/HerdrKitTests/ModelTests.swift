import Foundation
import Testing
@testable import HerdrKit

// Port of android ModelTest.kt. Alerts are planned from a Terminal here.
@Suite struct ModelTests {
    private func agent(_ state: String, changed: Int64? = 100, seen: Int64? = nil, addr: String = "t1", msg: String? = nil) -> Terminal {
        Terminal(address: addr, name: "app", status: state, kind: "permission", msg: msg, changedMs: changed, seenMs: seen)
    }

    private func plan(_ prev: String?, _ t: Terminal?, since: Int64? = nil) -> AlertAction? {
        planAlert(address: "t1", prev: prev, terminal: t, name: "app", since: since)
    }

    private func isPost(_ a: AlertAction?) -> Bool { if case .post = a { return true } else { return false } }

    @Test func doneTurnsIdleOnceSeenOnTheMacOrHere() {
        #expect(effectiveState("done", 100, nil) == "done")
        #expect(effectiveState("done", 100, 100) == "idle")
        #expect(effectiveState("done", 100, nil, 150) == "idle")
        #expect(effectiveState("done", 100, 50, 90) == "done")
        #expect(effectiveState("blocked", 100, 200) == "blocked")
        #expect(effectiveState(nil, nil, nil) == "idle")
    }

    @Test func planAlertPostsOnNewAlertStatesOnly() {
        guard case .post(let address, let name, let state, _)? = plan(nil, agent("blocked")) else { Issue.record("no post"); return }
        #expect(state == "blocked")
        #expect(address == "t1")
        #expect(name == "app")
        #expect(plan("blocked", agent("blocked", msg: "new msg")) == nil)
        // Done is in the summary only: a blocked alert goes, no Done alert comes.
        #expect(plan("blocked", agent("done")) == .cancel(address: "t1"))
        #expect(plan("error", agent("done")) == .cancel(address: "t1"))
        #expect(plan(nil, agent("done")) == nil)
        #expect(plan(nil, agent("done", changed: 600), since: 500) == nil)
        #expect(isPost(plan(nil, agent("error"))))
        #expect(plan(nil, agent("working")) == nil)
    }

    @Test func planAlertCancelsWhenTheAgentMovesOnOrGoes() {
        #expect(plan("blocked", agent("working")) == .cancel(address: "t1"))
        // A Done alert left from an older build goes.
        #expect(plan("done", agent("done", seen: 100)) == .cancel(address: "t1"))
        #expect(plan("done", nil) == .cancel(address: "t1"))
        #expect(plan(nil, nil) == nil)
    }

    @Test func planAlertOnASnapshotSkipsChangesFromBeforeWeListened() {
        #expect(plan(nil, agent("blocked", changed: 100), since: 500) == nil)
        #expect(isPost(plan(nil, agent("blocked", changed: 600), since: 500)))
        // Something already on screen still goes when it is over.
        #expect(plan("blocked", agent("idle", changed: 100), since: 500) == .cancel(address: "t1"))
    }

    @Test func alertText() {
        #expect(alertTitle("app", "blocked") == "app · needs you")
        #expect(alertBody(agent("blocked"), "blocked") == "Waiting at a permission")
        #expect(alertBody(agent("blocked", msg: "Run ls?"), "blocked") == "Run ls?")
    }

    @Test func displayNameUsesNameThenSpaceAndNamedTab() {
        let spaces = [Space(id: "s1", name: "app", tabs: [
            SpaceTab(id: "tb1", name: "tests", named: true, terminals: ["t1"]), SpaceTab(id: "tb2", name: "1", named: false, terminals: ["t2"]),
        ])]
        #expect(displayName(Terminal(address: "t9", name: "mine"), spaces) == "mine")
        #expect(displayName(Terminal(address: "t1", space: "s1", tab: "tb1"), spaces) == "app · tests")
        #expect(displayName(Terminal(address: "t2", space: "s1", tab: "tb2"), spaces) == "app")
        #expect(displayName(Terminal(address: "t3", agent: "claude", where: "proj / 1"), spaces) == "proj")
        #expect(displayName(Terminal(address: "t4", agent: "claude"), spaces) == "claude")
    }

    @Test func spaceSwipePagesKeepAgentOrderAndHideAppsAndShells() {
        let space = Space(id: "s", name: "app", tabs: [
            SpaceTab(id: "one", name: "one", named: true, terminals: ["agent-1", "server", "shell"]),
            SpaceTab(id: "two", name: "two", named: true, terminals: ["agent-2", "agent-1"]),
        ])
        let list = [
            Terminal(address: "agent-1", agent: "claude", status: "working"),
            Terminal(address: "server", agent: "node", listen: Listen(scheme: "http", port: 3000, host: nil)),
            Terminal(address: "shell", agent: "zsh"),
            Terminal(address: "agent-2", agent: "omp", status: "done"),
        ]
        let terminals = Dictionary(uniqueKeysWithValues: list.map { ($0.address, $0) })
        #expect(space.agentAddresses(terminals) == ["agent-1", "agent-2"])
    }

    @Test func ageWords() {
        #expect(age(1000, now: 2000) == "<1m")
        #expect(age(0 + 1, now: 300_001) == "5m")
        #expect(age(1, now: 7_200_001) == "2h")
        #expect(age(nil) == "")
    }
}
