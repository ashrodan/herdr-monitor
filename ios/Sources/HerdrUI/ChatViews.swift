import SwiftUI
import HerdrKit

// The conversation: prose first, each run of thinking and tool calls folded
// into one activity card. It opens at the end and stays there as new
// messages come in.
struct ChatList: View {
    let address: String
    var chat: Chat
    var items: [Item]
    var agent: String
    var blocked: Bool
    var working: Bool
    var queued: [OutItem]

    var body: some View {
        let waitingOn = blocked ? lastUnansweredAsk(chat.messages) : nil
        let running = working ? runningTool(items) : nil
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if chat.truncated { SystemChip(text: "Older messages are not shown (long transcript).") }
                    if items.isEmpty { SystemChip(text: "No messages yet.") }
                    ForEach(items) { item in
                        switch item {
                        case .prose(_, let text, let who): ProseView(text: text, who: who, agent: agent)
                        case .user(_, let text): UserBubble(text: text)
                        case .system(_, let text): SystemChip(text: text)
                        case .activity(let key, let steps): ActivityCard(id: key, steps: steps)
                        case .question(_, let ask): AskCard(address: address, ask: ask, live: ask == waitingOn)
                        }
                    }
                    if working { LiveStep(tool: running) }
                    QueuedReplies(items: queued)
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(.horizontal, 16).padding(.vertical, 14)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: items.count + queued.count) { _, _ in withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("end", anchor: .bottom) } }
            .accessibilityIdentifier("chat")
        }
    }
}

struct ProseView: View {
    var text: String
    var who: Bool
    var agent: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if who {
                HStack(spacing: 6) {
                    HarnessMark(agent: agent, size: 18)
                    Text(harnessName(agent)).font(.footnote.weight(.medium)).foregroundStyle(.secondary)
                }
            }
            MarkdownView(text).textSelection(.enabled)
        }
    }
}

// Your text: a bubble on the right, at most about 85% wide.
struct UserBubble: View {
    var text: String
    var faded = false
    var body: some View {
        HStack {
            Spacer(minLength: 40)
            MarkdownView(text, keepBreaks: true)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .foregroundStyle(.white)
                .background(Color.accentColor.opacity(faded ? 0.55 : 1), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .frame(maxWidth: 560, alignment: .trailing)
        }
    }
}

// A system line (a background job that finished, and so on).
struct SystemChip: View {
    var text: String
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            .padding(.horizontal, 12).padding(.vertical, 5)
            .background(Color.card, in: Capsule())
            .frame(maxWidth: .infinity)
    }
}

// One run of thinking and tool calls. Closed: the tool counts ("6 bash ·
// 1 read") and how many failed. Open: one step per tool or thought; a tap
// on a tool shows its output.
struct ActivityCard: View {
    let id: String
    var steps: [Part]
    @State private var open = false

    var body: some View {
        let tools = toolsOf(steps)
        let failed = tools.filter { $0.result?.error == true }.count
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation(.snappy(duration: 0.2)) { open.toggle() } } label: {
                HStack(spacing: 6) {
                    Image(systemName: "wrench.and.screwdriver").font(.footnote).foregroundStyle(.secondary)
                    Text(tools.isEmpty ? "Thinking" : toolCounts(tools)).foregroundStyle(.secondary).lineLimit(1)
                    if failed > 0 { Text("· \(failed) failed").foregroundStyle(.red) }
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(.secondary).rotationEffect(.degrees(open ? 180 : 0))
                }
                .font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel((tools.isEmpty ? "Thinking" : toolCounts(tools)) + (open ? ", fold" : ", unfold"))
            .accessibilityIdentifier("activity-\(id)")
            if open {
                Divider()
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { _, s in
                        switch s {
                        case .tool(let t): ToolStep(tool: t)
                        case .thinking(let text):
                            HStack(alignment: .top, spacing: 8) {
                                Image(systemName: "brain").font(.footnote).foregroundStyle(.secondary).frame(width: 18)
                                Text(text).font(.footnote).italic().foregroundStyle(.secondary).lineLimit(4)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 6)
                        default: EmptyView()
                        }
                    }
                }
                .padding(.vertical, 6)
            }
        }
        .background(Color.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct ToolStep: View {
    var tool: Tool
    @State private var open = false

    var body: some View {
        let failed = tool.result?.error == true
        VStack(alignment: .leading, spacing: 4) {
            Button { open.toggle() } label: {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: symbol(failed)).font(.footnote).foregroundStyle(failed ? .red : .secondary).frame(width: 18)
                    (Text(tool.name).fontWeight(.semibold).foregroundStyle(failed ? Color.red : Color.primary)
                     + Text(tool.summary.isEmpty ? "" : "  " + tool.summary).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary))
                        .font(.footnote).lineLimit(3).multilineTextAlignment(.leading)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(tool.result == nil)
            if open, let r = tool.result {
                ScrollView {
                    Text((r.text.isEmpty ? "(no output)" : r.text) + (r.truncated ? "\n… (cut)" : ""))
                        .font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                }
                .frame(maxHeight: 300)
                .background(Color.codeBackground, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
    }

    private func symbol(_ failed: Bool) -> String {
        if failed { return "exclamationmark.triangle.fill" }
        let n = tool.name.lowercased()
        if ["grep", "glob", "search", "find"].contains(where: n.contains) { return "magnifyingglass" }
        if ["edit", "write", "patch"].contains(where: n.contains) { return "pencil" }
        if ["read", "fetch", "ls"].contains(where: n.contains) { return "doc.text" }
        if ["bash", "shell", "exec", "run"].contains(where: n.contains) { return "terminal" }
        return "wrench"
    }
}

// The live step, last while the agent works.
struct LiveStep: View {
    var tool: Tool?
    var body: some View {
        HStack(spacing: 12) {
            ProgressView().controlSize(.small)
            VStack(alignment: .leading, spacing: 2) {
                Text(tool.map { "Running \($0.name)" } ?? "Working").font(.subheadline.weight(.semibold))
                if let s = tool?.summary, !s.isEmpty { Text(s).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(Color.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("live-step")
    }
}

// A question the agent asked. While it waits on it (live), tap an option:
// the server presses the keys in the form. One single-choice question: a
// tap answers it; otherwise each tap fills the form and Submit sends it.
struct AskCard: View {
    let address: String
    var ask: Ask
    var live: Bool
    @Environment(AppModel.self) private var model
    @State private var picked = Set<String>()
    @State private var note: String?
    @State private var busy = false
    @State private var ok = 0
    @State private var failed = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(ask.questions.enumerated()), id: \.offset) { i, q in
                VStack(alignment: .leading, spacing: 6) {
                    Text((q.header.isEmpty ? "Question" : q.header) + (q.multi ? " · pick any" : "")).font(.caption.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
                    Text(q.question).fontWeight(.medium)
                    ForEach(Array(q.options.enumerated()), id: \.offset) { j, o in
                        let key = "\(i).\(j)", on = picked.contains(key)
                        Button { pick(i, j, q.multi, key, on) } label: {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Image(systemName: on ? "checkmark.circle.fill" : (q.multi ? "square" : "circle")).foregroundStyle(on ? Color.orange : Color.secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(o.label).fontWeight(.medium)
                                    if !o.description.isEmpty { Text(o.description).font(.footnote).foregroundStyle(.secondary) }
                                }
                                Spacer(minLength: 0)
                            }
                            .multilineTextAlignment(.leading)
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .background(Color.card, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!live || busy)
                        .accessibilityIdentifier("option-\(i)-\(j)")
                    }
                }
            }
            if live, ask.needsSubmit {
                Button("Submit") { act("Submitted.") { try await model.api.submit(address, shape: ask.shape) } }
                    .buttonStyle(.borderedProminent).tint(.orange).disabled(busy)
            }
            if ask.answered {
                Text("Answered: " + String((ask.answer ?? "").components(separatedBy: "\n").first ?? "").prefix(200)).font(.footnote).foregroundStyle(.secondary)
            } else if let note {
                Text(note).font(.footnote).foregroundStyle(note.hasPrefix("Not") || note.hasPrefix("The form") ? .red : .secondary)
            } else if live {
                Text(ask.needsSubmit ? "Tap your answers, then Submit. To write your own, type it below." : "Tap an answer. To write your own, type it below.")
                    .font(.footnote).foregroundStyle(.orange)
            }
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(live ? Color.orange : Color.primary.opacity(0.15), lineWidth: live ? 1.5 : 1))
        .sensoryFeedback(.success, trigger: ok)
        .sensoryFeedback(.error, trigger: failed)
        // A container: the options keep their own identifiers and labels.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("ask-\(ask.id)")
    }

    private func pick(_ i: Int, _ j: Int, _ multi: Bool, _ key: String, _ on: Bool) {
        act(ask.needsSubmit ? "Picked. Submit when ready." : "Answer sent.") {
            try await model.api.answer(address, shape: ask.shape, q: i, option: j)
            if multi { if on { picked.remove(key) } else { picked.insert(key) } }
            else { picked = picked.filter { !$0.hasPrefix("\(i).") }; picked.insert(key) }
        }
    }

    private func act(_ done: String, _ body: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true; note = nil
        Task {
            do { try await body(); ok += 1; note = done } catch {
                failed += 1
                let why = describe(error)
                note = why == "the question form is not on screen" ? "The form is not on screen. Open Raw terminal to check." : "Not sent: \(why)"
            }
            busy = false
        }
    }
}

// Replies in the outbox for this agent: your bubble, marked Queued, with
// the last error and a control to delete it before it goes.
struct QueuedReplies: View {
    var items: [OutItem]
    @Environment(AppModel.self) private var model

    var body: some View {
        ForEach(items) { item in
            VStack(alignment: .trailing, spacing: 4) {
                UserBubble(text: item.text, faded: true)
                HStack(spacing: 10) {
                    Text(label(item) + (item.lastError.map { " · \($0)" } ?? "")).font(.caption).foregroundStyle(item.state == .waiting ? Color.secondary : Color.red).lineLimit(1)
                    if item.state != .waiting {
                        Button("Send again") { model.outbox.retry(item.id); Task { await model.flushOutbox() } }.font(.caption)
                    }
                    Button(role: .destructive) { model.outbox.remove(item.id) } label: { Label("Delete queued reply", systemImage: "trash").labelStyle(.iconOnly) }.font(.caption)
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .accessibilityIdentifier("queued-\(item.id)")
        }
    }

    private func label(_ i: OutItem) -> String {
        switch i.state {
        case .failed: return "Not sent"
        case .unconfirmed: return "Not confirmed, look in the chat"
        case .waiting: return "Queued"
        }
    }
}
