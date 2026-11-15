import Foundation
import Observation

// The app's state above one server link: the store, the outbox, what Home
// read from the transcripts, and the per-device records (pins, seen).
@MainActor @Observable
public final class AppModel {
    public private(set) var store: Store
    public let outbox: Outbox
    public let chats = ChatCache(8)
    public var digests: [String: Digest] = [:]
    public var sent: [String: SentMark] = [:]
    public private(set) var pins: Set<String>
    public private(set) var localSeen: [String: Int64]
    public private(set) var opened: [String: Int64]
    public private(set) var collapsed: Set<String>
    public private(set) var server: String
    public var theme: String { didSet { prefs.theme = theme } }
    public var alertsOn: Bool { didSet { prefs.alerts = alertsOn } }

    // The session the detail column shows, and whether it opens on the raw terminal.
    public var selected: String?
    public var filter: Bucket?

    @ObservationIgnored public let prefs: Prefs
    @ObservationIgnored public let transport: Transport
    @ObservationIgnored private let cache: Cache?
    // The alert state posted for each agent (address -> state).
    @ObservationIgnored private var posted: [String: String] = [:]
    @ObservationIgnored private var listeningSince: Int64 = nowMs()
    // Post or remove a local notification (HerdrUI Notifier).
    @ObservationIgnored public var alertSink: ((AlertAction) -> Void)?

    public init(prefs: Prefs = Prefs(), transport: Transport = URLSessionTransport(), cache: Cache? = Cache.standard()) {
        self.prefs = prefs
        self.transport = transport
        self.cache = cache
        server = prefs.server
        theme = prefs.theme
        alertsOn = prefs.alerts
        pins = prefs.pinned
        localSeen = prefs.localSeen
        opened = prefs.opened
        collapsed = prefs.collapsed
        outbox = Outbox(storage: prefs)
        store = Store(api: Api(prefs.server, transport: transport), cache: cache)
        loadDigests()
        wire()
    }

    public var api: Api { store.api }
    public var live: Live { store.live }
    public var online: Bool { store.live.link == .online }

    private func wire() {
        store.onChange = { [weak self] in self?.changed() }
    }

    private func loadDigests() {
        digests = cache?.read(server, Cache.digests).map { digestsFromJson($0.text) } ?? [:]
    }

    public func start() { store.start() }

    // A new server: its own store, records and transcripts.
    public func setServer(_ url: String) {
        guard url != server else { store.reconnect(); return }
        store.stop()
        prefs.server = url
        if !url.isEmpty { prefs.recentServers = [url] + prefs.recentServers.filter { $0 != url } }
        server = url
        pins = prefs.pinned; localSeen = prefs.localSeen; opened = prefs.opened; collapsed = prefs.collapsed
        sent = [:]; posted = [:]; selected = nil; filter = nil
        listeningSince = nowMs()
        store = Store(api: Api(url, transport: transport), cache: cache)
        loadDigests()
        wire()
        store.start()
    }

    public func sessions(now: Int64 = nowMs()) -> [Session] {
        buildSessions(terminals: Array(live.terminals.values), spaces: live.spaces, prs: live.prs, localSeen: localSeen, opened: opened,
                      unreadSince: prefs.unreadSince, digests: digests, pinned: pins, sent: sent, now: now)
    }

    public func togglePin(_ address: String) {
        if pins.contains(address) { pins.remove(address) } else { pins.insert(address) }
        prefs.pinned = pins
    }

    public func toggleCollapsed(_ space: String) {
        if collapsed.contains(space) { collapsed.remove(space) } else { collapsed.insert(space) }
        prefs.collapsed = collapsed
    }

    // The session is on screen: a Done one becomes Idle, its output is read.
    public func markOpened(_ address: String) {
        let now = nowMs()
        if live.terminals[address]?.status == "done" { localSeen[address] = now; prefs.localSeen = localSeen }
        opened[address] = now
        prefs.opened = opened
        if posted[address] != nil { posted[address] = nil; alertSink?(.cancel(address: address)) }
    }

    // Each change of the live list: plan the alerts, and send the outbox
    // when the link is up.
    private func changed() {
        planAlerts()
        if online, outbox.items.contains(where: { $0.server == server && $0.state == .waiting }) { Task { await flushOutbox() } }
    }

    private func planAlerts() {
        guard alertsOn else { return }
        let terminals = live.terminals
        for address in Set(terminals.keys).union(posted.keys) {
            let t = terminals[address]
            // The session on screen does not alert.
            if address == selected, t != nil { continue }
            let name = t.map { dedupName(displayName($0, live.spaces)) } ?? address
            guard let action = planAlert(address: address, prev: posted[address], terminal: t, name: name, localSeen: localSeen, since: listeningSince) else { continue }
            switch action {
            case .post(_, _, let state, _): posted[address] = state
            case .cancel: posted[address] = nil
            }
            alertSink?(action)
        }
    }

    public func flushOutbox() async {
        let api = self.api
        let terminals = live.terminals
        await outbox.flush(server: server, isBlocked: { terminals[$0]?.status == "blocked" }) { item, force in
            try await api.send(item.address, text: item.text, force: force)
        }
    }

    // Reads the transcript of each session that is not idle (up to 12),
    // every 8 s while Home shows, for its last words, its question and its
    // last output time. Needs you first (its answer buttons wait on this),
    // four at a time, a 10 s timeout each. An unchanged transcript costs
    // one small request.
    public func runDigestLoop() async {
        while !Task.isCancelled {
            // Not online yet: look again soon, so the answer buttons do not
            // wait a whole round after the link comes up.
            guard online else { try? await Task.sleep(nanoseconds: 300_000_000); continue }
            do {
                let targets = sessions().filter { $0.bucket != .idle }.prefix(12).map(\.address)
                let api = self.api, known = digests
                let fresh: [(String, Digest)] = await withTaskGroup(of: (String, Digest)?.self) { group in
                    var out: [(String, Digest)] = [], it = targets.makeIterator(), running = 0
                    func add(_ a: String) {
                        group.addTask {
                            guard let chat = try? await api.chat(a, version: known[a]?.version, timeout: 10), chat.available else { return nil }
                            return (a, digest(chat))
                        }
                    }
                    while running < 4, let a = it.next() { add(a); running += 1 }
                    while let r = await group.next() {
                        if let r { out.append(r) }
                        if let a = it.next() { add(a) }
                    }
                    return out
                }
                if !fresh.isEmpty, api.base == server {
                    for (a, d) in fresh { digests[a] = d }
                    cache?.write(server, Cache.digests, digestsToJson(digests))
                }
            }
            try? await Task.sleep(nanoseconds: 8 * 1_000_000_000)
        }
    }
}
