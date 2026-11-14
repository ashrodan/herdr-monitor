import Foundation

// Terminal glyphs that must not reach UI text: the Unicode private use
// areas (Nerd Font and Powerline icons draw as a box) and box-drawing
// characters (pane chrome).
public enum Glyphs {
    private static func privateUse(_ v: UInt32) -> Bool { (0xE000...0xF8FF).contains(v) || (0xF0000...0xFFFFD).contains(v) || (0x100000...0x10FFFD).contains(v) }
    private static func boxDrawing(_ v: UInt32) -> Bool { (0x2500...0x257F).contains(v) }
    private static func braille(_ v: UInt32) -> Bool { (0x2800...0x28FF).contains(v) }

    private static func strip(_ s: String, box: Bool, spin: Bool = false) -> String {
        let drop: (Unicode.Scalar) -> Bool = { privateUse($0.value) || (box && boxDrawing($0.value)) || (spin && braille($0.value)) }
        guard s.unicodeScalars.contains(where: drop) else { return s }
        var out = String.UnicodeScalarView()
        for u in s.unicodeScalars where !drop(u) { out.append(u) }
        return String(out)
    }

    private static let gaps = Rx("[ \\t]{2,}")

    // A one-line label (a name, a title, a status message). Braille goes
    // too: TUIs spin it in titles, and a label must not change each frame.
    public static func label(_ s: String?) -> String? {
        guard let s else { return nil }
        return gaps.replace(strip(s, box: true, spin: true), " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // Chat prose: box-drawing stays inside ``` code blocks only.
    public static func prose(_ s: String) -> String {
        guard s.contains("```") else { return strip(s, box: true) }
        return s.components(separatedBy: "```").enumerated().map { strip($1, box: $0 % 2 == 0) }.joined(separator: "```")
    }

    // The terminal screen and tool output: a monospaced font draws box-drawing.
    public static func mono(_ s: String) -> String { strip(s, box: false) }
}
