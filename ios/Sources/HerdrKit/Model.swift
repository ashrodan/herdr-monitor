import Foundation

// Shapes the server sends (lib/hc.js, lib/sidebar.js, lib/transcript.js,
// lib/pr.js) and the pure rules the web UI and the Android app use
// (web/src/model.ts, android Model.kt). No UI types, so tests run them.

public struct Listen: Equatable, Hashable, Sendable {
    public var scheme: String, port: Int, host: String?
    public init(scheme: String, port: Int, host: String?) { self.scheme = scheme; self.port = port; self.host = host }
}

public struct Terminal: Equatable, Hashable, Identifiable, Sendable {
    public var address: String
    public var name: String?
    public var agent = "?"
    public var cwd: String?
    public var state = "live"            // live | finished
    public var title: String?
    public var `where`: String?
    public var reported = false
    public var status: String?           // working | blocked | done | idle | error
    public var kind: String?
    public var msg: String?
    public var exitCode: Int?
    public var parent: String?
    public var space: String?
    public var tab: String?
    public var changedMs: Int64?
    public var seenMs: Int64?
    public var listen: Listen?

    public var id: String { address }
    public var finished: Bool { state == "finished" }

    public init(address: String, name: String? = nil, agent: String = "?", cwd: String? = nil, state: String = "live", title: String? = nil,
                where: String? = nil, reported: Bool = false, status: String? = nil, kind: String? = nil, msg: String? = nil, exitCode: Int? = nil,
                parent: String? = nil, space: String? = nil, tab: String? = nil, changedMs: Int64? = nil, seenMs: Int64? = nil, listen: Listen? = nil) {
        self.address = address; self.name = name; self.agent = agent; self.cwd = cwd; self.state = state; self.title = title
        self.where = `where`; self.reported = reported; self.status = status; self.kind = kind; self.msg = msg; self.exitCode = exitCode
        self.parent = parent; self.space = space; self.tab = tab; self.changedMs = changedMs; self.seenMs = seenMs; self.listen = listen
    }
}

private let SHELLS: Set<String> = ["zsh", "bash", "fish", "sh", "nu", "pwsh"]

public extension Terminal {
    var isAgent: Bool { reported || !SHELLS.contains(agent) }
    // A dev server: it listens on a port and reports no agent status.
    var isApp: Bool { listen != nil && status == nil }

    func stateOf(_ localSeen: [String: Int64] = [:]) -> String { effectiveState(status, changedMs, seenMs, localSeen[address]) }

    // The status mark: an idle shell gets none.
    func mark(_ localSeen: [String: Int64] = [:]) -> String? {
        if finished { return "finished" }
        if status == nil { return isAgent ? "idle" : nil }
        return stateOf(localSeen)
    }
}

// The URL the device opens for an app, or nil when the host is not known
// (never a localhost URL: the iPad cannot reach the Mac's localhost).
public func appUrl(_ t: Terminal, serverUrl: String) -> String? {
    guard let l = t.listen else { return nil }
    let host: String
    if let h = l.host { host = h } else if t.address.contains("/") { return nil } else if let h = URL(string: serverUrl)?.host { host = h } else { return nil }
    return "\(l.scheme)://\(host):\(l.port)"
}

public struct SpaceTab: Equatable, Hashable, Identifiable, Sendable {
    public var id: String, name: String, named: Bool, terminals: [String], tree: Pane?
    public init(id: String, name: String, named: Bool = false, terminals: [String] = [], tree: Pane? = nil) {
        self.id = id; self.name = name; self.named = named; self.terminals = terminals; self.tree = tree
    }
}

public struct Space: Equatable, Hashable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var color: String?
    public var repo: String?
    public var branch: String?
    public var worktree = false
    public var virtual = false
    public var tabs: [SpaceTab] = []
    public var parent: String?
    public var behind = 0

    public init(id: String, name: String, color: String? = nil, repo: String? = nil, branch: String? = nil, worktree: Bool = false,
                virtual: Bool = false, tabs: [SpaceTab] = [], parent: String? = nil, behind: Int = 0) {
        self.id = id; self.name = name; self.color = color; self.repo = repo; self.branch = branch; self.worktree = worktree
        self.virtual = virtual; self.tabs = tabs; self.parent = parent; self.behind = behind
    }

    // The agent sessions of a space, in Herdr order. App servers and raw
    // shells are not sessions.
    public func agentAddresses(_ terminals: [String: Terminal]) -> [String] {
        var seen = Set<String>(), out: [String] = []
        for tab in tabs { for a in tab.terminals {
            guard let t = terminals[a], seen.insert(a).inserted, t.isAgent, !t.isApp else { continue }
            out.append(a)
        } }
        return out
    }
}

public struct Pr: Equatable, Hashable, Sendable {
    public var number: Int, title: String, url: String, isDraft: Bool, headRefName: String, reviewDecision: String?, updatedAt: String?
    public init(number: Int, title: String, url: String, isDraft: Bool = false, headRefName: String = "", reviewDecision: String? = nil, updatedAt: String? = nil) {
        self.number = number; self.title = title; self.url = url; self.isDraft = isDraft; self.headRefName = headRefName
        self.reviewDecision = reviewDecision; self.updatedAt = updatedAt
    }
}

public struct OverviewPr: Equatable, Hashable, Identifiable, Sendable {
    public var space: String, spaceName: String, color: String?, branch: String?, pr: Pr, readiness: String?, fail: Int, pending: Int
    public var id: String { pr.url }
    public init(space: String, spaceName: String, color: String? = nil, branch: String? = nil, pr: Pr, readiness: String? = nil, fail: Int = 0, pending: Int = 0) {
        self.space = space; self.spaceName = spaceName; self.color = color; self.branch = branch; self.pr = pr
        self.readiness = readiness; self.fail = fail; self.pending = pending
    }
}

public let GROUPS: [(state: String, label: String)] = [("blocked", "Blocked"), ("error", "Error"), ("done", "Done"), ("working", "Working"), ("idle", "Idle")]
// The states that post their own notification.
public let ALERT_STATES: Set<String> = ["blocked", "error"]

// "Done" turns into "idle" once someone looked at the agent after it
// finished: on the Mac (seen_ms) or on this device (localSeen).
public func effectiveState(_ state: String?, _ changedMs: Int64?, _ seenMs: Int64?, _ localSeen: Int64? = nil) -> String {
    let s = state ?? "idle"
    if s == "done", let c = changedMs, max(seenMs ?? 0, localSeen ?? 0) >= c { return "idle" }
    return s
}

public struct Where: Equatable, Sendable {
    public var space: Space?
    public var tabName: String?
    public var tabNamed: Bool
}

public func whereOf(_ t: Terminal, _ spaces: [Space]) -> Where {
    for s in spaces {
        if let ts = t.space, s.id != ts { continue }
        let tab = s.tabs.first { t.tab != nil ? $0.id == t.tab : $0.terminals.contains(t.address) }
        if tab != nil || (t.space != nil && s.id == t.space) { return Where(space: s, tabName: tab?.name, tabNamed: tab?.named ?? false) }
    }
    return Where(space: nil, tabName: nil, tabNamed: false)
}

// Its own name, else the space name (+ the tab when the tab has a name).
public func displayName(_ t: Terminal, _ spaces: [Space]) -> String {
    if let n = t.name { return n }
    let w = whereOf(t, spaces)
    if let s = w.space { return s.name + (w.tabNamed ? " · \(w.tabName ?? "")" : "") }
    if let first = t.where?.components(separatedBy: " / ").first, !first.trimmingCharacters(in: .whitespaces).isEmpty { return first }
    return t.agent
}

// The most urgent state among a space's terminals, for its dot.
public func spaceState(_ s: Space, _ terminals: [String: Terminal], _ localSeen: [String: Int64] = [:]) -> String? {
    let states = Set(s.tabs.flatMap(\.terminals).compactMap { terminals[$0] }.filter { !$0.finished }.map { $0.stateOf(localSeen) })
    return ["blocked", "error", "working"].first { states.contains($0) }
}

public func age(_ ms: Int64?, now: Int64 = nowMs()) -> String {
    guard let ms, ms != 0 else { return "" }
    let s = max(0, (now - ms) / 1000)
    if s < 60 { return "<1m" }
    if s < 3600 { return "\(s / 60)m" }
    if s < 86400 { return "\(s / 3600)h" }
    return "\(s / 86400)d"
}

private let HOME = Rx("^/(Users|home)/[^/]+(?=/|$)")
public func shortPath(_ p: String?) -> String { HOME.replace(p ?? "", "~") }

// ---- Chat (GET /api/terminals/<addr>/chat) ----

public struct AskOption: Equatable, Hashable, Sendable {
    public var label: String, description: String
    public init(label: String, description: String = "") { self.label = label; self.description = description }
}

public struct Question: Equatable, Hashable, Sendable {
    public var header: String, question: String, multi: Bool, options: [AskOption]
    public init(header: String = "", question: String, multi: Bool = false, options: [AskOption]) {
        self.header = header; self.question = question; self.multi = multi; self.options = options
    }
}

public struct ToolResult: Equatable, Hashable, Sendable {
    public var text: String, error: Bool, truncated: Bool
    public init(text: String, error: Bool = false, truncated: Bool = false) { self.text = text; self.error = error; self.truncated = truncated }
}

public struct Tool: Equatable, Hashable, Sendable {
    public var name: String, summary: String, result: ToolResult?
    public init(name: String, summary: String = "", result: ToolResult? = nil) { self.name = name; self.summary = summary; self.result = result }
}

public struct Ask: Equatable, Hashable, Sendable {
    public var id: String, questions: [Question], answered: Bool, answer: String?
    public init(id: String, questions: [Question], answered: Bool = false, answer: String? = nil) {
        self.id = id; self.questions = questions; self.answered = answered; self.answer = answer
    }

    // The shape the /answer route wants: {question, options (a count), multi}.
    public var shape: [QuestionShape] { questions.map { QuestionShape(question: $0.question, options: $0.options.count, multi: $0.multi) } }
    public var needsSubmit: Bool { questions.count > 1 || questions.contains { $0.multi } }

    // One single-choice question: a tap answers it, so a card can offer its
    // options as buttons (at most `max`). Otherwise nil: answer in Chat.
    public func quickOptions(max: Int = 3) -> [String]? {
        guard questions.count == 1, let q = questions.first, !q.multi, !q.options.isEmpty else { return nil }
        return q.options.prefix(max).map { $0.label.trimmingCharacters(in: .whitespaces).isEmpty ? "Option" : $0.label }
    }
}

public struct QuestionShape: Equatable, Hashable, Sendable {
    public var question: String, options: Int, multi: Bool
    public init(question: String, options: Int, multi: Bool) { self.question = question; self.options = options; self.multi = multi }
}

public enum Part: Equatable, Hashable, Sendable {
    case text(String)
    case thinking(String)
    case tool(Tool)
    case ask(Ask)
}

public struct Message: Equatable, Hashable, Sendable {
    public var id: String, role: String, at: String?, parts: [Part]
    public init(id: String = "", role: String, at: String? = nil, parts: [Part]) { self.id = id; self.role = role; self.at = at; self.parts = parts }
    var texts: [String] { parts.compactMap { if case .text(let t) = $0 { return t } else { return nil } } }
}

public struct Chat: Equatable, Sendable {
    public var available: Bool, reason: String?, version: String?, truncated: Bool, messages: [Message]
    public init(available: Bool, reason: String? = nil, version: String? = nil, truncated: Bool = false, messages: [Message] = []) {
        self.available = available; self.reason = reason; self.version = version; self.truncated = truncated; self.messages = messages
    }
}

// The question the agent waits on: the last unanswered question part.
public func lastUnansweredAsk(_ messages: [Message]) -> Ask? {
    for m in messages.reversed() {
        for p in m.parts { if case .ask(let a) = p, !a.answered { return a } }
    }
    return nil
}

// ---- Git and PR panels ----

public struct Commit: Equatable, Identifiable, Sendable {
    public var sha: String, author: String, date: String, subject: String, local: Bool
    public var id: String { sha }
}
public struct Commits: Equatable, Sendable {
    public var repo: String?
    public var reason: String?
    public var branch: String?
    public var unpushed: Int?
    public var dirty: Int?
    public var pr: Pr?
    public var commits: [Commit]
}
public struct CommitFile: Equatable, Sendable { public var file: String, additions: Int?, deletions: Int? }
public struct CommitDetail: Equatable, Sendable {
    public var sha: String, author: String, date: String, message: String, files: [CommitFile]
}
public struct Check: Equatable, Sendable { public var name: String, bucket: String, url: String? }
public struct PrStatus: Equatable, Sendable {
    public var repo: String?
    public var reason: String?
    public var branch: String?
    public var pr: Pr?
    public var readiness: String?
    public var reasons: [String]
    public var checks: [Check]
}

// ---- Servers ----

public struct HerdrInfo: Equatable, Sendable {
    public var uiVersion: String?
    public var uiRunning: Bool
    public var tuiVersion: String?
    public var tuiRunning: Bool
    public var hc: String?
    public var appLine: String { "Herdr app: " + (uiVersion.map { "\($0), " + (uiRunning ? "running" : "not running") } ?? "not installed") }
    public var tuiLine: String { "Herdr TUI: " + (tuiVersion.map { "\($0), " + (tuiRunning ? "running" : "not running") } ?? "not installed") }
}

// One herdr-monitor server. older: it has no /api/info, so no versions.
public struct ServerInfo: Equatable, Identifiable, Sendable {
    public var url: String, name: String, os: String?, isSelf: Bool, herdr: HerdrInfo?, older = false, host: String?
    public var id: String { url }
    public init(url: String, name: String, os: String? = nil, isSelf: Bool = false, herdr: HerdrInfo? = nil, older: Bool = false, host: String? = nil) {
        self.url = url; self.name = name; self.os = os; self.isSelf = isSelf; self.herdr = herdr; self.older = older; self.host = host
    }
}

public struct Worktree: Equatable, Identifiable, Sendable {
    public var path: String, name: String, branch: String?, space: String?
    public var id: String { path }
    public init(path: String, name: String, branch: String? = nil, space: String? = nil) { self.path = path; self.name = name; self.branch = branch; self.space = space }
}

public struct Started: Equatable, Sendable { public var address: String, tab: String?, placeError: String? }

// ---- Alerts ----

public enum AlertAction: Equatable, Sendable {
    case post(address: String, name: String, state: String, body: String)
    case cancel(address: String)
}

// prev: the alert state now on screen for that agent (nil: none). since:
// alert only for changes after this time, so a reconnect does not replay
// old alerts. t nil: the agent went away.
public func planAlert(address: String, prev: String?, terminal t: Terminal?, name: String, localSeen: [String: Int64] = [:], since: Int64? = nil) -> AlertAction? {
    guard let t, !t.finished, t.status != nil else { return prev != nil ? .cancel(address: address) : nil }
    let now = t.stateOf(localSeen)
    guard ALERT_STATES.contains(now) else { return prev != nil ? .cancel(address: address) : nil }
    if prev == now { return nil }
    if let since, (t.changedMs ?? 0) <= since { return nil }
    return .post(address: address, name: name, state: now, body: alertBody(t, now))
}

public func alertTitle(_ name: String, _ state: String) -> String {
    name + " · " + (state == "blocked" ? "needs you" : state)
}

public func alertBody(_ t: Terminal, _ state: String) -> String {
    if let m = t.msg, !m.trimmingCharacters(in: .whitespaces).isEmpty { return m }
    switch state {
    case "blocked": return "Waiting at a \(t.kind ?? "dialog")"
    case "done": return "Finished its turn"
    case "error": return "Stopped with an error"
    default: return ""
    }
}

// Machine name of a server URL: "ashleys-macbook-pro-2".
public func shortHost(_ url: String) -> String {
    guard let host = URL(string: url)?.host else { return url }
    if host.allSatisfy({ $0.isNumber || $0 == "." }) || host.contains(":") { return host }
    return host.components(separatedBy: ".").first ?? host
}

public func portOf(_ url: String) -> Int {
    guard let u = URL(string: url) else { return -1 }
    return u.port ?? (u.scheme == "https" ? 443 : 80)
}
