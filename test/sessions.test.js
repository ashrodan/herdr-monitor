import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sessionName, sessionRequest, sessionResult, PROMPT_MAX } from '../lib/sessions.js';

const layout = [
  { id: 's1', name: 'herdr-monitor', folder: '/Users/me/dev/herdr-monitor', tabs: [] },
  { id: 'repo:/x', name: 'x', virtual: true, tabs: [] },
  { id: 's3', name: 'empty', folder: null, tabs: [] },
];

test('sessionRequest: starts the harness with the prompt as one argument, in the space folder', () => {
  const r = sessionRequest({ space: 's1', harness: 'claude', prompt: 'fix the "tests"; rm -rf /' }, layout, 1_700_000_000_000);
  assert.equal(r.ok, true);
  assert.deepEqual(r.params.argv, ['claude', '--', 'fix the "tests"; rm -rf /']);
  assert.equal(r.params.cwd, '/Users/me/dev/herdr-monitor');
  assert.match(r.params.name, /^hm-claude-[a-z0-9]{1,6}$/);
  assert.equal(r.params.wait, false);
  assert.equal(r.params.no_parent, true);
  assert.equal(r.params.line, undefined);
});

test('sessionRequest: omp is the other harness; anything else is refused', () => {
  assert.deepEqual(sessionRequest({ space: 's1', harness: 'omp', prompt: 'go' }, layout).params.argv, ['omp', 'go']);
  for (const harness of ['bash', 'sh', '', undefined, ['claude'], 'claude --dangerously-skip-permissions']) {
    const r = sessionRequest({ space: 's1', harness, prompt: 'go' }, layout);
    assert.equal(r.ok, false); assert.equal(r.status, 400);
  }
});

test('sessionRequest: the prompt is 1 to 4000 characters', () => {
  assert.equal(sessionRequest({ space: 's1', harness: 'claude', prompt: '   ' }, layout).status, 400);
  assert.equal(sessionRequest({ space: 's1', harness: 'claude', prompt: 7 }, layout).status, 400);
  assert.equal(sessionRequest({ space: 's1', harness: 'claude', prompt: 'x'.repeat(PROMPT_MAX) }, layout).ok, true);
  assert.equal(sessionRequest({ space: 's1', harness: 'claude', prompt: 'x'.repeat(PROMPT_MAX + 1) }, layout).status, 400);
});

test('sessionRequest: only a space from the layout; no path from the client', () => {
  assert.equal(sessionRequest({ space: '/etc', harness: 'claude', prompt: 'go' }, layout).status, 404);
  assert.equal(sessionRequest({ space: 'repo:/x', harness: 'claude', prompt: 'go' }, layout).status, 404);
  assert.equal(sessionRequest({ space: 's3', harness: 'claude', prompt: 'go' }, layout).status, 409);
  assert.equal(sessionRequest({ harness: 'claude', prompt: 'go' }, layout).status, 400);
  const r = sessionRequest({ space: 's1', harness: 'claude', prompt: 'go', cwd: '/tmp', argv: ['sh'] }, layout);
  assert.equal(r.params.cwd, '/Users/me/dev/herdr-monitor');
  assert.deepEqual(r.params.argv, ['claude', '--', 'go']);
});

test('sessionRequest: a prompt that starts with - is refused, so it cannot pass options to the harness', () => {
  for (const harness of ['claude', 'omp']) {
    for (const prompt of ['--dangerously-skip-permissions', '-p hi', '--system-prompt=evil do it', '-']) {
      const r = sessionRequest({ space: 's1', harness, prompt }, layout);
      assert.equal(r.ok, false, `${harness} ${prompt}`);
      assert.equal(r.status, 400);
      assert.match(r.error, /cannot start with '-'/);
    }
  }
  // A dash later in the prompt, or after a space, is plain text.
  assert.deepEqual(sessionRequest({ space: 's1', harness: 'omp', prompt: 'use -v' }, layout).params.argv, ['omp', 'use -v']);
  assert.deepEqual(sessionRequest({ space: 's1', harness: 'claude', prompt: ' --x' }, layout).params.argv, ['claude', '--', ' --x']);
});

test('sessionName: valid for hc and starts with hm-', () => {
  const n = sessionName('claude', Date.now());
  assert.match(n, /^hm-[a-z0-9_-]{1,29}$/);
  assert.ok(n.length <= 32);
});

test('sessionResult reads the address from either reply shape, and the name falls back to ours', () => {
  const params = { name: 'hm-claude-abc' };
  assert.deepEqual(sessionResult({ address: 't7', name: 'x' }, params), { address: 't7', name: 'x' });
  assert.deepEqual(sessionResult({ terminal: { address: 't8' } }, params), { address: 't8', name: 'hm-claude-abc' });
  assert.equal(sessionResult({}, params), null);
  assert.equal(sessionResult(null, params), null);
});

test('withNewTab adds one tab to one space and leaves the rest alone', async () => {
  const { withNewTab } = await import('../lib/sessions.js');
  const doc = { spaces: [
    { id: 'spA', name: 'a', tabs: [{ id: 'tb1', name: '', root: { type: 'row', id: 'tb1', children: [] } }] },
    { id: 'spB', name: 'b', tabs: [] },
  ], other: { keep: true } };
  const before = structuredClone(doc);
  let n = 0;
  const next = withNewTab(doc, 'spB', 't42', 'claude', (p) => `${p}${++n}`);
  assert.deepEqual(doc, before); // the input is not changed
  assert.deepEqual(next.spaces[0], before.spaces[0]);
  assert.deepEqual(next.other, { keep: true });
  assert.deepEqual(next.spaces[1].tabs, [{ id: 'tb1', name: 'claude', root: { children: [{ content: { terminal: 't42' }, id: 'p2', type: 'block' }], id: 'tb1', type: 'row' } }]);
  assert.equal(withNewTab(doc, 'nope', 't42', 'x', (p) => p), null);
  assert.equal(withNewTab(null, 'spA', 't42', 'x', (p) => p), null);
});

test('parseWorktrees reads git worktree list --porcelain', async () => {
  const { parseWorktrees } = await import('../lib/sessions.js');
  const text = 'worktree /r\nHEAD abc\nbranch refs/heads/main\n\nworktree /r/.worktrees/x\nHEAD def\nbranch refs/heads/feat/x\n\nworktree /r/.worktrees/d\nHEAD 123\ndetached\n\nworktree /bare\nbare\n';
  assert.deepEqual(parseWorktrees(text), [
    { path: '/r', branch: 'main', head: 'abc', detached: false, bare: false },
    { path: '/r/.worktrees/x', branch: 'feat/x', head: 'def', detached: false, bare: false },
    { path: '/r/.worktrees/d', branch: null, head: '123', detached: true, bare: false },
  ]);
  assert.deepEqual(parseWorktrees(null), []);
});
