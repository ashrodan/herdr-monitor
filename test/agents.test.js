import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AgentFeed, agentOf, sseFrame } from '../lib/agents.js';

const term = (address, state, extra = {}) => ({
  address, title: 'a title', changed_ms: 5, seen_ms: null,
  status: state ? { state, kind: extra.kind ?? null, msg: extra.msg ?? null } : null,
  ...extra.t,
});
const nameOf = (t) => t.name || `n-${t.address}`;

test('agentOf: only terminals that report a status', () => {
  assert.equal(agentOf(term('t1', null), 'x'), null);
  assert.equal(agentOf({ address: 't1', status: { app: 'claude' } }, 'x'), null);
  assert.deepEqual(agentOf(term('t1', 'blocked', { kind: 'permission', msg: 'Run ls?' }), 'app'), {
    address: 't1', name: 'app', state: 'blocked', kind: 'permission', msg: 'Run ls?', changed_ms: 5, seen_ms: null,
    harness: null, space_id: null, space_name: null, branch: null,
  });
});

test('agentOf: context from the terminal and its space, not from the name', () => {
  const a = agentOf(term('t1', 'working', { t: { agent: 'claude', space: 'sp1' } }), 'app · review', { name: 'herdr-monitor', branch: 'feat/x' });
  assert.equal(a.harness, 'claude');
  assert.equal(a.space_id, 'sp1');
  assert.equal(a.space_name, 'herdr-monitor');
  assert.equal(a.branch, 'feat/x');
});

test('diff: a context change counts, title churn still does not', () => {
  const feed = new AgentFeed();
  let sp = { name: 'app', branch: 'main' };
  const ts = [term('t1', 'working', { t: { agent: 'claude', space: 'sp1' } })];
  const spaceOf = () => sp;
  feed.diff(ts, nameOf, spaceOf);
  ts[0].title = 'other title';
  assert.deepEqual(feed.diff(ts, nameOf, spaceOf), [], 'title churn');
  sp = { name: 'app', branch: 'feat/y' };
  const out = feed.diff(ts, nameOf, spaceOf);
  assert.equal(out.length, 1, 'branch counts');
  assert.equal(out[0][1].branch, 'feat/y');
  ts[0].space = 'sp2';
  assert.equal(feed.diff(ts, nameOf, spaceOf).length, 1, 'space counts');
});

test('snapshot lists the agents and skips shells', () => {
  const agents = AgentFeed.snapshot([term('t1', 'working'), term('t2', null), term('t3', 'done')], nameOf);
  assert.deepEqual(agents.map((a) => [a.address, a.state, a.name]), [['t1', 'working', 'n-t1'], ['t3', 'done', 'n-t3']]);
});

test('diff: new agents, then only real changes', () => {
  const feed = new AgentFeed();
  const ts = [term('t1', 'working'), term('t2', null)];
  assert.deepEqual(feed.diff(ts, nameOf).map(([e, d]) => [e, d.address]), [['agent', 't1']]);
  assert.deepEqual(feed.diff(ts, nameOf), [], 'no change, no event');

  ts[0].title = 'a new title';
  ts[0].changed_ms = 99;
  assert.deepEqual(feed.diff(ts, nameOf), [], 'title churn does not count');

  ts[0].status = { state: 'blocked', kind: 'permission', msg: null };
  let out = feed.diff(ts, nameOf);
  assert.equal(out.length, 1);
  assert.equal(out[0][1].state, 'blocked');

  ts[0].status = { state: 'blocked', kind: 'permission', msg: 'Allow edit?' };
  assert.equal(feed.diff(ts, nameOf)[0][1].msg, 'Allow edit?', 'msg counts');

  ts[0].name = 'renamed';
  assert.equal(feed.diff(ts, nameOf)[0][1].name, 'renamed', 'name counts');

  ts[0].seen_ms = 100;
  assert.equal(feed.diff(ts, nameOf).length, 1, 'seen on the Mac counts');
});

test('diff: removed when the terminal goes or stops reporting', () => {
  const feed = new AgentFeed();
  const ts = [term('t1', 'working'), term('t2', 'done')];
  feed.diff(ts, nameOf);
  ts[0].status = null;
  assert.deepEqual(feed.diff(ts, nameOf), [['removed', { address: 't1' }]]);
  assert.deepEqual(feed.diff([], nameOf), [['removed', { address: 't2' }]]);
  assert.deepEqual(feed.diff([], nameOf), []);
});

test('sseFrame', () => {
  assert.equal(sseFrame('removed', { address: 't1' }), 'event: removed\ndata: {"address":"t1"}\n\n');
});
