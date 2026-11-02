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
