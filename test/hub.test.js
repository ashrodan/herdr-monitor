import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Hub, SSE_HEADERS } from '../lib/hub.js';

// A response that records what it was sent; `writableLength` is what the
// socket has not taken yet.
function fakeRes() {
  const res = { frames: [], head: null, writableLength: 0, destroyed: false, writableEnded: false };
  res.writeHead = (code, headers) => { res.head = [code, headers]; };
  res.write = (f) => { res.frames.push(f); return true; };
  res.end = () => { res.writableEnded = true; };
  res.destroy = () => { res.destroyed = true; };
  return res;
}

test('Hub opens a stream with its first frame and sends to every client', () => {
  const hub = new Hub({ pingMs: 60000 });
  const [a, b] = [fakeRes(), fakeRes()];
  hub.open(new EventEmitter(), a, 'event: snapshot\ndata: {}\n\n');
  hub.open(new EventEmitter(), b);
  assert.deepEqual(a.head, [200, SSE_HEADERS]);
  hub.send('x');
  assert.deepEqual(a.frames, ['event: snapshot\ndata: {}\n\n', 'x']);
  assert.deepEqual(b.frames, ['x']);
  hub.close();
});

test('Hub forgets a client when its request closes', () => {
  const hub = new Hub({ pingMs: 60000 });
  const req = new EventEmitter(), res = fakeRes();
  hub.open(req, res);
  req.emit('close');
  hub.send('x');
  assert.equal(hub.size, 0);
  assert.deepEqual(res.frames, []);
  hub.close();
});

test('Hub cuts off a client that cannot keep up, and keeps the others', () => {
  const hub = new Hub({ pingMs: 60000, maxBuffered: 100 });
  const slow = fakeRes(), ok = fakeRes();
  hub.open(new EventEmitter(), slow);
  hub.open(new EventEmitter(), ok);
  slow.writableLength = 101;
  hub.send('x');
  assert.equal(slow.destroyed, true);
  assert.deepEqual(slow.frames, []);
  assert.deepEqual(ok.frames, ['x']);
  assert.equal(hub.size, 1);
  assert.equal(hub.dropped, 1);
  hub.close();
});

test('Hub pings every client on one timer, and close ends every stream', async () => {
  const hub = new Hub({ pingMs: 10 });
  const res = fakeRes();
  hub.open(new EventEmitter(), res);
  await new Promise((r) => setTimeout(r, 35));
  assert.ok(res.frames.filter((f) => f === ': ping\n\n').length >= 2);
  hub.close();
  assert.equal(res.writableEnded, true);
  assert.equal(hub.size, 0);
});
