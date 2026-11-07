import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Overview, reposOf } from '../lib/overview.js';

const space = (id, repo, extra = {}) => ({ id, name: id, color: 'c', repo, ...extra });
const open = (n, updatedAt) => ({ pr: { number: n, state: 'OPEN', updatedAt }, branch: `b${n}`, readiness: 'ready', counts: {} });

test('reposOf lists each repo once, with its first space, and skips virtual spaces', () => {
  const got = reposOf([space('a', '/r1'), space('b', '/r1'), space('c', '/r2', { virtual: true }), space('d', null), space('e', '/r3')]);
  assert.deepEqual(got.map(([r, sp]) => [r, sp.id]), [['/r1', 'a'], ['/r3', 'e']]);
});

test('Overview keeps open PRs only, newest first', async () => {
  const st = { '/r1': open(1, '2026-01-01'), '/r2': { pr: { number: 2, state: 'MERGED' } }, '/r3': open(3, '2026-02-01') };
  const o = new Overview({ repos: () => reposOf([space('a', '/r1'), space('b', '/r2'), space('c', '/r3')]), status: async (r) => st[r] });
  const v = await o.get();
  assert.deepEqual(v.prs.map((p) => p.pr.number), [3, 1]);
  assert.equal(v.prs[0].space, 'c');
});

test('Overview answers from the last result at once and refreshes it in the background, once', async () => {
  let t = 0, calls = 0, release;
  const gate = () => new Promise((r) => { release = r; });
  const o = new Overview({ repos: () => [['/r1', space('a', '/r1')]], maxAgeMs: 100, now: () => t,
    status: async () => { calls++; if (calls > 1) await gate(); return open(calls, 'x'); } });
  const first = await o.get();
  assert.equal(calls, 1);
  t = 500; // stale now
  const a = await o.get(), b = await o.get();
  assert.equal(a, first); // no wait for gh
  assert.equal(b, first);
  assert.equal(calls, 2);  // one background run for both callers
  release();
  await o.running;
  assert.equal((await o.get()).prs[0].pr.number, 2);
});

test('Overview fresh waits for a new result', async () => {
  let n = 0;
  const o = new Overview({ repos: () => [['/r1', space('a', '/r1')]], status: async () => open(++n, 'x') });
  await o.get();
  assert.equal((await o.get({ fresh: true })).prs[0].pr.number, 2);
});

test('Overview keeps the last result on disk for the next start, and ignores a broken file', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-ov-'));
  const file = path.join(dir, 'cache', 'overview.json');
  const repos = () => [['/r1', space('a', '/r1')]];
  await new Overview({ repos, file, status: async () => open(7, 'x') }).get();
  assert.equal((fs.statSync(file).mode & 0o777), 0o600);
  let called = false;
  const next = new Overview({ repos, file, status: async () => { called = true; return open(8, 'x'); } });
  assert.equal(next.value.prs[0].pr.number, 7);
  fs.writeFileSync(file, '{broken');
  assert.equal(new Overview({ repos, file, status: async () => null }).value, null);
  assert.equal(called, false);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('Overview does not run or replace the last result while the layout is not loaded', async () => {
  let ready = false, calls = 0;
  const o = new Overview({ repos: () => [], ready: () => ready, status: async () => { calls++; return null; } });
  assert.deepEqual(await o.get(), { prs: [], at: 0 });
  o.value = { prs: [{ pr: { number: 1 } }], at: 5 };
  assert.equal((await o.get({ fresh: true })).prs.length, 1);
  assert.equal(calls, 0);
  ready = true;
});
