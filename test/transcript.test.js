import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { parseTranscript, toolSummary, projectSlug, sessionFor, chatFor, readTail, remoteChatFor } from '../lib/transcript.js';
import { execFile } from 'node:child_process';

const rows = [
  { type: 'attachment', attachment: { type: 'hook_success' } },
  { type: 'user', uuid: 'u1', timestamp: 't1', message: { role: 'user', content: 'Fix the build<system-reminder>secret context</system-reminder>' } },
  { type: 'user', uuid: 'm1', isMeta: true, message: { role: 'user', content: 'meta, not shown' } },
  { type: 'assistant', uuid: 'a1', message: { content: [{ type: 'thinking', thinking: 'hmm' }] } },
  { type: 'assistant', uuid: 'a2', message: { content: [{ type: 'text', text: 'Running the tests.' }] } },
  { type: 'assistant', uuid: 'a3', message: { content: [{ type: 'tool_use', id: 'tu1', name: 'Bash', input: { command: 'npm test\nmore', description: 'Run tests' } }] } },
  { type: 'user', uuid: 'r1', message: { content: [{ type: 'tool_result', tool_use_id: 'tu1', content: 'x'.repeat(5000), is_error: true }] } },
  { type: 'assistant', uuid: 'side', isSidechain: true, message: { content: [{ type: 'text', text: 'sub-agent, not shown' }] } },
  { type: 'user', uuid: 's1', promptSource: 'system', message: { content: '<task-notification><summary>CI finished</summary></task-notification>' } },
  { type: 'user', uuid: 'c1', message: { content: '<command-name>/model</command-name><command-args>opus</command-args>' } },
  { type: 'user', uuid: 'u2', message: { content: [{ type: 'text', text: 'Look' }, { type: 'image' }] } },
];

test('parseTranscript builds chat messages', () => {
  const m = parseTranscript([...rows.map((r) => JSON.stringify(r)), 'not json', '']);
  assert.deepEqual(m.map((x) => x.role), ['user', 'assistant', 'system', 'user', 'user']);
  assert.equal(m[0].parts[0].text, 'Fix the build');
  assert.deepEqual(m[1].parts.map((p) => p.kind), ['thinking', 'text', 'tool']);
  const tool = m[1].parts[2];
  assert.equal(tool.summary, 'npm test');
  assert.equal(tool.result.error, true);
  assert.equal(tool.result.truncated, true);
  assert.equal(tool.result.text.length, 4000);
  assert.equal(m[2].parts[0].text, 'CI finished');
  assert.equal(m[3].parts[0].text, '/model opus');
  assert.equal(m[4].parts[0].text, 'Look\n[image]');
});

test('toolSummary picks the useful field', () => {
  assert.equal(toolSummary('Read', { file_path: '/a/b.js' }), '/a/b.js');
  assert.equal(toolSummary('Grep', { pattern: 'foo', path: '.' }), 'foo');
  assert.equal(toolSummary('X', { other: 'first line\nsecond' }), 'first line');
  assert.equal(toolSummary('X', null), '');
});

test('projectSlug matches Claude Code folder names', () => {
  assert.equal(projectSlug('/Users/a/.herdr/worktrees/x'), '-Users-a--herdr-worktrees-x');
});

test('sessionFor and chatFor read a session by pid', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-claude-'));
  const id = '11111111-2222-3333-4444-555555555555';
  fs.mkdirSync(path.join(dir, 'sessions'));
  fs.mkdirSync(path.join(dir, 'projects', projectSlug('/w/app')), { recursive: true });
  fs.writeFileSync(path.join(dir, 'sessions', '4242.json'), JSON.stringify({ pid: 4242, sessionId: id, cwd: '/w/app', name: 'fix-build', status: 'idle' }));
  fs.writeFileSync(path.join(dir, 'projects', projectSlug('/w/app'), `${id}.jsonl`), rows.map((r) => JSON.stringify(r)).join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'sessions', '13.json'), JSON.stringify({ sessionId: '../../etc/passwd' }));

  assert.equal((await sessionFor(4242, dir)).name, 'fix-build');
  assert.equal(await sessionFor(13, dir), null);   // not a UUID: refused
  assert.equal(await sessionFor(-1, dir), null);
  assert.equal(await sessionFor(null, dir), null);
  const chat = await chatFor(4242, { dir });
  assert.equal(chat.available, true);
  assert.equal(chat.messages.length, 5);
  assert.match(chat.version, /^\d+:\d+$/);
  assert.equal((await chatFor(9999, { dir })).available, false);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('readTail drops the partial first line', async () => {
  const f = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-tail-')), 'x.jsonl');
  fs.writeFileSync(f, 'aaaa\nbbbb\ncccc\n');
  const r = await readTail(f, 8);
  assert.equal(r.truncated, true);
  assert.deepEqual(r.lines.filter(Boolean), ['cccc']);
});

// ---- omp / pi sessions
import { parseOmp, ompSessionFile } from '../lib/transcript.js';

const omp = (o) => JSON.stringify({ timestamp: '2026-11-02T00:00:00Z', ...o });
test('parseOmp: prompts, replies, tool calls with results, notices', () => {
  const lines = [
    omp({ type: 'session', id: 's1', cwd: '/x' }),
    omp({ type: 'message', id: 'u1', message: { role: 'user', attribution: 'user', content: [{ type: 'text', text: 'review PR 1' }] } }),
    omp({ type: 'message', id: 'a1', message: { role: 'assistant', content: [{ type: 'thinking', thinking: 'hmm' }, { type: 'toolCall', id: 'c1', name: 'read', arguments: { path: 'pr://o/r/1', i: 'Reading PR' }, intent: 'Reading PR' }] } }),
    omp({ type: 'message', id: 'r1', message: { role: 'toolResult', toolCallId: 'c1', toolName: 'read', content: [{ type: 'text', text: '# PR 1' }], isError: false } }),
    omp({ type: 'message', id: 'a2', message: { role: 'assistant', content: [{ type: 'text', text: 'Looks good.' }] } }),
    omp({ type: 'message', id: 'd1', message: { role: 'developer', content: [{ type: 'text', text: '<system-reminder>rule</system-reminder>' }] } }),
    omp({ type: 'custom_message', id: 'n1', customType: 'async-result', display: true, content: '<system-notice>\nBackground job ServerReview has completed. Resume your work.\n</system-notice>' }),
    omp({ type: 'compaction', id: 'k1', summary: '## Goal' }),
  ];
  const m = parseOmp(lines);
  assert.deepEqual(m.map((x) => x.role), ['user', 'assistant', 'system', 'system']);
  assert.equal(m[0].parts[0].text, 'review PR 1');
  const [think, tool, text] = m[1].parts;
  assert.equal(think.kind, 'thinking');
  assert.deepEqual([tool.kind, tool.name, tool.summary, tool.result.text], ['tool', 'read', 'pr://o/r/1', '# PR 1']);
  assert.equal(text.text, 'Looks good.');
  assert.equal(m[2].parts[0].text, 'Background job ServerReview has completed');
  assert.equal(m[3].parts[0].text, 'Context compacted');
});

test('ompSessionFile: the open .jsonl under a sessions root, cached per pid', async () => {
  let calls = 0;
  const lsof = async () => { calls++; return 'p42\nn/dev/null\nn/h/.omp/agent/sessions/-x/2026_abc.jsonl\nn/h/other/log.jsonl\n'; };
  const roots = ['/h/.omp/agent/sessions'];
  assert.equal(await ompSessionFile(42, { roots, lsof }), '/h/.omp/agent/sessions/-x/2026_abc.jsonl');
  assert.equal(await ompSessionFile(42, { roots, lsof }), '/h/.omp/agent/sessions/-x/2026_abc.jsonl');
  assert.equal(calls, 1);
  assert.equal(await ompSessionFile(43, { roots, lsof: async () => 'n/h/other/log.jsonl\n' }), null);
});

test('questions from AskUserQuestion and ask become question parts', () => {
  const qs = { questions: [{ header: 'Next', question: 'What next?', multi: true, options: [{ label: 'A', description: 'do a' }, { label: 'B' }] }] };
  const claude = parseTranscript([JSON.stringify({ type: 'assistant', uuid: 'a', message: { content: [{ type: 'tool_use', id: 't1', name: 'AskUserQuestion', input: qs }] } })]);
  const p = claude[0].parts[0];
  assert.equal(p.kind, 'question');
  assert.deepEqual(p.questions[0].options.map((o) => o.label), ['A', 'B']);
  const o = parseOmp([
    JSON.stringify({ type: 'message', id: 'a', message: { role: 'assistant', content: [{ type: 'toolCall', id: 'c', name: 'ask', arguments: { i: 'x', ...qs } }] } }),
    JSON.stringify({ type: 'message', id: 'r', message: { role: 'toolResult', toolCallId: 'c', content: [{ type: 'text', text: 'User chose: A' }] } }),
  ]);
  assert.equal(o[0].parts[0].kind, 'question');
  assert.equal(o[0].parts[0].questions[0].multi, true);
  assert.equal(o[0].parts[0].result.text, 'User chose: A');
});

test('remoteChatFor: reads the session of a pid with one script, and not again when nothing changed', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-remote-'));
  const id = '11111111-2222-3333-4444-555555555555';
  fs.mkdirSync(path.join(dir, 'sessions'));
  fs.mkdirSync(path.join(dir, 'projects', 'p'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'sessions', '4242.json'), JSON.stringify({ pid: 4242, sessionId: id, cwd: '/w/app' }));
  fs.writeFileSync(path.join(dir, 'sessions', '13.json'), JSON.stringify({ pid: 13, sessionId: '22222222-2222-3333-4444-555555555555' }));
  fs.writeFileSync(path.join(dir, 'projects', 'p', `${id}.jsonl`), rows.map((r) => JSON.stringify(r)).join('\n') + '\n');
  // Stands in for `hc exec --on <machine>`: runs the same argv here.
  const sent = [];
  const exec = (argv) => new Promise((resolve, reject) => {
    sent.push(argv);
    execFile(argv[0], argv.slice(1), { env: { ...process.env, CLAUDE_CONFIG_DIR: dir } }, (err, out) => (err ? reject(err) : resolve(out)));
  });

  const chat = await remoteChatFor('box', 4242, { exec });
  assert.equal(chat.available, true);
  assert.equal(chat.messages.length, 5);
  assert.equal(chat.machine, 'box');
  assert.match(chat.version, /^\d+:\d+$/);
  assert.equal(await remoteChatFor('box', 4242, { exec }), chat);   // the same version: no new body
  assert.equal(sent[1].at(-1), chat.version);                        // the script got the version it has
  assert.equal(sent[1].at(-2), '4242');

  assert.equal((await remoteChatFor('box', 9999, { exec })).available, false);            // no session file
  assert.match((await remoteChatFor('box', 13, { exec })).reason, /transcript .* not found/);
  assert.equal((await remoteChatFor('box', -1, { exec })).available, false);
  assert.equal(sent.length, 4);                                                           // a bad pid runs nothing
  const down = await remoteChatFor('box', 7, { exec: async () => { throw new Error('machine box is not connected'); } });
  assert.match(down.reason, /Could not read the session on box: machine box is not connected/);
  fs.rmSync(dir, { recursive: true, force: true });
});

// ---- Codex ----
import { parseCodex, codexIdInTitle, codexSessionFile } from '../lib/transcript.js';

const codexRows = [
  { timestamp: '2026-01-01T00:00:00Z', type: 'session_meta', payload: { id: '01a1deb8-6276-79b0-b8c8-8ac956480638', cwd: '/w/app' } },
  { timestamp: '2026-01-01T00:00:01Z', type: 'response_item', payload: { type: 'message', id: 'd1', role: 'developer', content: [{ type: 'input_text', text: 'You are Codex' }] } },
  { timestamp: '2026-01-01T00:00:02Z', type: 'response_item', payload: { type: 'message', id: 'u0', role: 'user', content: [{ type: 'input_text', text: '# AGENTS.md instructions\n\n<INSTRUCTIONS>x' }, { type: 'input_text', text: '<environment_context>\n  <cwd>/w/app</cwd>' }] } },
  { timestamp: '2026-01-01T00:00:03Z', type: 'response_item', payload: { type: 'message', id: 'u1', role: 'user', content: [{ type: 'input_text', text: 'fix the build' }] } },
  { timestamp: '2026-01-01T00:00:04Z', type: 'response_item', payload: { type: 'reasoning', id: 'r1', summary: [], encrypted_content: 'gAAAA' } },
  { timestamp: '2026-01-01T00:00:05Z', type: 'response_item', payload: { type: 'message', id: 'a1', role: 'assistant', content: [{ type: 'output_text', text: 'I will look.' }] } },
  { timestamp: '2026-01-01T00:00:06Z', type: 'response_item', payload: { type: 'custom_tool_call', id: 'c1', call_id: 'call_1', name: 'exec', input: 'text(await tools.exec_command({cmd:"npm test\\n# more", yield_time_ms: 1000}))' } },
  { timestamp: '2026-01-01T00:00:07Z', type: 'event_msg', payload: { type: 'token_count' } },
  { timestamp: '2026-01-01T00:00:08Z', type: 'response_item', payload: { type: 'custom_tool_call_output', call_id: 'call_1', output: [{ type: 'input_text', text: 'Script completed\n' }, { type: 'input_text', text: '{"exit_code":1,"output":"boom"}' }] } },
  { timestamp: '2026-01-01T00:00:09Z', type: 'response_item', payload: { type: 'function_call', id: 'f1', call_id: 'call_2', name: 'sleep', arguments: '{"duration_ms":10000}' } },
  { timestamp: '2026-01-01T00:00:10Z', type: 'response_item', payload: { type: 'function_call_output', call_id: 'call_2', output: 'ok' } },
  { timestamp: '2026-01-01T00:00:11Z', type: 'compacted', payload: {} },
  { timestamp: '2026-01-01T00:00:12Z', type: 'response_item', payload: { type: 'message', id: 'a2', role: 'assistant', content: [{ type: 'output_text', text: 'Done.' }] } },
];

test('parseCodex: user and assistant text, tools with their results, no context rows', () => {
  const m = parseCodex(codexRows.map((r) => JSON.stringify(r)).concat(['', 'not json']));
  assert.deepEqual(m.map((x) => x.role), ['user', 'assistant', 'system', 'assistant']);
  assert.deepEqual(m[0].parts, [{ kind: 'text', text: 'fix the build' }]);
  assert.deepEqual(m[1].parts.map((p) => p.kind), ['text', 'tool', 'tool']);
  assert.deepEqual(m[1].parts[1], { kind: 'tool', id: 'call_1', name: 'exec', summary: 'npm test', result: { text: 'Script completed\n\n{"exit_code":1,"output":"boom"}', error: true, truncated: false } });
  assert.equal(m[1].parts[2].summary, 'duration_ms: 10000');
  assert.deepEqual(m[1].parts[2].result, { text: 'ok', error: false, truncated: false });
  assert.equal(m[2].parts[0].text, 'Context compacted');
  assert.equal(m[3].parts[0].text, 'Done.');
});

test('codexIdInTitle: the cut session id in a terminal title', () => {
  assert.equal(codexIdInTitle('codex | 01a1deb8-6276-79b0-b8c8-8ac95... | next-gen'), '01a1deb8-6276-79b0-b8c8-8ac95');
  assert.equal(codexIdInTitle('codex | 01a1deb8-6276-79b0-b8c8-8ac956480638'), '01a1deb8-6276-79b0-b8c8-8ac956480638');
  assert.equal(codexIdInTitle('codex | next-gen'), null);
  assert.equal(codexIdInTitle('deadbeef'), null);
  assert.equal(codexIdInTitle(null), null);
});

test('codexSessionFile and chatFor: by the id in the title, else by folder and start time', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-codex-'));
  const day = path.join(dir, 'sessions', '2026', '01', '02');
  fs.mkdirSync(day, { recursive: true });
  const write = (name, cwd) => fs.writeFileSync(path.join(day, name), [{ type: 'session_meta', payload: { cwd } }, ...codexRows.slice(3)].map((r) => JSON.stringify(r)).join('\n') + '\n');
  const a = 'rollout-2026-01-02T10-00-05-01a1deb8-6276-79b0-b8c8-8ac956480638.jsonl';
  const b = 'rollout-2026-01-02T10-00-30-01a1e17b-6304-7be1-bd82-ea4b57838d2b.jsonl';
  const old = 'rollout-2026-01-02T08-00-00-01a1d2fa-e3f4-78a3-b8e1-c91590000000.jsonl';
  write(a, '/w/other'); write(b, '/w/app'); write(old, '/w/app');
  const started = new Date(2026, 0, 2, 10, 0, 0).getTime();
  const info = async () => ({ startedMs: started, cwd: '/w/app' });

  assert.equal(await codexSessionFile(1, { title: 'codex | 01a1deb8-6276-79b0-b8c8-8ac95... | x', dir, info }), path.join(day, a));
  assert.equal(await codexSessionFile(2, { title: 'codex | app', dir, info }), path.join(day, b));          // the folder and the time
  assert.equal(await codexSessionFile(3, { dir, info: async () => ({ startedMs: started + 3_600_000, cwd: '/w/app' }) }), null);
  assert.equal(await codexSessionFile(4, { dir, info: async () => null }), null);
  assert.equal(await codexSessionFile(-1, { dir, info }), null);

  const none = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-claude-none-'));
  const chat = await chatFor(2, { dir: none, roots: [], lsof: async () => '', agent: 'codex', title: 'codex | app', codex: { dir, info } });
  assert.equal(chat.available, true);
  assert.equal(chat.source, 'codex');
  assert.equal(chat.session.id, '01a1e17b-6304-7be1-bd82-ea4b57838d2b');
  assert.equal(chat.messages[0].parts[0].text, 'fix the build');
  // Not a Codex terminal: the Codex files are not read.
  assert.equal((await chatFor(2, { dir: none, roots: [], lsof: async () => '', agent: 'zsh', codex: { dir, info } })).available, false);
  fs.rmSync(dir, { recursive: true, force: true }); fs.rmSync(none, { recursive: true, force: true });
});
