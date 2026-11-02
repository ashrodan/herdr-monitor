import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeCheck, normalizeChecks, mergeReadiness, latestReviews, ghFailure, prStatus } from '../lib/pr.js';

const run = (over = {}) => ({ __typename: 'CheckRun', name: 'test', workflowName: 'CI', status: 'COMPLETED', conclusion: 'SUCCESS',
  startedAt: '2026-11-01T01:54:29Z', completedAt: '2026-11-01T01:54:37Z', detailsUrl: 'https://ci/1', ...over });
const ctx = (state) => ({ __typename: 'StatusContext', context: 'vercel', state, targetUrl: 'https://v/1' });

test('a passing CheckRun', () => {
  assert.deepEqual(normalizeCheck(run()), { name: 'test', workflow: 'CI', bucket: 'pass', url: 'https://ci/1',
    startedAt: '2026-11-01T01:54:29Z', completedAt: '2026-11-01T01:54:37Z' });
});

test('a failing CheckRun, and the other fail conclusions', () => {
  for (const c of ['FAILURE', 'CANCELLED', 'TIMED_OUT', 'ACTION_REQUIRED']) assert.equal(normalizeCheck(run({ conclusion: c })).bucket, 'fail', c);
});

test('a pending CheckRun', () => {
  const c = normalizeCheck(run({ status: 'IN_PROGRESS', conclusion: null, completedAt: null }));
  assert.equal(c.bucket, 'pending');
  assert.equal(c.completedAt, null);
  assert.equal(normalizeCheck(run({ status: 'QUEUED', conclusion: null })).bucket, 'pending');
});

test('a skipped CheckRun', () => {
  assert.equal(normalizeCheck(run({ conclusion: 'SKIPPED' })).bucket, 'skip');
  assert.equal(normalizeCheck(run({ conclusion: 'NEUTRAL' })).bucket, 'skip');
});

test('a StatusContext with SUCCESS', () => {
  assert.deepEqual(normalizeCheck(ctx('SUCCESS')), { name: 'vercel', workflow: null, bucket: 'pass', url: 'https://v/1', startedAt: null, completedAt: null });
});

test('a StatusContext with PENDING, and ERROR fails', () => {
  assert.equal(normalizeCheck(ctx('PENDING')).bucket, 'pending');
  assert.equal(normalizeCheck(ctx('ERROR')).bucket, 'fail');
});

test('normalizeChecks counts and puts failing checks first', () => {
  const { checks, counts } = normalizeChecks([run({ name: 'b' }), ctx('PENDING'), run({ name: 'a', conclusion: 'FAILURE' }), run({ name: 'c', conclusion: 'SKIPPED' })]);
  assert.deepEqual(checks.map((c) => `${c.bucket}:${c.name}`), ['fail:a', 'pending:vercel', 'pass:b', 'skip:c']);
  assert.deepEqual(counts, { pass: 1, fail: 1, pending: 1, skip: 1 });
  assert.deepEqual(normalizeChecks(null).checks, []);
});

const open = (over = {}) => ({ state: 'OPEN', isDraft: false, mergeable: 'MERGEABLE', mergeStateStatus: 'CLEAN', reviewDecision: '', ...over });
const none = { fail: 0, pending: 0 };

test('merge readiness: ready, merged and closed', () => {
  assert.deepEqual(mergeReadiness(open(), none), { state: 'ready', canMerge: true, reasons: [] });
  assert.equal(mergeReadiness({ state: 'MERGED' }).state, 'merged');
  assert.equal(mergeReadiness({ state: 'CLOSED' }).state, 'closed');
});

test('merge readiness: the reason for each block', () => {
  const reasons = (pr, counts = none) => mergeReadiness(pr, counts).reasons;
  assert.deepEqual(reasons(open({ mergeable: 'CONFLICTING' })), ['merge conflicts']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'DIRTY' })), ['merge conflicts']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'UNSTABLE' }), { fail: 2, pending: 0 }), ['2 checks failing']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'BLOCKED' }), { fail: 0, pending: 1 }), ['1 check pending']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'BLOCKED', reviewDecision: 'REVIEW_REQUIRED' })), ['review required']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'BLOCKED', reviewDecision: 'CHANGES_REQUESTED' })), ['changes requested']);
  assert.deepEqual(reasons(open({ isDraft: true, mergeStateStatus: 'DRAFT' })), ['draft']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'BEHIND' })), ['behind the base branch']);
  assert.deepEqual(reasons(open({ mergeStateStatus: 'BLOCKED' })), ['blocked by branch protection']);
  const multi = mergeReadiness(open({ isDraft: true, mergeable: 'CONFLICTING' }), { fail: 1, pending: 0 });
  assert.equal(multi.canMerge, false);
  assert.deepEqual(multi.reasons, ['draft', 'merge conflicts', '1 check failing']);
  assert.equal(mergeReadiness(open({ mergeable: 'UNKNOWN', mergeStateStatus: 'UNKNOWN' }), none).state, 'unknown');
});

test('latestReviews keeps the newest review per reviewer', () => {
  const got = latestReviews([
    { author: { login: 'a' }, state: 'CHANGES_REQUESTED', submittedAt: '2026-11-01T01:00:00Z' },
    { author: { login: 'a' }, state: 'APPROVED', submittedAt: '2026-11-01T02:00:00Z' },
    { author: { login: 'b' }, state: 'COMMENTED', submittedAt: '2026-11-01T03:00:00Z' },
  ]);
  assert.deepEqual(got.map((r) => `${r.login}:${r.state}`), ['a:APPROVED', 'b:COMMENTED']);
});

test('ghFailure explains a missing or logged-out gh, and no PR is not an error', () => {
  assert.match(ghFailure({ code: 'ENOENT' }), /not installed/);
  assert.match(ghFailure({ stderr: 'To get started with GitHub CLI, please run:  gh auth login' }), /not logged in/);
  assert.equal(ghFailure({ stderr: 'no pull requests found for branch "x"' }), null);
});

test('prStatus outside a repository', async () => {
  const r = await prStatus('/', { fresh: true, runImpl: () => { throw new Error('not called'); } });
  assert.equal(r.repo, null);
});
