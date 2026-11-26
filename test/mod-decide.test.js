import test from "node:test";
import assert from "node:assert/strict";
import { decisions, mergePlan, withLater, APPROVE_TEXT } from "../herdr-mod/summary/decide.js";

const agent = (address, status, extra = {}) => ({ address, status, seq: 7, space_name: "dash", tab_name: "1", title: "✳ fix dates", app: "claude-code", ...extra });
const pr = (number, extra = {}) => ({ number, title: `PR ${number}`, url: `https://github.com/o/r/pull/${number}`, repo: "o/r", branch: `b${number}`, author: "me", updatedAt: "2026-11-26T00:00:00Z", review: "", draft: false, checks: { fail: 0, pending: 0, pass: 3 }, ...extra });
const ids = (list) => list.map((d) => `${d.kind}:${d.options.map((o) => o.id).join("/")}`);

test("decide: each source becomes its card, most urgent first", () => {
  const list = decisions({
    me: "me",
    agents: [agent("t1", "done"), agent("t2", "blocked"), agent("t3", "working")],
    prs: [pr(1), pr(2, { checks: { fail: 2, pending: 0 } }), pr(3, { author: "ana", review: "REVIEW_REQUIRED" }), pr(4, { draft: true })],
    status: new Map([["t2", { kind: "permission", msg: "Run npm test?", seq: 7 }]]),
  });
  assert.deepEqual(ids(list), ["ask:yes/no/review/later", "merge:merge/review/later", "fix:fix/review/later", "review:approve-pr/review/later", "done:approve/review/later"]);
  assert.equal(list[0].detail, "Run npm test?");
  assert.equal(list[2].why, "2 checks failing");
  assert.equal(list[4].detail, "fix dates");
});

test("decide: attack probes, no Yes/No where keys would answer the wrong thing", () => {
  // A question (not a permission prompt): Enter would pick whatever is highlighted.
  const q = decisions({ agents: [agent("t2", "blocked")], status: new Map([["t2", { kind: "question", seq: 7 }]]) });
  assert.deepEqual(q[0].options.map((o) => o.id), ["review", "later"]);
  // omp's forms take other keys.
  const omp = decisions({ agents: [agent("t2", "blocked", { app: "omp" })], status: new Map([["t2", { kind: "permission", seq: 7 }]]) });
  assert.deepEqual(omp[0].options.map((o) => o.id), ["review", "later"]);
  // Unknown login: no PR cards at all (can't tell mine from theirs).
  assert.equal(decisions({ prs: [pr(1)], me: null }).length, 0);
  // Checks still running, or waiting on a review: not mergeable yet.
  assert.equal(decisions({ me: "me", prs: [pr(1, { checks: { fail: 0, pending: 1 } }), pr(2, { review: "REVIEW_REQUIRED" })] }).length, 0);
  // Someone else's PR without a review request: no card.
  assert.equal(decisions({ me: "me", prs: [pr(1, { author: "ana" })] }).length, 0);
});

test("decide: Later hides one card until its subject changes", () => {
  const prs = [pr(1)];
  const [d] = decisions({ me: "me", prs });
  const later = withLater({}, d.id);
  assert.equal(decisions({ me: "me", prs, later }).length, 0);
  assert.equal(decisions({ me: "me", prs: [pr(1, { updatedAt: "2026-11-27T00:00:00Z" })], later }).length, 1);
  const many = Array.from({ length: 250 }, (_, i) => i).reduce((l, i) => withLater(l, `x${i}`), {});
  assert.equal(Object.keys(many).length, 200);
  assert.ok(many.x249 && !many.x0);
});

test("mergePlan: the first method the repo allows; blocked or conflicting refuses", () => {
  assert.deepEqual(mergePlan({ squashMergeAllowed: true, pullRequest: { headRefOid: "abc", baseRefName: "main", mergeStateStatus: "CLEAN" } }), { method: "squash", head: "abc", base: "main", blocked: false });
  assert.equal(mergePlan({ squashMergeAllowed: false, mergeCommitAllowed: true }).method, "merge");
  assert.equal(mergePlan({ squashMergeAllowed: false, mergeCommitAllowed: false }).method, "rebase");
  assert.equal(mergePlan({ pullRequest: { mergeStateStatus: "BLOCKED" } }).blocked, true);
  assert.equal(mergePlan({ pullRequest: { mergeStateStatus: "DIRTY" } }).blocked, true);
  assert.ok(APPROVE_TEXT.includes("pull request"));
});

test("decide: bot PRs fold into one card, never one each", () => {
  const list = decisions({ me: "me", prs: [pr(1, { author: "app/dependabot", review: "REVIEW_REQUIRED" }), pr(2, { author: "renovate[bot]", repo: "o/s" }), pr(3)] });
  assert.deepEqual(list.map((d) => d.kind), ["merge", "bots"]);
  assert.equal(list[1].where, "2 dependency PRs");
  assert.equal(list[1].why, "2 repos");
});
