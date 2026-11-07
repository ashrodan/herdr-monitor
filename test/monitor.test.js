import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Monitor, cleanTitle } from '../lib/monitor.js';

// A fake hc link: answers calls from `answers`, records them, and can be
// closed by the test the way hc going away would.
class FakeHc extends EventEmitter {
  constructor(answers) { super(); this.answers = answers; this.calls = []; this.closed = false; }
  async call(method, params) {
    this.calls.push([method, params]);
    const a = this.answers[method];
    if (a instanceof Error) throw a;
    return typeof a === 'function' ? a(params) : a;
  }
  close() { this.closed = true; this.removeAllListeners('close'); this.removeAllListeners('event'); }
  drop() { this.emit('close'); }
}

const term = (address, extra = {}) => ({ address, state: 'live', title: 'Claude Code', status: { state: 'working' }, reported: true, ...extra });
const ANSWERS = () => ({
  'events.subscribe': { terminals: [term('t1'), term('t2', { title: '⠋ Build' })] },
  'doc.get': { value: { spaces: [] } },
  'terminal.get': ({ terminal }) => ({ terminal: term(terminal, { title: 'fresh' }) }),
});

async function setup(opts = {}) {
  const links = [];
  let fail = 0;
  const m = new Monitor({
    socket: '/x.sock', herdrDir: '/nowhere', readJson: async () => null, repoInfo: async () => null,
    retryMs: 10, coalesceMs: 40, refreshMs: 1, layoutMs: 1,
    connect: async () => {
      if (fail > 0) { fail--; throw new Error('ENOENT'); }
      const l = new FakeHc(opts.answers || ANSWERS()); links.push(l); return l;
    },
    ...opts.monitor,
  });
  const out = [];
  m.on('event', (name, data) => out.push([name, structuredClone(data)]));
  const states = [];
  m.on('state', (t, before, after) => states.push([t.address, before, after]));
  return { m, links, out, states, failNext: (n) => { fail = n; } };
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const terminalEvents = (out) => out.filter(([n]) => n === 'terminal');

test('cleanTitle drops spinner frames only', () => {
  assert.equal(cleanTitle('⠋ Build'), 'Build');
  assert.equal(cleanTitle('⣿⠿Build ⠋ done'), 'Build  done');
  assert.equal(cleanTitle('Claude Code'), 'Claude Code');
  assert.equal(cleanTitle(null), null);
});

test('start connects two links, loads the terminals, and says connected', async () => {
  const { m, links, out } = await setup();
  await m.start();
  assert.equal(links.length, 2); // rpc + events
  assert.equal(m.connected, true);
  assert.equal(m.terminals.get('t2').title, 'Build'); // cleaned in the snapshot too
  assert.deepEqual(out.map(([n]) => n), ['snapshot', 'connection']);
  m.stop();
});

test('a title event that only moves the spinner sends nothing', async () => {
  const { m, links, out } = await setup();
  await m.start();
  out.length = 0;
  const ev = links[1];
  for (const f of '⠋⠙⠹⠸⠼⠴') ev.emit('event', { type: 'title', terminal: 't2', data: { title: `${f} Build` } });
  assert.equal(terminalEvents(out).length, 0);
  ev.emit('event', { type: 'title', terminal: 't2', data: { title: '⠋ Test' } });
  assert.equal(terminalEvents(out).length, 1);
  assert.equal(terminalEvents(out)[0][1].title, 'Test');
  m.stop();
});

test('a burst of changes for one terminal sends the first at once and the last one later', async () => {
  const { m, links, out } = await setup();
  await m.start();
  out.length = 0;
  const ev = links[1];
  for (const title of ['a', 'b', 'c']) ev.emit('event', { type: 'title', terminal: 't1', data: { title } });
  ev.emit('event', { type: 'title', terminal: 't2', data: { title: 'other' } });
  assert.deepEqual(terminalEvents(out).map(([, t]) => [t.address, t.title]), [['t1', 'a'], ['t2', 'other']]);
  await wait(60);
  assert.deepEqual(terminalEvents(out).map(([, t]) => [t.address, t.title]), [['t1', 'a'], ['t2', 'other'], ['t1', 'c']]);
  m.stop();
});

test('a status change is reported at once, and a repeat of the same status sends nothing', async () => {
  const { m, links, out, states } = await setup();
  await m.start();
  out.length = 0;
  const ev = links[1];
  ev.emit('event', { type: 'status', terminal: 't1', data: { status: { state: 'blocked', kind: 'permission' }, reported: true, status_ms: 5 } });
  assert.deepEqual(states, [['t1', 'working', 'blocked']]);
  assert.equal(m.terminals.get('t1').changed_ms, 5);
  ev.emit('event', { type: 'status', terminal: 't1', data: { status: { state: 'blocked', kind: 'permission' }, reported: true } });
  await wait(60);
  assert.equal(terminalEvents(out).length, 1);
  assert.equal(states.length, 1);
  m.stop();
});

test('a held update for a terminal that is then removed is not sent', async () => {
  const answers = { ...ANSWERS(), 'terminal.get': Object.assign(new Error('not_found')) };
  const { m, links, out } = await setup({ answers });
  await m.start();
  const ev = links[1];
  ev.emit('event', { type: 'title', terminal: 't1', data: { title: 'a' } });
  ev.emit('event', { type: 'title', terminal: 't1', data: { title: 'b' } }); // held
  ev.emit('event', { type: 'exited', terminal: 't1' });                       // terminal.get -> not_found
  await wait(60);
  assert.ok(out.some(([n, d]) => n === 'removed' && d.address === 't1'));
  assert.equal(terminalEvents(out).filter(([, t]) => t.title === 'b').length, 0);
  assert.equal(m.terminals.has('t1'), false);
  m.stop();
});

test('when hc goes away it closes both links, says so, and connects again', async () => {
  const { m, links, out, failNext } = await setup();
  await m.start();
  failNext(1); // the first try fails: hc is still down
  links[0].drop();
  assert.equal(m.connected, false);
  assert.ok(links[0].closed && links[1].closed);
  assert.deepEqual(out.at(-1), ['connection', { connected: false }]);
  await wait(60);
  assert.equal(m.connected, true);
  assert.equal(links.length, 4);
  // A late close from an old link does nothing.
  links[1].emit('close');
  assert.equal(m.connected, true);
  m.stop();
});

test('a failed resync after "missed" reconnects instead of crashing', async () => {
  let n = 0;
  const answers = { ...ANSWERS(), 'events.subscribe': () => { if (++n === 2) throw new Error('boom'); return ANSWERS()['events.subscribe']; } };
  const { m, links } = await setup({ answers });
  await m.start();
  links[1].emit('event', { type: 'missed' });
  await wait(60);
  assert.equal(m.connected, true);
  assert.ok(links.length >= 4);
  m.stop();
});

test('stop ends the retries', async () => {
  const { m, links, failNext } = await setup();
  failNext(100);
  await m.start();
  m.stop();
  await wait(40);
  assert.equal(links.length, 0);
});

test('nameOf uses the name, else the space and a named tab', async () => {
  const { m } = await setup();
  m.layout = [{ id: 's1', name: 'app', tabs: [{ id: 'tb', name: 'tests', named: true }, { id: 'tc', name: '2', named: false }] }];
  assert.equal(m.nameOf({ name: 'mine' }), 'mine');
  assert.equal(m.nameOf({ space: 's1', tab: 'tb' }), 'app · tests');
  assert.equal(m.nameOf({ space: 's1', tab: 'tc' }), 'app');
  assert.equal(m.nameOf({ where: 'w / 1', agent: 'claude' }), 'w');
});
