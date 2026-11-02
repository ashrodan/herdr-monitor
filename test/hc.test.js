import { test } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { HcConnection, splitLines, summarize } from '../lib/hc.js';

test('splitLines keeps partial lines for the next chunk', () => {
  const got = [];
  let buf = splitLines('', '{"a":1}\n{"b"', (l) => got.push(l));
  buf = splitLines(buf, ':2}\n\n', (l) => got.push(l));
  assert.deepEqual(got, ['{"a":1}', '{"b":2}']);
  assert.equal(buf, '');
});

test('summarize handles object and string status and picks the live cwd', () => {
  const s = summarize({ address: 't7', agent: 'claude-code', cwd: '/a', foreground: { cwd: '/b', name: 'node' },
    state: 'live', reported: true, status: { state: 'blocked', kind: 'permission' }, labels: { where: 'x / 1' } });
  assert.equal(s.cwd, '/b');
  assert.equal(s.status.state, 'blocked');
  assert.equal(s.where, 'x / 1');
  assert.equal(summarize({ address: 't1', status: 'working', argv: ['/bin/zsh'] }).status.state, 'working');
  assert.equal(summarize({ address: 't1', argv: ['/bin/zsh'] }).agent, 'zsh');
  // Claude Code's process name is its version; the reported app wins, then argv.
  assert.equal(summarize({ address: 't3', foreground: { argv: ['claude'], name: '2.1.314' }, status: { app: 'claude-code', state: 'done' } }).agent, 'claude-code');
  assert.equal(summarize({ address: 't3', foreground: { argv: ['claude'], name: '2.1.314' } }).agent, 'claude');
});

test('HcConnection matches replies by id, surfaces errors and emits events', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hcmon-'));
  const sock = path.join(dir, 'hc.sock');
  const server = net.createServer((c) => {
    let buf = '';
    c.on('data', (d) => {
      buf = splitLines(buf, d.toString(), (line) => {
        const m = JSON.parse(line);
        if (m.method === 'terminal.get') {
          c.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: 'not_found', data: { kind: 'not_found' } } }) + '\n');
        } else {
          // Event first, then the reply split across two writes.
          c.write(JSON.stringify({ jsonrpc: '2.0', method: 'event', params: { type: 'title', terminal: 't1', seq: 5, data: { title: 'hi' } } }) + '\n');
          const reply = JSON.stringify({ jsonrpc: '2.0', id: m.id, result: { seq: 4, terminals: [] } }) + '\n';
          c.write(reply.slice(0, 10)); setTimeout(() => c.write(reply.slice(10)), 5);
        }
      });
    });
  });
  await new Promise((r) => server.listen(sock, r));
  const hc = await new HcConnection(sock).connect();
  const events = [];
  hc.on('event', (e) => events.push(e));
  assert.deepEqual(await hc.call('events.subscribe', {}), { seq: 4, terminals: [] });
  await assert.rejects(hc.call('terminal.get', { terminal: 'nope' }), /not_found/);
  assert.equal(events[0].data.title, 'hi');
  hc.close(); server.close();
  fs.rmSync(dir, { recursive: true, force: true });
});
