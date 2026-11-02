import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { parseLog, parseNumstat, fromGhCommits, repoCommits, commitDetail } from '../lib/git.js';

test('parseLog reads unit/record separated output', () => {
  const out = 'abc\x1fAsh\x1f2026-11-02T10:00:00Z\x1ffix: a\x1e\ndef\x1fBo\x1f2026-11-01T10:00:00Z\x1fadd b\x1e\n';
  assert.deepEqual(parseLog(out).map((c) => c.subject), ['fix: a', 'add b']);
});

test('parseNumstat handles binary files and tabs in names', () => {
  assert.deepEqual(parseNumstat('3\t1\tsrc/a.js\n-\t-\timg.png\n\n'), [
    { file: 'src/a.js', additions: 3, deletions: 1 },
    { file: 'img.png', additions: null, deletions: null },
  ]);
});

test('fromGhCommits maps gh fields', () => {
  const [c] = fromGhCommits([{ oid: 'f00', messageHeadline: 'x', authoredDate: 'd', authors: [{ login: 'ash', name: 'Ash' }] }]);
  assert.deepEqual(c, { sha: 'f00', author: 'ash', date: 'd', subject: 'x' });
});

test('repoCommits and commitDetail on a real repo without a PR', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-git-'));
  const g = (...a) => execFileSync('git', a, { cwd: dir, env: { ...process.env, GIT_AUTHOR_NAME: 'T', GIT_AUTHOR_EMAIL: 't@x', GIT_COMMITTER_NAME: 'T', GIT_COMMITTER_EMAIL: 't@x' } }).toString().trim();
  g('init', '-q', '-b', 'main');
  fs.writeFileSync(path.join(dir, 'a.txt'), 'one\n'); g('add', '.'); g('commit', '-qm', 'base');
  g('checkout', '-qb', 'feat/x');
  fs.writeFileSync(path.join(dir, 'a.txt'), 'one\ntwo\n'); g('commit', '-qam', 'add two', '-m', 'Body line.');
  fs.writeFileSync(path.join(dir, 'b.txt'), 'b\n');

  const r = await repoCommits(dir, { fresh: true });
  assert.equal(r.branch, 'feat/x');
  assert.equal(r.pr, null);
  assert.equal(r.base, 'main');
  assert.equal(r.dirty, 1);
  assert.deepEqual(r.commits.map((c) => c.subject), ['add two']);

  const d = await commitDetail(dir, r.commits[0].sha);
  assert.match(d.message, /add two\s+Body line\./);
  assert.deepEqual(d.files, [{ file: 'a.txt', additions: 1, deletions: 0 }]);
  await assert.rejects(commitDetail(dir, 'HEAD;rm'), /bad sha/);

  const none = await repoCommits(os.tmpdir());
  assert.equal(none.repo, null);
  fs.rmSync(dir, { recursive: true, force: true });
});
