// Minimal JSON-RPC 2.0 client for herdr-core (hc) over its Unix socket.
// Plain mode: one JSON object per line in each direction.
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';

export function defaultSocket(env = process.env) {
  return env.HC_TERMINAL_SOCKET
    || path.join(os.homedir(), '.local/state/herdr-core/default/default.sock');
}

// Split a stream of bytes into complete lines. Returns the leftover partial line.
export function splitLines(buffer, chunk, onLine) {
  buffer += chunk;
  let i;
  while ((i = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, i).trim();
    buffer = buffer.slice(i + 1);
    if (line) onLine(line);
  }
  return buffer;
}

export class HcConnection extends EventEmitter {
  constructor(socketPath = defaultSocket()) {
    super();
    this.socketPath = socketPath;
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = '';
    this.socket = null;
  }

  connect() {
    return new Promise((resolve, reject) => {
      const s = net.connect(this.socketPath);
      s.setEncoding('utf8');
      s.once('connect', () => { this.socket = s; resolve(this); });
      s.once('error', reject);
      s.on('data', (chunk) => {
        this.buffer = splitLines(this.buffer, chunk, (line) => this.#onLine(line));
      });
      s.on('close', () => {
        for (const { reject: rej } of this.pending.values()) rej(new Error('hc connection closed'));
        this.pending.clear();
        this.socket = null;
        this.emit('close');
      });
      s.on('error', (err) => this.emit('error', err));
    });
  }

  #onLine(line) {
    let msg;
    try { msg = JSON.parse(line); } catch { return; }
    if (msg.id != null && this.pending.has(msg.id)) {
      const { resolve, reject } = this.pending.get(msg.id);
      this.pending.delete(msg.id);
      if (msg.error) {
        const err = new Error(msg.error.data?.kind || msg.error.message || 'hc error');
        err.rpc = msg.error;
        reject(err);
      } else resolve(msg.result);
    } else if (msg.method === 'event') {
      this.emit('event', msg.params);
    }
  }

  call(method, params = {}) {
    if (!this.socket) return Promise.reject(new Error('hc not connected'));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  close() { this.socket?.end(); }
}

// Reduce an hc Terminal record to what the UI needs.
export function summarize(t) {
  const status = typeof t.status === 'string' ? { state: t.status } : (t.status || null);
  // Prefer what the program reports (claude-code), then the command it ran:
  // a process name can be a version number (Claude Code shows as 2.1.314).
  const base = (p) => p?.split('/').pop();
  return {
    address: t.address,
    name: t.name ?? null,
    agent: status?.app || t.agent || base(t.foreground?.argv?.[0]) || t.foreground?.name || base(t.argv?.[0]) || '?',
    cwd: t.foreground?.cwd || t.cwd,
    state: t.state,
    title: t.title ?? null,
    where: t.labels?.where ?? null,
    reported: !!t.reported,
    status,
    exit_code: t.exit_code ?? null,
    created_ms: t.created_ms ?? null,
    parent: t.parent ?? null,
    space: t.labels?.['herdr.space'] ?? null,
    tab: t.labels?.['herdr.tab'] ?? null,
    changed_ms: t.status_ms ?? null,
    cols: t.cols ?? null,
    pid: t.foreground?.pid ?? null,
    rows: t.rows ?? null,
  };
}
