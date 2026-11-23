import Foundation

// Enough GitHub-flavoured Markdown for agent chat, without a library:
// headings, paragraphs, - and 1. lists, > quotes, --- rules, ``` code,
// | tables |, and inline **bold**, *italic*, `code`, [links](url) and bare
// URLs. keepBreaks: single line breaks stay (for your own prompts).

public enum Block: Equatable, Sendable {
    case heading(level: Int, text: String)
    case para(String)
    case code(lang: String, text: String)
    case items(ordered: Bool, start: Int, items: [String])
    case table(header: [String], rows: [[String]])
    case quote(String)
    case rule
}

public struct Span: Equatable, Sendable {
    public var text: String, bold = false, italic = false, code = false, url: String?
    public init(_ text: String, bold: Bool = false, italic: Bool = false, code: Bool = false, url: String? = nil) {
        self.text = text; self.bold = bold; self.italic = italic; self.code = code; self.url = url
    }
}

public enum Markdown {
    private static let HEADING = Rx("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private static let BULLET = Rx("^\\s{0,3}[-*+]\\s+(.*)$")
    private static let NUMBER = Rx("^\\s{0,3}(\\d{1,9})[.)]\\s+(.*)$")
    private static let RULE = Rx("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private static let TABLE_SEP = Rx("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespaces) }

    public static func cells(_ line: String) -> [String] {
        var t = trim(line)
        if t.hasPrefix("|") { t.removeFirst() }
        if t.hasSuffix("|") { t.removeLast() }
        return t.components(separatedBy: "|").map(trim)
    }

    public static func blocks(_ src: String, keepBreaks: Bool = false) -> [Block] {
        let lines = src.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var out: [Block] = [], para: [String] = []
        func flush() {
            if !para.isEmpty { out.append(.para(para.map(trim).joined(separator: keepBreaks ? "\n" : " "))) }
            para = []
        }
        var i = 0
        while i < lines.count {
            let line = lines[i], t = trim(line)
            if t.hasPrefix("```") || t.hasPrefix("~~~") {
                flush()
                let fence = String(t.prefix(3)), lang = trim(String(t.dropFirst(3)))
                var body: [String] = []
                i += 1
                while i < lines.count, !trim(lines[i]).hasPrefix(fence) { body.append(lines[i]); i += 1 }
                out.append(.code(lang: lang, text: body.joined(separator: "\n")))
            } else if t.isEmpty {
                flush()
            } else if let h = HEADING.first(t) {
                flush(); out.append(.heading(level: h[1]?.count ?? 1, text: h[2] ?? ""))
            } else if RULE.matches(line) {
                flush(); out.append(.rule)
            } else if t.hasPrefix("|"), i + 1 < lines.count, TABLE_SEP.matches(lines[i + 1]) {
                flush()
                let header = cells(t)
                i += 2
                var rows: [[String]] = []
                while i < lines.count, trim(lines[i]).hasPrefix("|") { rows.append(cells(lines[i])); i += 1 }
                out.append(.table(header: header, rows: rows))
                continue
            } else if t.hasPrefix(">") {
                flush()
                var body: [String] = []
                while i < lines.count, trim(lines[i]).hasPrefix(">") { body.append(trim(String(trim(lines[i]).dropFirst()))); i += 1 }
                out.append(.quote(body.joined(separator: " ")))
                continue
            } else if BULLET.matches(line) || NUMBER.matches(line) {
                flush()
                let ordered = NUMBER.matches(line)
                let start = ordered ? Int(NUMBER.first(line)?[1] ?? "1") ?? 1 : 1
                var items: [String] = []
                while i < lines.count {
                    let l = lines[i]
                    if let m = (ordered ? NUMBER : BULLET).first(l) { items.append((m.last ?? nil) ?? "") }
                    else if !trim(l).isEmpty, l.hasPrefix("  "), !items.isEmpty { items[items.count - 1] += " " + trim(l) }
                    else { break }
                    i += 1
                }
                out.append(.items(ordered: ordered, start: start, items: items))
                continue
            } else {
                para.append(line)
            }
            i += 1
        }
        flush()
        return out
    }

    private static let INLINE = Rx(
        "`([^`]+)`" +                                    // 1 code
        "|\\*\\*(.+?)\\*\\*|__(.+?)__" +                 // 2, 3 bold
        "|\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)" +    // 4 text, 5 url
        "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)" + // 6, 7 italic
        "|(https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])")    // 8 bare url

    public static func spans(_ text: String, bold: Bool = false, italic: Bool = false) -> [Span] {
        var out: [Span] = []
        var at = text.startIndex
        for m in INLINE.all(text) {
            if m.range.lowerBound > at { out.append(Span(String(text[at..<m.range.lowerBound]), bold: bold, italic: italic)) }
            let g = m.groups
            if let c = g[1] { out.append(Span(c, bold: bold, italic: italic, code: true)) }
            else if let b = g[2] ?? g[3] { out += spans(b, bold: true, italic: italic) }
            else if let t = g[4], let u = g[5] { out.append(Span(t, bold: bold, italic: italic, url: u)) }
            else if let i = g[6] ?? g[7] { out += spans(i, bold: bold, italic: true) }
            else if let u = g[8] { out.append(Span(u, bold: bold, italic: italic, url: u)) }
            at = m.range.upperBound
        }
        if at < text.endIndex { out.append(Span(String(text[at...]), bold: bold, italic: italic)) }
        return out
    }
}
