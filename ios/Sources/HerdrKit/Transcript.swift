import Foundation

// The session transcript, flattened for the screen: agent prose first, and
// each run of thinking and tool calls folded into one activity item.

public enum Item: Equatable, Identifiable, Sendable {
    // Agent prose. `who` is true on the first prose after anything else, so
    // the harness mark shows once per run of prose.
    case prose(key: String, text: String, who: Bool)
    case user(key: String, text: String)
    case system(key: String, text: String)
    case activity(key: String, steps: [Part])
    case question(key: String, ask: Ask)

    public var id: String {
        switch self {
        case .prose(let k, _, _), .user(let k, _), .system(let k, _), .activity(let k, _), .question(let k, _): return k
        }
    }
}

public func toolsOf(_ steps: [Part]) -> [Tool] { steps.compactMap { if case .tool(let t) = $0 { return t } else { return nil } } }

private let HARNESS_NOTE = Rx("^\\[(Request interrupted|Request cancelled)[^\\]]*\\]$")

public func transcriptItems(_ messages: [Message]) -> [Item] {
    var out: [Item] = [], steps: [Part] = [], stepsKey = ""
    func flush() {
        if !steps.isEmpty { out.append(.activity(key: stepsKey, steps: steps)) }
        steps = []
    }
    for (mi, m) in messages.enumerated() {
        let base = m.id.isEmpty ? "m\(mi)" : m.id
        switch m.role {
        case "user":
            let text = m.texts.joined(separator: "\n"), trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            // A harness note in the user turn ("[Request interrupted by
            // user]") is a system line, not your text.
            if !trimmed.isEmpty {
                flush()
                out.append(HARNESS_NOTE.matches(trimmed) ? .system(key: base, text: String(trimmed.dropFirst().dropLast())) : .user(key: base, text: text))
            }
        case "system":
            let text = m.texts.joined(separator: " ")
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { flush(); out.append(.system(key: base, text: text)) }
        default:
            for (pi, p) in m.parts.enumerated() {
                let key = "\(base).\(pi)"
                switch p {
                case .thinking, .tool:
                    if steps.isEmpty { stepsKey = "act.\(key)" }
                    steps.append(p)
                case .text(let t):
                    if !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        flush()
                        var who = true
                        if case .prose = out.last { who = false }
                        out.append(.prose(key: key, text: t, who: who))
                    }
                case .ask(let a):
                    flush(); out.append(.question(key: key, ask: a))
                }
            }
        }
    }
    flush()
    return out
}

// "6 bash · 1 read": tool names by first use, with counts.
public func toolCounts(_ tools: [Tool]) -> String {
    var order: [String] = [], n: [String: Int] = [:]
    for t in tools { if n[t.name] == nil { order.append(t.name) }; n[t.name, default: 0] += 1 }
    return order.map { "\(n[$0]!) \($0)" }.joined(separator: " · ")
}

// The tool still running: the last tool with no result, when it is the
// last step of the transcript.
public func runningTool(_ items: [Item]) -> Tool? {
    guard case .activity(_, let steps) = items.last, case .tool(let t) = steps.last, t.result == nil else { return nil }
    return t
}

public func harnessName(_ agent: String?) -> String {
    switch agent {
    case nil, "", "?": return "agent"
    case "claude-code", "claude": return "Claude"
    case "codex": return "Codex"
    default: return agent!
    }
}

public func harnessMark(_ agent: String?) -> String {
    if agent == "omp" || agent == "pi" { return "π" }
    return String(harnessName(agent).prefix(1)).uppercased()
}

private let TICKET = Rx("\\b[A-Z][A-Z0-9]+-\\d+\\b")
public func ticketOf(_ text: String?) -> String? { text.flatMap { TICKET.first($0)?[0] } }

// Elapsed time in the current state: "8:06", or "1h 05m" from an hour.
public func clock(_ ms: Int64) -> String {
    let s = max(0, ms / 1000)
    return s < 3600 ? "\(s / 60):" + String(format: "%02d", s % 60) : "\(s / 3600)h " + String(format: "%02d", (s % 3600) / 60) + "m"
}

// The user's messages in a transcript, as (text, ms), for the outbox.
public func userTexts(_ messages: [Message]) -> [(text: String, at: Int64?)] {
    messages.filter { $0.role == "user" }.map { ($0.texts.joined(separator: "\n"), isoMs($0.at)) }
}
