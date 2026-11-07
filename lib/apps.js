import net from 'node:net';

// Running apps: a terminal that serves HTTP (a dev server). The phone shows
// it as an app and opens it in the browser, on the machine's tailnet name.
// A port is used only when we can read it; it is never guessed.

const LOCAL = /\b(https?):\/\/(?:127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1\]):(\d{2,5})\b/i;

const okPort = (n) => Number.isInteger(n) && n > 0 && n < 65536;

// --port N or --port=N in argv -> {scheme: 'http', port}.
export function portFromArgv(argv) {
  if (!Array.isArray(argv)) return null;
  for (let i = 0; i < argv.length; i++) {
    const a = String(argv[i]);
    const m = a.match(/^--port=(\d+)$/);
    const n = m ? Number(m[1]) : a === '--port' ? Number(argv[i + 1]) : NaN;
    if (okPort(n)) return { scheme: 'http', port: n };
  }
  return null;
}

// An http(s)://127.0.0.1:N or localhost:N URL in some text.
export function portFromText(text) {
  const m = String(text ?? '').match(LOCAL);
  if (!m) return null;
  const port = Number(m[2]);
  return okPort(port) ? { scheme: m[1].toLowerCase(), port } : null;
}

// Where the terminal listens, in order: argv, then its title, then its
// first screen lines (when given). null when none says.
export function listenOf({ argv, title, screen } = {}) {
  return portFromArgv(argv) || portFromText(title) || portFromText(String(screen ?? '').split('\n').slice(0, 60).join('\n'));
}

// The tailnet name of an hc machine (address "dai/t1" -> machine "dai").
// machines: `hc machine ls --json`; peers: lib/herdr.js tailnetPeers.
export function machineHost(address, machines, peers) {
  const i = String(address).indexOf('/');
  if (i < 0) return null; // this machine: the phone uses the server's own host
  const name = address.slice(0, i);
  const m = (machines || []).find((x) => x.name === name || x.target === name);
  const host = m?.host;
  if (!host) return null;
  const full = (peers || []).find((p) => p.name === host || p.name.startsWith(host + '.'));
  return full ? full.name : null;
}

// A port found earlier carries over a terminal refresh only while the same
// program runs there (same foreground pid, still live). A new program, or
// none, must say its port again.
export function samePrograms(prev, next) {
  return !!prev && !!next && next.state === 'live' && prev.pid != null && prev.pid === next.pid;
}

// Does something accept TCP connections on host:port? (1 s at most.)
export function portAnswers(port, host = '127.0.0.1', timeoutMs = 1000) {
  return new Promise((resolve) => {
    const s = net.connect({ port, host });
    const done = (ok) => { s.destroy(); resolve(ok); };
    s.setTimeout(timeoutMs, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

// Checks of local app ports: a port is dropped after `misses` failed checks
// in a row, at least `gapMs` apart, so a server that is still starting (or
// restarting) keeps its row.
export class PortChecks {
  constructor({ misses = 2, gapMs = 60000, now = Date.now } = {}) {
    Object.assign(this, { misses, gapMs, now });
    this.state = new Map(); // address -> { at, missed }
  }

  // Is this terminal due for a check?
  due(address) {
    const st = this.state.get(address);
    return !st || this.now() - st.at >= this.gapMs;
  }

  // Record a result. True when the port should be dropped now.
  record(address, answered) {
    const missed = answered ? 0 : (this.state.get(address)?.missed || 0) + 1;
    this.state.set(address, { at: this.now(), missed });
    return missed >= this.misses;
  }

  forget(address) { this.state.delete(address); }
}
