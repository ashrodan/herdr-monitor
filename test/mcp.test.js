import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { createMcp, bearerOk, headerValue, MODERN } from '../lib/mcp.js';
import { herdrMcp, pendingQuestion, stateOf, chatText, isDialogKey } from '../lib/mcp-herdr.js';
import { loadSkills, frontMatter } from '../lib/mcp-skills.js';
import { mcpToken } from '../lib/mcp-token.js';

const SKILLS = path.join(path.dirname(new URL(import.meta.url).pathname), '..', 'mcp', 'skills');

// ---- A fake Herdr: two spaces, three agents and a dev server ----
function fakeMonitor() {
  const m = new EventEmitter();
  m.connected = true;
  m.layout = [
    { id: 'spA', name: 'app', folder: '/src/app', repo: '/src/app', branch: 'feat/x', tabs: [{ id: 'tb1', name: 'work', terminals: ['t1', 't2', 't9'] }] },
    { id: 'spB', name: 'docs', folder: '/src/docs', repo: '/src/docs', tabs: [{ id: 'tb2', name: '1', terminals: ['t3'] }] },
  ];
  const T = (address, space, tab, state, extra = {}) => ({ address, agent: 'claude-code', state: 'live', reported: true, status: { app: 'claude-code', state }, space, tab, pid: 100 + Number(address.slice(1)), changed_ms: 1000, title: `task ${address}`, ...extra });
  m.terminals = new Map([
    ['t1', T('t1', 'spA', 'tb1', 'blocked', { status: { app: 'claude-code', state: 'blocked', msg: 'Which db?' } })],
    ['t2', T('t2', 'spA', 'tb1', 'working')],
    ['t3', T('t3', 'spB', 'tb2', 'done')],
    ['t9', { address: 't9', agent: 'npm', state: 'live', status: null, space: 'spA', tab: 'tb1', title: 'vite' }],
  ]);
  m.calls = [];
  m.call = async (method, params, opts) => {
    m.calls.push({ method, params, opts });
    if (method === 'terminal.read') return { text: 'screen text\n\n' };
    if (method === 'terminal.wait') return { terminal: params.terminal, ended: true, reason: 'status', status: 'done', turn: 2, children: [] };
    return {};
  };
  return m;
}

const QUESTION = { kind: 'question', id: 'q1', name: 'AskUserQuestion', result: null, questions: [{ header: 'DB', question: 'Which db?', multi: false, options: [{ label: 'Postgres', description: '' }, { label: 'SQLite', description: 'small' }] }] };
const CHAT = { available: true, messages: [{ role: 'user', at: '2026-11-15T10:00:00Z', parts: [{ kind: 'text', text: 'Add a db' }] }, { role: 'assistant', at: '2026-11-15T10:01:00Z', parts: [{ kind: 'text', text: 'Which one?' }, { kind: 'tool', name: 'Bash', summary: 'ls' }, QUESTION] }] };

let server, base, monitor, answers, started;
const TOKEN = 'x'.repeat(64);

before(async () => {
  monitor = fakeMonitor();
  answers = []; started = [];
  const app = herdrMcp({
    monitor, skills: loadSkills(SKILLS), debounceMs: 10, now: () => 61000,
    chatFor: async () => CHAT,
    overview: { get: async () => ({ prs: [{ space: 'spA', spaceName: 'app', pr: { number: 7, headRefName: 'feat/x', title: 'X', isDraft: false, url: 'https://gh/7' }, readiness: { canMerge: false, reasons: ['1 check failing'] } }] }) },
    startSession: async (body) => { started.push(body); return { address: 't20', name: 'hm-claude-1', tab: 'tbN' }; },
    answer: async (addr, body) => { answers.push({ addr, body }); return { ok: true }; },
  });
  const mcp = createMcp({ name: 'test', version: '0', app });
  server = http.createServer((req, res) => {
    if (!bearerOk(req, TOKEN)) { res.writeHead(401); return res.end(); }
    const scope = req.url === '/mcp/spaces/app' ? { kind: 'space', space: 'spA' } : { kind: 'board' };
    mcp.handle(req, res, scope);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const meta = { 'io.modelcontextprotocol/protocolVersion': MODERN, 'io.modelcontextprotocol/clientInfo': { name: 't', version: '1' }, 'io.modelcontextprotocol/clientCapabilities': {} };
async function post(body, { path: p = '/mcp', headers = {}, token = TOKEN } = {}) {
  const res = await fetch(base + p, { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', authorization: `Bearer ${token}`, ...headers }, body: JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, headers: res.headers, body: text ? JSON.parse(text) : null };
}
const modern = (method, params = {}, id = 1) => post({ jsonrpc: '2.0', id, method, params: { ...params, _meta: meta } }, { headers: { 'mcp-protocol-version': MODERN, 'mcp-method': method } });
const legacy = (method, params = {}, opts) => post({ jsonrpc: '2.0', id: 1, method, params }, opts);
const call = async (name, args, opts) => (await legacy('tools/call', { name, arguments: args }, opts)).body.result;

// ---- Protocol ----

test('legacy initialize answers the asked version, with no session', async () => {
  const r = await legacy('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'grok', version: '1' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.result.protocolVersion, '2025-06-18');
  assert.ok(r.body.result.capabilities.tools);
  assert.ok(r.body.result.capabilities.extensions['io.modelcontextprotocol/skills']);
  assert.match(r.body.result.instructions, /herdr_overview/);
  assert.equal(r.headers.get('mcp-session-id'), null);
  const old = await legacy('initialize', { protocolVersion: '2024-11-05' });
  assert.equal(old.body.result.protocolVersion, '2025-11-25');
});

test('a notification gets 202, a GET 405, no token 401', async () => {
  assert.equal((await post({ jsonrpc: '2.0', method: 'notifications/initialized' })).status, 202);
  assert.equal((await fetch(`${base}/mcp`, { headers: { authorization: `Bearer ${TOKEN}` } })).status, 405);
  assert.equal((await legacy('ping', {}, { token: 'nope' })).status, 401);
});

test('modern server/discover, version and header errors', async () => {
  const d = await modern('server/discover');
  assert.equal(d.body.result.resultType, 'complete');
  assert.deepEqual(d.body.result.supportedVersions.slice(0, 2), [MODERN, '2025-11-25']);
  assert.equal(d.body.result._meta['io.modelcontextprotocol/serverInfo'].name, 'test');
  const tl = await modern('tools/list');
  assert.equal(tl.body.result.cacheScope, 'private');
  assert.equal(typeof tl.body.result.ttlMs, 'number');
  assert.equal((await legacy('tools/list')).body.result.ttlMs, undefined);

  const v = await post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: { _meta: { ...meta, 'io.modelcontextprotocol/protocolVersion': '2027-01-01' } } });
  assert.equal(v.status, 400);
  assert.equal(v.body.error.code, -32022);

  const h = await post({ jsonrpc: '2.0', id: 3, method: 'tools/list', params: { _meta: meta } }, { headers: { 'mcp-method': 'tools/call' } });
  assert.equal(h.status, 400);
  assert.equal(h.body.error.code, -32020);

  const u = await modern('nope/nope');
  assert.equal(u.status, 404);
  assert.equal(u.body.error.code, -32601);

  assert.equal(headerValue('=?base64?aGk=?='), 'hi');
  assert.equal(headerValue('plain'), 'plain');
});

test('a bad Origin is refused', async () => {
  const r = await legacy('ping', {}, { headers: { origin: 'https://evil.example' } });
  assert.equal(r.status, 403);
});

// ---- Scopes and tools ----

test('the board lists every tool; a space endpoint drops overview and the space argument', async () => {
  const board = (await legacy('tools/list')).body.result.tools;
  assert.ok(board.some((t) => t.name === 'herdr_overview'));
  assert.ok(board.find((t) => t.name === 'herdr_start').inputSchema.required.includes('space'));
  assert.equal(board.find((t) => t.name === 'herdr_read').annotations.readOnlyHint, true);
  assert.equal(board.find((t) => t.name === 'herdr_interrupt').annotations.destructiveHint, true);

  const space = (await legacy('tools/list', {}, { path: '/mcp/spaces/app' })).body.result.tools;
  assert.ok(!space.some((t) => t.name === 'herdr_overview'));
  assert.ok(!('space' in space.find((t) => t.name === 'herdr_start').inputSchema.properties));
});

test('herdr_overview puts the agent that needs you first', async () => {
  const r = await call('herdr_overview', {});
  assert.match(r.content[0].text, /3 agents in 2 spaces: 1 need you, 1 working, 1 done/);
  assert.ok(r.content[0].text.indexOf('Needs you') < r.content[0].text.indexOf('Working'));
  assert.match(r.content[0].text, /t1 · claude-code · blocked 1m · asks: Which db\?/);
});

test('herdr_space shows tabs, agents and programs', async () => {
  const r = await call('herdr_space', { space: 'APP' });
  assert.match(r.content[0].text, /Space app \(spA\)/);
  assert.match(r.content[0].text, /program t9 · npm · running/);
  const missing = await call('herdr_space', { space: 'nope' });
  assert.equal(missing.isError, true);
});

test('a space endpoint refuses agents of other spaces', async () => {
  const r = await call('herdr_read', { agent: 't3' }, { path: '/mcp/spaces/app' });
  assert.equal(r.isError, true);
  assert.match(r.content[0].text, /not in this space/);
});

test('herdr_read renders the chat and the waiting question', async () => {
  const r = await call('herdr_read', { agent: 't1' });
  assert.match(r.content[0].text, /WAITING for an answer/);
  assert.match(r.content[0].text, /2\. SQLite — small/);
  assert.match(r.content[0].text, /→ Bash: ls/);
  assert.match(r.content[0].text, /Next: herdr_answer/);
  const screen = await call('herdr_read', { agent: 't9', source: 'screen' });
  assert.match(screen.content[0].text, /screen text/);
});

test('herdr_send types and presses enter; herdr_answer picks 1-based options', async () => {
  monitor.calls.length = 0;
  const s = await call('herdr_send', { agent: 't2', text: 'run the tests' });
  assert.match(s.content[0].text, /Delivered to t2/);
  assert.deepEqual(monitor.calls[0].params, { terminal: 't2', text: 'run the tests', force: false });

  const a = await call('herdr_answer', { agent: 't1', option: 2 });
  assert.match(a.content[0].text, /Picked "SQLite"/);
  assert.deepEqual(answers.at(-1), { addr: 't1', body: { questions: [{ question: 'Which db?', options: 2, multi: false }], q: 0, option: 1 } });

  const bad = await call('herdr_answer', { agent: 't1', option: 9 });
  assert.equal(bad.isError, true);

  const k = await call('herdr_answer', { agent: 't9', keys: ['1', 'enter'] });
  assert.match(k.content[0].text, /Pressed 1 enter/);
  assert.equal((await call('herdr_answer', { agent: 't9', keys: ['ctrl+c'] })).isError, true);
});

test('herdr_wait gives hc room past the wait; herdr_start uses the scoped space', async () => {
  monitor.calls.length = 0;
  const w = await call('herdr_wait', { agent: 't2', timeout_s: 15 });
  assert.match(w.content[0].text, /t2 stopped: status \(done\)/);
  assert.equal(monitor.calls[0].opts.timeoutMs, 25000);

  const s = await call('herdr_start', { prompt: 'fix it' }, { path: '/mcp/spaces/app' });
  assert.match(s.content[0].text, /Started t20/);
  assert.deepEqual(started.at(-1), { space: 'spA', harness: 'claude', prompt: 'fix it' });
});

test('herdr_prs filters to the space; herdr_guide lists and reads skills', async () => {
  assert.match((await call('herdr_prs', {})).content[0].text, /#7 app \[feat\/x\] X — 1 check failing/);
  const g = await call('herdr_guide', {});
  assert.match(g.content[0].text, /herdr-coordinate/);
  const body = await call('herdr_guide', { topic: 'herdr-space' });
  assert.match(body.content[0].text, /# Work in one Herdr space/);
  assert.doesNotMatch(body.content[0].text, /^---/);
});

test('an unknown tool is a protocol error', async () => {
  const r = await legacy('tools/call', { name: 'rm_rf', arguments: {} });
  assert.equal(r.body.error.code, -32602);
});

// ---- Resources and skills ----

test('herdr://agents is scoped', async () => {
  const board = JSON.parse((await legacy('resources/read', { uri: 'herdr://agents' })).body.result.contents[0].text);
  assert.equal(board.agents.length, 3);
  const space = JSON.parse((await legacy('resources/read', { uri: 'herdr://agents' }, { path: '/mcp/spaces/app' })).body.result.contents[0].text);
  assert.deepEqual(space.agents.map((a) => a.address), ['t1', 't2']);
});

test('skills/list digests match the files; skills/get and resources/read', async () => {
  const r = await modern('skills/list');
  const coord = r.body.result.skills.find((s) => s.frontmatter.name === 'herdr-coordinate');
  assert.equal(coord.uri, 'skill://herdr-coordinate/SKILL.md');
  const bytes = fs.readFileSync(path.join(SKILLS, 'herdr-coordinate', 'SKILL.md'));
  assert.equal(coord.resources[0].digest, `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`);
  assert.equal(coord.resources[0].size, bytes.length);

  assert.equal((await modern('skills/get', { uri: coord.uri })).body.result.skill.uri, coord.uri);
  assert.equal((await modern('skills/get', { uri: 'skill://nope/SKILL.md' })).body.error.code, -32602);
  const read = await modern('resources/read', { uri: coord.uri });
  assert.equal(read.body.result.contents[0].text, bytes.toString('utf8'));
  const list = (await legacy('resources/list')).body.result.resources.map((x) => x.uri);
  assert.ok(list.includes('herdr://agents') && list.includes(coord.uri));
});

// ---- Subscriptions (2026-07-28) ----

test('subscriptions/listen acknowledges, then sends resources/updated on a state change', async () => {
  const ac = new AbortController();
  const res = await fetch(`${base}/mcp`, {
    method: 'POST', signal: ac.signal,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}`, 'mcp-protocol-version': MODERN, 'mcp-method': 'subscriptions/listen' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 42, method: 'subscriptions/listen', params: { _meta: meta, notifications: { resourceSubscriptions: ['herdr://agents', 'herdr://nope'], toolsListChanged: true } } }),
  });
  assert.equal(res.headers.get('content-type'), 'text/event-stream');
  const reader = res.body.getReader();
  let buf = '';
  const nextMessage = async () => {
    for (;;) {
      const i = buf.indexOf('\n\n');
      if (i >= 0) {
        const frame = buf.slice(0, i); buf = buf.slice(i + 2);
        const data = frame.split('\n').find((l) => l.startsWith('data: '));
        if (data) return JSON.parse(data.slice(6));
        continue;
      }
      const { value, done } = await reader.read();
      if (done) throw new Error('stream ended');
      buf += Buffer.from(value).toString('utf8');
    }
  };
  const ack = await nextMessage();
  assert.equal(ack.method, 'notifications/subscriptions/acknowledged');
  assert.deepEqual(ack.params.notifications, { resourceSubscriptions: ['herdr://agents'] });
  assert.equal(ack.params._meta['io.modelcontextprotocol/subscriptionId'], 42);

  // A title change alone is not news; a state change is.
  monitor.terminals.get('t2').title = 'new title';
  monitor.emit('event', 'terminal', {});
  monitor.terminals.get('t2').status = { app: 'claude-code', state: 'done' };
  setTimeout(() => monitor.emit('event', 'terminal', {}), 50);
  const upd = await nextMessage();
  assert.equal(upd.method, 'notifications/resources/updated');
  assert.equal(upd.params.uri, 'herdr://agents');
  assert.equal(upd.params._meta['io.modelcontextprotocol/subscriptionId'], 42);
  ac.abort();
});

// ---- Pure helpers ----

test('pendingQuestion stops at a person reply or an answered question', () => {
  assert.equal(pendingQuestion(CHAT.messages), QUESTION);
  assert.equal(pendingQuestion([...CHAT.messages, { role: 'user', parts: [{ kind: 'text', text: 'never mind' }] }]), null);
  assert.equal(pendingQuestion([{ role: 'assistant', parts: [{ ...QUESTION, result: 'Postgres' }] }]), null);
});

test('stateOf, chatText, isDialogKey, frontMatter, bearerOk', () => {
  assert.equal(stateOf({ state: 'finished' }), 'exited');
  assert.equal(stateOf({ state: 'live', status: null }), 'running');
  assert.equal(stateOf({ state: 'live', reported: true }), 'idle');
  assert.equal(stateOf({ state: 'live', status: { state: 'done' }, changed_ms: 10, seen_ms: 5 }), 'done');
  assert.equal(stateOf({ state: 'live', status: { state: 'done' }, changed_ms: 10, seen_ms: 12 }), 'idle');
  assert.match(chatText(CHAT.messages, 1), /^\[assistant 10:01\]/);
  assert.equal(isDialogKey('shift+tab'), true);
  assert.equal(isDialogKey('ctrl+c'), false);
  assert.deepEqual(frontMatter('---\nname: a\ndescription: "b: c"\n---\nbody'), { name: 'a', description: 'b: c' });
  assert.equal(bearerOk({ headers: { authorization: `Bearer ${TOKEN}` } }, TOKEN), true);
  assert.equal(bearerOk({ headers: { authorization: 'Bearer short' } }, TOKEN), false);
  assert.equal(bearerOk({ headers: {} }, TOKEN), false);
});

test('mcpToken makes a 600 file once, then reads it', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-token-'));
  const a = mcpToken(dir, {});
  assert.equal(a.created, true);
  assert.equal(fs.statSync(a.file).mode & 0o777, 0o600);
  const b = mcpToken(dir, {});
  assert.equal(b.token, a.token);
  assert.equal(b.created, false);
  assert.equal(mcpToken(dir, { HERDR_MCP_TOKEN: 'y'.repeat(40) }).token, 'y'.repeat(40));
  assert.throws(() => mcpToken(dir, { HERDR_MCP_TOKEN: 'short' }));
  fs.rmSync(dir, { recursive: true });
});
