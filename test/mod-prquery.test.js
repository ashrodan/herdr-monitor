import test from "node:test";
import assert from "node:assert/strict";
import { ghRepo, prQueries, parsePrQuery, checksOf } from "../herdr-mod/summary/prquery.js";


test("ghRepo: https, ssh and scp remotes; anything else is null", () => {
  assert.equal(ghRepo("https://github.com/ashrodan/herdr-monitor.git\n"), "ashrodan/herdr-monitor");
  assert.equal(ghRepo("git@github.com:dashlytix/dash-ai-agent.git"), "dashlytix/dash-ai-agent");
  assert.equal(ghRepo("ssh://git@github.com/o/r"), "o/r");
  assert.equal(ghRepo("https://gitlab.com/o/r.git"), null);
  assert.equal(ghRepo(""), null);
});

test("prQueries: one alias per repo, 20 repos per query, names can't break out", () => {
  const names = Array.from({ length: 45 }, (_, i) => `o/r${i}`);
  const qs = prQueries(names);
  assert.equal(qs.length, 3);
  assert.match(qs[1], /r20: repository\(owner: "o", name: "r20"\)/);
  assert.doesNotMatch(qs[0], /r20:/);
  const evil = prQueries(['o/x") { viewer { login } } y: repository(owner: "a']);
  assert.match(evil[0], /name: "x\\"\) \{ viewer/);
});

test("parsePrQuery: rollup flattened the way checksOf reads it; missing repos skipped", () => {
  const data = {
    r0: { pullRequests: { nodes: [{ number: 1, commits: { nodes: [{ commit: { statusCheckRollup: { contexts: { nodes: [
      { __typename: "CheckRun", status: "COMPLETED", conclusion: "FAILURE" }, { __typename: "StatusContext", state: "PENDING" }] } } } }] } }] } },
    r1: null,
  };
  const got = parsePrQuery(data, ["o/a", "o/b"]);
  assert.deepEqual(Object.keys(got), ["o/a"]);
  assert.deepEqual(checksOf(got["o/a"][0].statusCheckRollup), { fail: 1, pending: 1, pass: 0 });
  assert.deepEqual(parsePrQuery({ r0: { pullRequests: { nodes: [{ number: 2 }] } } }, ["o/a"])["o/a"][0].statusCheckRollup, []);
});
