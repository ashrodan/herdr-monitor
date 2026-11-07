// What runs on this machine (Herdr desktop app, Herdr TUI, hc), and the
// other herdr-monitor servers on the tailnet. For the Android app: an app on
// the phone cannot read Tailscale's peer list, so it asks a server it reaches.
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';

// "hc 0.1.18" or "herdr 0.9.5\n" -> "0.1.18". null when there is no version.
export function parseVersion(out) {
  const m = String(out ?? '').match(/(?<![\d.])(\d+\.\d+(?:\.\d+)?(?:[-+][\w.]+)?)/);
  return m ? m[1] : null;
}

// CFBundleShortVersionString from an Info.plist (XML form).
export function plistVersion(xml) {
  const m = String(xml ?? '').match(/<key>CFBundleShortVersionString<\/key>\s*<string>([^<]+)<\/string>/);
  return m ? m[1].trim() : null;
}

// The peers worth probing from `tailscale status --json`: online, with a
// name. Self first. Each is {name, os, self}.
export function tailnetPeers(status) {
  const out = [];
  const add = (p, self) => {
    const name = p?.DNSName?.replace(/\.$/, '').toLowerCase();
    if (name && (self || p.Online)) out.push({ name, os: p.OS || null, self });
  };
  add(status?.Self, true);
  for (const p of Object.values(status?.Peer || {})) add(p, false);
  return out;
}

const run = (cmd, args, timeout = 3000) => new Promise((resolve) => {
  execFile(cmd, args, { timeout, maxBuffer: 4 * 1024 * 1024 }, (err, out) => resolve(err ? null : String(out)));
});

const APP_PATHS = ['/Applications/Herdr.app', path.join(os.homedir(), 'Applications/Herdr.app')];

// {ui: {version, running} | null, tui: {...} | null, hc: version | null}
export async function herdrInfo({ hcBin = 'hc' } = {}) {
  let ui = null;
  for (const app of APP_PATHS) {
    const v = plistVersion(await fs.readFile(path.join(app, 'Contents/Info.plist'), 'utf8').catch(() => null));
    if (v) { ui = { version: v, running: !!(await run('pgrep', ['-f', `${app}/Contents/MacOS/`])) }; break; }
  }
  const tuiVersion = parseVersion(await run('herdr', ['--version']));
  const tui = tuiVersion ? { version: tuiVersion, running: !!(await run('pgrep', ['-x', 'herdr'])) } : null;
  return { ui, tui, hc: parseVersion(await run(hcBin, ['--version'])) };
}

// GET a JSON URL, at most `timeout` ms. null on any failure.
function getJson(url, timeout) {
  return new Promise((resolve) => {
    const req = https.get(url, { timeout }, (res) => {
      if (res.statusCode !== 200) { res.resume(); return resolve(null); }
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { data += c; if (data.length > 64 * 1024) req.destroy(); });
      res.on('end', () => { try { resolve(JSON.parse(data)); } catch { resolve(null); } });
    });
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolve(null));
  });
}

// The herdr-monitor servers on the tailnet: each online peer is asked for
// /api/info on `port`. {servers: [{url, name, os, self, herdr}], at}.
// `port` is this server's own; `ports` adds a few more to try on each peer
// (the phone sends the ports of the URLs it knows). No port scan.
export async function discover({ status, port, ports = [], self, timeout = 2500, fetchJson = getJson }) {
  const peers = tailnetPeers(status);
  const all = [...new Set([port, ...ports])].filter((n) => Number.isInteger(n) && n > 0 && n < 65536).slice(0, 4);
  const servers = [];
  await Promise.all(peers.flatMap((p) => all.map(async (n) => {
    const url = `https://${p.name}${n === 443 ? '' : `:${n}`}`;
    let info = p.self && n === port && self ? self : await fetchJson(`${url}/api/info`, timeout);
    // An older herdr-monitor has no /api/info: its /api/health still says
    // what it is (an hc socket and a terminal count). No versions then.
    if (!info) {
      const h = await fetchJson(`${url}/api/health`, timeout);
      if (h?.ok === true && typeof h.socket === 'string' && 'terminals' in h) info = { app: 'herdr-monitor', herdr: null, older: true };
    }
    if (info?.app === 'herdr-monitor') servers.push({ url, name: p.name, port: n, os: p.os, self: p.self, herdr: info.herdr ?? null, older: !!info.older });
  })));
  servers.sort((a, b) => (b.self - a.self) || a.name.localeCompare(b.name) || a.port - b.port);
  return { servers, at: Date.now() };
}
