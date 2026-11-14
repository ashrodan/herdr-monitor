import SwiftUI
import HerdrKit

// The terminal's screen and its last lines, read again every 2 s.
struct RawTerminalView: View {
    let address: String
    @Environment(AppModel.self) private var model
    @State private var text: String?
    @State private var error: String?

    var body: some View {
        Group {
            if let text {
                ScrollView([.vertical, .horizontal]) {
                    Text(text.trimmingCharacters(in: .newlines))
                        .font(.system(size: 11, design: .monospaced))
                        .textSelection(.enabled)
                        .fixedSize()
                        .padding(8)
                }
                .defaultScrollAnchor(.bottomLeading)
            } else if let error {
                ContentUnavailableView("Could not read the screen", systemImage: "exclamationmark.triangle", description: Text(error))
            } else {
                ProgressView("Reading the screen…")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: address) {
            let api = model.api
            while !Task.isCancelled {
                do { text = try await api.read(address, lines: 300); error = nil } catch { self.error = describe(error) }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
        .accessibilityIdentifier("raw-terminal")
    }
}

// The PR for the agent's branch, or the commits since the default branch.
// A tap on a commit shows its message and files.
struct CommitsView: View {
    let address: String
    var initial: Commits?
    @Environment(AppModel.self) private var model
    @State private var data: Commits?
    @State private var error: String?
    @State private var detail: CommitDetail?

    var body: some View {
        List {
            if let d = data {
                Section {
                    let bits = [d.unpushed.flatMap { $0 > 0 ? "\($0) not pushed" : nil }, d.dirty.flatMap { $0 > 0 ? "\($0) changed files" : nil }].compactMap { $0 }
                    LabeledContent([d.repo, d.branch].compactMap { $0 }.joined(separator: " · ")) {
                        if !bits.isEmpty { Text(bits.joined(separator: " · ")).foregroundStyle(.orange) }
                    }
                    if let pr = d.pr { Text("PR #\(pr.number): \(pr.title)").font(.subheadline) }
                    if let reason = d.reason { Text(reason).foregroundStyle(.secondary) }
                }
                Section {
                    ForEach(d.commits) { c in
                        Button { open(c) } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(c.subject).fontWeight(.medium).lineLimit(2)
                                Text("\(c.sha.prefix(7)) · \(c.author) · \(c.date.prefix(10))" + (c.local ? " · not pushed" : "")).font(.caption).foregroundStyle(.secondary)
                                if let dd = detail, dd.sha.hasPrefix(c.sha.prefix(7)) {
                                    Text(dd.message).font(.footnote).padding(.top, 4)
                                    ForEach(Array(dd.files.enumerated()), id: \.offset) { _, f in
                                        Text("\(f.file)  +\(f.additions.map(String.init) ?? "?") −\(f.deletions.map(String.init) ?? "?")").font(.system(.caption, design: .monospaced))
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else if let error {
                Label("Could not read commits: \(error)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .refreshable { await load(fresh: true) }
        .task { data = initial; await load(fresh: false) }
        .accessibilityIdentifier("commits")
    }

    private func load(fresh: Bool) async {
        do { data = try await model.api.commits(address, fresh: fresh); error = nil } catch { self.error = describe(error) }
    }

    private func open(_ c: Commit) {
        if let d = detail, d.sha.hasPrefix(c.sha.prefix(7)) { detail = nil; return }
        Task { detail = try? await model.api.commitDetail(address, sha: c.sha) }
    }
}

// The branch's PR: whether it can merge and why not, and its checks.
struct StatusView: View {
    let address: String
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var data: PrStatus?
    @State private var error: String?

    var body: some View {
        List {
            if let d = data {
                if let pr = d.pr {
                    Section(d.branch ?? "") {
                        Button { if let u = URL(string: pr.url) { openURL(u) } } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("#\(pr.number) \(pr.title)").fontWeight(.medium)
                                Text([pr.isDraft ? "draft" : nil, d.readiness, pr.reviewDecision?.lowercased().replacingOccurrences(of: "_", with: " ")].compactMap { $0 }.joined(separator: " · "))
                                    .font(.footnote).foregroundStyle(d.readiness == "ready" ? .green : .secondary)
                                ForEach(d.reasons, id: \.self) { Text("• \($0)").font(.footnote) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    Section("Checks") {
                        if d.checks.isEmpty { Text("No checks.").foregroundStyle(.secondary) }
                        ForEach(Array(d.checks.enumerated()), id: \.offset) { _, c in
                            Button { if let u = c.url.flatMap(URL.init(string:)) { openURL(u) } } label: {
                                HStack(spacing: 10) {
                                    Dot(color: c.bucket == "pass" ? .green : c.bucket == "fail" ? .red : c.bucket == "pending" ? .orange : .gray, size: 9)
                                    Text(c.name)
                                    Spacer()
                                    Text(c.bucket).font(.footnote).foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } else {
                    Text(d.reason ?? "No PR for this branch.").foregroundStyle(.secondary)
                }
            } else if let error {
                Label("Could not read the status: \(error)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .refreshable { await load(fresh: true) }
        .task { await load(fresh: false) }
        .accessibilityIdentifier("status")
    }

    private func load(fresh: Bool) async {
        do { data = try await model.api.prStatus(address, fresh: fresh); error = nil } catch { self.error = describe(error) }
    }
}

// Where you are: the space and its worktrees, each tab with its panes where
// Herdr draws them. A tap on a pane opens that session.
struct SpaceMapSheet: View {
    let address: String
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var worktrees: [Worktree] = []
    @State private var newSession: NewSessionTarget?

    var body: some View {
        let live = model.live
        let place = placeOf(live.terminals[address], address: address, live.spaces)
        let family = spaceFamily(live.spaces, id: place?.space.id)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if family.isEmpty { ContentUnavailableView("This session is in no space", systemImage: "rectangle.split.2x1") }
                    ForEach(family) { s in
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 8) {
                                if s.parent == nil { SpaceAvatar(letter: spaceLetter(s.name), color: s.color, size: 22) } else { Image(systemName: "arrow.triangle.branch").foregroundStyle(.secondary) }
                                Text(s.name).font(.headline)
                                if let b = s.branch { Text(b).font(.footnote).foregroundStyle(.secondary) }
                                Spacer()
                                if !s.virtual { Button { newSession = NewSessionTarget(space: s.id) } label: { Label("New tab", systemImage: "plus") }.font(.subheadline) }
                            }
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 220), spacing: 12)], spacing: 12) {
                                ForEach(s.tabs) { tab in TabMap(tab: tab, here: address, live: live, localSeen: model.localSeen) { model.selected = $0; dismiss() } }
                            }
                        }
                    }
                    let rest = worktreesWithoutSpace(worktrees, family: family)
                    if !rest.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Worktrees with no space in Herdr").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                            ForEach(rest) { w in
                                HStack { Image(systemName: "arrow.triangle.branch").foregroundStyle(.secondary); Text(w.name); if let b = w.branch { Text(b).font(.footnote).foregroundStyle(.secondary) } }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Space map")
            .inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(item: $newSession) { t in NewSessionSheet(space: t.space) { model.selected = $0; dismiss() } }
            .task { if let id = place?.space.id { worktrees = (try? await model.api.worktrees(space: id)) ?? [] } }
        }
        .accessibilityIdentifier("space-map-sheet")
    }
}

// One tab in miniature: its panes as rectangles, with the harness mark, the
// name and the state of each pane. Your pane is filled.
struct TabMap: View {
    var tab: SpaceTab
    var here: String
    var live: Live
    var localSeen: [String: Int64]
    var open: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(tab.named ? tab.name : "Tab \(tab.name)").font(.caption.weight(.medium)).foregroundStyle(.secondary)
            GeometryReader { geo in
                ZStack(alignment: .topLeading) {
                    ForEach(tab.panes) { p in
                        let t = live.terminals[p.terminal]
                        let mine = p.terminal == here
                        Button { open(p.terminal) } label: {
                            VStack(spacing: 3) {
                                HStack(spacing: 4) {
                                    if let t { HarnessMark(agent: t.agent, size: 16) }
                                    StatusMark(state: t?.mark(localSeen), size: 7)
                                }
                                Text(t?.name ?? t.map { harnessName($0.agent) } ?? p.terminal).font(.caption2).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(mine ? Color.accentColor.opacity(0.25) : Color.card, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(mine ? Color.accentColor : Color.primary.opacity(0.12)))
                            .padding(2)
                        }
                        .buttonStyle(.plain)
                        .disabled(t == nil || mine || t?.isAgent != true)
                        .frame(width: geo.size.width * p.w, height: geo.size.height * p.h)
                        .offset(x: geo.size.width * p.x, y: geo.size.height * p.y)
                        .accessibilityLabel("\(t?.name ?? p.terminal)" + (mine ? ", this session" : ""))
                    }
                }
            }
            .aspectRatio(16 / 10, contentMode: .fit)
        }
    }
}
