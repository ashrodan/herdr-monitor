import Foundation
import Testing
@testable import HerdrKit

// Port of android SpaceMapTest.kt.
@Suite struct SpaceMapTests {
    private func close(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-4 }

    @Test func panesFollowSplitsAndWeights() throws {
        // t1 on the left (60 %), t2 over t3 on the right.
        let tree = Pane.split(row: true, kids: [
            .leaf(terminal: "t1", w: 0.6), .split(row: false, kids: [.leaf(terminal: "t2", w: 1), .leaf(terminal: "t3", w: 1)], w: 0.4),
        ], w: 1)
        let rects = paneRects(tree)
        #expect(rects.count == 3)
        let r = Dictionary(uniqueKeysWithValues: rects.map { ($0.terminal, $0) })
        let t1 = try #require(r["t1"]), t2 = try #require(r["t2"]), t3 = try #require(r["t3"])
        #expect(close(t1.w, 0.6) && close(t1.h, 1) && close(t1.x, 0))
        #expect(close(t2.x, 0.6) && close(t2.w, 0.4) && close(t2.h, 0.5) && close(t2.y, 0))
        #expect(close(t3.y, 0.5) && close(t3.h, 0.5))
        #expect(paneRects(nil) == [])
    }

    @Test func aTabWithNoTreeShowsItsTerminalsSideBySide() {
        let r = SpaceTab(id: "tb", name: "1", named: false, terminals: ["a", "b"]).panes
        #expect(r.map(\.terminal) == ["a", "b"])
        #expect(r.count == 2 && close(r[1].x, 0.5) && close(r[1].w, 0.5))
        #expect(SpaceTab(id: "tb", name: "1", named: false, terminals: ["a"]).panes == [PaneRect(terminal: "a", x: 0, y: 0, w: 1, h: 1)])
    }

    private let spaces = [
        Space(id: "main", name: "app", repo: "/r", tabs: [
            SpaceTab(id: "tA", name: "1", named: false, terminals: ["t1"]), SpaceTab(id: "tB", name: "2", named: false, terminals: ["t2", "t3"]),
        ]),
        Space(id: "wt1", name: "fix-x", repo: "/r/.worktrees/fix-x", worktree: true,
              tabs: [SpaceTab(id: "tC", name: "1", named: false, terminals: ["t4"])], parent: "main"),
        Space(id: "other", name: "notes", repo: "/n"),
    ]

    @Test func placeFindsTheSpaceTabAndIndex() throws {
        let p = try #require(placeOf(Terminal(address: "t3"), address: "t3", spaces))
        #expect(p.space.id == "main")
        #expect(p.tab.id == "tB")
        #expect(p.tabIndex == 1)
        #expect(placeOf(nil, address: "t9", spaces) == nil)
    }

    @Test func aWorktreeShowsWithItsMainSpace() {
        #expect(spaceFamily(spaces, id: "wt1").map(\.id) == ["main", "wt1"])
        #expect(spaceFamily(spaces, id: "main").map(\.id) == ["main", "wt1"])
        #expect(spaceFamily(spaces, id: "other").map(\.id) == ["other"])
        #expect(spaceFamily(spaces, id: nil) == [])
    }

    @Test func onlyWorktreesWithNoSpaceHereAreListedApart() {
        let all = [
            Worktree(path: "/r", name: "r", branch: "main", space: "main"),
            Worktree(path: "/r/.worktrees/fix-x", name: "fix-x", branch: "fix-x", space: "wt1"),
            Worktree(path: "/r/.worktrees/old", name: "old", branch: "old", space: nil),
        ]
        #expect(worktreesWithoutSpace(all, family: spaceFamily(spaces, id: "main")).map(\.name) == ["old"])
    }
}
