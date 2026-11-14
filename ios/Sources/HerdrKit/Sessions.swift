import Foundation

// Home as an inbox of agents. Pure: the screen gets ready-to-draw Sessions.

// A Working session with no output for this long shows a stale chip.
public let STALE_MS: Int64 = 30 * 60 * 1000

public enum Bucket: Int, CaseIterable, Comparable, Sendable {
    case needsYou, working, done, ready, idle
    public static func < (a: Bucket, b: Bucket) -> Bool { a.rawValue < b.rawValue }
    public var title: String {
        switch self {
        case .needsYou: return "Needs you"
        case .working: return "Working"
        case .done: return "Done"
        case .ready: return "Ready to review"
        case .idle: return "Idle"
        }
    }
}

// One status, one colour, one shape, everywhere.
public enum Status: String, Sendable { case waiting, failed, working, done, ready, idle }

// What the app read from one agent's transcript, for its card.
public struct Digest: Equatable, Sendable {
    public var version: String?, snippet: String?, lastAtMs: Int64?, ask: Ask?
    public init(version: String? = nil, snippet: String? = nil, lastAtMs: Int64? = nil, ask: Ask? = nil) {
        self.version = version; self.snippet = snippet; self.lastAtMs = lastAtMs; self.ask = ask
    }
}

// An answer sent from a card: the card shows "Sent: <label>" and Working
// until the agent's next output.
public struct SentMark: Equatable, Sendable {
    public var label: String, atMs: Int64
    public init(label: String, atMs: Int64) { self.label = label; self.atMs = atMs }
}

public struct Session: Equatable, Identifiable, Sendable {
    public var address: String
    public var name: String
    public var task: String?
    public var snippet: String?
    public var bucket: Bucket
    public var status: Status
    public var timeMs: Int64?
    public var unread: Bool
    public var staleForMs: Int64?
    public var branch: String?
    public var pr: OverviewPr?
    public var ask: Ask?
    public var sent: String?
    public var pinned: Bool
    public var spaceId: String?
    public var spaceName: String?
    public var spaceColor: String?
    public var letter: String
    public var agent: String
    public var id: String { address }
}

public struct Counts: Equatable, Sendable {
    public var all = 0, needsYou = 0, working = 0, done = 0, ready = 0, idle = 0
    public func of(_ b: Bucket?) -> Int {
        switch b {
        case nil: return all
        case .needsYou: return needsYou
        case .working: return working
        case .done: return done
        case .ready: return ready
        case .idle: return idle
        }
    }
}

public func counts(_ sessions: [Session]) -> Counts {
    var c = Counts(all: sessions.count)
    for s in sessions {
        switch s.bucket {
        case .needsYou: c.needsYou += 1
        case .working: c.working += 1
        case .done: c.done += 1
        case .ready: c.ready += 1
        case .idle: c.idle += 1
        }
    }
    return c
}

// "MNT-1 Android app · android app" -> "MNT-1 Android app": drop a
// " · suffix" that only repeats the name.
public func dedupName(_ name: String) -> String {
    guard let r = name.range(of: " · ", options: .backwards), r.lowerBound > name.startIndex else { return name }
    let head = String(name[..<r.lowerBound])
    func norm(_ s: String) -> String { s.lowercased().filter { ("a"..."z").contains($0) || ("0"..."9").contains($0) } }
    let tail = norm(String(name[r.upperBound...]))
    return !tail.isEmpty && norm(head).contains(tail) ? head : name
}

private let FENCE = Rx("```[\\s\\S]*?(```|$)")
private let CODE = Rx("`([^`]*)`")
private let LINK = Rx("!?\\[([^\\]]*)\\]\\([^)]*\\)")
private let LEAD = Rx("^\\s{0,3}(#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+|>\\s?|\\|)", [.anchorsMatchLines])
private let EMPH = Rx("\\*\\*|__|(?<!\\w)[*_](?!\\s)|(?<!\\s)[*_](?!\\w)")
private let SPACE = Rx("\\s+")
private let SENTENCE = Rx("[^.!?]+[.!?]+(\\s|$)|[^.!?]+$")

// The agent's last words: the last two sentences of its last prose, with
// Markdown marks and code removed. Never a pane title or a tool name.
public func lastProse(_ messages: [Message]) -> String? {
    func hasText(_ s: String) -> Bool { !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    guard let m = messages.last(where: { $0.role == "assistant" && $0.texts.contains(where: hasText) }),
          let text = m.texts.last(where: hasText) else { return nil }
    var plain = FENCE.replace(text, " ")
    plain = CODE.replace(plain, "$1")
    plain = LINK.replace(plain, "$1")
    plain = LEAD.replace(plain, "")
    plain = EMPH.replace(plain, "")
    plain = SPACE.replace(plain, " ").trimmingCharacters(in: .whitespaces)
    if plain.isEmpty { return nil }
    let sentences = SENTENCE.all(plain).map { ($0.groups[0] ?? "").trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    let joined = sentences.suffix(2).joined(separator: " ")
    let out = joined.isEmpty ? plain : joined
    return out.count > 220 ? "…" + String(out.suffix(219)) : out
}

public func digest(_ chat: Chat) -> Digest {
    Digest(version: chat.version, snippet: lastProse(chat.messages), lastAtMs: isoMs(chat.messages.last?.at), ask: lastUnansweredAsk(chat.messages))
}

public func buildSessions(
    terminals: [Terminal], spaces: [Space], prs: [OverviewPr], localSeen: [String: Int64], opened: [String: Int64], unreadSince: Int64,
    digests: [String: Digest], pinned: Set<String>, sent: [String: SentMark], now: Int64
) -> [Session] {
    let agents = terminals.filter { !$0.finished && $0.status != nil }
    let out: [Session] = agents.map { t in
        let w = whereOf(t, spaces)
        let d = digests[t.address]
        let pr = w.space.flatMap { s in prs.first { $0.space == s.id } }
        let state = t.stateOf(localSeen)
        let last = max(d?.lastAtMs ?? 0, t.changedMs ?? 0)
        let lastOutput: Int64? = last > 0 ? last : nil
        let mark = sent[t.address].flatMap { (lastOutput ?? 0) <= $0.atMs ? $0 : nil }
        let bucket: Bucket, status: Status
        if mark != nil { (bucket, status) = (.working, .working) }
        else if state == "blocked" { (bucket, status) = (.needsYou, .waiting) }
        else if state == "error" { (bucket, status) = (.needsYou, .failed) }
        else if state == "working" { (bucket, status) = (.working, .working) }
        else if state == "done" { (bucket, status) = (.done, .done) }
        else if let pr, !pr.pr.isDraft { (bucket, status) = (.ready, .ready) }
        else { (bucket, status) = (.idle, .idle) }
        let ask = status == .waiting ? d?.ask : nil
        let name = dedupName(displayName(t, spaces))
        let fromAsk = ask?.questions.first.flatMap { q in q.header.trimmingCharacters(in: .whitespaces).isEmpty ? q.question.components(separatedBy: "\n").first : q.header }
        let path = shortPath(t.cwd)
        let task = fromAsk ?? w.space?.branch ?? (w.tabNamed ? w.tabName : nil) ?? (path.isEmpty ? nil : path)
        var stale: Int64?
        if status == .working, mark == nil, let at = d?.lastAtMs, now - at > STALE_MS { stale = now - at }
        let letterSource = (w.space?.name ?? name).drop { "~. ".contains($0) }
        let letter = String(letterSource.prefix(1)).uppercased()
        return Session(
            address: t.address, name: name, task: task == name ? nil : task,
            snippet: d?.snippet ?? t.msg.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 },
            bucket: bucket, status: status, timeMs: lastOutput, unread: (lastOutput ?? 0) > (opened[t.address] ?? unreadSince),
            staleForMs: stale, branch: w.space?.branch, pr: pr, ask: ask, sent: mark?.label, pinned: pinned.contains(t.address),
            spaceId: w.space?.id, spaceName: w.space?.name, spaceColor: w.space?.color, letter: letter.isEmpty ? "~" : letter, agent: t.agent)
    }
    // Section, then pinned first, then the newest; the address keeps the order stable.
    return out.sorted { a, b in
        if a.bucket != b.bucket { return a.bucket < b.bucket }
        if a.pinned != b.pinned { return a.pinned }
        if (a.timeMs ?? 0) != (b.timeMs ?? 0) { return (a.timeMs ?? 0) > (b.timeMs ?? 0) }
        return a.address < b.address
    }
}

// The search field filters on the device: name, task, snippet, space,
// branch. It sends nothing anywhere.
public func matches(_ s: Session, query: String) -> Bool {
    let q = query.trimmingCharacters(in: .whitespaces).lowercased()
    if q.isEmpty { return true }
    return [s.name, s.task, s.snippet, s.spaceName, s.branch, s.agent, s.address].contains { $0?.lowercased().contains(q) ?? false }
}

// "3h", "45m": for the stale chip.
public func duration(_ ms: Int64) -> String {
    let m = ms / 60_000
    if m < 60 { return "\(m)m" }
    if m < 60 * 24 { return "\(m / 60)h" }
    return "\(m / (60 * 24))d"
}

// PRs waiting for a review: open, not a draft, review required.
public func awaitingReview(_ prs: [OverviewPr]) -> Int { prs.filter { !$0.pr.isDraft && $0.pr.reviewDecision == "REVIEW_REQUIRED" }.count }

// The cached part of a Digest: the snippet and the last output time. The
// question is left out: Home reads the transcript again before it shows
// answer buttons.
public func digestsToJson(_ d: [String: Digest]) -> String {
    var o: [String: Any] = [:]
    for (addr, v) in d where v.snippet != nil || v.lastAtMs != nil {
        var e: [String: Any] = [:]
        if let s = v.snippet { e["s"] = s }
        if let t = v.lastAtMs { e["t"] = t }
        o[addr] = e
    }
    return String(decoding: JSON.data(o), as: UTF8.self)
}

public func digestsFromJson(_ text: String) -> [String: Digest] {
    var out: [String: Digest] = [:]
    for (k, v) in JSON.obj(text) ?? [:] {
        guard let e = v as? Obj else { continue }
        out[k] = Digest(snippet: e.str("s").flatMap { $0.isEmpty ? nil : $0 }, lastAtMs: e.int64("t"))
    }
    return out
}
