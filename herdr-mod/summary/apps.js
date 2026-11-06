// Running web apps per space: pure logic, no "herdr" import, so node:test can
// load it. data.js runs SCAN on each machine, keeps the listeners of the
// spaces (spaceOf), runs PROBE on those, and gives both to apps().
//
// A web app is a TCP port that a process listens on, where that process runs
// in a Herdr terminal (HC_TERMINAL_ID in its environment) or in a folder under
// the home folder, and that answers GET / with text/html. Postgres, debuggers
// and JSON APIs are left out.

// The scan: one shell script for macOS (lsof, ps -E) and Linux (ss, /proc),
// a few processes in all, whatever the number of listeners. Tab-separated:
//   D <tailnet DNS name>            S <tailscale serve status, one line of JSON>
//   L <pid> <host> <port>           a TCP listener
//   E <pid> <terminal>              HC_TERMINAL_ID in the process environment
//   C <pid> <cwd>                   A <pid> <command line>
export const SCAN = String.raw`
T=$(printf '\t')
ts=$(command -v tailscale 2>/dev/null)
[ -z "$ts" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && ts=/Applications/Tailscale.app/Contents/MacOS/Tailscale
if [ -n "$ts" ]; then
  echo "D $("$ts" status --json 2>/dev/null | sed -n 's/.*"DNSName": *"\([^"]*\)".*/\1/p' | head -n 1 | sed 's/[.]$//')"
  echo "S $("$ts" serve status --json 2>/dev/null | tr -d '\n')"
fi
L=$(if [ "$(uname)" = Darwin ]; then
  lsof -nP -iTCP -sTCP:LISTEN -Fpn 2>/dev/null | awk '/^p/{p=substr($0,2)} /^n/{print p" "substr($0,2)}'
else
  ss -ltnpH 2>/dev/null | awk '{ if (match($0, /pid=[0-9]+/)) print substr($0, RSTART+4, RLENGTH-4)" "$4 }'
fi | awk -v T="$T" '{ if (match($2, /:[0-9]+$/)) print "L"T$1T substr($2, 1, RSTART-1) T substr($2, RSTART+1) }' | sort -u)
[ -n "$L" ] && printf '%s\n' "$L"
pids=$(printf '%s\n' "$L" | cut -f2 | sort -u | paste -sd, -)
if [ -n "$pids" ]; then
  if [ "$(uname)" = Darwin ]; then
    # ps -E prints the command line, then the environment (NAME=value words).
    ps -E -ww -o pid=,command= -p "$pids" 2>/dev/null | awk -v T="$T" '{
      a = ""; e = 0
      for (i = 2; i <= NF; i++) {
        if ($i ~ /^HC_TERMINAL_ID=/) print "E"T$1T substr($i, 16)
        if (i > 2 && $i ~ /^[A-Z_][A-Z0-9_]*=/) e = 1
        if (!e && length(a) < 200) a = a (a == "" ? "" : " ") $i
      }
      print "A"T$1T a
    }'
    lsof -a -d cwd -p "$pids" -Fpn 2>/dev/null | awk -v T="$T" '/^p/{p=substr($0,2)} /^n/{print "C"T p T substr($0,2)}'
  else
    for pid in $(echo "$pids" | tr , ' '); do
      t=$(tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null | sed -n 's/^HC_TERMINAL_ID=//p' | head -n 1)
      [ -n "$t" ] && echo "E$T$pid$T$t"
      echo "C$T$pid$T$(readlink "/proc/$pid/cwd" 2>/dev/null)"
      echo "A$T$pid$T$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | cut -c1-200)"
    done
  fi
fi
`;

// The probe: GET / on each "pid,host,port" given (all at once), and again with
// the tailnet name as Host, to find dev servers that refuse other host names.
//   P <pid> <host> <port> <code> <content-type> <code with the tailnet Host>
export const PROBE = String.raw`
T=$(printf '\t'); dns=$1; shift
for x in "$@"; do
  pid=$(echo "$x" | cut -d, -f1); host=$(echo "$x" | cut -d, -f2); port=$(echo "$x" | cut -d, -f3)
  case "$host" in "*"|0.0.0.0|127.0.0.1) ph=127.0.0.1 ;; "[::]"|"[::1]"|::|::1) ph="[::1]" ;; *) ph=$host ;; esac
  (
    r=$(curl -s -m 2 -o /dev/null -w "%{http_code}$T%{content_type}" "http://$ph:$port/" 2>/dev/null)
    h=""
    [ -n "$dns" ] && h=$(curl -s -m 2 -o /dev/null -w "%{http_code}" -H "Host: $dns:$port" "http://$ph:$port/" 2>/dev/null)
    echo "P$T$pid$T$host$T$port$T$r$T$h"
  ) &
done
wait
`;

// Turn on Tailscale Serve for one port: https://<machine>.<tailnet>.ts.net:<port>
// → the app ($2). Linux needs root unless the user is the tailscale operator.
export const SHARE = String.raw`
ts=$(command -v tailscale 2>/dev/null)
[ -z "$ts" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && ts=/Applications/Tailscale.app/Contents/MacOS/Tailscale
[ -n "$ts" ] || { echo "tailscale is not installed" >&2; exit 2; }
"$ts" serve --bg --https="$1" "$2" 2>/dev/null || sudo -n "$ts" serve --bg --https="$1" "$2"
`;

// Scan output → { dns, serve, listeners: [{ pid, host, port, term, cwd, args }] }.
export function parseScan(text) {
  const out = { dns: "", serve: {}, listeners: [] };
  const env = new Map(), cwd = new Map(), args = new Map();
  for (const line of String(text ?? "").split("\n")) {
    if (line.startsWith("D ")) out.dns = line.slice(2).trim();
    else if (line.startsWith("S ")) { try { out.serve = JSON.parse(line.slice(2)) ?? {}; } catch { /* no serve config */ } }
    else {
      const [kind, pid, ...rest] = line.split("\t");
      if (kind === "L") out.listeners.push({ pid: Number(pid), host: rest[0], port: Number(rest[1]) });
      else if (kind === "E") env.set(Number(pid), rest[0]);
      else if (kind === "C") cwd.set(Number(pid), rest.join("\t"));
      else if (kind === "A") args.set(Number(pid), rest.join("\t").trim());
    }
  }
  for (const l of out.listeners) {
    l.term = env.get(l.pid) || null;
    l.cwd = cwd.get(l.pid) || null;
    l.args = args.get(l.pid) || "";
  }
  return out;
}

// Probe output → [{ pid, host, port, code, type, hostCode }].
export function parseProbe(text) {
  const out = [];
  for (const line of String(text ?? "").split("\n")) {
    const [kind, pid, host, port, code, type, hostCode] = line.split("\t");
    if (kind === "P") out.push({ pid: Number(pid), host, port: Number(port), code: Number(code) || 0, type: type || "", hostCode: Number(hostCode) || 0 });
  }
  return out;
}

// The probe arguments for some listeners: "pid,host,port", one per address.
export const probeArgs = (listeners) => [...new Set(listeners.map((l) => `${l.pid},${l.host},${l.port}`))];

// Tailscale Serve: app port → the https URL that proxies to it.
export function servedPorts(serve, dns) {
  const out = new Map();
  for (const [hostPort, cfg] of Object.entries(serve?.Web ?? {})) {
    const proxy = cfg?.Handlers?.["/"]?.Proxy;
    const m = String(proxy ?? "").match(/^https?:\/\/(?:127\.0\.0\.1|localhost|\[::1\]):(\d+)\/?$/);
    if (!m) continue;
    const [host, port] = [hostPort.replace(/:\d+$/, ""), hostPort.match(/:(\d+)$/)?.[1] ?? "443"];
    if (dns && host !== dns) continue;
    const url = port === "443" ? `https://${host}` : `https://${host}:${port}`;
    if (!out.has(Number(m[1])) || port !== "443") out.set(Number(m[1]), url);
  }
  return out;
}

// Container port forwarders: their folder says nothing about the space.
const FORWARDER = /OrbStack|com\.docker|Docker\.app|docker-proxy|vpnkit|gvproxy|limactl/;
const WILD = new Set(["*", "0.0.0.0", "[::]", "::"]);
const isTailnetIp = (h) => /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(h) || /^\[?fd7a:115c:a1e0:/i.test(h);

// The program behind a listener, from its command line: "vite", "next", …
const KNOWN = ["vite", "next", "astro", "nuxt", "remix", "react-scripts", "webpack", "storybook", "wrangler",
  "http-server", "serve", "parcel", "expo", "svelte-kit", "ng", "rails", "flask", "uvicorn", "gunicorn", "hugo", "jekyll"];
export function programOf(args) {
  const text = String(args ?? "");
  const words = text.split(/\s+/).filter(Boolean);
  const base = (w) => String(w ?? "").split("/").pop();
  if (/-m\s+http\.server/.test(text)) return "http.server";
  if (/manage\.py\s+runserver/.test(text)) return "django";
  if (/^next-server/.test(text) || /\/next\/dist\/bin\/next\b/.test(text)) return "next";
  // The program, or the script an interpreter runs (node …/.bin/vite → vite).
  let i = 0;
  const first = base(words[0]);
  if (/^(node|bun|deno|python3?|ruby|php|npx|bunx|pnpm|yarn)$/.test(first)) {
    i = words.findIndex((w, j) => j > 0 && !w.startsWith("-"));
    if (i < 0) return first;
  }
  const prog = base(words[i]).replace(/\.(m?js|cjs|py)$/, "");
  if (KNOWN.includes(prog)) return prog;
  return i ? `${first} ${base(words[i])}` : first || "app";
}

// Listeners + probes → apps, one per process and port. Keeps only ports
// that answered with HTML.
export function apps(scan, probes, machine = null) {
  const served = servedPorts(scan.serve, scan.dns);
  const by = new Map();
  for (const l of scan.listeners) {
    const k = `${l.pid}:${l.port}`;
    if (!by.has(k)) by.set(k, { ...l, hosts: [], html: false, code: 0, hostBlocked: false });
    by.get(k).hosts.push(l.host);
  }
  for (const p of probes) {
    const a = by.get(`${p.pid}:${p.port}`);
    if (!a || !p.code) continue;
    const html = /text\/html/i.test(p.type);
    if (html) a.html = true;
    if (!a.code || html) { a.code = p.code; a.hostBlocked = Boolean(scan.dns) && p.code < 400 && p.hostCode === 403; }
  }
  const out = [];
  for (const a of by.values()) {
    if (!a.html || FORWARDER.test(a.args ?? "")) continue;
    let url = null, how = "local";
    if (served.has(a.port)) { url = served.get(a.port); how = "served"; }
    else if (scan.dns && a.hosts.some((h) => WILD.has(h) || isTailnetIp(h.replace(/^\[|\]$/g, "")))) { url = `http://${scan.dns}:${a.port}`; how = "open"; }
    const target = a.hosts.includes("[::1]") && !a.hosts.some((h) => h === "127.0.0.1" || WILD.has(h)) ? `http://[::1]:${a.port}` : `http://127.0.0.1:${a.port}`;
    out.push({
      key: `${machine ?? ""}:${a.pid}:${a.port}`, machine, pid: a.pid, port: a.port, term: a.term, cwd: a.cwd,
      program: programOf(a.args), url, how, local: `http://localhost:${a.port}`, target,
      hostBlocked: a.hostBlocked && how !== "local",
    });
  }
  out.sort((x, y) => x.port - y.port);
  return out;
}

// The space of an app: the space whose pane shows its terminal, else the
// space with the longest folder that holds the app's folder.
//   terms: Map terminal id → space; folders: [{ dir, space }]
export function spaceOf(app, terms, folders) {
  if (app.term) {
    const hit = terms.get(app.term) ?? terms.get(`${app.machine}/${app.term}`);
    if (hit) return hit;
  }
  if (!app.cwd) return null;
  let best = null, len = 0;
  for (const f of folders) {
    const d = String(f.dir).replace(/\/+$/, "");
    if ((app.cwd === d || app.cwd.startsWith(`${d}/`)) && d.length > len) { best = f.space; len = d.length; }
  }
  return best;
}
