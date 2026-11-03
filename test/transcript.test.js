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
