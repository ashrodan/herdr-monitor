import Foundation

// A server in memory, with made-up sessions. The UI tests and the
// screenshots use it (launch argument -demo), and so do the unit tests: it
// speaks the same JSON as the real server, so the whole path from the
// bytes to the screen is exercised.
public final class DemoTransport: Transport, @unchecked Sendable {
    public static let server = "https://demo-mac.example.ts.net:7400"

    private let lock = NSLock()
    private var _posts: [(path: String, body: Data)] = []
    // Every POST the app sent, for tests.
    public var posts: [(path: String, body: Data)] { lock.lock(); defer { lock.unlock() }; return _posts }
    // When set, every request fails this way (tests of the offline paths).
    public var failure: ApiError? {
        get { lock.lock(); defer { lock.unlock() }; return _failure }
        set { lock.lock(); _failure = newValue; lock.unlock() }
    }
    private var _failure: ApiError?
    private let now: Int64

    public init(now: Int64 = nowMs()) { self.now = now }

    private func ago(_ minutes: Double) -> Int64 { now - Int64(minutes * 60_000) }
    private func iso(_ minutes: Double) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date(timeIntervalSince1970: Double(ago(minutes)) / 1000))
    }

    private func terminal(_ address: String, _ agent: String, _ state: String?, space: String, tab: String, name: String? = nil, kind: String? = nil,
                          msg: String? = nil, minutes: Double = 5, cwd: String, listen: Int? = nil) -> Obj {
        var t: Obj = ["address": address, "agent": agent, "cwd": cwd, "state": "live", "reported": state != nil, "space": space, "tab": tab,
                      "changed_ms": ago(minutes), "title": name ?? agent]
        if let name { t["name"] = name }
        if let state { t["status"] = ["state": state, "kind": kind as Any, "msg": msg as Any] as Obj }
        if let listen { t["listen"] = ["scheme": "http", "port": listen] as Obj }
        return t
    }

    public var snapshot: Obj {
        let terminals: [Obj] = [
            terminal("t1", "claude-code", "blocked", space: "sp-shop", tab: "tb1", kind: "question", msg: "Which checkout flow should the tests cover?", minutes: 2, cwd: "/Users/demo/dev/shop"),
            terminal("t2", "omp", "working", space: "sp-shop-wt", tab: "tb3", msg: "Running the test suite", minutes: 1, cwd: "/Users/demo/dev/shop/.worktrees/fix-cart"),
            terminal("t3", "claude-code", "done", space: "sp-api", tab: "tb4", msg: "Rate limiter merged into the gateway", minutes: 14, cwd: "/Users/demo/dev/api"),
            terminal("t4", "codex", "error", space: "sp-api", tab: "tb5", name: "migrate-db", msg: "Migration 0042 failed: column exists", minutes: 22, cwd: "/Users/demo/dev/api"),
            terminal("t5", "claude-code", "idle", space: "sp-docs", tab: "tb6", minutes: 190, cwd: "/Users/demo/dev/docs"),
            terminal("t6", "zsh", nil, space: "sp-shop", tab: "tb1", minutes: 300, cwd: "/Users/demo/dev/shop"),
            terminal("t7", "node", nil, space: "sp-shop", tab: "tb2", name: "dev server", minutes: 300, cwd: "/Users/demo/dev/shop", listen: 5173),
        ]
        func leaf(_ t: String, _ w: Double = 1) -> Obj { ["terminal": t, "w": w] }
        let spaces: [Obj] = [
            ["id": "sp-shop", "name": "shop", "color": "#5f9cf0", "repo": "/Users/demo/dev/shop", "branch": NSNull(), "behind": 0, "worktree": false, "parent": NSNull(),
             "tabs": [["id": "tb1", "name": "checkout", "named": true, "terminals": ["t1", "t6"], "tree": ["dir": "row", "w": 1, "kids": [leaf("t1", 0.6), leaf("t6", 0.4)]] as Obj] as Obj,
                      ["id": "tb2", "name": "2", "named": false, "terminals": ["t7"], "tree": leaf("t7")] as Obj]],
            ["id": "sp-shop-wt", "name": "fix-cart", "color": "#5f9cf0", "repo": "/Users/demo/dev/shop/.worktrees/fix-cart", "branch": "fix/cart-totals", "behind": 2,
             "worktree": true, "parent": "sp-shop", "tabs": [["id": "tb3", "name": "1", "named": false, "terminals": ["t2"], "tree": leaf("t2")] as Obj]],
            ["id": "sp-api", "name": "api", "color": "#a98bf0", "repo": "/Users/demo/dev/api", "branch": "feat/rate-limit", "behind": 0, "worktree": false, "parent": NSNull(),
             "tabs": [["id": "tb4", "name": "1", "named": false, "terminals": ["t3"], "tree": leaf("t3")] as Obj,
                      ["id": "tb5", "name": "db", "named": true, "terminals": ["t4"], "tree": leaf("t4")] as Obj]],
            ["id": "sp-docs", "name": "docs", "color": "#7cc47f", "repo": "/Users/demo/dev/docs", "branch": NSNull(), "behind": 0, "worktree": false, "parent": NSNull(),
             "tabs": [["id": "tb6", "name": "1", "named": false, "terminals": ["t5"], "tree": leaf("t5")] as Obj]],
        ]
        return ["connected": true, "terminals": terminals, "spaces": spaces]
    }

    private var overview: Obj {
        ["at": now, "prs": [
            ["space": "sp-api", "spaceName": "api", "color": "#a98bf0", "branch": "feat/rate-limit",
             "pr": ["number": 212, "title": "Gateway: token-bucket rate limiter", "url": "https://github.com/example/api/pull/212", "isDraft": false,
                    "headRefName": "feat/rate-limit", "reviewDecision": "REVIEW_REQUIRED", "updatedAt": iso(30)] as Obj,
             "readiness": ["state": "blocked"] as Obj, "counts": ["pass": 6, "fail": 0, "pending": 1, "skip": 0] as Obj] as Obj,
            ["space": "sp-shop-wt", "spaceName": "fix-cart", "color": "#5f9cf0", "branch": "fix/cart-totals",
             "pr": ["number": 87, "title": "Cart: totals include the shipping discount", "url": "https://github.com/example/shop/pull/87", "isDraft": true,
                    "headRefName": "fix/cart-totals", "reviewDecision": "", "updatedAt": iso(8)] as Obj,
             "readiness": ["state": "blocked"] as Obj, "counts": ["pass": 3, "fail": 1, "pending": 0, "skip": 0] as Obj] as Obj,
        ]]
    }

    private func chat(_ address: String) -> Obj {
        func msg(_ id: String, _ role: String, _ minutes: Double, _ parts: [Obj]) -> Obj { ["id": id, "role": role, "at": iso(minutes), "parts": parts] }
        func text(_ t: String) -> Obj { ["kind": "text", "text": t] }
        func tool(_ name: String, _ summary: String, _ result: String?, error: Bool = false) -> Obj {
            var o: Obj = ["kind": "tool", "name": name, "summary": summary]
            if let result { o["result"] = ["text": result, "error": error, "truncated": false] as Obj }
            return o
        }
        var messages: [Obj]
        switch address {
        case "t1":
            messages = [
                msg("u1", "user", 9, [text("Add end-to-end tests for checkout. Keep them fast.")]),
                msg("a1", "assistant", 8, [
                    ["kind": "thinking", "text": "The checkout has a guest flow and an account flow."],
                    tool("Read", "web/checkout/flow.ts", "export const steps = ['cart', 'address', 'payment', 'review'];"),
                    tool("Bash", "npm test -- checkout", "12 passed"),
                    text("The checkout has **two flows**: guest and signed-in. Each one has four steps:\n\n1. Cart\n2. Address\n3. Payment\n4. Review\n\nA full pass over both takes about `40 s`. One flow takes half of that."),
                ]),
                msg("a2", "assistant", 2, [
                    ["kind": "question", "id": "q1", "questions": [
                        ["header": "Coverage", "question": "Which checkout flow should the tests cover?", "multi": false, "options": [
                            ["label": "Both flows", "description": "Guest and signed-in, about 40 s"],
                            ["label": "Guest only", "description": "The most used path, about 20 s"],
                            ["label": "Signed-in only", "description": "Saved addresses and cards"],
                        ]] as Obj,
                    ]] as Obj,
                ]),
            ]
        case "t2":
            messages = [
                msg("u1", "user", 12, [text("The cart total ignores the shipping discount. Fix it and add a test.")]),
                msg("a1", "assistant", 6, [
                    tool("grep", "shippingDiscount", "src/cart/total.ts:41\nsrc/cart/total.test.ts:12"),
                    tool("edit", "src/cart/total.ts", "1 change"),
                    text("The total was computed before the discount was applied. I moved the discount step ahead of the tax step, as in the table:\n\n| Step | Before | After |\n| --- | --- | --- |\n| Discount | 3 | 2 |\n| Tax | 2 | 3 |\n\n```ts\nconst total = applyTax(applyDiscount(subtotal));\n```\n\nNow I run the suite."),
                ]),
                msg("a2", "assistant", 1, [tool("bash", "npm test", nil)]),
            ]
        case "t3":
            messages = [
                msg("u1", "user", 40, [text("Add a rate limiter to the gateway.")]),
                msg("a1", "assistant", 14, [tool("bash", "npm test", "48 passed"), text("The rate limiter is in. It uses a token bucket per API key, 60 requests a minute by default. All 48 tests pass.")]),
            ]
        case "t4":
            messages = [
                msg("u1", "user", 30, [text("Run the pending migrations on the dev database.")]),
                msg("a1", "assistant", 22, [tool("bash", "npm run migrate", "error: column \"plan\" of relation \"accounts\" already exists", error: true),
                                            text("Migration 0042 failed: the column `plan` already exists. The dev database has a change that the migration table does not record.")]),
            ]
        default:
            messages = [msg("a1", "assistant", 190, [text("The docs build is clean.")])]
        }
        return ["available": true, "version": "v-\(address)", "truncated": false, "messages": messages]
    }

    private func answer(_ method: String, _ path: String, _ query: String?, _ body: Data?) -> (Obj, Int) {
        if method == "POST" {
            lock.lock(); _posts.append((path, body ?? Data())); lock.unlock()
            if path == "/api/sessions" { return (["address": "t9", "name": "hm-claude-demo", "tab": "tb9"], 200) }
            if path.hasSuffix("/upload") { return (["path": "/tmp/herdr/upload-1.jpg", "bytes": body?.count ?? 0], 200) }
            return (["ok": true], 200)
        }
        let parts = path.split(separator: "/").map(String.init)
        switch path {
        case "/api/overview": return (overview, 200)
        case "/api/info": return (["app": "herdr-monitor", "host": "demo-mac.example.ts.net", "herdr": ["ui": ["version": "0.9.2", "running": true], "tui": ["version": "0.4.0", "running": false], "hc": "0.9.2"] as Obj], 200)
        case "/api/discover": return (["servers": [["url": DemoTransport.server, "name": "demo-mac", "os": "macOS", "self": true,
                                                    "herdr": ["ui": ["version": "0.9.2", "running": true], "tui": ["version": "0.4.0", "running": false], "hc": "0.9.2"] as Obj] as Obj]], 200)
        case "/api/health": return (["ok": true, "connected": true, "socket": "/tmp/hc.sock", "terminals": 7], 200)
        default: break
        }
        if parts.count >= 4, parts[1] == "terminals" {
            let addr = parts[2].removingPercentEncoding ?? parts[2]
            switch parts[3] {
            case "chat":
                if query == "v=v-\(addr)" { return (["unchanged": true, "version": "v-\(addr)"], 200) }
                return (chat(addr), 200)
            case "read": return (["text": "demo@mac shop % npm test\n\n PASS  src/cart/total.test.ts\n ✓ total includes the shipping discount (4 ms)\n\nTests: 12 passed, 12 total\n"], 200)
            case "commits" where parts.count == 4:
                return (["repo": "shop", "branch": "fix/cart-totals", "unpushed": 1, "dirty": 2, "commits": [
                    ["sha": "a1b2c3d4e5f6", "author": "Demo", "date": "2026-11-14T10:00:00Z", "subject": "Cart: apply the discount before tax", "local": true] as Obj,
                    ["sha": "0f9e8d7c6b5a", "author": "Demo", "date": "2026-11-13T16:20:00Z", "subject": "Cart: a failing test for the shipping discount", "local": false] as Obj,
                ]], 200)
            case "commits":
                return (["sha": parts[4], "author": "Demo", "date": "2026-11-14T10:00:00Z", "message": "Cart: apply the discount before tax\n\nThe total was computed before the discount.",
                         "files": [["file": "src/cart/total.ts", "additions": 4, "deletions": 3] as Obj]], 200)
            case "status":
                return (["repo": "shop", "branch": "fix/cart-totals",
                         "pr": ["number": 87, "title": "Cart: totals include the shipping discount", "url": "https://github.com/example/shop/pull/87", "isDraft": true, "headRefName": "fix/cart-totals", "reviewDecision": ""] as Obj,
                         "readiness": ["state": "blocked", "reasons": ["The PR is a draft", "1 check failed"]] as Obj,
                         "checks": [["name": "test", "bucket": "fail", "url": "https://github.com/example/shop/actions/runs/1"] as Obj, ["name": "lint", "bucket": "pass"] as Obj, ["name": "typecheck", "bucket": "pass"] as Obj]], 200)
            default: break
            }
        }
        if parts.count == 4, parts[1] == "spaces", parts[3] == "worktrees" {
            return (["repo": "/Users/demo/dev/shop", "worktrees": [
                ["path": "/Users/demo/dev/shop", "name": "shop", "branch": "main", "space": "sp-shop"] as Obj,
                ["path": "/Users/demo/dev/shop/.worktrees/fix-cart", "name": "fix-cart", "branch": "fix/cart-totals", "space": "sp-shop-wt"] as Obj,
                ["path": "/Users/demo/dev/shop/.worktrees/new-search", "name": "new-search", "branch": "feat/search", "space": NSNull()] as Obj,
            ]], 200)
        }
        return (["error": "not found"], 404)
    }

    public func send(_ method: String, _ url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (Data, Int) {
        if let failure { throw failure }
        let (o, code) = answer(method, url.path, url.query, body)
        return (JSON.data(o), code)
    }

    public func stream(_ url: URL, timeout: TimeInterval) -> AsyncThrowingStream<StreamEvent, Error> {
        let frame = "event: snapshot\ndata: " + String(decoding: JSON.data(snapshot), as: UTF8.self) + "\n\n"
        let failure = self.failure
        return AsyncThrowingStream { c in
            if let failure { c.finish(throwing: failure); return }
            c.yield(.open)
            for l in frame.components(separatedBy: "\n").dropLast() { c.yield(.line(l)) }
            // The stream stays open, like the real one.
        }
    }
}
