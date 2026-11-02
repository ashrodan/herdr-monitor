// Zero-dependency Web Push: VAPID (RFC 8292) + message encryption
// (RFC 8291, aes128gcm content coding from RFC 8188). Only node: built-ins
// and the global fetch.
//
// VAPID keys live in <configDir>/vapid.json as a P-256 private JWK (0600).
// loadVapid() returns { publicKey, privateKey } where publicKey is the
// base64url 65-byte uncompressed point (the browser's applicationServerKey)
// and privateKey is a crypto KeyObject.

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const b64u = (buf) => Buffer.from(buf).toString('base64url');
const unb64u = (s) => (Buffer.isBuffer(s) ? s : Buffer.from(String(s), 'base64url'));
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();

export function configDir(env = process.env) {
  return env.HERDR_MONITOR_DIR || path.join(os.homedir(), '.config', 'herdr-monitor');
}

function writePrivate(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data, { mode: 0o600 });
  fs.chmodSync(file, 0o600); // writeFileSync's mode is ignored if the file already exists
}

// ---------------------------------------------------------------- VAPID keys

export function loadVapid(dir = configDir()) {
  const file = path.join(dir, 'vapid.json');
  let jwk;
  try {
    jwk = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    if (err.code !== 'ENOENT') throw err;
    const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    jwk = privateKey.export({ format: 'jwk' });
    writePrivate(file, JSON.stringify(jwk, null, 2) + '\n');
  }
  const privateKey = crypto.createPrivateKey({ key: jwk, format: 'jwk' });
  const publicKey = b64u(Buffer.concat([Buffer.from([4]), unb64u(jwk.x), unb64u(jwk.y)]));
  return { publicKey, privateKey };
}

export function vapidJwt({ audience, subject, privateKey, expSeconds = 12 * 3600, now = Date.now() }) {
  const header = b64u(JSON.stringify({ typ: 'JWT', alg: 'ES256' }));
  const claims = b64u(JSON.stringify({ aud: audience, exp: Math.floor(now / 1000) + expSeconds, sub: subject }));
  const data = `${header}.${claims}`;
  const sig = crypto.sign('sha256', Buffer.from(data), { key: privateKey, dsaEncoding: 'ieee-p1363' });
  return `${data}.${b64u(sig)}`;
}

// ------------------------------------------------- RFC 8291 key derivation

function toEcdh(k) {
  if (k && typeof k.computeSecret === 'function') return k;
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(unb64u(k));
  return ecdh;
}

function deriveKeys({ ecdhSecret, auth, uaPublic, asPublic, salt }) {
  const prkKey = hmac(auth, ecdhSecret);
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0'), uaPublic, asPublic]);
  const ikm = hmac(prkKey, Buffer.concat([keyInfo, Buffer.from([1])]));
  const prk = hmac(salt, ikm);
  const cek = hmac(prk, Buffer.from('Content-Encoding: aes128gcm\0\x01')).subarray(0, 16);
  const nonce = hmac(prk, Buffer.from('Content-Encoding: nonce\0\x01')).subarray(0, 12);
  return { cek, nonce };
}

// asKeyPair: optional ECDH object or raw private key (Buffer/base64url), for tests.
export function encrypt({ payload, p256dh, auth, salt, asKeyPair, rs = 4096 }) {
  const plaintext = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload));
  if (plaintext.length > rs - 16 - 1) throw new Error(`payload too large: ${plaintext.length} > ${rs - 17} bytes`);
  const uaPublic = unb64u(p256dh);
  const authSecret = unb64u(auth);
  salt = salt ? unb64u(salt) : crypto.randomBytes(16);
  if (salt.length !== 16) throw new Error('salt must be 16 bytes');
  let as;
  if (asKeyPair) as = toEcdh(asKeyPair);
  else { as = crypto.createECDH('prime256v1'); as.generateKeys(); }
  const asPublic = as.getPublicKey();
  const ecdhSecret = as.computeSecret(uaPublic);
  const { cek, nonce } = deriveKeys({ ecdhSecret, auth: authSecret, uaPublic, asPublic, salt });

  const cipher = crypto.createCipheriv('aes-128-gcm', cek, nonce);
  const ct = Buffer.concat([cipher.update(plaintext), cipher.update(Buffer.from([2])), cipher.final(), cipher.getAuthTag()]);

  const header = Buffer.alloc(16 + 4 + 1);
  salt.copy(header, 0);
  header.writeUInt32BE(rs, 16);
  header.writeUInt8(asPublic.length, 20);
  return Buffer.concat([header, asPublic, ct]);
}

// Receiver side (single record). uaPrivate: ECDH object or raw private key.
export function decrypt({ body, uaPrivate, auth }) {
  body = unb64u(body);
  const salt = body.subarray(0, 16);
  const rs = body.readUInt32BE(16);
  const idlen = body.readUInt8(20);
  const asPublic = body.subarray(21, 21 + idlen);
  const record = body.subarray(21 + idlen);
  if (record.length > rs) throw new Error('multi-record bodies are not supported');
  const ua = toEcdh(uaPrivate);
  const ecdhSecret = ua.computeSecret(asPublic);
  const { cek, nonce } = deriveKeys({ ecdhSecret, auth: unb64u(auth), uaPublic: ua.getPublicKey(), asPublic, salt });
  const decipher = crypto.createDecipheriv('aes-128-gcm', cek, nonce);
  decipher.setAuthTag(record.subarray(record.length - 16));
  const padded = Buffer.concat([decipher.update(record.subarray(0, record.length - 16)), decipher.final()]);
  let end = padded.length - 1;
  while (end >= 0 && padded[end] === 0) end--;
  if (end < 0 || padded[end] !== 2) throw new Error('bad padding delimiter');
  return padded.subarray(0, end);
}

// ------------------------------------------------------------ HTTP request

export function buildRequest(sub, payload, { vapid, subject = process.env.VAPID_SUBJECT || 'mailto:herdr-monitor@localhost', ttl = 3600, urgency = 'high' } = {}) {
  const url = sub.endpoint;
  const jwt = vapidJwt({ audience: new URL(url).origin, subject, privateKey: vapid.privateKey });
  const body = encrypt({ payload: JSON.stringify(payload), p256dh: sub.keys.p256dh, auth: sub.keys.auth });
  return {
    url,
    init: {
      method: 'POST',
      headers: {
        Authorization: `vapid t=${jwt}, k=${vapid.publicKey}`,
        'Content-Encoding': 'aes128gcm',
        'Content-Type': 'application/octet-stream',
        TTL: String(ttl),
        Urgency: urgency,
      },
      body,
    },
  };
}

// ----------------------------------------------------------- subscriptions

export class Subscriptions {
  constructor(dir = configDir()) {
    this.file = path.join(dir, 'subscriptions.json');
    try {
      const data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      this.subs = Array.isArray(data) ? data : [];
    } catch {
      this.subs = [];
    }
  }

  list() {
    return this.subs.slice();
  }

  add(sub) {
    let endpoint;
    try { endpoint = new URL(sub?.endpoint); } catch { throw new Error('invalid subscription: endpoint is not a URL'); }
    if (endpoint.protocol !== 'https:') throw new Error('invalid subscription: endpoint must be https');
    if (typeof sub.keys?.p256dh !== 'string' || typeof sub.keys?.auth !== 'string') {
      throw new Error('invalid subscription: keys.p256dh and keys.auth must be strings');
    }
    const clean = { endpoint: sub.endpoint, keys: { p256dh: sub.keys.p256dh, auth: sub.keys.auth } };
    this.subs = this.subs.filter((s) => s.endpoint !== clean.endpoint).concat(clean);
    this.save();
    return clean;
  }

  remove(endpoint) {
    const before = this.subs.length;
    this.subs = this.subs.filter((s) => s.endpoint !== endpoint);
    if (this.subs.length !== before) this.save();
    return this.subs.length !== before;
  }

  save() {
    writePrivate(this.file, JSON.stringify(this.subs, null, 2) + '\n');
  }
}

export async function sendPush(sub, payload, { vapid, subscriptions, fetchImpl = fetch, ...opts } = {}) {
  try {
    const { url, init } = buildRequest(sub, payload, { vapid, ...opts });
    const res = await fetchImpl(url, init);
    if ((res.status === 404 || res.status === 410) && subscriptions) subscriptions.remove(sub.endpoint);
    return { ok: res.ok, status: res.status };
  } catch (error) {
    return { ok: false, error };
  }
}
