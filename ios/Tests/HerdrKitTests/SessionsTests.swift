import Foundation
import Testing
@testable import HerdrKit

// Port of android SessionsTest.kt.
@Suite struct SessionsTests {
    private let now: Int64 = 10_000_000
    private let space = Space(id: "s1", name: "herdr-monitor", color: "#ff8800", branch: "feat/android-app",
                              tabs: [SpaceTab(id: "tab1", name: "worker", named: true, terminals: ["t1", "t2", "t3", "t4", "t5", "t6"])])

    private func t(_ addr: String, _ status: String?, changed: Int64? = nil, name: String? = nil) -> Terminal {
        Terminal(address: addr, name: name, agent: "claude-code", status: status, space: "s1", tab: "tab1", changedMs: changed ?? now - 1000)
    }

    private func ask(_ header: String = "Colours") -> Ask {
        Ask(id: "q1", questions: [Question(header: header, question: "Which accent?", multi: false,
                                           options: [AskOption(label: "Teal"), AskOption(label: "Amber"), AskOption(label: "Red"), AskOption(label: "Blue")])],
            answered: false, answer: nil)
    }

    private func build(_ terms: [Terminal], digests: [String: Digest] = [:], prs: [OverviewPr] = [], opened: [String: Int64] = [:],
                       sent: [String: SentMark] = [:], pinned: Set<String> = []) -> [Session] {
        buildSessions(terminals: terms, spaces: [space], prs: prs, localSeen: [:], opened: opened, unreadSince: 0, digests: digests,
                      pinned: pinned, sent: sent, now: now)
    }

    private func at(_ s: [Session], _ addr: String) throws -> Session { try #require(s.first { $0.address == addr }) }

    @Test func bucketsAndOrder() throws {
        let s = build([t("t1", "working"), t("t2", "blocked"), t("t3", "error"), t("t4", "done"), t("t5", "idle"), t("t6", nil)])
        #expect(s.map(\.bucket) == [.needsYou, .needsYou, .working, .done, .idle])
        #expect(try at(s, "t2").status == .waiting)
        #expect(try at(s, "t3").status == .failed)
        #expect(try at(s, "t4").status == .done)
        // A terminal with no status is a shell: not a session.
        #expect(!s.contains { $0.address == "t6" })
        #expect(counts(s) == Counts(all: 5, needsYou: 2, working: 1, done: 1, ready: 0, idle: 1))
    }

    @Test func idleWithAnOpenPrIsReadyToReview() {
        let pr = OverviewPr(space: "s1", spaceName: "herdr-monitor", color: nil, branch: "feat/android-app",
                            pr: Pr(number: 7, title: "x", url: "https://x/7", isDraft: false, headRefName: "feat/android-app", reviewDecision: nil, updatedAt: nil),
                            readiness: "ready", fail: 0, pending: 0)
        let ready = build([t("t5", "idle")], prs: [pr])
        #expect(ready.count == 1)
        #expect(ready.first?.bucket == .ready)
        var draft = pr
        draft.pr.isDraft = true
        let idle = build([t("t5", "idle")], prs: [draft])
        #expect(idle.count == 1)
        #expect(idle.first?.bucket == .idle)
    }

    @Test func nameDropsARepeatedSuffix() {
        #expect(dedupName("MNT-1 Android app · android app") == "MNT-1 Android app")
        #expect(dedupName("herdr-monitor · worker") == "herdr-monitor · worker")
        #expect(dedupName("plain") == "plain")
        let s = build([t("t1", "working", name: "MNT-1 Android app · android app")])
        #expect(s.count == 1)
        #expect(s.first?.name == "MNT-1 Android app")
    }

    @Test func taskIsTheQuestionHeaderWhenWaitingElseTheBranch() throws {
        let d = Digest(version: "v1", snippet: "Pick one.", lastAtMs: now - 500, ask: ask("Status colours"))
        var noAsk = d
        noAsk.ask = nil
        let s = build([t("t2", "blocked"), t("t1", "working")], digests: ["t2": d, "t1": noAsk])
        #expect(try at(s, "t2").task == "Status colours")
        #expect(try at(s, "t2").ask == ask("Status colours"))
        #expect(try at(s, "t1").task == "feat/android-app")
        // A working agent never carries a question.
        #expect(try at(s, "t1").ask == nil)
    }

    @Test func unreadIsOutputAfterTheLastOpen() throws {
        let s = build([t("t1", "working", changed: now - 1000), t("t2", "working", changed: now - 1000)],
                      opened: ["t1": now - 500, "t2": now - 5000])
        #expect(try !at(s, "t1").unread)
        #expect(try at(s, "t2").unread)
    }

    @Test func staleAfterThirtyMinutesWithoutOutput() throws {
        let old = Digest(version: "v", snippet: "x", lastAtMs: now - STALE_MS - 60_000, ask: nil)
        let fresh = Digest(version: "v", snippet: "x", lastAtMs: now - 60_000, ask: nil)
        let s = build([t("t1", "working", changed: now - STALE_MS * 3), t("t2", "working")], digests: ["t1": old, "t2": fresh])
        #expect(try at(s, "t1").staleForMs == STALE_MS + 60_000)
        #expect(try at(s, "t2").staleForMs == nil)
        #expect(duration(STALE_MS + 60_000) == "31m")
        #expect(duration(3 * 3_600_000) == "3h")
    }

    @Test func anAnswerFromTheCardShowsSentUntilTheNextOutput() throws {
        let d = Digest(version: "v", snippet: nil, lastAtMs: now - 5000, ask: ask())
        let sent = ["t2": SentMark(label: "Teal", atMs: now - 1000)]
        let all = build([t("t2", "blocked", changed: now - 5000)], digests: ["t2": d], sent: sent)
        #expect(all.count == 1)
        let s = try #require(all.first)
        #expect(s.bucket == .working)
        #expect(s.status == .working)
        #expect(s.sent == "Teal")
        #expect(s.ask == nil)
        // New output after the answer: the mark goes.
        var newer = d
        newer.lastAtMs = now - 10
        let later = build([t("t2", "working", changed: now - 10)], digests: ["t2": newer], sent: sent)
        #expect(later.count == 1)
        #expect(try #require(later.first).sent == nil)
    }

    @Test func pinnedSitsFirstInItsSection() {
        let s = build([t("t1", "working", changed: now - 10), t("t2", "working", changed: now - 9000)], pinned: ["t2"])
        #expect(s.map(\.address) == ["t2", "t1"])
        #expect(s.first?.pinned == true)
    }

    @Test func lastProseIsTheLastTwoSentencesWithoutMarkdown() {
        let m = [
            Message(id: "1", role: "assistant", at: nil, parts: [
                .text("First."), .tool(Tool(name: "bash", summary: "ls", result: nil)),
                .text("## Done\nI **fixed** the `build`. Tests pass now! See [the PR](https://x/1).\n```\ncode\n```"),
            ]),
            Message(id: "2", role: "user", at: nil, parts: [.text("thanks")]),
        ]
        #expect(lastProse(m) == "Tests pass now! See the PR.")
        #expect(lastProse([Message(id: "1", role: "assistant", at: nil, parts: [.tool(Tool(name: "bash", summary: "ls", result: nil))])]) == nil)
    }

    @Test func theFilterIsLocalAndMatchesSeveralFields() throws {
        let all = build([t("t1", "working", name: "MNT-1 Android app")])
        #expect(all.count == 1)
        let s = try #require(all.first)
        #expect(matches(s, query: "android"))
        #expect(matches(s, query: "feat/android"))
        #expect(matches(s, query: "  "))
        #expect(!matches(s, query: "nothing-like-this"))
    }
}
