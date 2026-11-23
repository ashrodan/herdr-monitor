import Foundation
import Observation

// A reply typed while the link was down, or whose POST /send failed. It is
// sent later, in order. Interrupts, key presses and image bytes never
// queue: they are live.
public struct OutItem: Equatable, Identifiable, Codable, Sendable {
    public var id: String
    // The server the reply was typed for. A flush sends it only there: the
    // same address on another server is another agent.
    public var server: String
    public var address: String
    public var text: String
    // True when the agent was blocked at a dialog when the reply was typed.
    // The send uses force only if the agent is still blocked then.
    public var force: Bool
    public var createdMs: Int64
    public var attempts = 0
    public var lastError: String?
    public var state = OutState.waiting
}

// waiting goes on the next flush. failed (the server refused it, or
// MAX_ATTEMPTS failed) and unconfirmed (no answer after the send, so it may
// have arrived) never go again by themselves: the user sends them again or
// deletes them. Neither holds back the items after it.
public enum OutState: String, Codable, Sendable { case waiting, failed, unconfirmed }

public let MAX_ATTEMPTS = 5

public enum Failure: Equatable, Sendable {
    // The server answered 4xx: the same send gives the same answer.
    case refused
    // The body went out and no answer came: the text may be in the chat.
    case maybeSent
    // Not sent (no link, a 5xx): try again later.
    case retry

    public var outState: OutState {
        switch self { case .refused: return .failed; case .maybeSent: return .unconfirmed; case .retry: return .waiting }
    }
}

public func failureOf(_ e: Error) -> Failure {
    guard let a = e as? ApiError else { return .retry }
    switch a {
    case .maybeSent: return .maybeSent
    case .http(let code, _) where (400...499).contains(code) && code != 408 && code != 429: return .refused
    default: return .retry
    }
}

public func describe(_ e: Error) -> String { (e as? LocalizedError)?.errorDescription ?? String(describing: e) }

// Where the queue is kept between runs.
public protocol OutboxStorage {
    func load() -> Data?
    func save(_ data: Data)
}

// Each server and address has its own list, oldest first. A flush sends one
// waiting item at a time. A failure that can pass keeps that item, records
// the error, and stops that address's list (the next item waits), up to
// MAX_ATTEMPTS. A refusal or an unconfirmed send marks the item and goes on
// to the next. Other addresses still flush. A second flush while one runs
// does nothing, so no item is sent twice.
@MainActor @Observable
public final class Outbox {
    public private(set) var items: [OutItem]
    @ObservationIgnored private let storage: OutboxStorage
    @ObservationIgnored private let clock: () -> Int64
    @ObservationIgnored private var flushing = false

    public init(storage: OutboxStorage, clock: @escaping () -> Int64 = nowMs) {
        self.storage = storage
        self.clock = clock
        items = storage.load().flatMap { try? JSONDecoder().decode([OutItem].self, from: $0) } ?? []
    }

    public func forAddress(_ server: String, _ address: String) -> [OutItem] { items.filter { $0.server == server && $0.address == address } }

    @discardableResult
    public func add(server: String, address: String, text: String, force: Bool, error: String? = nil, state: OutState = .waiting) -> OutItem {
        let item = OutItem(id: UUID().uuidString, server: server, address: address, text: text, force: force, createdMs: clock(), lastError: error, state: state)
        update { $0 + [item] }
        return item
    }

    public func remove(_ id: String) { update { $0.filter { $0.id != id } } }

    // The user sends a failed or unconfirmed item again.
    public func retry(_ id: String) {
        update { $0.map { i in var i = i; if i.id == id { i.state = .waiting; i.attempts = 0 }; return i } }
    }

    // Drop items the transcript already shows: a user message with the same
    // text, written after the item was queued.
    public func dropDelivered(server: String, address: String, userMessages: [(text: String, at: Int64?)]) {
        func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }
        update { list in
            list.filter { item in
                !(item.server == server && item.address == address && userMessages.contains { m in
                    (m.at ?? 0) > item.createdMs && trim(m.text) == trim(item.text)
                })
            }
        }
    }

    // Send what waits for this server. send(item, force) throws on failure.
    // isBlocked tells whether an agent is at a dialog now. Returns the
    // number of items sent.
    @discardableResult
    public func flush(server: String, isBlocked: (String) -> Bool, send: (OutItem, Bool) async throws -> Void) async -> Int {
        if flushing { return 0 }
        flushing = true
        defer { flushing = false }
        var sent = 0
        func waiting(_ i: OutItem) -> Bool { i.server == server && i.state == .waiting }
        var addresses: [String] = []
        for i in items where waiting(i) && !addresses.contains(i.address) { addresses.append(i.address) }
        for address in addresses {
            while let head = items.first(where: { waiting($0) && $0.address == address }) {
                do {
                    try await send(head, head.force && isBlocked(address))
                } catch is CancellationError {
                    return sent
                } catch {
                    let attempts = head.attempts + 1
                    var state = failureOf(error).outState
                    if state == .waiting, attempts >= MAX_ATTEMPTS { state = .failed }
                    let why = describe(error)
                    update { $0.map { i in var i = i; if i.id == head.id { i.attempts = attempts; i.lastError = why; i.state = state }; return i } }
                    if state == .waiting { break }
                    continue
                }
                remove(head.id)
                sent += 1
            }
        }
        return sent
    }

    private func update(_ f: ([OutItem]) -> [OutItem]) {
        let next = f(items)
        if next == items { return }
        items = next
        if let data = try? JSONEncoder().encode(next) { storage.save(data) }
    }
}
