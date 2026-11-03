import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { configDir, loadVapid, vapidJwt, encrypt, decrypt, buildRequest, Subscriptions, sendPush } from '../lib/push.js';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'herdr-push-'));
const b64u = (b) => Buffer.from(b).toString('base64url');

// RFC 8291 section 5 / Appendix A.
const RFC = {
  plaintext: 'When I grow up, I want to be a watermelon',
  asPrivate: 'yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw',
  asPublic: 'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8',
  uaPrivate: 'q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94',
  uaPublic: 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4',
  salt: 'DGv6ra1nlYgDCS1FRnbzlw',
  auth: 'BTBZMqHH6r4Tts7J_aSIgg',
  body:
    'DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml' +
    'mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT' +
    'pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN',
};

function uaKeys() {
  const ua = crypto.createECDH('prime256v1');
  ua.generateKeys();
  return { ua, p256dh: b64u(ua.getPublicKey()), auth: b64u(crypto.randomBytes(16)) };
}

test('configDir honours HERDR_MONITOR_DIR', () => {
  assert.equal(configDir({ HERDR_MONITOR_DIR: '/x/y' }), '/x/y');
  assert.equal(configDir({}), path.join(os.homedir(), '.config', 'herdr-monitor'));
});

test('RFC 8291 Appendix A: encrypt reproduces the exact body', () => {
  const body = encrypt({ payload: RFC.plaintext, p256dh: RFC.uaPublic, auth: RFC.auth, salt: RFC.salt, asKeyPair: RFC.asPrivate });
  assert.equal(b64u(body), RFC.body);
  // 86-byte header + 41 + 1 delimiter + 16 tag. (The RFC's example says
  // Content-Length: 145, but its own body decodes to 144 bytes.)
  assert.equal(body.length, 144);
});

test('RFC 8291 Appendix A: decrypt recovers the plaintext', () => {
  const out = decrypt({ body: RFC.body, uaPrivate: RFC.uaPrivate, auth: RFC.auth });
  assert.equal(out.toString(), RFC.plaintext);
});

test('random round trip and header layout', () => {
  const { ua, p256dh, auth } = uaKeys();
  const payload = JSON.stringify({ title: 'hi', body: 'agent needs input' });
  const body = encrypt({ payload, p256dh, auth });
  assert.equal(body.readUInt32BE(16), 4096);
  assert.equal(body[20], 65);
  const keyid = body.subarray(21, 86);
  assert.equal(keyid[0], 4);
  assert.notDeepEqual(keyid, ua.getPublicKey());
  // ciphertext = plaintext + delimiter + 16-byte tag
  assert.equal(body.length, 16 + 4 + 1 + 65 + Buffer.byteLength(payload) + 1 + 16);
  assert.equal(decrypt({ body, uaPrivate: ua, auth }).toString(), payload);
  // salts are random
  assert.notDeepEqual(encrypt({ payload, p256dh, auth }).subarray(0, 16), body.subarray(0, 16));
});

test('encrypt rejects payloads that do not fit one record', () => {
  const { p256dh, auth } = uaKeys();
  assert.ok(encrypt({ payload: Buffer.alloc(4096 - 17), p256dh, auth }));
  assert.throws(() => encrypt({ payload: Buffer.alloc(4096 - 16), p256dh, auth }), /too large/);
});

test('decrypt fails with the wrong auth secret', () => {
  const { ua, p256dh, auth } = uaKeys();
  const body = encrypt({ payload: 'x', p256dh, auth });
  assert.throws(() => decrypt({ body, uaPrivate: ua, auth: b64u(crypto.randomBytes(16)) }));
});

test('vapidJwt is a verifiable ES256 JWT with aud/exp/sub', () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const now = 1_700_000_000_000;
  const jwt = vapidJwt({ audience: 'https://push.example.net', subject: 'mailto:test@localhost', privateKey, now });
  const [h, c, s] = jwt.split('.');
  assert.doesNotMatch(jwt, /[=+/]/);
  assert.deepEqual(JSON.parse(Buffer.from(h, 'base64url')), { typ: 'JWT', alg: 'ES256' });
  assert.deepEqual(JSON.parse(Buffer.from(c, 'base64url')), {
    aud: 'https://push.example.net',
    exp: now / 1000 + 12 * 3600,
    sub: 'mailto:test@localhost',
  });
  const sig = Buffer.from(s, 'base64url');
  assert.equal(sig.length, 64);
  assert.ok(crypto.verify('sha256', Buffer.from(`${h}.${c}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, sig));
});

test('loadVapid persists the key pair with mode 0600', () => {
  const dir = path.join(tmp(), 'nested');
  const a = loadVapid(dir);
  const b = loadVapid(dir);
  assert.equal(a.publicKey, b.publicKey);
  assert.equal(Buffer.from(a.publicKey, 'base64url').length, 65);
  assert.equal(fs.statSync(path.join(dir, 'vapid.json')).mode & 0o777, 0o600);
  // the public key matches the private key
  const pub = crypto.createPublicKey(a.privateKey).export({ format: 'jwk' });
  assert.equal(b64u(Buffer.concat([Buffer.from([4]), Buffer.from(pub.x, 'base64url'), Buffer.from(pub.y, 'base64url')])), a.publicKey);
});

test('loadVapid default dir follows HERDR_MONITOR_DIR', () => {
  const dir = tmp();
  const prev = process.env.HERDR_MONITOR_DIR;
  process.env.HERDR_MONITOR_DIR = dir;
  try {
    const a = loadVapid();
    assert.ok(fs.existsSync(path.join(dir, 'vapid.json')));
    assert.equal(loadVapid().publicKey, a.publicKey);
  } finally {
    if (prev === undefined) delete process.env.HERDR_MONITOR_DIR;
    else process.env.HERDR_MONITOR_DIR = prev;
  }
});

test('buildRequest lays out headers and an encrypted body', () => {
  const vapid = loadVapid(tmp());
  const { ua, p256dh, auth } = uaKeys();
  const sub = { endpoint: 'https://fcm.googleapis.com/fcm/send/abc123', keys: { p256dh, auth } };
  const { url, init } = buildRequest(sub, { title: 'x' }, { vapid, ttl: 60, urgency: 'normal', subject: 'mailto:a@localhost' });
  assert.equal(url, sub.endpoint);
  assert.equal(init.method, 'POST');
  const m = init.headers.Authorization.match(/^vapid t=([\w-]+\.[\w-]+\.[\w-]+), k=([\w-]+)$/);
  assert.ok(m, init.headers.Authorization);
  assert.equal(m[2], vapid.publicKey);
  const claims = JSON.parse(Buffer.from(m[1].split('.')[1], 'base64url'));
  assert.equal(claims.aud, 'https://fcm.googleapis.com');
  assert.equal(claims.sub, 'mailto:a@localhost');
  assert.equal(init.headers['Content-Encoding'], 'aes128gcm');
  assert.equal(init.headers['Content-Type'], 'application/octet-stream');
  assert.equal(init.headers.TTL, '60');
  assert.equal(init.headers.Urgency, 'normal');
  assert.deepEqual(JSON.parse(decrypt({ body: init.body, uaPrivate: ua, auth })), { title: 'x' });
  const d = buildRequest(sub, {}, { vapid }).init.headers;
  assert.equal(d.TTL, '3600');
  assert.equal(d.Urgency, 'high');
});

test('Subscriptions add/dedupe/remove and persist with mode 0600', () => {
  const dir = tmp();
  const s = new Subscriptions(dir);
  const sub = { endpoint: 'https://push.example.net/a', keys: { p256dh: 'p', auth: 'a' }, expirationTime: null };
  s.add(sub);
  s.add({ ...sub, keys: { p256dh: 'p2', auth: 'a2' } });
  s.add({ endpoint: 'https://push.example.net/b', keys: { p256dh: 'p', auth: 'a' } });
  assert.equal(s.list().length, 2);
  assert.equal(s.list().find((x) => x.endpoint.endsWith('/a')).keys.p256dh, 'p2');
  assert.equal(fs.statSync(path.join(dir, 'subscriptions.json')).mode & 0o777, 0o600);
  assert.equal(new Subscriptions(dir).list().length, 2);
  assert.equal(s.remove('https://push.example.net/a'), true);
  assert.equal(s.remove('https://push.example.net/a'), false);
  assert.deepEqual(new Subscriptions(dir).list().map((x) => x.endpoint), ['https://push.example.net/b']);
  assert.throws(() => s.add({ endpoint: 'http://push.example.net/c', keys: { p256dh: 'p', auth: 'a' } }), /https/);
  assert.throws(() => s.add({ endpoint: 'nope', keys: { p256dh: 'p', auth: 'a' } }), /URL/);
  assert.throws(() => s.add({ endpoint: 'https://push.example.net/d', keys: { p256dh: 1, auth: 'a' } }), /keys/);
});

test('sendPush removes the subscription on 410 and 404, keeps it on 201', async () => {
  const dir = tmp();
  const vapid = loadVapid(dir);
  const subscriptions = new Subscriptions(dir);
  const { p256dh, auth } = uaKeys();
  const mk = (n) => subscriptions.add({ endpoint: `https://push.example.net/${n}`, keys: { p256dh, auth } });
  const [a, b, c] = [mk('a'), mk('b'), mk('c')];
  const calls = [];
  const stub = (status) => async (url, init) => { calls.push({ url, init }); return { ok: status < 300, status }; };

  assert.deepEqual(await sendPush(a, { t: 1 }, { vapid, subscriptions, fetchImpl: stub(201) }), { ok: true, status: 201 });
  assert.deepEqual(await sendPush(b, { t: 1 }, { vapid, subscriptions, fetchImpl: stub(410) }), { ok: false, status: 410 });
  assert.deepEqual(await sendPush(c, { t: 1 }, { vapid, subscriptions, fetchImpl: stub(404) }), { ok: false, status: 404 });
  assert.deepEqual(subscriptions.list().map((x) => x.endpoint), ['https://push.example.net/a']);
  assert.equal(calls[0].url, 'https://push.example.net/a');
  assert.equal(calls[0].init.method, 'POST');
});

test('sendPush never throws on network errors', async () => {
  const vapid = loadVapid(tmp());
  const { p256dh, auth } = uaKeys();
  const sub = { endpoint: 'https://push.example.net/a', keys: { p256dh, auth } };
  const r = await sendPush(sub, {}, { vapid, fetchImpl: async () => { throw new Error('ECONNRESET'); } });
  assert.equal(r.ok, false);
  assert.match(r.error.message, /ECONNRESET/);
});
