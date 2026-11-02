// herdr-monitor: watch and answer Herdr (hc) agents from a browser.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { HcConnection, defaultSocket, summarize } from './lib/hc.js';
import { repoCommits, commitDetail } from './lib/git.js';
import { prStatus } from './lib/pr.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 7400);
const SOCKET = process.env.HC_SOCKET || defaultSocket();

const terminals = new Map(); // address -> summary
const clients = new Set();   // SSE responses
let rpc = null;
let connected = false;

function broadcast(event, data) {
  const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(frame);
}

function snapshot() {
  return { connected, terminals: [...terminals.values()] };
}

async function refreshOne(address) {
  try {
    const t = await rpc.call('terminal.get', { terminal: address });
    const s = summarize(t.terminal || t);
    const prev = terminals.get(address);
    if (prev?.title && !s.title) s.title = prev.title;
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
    Object.assign(t, { status: ev.data?.status ?? null, reported: ev.data?.reported ?? t.reported });
    broadcast('terminal', t);
  } else if (ev.terminal) {
    scheduleRefresh(ev.terminal); // created, exited, labels... re-read the record
  }
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
  for (const t of sub.terminals) terminals.set(t.address, summarize(t));
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

const routes = [
  ['GET', /^\/$/, async (req, res) => send(res, 200, await fs.readFile(path.join(HERE, 'public/index.html')), 'text/html; charset=utf-8')],
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
  send(res, 404, { error: 'not found' });
});

if (import.meta.url === `file://${process.argv[1]}`) {
  server.listen(PORT, HOST, () => console.log(`herdr-monitor on http://${HOST}:${PORT}`));
  start();
}
