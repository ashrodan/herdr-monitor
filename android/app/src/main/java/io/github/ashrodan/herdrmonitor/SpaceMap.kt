package io.github.ashrodan.herdrmonitor

// The space map: where a terminal sits in Herdr (its space, its tab, its
// pane), the space's worktrees, and the panes of each tab as rectangles.
// No Android types, so JVM tests run it.

// A tab's panes, from the layout's tree: a split (side by side when row,
// else stacked) or one pane. w is the relative size.
sealed interface Pane { val w: Float }
data class PaneLeaf(val terminal: String, override val w: Float = 1f) : Pane
data class PaneSplit(val row: Boolean, val kids: List<Pane>, override val w: Float = 1f) : Pane

// A pane's place in its tab, in parts of the tab's width and height (0..1).
data class PaneRect(val terminal: String, val x: Float, val y: Float, val w: Float, val h: Float)

fun paneRects(tree: Pane?, x: Float = 0f, y: Float = 0f, w: Float = 1f, h: Float = 1f): List<PaneRect> = when (tree) {
    null -> emptyList()
    is PaneLeaf -> listOf(PaneRect(tree.terminal, x, y, w, h))
    is PaneSplit -> {
        val total = tree.kids.sumOf { it.w.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        var at = 0f
        tree.kids.flatMap { k ->
            val part = k.w / total
            val r = if (tree.row) paneRects(k, x + at * w, y, part * w, h) else paneRects(k, x, y + at * h, w, part * h)
            at += part
            r
        }
    }
}

// A tab's panes; a tab from an older server (no tree) shows its terminals
// side by side.
fun SpaceTab.panes(): List<PaneRect> =
    paneRects(tree ?: terminals.takeIf { it.isNotEmpty() }?.let { ts -> if (ts.size == 1) PaneLeaf(ts[0]) else PaneSplit(true, ts.map { PaneLeaf(it) }) })

data class Place(val space: Space, val tab: SpaceTab, val tabIndex: Int)

// Where a terminal is drawn in Herdr: hc's labels first, else a search.
fun placeOf(t: Terminal?, address: String, spaces: List<Space>): Place? {
    val hinted = spaces.firstOrNull { it.id == t?.space }
    for (s in listOfNotNull(hinted) + spaces) {
        val i = s.tabs.indexOfFirst { address in it.terminals }
        if (i >= 0) return Place(s, s.tabs[i], i)
    }
    return null
}

// A space and its worktrees, main space first, in the layout's order. A
// worktree's family is its main space's (or the repo's header row).
fun spaceFamily(spaces: List<Space>, id: String?): List<Space> {
    val s = spaces.firstOrNull { it.id == id } ?: return emptyList()
    val head = s.parent?.let { p -> spaces.firstOrNull { it.id == p } } ?: s
    return listOf(head) + spaces.filter { it.parent == head.id }
}

// A git worktree of the space's repo, and its space in Herdr (null: none).
data class Worktree(val path: String, val name: String, val branch: String?, val space: String?)

// What the map shows under the spaces: worktrees that have no space here.
fun worktreesWithoutSpace(all: List<Worktree>, family: List<Space>): List<Worktree> {
    val ids = family.map { it.id }.toSet()
    val repos = family.mapNotNull { it.repo }.toSet()
    return all.filter { w -> (w.space == null || w.space !in ids) && w.path !in repos }
}

data class Started(val address: String, val tab: String?, val placeError: String?)
