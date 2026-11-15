import SwiftUI
import HerdrKit
import HerdrUI

// The app target is a thin shell: every screen is in the HerdrUI package.
//
// Launch arguments, for UI tests and screenshots:
//   -demo            a server in memory with made-up sessions; nothing is
//                    kept between runs, and no notification is asked for
//   -demo-select t1  open that session at the start
@main
struct HerdrMonitorApp: App {
    @State private var model: AppModel

    init() {
        let args = ProcessInfo.processInfo.arguments
        let demo = args.contains("-demo")
        let model: AppModel
        if demo {
            let suite = "herdr-demo"
            UserDefaults().removePersistentDomain(forName: suite)
            let prefs = Prefs(UserDefaults(suiteName: suite)!)
            prefs.server = DemoTransport.server
            model = AppModel(prefs: prefs, transport: DemoTransport(), cache: nil)
            if let i = args.firstIndex(of: "-demo-select"), i + 1 < args.count { model.selected = args[i + 1] }
        } else {
            model = AppModel()
        }
        Notifier.shared.attach(model, demo: demo)
        _model = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup { RootView(model: model) }
    }
}
