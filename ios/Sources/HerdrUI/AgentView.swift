import SwiftUI
import HerdrKit

enum SessionPanel: String, Identifiable {
    case terminal, commits, status, map
    var id: String { rawValue }
}

// One session: the chat is the primary surface. The reply box is at the
// bottom; PR, commits, status and running app servers are pills above it.
// The toolbar has the space map and the session options.
struct AgentView: View {
    let address: String
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var chat: Chat?
    @State private var items: [Item] = []
    @State private var error: String?
    @State private var commits: Commits?
    @State private var panel: SessionPanel?
    @State private var rawAddress = ""
    @State private var now = nowMs()

    private var terminal: Terminal? { model.live.terminals[address] }

    var body: some View {
        let live = model.live
        let t = terminal
        let queued = model.outbox.items.filter { $0.server == model.server && $0.address == address }
        let name = t.map { dedupName(displayName($0, live.spaces)) } ?? address
        let space = t.flatMap { whereOf($0, live.spaces).space }
        let pr = space.flatMap { s in live.prs.first { $0.space == s.id } }?.pr ?? commits?.pr
        let branch = space?.branch ?? commits?.branch
        VStack(spacing: 0) {
            ConnectionLine(live: live)
            content(t, live, queued)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaInset(edge: .bottom, spacing: 0) { bottom(t, live, pr) }
        .navigationTitle(name)
        .inlineTitle()
        .toolbar {
            ToolbarItem(placement: .principal) { title(name, t, space) }
            ToolbarItem(placement: .trailing) {
                Button { panel = .map } label: { Label("Space map", systemImage: "rectangle.split.2x1") }
                    .accessibilityIdentifier("space-map")
            }
            ToolbarItem(placement: .trailing) { options(t, live, name: name, branch: branch, pr: pr) }
        }
        .sheet(item: $panel) { p in
            switch p {
            case .terminal: PanelSheet(title: "Raw terminal") { RawTerminalView(address: rawAddress.isEmpty ? address : rawAddress) }
            case .commits: PanelSheet(title: "Commits") { CommitsView(address: address, initial: commits) }
            case .status: PanelSheet(title: "Status") { StatusView(address: address) }
            case .map: SpaceMapSheet(address: address)
            }
        }
        // Read the transcript while the session shows; faster while it works.
        .task(id: live.link == .online) { await poll() }
        .task(id: live.link == .online) {
            if live.link == .online { commits = try? await model.api.commits(address) }
        }
        .task { while !Task.isCancelled { try? await Task.sleep(nanoseconds: 1_000_000_000); now = nowMs() } }
        .onAppear {
            chat = model.chats.get(model.server, address)
            if let chat { items = transcriptItems(chat.messages) }
            model.markOpened(address)
        }
        .onChange(of: t?.status) { _, _ in model.markOpened(address) }
    }

    private func poll() async {
        guard model.live.link == .online else { return }
        let api = model.api
        while !Task.isCancelled {
            do {
                if let c = try await api.chat(address, version: chat?.version) {
                    // Fold the transcript off the main thread: it can be long.
                    let folded = await Task.detached { transcriptItems(c.messages) }.value
                    if Task.isCancelled { return }
                    chat = c; items = folded
                    model.chats.put(api.base, address, c)
                    model.outbox.dropDelivered(server: api.base, address: address, userMessages: userTexts(c.messages))
                }
                error = nil
            } catch is CancellationError { return } catch { self.error = describe(error) }
            let busy = ["working", "blocked"].contains(terminal?.status ?? "")
            try? await Task.sleep(nanoseconds: busy ? 2_500_000_000 : 6_000_000_000)
        }
    }

    @ViewBuilder private func content(_ t: Terminal?, _ live: Live, _ queued: [OutItem]) -> some View {
        if t == nil, live.link == .online {
            ContentUnavailableView("This terminal is gone", systemImage: "xmark.rectangle")
        } else if let c = chat, c.available {
            ChatList(address: address, chat: c, items: items, agent: t?.agent ?? "?", blocked: t?.status == "blocked", working: t?.status == "working", queued: queued)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let c = chat {
                        Text("\(c.reason ?? "No transcript.") Open Raw terminal from the session options.").foregroundStyle(.secondary)
                    } else if let error {
                        Label("Could not read the transcript: \(error)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    } else if live.link != .online {
                        Text("No link to the server. The chat loads when the link is back.").foregroundStyle(.secondary)
                    } else {
                        ProgressView("Loading the conversation…").frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    QueuedReplies(items: queued)
                }
                .padding(16)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder private func bottom(_ t: Terminal?, _ live: Live, _ pr: Pr?) -> some View {
        let pills = SessionPills(address: address, commits: commits, pr: pr, apps: companions(live).filter { $0.isApp && !$0.finished }, panel: $panel)
        if let t, !t.finished {
            Composer(address: address, online: live.link == .online, blocked: t.status == "blocked", kind: t.kind, working: t.status == "working", harness: t.agent) { pills }
        } else if t == nil, live.link != .online {
            Composer(address: address, online: false, blocked: false, kind: nil, working: false, harness: nil) { EmptyView() }
        } else if t != nil {
            pills.padding(.horizontal, 12).padding(.vertical, 8).background(.bar)
        }
    }

    // The other terminals of this session's space.
    private func companions(_ live: Live) -> [Terminal] {
        guard let t = terminal, let space = whereOf(t, live.spaces).space else { return [] }
        var seen = Set<String>()
        return space.tabs.flatMap(\.terminals).filter { seen.insert($0).inserted }.compactMap { live.terminals[$0] }
    }

    private func title(_ name: String, _ t: Terminal?, _ space: Space?) -> some View {
        let raw = t?.finished == true ? "finished" : (t?.status ?? "idle")
        let bits: [String?] = [STATE_WORD[raw] ?? raw, ticketOf(name) ?? space.map { ticketOf($0.name) ?? $0.name }, t.map { harnessName($0.agent) },
                               t?.changedMs.map { clock(now - $0) }]
        return VStack(spacing: 1) {
            Text(name).font(.headline).lineLimit(1)
            HStack(spacing: 5) {
                Dot(color: stateColor(raw), size: 8)
                Text(bits.compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary).monospacedDigit().lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("session-title")
    }

    private func options(_ t: Terminal?, _ live: Live, name: String, branch: String?, pr: Pr?) -> some View {
        let shells = companions(live).filter { $0.address != address && !$0.isApp && !$0.isAgent && !$0.finished }
        return Menu {
            if t != nil, ["working", "blocked"].contains(t?.status ?? ""), live.link == .online {
                Button(role: .destructive) { Task { try? await model.api.interrupt(address) } } label: { Label("Stop", systemImage: "stop.circle") }
            }
            Button { rawAddress = address; panel = .terminal } label: { Label("Raw terminal", systemImage: "terminal") }
            ForEach(shells) { shell in
                Button { rawAddress = shell.address; panel = .terminal } label: { Label("Shell · \(shell.name ?? shell.agent)", systemImage: "apple.terminal") }
            }
            Button { model.togglePin(address) } label: { Label(model.pins.contains(address) ? "Unpin" : "Pin", systemImage: "pin") }
            if let branch { Button { copyToClipboard(branch) } label: { Label("Copy branch", systemImage: "doc.on.doc") } }
            ShareLink(item: [name, branch, pr?.url].compactMap { $0 }.joined(separator: "\n")) { Label("Share", systemImage: "square.and.arrow.up") }
        } label: {
            Label("Session options", systemImage: "ellipsis.circle")
        }
        .accessibilityIdentifier("session-options")
    }
}

// PR, commits, status and the running app servers of the space.
struct SessionPills: View {
    let address: String
    var commits: Commits?
    var pr: Pr?
    var apps: [Terminal]
    @Binding var panel: SessionPanel?
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let pr, let url = URL(string: pr.url) {
                    pill("PR #\(pr.number)", "arrow.triangle.pull") { openURL(url) }
                }
                pill(commits.map { "Commits \($0.commits.count)" } ?? "Commits", "point.3.connected.trianglepath.dotted") { panel = .commits }
                    .accessibilityIdentifier("pill-commits")
                pill("Status", "checklist") { panel = .status }
                    .accessibilityIdentifier("pill-status")
                ForEach(apps) { app in
                    let url = appUrl(app, serverUrl: model.server).flatMap(URL.init(string:))
                    pill("\(app.name ?? "Server") :\(app.listen?.port ?? 0)", "globe") { if let url { openURL(url) } }.disabled(url == nil)
                }
            }
        }
    }

    private func pill(_ title: String, _ symbol: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Label(title, systemImage: symbol).font(.subheadline) }
            .buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
    }
}

// A panel over the session, with Done.
struct PanelSheet<Content: View>: View {
    var title: String
    @ViewBuilder var content: Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(title)
                .inlineTitle()
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
