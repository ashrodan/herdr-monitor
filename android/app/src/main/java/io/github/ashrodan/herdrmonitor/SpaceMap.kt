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

// A move of the joystick.
enum class Move { Left, Right, Up, Down }

private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = minOf(a1, b1) - maxOf(a0, b0)

// The pane that is beside `address` in that direction in one tab: it must
// share a part of the edge. The nearest one wins, then the one that shares
// the most of the edge. alive: only a terminal that still runs counts.
fun paneBeside(rects: List<PaneRect>, address: String, move: Move, alive: (String) -> Boolean): String? {
    val c = rects.firstOrNull { it.terminal == address } ?: return null
    val eps = 1e-3f
    return rects.filter { it.terminal != address && alive(it.terminal) }.mapNotNull { r ->
        val across = overlap(c.y, c.y + c.h, r.y, r.y + r.h)
        val along = overlap(c.x, c.x + c.w, r.x, r.x + r.w)
        val (gap, shared) = when (move) {
            Move.Right -> (r.x - (c.x + c.w)) to across
            Move.Left -> (c.x - (r.x + r.w)) to across
            Move.Down -> (r.y - (c.y + c.h)) to along
            Move.Up -> (c.y - (r.y + r.h)) to along
        }
        if (gap < -eps || shared <= eps) null else Triple(r.terminal, Math.round(gap / eps), shared)
    }.minWithOrNull(compareBy<Triple<String, Int, Float>>({ it.second }, { -it.third }))?.first
}

// Where the joystick goes from `address`, as on the space map (tabs side by
// side, the spaces of the repo one under the other). In the tab first. At
// the tab's left or right edge: the tab before or after it, into the pane
// on its near edge at the height you left. At its top or bottom edge: the
// space above or below in the family (spaces: all of them), into the tab at
// the same place in the row. A tab or space with no terminal that runs is
// passed.
fun neighbour(place: Place?, address: String, move: Move, alive: (String) -> Boolean, spaces: List<Space> = emptyList()): String? {
    place ?: return null
    val here = place.tab.panes()
    paneBeside(here, address, move, alive)?.let { return it }
    val me = here.firstOrNull { it.terminal == address }
    val near = 1e-3f
    if (move == Move.Up || move == Move.Down) {
        val down = move == Move.Down
        val family = spaceFamily(spaces, place.space.id)
        val cx = me?.let { it.x + it.w / 2 } ?: 0.5f
        var j = family.indexOfFirst { it.id == place.space.id }.takeIf { it >= 0 }?.plus(if (down) 1 else -1) ?: return null
        while (j in family.indices) {
            val tabs = family[j].tabs
            for (k in tabs.indices.sortedBy { kotlin.math.abs(it - place.tabIndex) }) {
                val rects = tabs[k].panes().filter { alive(it.terminal) }
                if (rects.isEmpty()) continue
                val edge = if (down) rects.minOf { it.y } else rects.maxOf { it.y + it.h }
                return rects.filter { kotlin.math.abs((if (down) it.y else it.y + it.h) - edge) < near }
                    .minByOrNull { kotlin.math.abs(it.x + it.w / 2 - cx) }?.terminal
            }
            j += if (down) 1 else -1
        }
        return null
    }
    val step = if (move == Move.Right) 1 else -1
    val cy = me?.let { it.y + it.h / 2 } ?: 0.5f
    var i = place.tabIndex + step
    while (i in place.space.tabs.indices) {
        val rects = place.space.tabs[i].panes().filter { alive(it.terminal) }
        if (rects.isNotEmpty()) {
            val edge = if (move == Move.Right) rects.minOf { it.x } else rects.maxOf { it.x + it.w }
            return rects.filter { kotlin.math.abs((if (move == Move.Right) it.x else it.x + it.w) - edge) < near }
                .minByOrNull { kotlin.math.abs(it.y + it.h / 2 - cy) }?.terminal
        }
        i += step
    }
    return null
}

// A terminal in no tab (a session on another machine): left and right go
// through the sessions a swipe reaches.
fun neighbourInList(pages: List<String>, address: String, move: Move): String? {
    val i = pages.indexOf(address)
    if (i < 0) return null
    return when (move) { Move.Left -> pages.getOrNull(i - 1); Move.Right -> pages.getOrNull(i + 1); else -> null }
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
