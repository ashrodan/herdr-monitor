import SwiftUI
import HerdrKit

// One session in the inbox: the space, the status, the name, the task and
// the agent's last sentences. A waiting session shows its question's
// answers as buttons (one single-choice question only).
struct SessionCard: View {
    var session: Session
    var now: Int64
    var stale = false
    var answer: (Int, String) -> Void

    var body: some View {
        let s = session
        HStack(alignment: .top, spacing: 12) {
            SpaceAvatar(letter: s.letter, color: s.spaceColor, size: 34)
                .overlay(alignment: .bottomTrailing) {
                    Dot(color: s.status.color, size: 11).overlay(Circle().stroke(.background, lineWidth: 2)).offset(x: 3, y: 3)
                }
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(s.name).font(.body).fontWeight(s.unread ? .semibold : .medium).lineLimit(1)
                    if s.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.secondary).accessibilityLabel("Pinned") }
                    Spacer(minLength: 4)
                    Text(age(s.timeMs, now: now)).font(.footnote).monospacedDigit().foregroundStyle(.secondary)
                    if s.unread { Dot(color: .accentColor, size: 8).accessibilityLabel("Unread") }
                }
                HStack(spacing: 5) {
                    HarnessMark(agent: s.agent, size: 15)
                    Text(s.status.label).foregroundStyle(s.status.color).fontWeight(.medium)
                    if let task = s.task { Text("· " + task).foregroundStyle(.secondary).lineLimit(1) }
                }
                .font(.footnote)
                if let sent = s.sent {
                    Label("Sent: \(sent)", systemImage: "checkmark").font(.footnote).foregroundStyle(.secondary)
                } else if let snippet = s.snippet {
                    Text(snippet).font(.subheadline).foregroundStyle(.secondary).lineLimit(s.status == .waiting ? 3 : 2)
                }
                if let q = s.staleForMs {
                    Label("Quiet for \(duration(q))", systemImage: "clock").font(.caption).foregroundStyle(.orange)
                }
                if let options = s.ask?.quickOptions() {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(options.enumerated()), id: \.offset) { j, label in
                            Button { answer(j, label) } label: { Text(label).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading) }
                                .buttonStyle(.bordered)
                                .tint(Status.waiting.color)
                                .accessibilityIdentifier("answer-\(s.address)-\(j)")
                        }
                    }
                    .padding(.top, 4)
                }
            }
        }
        .padding(.vertical, 4)
        .opacity(stale ? 0.6 : 1)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session-\(s.address)")
    }
}

// Herdr's spaces in Herdr's order, each with its agent sessions. A space
// with worktrees folds: folded, its row shows the worktrees rolled up (the
// most urgent state, how many, and the commits behind).
struct SpacesList: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Binding var selection: String?
    var now: Int64
    var newSession: (String) -> Void

    var body: some View {
        let live = model.live
        List(selection: $selection) {
            if live.spaces.isEmpty {
                ContentUnavailableView(live.link == .online ? "No spaces" : "Connecting to Herdr…", systemImage: "square.grid.2x2").listRowSeparator(.hidden)
            }
            ForEach(live.spaces.filter { $0.parent == nil }) { head in
                let kids = live.spaces.filter { $0.parent == head.id }
                let folded = model.collapsed.contains(head.id) && !kids.isEmpty
                Section {
                    if !head.virtual { rows(head, live) }
                    if !folded {
                        ForEach(kids) { k in
                            worktreeHeader(k, live)
                            rows(k, live)
                        }
                    }
                } header: {
                    header(head, kids: kids, folded: folded, live)
                }
            }
        }
        .listStyle(.sidebar)
        .accessibilityIdentifier("spaces-list")
    }

    @ViewBuilder private func header(_ s: Space, kids: [Space], folded: Bool, _ live: Live) -> some View {
        let r = rollup(s, live.spaces, live.terminals, model.localSeen)
        HStack(spacing: 8) {
            SpaceAvatar(letter: spaceLetter(s.name), color: s.color, size: 22)
            Text(s.name).font(.headline).foregroundStyle(s.virtual ? .secondary : .primary).lineLimit(1)
            if let b = s.branch { Text(b).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
            Spacer(minLength: 4)
            // Folded: the family's state. Open: only this space's.
            StatusMark(state: folded ? r.state : spaceState(s, live.terminals, model.localSeen))
            let behind = folded ? r.behind : s.behind
            if behind > 0 { Text("↓\(behind)").font(.footnote.weight(.medium)).foregroundStyle(.red) }
            if !kids.isEmpty {
                Button { withAnimation { model.toggleCollapsed(s.id) } } label: {
                    HStack(spacing: 3) {
                        if folded { Text("\(kids.count)").font(.footnote).monospacedDigit() }
                        Image(systemName: "arrow.triangle.branch").font(.caption)
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).rotationEffect(.degrees(folded ? 0 : 90))
                    }
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 4).padding(.leading, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(folded ? "Show \(kids.count) worktrees of \(s.name)" : "Hide the worktrees of \(s.name)")
                .accessibilityIdentifier("fold-\(s.id)")
            }
        }
        .textCase(nil)
        .contextMenu { if !s.virtual { Button { newSession(s.id) } label: { Label("New session here", systemImage: "plus") } } }
    }

    private func worktreeHeader(_ s: Space, _ live: Live) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "arrow.triangle.branch").font(.footnote).foregroundStyle(.secondary).frame(width: 22)
            Text(s.name).font(.subheadline.weight(.semibold)).lineLimit(1)
            if let b = s.branch { Text(b).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
            Spacer(minLength: 4)
            StatusMark(state: spaceState(s, live.terminals, model.localSeen))
            if s.behind > 0 { Text("↓\(s.behind)").font(.footnote.weight(.medium)).foregroundStyle(.red) }
        }
        .padding(.leading, 8)
        .contextMenu { Button { newSession(s.id) } label: { Label("New session here", systemImage: "plus") } }
    }

    @ViewBuilder private func rows(_ s: Space, _ live: Live) -> some View {
        let all = s.tabs.flatMap(\.terminals).compactMap { live.terminals[$0] }.filter { !$0.finished }
        let agents = s.agentAddresses(live.terminals).compactMap { live.terminals[$0] }.filter { !$0.finished }
        let apps = all.filter(\.isApp)
        if agents.isEmpty && apps.isEmpty {
            Text("No agent sessions").font(.footnote).foregroundStyle(.tertiary).padding(.leading, s.parent == nil ? 30 : 38)
        }
        ForEach(agents) { t in
            let w = whereOf(t, live.spaces)
            TerminalRow(terminal: t, name: t.name ?? (w.tabNamed ? w.tabName : nil) ?? harnessName(t.agent), detail: t.msg ?? t.title, mark: t.mark(model.localSeen), now: now)
                .padding(.leading, s.parent == nil ? 0 : 8)
                .tag(t.address)
        }
        ForEach(apps) { t in
            let url = appUrl(t, serverUrl: model.server)
            Button { if let url, let u = URL(string: url) { openURL(u) } } label: {
                HStack(spacing: 10) {
                    Image(systemName: "globe").foregroundStyle(.green).frame(width: 22)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t.name ?? "Server").lineLimit(1)
                        Text(url ?? "port \(t.listen?.port ?? 0) · the machine's address is not known").font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if url != nil { Image(systemName: "arrow.up.right").font(.footnote).foregroundStyle(.tertiary) }
                }
            }
            .buttonStyle(.plain)
            .disabled(url == nil)
            .padding(.leading, s.parent == nil ? 0 : 8)
        }
    }
}

// One terminal: the harness, the name, a second line, the age, the mark.
struct TerminalRow: View {
    var terminal: Terminal
    var name: String
    var detail: String?
    var mark: String?
    var now: Int64

    var body: some View {
        HStack(spacing: 10) {
            HarnessMark(agent: terminal.agent, size: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(name).lineLimit(1)
                if let detail, !detail.isEmpty { Text(detail).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 4)
            Text(age(terminal.changedMs, now: now)).font(.footnote).monospacedDigit().foregroundStyle(.secondary)
            StatusMark(state: mark)
        }
        .accessibilityIdentifier("terminal-\(terminal.address)")
    }
}
