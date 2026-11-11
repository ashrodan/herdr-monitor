import { test } from 'node:test';
import assert from 'node:assert/strict';
import zlib from 'node:zlib';
import { EventEmitter } from 'node:events';
import { send, readJson, route, GZIP_MIN } from '../lib/http.js';

function fakeRes(acceptEncoding = '') {
  const res = { req: { headers: { 'accept-encoding': acceptEncoding } }, headersSent: false, destroyed: false };
  res.writeHead = (code, headers) => { res.code = code; res.headers = headers; res.headersSent = true; };
  res.end = (body) => { res.body = body; };
  return res;
}

test('send gzips a big answer when the client accepts gzip, and nothing else', async () => {
  const big = { text: 'x'.repeat(GZIP_MIN + 1) };
  const gz = fakeRes('gzip, deflate');
  await send(gz, 200, big);
  assert.equal(gz.headers['content-encoding'], 'gzip');
  assert.deepEqual(JSON.parse(zlib.gunzipSync(gz.body)), big);
  const plain = fakeRes('');
  await send(plain, 200, big);
  assert.equal(plain.headers['content-encoding'], undefined);
  const small = fakeRes('gzip');
  await send(small, 404, { error: 'not found' });
  assert.equal(small.headers['content-encoding'], undefined);
  assert.equal(small.body, '{"error":"not found"}');
});

test('send does nothing once the client is gone', async () => {
  const res = fakeRes('gzip');
  res.destroyed = true;
  await send(res, 200, { a: 1 });
  assert.equal(res.code, undefined);
});

function fakeReq(body) {
  const req = new EventEmitter();
  req.destroy = () => {};
  setImmediate(() => { if (body) req.emit('data', body); req.emit('end'); });
  return req;
}

test('readJson parses the body, and a bad or big body is a 400 or 413', async () => {
  assert.deepEqual(await readJson(fakeReq('{"a":1}')), { a: 1 });
  assert.deepEqual(await readJson(fakeReq('')), {});
  await assert.rejects(readJson(fakeReq('{bad')), (e) => e.status === 400);
  await assert.rejects(readJson(fakeReq('x'.repeat(20)), 10), (e) => e.status === 413);
});

test('route picks the first match, decodes params, and turns a throw into an answer', async () => {
  const api = route([
    ['GET', /^\/a\/([^/]+)$/, async (req, res, [p]) => send(res, 200, { p })],
    ['POST', /^\/bad$/, async () => { throw Object.assign(new Error('bad json'), { status: 400 }); }],
    ['GET', /^\/hc$/, async () => { throw new Error('hc not connected'); }],
  ]);
  const call = async (method, p) => { const res = fakeRes(); const hit = await api({ method }, res, new URL(p, 'http://x')); return { hit, res }; };
  let r = await call('GET', '/a/t%201');
  assert.equal(r.res.body, '{"p":"t 1"}');
  assert.equal((await call('POST', '/bad')).res.code, 400);
  r = await call('GET', '/hc');
  assert.equal(r.res.code, 502);
  assert.equal(JSON.parse(r.res.body).error, 'hc not connected');
  assert.equal((await call('GET', '/a/%E0%A4%A')).res.code, 400);
  assert.equal((await call('GET', '/none')).hit, false);
});
