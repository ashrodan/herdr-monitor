import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sessionName, sessionRequest, PROMPT_MAX } from '../lib/sessions.js';

const layout = [
  { id: 's1', name: 'herdr-monitor', folder: '/Users/me/dev/herdr-monitor', tabs: [] },
  { id: 'repo:/x', name: 'x', virtual: true, tabs: [] },
  { id: 's3', name: 'empty', folder: null, tabs: [] },
];

test('sessionRequest: starts the harness with the prompt as one argument, in the space folder', () => {
  const r = sessionRequest({ space: 's1', harness: 'claude', prompt: 'fix the "tests"; rm -rf /' }, layout, 1_700_000_000_000);
  assert.equal(r.ok, true);
  assert.deepEqual(r.params.argv, ['claude', 'fix the "tests"; rm -rf /']);
  assert.equal(r.params.cwd, '/Users/me/dev/herdr-monitor');
  assert.match(r.params.name, /^mnt1-claude-[a-z0-9]{1,6}$/);
  assert.equal(r.params.wait, false);
  assert.equal(r.params.no_parent, true);
  assert.equal(r.params.line, undefined);
});

test('sessionRequest: omp is the other harness; anything else is refused', () => {
  assert.equal(sessionRequest({ space: 's1', harness: 'omp', prompt: 'go' }, layout).params.argv[0], 'omp');
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
  assert.deepEqual(r.params.argv, ['claude', 'go']);
});

test('sessionName: valid for hc and starts with mnt1-', () => {
  const n = sessionName('claude', Date.now());
  assert.match(n, /^mnt1-[a-z0-9_-]{1,27}$/);
  assert.ok(n.length <= 32);
});
