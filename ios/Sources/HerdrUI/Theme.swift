import SwiftUI
import HerdrKit
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// One status, one colour, everywhere. System colours, so they follow dark
// mode and Increase Contrast.
extension Status {
    var color: Color {
        switch self {
        case .waiting: return .orange
        case .failed: return .red
        case .working: return .blue
        case .done: return .green
        case .ready: return .teal
        case .idle: return .gray
        }
    }
    var label: String {
        switch self {
        case .waiting: return "Needs you"
        case .failed: return "Failed"
        case .working: return "Working"
        case .done: return "Done"
        case .ready: return "Ready to review"
        case .idle: return "Idle"
        }
    }
    var symbol: String {
        switch self {
        case .waiting: return "questionmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .working: return "circle.dotted"
        case .done: return "checkmark.circle.fill"
        case .ready: return "arrow.triangle.pull"
        case .idle: return "moon.zzz"
        }
    }
}

// A terminal state word (blocked, error, done, working, idle, finished).
func stateColor(_ state: String?) -> Color {
    switch state {
    case "blocked": return .orange
    case "error": return .red
    case "working": return .blue
    case "done": return .green
    default: return .gray
    }
}

let STATE_WORD = ["blocked": "Needs you", "error": "Failed", "done": "Done", "working": "Working", "idle": "Idle", "finished": "Finished"]

extension Color {
    // "#5f9cf0" -> a colour; nil when it is not six hex digits.
    init?(hex: String?) {
        guard let hex, hex.hasPrefix("#"), hex.count == 7, let v = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
    static let card = Color.primary.opacity(0.06)
    // A row of the grouped session list when it is not the open one.
    static var rowBackground: Color {
        #if os(iOS)
        Color(uiColor: .secondarySystemGroupedBackground)
        #else
        Color.clear
        #endif
    }
    static let codeBackground = Color.primary.opacity(0.08)
}

struct Dot: View {
    var color: Color
    var size: CGFloat = 9
    var body: some View { Circle().fill(color).frame(width: size, height: size) }
}

// The status mark of a terminal: a dot, or nothing for an idle shell.
struct StatusMark: View {
    var state: String?
    var size: CGFloat = 9
    var body: some View {
        if let state {
            Dot(color: stateColor(state), size: size)
                .opacity(state == "idle" || state == "finished" ? 0.45 : 1)
                .accessibilityLabel(STATE_WORD[state] ?? state)
        }
    }
}

// A space: a rounded square in its colour, with a letter.
struct SpaceAvatar: View {
    var letter: String
    var color: String?
    var size: CGFloat = 30
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
            .fill((Color(hex: color) ?? .gray).gradient)
            .frame(width: size, height: size)
            .overlay(Text(letter).font(.system(size: size * 0.48, weight: .semibold, design: .rounded)).foregroundStyle(.white))
            .accessibilityHidden(true)
    }
}

func spaceLetter(_ name: String) -> String {
    let l = String(name.drop { "~. ".contains($0) }.prefix(1)).uppercased()
    return l.isEmpty ? "~" : l
}

// The harness mark: a letter in a circle (Claude C, Codex C, omp and pi π).
struct HarnessMark: View {
    var agent: String
    var size: CGFloat = 20
    var body: some View {
        Text(harnessMark(agent))
            .font(.system(size: size * 0.55, weight: .bold, design: .rounded))
            .frame(width: size, height: size)
            .background(Circle().fill(tint.opacity(0.18)))
            .foregroundStyle(tint)
            .accessibilityLabel(harnessName(agent))
    }
    private var tint: Color {
        switch agent {
        case "claude-code", "claude": return .orange
        case "codex": return .mint
        case "omp", "pi": return .purple
        default: return .secondary
        }
    }
}

extension View {
    func inlineTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    // A URL field: no capitals, no autocorrect, the URL keyboard.
    func urlField() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        #else
        autocorrectionDisabled()
        #endif
    }
}

extension ToolbarItemPlacement {
    static var trailing: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .automatic
        #endif
    }
    static var leading: ToolbarItemPlacement {
        #if os(iOS)
        .topBarLeading
        #else
        .automatic
        #endif
    }
}

func copyToClipboard(_ s: String) {
    #if canImport(UIKit)
    UIPasteboard.general.string = s
    #elseif canImport(AppKit)
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(s, forType: .string)
    #endif
}

func image(from data: Data) -> Image? {
    #if canImport(UIKit)
    return UIImage(data: data).map(Image.init(uiImage:))
    #elseif canImport(AppKit)
    return NSImage(data: data).map(Image.init(nsImage:))
    #endif
}

// "9:31" today, else "Nov 6, 9:31".
func clockOf(_ ms: Int64) -> String {
    let d = Date(timeIntervalSince1970: Double(ms) / 1000)
    return Calendar.current.isDateInToday(d) ? d.formatted(date: .omitted, time: .shortened) : d.formatted(.dateTime.month(.abbreviated).day().hour().minute())
}

// The link state, in one line. With cached sessions on screen, it says how
// old they are. Nothing when all is well.
struct ConnectionLine: View {
    var live: Live
    var body: some View {
        if let text {
            Label(text, systemImage: live.link == .offline ? "wifi.slash" : "arrow.triangle.2.circlepath")
                .font(.footnote)
                .foregroundStyle(live.link == .offline ? Color.red : Color.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).padding(.vertical, 6)
                .accessibilityIdentifier("connection-line")
        }
    }
    private var text: String? {
        let from = live.cachedAt.map { " Showing sessions from \(clockOf($0))." } ?? ""
        switch live.link {
        case .connecting: return live.cachedAt != nil ? "Connecting…" + from : nil
        case .offline: return "No link to the server" + (live.error.map { ": \($0)" } ?? "") + "." + from
        case .online: return live.hcConnected ? nil : "Connected, but Herdr (hc) is not running on the Mac"
        }
    }
}
