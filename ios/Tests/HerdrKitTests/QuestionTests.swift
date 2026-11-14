import Foundation
import Testing
@testable import HerdrKit

// Port of android QuestionTest.kt.
@Suite struct QuestionTests {
    private func q(_ text: String, _ n: Int, multi: Bool = false) -> Question {
        Question(header: "", question: text, multi: multi, options: n > 0 ? (1...n).map { AskOption(label: "opt\($0)") } : [])
    }
    private func ask(_ id: String, _ answered: Bool, _ qs: Question...) -> Ask { Ask(id: id, questions: qs, answered: answered) }
    private func msg(_ id: String, _ parts: Part...) -> Message { Message(id: id, role: "assistant", at: nil, parts: parts) }

    @Test func lastUnansweredAskSkipsAnsweredAndTakesTheLatest() {
        let old = ask("a", false, q("Old?", 2))
        let done = ask("b", true, q("Done?", 2))
        let latest = ask("c", false, q("Now?", 3))
        let messages = [msg("1", .ask(old)), msg("2", .text("hi"), .ask(done)), msg("3", .tool(Tool(name: "Bash", summary: "ls")), .ask(latest)), msg("4", .text("after"))]
        #expect(lastUnansweredAsk(messages) == latest)
        #expect(lastUnansweredAsk([msg("1", .ask(done))]) == nil)
        #expect(lastUnansweredAsk([]) == nil)
    }

    @Test func quickOptionsOnlyForOneSingleChoiceQuestion() {
        #expect(ask("x", false, q("Pick", 5)).quickOptions(max: 3) == ["opt1", "opt2", "opt3"])
        #expect(ask("x", false, q("Pick", 2)).quickOptions(max: 3) == ["opt1", "opt2"])
        #expect(ask("x", false, q("Pick any", 3, multi: true)).quickOptions() == nil)
        #expect(ask("x", false, q("A", 2), q("B", 2)).quickOptions() == nil)
        #expect(ask("x", false, q("None", 0)).quickOptions() == nil)
    }

    @Test func blankLabelsGetAName() {
        let a = Ask(id: "x", questions: [Question(header: "", question: "Q", multi: false, options: [AskOption(label: "")])], answered: false)
        #expect(a.quickOptions() == ["Option"])
    }

    @Test func shapeIsWhatTheAnswerRouteWants() {
        #expect(ask("x", false, q("A", 2), q("B", 4, multi: true)).shape
            == [QuestionShape(question: "A", options: 2, multi: false), QuestionShape(question: "B", options: 4, multi: true)])
    }
}
