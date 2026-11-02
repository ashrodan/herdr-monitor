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
