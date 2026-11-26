import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildRequest, parseResponse, toAction, branchOf, slug } from '../lib/ask.js';

const context = {
  spaces: [{ id: 'sp1', name: 'dash', folder: '/r/dash', machine: null }, { id: 'sp2', name: 'mon', folder: '/r/mon', machine: 'dai' }],
  agents: [{ address: 't5', space: 'mon', title: 'android', status: 'working' }],
  machines: [{ name: 'dai' }],
};
const call = (name, args) => ({ candidates: [{ content: { parts: [{ functionCall: { name, args } }] } }] });

test('buildRequest: context in the system text, history then the message, tools on', () => {
  const r = buildRequest({ message: 'hi', history: [{ role: 'user', text: 'a' }, { role: 'model', text: 'b' }, { role: 'user', text: '' }], context });
  assert.match(r.systemInstruction.parts[0].text, /"sp1"/);
  assert.deepEqual(r.contents.map((c) => [c.role, c.parts[0].text]), [['user', 'a'], ['model', 'b'], ['user', 'hi']]);
  assert.equal(r.tools[0].functionDeclarations.length, 3);
});

test('buildRequest: keeps only the last 12 turns', () => {
  const history = Array.from({ length: 20 }, (_, i) => ({ role: 'user', text: String(i) }));
  assert.equal(buildRequest({ message: 'x', history, context }).contents.length, 13);
});

test('spawn: a known space becomes a card with a checked branch', () => {
  const { actions, dropped } = parseResponse(call('spawn_agent', { space_id: 'sp1', title: 'Fix dates', brief: 'b', agent: 'claude', worktree: true, branch: 'fix/dates' }), context);
  assert.deepEqual(dropped, []);
  assert.deepEqual(actions[0], { type: 'spawn', space_id: 'sp1', space_name: 'dash', machine: null, folder: '/r/dash', title: 'Fix dates', brief: 'b', agent: 'claude', worktree: true, branch: 'fix/dates' });
});

test('spawn: attack probes — unknown space, odd agent, shell in the branch', () => {
  assert.deepEqual(toAction({ name: 'spawn_agent', args: { space_id: 'nope' } }, context), { drop: 'no space nope' });
  const a = toAction({ name: 'spawn_agent', args: { space_id: 'sp1', title: 'X Y', brief: 'b', agent: 'rm -rf', worktree: true, branch: 'feat/x; rm -rf ~' } }, context);
  assert.equal(a.agent, 'claude');
  assert.equal(a.branch, 'feat/x-y');
  assert.equal(branchOf('main', 'Do it'), 'feat/do-it');
  assert.equal(branchOf('../../etc', 'Do it'), 'feat/do-it');
});

test('spawn: worktree false runs in the folder with no branch', () => {
  const a = toAction({ name: 'spawn_agent', args: { space_id: 'sp2', title: 'Read', brief: 'b', agent: 'codex', worktree: false } }, context);
  assert.equal(a.branch, null);
  assert.equal(a.machine, 'dai');
  assert.equal(a.agent, 'codex');
});

test('tell and hand_off: only known agents and machines', () => {
  assert.equal(toAction({ name: 'tell_agent', args: { terminal: 't9', text: 'x' } }, context).drop, 'no agent t9');
  assert.equal(toAction({ name: 'tell_agent', args: { terminal: 't5', text: ' ' } }, context).drop, 'empty message');
  assert.equal(toAction({ name: 'tell_agent', args: { terminal: 't5', text: 'push' } }, context).label, 'mon · android');
  assert.equal(toAction({ name: 'hand_off', args: { space_id: 'sp1', machine: 'evil' } }, context).machine, null);
  assert.equal(toAction({ name: 'hand_off', args: { space_id: 'sp1', machine: 'dai' } }, context).machine, 'dai');
  assert.equal(toAction({ name: 'exec_shell', args: {} }, context).drop, 'unknown tool exec_shell');
});

test('parseResponse: text only, thoughts left out, empty answers explained', () => {
  const r = parseResponse({ candidates: [{ content: { parts: [{ text: 'secret', thought: true }, { text: 'Two waiting.' }] } }] }, context);
  assert.deepEqual(r, { reply: 'Two waiting.', actions: [], dropped: [] });
  assert.equal(parseResponse({ promptFeedback: { blockReason: 'SAFETY' } }, context).reply, 'No answer (SAFETY).');
  assert.equal(parseResponse({}, context).reply, 'No answer.');
});

test('slug: plain words only', () => {
  assert.equal(slug('Fix the Date tests!'), 'fix-the-date-tests');
  assert.equal(slug('***'), 'task');
});
