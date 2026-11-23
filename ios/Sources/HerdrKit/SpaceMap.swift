import Foundation

// The space map: where a terminal sits in Herdr (its space, its tab, its
// pane), the space's worktrees, and the panes of each tab as rectangles.

// A tab's panes, from the layout's tree: a split (side by side when row,
// else stacked) or one pane. w is the relative size.
public indirect enum Pane: Equatable, Hashable, Sendable {
    case leaf(terminal: String, w: Double)
    case split(row: Bool, kids: [Pane], w: Double)

    public var w: Double {
        switch self { case .leaf(_, let w): return w; case .split(_, _, let w): return w }
    }
}

// A pane's place in its tab, in parts of the tab's width and height (0..1).
public struct PaneRect: Equatable, Identifiable, Sendable {
    public var terminal: String, x: Double, y: Double, w: Double, h: Double
    public var id: String { terminal }
}

public func paneRects(_ tree: Pane?, x: Double = 0, y: Double = 0, w: Double = 1, h: Double = 1) -> [PaneRect] {
    guard let tree else { return [] }
    switch tree {
    case .leaf(let terminal, _):
        return [PaneRect(terminal: terminal, x: x, y: y, w: w, h: h)]
    case .split(let row, let kids, _):
        let sum = kids.reduce(0) { $0 + $1.w }
        let total = sum > 0 ? sum : 1
        var at = 0.0, out: [PaneRect] = []
        for k in kids {
            let part = k.w / total
            out += row ? paneRects(k, x: x + at * w, y: y, w: part * w, h: h) : paneRects(k, x: x, y: y + at * h, w: w, h: part * h)
            at += part
        }
        return out
    }
}

public extension SpaceTab {
    // A tab from an older server (no tree) shows its terminals side by side.
    var panes: [PaneRect] {
        if let tree { return paneRects(tree) }
        if terminals.isEmpty { return [] }
        return paneRects(terminals.count == 1 ? .leaf(terminal: terminals[0], w: 1) : .split(row: true, kids: terminals.map { .leaf(terminal: $0, w: 1) }, w: 1))
    }
}

public struct Place: Equatable, Sendable {
    public var space: Space, tab: SpaceTab, tabIndex: Int
}

// Where a terminal is drawn in Herdr: hc's labels first, else a search.
public func placeOf(_ t: Terminal?, address: String, _ spaces: [Space]) -> Place? {
    let hinted = spaces.first { $0.id == t?.space }
    for s in (hinted.map { [$0] } ?? []) + spaces {
        if let i = s.tabs.firstIndex(where: { $0.terminals.contains(address) }) { return Place(space: s, tab: s.tabs[i], tabIndex: i) }
    }
    return nil
}

// A space and its worktrees, main space first, in the layout's order.
public func spaceFamily(_ spaces: [Space], id: String?) -> [Space] {
    guard let s = spaces.first(where: { $0.id == id }) else { return [] }
    let head = s.parent.flatMap { p in spaces.first { $0.id == p } } ?? s
    return [head] + spaces.filter { $0.parent == head.id }
}

// What the map shows under the spaces: worktrees that have no space here.
public func worktreesWithoutSpace(_ all: [Worktree], family: [Space]) -> [Worktree] {
    let ids = Set(family.map(\.id)), repos = Set(family.compactMap(\.repo))
    return all.filter { w in (w.space == nil || !ids.contains(w.space!)) && !repos.contains(w.path) }
}

// A parent space with its worktrees rolled up: the most urgent state of
// the family, the worktree count and the commits behind, summed.
public struct SpaceRollup: Equatable, Sendable {
    public var state: String?
    public var worktrees: Int
    public var behind: Int
}

public func rollup(_ head: Space, _ spaces: [Space], _ terminals: [String: Terminal], _ localSeen: [String: Int64] = [:]) -> SpaceRollup {
    let kids = spaces.filter { $0.parent == head.id }
    let states = Set(([head] + kids).compactMap { spaceState($0, terminals, localSeen) })
    return SpaceRollup(state: ["blocked", "error", "working"].first { states.contains($0) }, worktrees: kids.count,
                       behind: ([head] + kids).reduce(0) { $0 + $1.behind })
}
