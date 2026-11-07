import { test } from 'node:test';
import assert from 'node:assert/strict';
import { listenOf, machineHost, portFromArgv, portFromText } from '../lib/apps.js';

test('portFromArgv: --port N and --port=N only', () => {
  assert.deepEqual(portFromArgv(['node', './node_modules/.bin/vite', '--port', '5223', '--strictPort', '--host', '0.0.0.0']), { scheme: 'http', port: 5223 });
  assert.deepEqual(portFromArgv(['next', 'dev', '--port=3001']), { scheme: 'http', port: 3001 });
  assert.equal(portFromArgv(['npm', 'run', 'dev']), null);
  assert.equal(portFromArgv(['x', '--port']), null);
  assert.equal(portFromArgv(['x', '--port', '99999']), null);
  assert.equal(portFromArgv(null), null);
});

test('portFromText: a local URL, keeping https', () => {
  assert.deepEqual(portFromText('  ➜  Local:   http://localhost:5173/'), { scheme: 'http', port: 5173 });
  assert.deepEqual(portFromText('ready on https://127.0.0.1:8443'), { scheme: 'https', port: 8443 });
  assert.equal(portFromText('see https://example.com:8080'), null);
  assert.equal(portFromText(''), null);
});

test('listenOf: argv, then title, then the screen', () => {
  assert.equal(listenOf({ argv: ['x', '--port', '3001'], title: 'http://localhost:3002', screen: 'http://localhost:3003' }).port, 3001);
  assert.equal(listenOf({ argv: ['npm', 'run', 'dev'], title: 'http://localhost:3002' }).port, 3002);
  assert.equal(listenOf({ argv: ['npm', 'run', 'dev'], screen: '> vite\n\n  Local: http://localhost:5173/\n' }).port, 5173);
  assert.equal(listenOf({ argv: ['zsh'] }), null);
  assert.equal(listenOf(), null);
});

test('machineHost: a remote terminal on its machine tailnet name', () => {
  const machines = [{ name: 'dai', target: 'dai', host: 'herdr-1' }, { name: 'box', host: 'nowhere' }];
  const peers = [{ name: 'herdr-1.tail1.ts.net' }, { name: 'mac.tail1.ts.net' }];
  assert.equal(machineHost('t8t', machines, peers), null);
  assert.equal(machineHost('dai/t1', machines, peers), 'herdr-1.tail1.ts.net');
  assert.equal(machineHost('box/t1', machines, peers), null);
  assert.equal(machineHost('gone/t1', machines, peers), null);
});
