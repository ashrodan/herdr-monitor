// Commit details for the repo an agent works in: its PR (through gh) or,
// without a PR, the branch's commits since the default branch.
import { execFile } from 'node:child_process';

export function run(cmd, args, cwd, timeout = 15000) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { cwd, timeout, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) { err.stderr = stderr; reject(err); } else resolve(stdout);
    });
  });
}
const git = (cwd, ...args) => run('git', args, cwd).then((s) => s.trim());
export const tryGit = (cwd, ...args) => git(cwd, ...args).catch(() => null);

export const SHA_RE = /^[0-9a-f]{7,40}$/i;
const SEP = '\x1f';
const REC = '\x1e';

// Parse `git log --format=%H%x1f%an%x1f%aI%x1f%s%x1e`.
export function parseLog(out) {
  return out.split(REC).map((r) => r.trim()).filter(Boolean).map((r) => {
    const [sha, author, date, subject] = r.split(SEP);
    return { sha, author, date, subject };
  });
}

// Normalize `gh pr view --json commits` entries to the same shape.
export function fromGhCommits(commits = []) {
  return commits.map((c) => ({
    sha: c.oid,
    author: c.authors?.map((a) => a.login || a.name).filter(Boolean).join(', ') || '',
    date: c.authoredDate,
    subject: c.messageHeadline,
  }));
}

// Parse `git show --numstat` lines into per-file counts.
export function parseNumstat(out) {
  return out.split('\n').filter((l) => /^(\d+|-)\t(\d+|-)\t/.test(l)).map((l) => {
    const [add, del, ...rest] = l.split('\t');
    return { file: rest.join('\t'), additions: add === '-' ? null : +add, deletions: del === '-' ? null : +del };
  });
}

const cache = new Map(); // repo root -> { at, value }
const TTL_MS = 20000;

export async function repoCommits(cwd, { fresh = false } = {}) {
  const root = await tryGit(cwd, 'rev-parse', '--show-toplevel');
  if (!root) return { repo: null, reason: 'not a git repository', commits: [] };
  const branch = await tryGit(cwd, 'rev-parse', '--abbrev-ref', 'HEAD');
  const key = `${cwd}\0${branch}`;
  const hit = cache.get(key);
  if (!fresh && hit && Date.now() - hit.at < TTL_MS) return hit.value;

  const head = await tryGit(cwd, 'rev-parse', 'HEAD');
  const upstream = await tryGit(cwd, 'rev-parse', '--abbrev-ref', '@{u}');
  const unpushed = upstream ? Number(await tryGit(cwd, 'rev-list', '--count', '@{u}..HEAD')) : null;
  const dirty = (await tryGit(cwd, 'status', '--porcelain'))?.split('\n').filter(Boolean).length ?? 0;

  let pr = null;
  try {
    const out = await run('gh', ['pr', 'view', '--json',
      'number,title,url,state,isDraft,baseRefName,headRefName,additions,deletions,changedFiles,commits'], cwd);
    pr = JSON.parse(out);
  } catch { /* no PR for this branch, or gh unavailable */ }

  let commits;
  let base = null;
  if (pr) {
    base = pr.baseRefName;
    commits = fromGhCommits(pr.commits).reverse(); // newest first, like git log
  } else {
    base = (await tryGit(cwd, 'symbolic-ref', '--short', 'refs/remotes/origin/HEAD'))
      || (await tryGit(cwd, 'rev-parse', '--verify', '-q', 'origin/main') ? 'origin/main' : null)
      || 'main';
    const out = await tryGit(cwd, 'log', `--format=%H${SEP}%an${SEP}%aI${SEP}%s${REC}`, '-n', '100', `${base}..HEAD`);
    commits = out ? parseLog(out) : [];
  }

  // Mark commits that exist only locally (not yet in the PR / upstream).
  const remoteShas = new Set(pr ? pr.commits.map((c) => c.oid) : []);
  let local = [];
  if (pr) {
    const out = await tryGit(cwd, 'log', `--format=%H${SEP}%an${SEP}%aI${SEP}%s${REC}`, '-n', '50', `origin/${pr.baseRefName}..HEAD`);
    local = out ? parseLog(out).filter((c) => !remoteShas.has(c.sha)).map((c) => ({ ...c, local: true })) : [];
  }

  if (pr) delete pr.commits;
  const value = { repo: root, branch, head, upstream, unpushed, dirty, base, pr, commits: [...local, ...commits] };
  cache.set(key, { at: Date.now(), value });
  return value;
}

export async function commitDetail(cwd, sha) {
  if (!SHA_RE.test(sha)) throw new Error('bad sha');
  const meta = await git(cwd, 'show', '-s', `--format=%H${SEP}%an <%ae>${SEP}%aI${SEP}%B`, sha);
  const [full, author, date, ...body] = meta.split(SEP);
  const numstat = await git(cwd, 'show', '--numstat', '--format=', sha);
  return { sha: full, author, date, message: body.join(SEP).trim(), files: parseNumstat(numstat) };
}
