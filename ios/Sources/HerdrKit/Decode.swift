import Foundation

// Server JSON -> the model. The rules that matter live in Model.swift.
public enum Decode {
    public static func errorOf(_ data: Data) -> String? { JSON.obj(data)?.str("error") }

    public static func terminal(_ o: Obj) -> Terminal? {
        guard let address = o.str("address") else { return nil }
        let st = o.obj("status")
        var listen: Listen?
        if let l = o.obj("listen"), let port = l.int("port"), port > 0 { listen = Listen(scheme: l.text("scheme", "http"), port: port, host: l.str("host")) }
        return Terminal(
            address: address, name: Glyphs.label(o.str("name")), agent: o.text("agent", "?"), cwd: o.str("cwd"), state: o.text("state", "live"),
            title: Glyphs.label(o.str("title")), where: Glyphs.label(o.str("where")), reported: o.bool("reported"),
            status: st?.str("state"), kind: st?.str("kind"), msg: Glyphs.label(st?.str("msg")), exitCode: o.int("exit_code"),
            parent: o.str("parent"), space: o.str("space"), tab: o.str("tab"), changedMs: o.int64("changed_ms"), seenMs: o.int64("seen_ms"), listen: listen)
    }

    public static func terminals(_ a: [Obj]) -> [String: Terminal] {
        var out: [String: Terminal] = [:]
        for o in a { if let t = terminal(o) { out[t.address] = t } }
        return out
    }

    public static func spaces(_ a: [Obj]) -> [Space] {
        a.compactMap { s in
            guard let id = s.str("id") else { return nil }
            return Space(
                id: id, name: Glyphs.label(s.text("name")) ?? "", color: s.str("color"), repo: s.str("repo"), branch: s.str("branch"),
                worktree: s.bool("worktree"), virtual: s.bool("virtual"),
                tabs: s.objs("tabs").compactMap { t in
                    guard let tid = t.str("id") else { return nil }
                    return SpaceTab(id: tid, name: Glyphs.label(t.text("name")) ?? "", named: t.bool("named"), terminals: t.strings("terminals"), tree: pane(t.obj("tree")))
                },
                parent: s.str("parent"), behind: s.int("behind") ?? 0)
        }
    }

    // {dir, w, kids} or {terminal, w} (lib/sidebar.js paneTree).
    public static func pane(_ o: Obj?) -> Pane? {
        guard let o else { return nil }
        let raw = o.double("w") ?? 1
        let w = raw > 0 ? raw : 1
        if let t = o.str("terminal") { return .leaf(terminal: t, w: w) }
        let kids = o.objs("kids").compactMap { pane($0) }
        switch kids.count {
        case 0: return nil
        case 1: return kids[0]
        default: return .split(row: o.str("dir") != "column", kids: kids, w: w)
        }
    }

    public static func worktrees(_ data: Data) -> [Worktree] {
        (JSON.obj(data)?.objs("worktrees") ?? []).compactMap { w in
            guard let path = w.str("path") else { return nil }
            return Worktree(path: path, name: w.text("name"), branch: w.str("branch"), space: w.str("space"))
        }
    }

    public static func started(_ data: Data) -> Started? {
        guard let o = JSON.obj(data), let a = o.str("address") else { return nil }
        return Started(address: a, tab: o.str("tab"), placeError: o.str("placeError"))
    }

    public static func pr(_ o: Obj?) -> Pr? {
        guard let o else { return nil }
        return Pr(number: o.int("number") ?? 0, title: o.text("title"), url: o.text("url"), isDraft: o.bool("isDraft"),
                  headRefName: o.text("headRefName"), reviewDecision: o.str("reviewDecision").flatMap { $0.isEmpty ? nil : $0 }, updatedAt: o.str("updatedAt"))
    }

    public static func overview(_ data: Data) -> [OverviewPr]? {
        guard let o = JSON.obj(data) else { return nil }
        return o.objs("prs").compactMap { p in
            guard let pr = pr(p.obj("pr")) else { return nil }
            let counts = p.obj("counts")
            return OverviewPr(space: p.text("space"), spaceName: p.text("spaceName"), color: p.str("color"), branch: p.str("branch"), pr: pr,
                              readiness: p.obj("readiness")?.str("state"), fail: counts?.int("fail") ?? 0, pending: counts?.int("pending") ?? 0)
        }
    }

    public static func herdr(_ o: Obj?) -> HerdrInfo? {
        guard let o else { return nil }
        let ui = o.obj("ui"), tui = o.obj("tui")
        return HerdrInfo(uiVersion: ui?.str("version"), uiRunning: ui?.bool("running") ?? false, tuiVersion: tui?.str("version"),
                         tuiRunning: tui?.bool("running") ?? false, hc: o.str("hc"))
    }

    public static func isHealth(_ data: Data) -> Bool {
        guard let o = JSON.obj(data) else { return false }
        return o.bool("ok") && o["socket"] != nil && o["terminals"] != nil
    }

    public static func discover(_ data: Data) -> [ServerInfo] {
        (JSON.obj(data)?.objs("servers") ?? []).compactMap { s in
            guard let url = s.str("url") else { return nil }
            return ServerInfo(url: url, name: s.text("name"), os: s.str("os"), isSelf: s.bool("self"), herdr: herdr(s.obj("herdr")), older: s.bool("older"))
        }
    }

    public static func readText(_ data: Data) -> String { Glyphs.mono(JSON.obj(data)?.text("text") ?? "") }
    public static func uploadPath(_ data: Data) -> String { JSON.obj(data)?.text("path") ?? "" }

    // nil when the server says the version did not change.
    public static func chat(_ data: Data) -> Chat? {
        guard let o = JSON.obj(data), !o.bool("unchanged") else { return nil }
        return Chat(available: o.bool("available"), reason: o.str("reason"), version: o.str("version"), truncated: o.bool("truncated"),
                    messages: o.objs("messages").map { m in Message(id: m.text("id"), role: m.text("role"), at: m.str("at"), parts: m.objs("parts").compactMap(part)) })
    }

    // A result is {text, error, truncated} (lib/transcript.js), or a string.
    private static func result(_ o: Obj) -> ToolResult? {
        if let r = o.obj("result") { return ToolResult(text: Glyphs.mono(r.text("text")), error: r.bool("error"), truncated: r.bool("truncated")) }
        if let s = o.str("result") { return ToolResult(text: s) }
        return nil
    }

    private static func part(_ p: Obj) -> Part? {
        switch p.text("kind") {
        case "text": return .text(Glyphs.prose(p.text("text")))
        case "thinking": return .thinking(Glyphs.prose(p.text("text")))
        case "tool": return .tool(Tool(name: p.text("name"), summary: Glyphs.label(p.text("summary")) ?? "", result: result(p)))
        case "question":
            let r = result(p)
            return .ask(Ask(id: p.text("id"), questions: p.objs("questions").map { q in
                Question(header: Glyphs.label(q.text("header")) ?? "", question: Glyphs.prose(q.text("question")), multi: q.bool("multi"),
                         options: q.objs("options").map { AskOption(label: Glyphs.label($0.text("label")) ?? "", description: Glyphs.label($0.text("description")) ?? "") })
            }, answered: r != nil, answer: r?.text))
        default: return nil
        }
    }

    public static func commits(_ data: Data) -> Commits? {
        guard let o = JSON.obj(data) else { return nil }
        return Commits(repo: o.str("repo"), reason: o.str("reason") ?? o.str("error"), branch: o.str("branch"), unpushed: o.int("unpushed"), dirty: o.int("dirty"),
                       pr: pr(o.obj("pr")),
                       commits: o.objs("commits").map { Commit(sha: $0.text("sha"), author: $0.text("author"), date: $0.text("date"), subject: $0.text("subject"), local: $0.bool("local")) })
    }

    public static func commitDetail(_ data: Data) -> CommitDetail? {
        guard let o = JSON.obj(data) else { return nil }
        return CommitDetail(sha: o.text("sha"), author: o.text("author"), date: o.text("date"), message: o.text("message"),
                            files: o.objs("files").map { CommitFile(file: $0.text("file"), additions: $0.int("additions"), deletions: $0.int("deletions")) })
    }

    public static func prStatus(_ data: Data) -> PrStatus? {
        guard let o = JSON.obj(data) else { return nil }
        let rd = o.obj("readiness")
        return PrStatus(repo: o.str("repo"), reason: o.str("reason") ?? o.str("error"), branch: o.str("branch"), pr: pr(o.obj("pr")),
                        readiness: rd?.str("state"), reasons: rd?.strings("reasons") ?? [],
                        checks: o.objs("checks").map { Check(name: $0.text("name"), bucket: $0.text("bucket"), url: $0.str("url")) })
    }
}
