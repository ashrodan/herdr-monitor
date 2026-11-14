import SwiftUI
import HerdrKit

// The app: the session list beside the open session. On an iPad both show
// at once; on a narrow width (Slide Over, an iPhone) the list comes first
// and a session opens on top of it.
public struct RootView: View {
    @State private var model: AppModel
    @State private var columns = NavigationSplitViewVisibility.all
    @Environment(\.scenePhase) private var phase

    public init(model: AppModel) { _model = State(initialValue: model) }

    public var body: some View {
        Group {
            if model.server.isEmpty {
                OnboardingView()
            } else {
                NavigationSplitView(columnVisibility: $columns) {
                    HomeView()
                        .navigationSplitViewColumnWidth(min: 300, ideal: 340, max: 420)
                } detail: {
                    if let address = model.selected {
                        // One view per session: its chat, its draft, its scroll place.
                        AgentView(address: address).id(address)
                    } else {
                        ContentUnavailableView("Pick a session", systemImage: "rectangle.stack", description: Text("The sessions of your Herdr spaces are in the list."))
                    }
                }
                .navigationSplitViewStyle(.balanced)
            }
        }
        .environment(model)
        .preferredColorScheme(model.theme == "Dark" ? .dark : model.theme == "Light" ? .light : nil)
        .task { model.start() }
        .onChange(of: phase) { _, now in
            // iOS cuts the link while the app is in the background. Back in
            // front: connect now, not at the next retry.
            if now == .active, !model.online { model.store.reconnect() }
        }
    }
}

// First run: the app needs the server's address.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var url = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://my-mac.tailnet.ts.net:7400", text: $url)
                        .urlField()
                        .onSubmit(connect)
                        .accessibilityIdentifier("server-field")
                } header: {
                    Text("Server")
                } footer: {
                    Text("The address of herdr-monitor on your Mac. The iPad must be on the same tailnet (Tailscale). Start the server with Tailscale Serve, then type its https address here.")
                }
                if let error { Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) } }
                Section {
                    Button(action: connect) {
                        HStack { Text("Connect"); if busy { Spacer(); ProgressView() } }
                    }
                    .disabled(busy || normalizeServer(url) == nil)
                    .accessibilityIdentifier("connect")
                }
            }
            .navigationTitle("Herdr Monitor")
        }
    }

    private func connect() {
        guard let server = normalizeServer(url), !busy else { return }
        busy = true; error = nil
        Task {
            if await Servers.probe(server, transport: model.transport) != nil { model.setServer(server) }
            else { error = "No herdr-monitor server answers at \(server). Check the address, and that Tailscale is on." }
            busy = false
        }
    }
}
