// Run herdr-monitor as a macOS launchd agent: it starts at login and
// launchd starts it again if it stops.
//
//   node scripts/service.js print    [--bun]   show the plist, change nothing
//   node scripts/service.js install  [--bun]   write it and start the agent
//   node scripts/service.js uninstall          stop the agent, remove it
//   node scripts/service.js status
//
// install takes PORT, HOST, REDIRECT_PORT, HTTPS_PORT, HC_SOCKET, HC_BIN and
// HERDR_MONITOR_DIR from the current environment, and PATH too: launchd's own
// PATH has no hc, gh or tailscale. It stops if something already listens on
// the port (a server you started by hand): stop that one first.
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const LABEL = 'io.github.ashrodan.herdr-monitor';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const KEEP_ENV = ['PORT', 'HOST', 'REDIRECT_PORT', 'HTTPS_PORT', 'HC_SOCKET', 'HC_BIN', 'HERDR_MONITOR_DIR', 'PATH'];

const xml = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

export function plist({ runtime, root = ROOT, env = {}, logDir }) {
  const envXml = Object.entries(env).filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `    <key>${xml(k)}</key><string>${xml(v)}</string>`).join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${xml(runtime)}</string>
    <string>${xml(path.join(root, 'server.js'))}</string>
  </array>
  <key>WorkingDirectory</key><string>${xml(root)}</string>
  <key>EnvironmentVariables</key>
  <dict>
${envXml}
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>ExitTimeOut</key><integer>5</integer>
  <key>StandardOutPath</key><string>${xml(path.join(logDir, 'server.log'))}</string>
  <key>StandardErrorPath</key><string>${xml(path.join(logDir, 'server.log'))}</string>
</dict>
</plist>
`;
}

export function pickEnv(env) {
  return Object.fromEntries(KEEP_ENV.filter((k) => env[k]).map((k) => [k, env[k]]));
}

function which(cmd) {
  try { return execFileSync('/usr/bin/which', [cmd], { encoding: 'utf8' }).trim(); } catch { return null; }
}

function portInUse(port, host) {
  return new Promise((resolve) => {
    const s = net.connect(port, host);
    s.once('connect', () => { s.destroy(); resolve(true); });
    s.once('error', () => resolve(false));
  });
}

async function main([cmd, ...flags]) {
  const plistFile = path.join(os.homedir(), 'Library/LaunchAgents', `${LABEL}.plist`);
  const logDir = path.join(os.homedir(), 'Library/Logs/herdr-monitor');
  const domain = `gui/${process.getuid()}`;
  const runtime = flags.includes('--bun') ? which('bun') : process.execPath;
  if (!runtime) throw new Error('bun is not on PATH');
  const text = plist({ runtime, env: pickEnv(process.env), logDir });

  if (cmd === 'print') { process.stdout.write(text); return; }
  if (cmd === 'status') {
    try { process.stdout.write(execFileSync('launchctl', ['print', `${domain}/${LABEL}`], { encoding: 'utf8' }).split('\n').slice(0, 12).join('\n') + '\n'); }
    catch { console.log('not installed'); }
    return;
  }
  if (cmd === 'install') {
    const port = Number(process.env.PORT || 7400);
    if (await portInUse(port, process.env.HOST || '127.0.0.1')) {
      throw new Error(`something already listens on port ${port}. Stop that server first, then run install again.`);
    }
    fs.mkdirSync(logDir, { recursive: true });
    fs.mkdirSync(path.dirname(plistFile), { recursive: true });
    fs.writeFileSync(plistFile, text);
    try { execFileSync('launchctl', ['bootout', `${domain}/${LABEL}`], { stdio: 'ignore' }); } catch { /* not loaded */ }
    execFileSync('launchctl', ['bootstrap', domain, plistFile]);
    console.log(`installed ${plistFile}\nlogs: ${path.join(logDir, 'server.log')}`);
    return;
  }
  if (cmd === 'uninstall') {
    try { execFileSync('launchctl', ['bootout', `${domain}/${LABEL}`], { stdio: 'ignore' }); } catch { /* not loaded */ }
    fs.rmSync(plistFile, { force: true });
    console.log('uninstalled');
    return;
  }
  console.log('usage: node scripts/service.js print|install|uninstall|status [--bun]');
  process.exitCode = 2;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main(process.argv.slice(2)).catch((err) => { console.error(err.message); process.exitCode = 1; });
}
