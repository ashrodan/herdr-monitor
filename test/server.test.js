import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sameOrigin } from '../server.js';

test('sameOrigin only accepts the page own origin', () => {
  const req = (origin, host = '127.0.0.1:7400') => ({ headers: { origin, host } });
  assert.equal(sameOrigin(req('http://127.0.0.1:7400')), true);
  assert.equal(sameOrigin(req('http://evil.example')), false);
  assert.equal(sameOrigin(req(undefined)), false);
  assert.equal(sameOrigin(req('not a url')), false);
});

test('publicFile serves known types inside dist/ only', async () => {
  const { publicFile } = await import('../server.js');
  const root = '/srv/public';
  assert.equal(publicFile('/', root), '/srv/public/index.html');
  assert.equal(publicFile('/app.js', root), '/srv/public/app.js');
  assert.equal(publicFile('/icons/icon-192.png', root), '/srv/public/icons/icon-192.png');
  assert.equal(publicFile('/../server.js', root), null);
  assert.equal(publicFile('/%2e%2e/secret.css', root), null);
  assert.equal(publicFile('/notes.txt', root), null);
  assert.equal(publicFile('/%E0%A4%A', root), null);
});

test('parseStore reads Herdr agent store, as an object or a JSON string', async () => {
  const { parseStore } = await import('../server.js');
  const changed = { t1: { s: 'idle', at: 10 }, t2: { s: 'done', at: 20 }, t3: { s: 'idle' } };
  for (const store of [{ changed }, { changed: JSON.stringify(changed) }]) {
    const { at, seen } = parseStore(store);
    assert.deepEqual([...at], [['t1', 10], ['t2', 20]]);
    assert.deepEqual([...seen], [['t1', 10]]);
  }
  assert.equal(parseStore(null).at.size, 0);
  assert.equal(parseStore({ changed: '{bad' }).at.size, 0);
});

test('pushPayload names the agent and links to it', async () => {
  const { pushPayload } = await import('../server.js');
  const t = { address: 't7', title: 'Fix the build', status: { state: 'blocked', kind: 'permission' } };
  assert.deepEqual(pushPayload(t, 'blocked', 'app · tests'), { title: 'app · tests · needs you', body: 'Fix the build', tag: 't7', url: '/#t7' });
  assert.equal(pushPayload({ address: 't8', status: { kind: 'permission' } }, 'blocked', 'x').body, 'Waiting at a permission');
  assert.equal(pushPayload({ address: 't9', status: { msg: 'All tests pass' } }, 'done', 'x').title, 'x · done');
});

test('stripScriptNoise drops the ^D echo of script only at the start', async () => {
  const { stripScriptNoise } = await import('../server.js');
  assert.equal(stripScriptNoise(Buffer.from('^D\b\b\x1b[Hhi')).toString(), '\x1b[Hhi');
  assert.equal(stripScriptNoise(Buffer.from('\x1b[Hhi')).toString(), '\x1b[Hhi');
});

test('attachEnv drops HC_TERMINAL_ID and sets the socket and TERM', async () => {
  const { attachEnv } = await import('../server.js');
  const env = attachEnv({ HC_TERMINAL_ID: 't4y', PATH: '/bin' });
  assert.equal(env.HC_TERMINAL_ID, undefined);
  assert.equal(env.PATH, '/bin');
  assert.equal(env.TERM, 'xterm-256color');
  assert.ok(env.HC_TERMINAL_SOCKET);
});

test('httpsLocation keeps the host and path, and refuses odd hosts', async () => {
  const { httpsLocation } = await import('../server.js');
  assert.equal(httpsLocation('box.tail1.ts.net', '/#x', 7400), 'https://box.tail1.ts.net:7400/#x');
  assert.equal(httpsLocation('box.tail1.ts.net:80', '/api/health?a=1', 7400), 'https://box.tail1.ts.net:7400/api/health?a=1');
  assert.equal(httpsLocation('box.ts.net', '/', 443), 'https://box.ts.net/');
  assert.equal(httpsLocation('evil.com/@x', '/', 7400), null);
  assert.equal(httpsLocation('', '/', 7400), null);
  assert.equal(httpsLocation('box.ts.net', '//evil.com', 7400), 'https://box.ts.net:7400//evil.com');
});

test('httpsLocation expands the short MagicDNS name to the full one', async () => {
  const { httpsLocation } = await import('../server.js');
  assert.equal(httpsLocation('box', '/', 7400, 'box.tail1.ts.net'), 'https://box.tail1.ts.net:7400/');
  assert.equal(httpsLocation('other', '/', 7400, 'box.tail1.ts.net'), 'https://other:7400/');
});
