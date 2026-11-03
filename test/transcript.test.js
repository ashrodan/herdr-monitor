import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { parseTranscript, toolSummary, projectSlug, sessionFor, chatFor, readTail } from '../lib/transcript.js';

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
