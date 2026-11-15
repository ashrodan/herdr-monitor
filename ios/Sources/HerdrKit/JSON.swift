import Foundation

// The server's JSON is read as dictionaries, not Codable: a field that is
// missing, null or of another type must never fail a whole snapshot.
public typealias Obj = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? { self[k] as? String }
    func text(_ k: String, _ fallback: String = "") -> String { (self[k] as? String) ?? fallback }
    func int(_ k: String) -> Int? { self[k] is Bool ? nil : (self[k] as? NSNumber)?.intValue }
    func int64(_ k: String) -> Int64? { self[k] is Bool ? nil : (self[k] as? NSNumber)?.int64Value }
    func double(_ k: String) -> Double? { (self[k] as? NSNumber)?.doubleValue }
    func bool(_ k: String) -> Bool { (self[k] as? Bool) ?? false }
    func obj(_ k: String) -> Obj? { self[k] as? Obj }
    func objs(_ k: String) -> [Obj] { (self[k] as? [Any])?.compactMap { $0 as? Obj } ?? [] }
    func strings(_ k: String) -> [String] { (self[k] as? [Any])?.compactMap { $0 as? String } ?? [] }
}

public enum JSON {
    public static func obj(_ text: String) -> Obj? { obj(Data(text.utf8)) }
    public static func obj(_ data: Data) -> Obj? { (try? JSONSerialization.jsonObject(with: data)) as? Obj }
    public static func data(_ value: Any) -> Data { (try? JSONSerialization.data(withJSONObject: value)) ?? Data("{}".utf8) }
}

// A small NSRegularExpression wrapper (ICU: lookbehind works).
struct Rx: @unchecked Sendable {
    let re: NSRegularExpression
    init(_ pattern: String, _ options: NSRegularExpression.Options = []) { re = try! NSRegularExpression(pattern: pattern, options: options) }

    // The groups of each match (group 0 first); nil for a group that did not take part.
    func all(_ s: String) -> [(range: Range<String.Index>, groups: [String?])] {
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard let r = Range(m.range, in: s) else { return nil }
            let groups = (0..<m.numberOfRanges).map { i -> String? in m.range(at: i).location == NSNotFound ? nil : ns.substring(with: m.range(at: i)) }
            return (r, groups)
        }
    }
    func first(_ s: String) -> [String?]? { all(s).first?.groups }
    func matches(_ s: String) -> Bool { re.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil }
    func replace(_ s: String, _ with: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: with)
    }
}

public func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

// "2026-11-07T07:51:54.214Z" -> ms. nil when it is not an ISO time.
public func isoMs(_ s: String?) -> Int64? {
    guard let s else { return nil }
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let d = f.date(from: s) { return Int64(d.timeIntervalSince1970 * 1000) }
    f.formatOptions = [.withInternetDateTime]
    return f.date(from: s).map { Int64($0.timeIntervalSince1970 * 1000) }
}
