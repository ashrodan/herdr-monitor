import SwiftUI
import HerdrKit

enum HomeTab: String, CaseIterable, Identifiable {
    case sessions = "Sessions", prs = "PRs", spaces = "Spaces"
    var id: String { rawValue }
}

// The list column: an inbox of agent sessions (Needs you, Working, Done,
// Ready to review, Idle), the open pull requests, and the spaces.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var tab = HomeTab.sessions
    @State private var query = ""
    @State private var showIdle = false
    @State private var newSession: NewSessionTarget?
    @State private var settings = false
    @State private var toast: String?
    @State private var now = nowMs()

    var body: some View {
        @Bindable var model = model
        let live = model.live
        let sessions = model.sessions(now: now)
        let counts = counts(sessions)
        VStack(spacing: 0) {
            ConnectionLine(live: live)
            Picker("View", selection: $tab) {
                ForEach(HomeTab.allCases) { t in Text(label(t, counts, live)).tag(t) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16).padding(.vertical, 8)
            switch tab {
            case .sessions: sessionsList(sessions, counts, selection: $model.selected)
            case .prs: prsList(live)
            case .spaces: SpacesList(selection: $model.selected, now: now, newSession: { newSession = NewSessionTarget(space: $0) })
            }
        }
        .navigationTitle(shortHost(model.server))
        .inlineTitle()
        .searchable(text: $query, prompt: "Filter sessions")
        .toolbar {
            ToolbarItem(placement: .trailing) {
                Button { newSession = NewSessionTarget(space: nil) } label: { Label("New session", systemImage: "plus") }
                    .keyboardShortcut("n", modifiers: .command)
                    .accessibilityIdentifier("new-session")
            }
            ToolbarItem(placement: .trailing) {
                Button { settings = true } label: { Label("Settings", systemImage: "gearshape") }
                    .accessibilityIdentifier("settings")
            }
        }
        .sheet(item: $newSession) { target in NewSessionSheet(space: target.space) { model.selected = $0 } }
        .sheet(isPresented: $settings) { SettingsView() }
        .overlay(alignment: .bottom) { ToastView(text: $toast) }
        .task { await model.runDigestLoop() }
        .task {
            // The rows' ages tick.
            while !Task.isCancelled { try? await Task.sleep(nanoseconds: 30_000_000_000); now = nowMs() }
        }
        .onChange(of: model.filter) { _, f in if f != nil { tab = .sessions } }
        .onChange(of: query) { _, q in if !q.isEmpty { tab = .sessions } }
    }

    private func label(_ t: HomeTab, _ c: Counts, _ live: Live) -> String {
        switch t {
        case .sessions: return c.needsYou > 0 ? "Sessions · \(c.needsYou)" : "Sessions"
        case .prs: return live.prs.isEmpty ? "PRs" : "PRs · \(live.prs.count)"
        case .spaces: return "Spaces"
        }
    }

    // ---- Sessions ----

    @ViewBuilder private func sessionsList(_ all: [Session], _ counts: Counts, selection: Binding<String?>) -> some View {
        let shown = all.filter { matches($0, query: query) }
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        let filter = model.filter
        // No List(selection:): the system's selected look turns every text
        // white and hides the status colours. The row is a tap target, and
        // the open session gets a light tint.
        List {
            Section {
                FilterChips(counts: counts, filter: filter) { model.filter = $0 }
                    .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 0))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }
            if shown.isEmpty {
                emptyState(all.isEmpty, searching)
            } else {
                ForEach(Bucket.allCases, id: \.self) { b in
                    let rows = shown.filter { $0.bucket == b }
                    if (filter == nil || filter == b), !rows.isEmpty {
                        // Most sessions are idle: one folded row, unless you
                        // search or ask for them.
                        if b == .idle, filter == nil, !searching, !showIdle {
                            Section {
                                Button { withAnimation { showIdle = true } } label: {
                                    Label("\(rows.count) idle", systemImage: "moon.zzz").foregroundStyle(.secondary)
                                }
                                .accessibilityIdentifier("idle-row")
                            }
                        } else {
                            Section {
                                ForEach(rows) { s in
                                    SessionCard(session: s, now: now, stale: model.live.cachedAt != nil, answer: { j, l in answer(s, j, l) })
                                        .contentShape(Rectangle())
                                        .onTapGesture { selection.wrappedValue = s.address }
                                        .accessibilityAction(named: "Open") { selection.wrappedValue = s.address }
                                        .selectedRow(selection.wrappedValue == s.address)
                                        .contextMenu { menu(s) }
                                        .swipeActions(edge: .leading) {
                                            Button { model.togglePin(s.address) } label: { Label(s.pinned ? "Unpin" : "Pin", systemImage: s.pinned ? "pin.slash" : "pin") }.tint(.indigo)
                                        }
                                }
                            } header: {
                                HStack {
                                    Text(b.title)
                                    Text("\(rows.count)").foregroundStyle(.secondary)
                                    if b == .idle, filter == nil, !searching { Spacer(); Button("Hide") { withAnimation { showIdle = false } }.font(.footnote) }
                                }
                            }
                        }
                    }
                }
                if let filter, counts.of(filter) == 0 {
                    ContentUnavailableView(filter == .needsYou ? "Nobody needs you" : "No \(filter.title.lowercased()) sessions", systemImage: "checkmark.circle")
                        .listRowSeparator(.hidden)
                }
            }
        }
        .sessionListStyle()
        .accessibilityIdentifier("sessions-list")
    }

    @ViewBuilder private func emptyState(_ none: Bool, _ searching: Bool) -> some View {
        Group {
            if searching && !none { ContentUnavailableView.search(text: query) }
            else {
                switch model.live.link {
                case .online: ContentUnavailableView("No sessions", systemImage: "rectangle.stack", description: Text("Start one with the + button, or in Herdr on the Mac."))
                case .offline: ContentUnavailableView("No link to the server", systemImage: "wifi.slash", description: Text(model.live.error ?? "Check Tailscale and the server."))
                case .connecting: ProgressView("Connecting…").frame(maxWidth: .infinity).padding(.vertical, 40)
                }
            }
        }
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
    }

    @ViewBuilder private func menu(_ s: Session) -> some View {
        Button { model.togglePin(s.address) } label: { Label(s.pinned ? "Unpin" : "Pin", systemImage: s.pinned ? "pin.slash" : "pin") }
        if s.status == .working || s.status == .waiting {
            Button(role: .destructive) { stop(s) } label: { Label("Stop", systemImage: "stop.circle") }
        }
        if let b = s.branch { Button { copyToClipboard(b); toast = "Branch copied." } label: { Label("Copy branch", systemImage: "doc.on.doc") } }
        if let pr = s.pr, let url = URL(string: pr.pr.url) { Button { openURL(url) } label: { Label("Open PR #\(pr.pr.number)", systemImage: "arrow.up.right.square") } }
    }

    private func answer(_ s: Session, _ option: Int, _ label: String) {
        guard let ask = s.ask else { return }
        let api = model.api
        Task {
            do {
                try await api.answer(s.address, shape: ask.shape, q: 0, option: option)
                model.sent[s.address] = SentMark(label: label, atMs: nowMs())
            } catch { toast = "Not sent: \(describe(error))" }
        }
    }

    private func stop(_ s: Session) {
        let api = model.api
        Task {
            do { try await api.interrupt(s.address); toast = "Stop key sent to \(s.name)." } catch { toast = "Not sent: \(describe(error))" }
        }
    }

    // ---- Pull requests ----

    @ViewBuilder private func prsList(_ live: Live) -> some View {
        List {
            if !live.prsLoaded {
                ProgressView("Loading pull requests…").frame(maxWidth: .infinity).listRowSeparator(.hidden)
            } else if live.prs.isEmpty {
                ContentUnavailableView("No open pull requests", systemImage: "arrow.triangle.pull").listRowSeparator(.hidden)
            }
            ForEach(live.prs) { p in
                Button { if let u = URL(string: p.pr.url) { openURL(u) } } label: { PrRow(pr: p) }
                    .buttonStyle(.plain)
            }
        }
        .listStyle(.plain)
        .refreshable { await model.store.refreshPrs(fresh: true) }
        .accessibilityIdentifier("prs-list")
    }
}

struct NewSessionTarget: Identifiable {
    var space: String?
    var id: String { space ?? "" }
}

extension View {
    // Inset grouped on iOS (cards on a grouped background). macOS has no
    // such style, and only builds this to check the code.
    // The open session's row: a light tint, so the status colours and the
    // answer buttons stay readable (the system's selection fills the row
    // with the accent colour).
    func selectedRow(_ on: Bool) -> some View {
        listRowBackground(on ? Color.accentColor.opacity(0.16) : Color.rowBackground)
    }

    @ViewBuilder func sessionListStyle() -> some View {
        #if os(iOS)
        listStyle(.insetGrouped)
        #else
        listStyle(.sidebar)
        #endif
    }
}

// The count of each section; a tap shows only that section.
struct FilterChips: View {
    var counts: Counts
    var filter: Bucket?
    var pick: (Bucket?) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("All", counts.all, nil, .accentColor)
                chip("Needs you", counts.needsYou, .needsYou, Status.waiting.color)
                chip("Working", counts.working, .working, Status.working.color)
                chip("Done", counts.done, .done, Status.done.color)
                chip("Ready", counts.ready, .ready, Status.ready.color)
                chip("Idle", counts.idle, .idle, Status.idle.color)
            }
            .padding(.trailing, 12)
        }
    }

    private func chip(_ title: String, _ n: Int, _ b: Bucket?, _ color: Color) -> some View {
        let on = filter == b
        return Button { pick(on ? nil : b) } label: {
            HStack(spacing: 5) {
                Text(title)
                Text("\(n)").monospacedDigit().fontWeight(.semibold).foregroundStyle(on ? Color.white : (n > 0 ? color : Color.secondary))
            }
            .font(.subheadline)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(on ? AnyShapeStyle(color) : AnyShapeStyle(Color.card), in: Capsule())
            .foregroundStyle(on ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title), \(n)")
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("chip-\(title)")
    }
}

struct PrRow: View {
    var pr: OverviewPr
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Dot(color: pr.fail > 0 ? .red : pr.pending > 0 ? .orange : (Color(hex: pr.color) ?? .gray), size: 9)
            VStack(alignment: .leading, spacing: 2) {
                Text("#\(pr.pr.number) \(pr.pr.title)").fontWeight(.medium).lineLimit(2)
                Text(detail).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "arrow.up.right").font(.footnote).foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
    private var detail: String {
        let review = pr.pr.isDraft ? "draft" : pr.pr.reviewDecision?.lowercased().replacingOccurrences(of: "_", with: " ")
        let checks = pr.fail > 0 ? "\(pr.fail) failed" : pr.pending > 0 ? "\(pr.pending) pending" : nil
        return [pr.spaceName, pr.pr.headRefName.isEmpty ? nil : pr.pr.headRefName, review, checks].compactMap { $0 }.joined(separator: " · ")
    }
}

// A short message at the bottom that goes away by itself.
struct ToastView: View {
    @Binding var text: String?
    var body: some View {
        if let t = text {
            Text(t).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .shadow(radius: 8, y: 2)
                .padding(.bottom, 16)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: t) { try? await Task.sleep(nanoseconds: 2_500_000_000); withAnimation { text = nil } }
                .accessibilityIdentifier("toast")
        }
    }
}
