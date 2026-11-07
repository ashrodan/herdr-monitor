// What the server knows about Herdr: the terminals, the sidebar layout and
// the link to hc. Two hc links: `rpc` for calls, `events` for the event
// subscription. Either one closing means hc went away: close both, tell the
// clients, and connect again every retryMs until hc is back.
//
// The Monitor does no HTTP. It emits:
//   'event' (name, data)       for the SSE clients: terminal, removed,
//                              snapshot, layout, connection
//   'state' (t, before, after) a live status change (for Web Push)
//   'log'   (message)
import path from 'node:path';
import fs from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import { HcConnection, summarize } from './hc.js';
import { buildLayout, repoInfo as gitRepoInfo, terminalsIn } from './sidebar.js';
import { listenOf, machineHost, portFromText } from './apps.js';

// Spinner frames (braille) that agents put in their titles, about 12 a
// second. Not a change anyone needs to see: drop them, so a title event
// that only moves the spinner sends nothing.
const BRAILLE = /[⠀-⣿]/g;
export const cleanTitle = (title) => (title == null ? null : String(title).replace(BRAILLE, '').trimStart());

export async function readJsonFile(file) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')); } catch { return null; }
}

// Herdr's own record of when each agent last changed state: a seed for row
// ages when hc has no status_ms. `changed` may be a JSON string.
// Herdr also records when you looked at a finished agent (s: "idle"):
// sent as seen_ms, so "Done" here matches Herdr's sidebar.
export function parseStore(store) {
  let changed = store?.changed;
  if (typeof changed === 'string') { try { changed = JSON.parse(changed); } catch { changed = null; } }
  const at = new Map(), seen = new Map();
  for (const [addr, v] of Object.entries(changed || {})) {
    if (!Number.isFinite(v?.at)) continue;
    at.set(addr, v.at);
    if (v.s === 'idle') seen.set(addr, v.at);
  }
  return { at, seen };
}

// Events that can move a terminal between spaces and tabs, or rename a space.
const LAYOUT_EVENTS = new Set(['doc', 'created', 'removed', 'finished', 'labels']);

export class Monitor extends EventEmitter {
  constructor({
    socket,
    herdrDir,
    connect = (sock) => new HcConnection(sock).connect(),
    readJson = readJsonFile,
    repoInfo = gitRepoInfo,
    retryMs = 2000,
    coalesceMs = 250,   // at most one 'terminal' event per terminal in this time
    refreshMs = 150,    // wait for a burst of hc events before terminal.get
    layoutMs = 300,
  } = {}) {
    super();
    Object.assign(this, { socket, herdrDir, connect, readJson, repoInfo, retryMs, coalesceMs, refreshMs, layoutMs });
    this.terminals = new Map(); // address -> summary
    this.layout = [];
    this.connected = false;
    this.rpc = null;
    this.events = null;
    this.stopped = false;
    this.seedChanged = new Map(); // address -> ms, from Herdr's agent store
    this.herdrSeen = new Map();   // address -> ms Herdr turned its "done" into idle
    this.screenListen = new Map(); // address -> {scheme, port}, read off the screen
    this.screenRead = new Map();   // address -> ms
    this.machines = [];
    this.peers = [];
    this.timers = { retry: null, layout: null };
    this.refreshing = new Map(); // address -> timer
    this.lastSent = new Map();   // address -> ms of the last 'terminal' event
    this.trailing = new Map();   // address -> timer for a held 'terminal' event
  }

  snapshot() {
    return { connected: this.connected, terminals: [...this.terminals.values()], spaces: this.layout };
  }

  call(method, params) {
    if (!this.rpc) return Promise.reject(new Error('hc not connected'));
    return this.rpc.call(method, params);
  }

  // ---- The hc link ----

  async start() {
    this.stopped = false;
    this.#closeLinks();
    try {
      const rpc = await this.connect(this.socket);
      rpc.on('error', () => {});
      rpc.on('close', () => this.#lost());
      this.rpc = rpc;
      await this.loadSeed();
      await this.resync();
      this.#setConnected(true);
      this.emit('log', `connected to hc at ${this.socket} (${this.terminals.size} terminals)`);
    } catch (err) {
      this.emit('log', `hc connect failed (${err.message}); retrying in ${this.retryMs / 1000}s`);
      this.#closeLinks();
      this.#setConnected(false);
      this.#retry();
    }
  }

  stop() {
    this.stopped = true;
    for (const t of Object.values(this.timers)) clearTimeout(t);
    for (const m of [this.refreshing, this.trailing]) { for (const t of m.values()) clearTimeout(t); m.clear(); }
    this.#closeLinks();
  }

  // A fresh event subscription and a full snapshot: at start, and when hc
  // says events were missed.
  async resync() {
    this.events?.close();
    const events = await this.connect(this.socket);
    events.on('error', () => {});
    events.on('close', () => this.#lost());
    events.on('event', (ev) => this.onEvent(ev));
    this.events = events;
    const sub = await events.call('events.subscribe', {});
    this.terminals.clear();
    for (const t of sub.terminals || []) this.terminals.set(t.address, this.#remember(this.#clean(summarize(t))));
    await this.refreshLayout();
    for (const t of this.trailing.values()) clearTimeout(t);
    this.trailing.clear();
    this.emit('event', 'snapshot', { ...this.snapshot(), connected: true });
  }

  #closeLinks() {
    this.rpc?.close();
    this.events?.close();
    this.rpc = this.events = null;
  }

  #lost() {
    this.#closeLinks();
    this.#setConnected(false);
    this.#retry();
  }

  #retry() {
    if (this.stopped || this.timers.retry) return;
    this.timers.retry = setTimeout(() => { this.timers.retry = null; this.start(); }, this.retryMs);
  }

  #setConnected(v) {
    if (this.connected === v) return;
    this.connected = v;
    this.emit('event', 'connection', { connected: v });
  }

  // ---- Events from hc ----

  onEvent(ev) {
    const t = ev.terminal && this.terminals.get(ev.terminal);
    if (ev.type === 'missed') {
      this.resync().catch((err) => { this.emit('log', `resync failed: ${err.message}`); this.#lost(); });
      return;
    }
    if (ev.type === 'title' && t) {
      const title = cleanTitle(ev.data?.title);
      if (title === t.title) return; // only the spinner moved
      t.title = title;
      if (!t.listen) { const l = portFromText(title); if (l) { t.listen = l; this.#remember(t); } }
      this.#sendTerminal(t);
    } else if (ev.type === 'status' && t) {
      const status = ev.data?.status ?? null;
      const reported = ev.data?.reported ?? t.reported;
      if (JSON.stringify(status) === JSON.stringify(t.status) && reported === t.reported) return;
      const before = t.status?.state ?? null;
      Object.assign(t, { status, reported });
      const after = t.status?.state ?? null;
      if (after !== before) {
        t.changed_ms = ev.data?.status_ms ?? Date.now();
        this.emit('state', t, before, after);
      }
      this.#sendTerminal(t);
    } else if (ev.terminal) {
      this.#scheduleRefresh(ev.terminal); // created, exited, labels... re-read the record
    }
    if (LAYOUT_EVENTS.has(ev.type)) this.scheduleLayout();
  }

  // Send a terminal at once, unless one went out for it in the last
  // coalesceMs: then hold it, and send the newest copy when that time is up.
  #sendTerminal(t) {
    const a = t.address;
    if (this.trailing.has(a)) return; // the held send reads this.terminals then
    const wait = this.coalesceMs - (Date.now() - (this.lastSent.get(a) || 0));
    if (wait <= 0) {
      this.lastSent.set(a, Date.now());
      this.emit('event', 'terminal', t);
      return;
    }
    this.trailing.set(a, setTimeout(() => {
      this.trailing.delete(a);
      const now = this.terminals.get(a);
      if (!now) return;
      this.lastSent.set(a, Date.now());
      this.emit('event', 'terminal', now);
    }, wait));
  }

  #removed(address) {
    this.terminals.delete(address);
    clearTimeout(this.trailing.get(address));
    this.trailing.delete(address);
    this.lastSent.delete(address);
    this.emit('event', 'removed', { address });
  }

  #scheduleRefresh(address) {
    clearTimeout(this.refreshing.get(address));
    this.refreshing.set(address, setTimeout(() => { this.refreshing.delete(address); this.refreshOne(address); }, this.refreshMs));
  }

  async refreshOne(address) {
    try {
      const r = await this.call('terminal.get', { terminal: address });
      const s = this.#remember(this.#clean(summarize(r.terminal || r)));
      const prev = this.terminals.get(address);
      if (prev?.title && !s.title) s.title = prev.title;
      if (prev?.changed_ms && !s.changed_ms) s.changed_ms = prev.changed_ms;
      if (prev?.listen && !s.listen) s.listen = prev.listen;
      this.terminals.set(address, s);
      this.#sendTerminal(s);
    } catch (err) {
      if (err.message === 'not_found') this.#removed(address);
    }
  }

  #clean(s) { s.title = cleanTitle(s.title); return s; }

  // ---- Layout and seeds ----

  scheduleLayout(delay = this.layoutMs) {
    clearTimeout(this.timers.layout);
    this.timers.layout = setTimeout(() => this.refreshLayout(), delay);
  }

  async refreshLayout() {
    if (!this.rpc) return;
    for (const addr of await this.loadSeed()) {
      const t = this.terminals.get(addr);
      if (t) { this.#remember(t); this.#sendTerminal(t); }
    }
    try {
      const [{ value }, shared] = await Promise.all([
        this.call('doc.get', { name: 'herdr-app' }),
        this.readJson(path.join(this.herdrDir, 'shared.json')),
      ]);
      // Resolve git facts for each space's first terminal (or folder) first,
      // so buildLayout stays pure.
      const cwds = new Set();
      for (const sp of value?.spaces || []) {
        const first = terminalsIn(sp.tabs?.[0]?.root)[0];
        cwds.add(this.terminals.get(first)?.cwd || sp.folder);
      }
      const infos = new Map(await Promise.all([...cwds].map(async (c) => [c, await this.repoInfo(c)])));
      const next = buildLayout({ doc: value, terminals: this.terminals, colors: shared?.['space.colors'] || {}, repoOf: (c) => infos.get(c) });
      if (JSON.stringify(next) === JSON.stringify(this.layout)) return;
      this.layout = next;
      this.emit('event', 'layout', { spaces: this.layout });
    } catch (err) {
      this.emit('log', `layout refresh failed: ${err.message}`);
    }
  }

  // Read Herdr's agent store. Returns the addresses whose seen_ms changed.
  async loadSeed() {
    const { at, seen } = parseStore(await this.readJson(path.join(this.herdrDir, 'data/agents/store.json')));
    for (const [k, v] of at) this.seedChanged.set(k, v);
    const changedSeen = [];
    for (const [k, v] of seen) if (this.herdrSeen.get(k) !== v) { this.herdrSeen.set(k, v); changedSeen.push(k); }
    return changedSeen;
  }

  #remember(s) {
    if (s.changed_ms == null) s.changed_ms = this.seedChanged.get(s.address) ?? null;
    s.seen_ms = this.herdrSeen.get(s.address) ?? null;
    if (!s.listen && this.screenListen.has(s.address)) s.listen = this.screenListen.get(s.address);
    if (s.listen) s.listen = { ...s.listen, host: machineHost(s.address, this.machines, this.peers) };
    return s;
  }

  // The name the UI shows: its own name, else its space (+ a named tab).
  nameOf(t) {
    if (t.name) return t.name;
    const sp = this.layout.find((s) => s.id === t.space);
    if (sp) {
      const tab = sp.tabs.find((tb) => tb.id === t.tab);
      return sp.name + (tab?.named ? ` · ${tab.name}` : '');
    }
    return t.where?.split(' / ')[0] || t.agent;
  }

  // ---- Running apps (lib/apps.js) ----

  setMachines(machines, peers) { this.machines = machines; this.peers = peers; }

  // A shell's dev server often prints its URL but has no --port: read the
  // first screen lines of live terminals without a status, at most 6 a
  // call, each again after 2 minutes.
  async scanApps() {
    if (!this.rpc) return;
    const due = [...this.terminals.values()].filter((t) => t.state === 'live' && !t.status && !t.listen && Date.now() - (this.screenRead.get(t.address) || 0) > 120000).slice(0, 6);
    for (const t of due) {
      this.screenRead.set(t.address, Date.now());
      try {
        const r = await this.call('terminal.read', { terminal: t.address, lines: 60 });
        const l = listenOf({ screen: String(r?.text ?? '') });
        if (l) { this.screenListen.set(t.address, l); this.#remember(t); this.#sendTerminal(t); }
      } catch { /* gone */ }
    }
  }
}
