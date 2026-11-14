// A Model Context Protocol endpoint over Streamable HTTP, with no
// dependencies. One POST endpoint serves two eras of the protocol:
//
//   legacy  2025-03-26 .. 2025-11-25: `initialize`, then plain requests.
//           Stateless: no Mcp-Session-Id is minted, GET answers 405.
//   modern  2026-07-28: every request carries its version in
//           params._meta; `server/discover`; `subscriptions/listen` opens
//           a long-lived SSE stream of change notifications.
//
// Also the Skills extension (SEP-2640): skills/list, skills/get, and the
// skill files as skill:// resources.
//
// What the server offers (tools, resources, skills, change events) comes
// from the `app` object; this file only speaks the protocol. Every request
// runs in a `scope` that the caller resolves from the URL.
import crypto from 'node:crypto';
import { readJson } from './http.js';
import { SSE_HEADERS } from './hub.js';

export const MODERN = '2026-07-28';
export const LEGACY = ['2025-11-25', '2025-06-18', '2025-03-26'];
export const SUPPORTED = [MODERN, ...LEGACY];
const M = 'io.modelcontextprotocol/';
const SKILLS_EXT = `${M}skills`;

export const CODE = { parse: -32700, invalid: -32600, method: -32601, params: -32602, internal: -32603, header: -32020, version: -32022 };

export class RpcError extends Error {
  constructor(code, message, data) { super(message); this.code = code; this.data = data; }
}

// The bearer token: compared in constant time.
export function bearerOk(req, token) {
  const m = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '');
  if (!m || !token) return false;
  const a = Buffer.from(m[1]), b = Buffer.from(token);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

// An Origin header, when present, must name this host (DNS rebinding).
// Server-side clients send none.
export function originOk(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  try { return new URL(origin).host === req.headers.host; } catch { return false; }
}

const isModern = (msg) => typeof msg?.params?._meta?.[`${M}protocolVersion`] === 'string';

// A header value may be sent as =?base64?...?= (Mcp-Name, Mcp-Param-*).
export function headerValue(v) {
  const m = /^=\?base64\?(.*)\?=$/.exec(v ?? '');
  return m ? Buffer.from(m[1], 'base64').toString('utf8') : v;
}

export function createMcp({ name, version, app, maxStreams = 16 }) {
  const streams = new Set(); // open subscriptions/listen responses

  const capabilities = (era) => ({
    tools: { listChanged: false },
    resources: { subscribe: era === 'modern', listChanged: false },
    extensions: { [SKILLS_EXT]: {} },
  });
  const serverInfo = { name, version };

  // One JSON-RPC request -> its result. Throws RpcError.
  async function dispatch(msg, scope, era) {
    const p = msg.params || {};
    const done = (r) => (era === 'modern' ? { resultType: 'complete', ...r } : r);
    switch (msg.method) {
      case 'initialize': {
        if (era === 'modern') throw new RpcError(CODE.method, 'initialize is not part of 2026-07-28; use server/discover');
        const asked = p.protocolVersion;
        return { protocolVersion: LEGACY.includes(asked) ? asked : LEGACY[0], capabilities: capabilities('legacy'), serverInfo, instructions: app.instructions(scope) };
      }
      case 'server/discover':
        return done({ supportedVersions: SUPPORTED, capabilities: capabilities('modern'), _meta: { [`${M}serverInfo`]: serverInfo }, instructions: app.instructions(scope) });
      case 'ping': return done({});
      case 'logging/setLevel': return done({});
      case 'tools/list':
        return done({ tools: app.tools(scope).map(({ name, title, description, inputSchema, annotations }) => ({ name, title, description, inputSchema, annotations })) });
      case 'tools/call': {
        const tool = app.tools(scope).find((t) => t.name === p.name);
        if (!tool) throw new RpcError(CODE.params, `Unknown tool: ${p.name}. Call tools/list.`);
        try {
          const out = await tool.run(p.arguments || {}, { scope });
          return done({ content: [{ type: 'text', text: String(out) }] });
        } catch (err) {
          // A failed tool is a result the model reads, not a protocol error.
          return done({ isError: true, content: [{ type: 'text', text: err.message || String(err) }] });
        }
      }
      case 'resources/list':
        return done({ resources: [...app.resources.list(scope), ...app.skills.resources()] });
      case 'resources/templates/list': return done({ resourceTemplates: [] });
      case 'resources/read': {
        const uri = String(p.uri || '');
        const r = uri.startsWith('skill://') ? app.skills.read(uri) : await app.resources.read(uri, scope);
        if (!r) throw new RpcError(CODE.params, `Resource not found: ${uri}`, { uri });
        return done({ contents: [{ uri, mimeType: r.mimeType, text: r.text }] });
      }
      case 'skills/list': return done({ skills: app.skills.list() });
      case 'skills/get': {
        const skill = app.skills.get(String(p.uri || ''));
        if (!skill) throw new RpcError(CODE.params, `Not a skill this server serves: ${p.uri}`);
        return done({ skill });
      }
      default:
        throw new RpcError(CODE.method, `Method not found: ${msg.method}`);
    }
  }

  const reply = (id, result) => ({ jsonrpc: '2.0', id, result });
  const failure = (id, err) => ({ jsonrpc: '2.0', id: id ?? null, error: { code: err.code ?? CODE.internal, message: err.message, ...(err.data ? { data: err.data } : {}) } });

  // Check the version and the mirrored headers of one message. Returns an
  // RpcError, or null when the message may run.
  function check(req, msg, era) {
    const header = req.headers['mcp-protocol-version'];
    if (era === 'modern') {
      const v = msg.params._meta[`${M}protocolVersion`];
      if (v !== MODERN) return new RpcError(CODE.version, 'Unsupported protocol version', { supported: SUPPORTED, requested: v });
      if (header && header !== v) return new RpcError(CODE.header, `Header mismatch: MCP-Protocol-Version ${header} does not match body ${v}`);
      const method = req.headers['mcp-method'];
      if (method && method !== msg.method) return new RpcError(CODE.header, `Header mismatch: Mcp-Method ${method} does not match body ${msg.method}`);
      const nameHeader = req.headers['mcp-name'];
      const nameBody = msg.params.name ?? msg.params.uri;
      if (nameHeader && nameBody != null && headerValue(nameHeader) !== String(nameBody)) return new RpcError(CODE.header, 'Header mismatch: Mcp-Name does not match the body');
      return null;
    }
    if (header && !SUPPORTED.includes(header)) return new RpcError(CODE.version, 'Unsupported protocol version', { supported: SUPPORTED, requested: header });
    return null;
  }

  // HTTP status for an error: modern clients read 400/404 to tell eras apart.
  const statusOf = (err, era) => {
    if (err.code === CODE.version || err.code === CODE.header || err.code === CODE.parse || err.code === CODE.invalid) return 400;
    if (era === 'modern' && err.code === CODE.method) return 404;
    return 200;
  };

  function sendJson(res, status, body) {
    if (res.headersSent) return;
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify(body));
  }

  // subscriptions/listen: acknowledge the filter we honour, then send
  // notifications/resources/updated until the client closes the stream.
  function listen(req, res, msg, scope) {
    if (streams.size >= maxStreams) return sendJson(res, 200, failure(msg.id, new RpcError(CODE.internal, 'Too many open subscriptions')));
    const id = msg.id;
    const wanted = msg.params?.notifications || {};
    const known = new Set(app.resources.list(scope).map((r) => r.uri));
    const uris = (Array.isArray(wanted.resourceSubscriptions) ? wanted.resourceSubscriptions : []).filter((u) => known.has(u));
    const honored = uris.length ? { resourceSubscriptions: uris } : {};
    const meta = { [`${M}subscriptionId`]: id };
    res.writeHead(200, { ...SSE_HEADERS, 'x-accel-buffering': 'no' });
    const write = (m) => { if (!res.writableEnded) res.write(`event: message\ndata: ${JSON.stringify(m)}\n\n`); };
    write({ jsonrpc: '2.0', method: 'notifications/subscriptions/acknowledged', params: { _meta: meta, notifications: honored } });
    const unwatch = uris.length ? app.watch(scope, (uri) => { if (uris.includes(uri)) write({ jsonrpc: '2.0', method: 'notifications/resources/updated', params: { _meta: meta, uri } }); }) : () => {};
    const ping = setInterval(() => { if (!res.writableEnded) res.write(':\n\n'); }, 15000);
    ping.unref?.();
    const stream = {
      // A graceful end (shutdown): the listen request's own response.
      end() { write({ jsonrpc: '2.0', id, result: { resultType: 'complete', _meta: meta } }); res.end(); },
      stop() { clearInterval(ping); unwatch(); streams.delete(stream); },
    };
    streams.add(stream);
    req.on('close', () => stream.stop());
  }

  // The HTTP handler. The caller has checked auth and resolved the scope.
  async function handle(req, res, scope) {
    if (req.method !== 'POST') {
      res.writeHead(405, { allow: 'POST', 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(failure(null, new RpcError(CODE.invalid, 'Only POST: this endpoint has no GET stream (use subscriptions/listen)'))));
    }
    if (!originOk(req)) return sendJson(res, 403, failure(null, new RpcError(CODE.invalid, 'Origin not allowed')));
    let body;
    try { body = await readJson(req, 1024 * 1024); } catch (err) {
      return sendJson(res, err.status === 413 ? 413 : 400, failure(null, new RpcError(CODE.parse, err.message)));
    }
    // 2025-03-26 allowed a batch: answer each request in it.
    if (Array.isArray(body)) {
      const out = [];
      for (const msg of body) { const r = await one(req, msg, scope); if (r.body) out.push(r.body); }
      return out.length ? sendJson(res, 200, out) : (res.writeHead(202), res.end());
    }
    if (body?.method === 'subscriptions/listen' && body.id != null && isModern(body)) {
      const err = check(req, body, 'modern');
      if (err) return sendJson(res, statusOf(err, 'modern'), failure(body.id, err));
      return listen(req, res, body, scope);
    }
    const r = await one(req, body, scope);
    if (!r.body) { res.writeHead(202); return res.end(); }
    sendJson(res, r.status, r.body);
  }

  // One message -> {status, body}; body null for a notification.
  async function one(req, msg, scope) {
    if (!msg || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return { status: 400, body: failure(msg?.id, new RpcError(CODE.invalid, 'Not a JSON-RPC 2.0 request')) };
    const era = isModern(msg) ? 'modern' : 'legacy';
    if (msg.id === undefined) return { status: 202, body: null }; // notifications/initialized, cancelled: nothing to do
    const bad = check(req, msg, era);
    if (bad) return { status: statusOf(bad, era), body: failure(msg.id, bad) };
    try {
      return { status: 200, body: reply(msg.id, await dispatch(msg, scope, era)) };
    } catch (err) {
      if (!(err instanceof RpcError)) err = new RpcError(CODE.internal, err.message);
      return { status: statusOf(err, era), body: failure(msg.id, err) };
    }
  }

  return {
    handle,
    dispatch,
    get streams() { return streams.size; },
    close() { for (const s of [...streams]) { s.end(); s.stop(); } },
  };
}
