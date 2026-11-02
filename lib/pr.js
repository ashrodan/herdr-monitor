// PR status for the branch an agent works on: merge readiness, reviews and
// checks, from `gh pr view`.
import { run, tryGit } from './git.js';

const FAIL = new Set(['FAILURE', 'CANCELLED', 'TIMED_OUT', 'ACTION_REQUIRED', 'ERROR', 'STARTUP_FAILURE']);
const SKIP = new Set(['SKIPPED', 'NEUTRAL', 'STALE']);
const ORDER = { fail: 0, pending: 1, pass: 2, skip: 3 };

// One shape for CheckRun and StatusContext entries of statusCheckRollup.
export function normalizeCheck(c) {
  if (c.__typename === 'StatusContext' || (c.context && !c.name)) {
    const s = c.state;
    const bucket = s === 'SUCCESS' ? 'pass' : FAIL.has(s) ? 'fail' : 'pending'; // PENDING, EXPECTED
    return { name: c.context, workflow: null, bucket, url: c.targetUrl || null, startedAt: c.startedAt || null, completedAt: null };
  }
  let bucket;
  if (c.status && c.status !== 'COMPLETED') bucket = 'pending';
  else if (c.conclusion === 'SUCCESS') bucket = 'pass';
  else if (FAIL.has(c.conclusion)) bucket = 'fail';
  else if (SKIP.has(c.conclusion)) bucket = 'skip';
  else bucket = 'pending';
  return { name: c.name, workflow: c.workflowName || null, bucket, url: c.detailsUrl || null,
    startedAt: c.startedAt || null, completedAt: c.completedAt || null };
}

export function normalizeChecks(rollup = []) {
  const checks = (rollup || []).map(normalizeCheck)
    .sort((a, b) => ORDER[a.bucket] - ORDER[b.bucket] || String(a.name).localeCompare(String(b.name)));
  const counts = { pass: 0, fail: 0, pending: 0, skip: 0 };
  for (const c of checks) counts[c.bucket]++;
  return { checks, counts };
}

// Can it merge, and if not, why. Reasons are short phrases for one line.
export function mergeReadiness(pr, counts = { fail: 0, pending: 0 }) {
  if (pr.state === 'MERGED') return { state: 'merged', canMerge: false, reasons: [] };
  if (pr.state === 'CLOSED') return { state: 'closed', canMerge: false, reasons: [] };
  const reasons = [];
  if (pr.isDraft) reasons.push('draft');
  if (pr.mergeable === 'CONFLICTING' || pr.mergeStateStatus === 'DIRTY') reasons.push('merge conflicts');
  if (counts.fail) reasons.push(`${counts.fail} check${counts.fail > 1 ? 's' : ''} failing`);
  if (counts.pending) reasons.push(`${counts.pending} check${counts.pending > 1 ? 's' : ''} pending`);
  if (pr.reviewDecision === 'CHANGES_REQUESTED') reasons.push('changes requested');
  if (pr.reviewDecision === 'REVIEW_REQUIRED') reasons.push('review required');
  if (pr.mergeStateStatus === 'BEHIND') reasons.push('behind the base branch');
  if (!reasons.length && pr.mergeStateStatus === 'BLOCKED') reasons.push('blocked by branch protection');
  if (!reasons.length && (pr.mergeable === 'UNKNOWN' || pr.mergeStateStatus === 'UNKNOWN')) {
    return { state: 'unknown', canMerge: false, reasons: ['GitHub is still checking mergeability'] };
  }
  return { state: reasons.length ? 'blocked' : 'ready', canMerge: !reasons.length, reasons };
}

// The newest review of each reviewer.
export function latestReviews(reviews = []) {
  const by = new Map();
  for (const r of reviews || []) {
    const login = r.author?.login || 'unknown';
    const prev = by.get(login);
    if (!prev || new Date(r.submittedAt) > new Date(prev.submittedAt)) by.set(login, r);
  }
  return [...by.entries()].map(([login, r]) => ({ login, state: r.state, submittedAt: r.submittedAt || null }));
}

// Why gh failed, in words for the page.
export function ghFailure(err) {
  if (err?.code === 'ENOENT') return 'gh is not installed, so the PR status is not available.';
  const msg = String(err?.stderr || err?.message || '');
  if (/no (open )?pull requests? found/i.test(msg)) return null; // no PR: not an error
  if (/gh auth login|not logged in|authentication/i.test(msg)) return 'gh is not logged in. Run `gh auth login`.';
  return `gh failed: ${msg.trim().split('\n')[0] || 'unknown error'}`;
}

const FIELDS = 'number,title,url,state,isDraft,baseRefName,headRefName,updatedAt,mergeable,mergeStateStatus,reviewDecision,latestReviews,statusCheckRollup';
const cache = new Map();
const TTL_MS = 20000;

export async function prStatus(cwd, { fresh = false, runImpl = run } = {}) {
  const root = await tryGit(cwd, 'rev-parse', '--show-toplevel');
  if (!root) return { repo: null, reason: 'not a git repository', pr: null };
  const branch = await tryGit(cwd, 'rev-parse', '--abbrev-ref', 'HEAD');
  const key = `${cwd}\0${branch}`;
  const hit = cache.get(key);
  if (!fresh && hit && Date.now() - hit.at < TTL_MS) return hit.value;

  let value;
  try {
    const raw = JSON.parse(await runImpl('gh', ['pr', 'view', '--json', FIELDS], cwd));
    const { checks, counts } = normalizeChecks(raw.statusCheckRollup);
    const pr = { ...raw };
    delete pr.statusCheckRollup;
    delete pr.latestReviews;
    value = { repo: root, branch, pr, readiness: mergeReadiness(raw, counts),
      reviews: latestReviews(raw.latestReviews), checks, counts };
  } catch (err) {
    const why = ghFailure(err);
    value = { repo: root, branch, pr: null, reason: why || 'No PR for this branch.', ghError: !!why };
  }
  cache.set(key, { at: Date.now(), value });
  return value;
}
