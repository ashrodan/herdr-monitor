import Foundation
import Testing
@testable import HerdrKit

// Port of android SseParserTest.kt. The Swift parser returns the event a
// line completes; it has no comment callback.
@Suite struct SseParserTests {
    private func parse(_ lines: String...) -> [SseEvent] {
        var p = SseParser()
        return lines.compactMap { p.line($0) }
    }

    @Test func eventAndData() {
        #expect(parse("event: snapshot", "data: {\"connected\":true}", "") == [SseEvent(event: "snapshot", data: "{\"connected\":true}")])
    }

    @Test func multiLineDataJoinsWithNewline() {
        let e = parse("event: x", "data: a", "data: b", "")
        #expect(e.count == 1)
        #expect(e.first?.data == "a\nb")
    }

    @Test func defaultEventIsMessageAndNoSpaceAfterColon() {
        #expect(parse("data:abc", "") == [SseEvent(event: "message", data: "abc")])
    }

    @Test func commentsAndCrlf() {
        // A comment is not an event and does not disturb the next one.
        #expect(parse(": ping\r", "event: removed\r", "data: {\"address\":\"t1\"}\r", "\r") == [SseEvent(event: "removed", data: "{\"address\":\"t1\"}")])
    }

    @Test func blankLineWithoutDataSendsNothingAndResetsEvent() {
        #expect(parse("event: lost", "", "data: 1", "") == [SseEvent(event: "message", data: "1")])
    }

    @Test func incompleteEventIsHeld() {
        #expect(parse("event: agent", "data: {}").isEmpty)
    }

    @Test func backoffDoublesToThirtySecondsAndResets() {
        var b = Backoff()
        #expect((1...7).map { _ in b.next() } == [1000, 2000, 4000, 8000, 16000, 30000, 30000])
        b.reset()
        #expect(b.next() == 1000)
    }
}
