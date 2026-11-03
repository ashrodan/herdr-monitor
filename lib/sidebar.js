// The Herdr sidebar as data: spaces in Herdr order, worktrees nested under
// their main repo, each space with its tabs and the terminals in them.
import os from 'node:os';
import path from 'node:path';
import { tryGit } from './git.js';

// Herdr's space colours (space.colors in shared.json holds ansi1..ansi6).
export const ANSI = {
  ansi1: '#e5739f', // pink
  ansi2: '#7cc47f', // green
  ansi3: '#e8a25a', // orange
  ansi4: '#5f9cf0', // blue
  ansi5: '#a98bf0', // purple
  ansi6: '#3fbfb0', // teal
};
export const DEFAULT_COLOR = ANSI.ansi6;

// Terminal addresses in a pane tree, depth first (the order Herdr draws them).
export function terminalsIn(node, out = []) {
  if (!node) return out;
  if (node.type === 'block') { if (node.content?.terminal) out.push(node.content.terminal); }
  for (const c of node.children || []) terminalsIn(c, out);
  return out;
}

// Herdr names an unnamed space after the repo of its first terminal's live
// cwd, `~` at home, else the folder's basename.
export function spaceName(space, firstCwd, repo, home = os.homedir()) {
  if (space.name) return space.name;
  if (repo?.name) return repo.name;
  const dir = firstCwd || space.folder;
  if (!dir || dir === home) return '~';
  return path.basename(dir);
}

// A branch is noise when it is the default branch or repeats the worktree name.
export function visibleBranch(repo) {
  const b = repo?.branch;
  if (!b || b === 'HEAD') return null;
  if (b === (repo.defaultBranch || 'main') || (!repo.defaultBranch && b === 'master')) return null;
  if (repo.worktree && (b === repo.name || b.endsWith('/' + repo.name))) return null;
  return b;
}

// buildLayout is pure: repoOf(cwd) returns repoInfo() output resolved earlier.
export function buildLayout({ doc, terminals, colors = {}, repoOf = () => null, home = os.homedir() }) {
  const byAddr = terminals instanceof Map ? terminals : new Map((terminals || []).map((t) => [t.address, t]));
  const flat = (doc?.spaces || []).map((sp) => {
    const tabs = (sp.tabs || []).map((tb, i) => ({
      id: tb.id, name: tb.name || String(i + 1), named: !!tb.name, terminals: terminalsIn(tb.root),
    }));
    const first = tabs[0]?.terminals[0];
    const firstCwd = (first && byAddr.get(first)?.cwd) || null;
    const repo = repoOf(firstCwd || sp.folder) || null;
    return {
      id: sp.id,
      name: spaceName(sp, firstCwd, repo, home),
      color: ANSI[colors[sp.id]] || DEFAULT_COLOR,
      repo: repo?.toplevel || null,
      mainRepo: repo?.worktree ? repo.mainRepo : null,
      branch: visibleBranch(repo),
      behind: repo?.behind || 0,
      worktree: !!repo?.worktree,
      parent: null,
      tabs,
    };
  });

  // Each worktree goes right after the space of its main repo, in Herdr
  // order. A worktree whose main repo has no space gets a header row, placed
  // where its first worktree was.
  const mainSpace = new Map();
  for (const s of flat) if (!s.worktree && s.repo && !mainSpace.has(s.repo)) mainSpace.set(s.repo, s);
  const children = new Map(); // main repo -> worktree rows
  for (const s of flat) if (s.worktree) {
    if (!children.has(s.mainRepo)) children.set(s.mainRepo, []);
    children.get(s.mainRepo).push(s);
  }
  const out = [];
  const emit = (row) => {
    out.push(row);
    for (const c of children.get(row.repo) || []) { c.parent = row.id; out.push(c); }
    children.delete(row.repo);
  };
  for (const s of flat) {
    if (!s.worktree) { if (mainSpace.get(s.repo) === s) emit(s); else out.push(s); continue; }
    if (mainSpace.has(s.mainRepo) || !children.has(s.mainRepo)) continue; // placed by its main space, or already placed
    emit({ id: `repo:${s.mainRepo}`, name: path.basename(s.mainRepo).replace(/\.git$/, ''), color: s.color,
      repo: s.mainRepo, mainRepo: null, branch: null, behind: 0, worktree: false, parent: null, virtual: true, tabs: [] });
  }
  return out;
}

// What git says about a cwd, cached for 30 s.
const cache = new Map();
const TTL_MS = 30000;
export async function repoInfo(cwd, { fresh = false } = {}) {
  if (!cwd) return null;
  const hit = cache.get(cwd);
  if (!fresh && hit && Date.now() - hit.at < TTL_MS) return hit.value;
  let value = null;
  const out = await tryGit(cwd, 'rev-parse', '--show-toplevel', '--path-format=absolute', '--git-common-dir', '--abbrev-ref', 'HEAD');
  if (out) {
    const [toplevel, common, branch] = out.split('\n');
    const mainRepo = path.basename(common) === '.git' ? path.dirname(common) : common;
    const [def, behind] = await Promise.all([
      tryGit(cwd, 'symbolic-ref', '--short', 'refs/remotes/origin/HEAD'),
      tryGit(cwd, 'rev-list', '--count', 'HEAD..@{u}'),
    ]);
    value = {
      toplevel, name: path.basename(toplevel), mainRepo, worktree: mainRepo !== toplevel,
      branch: branch || null, defaultBranch: def ? def.replace(/^origin\//, '') : null, behind: Number(behind) || 0,
    };
  }
  cache.set(cwd, { at: Date.now(), value });
  return value;
}
