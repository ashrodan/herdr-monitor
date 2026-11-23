// Renders the screens with the demo server to PNG files, on a Mac, with no
// Xcode and no simulator: a quick look at layout and data while developing.
// The real check is the iPad simulator in CI (UITests). macOS draws lists
// and bars its own way, so these pictures are not the iPad's look.
//
//   swift run Snapshots [out-dir]
#if os(macOS)
import AppKit
import SwiftUI
import HerdrKit
@testable import HerdrUI

@MainActor func render<V: View>(_ view: V, size: CGSize, to file: URL, dark: Bool) async {
    let host = NSHostingView(rootView: view.frame(width: size.width, height: size.height))
    let window = NSWindow(contentRect: CGRect(origin: .zero, size: size), styleMask: [.borderless], backing: .buffered, defer: false)
    window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    window.contentView = host
    window.orderBack(nil)
    // Let the store connect, the transcripts load and the layout settle.
    try? await Task.sleep(nanoseconds: 1_500_000_000)
    host.layoutSubtreeIfNeeded()
    guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
    host.cacheDisplay(in: host.bounds, to: rep)
    try? rep.representation(using: .png, properties: [:])?.write(to: file)
    window.close()
    print("wrote \(file.path)")
}

@MainActor func model(selected: String?) -> AppModel {
    let suite = "herdr-snapshots-\(UUID().uuidString)"
    let prefs = Prefs(UserDefaults(suiteName: suite)!)
    prefs.server = DemoTransport.server
    let m = AppModel(prefs: prefs, transport: DemoTransport(), cache: nil)
    m.selected = selected
    return m
}

let out = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "shots", isDirectory: true)
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
let app = NSApplication.shared
app.setActivationPolicy(.accessory)
Task { @MainActor in
    // iPad mini: 744 x 1133 points.
    let portrait = CGSize(width: 744, height: 1133), landscape = CGSize(width: 1133, height: 744)
    await render(RootView(model: model(selected: "t1")), size: landscape, to: out.appendingPathComponent("question-landscape.png"), dark: false)
    await render(RootView(model: model(selected: "t2")), size: portrait, to: out.appendingPathComponent("working-portrait.png"), dark: false)
    // macOS does not draw a split view's sidebar off screen: the list alone.
    let m = model(selected: nil)
    m.start()
    await render(NavigationStack { HomeView() }.environment(m), size: CGSize(width: 380, height: 1000), to: out.appendingPathComponent("list.png"), dark: false)
    exit(0)
}
app.run()
#else
print("Snapshots runs on macOS only.")
#endif
