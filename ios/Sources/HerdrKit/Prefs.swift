import Foundation

// The per-agent records (seen, opened, pinned) are kept per server: t1 on
// one server is not t1 on another.
public func scopedKey(_ key: String, _ server: String) -> String { "\(key)@\(server)" }

// What the app keeps between runs, in UserDefaults.
public final class Prefs: OutboxStorage, @unchecked Sendable {
    private let d: UserDefaults
    public init(_ defaults: UserDefaults = .standard) { d = defaults }

    // Empty until the user sets one: there is no default server.
    public var server: String {
        get { d.string(forKey: "server") ?? "" }
        set { d.set(newValue, forKey: "server") }
    }

    // Servers used before, newest first, for the picker.
    public var recentServers: [String] {
        get { d.stringArray(forKey: "recent_servers") ?? [] }
        set { d.set(Array(newValue.prefix(6)), forKey: "recent_servers") }
    }

    // "System" (default), "Dark" or "Light".
    public var theme: String {
        get { d.string(forKey: "theme") ?? "System" }
        set { d.set(newValue, forKey: "theme") }
    }

    public var alerts: Bool {
        get { d.object(forKey: "alerts") as? Bool ?? true }
        set { d.set(newValue, forKey: "alerts") }
    }

    private func map(_ key: String) -> [String: Int64] {
        (d.dictionary(forKey: scopedKey(key, server)) ?? [:]).compactMapValues { ($0 as? NSNumber)?.int64Value }
    }
    private func setMap(_ key: String, _ m: [String: Int64]) { d.set(m.mapValues { NSNumber(value: $0) }, forKey: scopedKey(key, server)) }

    // "Done" agents opened on this device (address -> ms).
    public var localSeen: [String: Int64] {
        get { map("seen") }
        set { setMap("seen", newValue) }
    }

    // When each agent was last opened here: newer output is unread.
    public var opened: [String: Int64] {
        get { map("opened") }
        set { setMap("opened", newValue) }
    }

    // Output before the app first ran does not count as unread.
    public var unreadSince: Int64 {
        let v = (d.object(forKey: "unread_since") as? NSNumber)?.int64Value ?? 0
        if v > 0 { return v }
        let now = nowMs()
        d.set(NSNumber(value: now), forKey: "unread_since")
        return now
    }

    // Pinned sessions sit at the top of their section. Local to the device.
    public var pinned: Set<String> {
        get { Set(d.stringArray(forKey: scopedKey("pinned", server)) ?? []) }
        set { d.set(newValue.sorted(), forKey: scopedKey("pinned", server)) }
    }

    // Spaces whose worktrees are folded under them in the Spaces list.
    public var collapsed: Set<String> {
        get { Set(d.stringArray(forKey: scopedKey("collapsed", server)) ?? []) }
        set { d.set(newValue.sorted(), forKey: scopedKey("collapsed", server)) }
    }

    // The outbox (OutboxStorage).
    public func load() -> Data? { d.data(forKey: "outbox") }
    public func save(_ data: Data) { d.set(data, forKey: "outbox") }
}

// The last copy of what the server sent, per server, so a cold start shows
// the last-known sessions and PRs at once (marked as stale). Files in the
// caches folder: iOS may delete them, and the app works without them.
public struct Cache: Sendable {
    public static let maxAgeMs: Int64 = 24 * 60 * 60 * 1000
    public static let snapshot = "snapshot", overview = "overview", digests = "digests"

    let dir: URL
    let now: @Sendable () -> Int64

    public init(dir: URL, now: @escaping @Sendable () -> Int64 = { nowMs() }) { self.dir = dir; self.now = now }

    public static func standard() -> Cache? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first.map { Cache(dir: $0.appendingPathComponent("herdr", isDirectory: true)) }
    }

    // One folder name per server: the URL with every other character as "_".
    public func slot(_ server: String) -> String {
        let s = Rx("[^a-z0-9]+").replace(server.lowercased(), "_").trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        return s.isEmpty ? "default" : s
    }

    private func file(_ server: String, _ name: String) -> URL { dir.appendingPathComponent(slot(server), isDirectory: true).appendingPathComponent("\(name).txt") }

    // The text and when it was written, or nil (none, unreadable, too old).
    public func read(_ server: String, _ name: String, maxAgeMs: Int64 = Cache.maxAgeMs) -> (at: Int64, text: String)? {
        guard let text = try? String(contentsOf: file(server, name), encoding: .utf8), let nl = text.firstIndex(of: "\n"),
              let at = Int64(text[..<nl]), now() - at <= maxAgeMs else { return nil }
        return (at, String(text[text.index(after: nl)...]))
    }

    // An atomic write: a reader never sees half a file.
    public func write(_ server: String, _ name: String, _ text: String) {
        let f = file(server, name)
        try? FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? Data("\(now())\n\(text)".utf8).write(to: f, options: .atomic)
    }
}

// The last `size` transcripts read (server + address -> Chat), least
// recently used out first. In memory only: a transcript can be large.
@MainActor
public final class ChatCache {
    private let size: Int
    private var order: [String] = []
    private var map: [String: Chat] = [:]
    public init(_ size: Int) { self.size = size }

    public func get(_ server: String, _ address: String) -> Chat? {
        let k = "\(server)\u{0}\(address)"
        guard let c = map[k] else { return nil }
        order.removeAll { $0 == k }; order.append(k)
        return c
    }

    public func put(_ server: String, _ address: String, _ chat: Chat) {
        let k = "\(server)\u{0}\(address)"
        map[k] = chat
        order.removeAll { $0 == k }; order.append(k)
        while order.count > size { map[order.removeFirst()] = nil }
    }
}
