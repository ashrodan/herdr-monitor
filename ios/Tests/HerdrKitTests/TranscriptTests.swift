import Foundation
import Testing
@testable import HerdrKit

// Port of android TranscriptTest.kt.
@Suite struct TranscriptTests {
    private func tool(_ name: String, ok: Bool? = true) -> Part {
        .tool(Tool(name: name, summary: "\(name) x", result: ok.map { ToolResult(text: "out", error: !$0, truncated: false) }))
    }

    private func kind(_ i: Item) -> String {
        switch i {
        case .prose: return "prose"
        case .user: return "user"
        case .system: return "system"
        case .activity: return "activity"
        case .question: return "question"
        }
    }

    private func steps(_ i: Item) -> [Part]? { if case .activity(_, let s) = i { return s } else { return nil } }

    @Test func toolsAndThinkingFoldBetweenProse() throws {
        let items = transcriptItems([
            Message(id: "1", role: "assistant", at: nil, parts: [.text("I'll capture it."), .thinking("need id"), tool("bash"), tool("bash")]),
            // The run goes on in the next assistant message.
            Message(id: "2", role: "assistant", at: nil, parts: [tool("read"), .text("Done."), .text("Next.")]),
        ])
        #expect(items.map(kind) == ["prose", "activity", "prose", "prose"])
        let act = try #require(steps(items[1]))
        #expect(act.count == 4)
        #expect(toolCounts(toolsOf(act)) == "2 bash · 1 read")
        // The harness mark shows once per run of prose.
        let who: [Bool] = items.compactMap { if case .prose(_, _, let w) = $0 { return w } else { return nil } }
        #expect(who == [true, true, false])
    }

    @Test func userAndSystemBreakTheFold() throws {
        let items = transcriptItems([
            Message(id: "1", role: "assistant", at: nil, parts: [tool("bash")]),
            Message(id: "2", role: "system", at: nil, parts: [.text("Background job bg_9 completed")]),
            Message(id: "3", role: "user", at: nil, parts: [.text("also do dark mode")]),
            Message(id: "4", role: "assistant", at: nil, parts: [tool("grep"), tool("write", ok: false)]),
        ])
        #expect(items.map(kind) == ["activity", "system", "user", "activity"])
        // Kotlin's Item.Activity.failed: the tools whose result is an error.
        let last = try #require(steps(items[3]))
        #expect(toolsOf(last).filter { $0.result?.error == true }.count == 1)
        // Keys are unique, so the list can use them.
        #expect(Set(items.map(\.id)).count == items.count)
    }

    @Test func anInterruptNoteIsASystemLine() {
        let items = transcriptItems([Message(id: "1", role: "user", at: nil, parts: [.text("[Request interrupted by user]")])])
        #expect(items == [.system(key: "1", text: "Request interrupted by user")])
    }

    @Test func theRunningToolIsTheLastStepWithNoResult() {
        let running = transcriptItems([Message(id: "1", role: "assistant", at: nil, parts: [tool("bash"), tool("bash", ok: nil)])])
        #expect(runningTool(running)?.name == "bash")
        let after = transcriptItems([Message(id: "1", role: "assistant", at: nil, parts: [tool("bash", ok: nil), .text("ok")])])
        #expect(runningTool(after) == nil)
    }

    @Test func headerHelpers() {
        #expect(harnessMark("omp") == "π")
        #expect(harnessName("claude-code") == "Claude")
        #expect(harnessMark("claude-code") == "C")
        #expect(ticketOf("MNT-1 Android app") == "MNT-1")
        #expect(ticketOf("herdr-monitor · worker") == nil)
        #expect(clock(486_000) == "8:06")
        #expect(clock(3_900_000) == "1h 05m")
        #expect(transcriptItems([]).isEmpty)
    }
}
