// herdr-monitor: watch and answer Herdr (hc) agents from a browser.
import crypto from 'node:crypto';
import http from 'node:http';
import { spawn, execFile } from 'node:child_process';
import { openSync, closeSync } from 'node:fs';
import zlib from 'node:zlib';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { HcConnection, defaultSocket, summarize } from './lib/hc.js';
import { repoCommits, commitDetail } from './lib/git.js';
import { buildLayout, repoInfo, terminalsIn } from './lib/sidebar.js';
import { prStatus } from './lib/pr.js';
import { chatFor } from './lib/transcript.js';
import { loadVapid, Subscriptions, sendPush } from './lib/push.js';
import { answerKeys, currentQuestion } from './lib/answer.js';
import { AgentFeed, sseFrame } from './lib/agents.js';
import { herdrInfo, discover } from './lib/herdr.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 7400);
// Plain-http helper for Tailscale Serve: `tailscale serve --http=80` points
// here, and every request is sent on to the https address. 0 turns it off.
const REDIRECT_PORT = Number(process.env.REDIRECT_PORT ?? 7480);
const HTTPS_PORT = Number(process.env.HTTPS_PORT || PORT);
const SOCKET = process.env.HC_SOCKET || defaultSocket();
const HERDR_DIR = process.env.HERDR_APP_DIR || path.join(os.homedir(), '.config/herdr-app');

const terminals = new Map(); // address -> summary
const clients = new Set();   // SSE responses
let rpc = null;
let connected = false;
let layout = [];             // sidebar spaces, see lib/sidebar.js
const seedChanged = new Map(); // address -> ms, from Herdr's agent store
const herdrSeen = new Map();   // address -> ms when Herdr turned its "done" into idle

function broadcast(event, data) {
  const frame = sseFrame(event, data);
  for (const res of clients) res.write(frame);
  if (event === 'connection') for (const res of agentClients) res.write(frame);
  if (AGENT_TRIGGERS.has(event)) scheduleAgents();
}

// ---- Agent stream (the Android app) ----
// One small feed: an agent's state, name or status changed. See lib/agents.js.
const agentClients = new Set();
const agentFeed = new AgentFeed();
const AGENT_TRIGGERS = new Set(['terminal', 'removed', 'snapshot', 'layout']);
let agentsQueued = false;
function scheduleAgents() {
  if (agentsQueued || !agentClients.size) return;
  agentsQueued = true;
  queueMicrotask(syncAgents);
}
function syncAgents() {
  agentsQueued = false;
  for (const [event, data] of agentFeed.diff(terminals.values(), nameOf)) {
    const frame = sseFrame(event, data);
    for (const res of agentClients) res.write(frame);
  }
}

function snapshot() {
  return { connected, terminals: [...terminals.values()], spaces: layout };
}

async function readJsonFile(file) {
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

async function loadSeed() {
  const { at, seen } = parseStore(await readJsonFile(path.join(HERDR_DIR, 'data/agents/store.json')));
  for (const [k, v] of at) seedChanged.set(k, v);
  const changedSeen = [];
  for (const [k, v] of seen) if (herdrSeen.get(k) !== v) { herdrSeen.set(k, v); changedSeen.push(k); }
  return changedSeen;
}

function remember(s) {
  if (s.changed_ms == null) s.changed_ms = seedChanged.get(s.address) ?? null;
  s.seen_ms = herdrSeen.get(s.address) ?? null;
  return s;
}

let layoutTimer = null;
function scheduleLayout(delay = 300) {
  clearTimeout(layoutTimer);
  layoutTimer = setTimeout(refreshLayout, delay);
}

async function refreshLayout() {
  if (!rpc) return;
  for (const addr of await loadSeed()) {
    const t = terminals.get(addr);
    if (t) { remember(t); broadcast('terminal', t); }
  }
  try {
    const [{ value }, shared] = await Promise.all([
      rpc.call('doc.get', { name: 'herdr-app' }),
      readJsonFile(path.join(HERDR_DIR, 'shared.json')),
    ]);
    // Resolve git facts for each space's first terminal (or folder) first,
    // so buildLayout stays pure.
    const cwds = new Set();
    for (const sp of value?.spaces || []) {
      const first = terminalsIn(sp.tabs?.[0]?.root)[0];
      cwds.add(terminals.get(first)?.cwd || sp.folder);
    }
    const infos = new Map(await Promise.all([...cwds].map(async (c) => [c, await repoInfo(c)])));
    const next = buildLayout({ doc: value, terminals, colors: shared?.['space.colors'] || {}, repoOf: (c) => infos.get(c) });
    if (JSON.stringify(next) === JSON.stringify(layout)) return;
    layout = next;
    broadcast('layout', { spaces: layout });
  } catch (err) {
    console.error(`layout refresh failed: ${err.message}`);
  }
}

async function refreshOne(address) {
  try {
    const t = await rpc.call('terminal.get', { terminal: address });
    const s = remember(summarize(t.terminal || t));
    const prev = terminals.get(address);
    if (prev?.title && !s.title) s.title = prev.title;
    if (prev?.changed_ms && !s.changed_ms) s.changed_ms = prev.changed_ms;
    terminals.set(address, s);
    broadcast('terminal', s);
  } catch (err) {
    if (err.message === 'not_found') { terminals.delete(address); broadcast('removed', { address }); }
  }
}

const pendingRefresh = new Map();
function scheduleRefresh(address) {
  clearTimeout(pendingRefresh.get(address));
  pendingRefresh.set(address, setTimeout(() => { pendingRefresh.delete(address); refreshOne(address); }, 150));
}

function onEvent(ev) {
  const t = ev.terminal && terminals.get(ev.terminal);
  if (ev.type === 'missed') { resync(); return; }
  if (ev.type === 'title' && t) {
    t.title = ev.data?.title ?? null;
    broadcast('terminal', t);
  } else if (ev.type === 'status' && t) {
    const before = t.status?.state ?? null;
    Object.assign(t, { status: ev.data?.status ?? null, reported: ev.data?.reported ?? t.reported });
    const after = t.status?.state ?? null;
    if (after !== before) {
      t.changed_ms = ev.data?.status_ms ?? Date.now();
      onStateChange(t, before, after);
    }
    broadcast('terminal', t);
  } else if (ev.terminal) {
    scheduleRefresh(ev.terminal); // created, exited, labels... re-read the record
  }
  if (LAYOUT_EVENTS.has(ev.type)) scheduleLayout();
}

// Events that can move a terminal between spaces and tabs, or rename a space.
const LAYOUT_EVENTS = new Set(['doc', 'created', 'removed', 'finished', 'labels']);

// ---- Web Push ----
// Created on first use: the key pair and the subscriptions live in
// ~/.config/herdr-monitor (HERDR_MONITOR_DIR overrides), mode 600.
let push = null;
function pushState() {
  if (!push) push = { vapid: loadVapid(), subs: new Subscriptions() };
  return push;
}

// The name the UI shows: its own name, else its space (+ a named tab).
function nameOf(t) {
  if (t.name) return t.name;
  const sp = layout.find((s) => s.id === t.space);
  if (sp) {
    const tab = sp.tabs.find((tb) => tb.id === t.tab);
    return sp.name + (tab?.named ? ` · ${tab.name}` : '');
  }
  return t.where?.split(' / ')[0] || t.agent;
}

const PUSH_STATES = new Set(['blocked', 'done', 'error']);
const PUSH_GAP_MS = 30000;
const lastPush = new Map(); // `${addr}:${state}` -> ms

export function pushPayload(t, state, name) {
  const word = { blocked: 'needs you', done: 'done', error: 'error' }[state] || state;
  return {
    title: `${name} · ${word}`,
    body: t.status?.msg || t.title || (state === 'blocked' ? `Waiting at a ${t.status?.kind || 'dialog'}` : ''),
    tag: t.address,
    url: `/#${t.address}`,
  };
}

async function notifyAll(payload) {
  const { vapid, subs } = pushState();
  return Promise.all(subs.list().map((sub) => sendPush(sub, payload, { vapid, subscriptions: subs })));
}

// A live state change (not a snapshot or resync).
function onStateChange(t, before, after) {
  if (!PUSH_STATES.has(after)) return;
  const key = `${t.address}:${after}`;
  const now = Date.now();
  if (now - (lastPush.get(key) || 0) < PUSH_GAP_MS) return;
  lastPush.set(key, now);
  if (!pushState().subs.list().length) return;
  notifyAll(pushPayload(t, after, nameOf(t))).then((results) => {
    const failed = results.filter((r) => !r.ok);
    if (failed.length) console.error(`push: ${failed.length} of ${results.length} failed (${failed.map((r) => r.status || r.error?.message).join(', ')})`);
  });
}

let events = null;
async function resync() {
  events?.removeAllListeners('close');
  events?.close();
  events = new HcConnection(SOCKET);
  await events.connect();
  events.on('event', onEvent);
  events.on('close', () => setConnected(false));
  events.on('error', () => {});
  const sub = await events.call('events.subscribe', {});
  terminals.clear();
  for (const t of sub.terminals) terminals.set(t.address, remember(summarize(t)));
  await refreshLayout();
  broadcast('snapshot', { ...snapshot(), connected: true });
}

function setConnected(v) {
  if (connected === v) return;
  connected = v;
  broadcast('connection', { connected });
  if (!v) setTimeout(start, 2000);
}

async function start() {
  try {
    rpc = new HcConnection(SOCKET);
    await rpc.connect();
    await loadSeed();
    rpc.on('error', () => {});
    rpc.on('close', () => setConnected(false));
    await resync();
    setConnected(true);
    console.log(`connected to hc at ${SOCKET} (${terminals.size} terminals)`);
  } catch (err) {
    console.error(`hc connect failed (${err.message}); retrying in 2s`);
    setTimeout(start, 2000);
  }
}

// ---- HTTP ----

function send(res, code, body, type = 'application/json; charset=utf-8') {
  let data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  const headers = { 'content-type': type, 'cache-control': 'no-store' };
  // Big answers (a long transcript is ~700 KB) go gzipped: over Tailscale to a
  // phone that is most of the wait.
  if (data.length > 16 * 1024 && /\bgzip\b/.test(res.req?.headers['accept-encoding'] || '')) {
    data = zlib.gzipSync(data, { level: 4 });
    headers['content-encoding'] = 'gzip'; headers.vary = 'accept-encoding';
  }
  res.writeHead(code, headers);
  res.end(data);
}

function readJson(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > limit) { reject(new Error('too large')); req.destroy(); } });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch { reject(new Error('bad json')); } });
  });
}

// A request body as bytes, at most `limit` (hc's put takes 8 MB).
const MAX_UPLOAD = 8 * 1024 * 1024;
function readBytes(req, limit = MAX_UPLOAD) {
  return new Promise((resolve, reject) => {
    const parts = []; let size = 0;
    req.on('data', (c) => { size += c.length; if (size > limit) { reject(Object.assign(new Error('image too large (8 MB at most)'), { status: 413 })); req.destroy(); } else parts.push(c); });
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
}

// An image's type from its first bytes (not from what the browser claims).
export function imageKind(buf) {
  if (!buf || buf.length < 12) return null;
  if (buf[0] === 0x89 && buf.toString('latin1', 1, 4) === 'PNG') return 'png';
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpg';
  if (buf.toString('latin1', 0, 4) === 'GIF8') return 'gif';
  if (buf.toString('latin1', 0, 4) === 'RIFF' && buf.toString('latin1', 8, 12) === 'WEBP') return 'webp';
  return null;
}

// The uploaded image's file name: when, plus a little randomness.
export const uploadName = (kind, now = new Date()) =>
  `image-${now.toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15)}-${crypto.randomBytes(3).toString('hex')}.${kind}`;

// Only the page itself may drive agents: block cross-site POSTs.
export function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return false;
  try { return new URL(origin).host === req.headers.host; } catch { return false; }
}

// ---- Live terminal view ----
// `hc attach --observe` streams a terminal's real bytes (colours, status
// bar) and never types or resizes. It wants a tty, so macOS `script` gives it
// one. Each viewer gets its own process; it ends when the page goes away.
const HC_BIN = process.env.HC_BIN || 'hc';
const MAX_ATTACH = 8;
const attached = new Set();

// `script` echoes ^D and two backspaces when its input is not a tty: drop them.
export function stripScriptNoise(buf) {
  const noise = Buffer.from('^D\b\b');
  return buf.subarray(0, noise.length).equals(noise) ? buf.subarray(noise.length) : buf;
}

// Without HC_TERMINAL_ID: when the server itself runs in a Herdr terminal,
// hc would refuse to watch that terminal ("attached to itself"). The bytes go
// to script's pty, never back into the server's terminal, so there is no loop.
export function attachEnv(env) {
  const { HC_TERMINAL_ID, ...rest } = env;
  return { ...rest, HC_TERMINAL_SOCKET: SOCKET, TERM: 'xterm-256color' };
}

function attachStream(req, res, t) {
  if (attached.size >= MAX_ATTACH) return send(res, 429, { error: 'too many live views open' });
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
  res.write(`event: size\ndata: ${JSON.stringify({ cols: t.cols, rows: t.rows })}\n\n`);
  // stdin must be a file, not a pipe: script calls tcgetattr on it.
  const devnull = openSync('/dev/null', 'r');
  const child = spawn('script', ['-q', '/dev/null', HC_BIN, 'attach', '--observe', t.address], {
    stdio: [devnull, 'pipe', 'ignore'],
    env: attachEnv(process.env),
  });
  closeSync(devnull);
  attached.add(child);
  let first = true;
  child.stdout.on('data', (chunk) => {
    const data = first ? stripScriptNoise(chunk) : chunk;
    first = false;
    if (data.length) res.write(`data: ${data.toString('base64')}\n\n`);
  });
  const ping = setInterval(() => res.write(': ping\n\n'), 25000);
  const stop = () => { clearInterval(ping); attached.delete(child); child.kill('SIGTERM'); };
  child.on('exit', () => { res.write('event: end\ndata: {}\n\n'); res.end(); stop(); });
  child.on('error', (err) => { res.write(`event: end\ndata: ${JSON.stringify({ error: err.message })}\n\n`); res.end(); stop(); });
  req.on('close', stop);
}

function terminalOr404(res, address) {
  const t = terminals.get(address);
  if (!t) send(res, 404, { error: 'unknown terminal' });
  return t;
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};
// The Vite build of web/ (npm run build).
const PUBLIC = path.join(HERE, 'dist');

// Resolve a URL path to a file in dist/, or null. No traversal, fixed types.
export function publicFile(urlPath, root = PUBLIC) {
  let rel;
  try { rel = decodeURIComponent(urlPath); } catch { return null; }
  if (rel === '/') rel = '/index.html';
  if (rel.includes('\0') || !MIME[path.extname(rel)]) return null;
  const file = path.resolve(root, '.' + rel);
  return file.startsWith(root + path.sep) ? file : null;
}

async function serveStatic(req, res, url) {
  const file = publicFile(url.pathname);
  if (!file) return false;
  let body;
  try { body = await fs.readFile(file); } catch {
    if (url.pathname !== '/') return false;
    send(res, 503, 'The web UI is not built yet. Run `npm run build` (or `npm run dev`).', 'text/plain; charset=utf-8');
    return true;
  }
  // Vite puts a content hash in asset names: those can be cached for good.
  const immutable = url.pathname.startsWith('/assets/');
  res.writeHead(200, { 'content-type': MIME[path.extname(file)], 'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache' });
  res.end(body);
  return true;
}

// Open PRs across the spaces' repos, for the Overview. One gh call per repo,
// at most 4 at a time; cached 60 s (prStatus also caches 20 s per repo).
let overviewCache = null;
async function overview({ fresh = false } = {}) {
  if (!fresh && overviewCache && Date.now() - overviewCache.at < 60000) return overviewCache.value;
  const repos = new Map(); // repo -> first space that shows it
  for (const sp of layout) if (sp.repo && !sp.virtual && !repos.has(sp.repo)) repos.set(sp.repo, sp);
  const jobs = [...repos.entries()];
  const prs = [];
  const worker = async () => {
    for (let job; (job = jobs.shift());) {
      const [repo, sp] = job;
      const st = await prStatus(repo, { fresh }).catch(() => null);
      if (st?.pr && st.pr.state === 'OPEN') {
        prs.push({ space: sp.id, spaceName: sp.name, color: sp.color, branch: st.branch, pr: st.pr, readiness: st.readiness, counts: st.counts });
      }
    }
  };
  await Promise.all([1, 2, 3, 4].map(worker));
  prs.sort((a, b) => String(b.pr.updatedAt).localeCompare(String(a.pr.updatedAt)));
  const value = { prs, at: Date.now() };
  overviewCache = { at: Date.now(), value };
  return value;
}

// Herdr's program icons (~/.config/herdr-app/icons), read from the local
// install, not copied into this repo. `mono` icons draw with currentColor.
const ICONS_DIR = path.join(HERDR_DIR, 'icons');
let iconIndex = null;
async function icons() {
  if (iconIndex) return iconIndex;
  const names = [], mono = [];
  for (const f of await fs.readdir(ICONS_DIR).catch(() => [])) {
    if (!/^[a-z0-9-]+\.svg$/.test(f)) continue;
    const name = f.slice(0, -4);
    names.push(name);
    if ((await fs.readFile(path.join(ICONS_DIR, f), 'utf8')).includes('currentColor')) mono.push(name);
  }
  iconIndex = { names, mono };
  return iconIndex;
}

// What runs here (Herdr app, TUI, hc) and the herdr-monitor servers on the
// tailnet, for the Android app's server picker. Each cached 60 s.
let infoCache = null, discoverCache = null;
async function info() {
  if (!infoCache || Date.now() - infoCache.at > 60000) infoCache = { at: Date.now(), value: { app: 'herdr-monitor', host: tailnetName, herdr: await herdrInfo({ hcBin: HC_BIN }) } };
  return infoCache.value;
}
function tailscaleStatus() {
  return new Promise((resolve) => execFile('tailscale', ['status', '--json'], { timeout: 5000, maxBuffer: 8 * 1024 * 1024 }, (err, out) => {
    try { resolve(err ? null : JSON.parse(out)); } catch { resolve(null); }
  }));
}
async function discovered() {
  if (!discoverCache || Date.now() - discoverCache.at > 60000) {
    discoverCache = { at: Date.now(), value: await discover({ status: await tailscaleStatus(), port: HTTPS_PORT, self: await info() }) };
  }
  return discoverCache.value;
}

export const isDialogKey = (k) => typeof k === 'string' && /^(up|down|left|right|tab|shift\+tab|space|enter|esc|backspace|[a-z0-9])$/.test(k);

const routes = [
  ['GET', /^\/api\/icons$/, async (req, res) => {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'max-age=3600' });
    res.end(JSON.stringify(await icons()));
  }],
  ['GET', /^\/api\/icons\/([a-z0-9-]+)\.svg$/, async (req, res, [name]) => {
    let body;
    try { body = await fs.readFile(path.join(ICONS_DIR, `${name}.svg`)); } catch { return send(res, 404, { error: 'no such icon' }); }
    // An SVG opened on its own could run script: forbid everything but styles.
    res.writeHead(200, { 'content-type': 'image/svg+xml', 'cache-control': 'max-age=86400',
      'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'", 'x-content-type-options': 'nosniff' });
    res.end(body);
  }],
  ['GET', /^\/api\/sidebar$/, (req, res) => send(res, 200, { spaces: layout })],
  ['GET', /^\/api\/overview$/, async (req, res, m, url) => send(res, 200, await overview({ fresh: url.searchParams.has('fresh') }))],
  ['GET', /^\/api\/info$/, async (req, res) => send(res, 200, await info())],
  ['GET', /^\/api\/discover$/, async (req, res) => send(res, 200, await discovered())],
  ['GET', /^\/api\/health$/, (req, res) => send(res, 200, { ok: true, connected, socket: SOCKET, terminals: terminals.size })],
  ['GET', /^\/api\/terminals$/, (req, res) => send(res, 200, snapshot())],
  ['GET', /^\/api\/events$/, (req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
    res.write(`event: snapshot\ndata: ${JSON.stringify(snapshot())}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 25000);
    req.on('close', () => { clearInterval(ping); clients.delete(res); });
  }],
  ['GET', /^\/api\/agents\/stream$/, (req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
    syncAgents(); // the clients already here get any pending change first
    res.write(sseFrame('snapshot', { connected, agents: AgentFeed.snapshot(terminals.values(), nameOf) }));
    agentClients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 25000);
    req.on('close', () => { clearInterval(ping); agentClients.delete(res); });
  }],
  ['GET', /^\/api\/terminals\/([^/]+)\/attach$/, (req, res, [addr]) => {
    const t = terminalOr404(res, addr); if (!t) return;
    if (t.state !== 'live') return send(res, 409, { error: 'terminal is not live' });
    attachStream(req, res, t);
  }],
  ['GET', /^\/api\/terminals\/([^/]+)\/chat$/, async (req, res, [addr], url) => {
    const t = terminalOr404(res, addr); if (!t) return;
    const chat = await chatFor(t.pid);
    // The page polls: answer "no change" without the body when it has this version.
    if (chat.available && url.searchParams.get('v') === chat.version) return send(res, 200, { unchanged: true, version: chat.version });
    send(res, 200, chat);
  }],
  ['GET', /^\/api\/terminals\/([^/]+)\/read$/, async (req, res, [addr], url) => {
    if (!terminalOr404(res, addr)) return;
    const lines = Math.min(Number(url.searchParams.get('lines') || 200), 5000);
    send(res, 200, await rpc.call('terminal.read', { terminal: addr, lines }));
  }],
  ['GET', /^\/api\/terminals\/([^/]+)\/commits$/, async (req, res, [addr], url) => {
    const t = terminalOr404(res, addr); if (!t) return;
    send(res, 200, await repoCommits(t.cwd, { fresh: url.searchParams.has('fresh') }));
  }],
  ['GET', /^\/api\/terminals\/([^/]+)\/commits\/([0-9a-fA-F]{7,40})$/, async (req, res, [addr, sha]) => {
    const t = terminalOr404(res, addr); if (!t) return;
    send(res, 200, await commitDetail(t.cwd, sha));
  }],
  ['GET', /^\/api\/terminals\/([^/]+)\/status$/, async (req, res, [addr], url) => {
    const t = terminalOr404(res, addr); if (!t) return;
    send(res, 200, await prStatus(t.cwd, { fresh: url.searchParams.has('fresh') }));
  }],
  ['GET', /^\/api\/push\/key$/, (req, res) => send(res, 200, { publicKey: pushState().vapid.publicKey })],
  ['POST', /^\/api\/push\/subscribe$/, async (req, res) => {
    const { subscription } = await readJson(req);
    try { pushState().subs.add(subscription); } catch (err) { return send(res, 400, { error: err.message }); }
    send(res, 200, { ok: true, count: pushState().subs.list().length });
  }],
  ['POST', /^\/api\/push\/unsubscribe$/, async (req, res) => {
    const { endpoint } = await readJson(req);
    send(res, 200, { ok: true, removed: typeof endpoint === 'string' && pushState().subs.remove(endpoint) });
  }],
  ['POST', /^\/api\/push\/test$/, async (req, res) => {
    const results = await notifyAll({ title: 'Herdr Monitor', body: 'Notifications work.', tag: 'test', url: '/' });
    send(res, 200, { sent: results.filter((r) => r.ok).length, failed: results.filter((r) => !r.ok).map((r) => r.status || r.error?.message) });
  }],
  ['POST', /^\/api\/terminals\/([^/]+)\/send$/, async (req, res, [addr]) => {
    if (!terminalOr404(res, addr)) return;
    const { text, force } = await readJson(req);
    if (typeof text !== 'string' || !text.trim()) return send(res, 400, { error: 'text required' });
    // force: type even while the agent waits at a dialog (the page asks for it
    // only then). Without it, hc refuses to type into a blocked agent.
    send(res, 200, await rpc.call('terminal.send', { terminal: addr, text, force: force === true }));
  }],
  // Navigation keys only, to answer a dialog: arrows, tab, space, enter, esc,
  // backspace, and single letters or digits (option shortcuts).
  ['POST', /^\/api\/terminals\/([^/]+)\/keys$/, async (req, res, [addr]) => {
    if (!terminalOr404(res, addr)) return;
    const { keys } = await readJson(req);
    if (!Array.isArray(keys) || !keys.length || keys.length > 20 || !keys.every(isDialogKey)) return send(res, 400, { error: 'keys: up to 20 of up, down, left, right, tab, shift+tab, space, enter, esc, backspace, a-z, 0-9' });
    send(res, 200, await rpc.call('terminal.keys', { terminal: addr, keys }));
  }],
  // Tap an option of the question form on screen: read the screen to find
  // which question it shows, then press the keys a person would.
  // Body: {questions: [{question, options, multi}], q, option} or {questions, action: 'submit'}.
  ['POST', /^\/api\/terminals\/([^/]+)\/answer$/, async (req, res, [addr]) => {
    if (!terminalOr404(res, addr)) return;
    const { questions, q = 0, option = 0, action = 'pick' } = await readJson(req);
    if (!Array.isArray(questions) || !questions.length || questions.length > 10) return send(res, 400, { error: 'questions required' });
    const qs = questions.map((x) => ({ question: String(x?.question || ''), options: Number(x?.options) | 0, multi: x?.multi === true }));
    if (action !== 'submit' && !(Number.isInteger(q) && q >= 0 && q < qs.length && Number.isInteger(option) && option >= 0 && option < qs[q].options)) return send(res, 400, { error: 'no such option' });
    const screen = await rpc.call('terminal.read', { terminal: addr, lines: 80 });
    const current = currentQuestion(String(screen?.text ?? ''), qs.map((x) => x.question));
    if (current < 0) return send(res, 409, { error: 'the question form is not on screen' });
    const keys = answerKeys({ current, total: qs.length, q, option, rows: (qs[q]?.options || 0) + 1, multi: qs[q]?.multi, action });
    for (let i = 0; i < keys.length; i += 20) await rpc.call('terminal.keys', { terminal: addr, keys: keys.slice(i, i + 20) });
    send(res, 200, { ok: true, keys });
  }],
  // An image for the agent: hc keeps it beside the terminal (on its machine,
  // removed with the terminal) and returns its path; the page puts the path
  // in the reply. Types nothing itself.
  ['POST', /^\/api\/terminals\/([^/]+)\/upload$/, async (req, res, [addr]) => {
    if (!terminalOr404(res, addr)) return;
    let buf;
    try { buf = await readBytes(req); } catch (e) { return send(res, e.status || 400, { error: e.message }); }
    const kind = imageKind(buf);
    if (!kind) return send(res, 415, { error: 'not a PNG, JPEG, GIF or WebP image' });
    const r = await rpc.call('terminal.put', { terminal: addr, name: uploadName(kind), data: buf.toString('base64') });
    send(res, 200, { path: r?.path ?? r, bytes: buf.length });
  }],
  ['POST', /^\/api\/terminals\/([^/]+)\/interrupt$/, async (req, res, [addr]) => {
    if (!terminalOr404(res, addr)) return;
    send(res, 200, await rpc.call('terminal.interrupt', { terminal: addr, timeout: 5 }));
  }],
];

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'POST' && !sameOrigin(req)) return send(res, 403, { error: 'cross-origin request refused' });
  for (const [method, re, handler] of routes) {
    const m = req.method === method && url.pathname.match(re);
    if (!m) continue;
    try { return await handler(req, res, m.slice(1).map(decodeURIComponent), url); }
    catch (err) { return send(res, 502, { error: err.message, rpc: err.rpc ?? null }); }
  }
  if (req.method === 'GET' && !url.pathname.startsWith('/api/') && await serveStatic(req, res, url)) return;
  send(res, 404, { error: 'not found' });
});

// This machine's full tailnet name (box.tailXXXX.ts.net), for the short
// MagicDNS name: the https certificate is only valid for the full name.
let tailnetName = null;
execFile('tailscale', ['status', '--json'], { timeout: 5000, maxBuffer: 8 * 1024 * 1024 }, (err, out) => {
  if (err) return;
  try { tailnetName = JSON.parse(out).Self?.DNSName?.replace(/\.$/, '').toLowerCase() || null; } catch { /* no tailscale */ }
});

// Where an http request should go: same host name, https, the app's port.
export function httpsLocation(hostHeader, url, httpsPort = HTTPS_PORT, fullName = tailnetName) {
  let host = String(hostHeader || '').replace(/:\d+$/, '').toLowerCase();
  if (!/^[a-z0-9.-]+$/.test(host)) return null; // only a plain host name
  if (!host.includes('.') && fullName?.startsWith(host + '.')) host = fullName;
  const path = String(url || '/').startsWith('/') ? url : '/';
  return `https://${host}${httpsPort === 443 ? '' : `:${httpsPort}`}${path}`;
}

const redirector = http.createServer((req, res) => {
  const to = httpsLocation(req.headers.host, req.url);
  if (!to) { res.writeHead(400, { 'content-type': 'text/plain' }); return res.end('bad host'); }
  res.writeHead(308, { location: to, 'cache-control': 'no-store' });
  res.end();
});

if (import.meta.url === `file://${process.argv[1]}`) {
  if (REDIRECT_PORT) redirector.listen(REDIRECT_PORT, HOST, () => console.log(`http → https redirect on http://${HOST}:${REDIRECT_PORT}`));
  server.listen(PORT, HOST, () => console.log(`herdr-monitor on http://${HOST}:${PORT}`));
  start();
  setInterval(() => { if (connected) refreshLayout(); }, 60000); // cwd and branch drift
}
