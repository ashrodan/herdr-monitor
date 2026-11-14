import Foundation
import Testing
@testable import HerdrKit

// Port of android OutboxTest.kt. Kotlin's error("...") (a failure that can
// pass) is ApiError.network here; HttpError is ApiError.http and
// MaybeSentException is ApiError.maybeSent.
@MainActor @Suite struct OutboxTests {
    private final class Mem: OutboxStorage {
        var data: Data?
        init(_ text: String? = nil) { data = text.map { Data($0.utf8) } }
        init(data: Data?) { self.data = data }
        func load() -> Data? { data }
        func save(_ data: Data) { self.data = data }
    }

    private final class Counter { var now: Int64 = 1_000 }

    private let S = "http://mac.ts.net:7400"
    private let counter = Counter()

    private func box(_ mem: Mem = Mem()) -> Outbox {
        let c = counter
        return Outbox(storage: mem) { defer { c.now += 1 }; return c.now }
    }

    private func down(_ m: String) -> ApiError { .network(m) }
    private let notBlocked: (String) -> Bool = { _ in false }

    @Test func appendKeepsOrder() {
        let b = box()
        b.add(server: S, address: "t1", text: "one", force: false)
        b.add(server: S, address: "t2", text: "other", force: false)
        b.add(server: S, address: "t1", text: "two", force: false)
        #expect(b.forAddress(S, "t1").map(\.text) == ["one", "two"])
        #expect(b.items.map(\.text) == ["one", "other", "two"])
    }

    @Test func aSuccessfulFlushRemovesOnlyTheSentItems() async {
        let b = box()
        b.add(server: S, address: "t1", text: "one", force: false)
        b.add(server: S, address: "t1", text: "two", force: false)
        var sent: [String] = []
        // The second send fails: only "one" goes.
        let n = await b.flush(server: S, isBlocked: notBlocked) { item, _ in
            if item.text == "two" { throw self.down("down") }
            sent.append(item.text)
        }
        #expect(n == 1)
        #expect(sent == ["one"])
        #expect(b.items.map(\.text) == ["two"])
    }

    @Test func aFailedFlushKeepsTheHeadAndDoesNotSendTheNext() async throws {
        let b = box()
        b.add(server: S, address: "t1", text: "one", force: false)
        b.add(server: S, address: "t1", text: "two", force: false)
        var tried: [String] = []
        let n = await b.flush(server: S, isBlocked: notBlocked) { item, _ in tried.append(item.text); throw self.down("HTTP 502") }
        #expect(n == 0)
        #expect(tried == ["one"])
        let head = try #require(b.items.first)
        #expect(head.text == "one")
        #expect(head.attempts == 1)
        #expect(head.lastError == "HTTP 502")
        #expect(b.items.count == 2)
    }

    @Test func aFailureForOneAgentDoesNotHoldBackAnother() async {
        let b = box()
        b.add(server: S, address: "t1", text: "stuck", force: false)
        b.add(server: S, address: "t2", text: "fine", force: false)
        var sent: [String] = []
        await b.flush(server: S, isBlocked: notBlocked) { item, _ in
            if item.address == "t1" { throw self.down("gone") }
            sent.append(item.text)
        }
        #expect(sent == ["fine"])
        #expect(b.items.map(\.text) == ["stuck"])
    }

    @Test func twoFlushesAtOnceDoNotSendTheHeadTwice() async {
        let b = box()
        let server = S
        b.add(server: server, address: "t1", text: "one", force: false)
        final class Gate { var calls = 0; var waiting: CheckedContinuation<Void, Never>? }
        let gate = Gate()
        let first = Task { @MainActor in
            await b.flush(server: server, isBlocked: { _ in false }) { _, _ in
                gate.calls += 1
                await withCheckedContinuation { gate.waiting = $0 }
            }
        }
        // Wait until the first flush is inside send, at the gate.
        while gate.waiting == nil { await Task.yield() }
        let second = await b.flush(server: server, isBlocked: { _ in false }) { _, _ in gate.calls += 1 }
        #expect(second == 0)
        gate.waiting?.resume()
        #expect(await first.value == 1)
        #expect(gate.calls == 1)
        #expect(b.items.isEmpty)
    }

    @Test func saveAndLoadRoundTripIncludingLastError() async throws {
        let mem = Mem()
        let b = box(mem)
        b.add(server: S, address: "dai/t1", text: "quote \" back\\slash\nnew line\ttab é 🚀", force: true)
        b.add(server: S, address: "t2", text: "plain", force: false)
        await b.flush(server: S, isBlocked: { _ in true }) { item, _ in
            if item.address == "dai/t1" { throw self.down("HTTP 409: \"busy\"") }
        }
        let again = Outbox(storage: Mem(data: mem.data))
        #expect(again.items == b.items)
        #expect(again.items.count == 1)
        let item = try #require(again.items.first)
        #expect(item.lastError == "HTTP 409: \"busy\"")
        #expect(item.attempts == 1)
        #expect(item.force)
        #expect(item.text == "quote \" back\\slash\nnew line\ttab é 🚀")
    }

    @Test func aBadStoredValueLoadsAsEmpty() {
        #expect(Outbox(storage: Mem("not json")).items.isEmpty)
        #expect(Outbox(storage: Mem(nil)).items.isEmpty)
        #expect(Outbox(storage: Mem("[]")).items.isEmpty)
    }

    @Test func deleteRemovesOneItemAndLeavesTheRest() {
        let b = box()
        b.add(server: S, address: "t1", text: "one", force: false)
        let two = b.add(server: S, address: "t1", text: "two", force: false)
        b.add(server: S, address: "t1", text: "three", force: false)
        b.remove(two.id)
        #expect(b.items.map(\.text) == ["one", "three"])
    }

    @Test func forceIsClearedWhenTheAgentIsNoLongerBlocked() async {
        let b = box()
        b.add(server: S, address: "t1", text: "answer", force: true)
        b.add(server: S, address: "t2", text: "answer", force: true)
        b.add(server: S, address: "t3", text: "note", force: false)
        var forced: [String: Bool] = [:]
        await b.flush(server: S, isBlocked: { $0 == "t2" || $0 == "t3" }) { item, force in forced[item.address] = force }
        #expect(forced["t1"] == false) // queued at a dialog, no longer blocked
        #expect(forced["t2"] == true)  // still blocked
        #expect(forced["t3"] == false) // not queued at a dialog
    }

    @Test func anItemTheTranscriptShowsIsDropped() {
        let b = box()
        let item = b.add(server: S, address: "t1", text: "hello there", force: false) // createdMs = 1000
        #expect(item.createdMs == 1000)
        b.add(server: S, address: "t2", text: "hello there", force: false)
        // An older message with the same text does not count.
        b.dropDelivered(server: S, address: "t1", userMessages: [("hello there", 500)])
        #expect(b.items.count == 2)
        b.dropDelivered(server: S, address: "t1", userMessages: [("  hello there \n", item.createdMs + 10)])
        #expect(b.items.map(\.address) == ["t2"])
    }

    @Test func aFlushSendsOnlyThisServersItems() async {
        let b = box()
        b.add(server: S, address: "t1", text: "for this server", force: false)
        b.add(server: "http://other:7400", address: "t1", text: "for the other server", force: false)
        var sent: [String] = []
        let n = await b.flush(server: S, isBlocked: notBlocked) { item, _ in sent.append(item.text) }
        #expect(n == 1)
        #expect(sent == ["for this server"])
        #expect(b.items.map(\.text) == ["for the other server"])
        #expect(b.forAddress(S, "t1").isEmpty)
        // This server's transcript of t1 does not drop the other server's item.
        b.dropDelivered(server: S, address: "t1", userMessages: [("for the other server", 99_999)])
        #expect(b.items.count == 1)
    }

    @Test func failuresAreSortedByWhatTheyMean() {
        #expect(failureOf(ApiError.http(404, "no such terminal")) == .refused)
        #expect(failureOf(ApiError.http(409, "busy")) == .refused)
        #expect(failureOf(ApiError.http(400, "text required")) == .refused)
        #expect(failureOf(ApiError.http(429, "slow down")) == .retry)
        #expect(failureOf(ApiError.http(502, "hc down")) == .retry)
        #expect(failureOf(ApiError.network("refused")) == .retry)
        #expect(failureOf(URLError(.cannotConnectToHost)) == .retry)
        #expect(failureOf(ApiError.maybeSent("read timed out")) == .maybeSent)
    }

    @Test func aFailedFirstSendGetsTheMatchingState() {
        // A notification Reply that fails keeps its text in this state.
        #expect(Failure.refused.outState == .failed)
        #expect(Failure.maybeSent.outState == .unconfirmed)
        #expect(Failure.retry.outState == .waiting)
        let b = box()
        let i = b.add(server: S, address: "t1", text: "from a notification", force: true, error: "no such terminal", state: .failed)
        #expect(i.state == .failed)
        #expect(i.server == S)
    }

    @Test func aRefusedItemIsMarkedFailedAndDoesNotHoldBackTheNext() async throws {
        let b = box()
        b.add(server: S, address: "t1", text: "refused", force: false)
        b.add(server: S, address: "t1", text: "next", force: false)
        var tried: [String] = []
        let n = await b.flush(server: S, isBlocked: notBlocked) { item, _ in
            tried.append(item.text)
            if item.text == "refused" { throw ApiError.http(404, "no such terminal") }
        }
        #expect(n == 1)
        #expect(tried == ["refused", "next"])
        #expect(b.items.count == 1)
        let left = try #require(b.items.first)
        #expect(left.state == .failed)
        #expect(left.lastError == "no such terminal")
        // A later flush does not try it again.
        tried.removeAll()
        let again = await b.flush(server: S, isBlocked: notBlocked) { item, _ in tried.append(item.text) }
        #expect(again == 0)
        #expect(tried.isEmpty)
    }

    @Test func anUnconfirmedSendIsNeverSentAgainByItself() async {
        let b = box()
        let item = b.add(server: S, address: "t1", text: "maybe", force: false)
        var calls = 0
        await b.flush(server: S, isBlocked: notBlocked) { _, _ in calls += 1; throw ApiError.maybeSent("read timed out") }
        await b.flush(server: S, isBlocked: notBlocked) { _, _ in calls += 1 }
        #expect(calls == 1)
        #expect(b.items.map(\.state) == [.unconfirmed])
        // The transcript shows it: it goes away.
        b.dropDelivered(server: S, address: "t1", userMessages: [("maybe", item.createdMs + 1)])
        #expect(b.items.isEmpty)
    }

    @Test func afterMaxAttemptsAnItemFailsAndTheNextGoes() async throws {
        let b = box()
        b.add(server: S, address: "t1", text: "stuck", force: false)
        b.add(server: S, address: "t1", text: "next", force: false)
        var sent: [String] = []
        for _ in 0..<(MAX_ATTEMPTS - 1) {
            await b.flush(server: S, isBlocked: notBlocked) { item, _ in
                if item.text == "stuck" { throw self.down("HTTP 502") }
                sent.append(item.text)
            }
        }
        #expect(sent.isEmpty)
        #expect(b.items.first?.state == .waiting)
        await b.flush(server: S, isBlocked: notBlocked) { item, _ in
            if item.text == "stuck" { throw self.down("HTTP 502") }
            sent.append(item.text)
        }
        #expect(sent == ["next"])
        #expect(b.items.count == 1)
        let stuck = try #require(b.items.first)
        #expect(stuck.state == .failed)
        #expect(stuck.attempts == MAX_ATTEMPTS)
        // Send again: it waits once more, and goes.
        b.retry(stuck.id)
        let n = await b.flush(server: S, isBlocked: notBlocked) { item, _ in sent.append(item.text) }
        #expect(n == 1)
        #expect(sent == ["next", "stuck"])
    }

    @Test func theStateSurvivesASaveAndALoad() async {
        let mem = Mem()
        let b = box(mem)
        b.add(server: S, address: "t1", text: "x", force: false)
        await b.flush(server: S, isBlocked: notBlocked) { _, _ in throw ApiError.http(409, "busy") }
        let again = Outbox(storage: Mem(data: mem.data))
        #expect(again.items == b.items)
        #expect(again.items.map(\.state) == [.failed])
    }

    @Test func userTextsReadsTimes() {
        let m = [
            Message(id: "1", role: "user", at: "2026-11-07T08:00:00Z", parts: [.text("hi")]),
            Message(id: "2", role: "assistant", at: "2026-11-07T08:00:01Z", parts: [.text("yo")]),
            Message(id: "3", role: "user", at: nil, parts: [.text("no time")]),
        ]
        let u = userTexts(m)
        #expect(u.count == 2)
        #expect(u.first?.text == "hi")
        #expect(u.first?.at == 1_794_038_400_000)
        #expect(u.last?.text == "no time")
        #expect(u.last?.at == nil)
    }
}
