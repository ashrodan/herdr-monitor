// Summary: what needs you across every space, like herdr-monitor's Overview.
//
// Two copies of one mod:
// - the bar (props {bar: true}, in the status row): counts of agents waiting,
//   done and working, and open PRs. A click opens the panel on that list.
// - the panel (in the right panel stack, no props): the lists, each Claude
//   agent's latest recap, the web apps running in each space (with their
//   tailnet URL), and every open PR in the repos of the open spaces.
//
// Agent facts are Herdr's own (agents()), so they cost nothing. PRs come from
// `gh pr list` per repo, at most every 2 minutes, shared between the copies
// through setShared("summary.prs"). Apps come from one port scan per machine
// every minute, shared through setShared("summary.apps"). Recaps are read only for rows the panel
// shows, and again only when that agent changes.
import { View, div, image, svg } from "gpui-kit";
import { v_flex, h_flex } from "gpui-base";
import { agents, spaces, panes, layout, theme, run, shared, setShared, nextCommand, props } from "herdr";
import { loadPrs, loadApps, shareApp, loadRecap, age } from "./data.js";

const MOD = "summary";
const PANEL_BLOCK = "summary"; // the block id in the layout's panel-body stack
const PRS = "summary.prs";
const APPS = "summary.apps";
const VIEW = "summary.view";
const SHOW = { waiting: 8, done: 6, working: 6, apps: 10, prs: 8 };
const KINDS = ["waiting", "done", "working", "apps", "prs"];
const TITLE = { waiting: "Waiting for you", done: "Done, not seen", working: "Working", apps: "Running apps", prs: "Open PRs" };

// The slow facts, each loaded by the bar on a timer and shared with the panel.
//   every: the bar reloads this often; fresh: the panel trusts values this young.
const FACTS = {
  prs: { key: PRS, load: loadPrs, every: 120_000, fresh: 60_000, empty: { prs: [], repos: 0 } },
  apps: { key: APPS, load: loadApps, every: 60_000, fresh: 30_000, empty: { apps: [] } },
};
// Per fact, across copies (they share this module): the load in flight, and
// when it was last checked. A load that finds nothing new updates only
// checked, not the shared value: setShared redraws every mod in the window.
const inflight = { prs: 0, apps: 0 };
const checked = { prs: 0, apps: 0 };
const factAt = (kind) => Math.max(shared(FACTS[kind].key)?.at ?? 0, checked[kind]);

function findNode(node, id) {
  if (node?.id === id) return node;
  for (const child of node?.children ?? []) { const f = findNode(child, id); if (f) return f; }
  return null;
}
function panelShown() {
  const root = layout(), panel = findNode(root, "panel"), body = findNode(root, "panel-body");
  return Boolean(panel && !panel.hidden && body?.active === PANEL_BLOCK);
}

// The panel copy is on screen: in the panel, or as a pane in the tab on
// screen (the fallback when the layout has no panel block).
function panelCopyShown() {
  if (panelShown()) return true;
  const tab = spaces().find((s) => s.active)?.tabs?.find((t) => t.active)?.id ?? null;
  return panes().some((p) => p.mod === MOD && p.tab === tab);
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
    this.loading = { prs: false, apps: false };
    this.sharing = new Map(); // app key -> { busy, error }
    if (this.bar) {
      // The bar is always on screen: it keeps the slow facts fresh for both copies.
      for (const kind of Object.keys(FACTS)) {
        const { every } = FACTS[kind];
        cx.timer.every(every, (cx) => this.load(kind, cx, false, every - 10_000));
        this.load(kind, cx, false, every - 10_000);
      }
    }
    cx.spawn(async (cx) => { for (;;) { this.command(await nextCommand(), cx); cx.notify(); } });
  }

  // summary.open, .toggle, .all, .waiting, .done, .working, .apps, .prs, .refresh
  command(name, cx) {
    const c = String(name).replace(/^summary[.:]/, "");
    if (c === "refresh") { this.refresh(cx); return; }
    if (!this.bar && panelCopyShown() && (KINDS.includes(c) || c === "all")) { setShared(VIEW, c); return; }
    if (!this.bar) return; // the bar opens and toggles; the panel would do it twice
    if (c === "open") openPanel(null);
    else if (c === "toggle") togglePanel(null);
    else if (KINDS.includes(c) || c === "all") openPanel(c);
  }

  // Reload PRs, apps and recaps now.
  refresh(cx) {
    this.recaps.clear();
    for (const kind of Object.keys(FACTS)) this.load(kind, cx, true);
  }

  // Load one fact (prs, apps) unless the shared value is younger than maxAge (or force).
  load(kind, cx, force, maxAge = FACTS[kind].fresh) {
    const f = FACTS[kind];
    const have = shared(f.key);
    if (!force && have && Date.now() - factAt(kind) < maxAge) return;
    if (inflight[kind] && Date.now() - inflight[kind] < 60_000) return;
    const started = inflight[kind] = Date.now();
    // No cx.notify() here: render calls this (the panel's catch-up), and
    // render may not ask for another render. The spinner shows from the
    // render that is running, or from the caller's own notify.
    this.loading[kind] = true;
    cx.spawn(async (cx) => {
      try {
        const value = await f.load();
        const { at: _a, ...now } = value, { at: _b, ...before } = shared(f.key) ?? {};
        checked[kind] = value.at;
        if (JSON.stringify(now) !== JSON.stringify(before)) setShared(f.key, value); // redraw only on a change
      } catch (e) {
        setShared(f.key, { ...(shared(f.key) ?? f.empty), at: Date.now(), errors: [{ repo: "", error: String(e).slice(0, 120) }] });
      } finally {
        if (inflight[kind] === started) inflight[kind] = 0;
        this.loading[kind] = false; cx.notify();
      }
    });
  }

  // Share an app on the tailnet (Tailscale Serve), then scan again.
  share(app, cx) {
    if (this.sharing.get(app.key)?.busy) return;
    this.sharing.set(app.key, { busy: true, error: null });
    cx.spawn(async (cx) => {
      let error = null;
      try { error = await shareApp(app); } catch (e) { error = String(e).slice(0, 160); }
      this.sharing.set(app.key, { busy: false, error });
      this.load("apps", cx, true);
      cx.notify();
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
    const prs = shared(PRS), apps = shared(APPS);
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
      .child(chip("apps", svg("mods/summary/globe.svg").size(12), apps?.apps?.length ?? 0, apps ? "apps" : "apps …", t.foreground))
      .child(chip("prs", svg("mods/summary/pr.svg").size(12), prs?.prs?.length ?? 0, prs ? "PRs" : "PRs …", t.foreground));
  }

  // ------------------------------------------------------------ the panel

  renderPanel(t, cx) {
    const shown = panelCopyShown();
    if (shown && this.stale) { this.stale = false; this.load("prs", cx, false); this.load("apps", cx, false); }
    if (!shown) this.stale = true;

    const b = buckets();
    const prs = shared(PRS), apps = shared(APPS);
    const view = shared(VIEW) ?? "all";
    const one = KINDS.includes(view) ? view : null;
    const lists = { ...b, apps: apps?.apps ?? [], prs: prs?.prs ?? [] };

    // Recaps only for rows on screen.
    if (shown) {
      const rows = one ? (one === "prs" || one === "apps" ? [] : lists[one]) : [...b.waiting.slice(0, SHOW.waiting), ...b.done.slice(0, SHOW.done), ...b.working.slice(0, SHOW.working)];
      this.loadRecaps(rows, cx);
    }

    const tile = (kind, n, color) => v_flex().id(`tile-${kind}`).flex_1().min_w(0).px(8).py(6).gap(1).rounded(8).cursor_pointer()
      .border(1).border_color(one === kind ? (color ?? t.accent) : t.border).bg(one === kind ? t.raised : t.background)
      .hover((s) => s.bg(t.raised))
      .on_click(() => setShared(VIEW, one === kind ? "all" : kind))
      .child(div().text_size(18).font_semibold().text_color(n ? color ?? t.foreground : t.dim).child(String(n)))
      .child(div().text_size(10.5).text_color(t.muted).truncate().child({ prs: "PRs", done: "Done", waiting: "Waiting", working: "Working", apps: "Apps" }[kind]));

    const header = h_flex().px(12).h(40).flex_none().items_center().gap(8).border_b(1).border_color(t.border)
      .child(div().text_size(13).font_semibold().child("Summary"))
      .child(div().flex_1().text_size(11).text_color(t.dim).truncate()
        .child(prs ? `${prs.repos} repo${prs.repos === 1 ? "" : "s"} · PRs ${age(factAt("prs"))}${age(factAt("prs")) === "now" ? "" : " ago"}` : "Loading PRs…"))
      .child(div().id("summary-refresh").size(24).flex().items_center().justify_center().rounded(5).cursor_pointer()
        .hover((s) => s.bg(t.raised)).text_color(t.muted).tooltip("Refresh PRs, apps and recaps")
        .on_click((_e, cx) => { this.refresh(cx); cx.notify(); })
        .child(this.loading.prs || this.loading.apps ? svg("mods/summary/spinner.svg").size(13).spin(900) : svg("mods/summary/refresh.svg").size(13)));

    const tiles = h_flex().px(12).pt(10).pb(4).gap(6).flex_none()
      .child(tile("waiting", b.waiting.length, t.blocked))
      .child(tile("done", b.done.length, t.done))
      .child(tile("working", b.working.length, t.working))
      .child(tile("apps", lists.apps.length, null))
      .child(tile("prs", lists.prs.length, null));

    const parts = [];
    for (const kind of one ? [one] : KINDS) {
      const rows = lists[kind];
      if (!one && !rows.length && kind !== "waiting") continue;
      parts.push(this.section(t, kind, rows, one ? Infinity : SHOW[kind], kind === "apps" ? apps : prs));
    }
    if (apps?.errors?.length && (!one || one === "apps")) {
      parts.push(v_flex().px(6).pt(8).gap(2).text_size(11).text_color(t.dim)
        .children(apps.errors.slice(0, 3).map((e, i) => div().id(`app-err-${i}`).truncate().child(`Apps on ${e.machine ?? "this Mac"}: ${e.error}`))));
    }
    if (prs?.errors?.length && (!one || one === "prs")) {
      parts.push(v_flex().px(6).pt(8).gap(2).text_size(11).text_color(t.dim)
        .children(prs.errors.slice(0, 4).map((e, i) => div().id(`err-${i}`).truncate().child(`${e.repo.split("/").pop() || "PRs"}: ${e.error}`))));
    }
    const body = v_flex().id("summary-body").flex_1().min_h(0).overflow_y_scrollbar().px(8).pb(12).children(parts);
    return v_flex().id("summary-panel").size_full().bg(t.surface).text_size(12.5).text_color(t.foreground)
      .child(header).child(tiles).child(body);
  }

  section(t, kind, rows, limit, facts) {
    const head = h_flex().px(6).pb(3).gap(6).items_center().text_size(11).font_semibold().text_color(t.muted)
        .child(TITLE[kind]).child(div().text_color(t.dim).child(String(rows.length)));
    const el = v_flex().id(`section-${kind}`).pt(10).gap(1).child(head);
    if (!rows.length) {
      return el.child(div().px(6).py(4).text_size(11.5).text_color(t.dim)
        .child(kind === "waiting" ? "Nothing needs you." : kind === "prs" ? (facts ? "No open PRs." : "Loading…")
          : kind === "apps" ? (facts ? "No web app runs in a space." : "Looking for apps…") : "None."));
    }
    const shown = rows.slice(0, limit);
    return el.children(shown.map((r) => (kind === "prs" ? this.prRow(t, r) : kind === "apps" ? this.appRow(t, r) : this.agentRow(t, r, kind))))
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

  // A web app: space, port and program; the tailnet URL, or a Share button
  // when the app listens on this machine only. A click opens the URL.
  appRow(t, a) {
    const share = this.sharing.get(a.key);
    const open = a.url ?? (a.machine ? null : a.local);
    const button = (id, label, tip, fn) => div().id(id).flex_none().px(6).h(18).flex().items_center().rounded(4)
      .border(1).border_color(t.border).text_size(10.5).text_color(t.muted).cursor_pointer()
      .hover((s) => s.bg(t.surface)).tooltip(tip)
      .on_click((_e, cx) => { cx.stop_propagation(); fn(cx); cx.notify(); })
      .child(label);
    const where = a.machine ? `${a.space} · ${a.machine}` : a.space;
    return v_flex().id(`app-${a.key}`).px(6).py(5).gap(2).rounded(6)
      .when(Boolean(open), (el) => el.cursor_pointer().hover((s) => s.bg(t.raised)).tooltip(`Open ${open}`)
        .on_click((_e, cx) => cx.open_url(open)))
      .child(h_flex().gap(7).items_center().min_w(0)
        .child(svg("mods/summary/globe.svg").size(13).flex_none().text_color(a.url ? t.done : t.dim))
        .child(div().flex_1().min_w(0).truncate().font_semibold().child(where))
        .child(div().flex_none().text_color(t.muted).child(`:${a.port}`))
        .child(div().flex_none().max_w(90).truncate().text_size(11).text_color(t.dim).child(a.program)))
      .child(h_flex().pl(20).gap(6).items_center().min_w(0)
        .child(div().flex_1().min_w(0).truncate().text_size(11).text_color(a.url ? t.muted : t.dim)
          .child(a.url ?? (share?.busy ? "Sharing on the tailnet…" : "This machine only")))
        .when(Boolean(a.url), (el) => el.child(button(`copy-${a.key}`, "Copy", `Copy ${a.url}`, (cx) => cx.write_to_clipboard(a.url))))
        .when(!a.url && !share?.busy, (el) => el.child(button(`share-${a.key}`, "Share",
          `Tailscale Serve: https://<this machine>:${a.port} → ${a.target}`, (cx) => this.share(a, cx)))))
      .when(Boolean(a.hostBlocked), (el) => el.child(div().pl(20).text_size(11).text_color(t.blocked)
        .child("Refuses the tailnet host name. Vite: server.allowedHosts: ['.ts.net']")))
      .when(Boolean(share?.error), (el) => el.child(div().pl(20).text_size(11).text_color(t.blocked).truncate().child(share.error)));
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
