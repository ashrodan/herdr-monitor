import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { buildLayout, terminalsIn, spaceName, visibleBranch, repoInfo, ANSI, DEFAULT_COLOR } from '../lib/sidebar.js';

const HOME = '/home/u';
const block = (terminal) => ({ type: 'block', content: { terminal } });
const tab = (id, name, root) => ({ id, name, root });
const space = (id, name, folder, tabs) => ({ id, name, folder, tabs });

const repos = {
  '/home/u/dev/app': { toplevel: '/home/u/dev/app', name: 'app', mainRepo: '/home/u/dev/app', worktree: false, branch: 'main', defaultBranch: 'main', behind: 3 },
  '/home/u/wt/app/fix-x': { toplevel: '/home/u/wt/app/fix-x', name: 'fix-x', mainRepo: '/home/u/dev/app', worktree: true, branch: 'me/fix-x', defaultBranch: 'main', behind: 0 },
  '/home/u/wt/app/try-y': { toplevel: '/home/u/wt/app/try-y', name: 'try-y', mainRepo: '/home/u/dev/app', worktree: true, branch: 'other-branch', defaultBranch: 'main', behind: 0 },
  '/home/u/ws/ess/eba': { toplevel: '/home/u/ws/ess/eba', name: 'eba', mainRepo: '/home/u/dev/ess', worktree: true, branch: 'eba', defaultBranch: 'main', behind: 0 },
};
const repoOf = (cwd) => repos[cwd] || null;

test('terminalsIn walks the pane tree depth first', () => {
  const root = { type: 'row', children: [{ type: 'column', children: [block('t13'), block('t15')] }, block('t14')] };
  assert.deepEqual(terminalsIn(root), ['t13', 't15', 't14']);
  assert.deepEqual(terminalsIn(null), []);
});

test('spaceName follows the Herdr rule', () => {
  assert.equal(spaceName({ name: 'ship-it', folder: '/x' }, '/home/u/dev/app', repos['/home/u/dev/app'], HOME), 'ship-it');
  assert.equal(spaceName({ name: '', folder: HOME }, '/home/u/dev/app', repos['/home/u/dev/app'], HOME), 'app');
  assert.equal(spaceName({ name: '', folder: '/elsewhere' }, HOME, null, HOME), '~');
  assert.equal(spaceName({ name: '', folder: '/a/notes' }, null, null, HOME), 'notes');
  assert.equal(spaceName({ name: '', folder: '/a/notes' }, '/b/scratch', null, HOME), 'scratch');
});

test('visibleBranch hides the default branch and the worktree name', () => {
  assert.equal(visibleBranch(repos['/home/u/dev/app']), null);
  assert.equal(visibleBranch(repos['/home/u/wt/app/fix-x']), null); // me/fix-x repeats fix-x
  assert.equal(visibleBranch(repos['/home/u/wt/app/try-y']), 'other-branch');
  assert.equal(visibleBranch({ branch: 'master', name: 'r' }), null);
  assert.equal(visibleBranch({ branch: 'HEAD', name: 'r' }), null);
  assert.equal(visibleBranch(null), null);
});

test('buildLayout names, colours, nests worktrees and adds a header row', () => {
  const doc = { spaces: [
    space('s1', '', HOME, [tab('a', '', block('t1')), tab('b', 'Claude Code', { type: 'row', children: [block('t2'), block('t3')] })]),
    space('s2', '', '/home/u/wt/app/fix-x', [tab('c', '', block('t4'))]), // worktree before its main space
    space('s3', '', HOME, [tab('d', '', block('t5'))]),
    space('s4', '', '/home/u/ws/ess/eba', [tab('e', '', block('t6'))]),   // main repo has no space
    space('s5', 'notes', '/home/u/notes', [tab('f', '', block('t7'))]),
    space('s6', '', '/home/u/wt/app/try-y', [tab('g', '', block('t8'))]),
  ] };
  const terminals = [
    { address: 't1', cwd: HOME }, { address: 't4', cwd: '/home/u/wt/app/fix-x' },
    { address: 't5', cwd: '/home/u/dev/app' }, { address: 't6', cwd: '/home/u/ws/ess/eba' },
    { address: 't8', cwd: '/home/u/wt/app/try-y' },
  ];
  const colors = { s1: 'ansi1', s3: 'ansi4', s4: 'ansi3' };
  const out = buildLayout({ doc, terminals, colors, repoOf, home: HOME });

  assert.deepEqual(out.map((s) => s.name), ['~', 'app', 'fix-x', 'try-y', 'ess', 'eba', 'notes']);
  const by = Object.fromEntries(out.map((s) => [s.name, s]));
  assert.equal(by['~'].color, ANSI.ansi1);
  assert.equal(by.app.color, ANSI.ansi4);
  assert.equal(by['fix-x'].color, DEFAULT_COLOR);
  assert.equal(by.app.behind, 3);
  assert.equal(by.app.branch, null);
  assert.equal(by['fix-x'].parent, 's3');
  assert.equal(by['try-y'].parent, 's3');
  assert.equal(by['try-y'].branch, 'other-branch');
  assert.equal(by.ess.virtual, true);
  assert.equal(by.ess.id, 'repo:/home/u/dev/ess');
  assert.equal(by.ess.color, ANSI.ansi3);
  assert.equal(by.eba.parent, 'repo:/home/u/dev/ess');
  assert.equal(by.notes.parent, null);
  assert.deepEqual(by['~'].tabs, [
    { id: 'a', name: '1', named: false, terminals: ['t1'] },
    { id: 'b', name: 'Claude Code', named: true, terminals: ['t2', 't3'] },
  ]);
});

test('repoInfo reads a main repo and a linked worktree', async () => {
  const dir = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-side-')));
  const main = path.join(dir, 'proj');
  const wt = path.join(dir, 'wt-feature');
  const g = (cwd, ...a) => execFileSync('git', a, { cwd, env: { ...process.env, GIT_AUTHOR_NAME: 'T', GIT_AUTHOR_EMAIL: 't@x', GIT_COMMITTER_NAME: 'T', GIT_COMMITTER_EMAIL: 't@x' } });
  fs.mkdirSync(main);
  g(main, 'init', '-q', '-b', 'main');
  fs.writeFileSync(path.join(main, 'a'), 'a'); g(main, 'add', '.'); g(main, 'commit', '-qm', 'a');
  g(main, 'worktree', 'add', '-q', '-b', 'feature', wt);

  const m = await repoInfo(main, { fresh: true });
  assert.equal(m.name, 'proj');
  assert.equal(m.worktree, false);
  assert.equal(m.branch, 'main');
  const w = await repoInfo(path.join(wt), { fresh: true });
  assert.equal(w.worktree, true);
  assert.equal(w.mainRepo, main);
  assert.equal(w.branch, 'feature');
  assert.equal(await repoInfo(os.tmpdir(), { fresh: true }), null);
  fs.rmSync(dir, { recursive: true, force: true });
});
