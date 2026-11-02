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

test('publicFile serves known types inside public/ only', async () => {
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
