// Summary: what needs you across every space, like herdr-monitor's Overview.
//
// Two copies of one mod:
// - the bar (props {bar: true}, in the status row): counts of agents waiting,
//   done and working, and open PRs. A click opens the panel on that list.
// - the panel (in the right panel stack, no props): the lists, each Claude
//   agent's latest recap, and every open PR in the repos of the open spaces.
//
// Agent facts are Herdr's own (agents()), so they cost nothing. PRs come from
// `gh pr list` per repo, at most every 2 minutes, shared between the copies
// through setShared("summary.prs"). Recaps are read only for rows the panel
// shows, and again only when that agent changes.
import { View, div, image, svg } from "gpui-kit";
import { v_flex, h_flex } from "gpui-base";
import { agents, spaces, layout, theme, run, shared, setShared, nextCommand, props } from "herdr";
import { loadPrs, loadRecap, age } from "./data.js";

const MOD = "summary";
const PANEL_BLOCK = "summary"; // the block id in the layout's panel-body stack
const PRS = "summary.prs";
const VIEW = "summary.view";
const PR_EVERY = 120_000;   // the bar refreshes PRs this often
const PR_FRESH = 60_000;    // the panel trusts shared PRs this young
const SHOW = { waiting: 8, done: 6, working: 6, prs: 8 };
const KINDS = ["waiting", "done", "working", "prs"];
const TITLE = { waiting: "Waiting for you", done: "Done, not seen", working: "Working", prs: "Open PRs" };

let inflight = 0; // one PR load at a time across copies (they share this module)

function findNode(node, id) {
  if (node?.id === id) return node;
  for (const child of node?.children ?? []) { const f = findNode(child, id); if (f) return f; }
  return null;
}
function panelShown() {
  const root = layout(), panel = findNode(root, "panel"), body = findNode(root, "panel-body");
  return Boolean(panel && !panel.hidden && body?.active === PANEL_BLOCK);
}

// Open the panel on a list. With no panel block in the layout, open a pane.
function openPanel(view) {
  if (KINDS.includes(view) || view === "all") { if (shared(VIEW) !== view) setShared(VIEW, view); }
  if (findNode(layout(), PANEL_BLOCK)) {
    run("layout.select", { stack: "panel-body", child: PANEL_BLOCK });
    run("layout.show", { id: "panel" });
  } else {
    run("pane.split", { direction: "right", mod: MOD });
  }
}
function togglePanel(view) {
  if (panelShown() && (view == null || shared(VIEW) === view)) run("layout.hide", { id: "panel" });
  else openPanel(view);
}

// The lists, from Herdr's live agent data.
function buckets() {
  const list = agents();
  return {
    waiting: list.filter((a) => a.status === "blocked"),
    done: list.filter((a) => a.status === "done").sort((a, b) => b.seq - a.seq),
    working: list.filter((a) => a.status === "working").sort((a, b) => b.seq - a.seq),
  };
}

const isClaude = (a) => a.app === "claude-code" || a.program?.key === "claude";

function glyph(p, size, color) {
  if (!p?.icon) return div().size(size).flex_none();
  return p.tint ? svg(p.icon).size(size).flex_none().text_color(color) : image(p.icon).w(size).h(size).flex_none();
}

export default class Summary extends View {
  init(_props, cx) {
    this.bar = Boolean(props()?.bar);
    this.recaps = new Map(); // address -> { seq, recap, loading }
    this.stale = true;
    if (this.bar) {
      // The bar is always on screen: it keeps the PR facts fresh for both copies.
      cx.timer.every(PR_EVERY, (cx) => this.loadPrs(cx, false, PR_EVERY - 10_000));
      this.loadPrs(cx, false, PR_EVERY - 10_000);
    }
    cx.spawn(async (cx) => { for (;;) { this.command(await nextCommand(), cx); cx.notify(); } });
  }

  // summary.open, .toggle, .all, .waiting, .done, .working, .prs, .refresh
  command(name, cx) {
    const c = String(name).replace(/^summary[.:]/, "");
    if (c === "refresh") { this.loadPrs(cx, true); this.recaps.clear(); return; }
    if (!this.bar && panelShown() && (KINDS.includes(c) || c === "all")) { setShared(VIEW, c); return; }
    if (!this.bar) return; // the bar opens and toggles; the panel would do it twice
    if (c === "open") openPanel(null);
    else if (c === "toggle") togglePanel(null);
    else if (KINDS.includes(c) || c === "all") openPanel(c);
  }

  // Load PRs unless the shared ones are younger than maxAge (or force).
  loadPrs(cx, force, maxAge = PR_FRESH) {
    const have = shared(PRS);
    if (!force && have && Date.now() - have.at < maxAge) return;
    if (inflight && Date.now() - inflight < 60_000) return;
    const started = inflight = Date.now();
    this.loading = true; cx.notify();
    cx.spawn(async (cx) => {
      try {
        const value = await loadPrs();
        setShared(PRS, value); // at changes each load: one redraw per load
      } catch (e) {
        setShared(PRS, { ...(shared(PRS) ?? { prs: [], repos: 0 }), at: Date.now(), errors: [{ repo: "", error: String(e).slice(0, 120) }] });
      } finally {
        if (inflight === started) inflight = 0;
        this.loading = false; cx.notify();
      }
    });
  }

  // Recaps for the Claude agents the panel shows; again when one changes.
  loadRecaps(rows, cx) {
    const local = new Set(spaces().filter((s) => !s.machine).map((s) => s.id));
    for (const a of rows) {
      if (!isClaude(a) || !local.has(a.space)) continue;
      const have = this.recaps.get(a.address);
      if (have && (have.seq === a.seq || have.loading)) continue;
      this.recaps.set(a.address, { seq: a.seq, recap: have?.recap ?? null, loading: true });
      cx.spawn(async (cx) => {
        let recap = have?.recap ?? null;
        try { recap = await loadRecap(a.address); } catch { /* keep the last one */ }
        this.recaps.set(a.address, { seq: a.seq, recap, loading: false });
        cx.notify();
      });
    }
  }

  render(cx) {
    const t = theme();
    return this.bar ? this.renderBar(t) : this.renderPanel(t, cx);
  }

  // ------------------------------------------------------------ the bar

  renderBar(t) {
    const b = buckets();
    const prs = shared(PRS);
    const view = panelShown() ? shared(VIEW) : null;
    const chip = (kind, icon, n, label, color) => h_flex().id(`summary-${kind}`).h(22).px(7).gap(5).items_center().flex_shrink_0()
      .rounded(5).cursor_pointer().hover((s) => s.bg(t.raised)).when(view === kind, (el) => el.bg(t.selected ?? t.raised))
      .text_color(n ? color : t.dim)
      .tooltip(`${TITLE[kind]}: open the list`)
      .on_click(() => togglePanel(kind))
      .child(icon)
      .child(div().font_semibold().child(String(n)))
      .child(div().text_color(n ? t.muted : t.dim).child(label));
    const dot = (c) => div().size(7).flex_none().rounded(4).bg(c);
    return h_flex().id("summary-bar").size_full().px(6).gap(2).items_center().overflow_x_scroll()
      .bg(t.chrome).border_t(1).border_color(t.border).text_size(11.5)
      .child(h_flex().id("summary-open").h(22).px(6).gap(5).items_center().flex_shrink_0().rounded(5).cursor_pointer()
        .hover((s) => s.bg(t.raised)).text_color(t.muted).tooltip("Summary of every space")
        .on_click(() => togglePanel("all"))
        .child(svg("mods/summary/summary.svg").size(13)))
      .child(chip("waiting", svg("mods/summary/alert.svg").size(12), b.waiting.length, "waiting", t.blocked))
      .child(chip("done", svg("mods/summary/check.svg").size(12), b.done.length, "done", t.done))
      .child(chip("working", b.working.length ? svg("mods/summary/spinner.svg").size(11).spin(900) : dot(t.dim), b.working.length, "working", t.working))
      .child(chip("prs", svg("mods/summary/pr.svg").size(12), prs?.prs?.length ?? 0, prs ? "PRs" : "PRs …", t.foreground));
  }

  // ------------------------------------------------------------ the panel

  renderPanel(t, cx) {
    const shown = panelShown();
    if (shown && this.stale) { this.stale = false; this.loadPrs(cx, false); }
    if (!shown) this.stale = true;

    const b = buckets();
    const prs = shared(PRS);
    const view = shared(VIEW) ?? "all";
    const one = KINDS.includes(view) ? view : null;
    const lists = { ...b, prs: prs?.prs ?? [] };

    // Recaps only for rows on screen.
    if (shown) {
      const rows = one ? (one === "prs" ? [] : lists[one]) : [...b.waiting.slice(0, SHOW.waiting), ...b.done.slice(0, SHOW.done), ...b.working.slice(0, SHOW.working)];
      this.loadRecaps(rows, cx);
    }

    const tile = (kind, n, color) => v_flex().id(`tile-${kind}`).flex_1().min_w(0).px(8).py(6).gap(1).rounded(8).cursor_pointer()
      .border(1).border_color(one === kind ? (color ?? t.accent) : t.border).bg(one === kind ? t.raised : t.background)
      .hover((s) => s.bg(t.raised))
      .on_click(() => setShared(VIEW, one === kind ? "all" : kind))
      .child(div().text_size(18).font_semibold().text_color(n ? color ?? t.foreground : t.dim).child(String(n)))
      .child(div().text_size(10.5).text_color(t.muted).truncate().child(kind === "prs" ? "Open PRs" : kind === "done" ? "Done" : kind === "waiting" ? "Waiting" : "Working"));

    const header = h_flex().px(12).h(40).flex_none().items_center().gap(8).border_b(1).border_color(t.border)
      .child(div().text_size(13).font_semibold().child("Summary"))
      .child(div().flex_1().text_size(11).text_color(t.dim).truncate()
        .child(prs ? `${prs.repos} repo${prs.repos === 1 ? "" : "s"} · PRs ${age(prs.at)}${age(prs.at) === "now" ? "" : " ago"}` : "Loading PRs…"))
      .child(div().id("summary-refresh").size(24).flex().items_center().justify_center().rounded(5).cursor_pointer()
        .hover((s) => s.bg(t.raised)).text_color(t.muted).tooltip("Refresh PRs and recaps")
        .on_click((_e, cx) => { this.recaps.clear(); this.loadPrs(cx, true); })
        .child(this.loading ? svg("mods/summary/spinner.svg").size(13).spin(900) : svg("mods/summary/refresh.svg").size(13)));

    const tiles = h_flex().px(12).pt(10).pb(4).gap(6).flex_none()
      .child(tile("waiting", b.waiting.length, t.blocked))
      .child(tile("done", b.done.length, t.done))
      .child(tile("working", b.working.length, t.working))
      .child(tile("prs", lists.prs.length, null));

    const parts = [];
    for (const kind of one ? [one] : KINDS) {
      const rows = lists[kind];
      if (!one && !rows.length && kind !== "waiting") continue;
      parts.push(this.section(t, kind, rows, one ? Infinity : SHOW[kind], prs));
    }
    if (prs?.errors?.length && (!one || one === "prs")) {
      parts.push(v_flex().px(6).pt(8).gap(2).text_size(11).text_color(t.dim)
        .children(prs.errors.slice(0, 4).map((e, i) => div().id(`err-${i}`).truncate().child(`${e.repo.split("/").pop() || "PRs"}: ${e.error}`))));
    }
    const body = v_flex().id("summary-body").flex_1().min_h(0).overflow_y_scrollbar().px(8).pb(12).children(parts);
    return v_flex().id("summary-panel").size_full().bg(t.surface).text_size(12.5).text_color(t.foreground)
      .child(header).child(tiles).child(body);
  }

  section(t, kind, rows, limit, prs) {
    const head = h_flex().px(6).pb(3).gap(6).items_center().text_size(11).font_semibold().text_color(t.muted)
        .child(TITLE[kind]).child(div().text_color(t.dim).child(String(rows.length)));
    const el = v_flex().id(`section-${kind}`).pt(10).gap(1).child(head);
    if (!rows.length) {
      return el.child(div().px(6).py(4).text_size(11.5).text_color(t.dim)
        .child(kind === "waiting" ? "Nothing needs you." : kind === "prs" ? (prs ? "No open PRs." : "Loading…") : "None."));
    }
    const shown = rows.slice(0, limit);
    return el.children(shown.map((r) => (kind === "prs" ? this.prRow(t, r) : this.agentRow(t, r, kind))))
      .when(rows.length > shown.length, (el) => el.child(div().id(`more-${kind}`).px(6).py(3).text_size(11.5).text_color(t.accent).cursor_pointer()
        .on_click(() => setShared(VIEW, kind)).child(`${rows.length - shown.length} more`)));
  }

  agentRow(t, a, kind) {
    const color = kind === "waiting" ? t.blocked : kind === "done" ? t.done : t.working;
    const recap = this.recaps.get(a.address)?.recap;
    const where = [a.space_name, a.tab_name].filter(Boolean).join(" · ");
    const title = String(a.title ?? "").replace(/^[^\p{L}\p{N}]+/u, "").trim();
    return v_flex().id(`agent-${a.address}`).px(6).py(5).gap(2).rounded(6).cursor_pointer()
      .hover((s) => s.bg(t.raised)).when(a.focused, (el) => el.bg(t.selected ?? t.raised))
      .tooltip(`Show ${a.address}`)
      .on_click(() => run("agent.focus", { terminal: a.address }))
      .child(h_flex().gap(7).items_center().min_w(0)
        .child(glyph(a.program, 14, t.muted))
        .child(div().flex_1().min_w(0).truncate().font_semibold().child(where || a.address))
        .child(kind === "working"
          ? svg("mods/summary/spinner.svg").size(11).flex_none().text_color(color).spin(900)
          : div().size(7).flex_none().rounded(4).bg(color)))
      .when(Boolean(title) && !recap, (el) => el.child(div().pl(21).text_size(11.5).text_color(t.muted).truncate().child(title)))
      .when(Boolean(recap), (el) => el.child(div().pl(21).text_size(11.5).text_color(t.muted).child(recap.text)));
  }

  prRow(t, p) {
    const c = p.checks ?? {};
    const state = c.fail ? ["checks fail", t.blocked] : c.pending ? ["checks running", t.working] : null;
    const review = p.review === "APPROVED" ? "approved" : p.review === "CHANGES_REQUESTED" ? "changes requested" : p.review === "REVIEW_REQUIRED" ? "review required" : "";
    const meta = [p.repo.split("/").pop(), p.branch, p.author && `@${p.author}`, p.draft ? "draft" : review].filter(Boolean).join(" · ");
    return v_flex().id(`pr-${p.url}`).px(6).py(5).gap(2).rounded(6).cursor_pointer()
      .hover((s) => s.bg(t.raised)).tooltip(`Open ${p.repo}#${p.number} on GitHub`)
      .on_click((_e, cx) => cx.open_url(p.url))
      .child(h_flex().gap(7).items_center().min_w(0)
        .child(svg("mods/summary/pr.svg").size(13).flex_none().text_color(p.draft ? t.dim : t.done))
        .child(div().flex_none().text_color(t.muted).child(`#${p.number}`))
        .child(div().flex_1().min_w(0).truncate().child(p.title))
        .when(Boolean(state), (el) => el.child(div().size(7).flex_none().rounded(4).bg(state[1]))))
      .child(div().pl(20).text_size(11).text_color(t.dim).truncate().child(`${meta} · ${age(Date.parse(p.updatedAt))}`));
  }
}
