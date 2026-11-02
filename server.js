// herdr-monitor: watch and answer Herdr (hc) agents from a browser.
import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { HcConnection, defaultSocket, summarize } from './lib/hc.js';
import { repoCommits, commitDetail } from './lib/git.js';
import { buildLayout, repoInfo, terminalsIn } from './lib/sidebar.js';
import { prStatus } from './lib/pr.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 7400);
const SOCKET = process.env.HC_SOCKET || defaultSocket();
const HERDR_DIR = process.env.HERDR_APP_DIR || path.join(os.homedir(), '.config/herdr-app');

const terminals = new Map(); // address -> summary
const clients = new Set();   // SSE responses
let rpc = null;
let connected = false;
let layout = [];             // sidebar spaces, see lib/sidebar.js
const seedChanged = new Map(); // address -> ms, from Herdr's agent store

function broadcast(event, data) {
  const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(frame);
}

function snapshot() {
  return { connected, terminals: [...terminals.values()], spaces: layout };
}

async function readJsonFile(file) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')); } catch { return null; }
}

// Herdr's own record of when each agent last changed state: a seed for row
// ages when hc has no status_ms. `changed` may be a JSON string.
async function loadSeed() {
  const store = await readJsonFile(path.join(HERDR_DIR, 'data/agents/store.json'));
  let changed = store?.changed;
  if (typeof changed === 'string') { try { changed = JSON.parse(changed); } catch { changed = null; } }
  for (const [addr, v] of Object.entries(changed || {})) if (Number.isFinite(v?.at)) seedChanged.set(addr, v.at);
}

function remember(s) {
  if (s.changed_ms == null) s.changed_ms = seedChanged.get(s.address) ?? null;
  return s;
}

let layoutTimer = null;
function scheduleLayout(delay = 300) {
  clearTimeout(layoutTimer);
  layoutTimer = setTimeout(refreshLayout, delay);
}

async function refreshLayout() {
  if (!rpc) return;
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

// A live state change (not a snapshot or resync). Push notifications hook in here.
function onStateChange(t, before, after) {}

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
  res.writeHead(code, { 'content-type': type, 'cache-control': 'no-store' });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

function readJson(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > limit) { reject(new Error('too large')); req.destroy(); } });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch { reject(new Error('bad json')); } });
  });
}

// Only the page itself may drive agents: block cross-site POSTs.
export function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return false;
  try { return new URL(origin).host === req.headers.host; } catch { return false; }
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

const routes = [
  ['GET', /^\/api\/sidebar$/, (req, res) => send(res, 200, { spaces: layout })],
  ['GET', /^\/api\/health$/, (req, res) => send(res, 200, { ok: true, connected, socket: SOCKET, terminals: terminals.size })],
  ['GET', /^\/api\/terminals$/, (req, res) => send(res, 200, snapshot())],
  ['GET', /^\/api\/events$/, (req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
    res.write(`event: snapshot\ndata: ${JSON.stringify(snapshot())}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 25000);
    req.on('close', () => { clearInterval(ping); clients.delete(res); });
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
  ['POST', /^\/api\/terminals\/([^/]+)\/send$/, async (req, res, [addr]) => {
    if (!terminalOr404(res, addr)) return;
    const { text } = await readJson(req);
    if (typeof text !== 'string' || !text.trim()) return send(res, 400, { error: 'text required' });
    // No `force`: hc refuses to type into an agent at an approval dialog.
    send(res, 200, await rpc.call('terminal.send', { terminal: addr, text }));
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

if (import.meta.url === `file://${process.argv[1]}`) {
  server.listen(PORT, HOST, () => console.log(`herdr-monitor on http://${HOST}:${PORT}`));
  start();
  setInterval(() => { if (connected) refreshLayout(); }, 60000); // cwd and branch drift
}
