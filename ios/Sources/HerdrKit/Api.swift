import Foundation

// "host:7400" or "https://host:7400/" -> "https://host:7400". nil when it
// is not an http(s) URL with a host.
public func normalizeServer(_ input: String) -> String? {
    var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
    while s.hasSuffix("/") { s.removeLast() }
    if s.isEmpty { return nil }
    if !s.contains("://") { s = "https://" + s }
    guard let u = URLComponents(string: s), let scheme = u.scheme?.lowercased(), ["http", "https"].contains(scheme),
          let host = u.host, !host.isEmpty else { return nil }
    return "\(scheme)://\(host.lowercased())" + (u.port.map { ":\($0)" } ?? "")
}

// The Origin the server's same-origin check wants: scheme://host[:port],
// equal to the URL we call. The default port is left out.
public func originOf(_ url: String) -> String {
    guard let u = URLComponents(string: url), let scheme = u.scheme, let host = u.host else { return url }
    let isDefault = (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)
    return "\(scheme)://\(host)" + (u.port == nil || isDefault ? "" : ":\(u.port!)")
}

public func pathPart(_ s: String) -> String {
    var ok = CharacterSet.alphanumerics
    ok.insert(charactersIn: "-._~")
    return s.addingPercentEncoding(withAllowedCharacters: ok) ?? s
}

public enum ApiError: Error, Equatable, LocalizedError {
    // The server answered with an error status.
    case http(Int, String)
    // The request body went out, but no answer came back. The server may
    // already have acted on it, so a caller must not repeat it blindly.
    case maybeSent(String)
    // Not sent: no link, no route, a timeout before the send.
    case network(String)
    case badAnswer

    public var errorDescription: String? {
        switch self {
        case .http(_, let m): return m
        case .maybeSent(let m): return "No answer after the send (\(m))"
        case .network(let m): return m
        case .badAnswer: return "The server sent an answer the app cannot read"
        }
    }
    public var code: Int? { if case .http(let c, _) = self { return c } else { return nil } }
}

public enum StreamEvent: Sendable { case open; case line(String) }

// The network, as the Api uses it. URLSessionTransport is the real one;
// tests and the demo mode pass their own.
public protocol Transport: Sendable {
    func send(_ method: String, _ url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (Data, Int)
    // An SSE stream: .open after a 200, then every line (empty ones too).
    func stream(_ url: URL, timeout: TimeInterval) -> AsyncThrowingStream<StreamEvent, Error>
}

public struct URLSessionTransport: Transport {
    private let session: URLSession
    public init() {
        let c = URLSessionConfiguration.default
        c.waitsForConnectivity = false
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        session = URLSession(configuration: c)
    }

    public func send(_ method: String, _ url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (Data, Int) {
        var r = URLRequest(url: url, timeoutInterval: timeout)
        r.httpMethod = method
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = body
        do {
            let (data, resp) = try await session.data(for: r)
            return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
        } catch let e as URLError {
            // After these, a POST may have reached the server.
            if body != nil, [.timedOut, .networkConnectionLost].contains(e.code) { throw ApiError.maybeSent(e.localizedDescription) }
            throw ApiError.network(e.localizedDescription)
        }
    }

    public func stream(_ url: URL, timeout: TimeInterval) -> AsyncThrowingStream<StreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var r = URLRequest(url: url, timeoutInterval: timeout)
                    r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    let (bytes, resp) = try await session.bytes(for: r)
                    let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                    guard code == 200 else { throw ApiError.http(code, "HTTP \(code)") }
                    continuation.yield(.open)
                    // bytes.lines drops empty lines, and an empty line ends
                    // an SSE event: split the lines here.
                    var line: [UInt8] = []
                    for try await b in bytes {
                        if b == 0x0A { continuation.yield(.line(String(decoding: line, as: UTF8.self))); line.removeAll(keepingCapacity: true) } else { line.append(b) }
                    }
                    continuation.finish()
                } catch let e as URLError {
                    continuation.finish(throwing: ApiError.network(e.localizedDescription))
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

// Calls against one server.
public struct Api: Sendable {
    public let base: String
    public let transport: Transport

    public init(_ base: String, transport: Transport = URLSessionTransport()) { self.base = base; self.transport = transport }

    private func url(_ path: String) throws -> URL {
        guard let u = URL(string: base + path) else { throw ApiError.network("Bad server URL") }
        return u
    }

    private func checked(_ r: (Data, Int)) throws -> Data {
        guard (200...299).contains(r.1) else { throw ApiError.http(r.1, Decode.errorOf(r.0) ?? "HTTP \(r.1)") }
        return r.0
    }

    public func get(_ path: String, timeout: TimeInterval = 15) async throws -> Data {
        try checked(await transport.send("GET", url(path), headers: ["Accept": "application/json"], body: nil, timeout: timeout))
    }

    @discardableResult
    public func post(_ path: String, json: Any = [String: Any]()) async throws -> Data {
        try await post(path, bytes: JSON.data(json), type: "application/json")
    }

    @discardableResult
    public func post(_ path: String, bytes: Data, type: String) async throws -> Data {
        try checked(await transport.send("POST", url(path), headers: ["Accept": "application/json", "Content-Type": type, "Origin": originOf(base)], body: bytes, timeout: 30))
    }

    // The server pings every 25 s, so 70 s with no byte is a dead link.
    public func stream(_ path: String) -> AsyncThrowingStream<StreamEvent, Error> {
        guard let u = URL(string: base + path) else { return AsyncThrowingStream { $0.finish(throwing: ApiError.network("Bad server URL")) } }
        return transport.stream(u, timeout: 70)
    }

    // ---- The routes ----
    public func terminalPath(_ addr: String, _ rest: String) -> String { "/api/terminals/\(pathPart(addr))/\(rest)" }

    public func send(_ addr: String, text: String, force: Bool) async throws {
        try await post(terminalPath(addr, "send"), json: ["text": text, "force": force])
    }

    public static func answerBody(_ shape: [QuestionShape], q: Int, option: Int, action: String?) -> [String: Any] {
        var o: [String: Any] = ["questions": shape.map { ["question": $0.question, "options": $0.options, "multi": $0.multi] as [String: Any] }]
        if let action { o["action"] = action } else { o["q"] = q; o["option"] = option }
        return o
    }

    public func answer(_ addr: String, shape: [QuestionShape], q: Int, option: Int) async throws {
        try await post(terminalPath(addr, "answer"), json: Api.answerBody(shape, q: q, option: option, action: nil))
    }

    public func submit(_ addr: String, shape: [QuestionShape]) async throws {
        try await post(terminalPath(addr, "answer"), json: Api.answerBody(shape, q: 0, option: 0, action: "submit"))
    }

    public func interrupt(_ addr: String) async throws { try await post(terminalPath(addr, "interrupt")) }

    public func keys(_ addr: String, _ keys: [String]) async throws { try await post(terminalPath(addr, "keys"), json: ["keys": keys]) }

    // nil: the transcript did not change since `version`.
    public func chat(_ addr: String, version: String?, timeout: TimeInterval = 30) async throws -> Chat? {
        let q = version.map { "?v=\(pathPart($0))" } ?? ""
        return Decode.chat(try await get(terminalPath(addr, "chat") + q, timeout: timeout))
    }

    public func read(_ addr: String, lines: Int) async throws -> String {
        Decode.readText(try await get(terminalPath(addr, "read") + "?lines=\(lines)"))
    }

    public func commits(_ addr: String, fresh: Bool = false) async throws -> Commits {
        guard let c = Decode.commits(try await get(terminalPath(addr, "commits") + (fresh ? "?fresh" : ""), timeout: 30)) else { throw ApiError.badAnswer }
        return c
    }

    public func commitDetail(_ addr: String, sha: String) async throws -> CommitDetail {
        guard let c = Decode.commitDetail(try await get(terminalPath(addr, "commits/\(pathPart(sha))"))) else { throw ApiError.badAnswer }
        return c
    }

    public func prStatus(_ addr: String, fresh: Bool = false) async throws -> PrStatus {
        guard let s = Decode.prStatus(try await get(terminalPath(addr, "status") + (fresh ? "?fresh" : ""), timeout: 30)) else { throw ApiError.badAnswer }
        return s
    }

    // Start one agent in a space (POST /api/sessions).
    public func startSession(space: String, harness: String, prompt: String) async throws -> Started {
        guard let s = Decode.started(try await post("/api/sessions", json: ["space": space, "harness": harness, "prompt": prompt])) else { throw ApiError.badAnswer }
        return s
    }

    public func worktrees(space: String) async throws -> [Worktree] {
        Decode.worktrees(try await get("/api/spaces/\(pathPart(space))/worktrees"))
    }

    // An image for the agent. Returns the path the server keeps it at.
    public func upload(_ addr: String, bytes: Data, type: String) async throws -> String {
        Decode.uploadPath(try await post(terminalPath(addr, "upload"), bytes: bytes, type: type))
    }

    public func overview(fresh: Bool = false) async throws -> (prs: [OverviewPr], raw: Data) {
        let data = try await get("/api/overview" + (fresh ? "?fresh" : ""), timeout: 60)
        guard let prs = Decode.overview(data) else { throw ApiError.badAnswer }
        return (prs, data)
    }
}

// ---- Server-Sent Events ----

public struct SseEvent: Equatable, Sendable {
    public var event: String, data: String
    public init(event: String, data: String) { self.event = event; self.data = data }
}

// The parts of SSE the server uses: "event:", "data:" (joined with
// newlines), comments (": ping") and the blank line that ends an event.
public struct SseParser: Sendable {
    private var event: String?
    private var data: [String] = []
    public init() {}

    // Feed one line; returns the event that this line completes, if any.
    public mutating func line(_ raw: String) -> SseEvent? {
        let line = raw.hasSuffix("\r") ? String(raw.dropLast()) : raw
        if line.isEmpty {
            defer { event = nil; data = [] }
            return data.isEmpty ? nil : SseEvent(event: event ?? "message", data: data.joined(separator: "\n"))
        }
        if line.hasPrefix(":") { return nil }
        let field: Substring, value: Substring
        if let colon = line.firstIndex(of: ":") {
            field = line[..<colon]
            let rest = line[line.index(after: colon)...]
            value = rest.hasPrefix(" ") ? rest.dropFirst() : rest
        } else { field = Substring(line); value = "" }
        switch field {
        case "event": event = String(value)
        case "data": data.append(String(value))
        default: break
        }
        return nil
    }
}

// Reconnect delays: 1 s, 2 s, 4 s ... up to 30 s. reset() after a good link.
public struct Backoff: Sendable {
    private let first: Int, max: Int
    private var upcoming: Int
    public init(first: Int = 1000, max: Int = 30000) { self.first = first; self.max = max; upcoming = first }
    public mutating func next() -> Int { defer { upcoming = Swift.min(max, upcoming * 2) }; return upcoming }
    public mutating func reset() { upcoming = first }
}
