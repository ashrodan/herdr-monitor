import Foundation
import Observation

public enum Link: Equatable, Sendable { case connecting, online, offline }

// What the screens show: every terminal and space, live from /api/events
// (the same feed the web UI uses), plus open PRs.
public struct Live: Equatable, Sendable {
    public var link = Link.connecting
    public var hcConnected = false
    public var terminals: [String: Terminal] = [:]
    public var spaces: [Space] = []
    public var prs: [OverviewPr] = []
    public var error: String?
    // Set while terminals and spaces come from the cache (when it was
    // written): the screens show them as stale. The first snapshot clears it.
    public var cachedAt: Int64?
    // false until a PR list (live or cached) is in: "No open pull requests"
    // must not show while /api/overview still runs (it can take a minute).
    public var prsLoaded = false
    public init() {}
}

@MainActor @Observable
public final class Store {
    public private(set) var live = Live()
    public let api: Api
    @ObservationIgnored private let cache: Cache?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var prTask: Task<Void, Never>?
    // Called after each change to the terminals (alerts, the outbox).
    @ObservationIgnored public var onChange: (() -> Void)?

    public var server: String { api.base }

    public init(api: Api, cache: Cache? = nil) {
        self.api = api
        self.cache = cache
        loadCache()
    }

    // Fill an empty Live from the cache. Live data always wins.
    private func loadCache() {
        guard let cache else { return }
        if let snap = cache.read(server, Cache.snapshot), let o = JSON.obj(snap.text), live.terminals.isEmpty {
            live.cachedAt = snap.at
            live.terminals = Decode.terminals(o.objs("terminals"))
            live.spaces = Decode.spaces(o.objs("spaces"))
        }
        if let ov = cache.read(server, Cache.overview), let prs = Decode.overview(Data(ov.text.utf8)) {
            live.prs = prs
            live.prsLoaded = true
        }
    }

    public func start() {
        guard task == nil, !server.isEmpty else { return }
        let api = self.api
        task = Task { [weak self] in
            var backoff = Backoff()
            while !Task.isCancelled {
                var parser = SseParser()
                do {
                    for try await ev in api.stream("/api/events") {
                        guard let self, !Task.isCancelled else { return }
                        switch ev {
                        case .open: backoff.reset()
                        case .line(let l): if let e = parser.line(l) { self.onEvent(e) }
                        }
                    }
                } catch {
                    if Task.isCancelled { return }
                    self?.live.error = describe(error)
                }
                guard let self, !Task.isCancelled else { return }
                if self.live.link != .offline { self.live.link = .offline }
                try? await Task.sleep(nanoseconds: UInt64(backoff.next()) * 1_000_000)
            }
        }
        prTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshPrs()
                try? await Task.sleep(nanoseconds: 120 * 1_000_000_000)
            }
        }
    }

    public func stop() {
        task?.cancel(); task = nil
        prTask?.cancel(); prTask = nil
    }

    // The app came back to the front, or the network changed: connect now.
    public func reconnect() {
        stop()
        if live.link == .offline { live.link = .connecting }
        start()
    }

    public func refreshPrs(fresh: Bool = false) async {
        guard let r = try? await api.overview(fresh: fresh) else { return } // keep the last list
        live.prs = r.prs
        live.prsLoaded = true
        cache?.write(server, Cache.overview, String(decoding: r.raw, as: UTF8.self))
    }

    func onEvent(_ e: SseEvent) {
        guard let o = JSON.obj(e.data) else { return }
        switch e.event {
        case "snapshot":
            live.link = .online
            live.error = nil
            live.cachedAt = nil
            live.hcConnected = (o["connected"] as? Bool) ?? true
            live.terminals = Decode.terminals(o.objs("terminals"))
            live.spaces = Decode.spaces(o.objs("spaces"))
            cache?.write(server, Cache.snapshot, e.data)
        case "terminal":
            // Skip an event that changes nothing the app shows (a spinner
            // in the title): each new Live redraws the screens.
            guard let t = Decode.terminal(o) else { return }
            if live.link == .online, live.terminals[t.address] == t { return }
            live.link = .online
            live.terminals[t.address] = t
        case "removed":
            live.terminals[o.text("address")] = nil
        case "layout":
            let s = Decode.spaces(o.objs("spaces"))
            if s == live.spaces { return }
            live.spaces = s
        case "connection":
            live.hcConnected = o.bool("connected")
        default:
            return
        }
        onChange?()
    }
}

// Every herdr-monitor the device can reach. An app cannot read Tailscale's
// list of machines, so: ask a server it knows for /api/discover, and ask
// that server directly too. No port scan.
public enum Servers {
    // One server, asked directly: /api/info, else /api/health (an older
    // server has no /api/info).
    public static func probe(_ url: String, transport: Transport) async -> ServerInfo? {
        let api = Api(url, transport: transport)
        let urlHost = URL(string: url)?.host
        if let data = try? await api.get("/api/info", timeout: 6), let o = JSON.obj(data) {
            let host = o.str("host")
            return ServerInfo(url: url, name: urlHost ?? url, isSelf: host != nil && host?.lowercased() == urlHost?.lowercased(),
                              herdr: Decode.herdr(o.obj("herdr")), host: host)
        }
        if let data = try? await api.get("/api/health", timeout: 6), Decode.isHealth(data) {
            return ServerInfo(url: url, name: urlHost ?? url, older: true)
        }
        return nil
    }

    public static func find(known: [String], transport: Transport) async -> [ServerInfo] {
        var bases: [String] = []
        for b in known where !b.isEmpty && !bases.contains(b) { bases.append(b) }
        let ports = Array(Set(bases.map(portOf).filter { $0 > 0 })).sorted().map(String.init).joined(separator: ",")
        return await withTaskGroup(of: [ServerInfo].self) { group in
            for b in bases {
                group.addTask { (try? await Api(b, transport: transport).get("/api/discover?ports=\(ports)", timeout: 30)).map(Decode.discover) ?? [] }
                group.addTask { await probe(b, transport: transport).map { [$0] } ?? [] }
            }
            var all: [ServerInfo] = []
            for await part in group { all += part }
            return merge(all)
        }
    }

    // One row per URL. A row with versions wins over one without; the
    // server's own "self" flag wins over a direct probe's guess.
    public static func merge(_ all: [ServerInfo]) -> [ServerInfo] {
        var groups: [String: [ServerInfo]] = [:], order: [String] = []
        for s in all {
            let k = normalizeServer(s.url) ?? s.url
            if groups[k] == nil { order.append(k) }
            groups[k, default: []].append(s)
        }
        let rows: [ServerInfo] = order.map { url in
            let rows = groups[url]!
            func score(_ s: ServerInfo) -> Int { (s.herdr != nil ? 2 : 0) + (s.os != nil ? 1 : 0) }
            var best = rows.max { score($0) < score($1) }!
            best.url = url
            best.isSelf = rows.contains { $0.isSelf }
            best.os = rows.compactMap(\.os).first
            return best
        }
        return rows.sorted { a, b in
            if a.isSelf != b.isSelf { return a.isSelf }
            if a.name != b.name { return a.name < b.name }
            return portOf(a.url) < portOf(b.url)
        }
    }
}
