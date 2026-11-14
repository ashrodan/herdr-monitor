import SwiftUI
import UserNotifications
import HerdrKit

let HARNESSES: [(id: String, label: String)] = [("claude", "Claude Code"), ("omp", "omp")]
let PROMPT_MAX = 4000

// Start one agent in a space (POST /api/sessions). The server starts it in
// that space's folder; the app sends only the space, the harness and the
// task text.
struct NewSessionSheet: View {
    var space: String?
    var started: (String) -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var spaceId = ""
    @State private var harness = "claude"
    @State private var prompt = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        let spaces = model.live.spaces.filter { !$0.virtual }
        NavigationStack {
            Form {
                if spaces.isEmpty {
                    Text("No spaces yet. Open one in Herdr first.").foregroundStyle(.secondary)
                } else {
                    Picker("Space", selection: $spaceId) {
                        ForEach(spaces) { s in Text(s.parent == nil ? s.name : "↳ \(s.name)").tag(s.id) }
                    }
                    Picker("Harness", selection: $harness) {
                        ForEach(HARNESSES, id: \.id) { Text($0.label).tag($0.id) }
                    }
                    .pickerStyle(.segmented)
                    Section {
                        TextField("What should it do? A ticket key can go here too.", text: $prompt, axis: .vertical)
                            .lineLimit(4...12)
                            .focused($focused)
                            .accessibilityIdentifier("task-field")
                    } header: { Text("Task") } footer: {
                        HStack {
                            if prompt.hasPrefix("-") { Text("A task cannot start with '-'.").foregroundStyle(.red) }
                            Spacer()
                            Text("\(prompt.count) / \(PROMPT_MAX)").monospacedDigit().foregroundStyle(prompt.count > PROMPT_MAX ? .red : .secondary)
                        }
                    }
                    if let error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                }
            }
            .navigationTitle("New session")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: start) { if busy { ProgressView() } else { Text("Start") } }
                        .disabled(busy || !valid || !model.online)
                        .accessibilityIdentifier("start-session")
                }
            }
            .onAppear {
                spaceId = space ?? model.selected.flatMap { a in model.live.terminals[a].flatMap { whereOf($0, model.live.spaces).space?.id } } ?? spaces.first?.id ?? ""
                focused = true
            }
        }
    }

    private var valid: Bool {
        let p = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        return !spaceId.isEmpty && !p.isEmpty && prompt.count <= PROMPT_MAX && !prompt.hasPrefix("-")
    }

    private func start() {
        busy = true; error = nil
        Task {
            do {
                let s = try await model.api.startSession(space: spaceId, harness: harness, prompt: prompt)
                dismiss()
                started(s.address)
            } catch { self.error = "Not started: \(describe(error))" }
            busy = false
        }
    }
}

// The server (type it, or find the servers on the tailnet), the link's
// state, alerts and the look.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var found: [ServerInfo]?
    @State private var finding = false
    @State private var error: String?
    @State private var notify: String = "…"
    @State private var tested = false

    var body: some View {
        @Bindable var model = model
        let live = model.live
        NavigationStack {
            Form {
                Section {
                    TextField("https://my-mac.tailnet.ts.net:7400", text: $url).urlField().onSubmit(connect).accessibilityIdentifier("server-field")
                    Button("Connect", action: connect).disabled(normalizeServer(url) == nil || normalizeServer(url) == model.server)
                    if let error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                } header: { Text("Server") } footer: {
                    Text("The iPad must be on the same tailnet as the Mac. Use the https address of Tailscale Serve.")
                }
                Section {
                    Button { find() } label: { HStack { Text("Find on tailnet"); if finding { Spacer(); ProgressView() } } }.disabled(finding)
                    ForEach(found ?? []) { s in
                        Button { model.setServer(s.url); url = s.url } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(shortHost(s.url) + (s.isSelf ? " (answered)" : "")).fontWeight(.medium)
                                    Text(s.url).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                                    if let h = s.herdr { Text(h.appLine + " · " + h.tuiLine + (h.hc.map { " · hc \($0)" } ?? "")).font(.caption).foregroundStyle(.secondary) }
                                    else if s.older { Text("An older herdr-monitor (no version details)").font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer()
                                if s.url == model.server { Image(systemName: "checkmark").foregroundStyle(.tint) }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if let found, found.isEmpty { Text("No server answered.").foregroundStyle(.secondary) }
                    let recent = model.prefs.recentServers.filter { $0 != model.server }
                    ForEach(recent, id: \.self) { r in
                        Button { model.setServer(r); url = r } label: { Label(shortHost(r) + " · " + r, systemImage: "clock.arrow.circlepath").lineLimit(1) }
                    }
                } footer: {
                    Text("An app cannot read Tailscale's list of machines. The server you are connected to lists the others.")
                }
                Section("Link") {
                    LabeledContent("State") {
                        HStack(spacing: 6) {
                            Dot(color: live.link == .online ? .green : live.link == .connecting ? .orange : .red, size: 8)
                            Text(live.link == .online ? "Connected" : live.link == .connecting ? "Connecting" : "Offline")
                        }
                    }
                    if let e = live.error, live.link != .online { Text(e).font(.footnote).foregroundStyle(.red) }
                    LabeledContent("Herdr (hc)", value: live.link == .online ? (live.hcConnected ? "Running" : "Not running on the Mac") : "—")
                    let c = counts(model.sessions())
                    LabeledContent("Sessions", value: "\(c.needsYou) need you · \(c.working) working · \(c.done) done")
                    Button("Retry connection") { model.store.reconnect() }
                }
                Section {
                    Toggle("Alert when an agent needs you", isOn: $model.alertsOn)
                    LabeledContent("iOS notifications", value: notify)
                    Button(tested ? "Test alert sent" : "Send test alert") { Notifier.shared.test(); tested = true }
                } header: { Text("Alerts") } footer: {
                    Text("Alerts come while the app is open, and for a short time after you leave it. iOS stops the link of an app in the background, so the app cannot alert you all day. See the README for the plan (Apple push).")
                }
                Section("Look") {
                    Picker("Appearance", selection: $model.theme) {
                        ForEach(["System", "Dark", "Light"], id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("Settings")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.accessibilityIdentifier("settings-done") } }
            .onAppear { url = model.server }
            .task { notify = await Notifier.shared.stateText() }
            .onChange(of: model.alertsOn) { _, on in if on { Task { await Notifier.shared.ask(); notify = await Notifier.shared.stateText() } } }
        }
    }

    private func connect() {
        guard let server = normalizeServer(url) else { return }
        error = nil
        Task {
            if await Servers.probe(server, transport: model.transport) != nil { model.setServer(server); url = server }
            else { error = "No herdr-monitor server answers at \(server)." }
        }
    }

    private func find() {
        finding = true
        Task {
            found = await Servers.find(known: [model.server] + model.prefs.recentServers, transport: model.transport)
            finding = false
        }
    }
}

// Local notifications: one per agent that needs you (waiting at a question,
// or failed). A tap opens that agent. It goes away when the agent moves on.
@MainActor
public final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = Notifier()
    private weak var model: AppModel?
    private var enabled = false
    private var center: UNUserNotificationCenter { UNUserNotificationCenter.current() }

    // demo: no permission prompt and no notifications (UI tests, screenshots).
    public func attach(_ model: AppModel, demo: Bool) {
        self.model = model
        guard !demo else { return }
        enabled = true
        center.delegate = self
        model.alertSink = { [weak self] in self?.handle($0) }
        if model.alertsOn { Task { await ask() } }
    }

    func ask() async {
        guard enabled else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    func stateText() async -> String {
        guard enabled else { return "Off in the demo" }
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: return "Allowed"
        case .denied: return "Not allowed (change it in Settings)"
        default: return "Not asked yet"
        }
    }

    private func handle(_ action: AlertAction) {
        switch action {
        case .post(let address, let name, let state, let body):
            let c = UNMutableNotificationContent()
            c.title = alertTitle(name, state)
            c.body = body
            c.sound = .default
            c.userInfo = ["address": address]
            c.threadIdentifier = "needs-you"
            center.add(UNNotificationRequest(identifier: address, content: c, trigger: nil))
        case .cancel(let address):
            center.removeDeliveredNotifications(withIdentifiers: [address])
            center.removePendingNotificationRequests(withIdentifiers: [address])
        }
    }

    func test() {
        guard enabled else { return }
        Task {
            await ask()
            handle(.post(address: "test-alert", name: "Test agent", state: "blocked", body: "This is a test. No agent was contacted."))
        }
    }

    // The app is in front: still show the banner (the agent may be another one).
    nonisolated public func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    nonisolated public func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let address = response.notification.request.content.userInfo["address"] as? String
        await MainActor.run {
            guard let address, address != "test-alert", let model = Notifier.shared.model else { return }
            model.selected = address
        }
    }
}
