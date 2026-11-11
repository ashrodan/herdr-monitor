// Small HTTP helpers for server.js: answers, request bodies, routing.
import zlib from 'node:zlib';
import { promisify } from 'node:util';

const gzip = promisify(zlib.gzip);
export const GZIP_MIN = 16 * 1024;

// Big answers (a long transcript is ~700 KB) go gzipped: over Tailscale to a
// phone that is most of the wait. gzip runs on the thread pool, not on the
// event loop, so other clients do not wait for it.
export async function send(res, code, body, type = 'application/json; charset=utf-8') {
  let data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  const headers = { 'content-type': type, 'cache-control': 'no-store' };
  if (data.length > GZIP_MIN && /\bgzip\b/.test(res.req?.headers['accept-encoding'] || '')) {
    data = await gzip(data, { level: 4 });
    headers['content-encoding'] = 'gzip'; headers.vary = 'accept-encoding';
  }
  if (res.headersSent || res.destroyed) return;
  res.writeHead(code, headers);
  res.end(data);
}

export function readJson(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > limit) { reject(Object.assign(new Error('too large'), { status: 413 })); req.destroy(); } });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch { reject(Object.assign(new Error('bad json'), { status: 400 })); } });
    req.on('error', reject);
  });
}

// A request body as bytes, at most `limit` (hc's put takes 8 MB).
export const MAX_UPLOAD = 8 * 1024 * 1024;
export function readBytes(req, limit = MAX_UPLOAD) {
  return new Promise((resolve, reject) => {
    const parts = []; let size = 0;
    req.on('data', (c) => { size += c.length; if (size > limit) { reject(Object.assign(new Error('image too large (8 MB at most)'), { status: 413 })); req.destroy(); } else parts.push(c); });
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
}

// [method, regex, handler] in order; the first match answers. A handler
// that throws answers 400/413 for a bad body, else 502 (hc or gh failed).
export function route(routes) {
  return async (req, res, url) => {
    for (const [method, re, handler] of routes) {
      const m = req.method === method && url.pathname.match(re);
      if (!m) continue;
      let params;
      try { params = m.slice(1).map(decodeURIComponent); } catch { await send(res, 400, { error: 'bad path' }); return true; }
      try { await handler(req, res, params, url); }
      catch (err) { await send(res, err.status || 502, { error: err.message, rpc: err.rpc ?? null }); }
      return true;
    }
    return false;
  };
}
