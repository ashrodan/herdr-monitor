// The facts behind the summary: open PRs in the repos of the open spaces,
// each Claude agent's latest recap, and the web apps that run in each space. Everything slow goes through exec (its own
// thread); the JavaScript here only parses small outputs.
import { exec, hc, home, readTextFile, spaces, panes } from "herdr";
import { SCAN, PROBE, SHARE, parseScan, parseProbe, probeArgs, apps, spaceOf } from "./apps.js";

// git reads that never take the index lock while an agent commits.
const READ = ["--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "diff.autoRefreshIndex=false"];
const PR_FIELDS = "number,title,url,isDraft,headRefName,author,updatedAt,reviewDecision,statusCheckRollup";
const PR_LIMIT = 30;
const REPO_TTL = 5 * 60 * 1000;

// ---------------------------------------------------------------- repos

// Folder → its repo (the git common dir names the main checkout, so every
// worktree of one repo counts once). Cached for 5 minutes.
const repoOf = new Map(); // `${on}:${dir}` -> { at, repo: {key, root, on} | null }

async function repoFor(dir, on) {
  const k = `${on ?? ""}:${dir}`;
  const hit = repoOf.get(k);
  if (hit && Date.now() - hit.at < REPO_TTL) return hit.repo;
  let repo = null;
  try {
    const r = await exec("git", [...READ, "rev-parse", "--path-format=absolute", "--show-toplevel", "--git-common-dir"], { cwd: dir, on });
    const [root, common] = r.stdout.trim().split("\n");
    if (r.code === 0 && root && common) repo = { key: `${on ?? ""}:${common}`, root, on: on ?? null };
  } catch { /* the folder is gone or the machine is away */ }
  repoOf.set(k, { at: Date.now(), repo });
  return repo;
}

// Every folder the open spaces use: the space's own folder and the live
// folder of each of its panes.
function folders() {
  const out = new Map(); // `${on}:${dir}` -> { dir, on, space }
  const tabSpace = new Map();
  for (const s of spaces()) for (const t of s.tabs ?? []) tabSpace.set(t.id, s);
  const add = (dir, s) => {
    if (!dir || dir === "~" || dir === home()) return;
    const k = `${s.machine ?? ""}:${dir}`;
    if (!out.has(k)) out.set(k, { dir, on: s.machine ?? null, space: s });
  };
  for (const s of spaces()) add(s.folder, s);
  for (const p of panes()) { const s = tabSpace.get(p.tab); if (s) add(p.cwd, s); }
  return [...out.values()];
}

// The distinct repos of the open spaces, each with the first space that shows it.
export async function openRepos() {
  const repos = new Map();
  const list = folders();
  await pool(list, 6, async (f) => {
    const repo = await repoFor(f.dir, f.on);
    if (repo && !repos.has(repo.key)) repos.set(repo.key, { ...repo, space: f.space.name, spaceId: f.space.id });
  });
  return [...repos.values()];
}

// ---------------------------------------------------------------- PRs

const FAIL = new Set(["FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE"]);
export function checksOf(rollup) {
  let fail = 0, pending = 0, pass = 0;
  for (const c of rollup ?? []) {
    const state = String(c.conclusion || c.state || "").toUpperCase();
    const done = c.__typename === "StatusContext" ? state !== "PENDING" && state !== "EXPECTED" : String(c.status || "").toUpperCase() === "COMPLETED";
    if (!done) pending++;
    else if (FAIL.has(state)) fail++;
    else pass++;
  }
  return { fail, pending, pass };
}

// "owner/repo" from a PR URL.
const repoName = (url) => String(url).match(/github\.com\/([^/]+\/[^/]+)\/pull\//)?.[1] ?? "";

export function shortError(text) {
  const s = String(text || "");
  if (/no git remotes|no default remote|not a git repository|none of the git remotes/i.test(s)) return null; // not a GitHub repo: skip quietly
  if (/auth|gh auth login/i.test(s)) return "gh is not logged in";
  return s.trim().split("\n")[0].slice(0, 120) || "gh failed";
}

// Every open PR in every repo of the open spaces, newest first.
export async function loadPrs() {
  const repos = await openRepos();
  const prs = [], errors = [];
  await pool(repos, 4, async (repo) => {
    let r;
    try {
      r = await exec("gh", ["pr", "list", "--state", "open", "--limit", String(PR_LIMIT), "--json", PR_FIELDS], { cwd: repo.root, on: repo.on });
    } catch (e) { errors.push({ repo: repo.root, error: String(e).slice(0, 120) }); return; }
    if (r.code !== 0) {
      const msg = shortError(r.stderr || r.stdout);
      if (msg) errors.push({ repo: repo.root, error: msg });
      return;
    }
    let rows = [];
    try { rows = JSON.parse(r.stdout || "[]"); } catch { return; }
    for (const p of rows) {
      prs.push({
        number: p.number, title: p.title, url: p.url, draft: !!p.isDraft, branch: p.headRefName,
        author: p.author?.login ?? "", updatedAt: p.updatedAt, review: p.reviewDecision || "",
        checks: checksOf(p.statusCheckRollup), repo: repoName(p.url), space: repo.space, spaceId: repo.spaceId,
      });
    }
  });
  prs.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  return { at: Date.now(), repos: repos.length, prs, errors };
}

// ---------------------------------------------------------------- recaps

// Claude Code's folder name for a cwd: every non-alphanumeric becomes '-'.
export const projectSlug = (cwd) => String(cwd).replace(/[^a-zA-Z0-9]/g, "-");

// The newest recap in some transcript lines: Claude's own away summary, else
// its last reply with text. Lines are oldest first.
export function recapFrom(lines) {
  for (let i = lines.length - 1; i >= 0; i--) {
    let r;
    try { r = JSON.parse(lines[i]); } catch { continue; }
    if (r.isSidechain) continue;
    if (r.type === "system" && r.subtype === "away_summary" && r.content) return { kind: "recap", text: oneLine(r.content), at: r.timestamp ?? null };
    if (r.type === "assistant" && Array.isArray(r.message?.content)) {
      const text = r.message.content.filter((b) => b.type === "text" && b.text?.trim()).map((b) => b.text).join("\n");
      if (text.trim()) return { kind: "reply", text: oneLine(text), at: r.timestamp ?? null };
    }
  }
  return null;
}

// The first paragraph, Markdown marks removed, at most 320 characters.
export function oneLine(text) {
  const para = String(text).trim().split(/\n\s*\n/)[0];
  const s = para.replace(/```[\s\S]*?```/g, " ").replace(/[*_`#>]+/g, "").replace(/\s+/g, " ").trim();
  return s.length > 320 ? `${s.slice(0, 317)}…` : s;
}

// The last part of the transcript, only the lines a recap can come from.
const TAIL = 'f="$1"; [ -f "$f" ] || f=$(ls "$2"/*/"$3".jsonl 2>/dev/null | head -n 1); [ -n "$f" ] || exit 3; tail -c 600000 "$f" | grep -E \'"subtype":"away_summary"|"type":"assistant"\' | tail -n 30';

// An agent's recap: terminal → foreground pid → ~/.claude/sessions/<pid>.json
// → the session's transcript. Local agents only.
export async function loadRecap(address) {
  const g = await hc(["get", address, "--json"]);
  if (g.code !== 0) return null;
  let pid;
  try { pid = JSON.parse(g.stdout).foreground?.pid; } catch { return null; }
  if (!Number.isInteger(pid)) return null;
  const claude = `${home()}/.claude`;
  const raw = await readTextFile(`${claude}/sessions/${pid}.json`).catch(() => null);
  if (!raw) return null;
  let s;
  try { s = JSON.parse(raw); } catch { return null; }
  if (!/^[0-9a-f-]{36}$/i.test(s.sessionId || "")) return null;
  const file = `${claude}/projects/${projectSlug(s.cwd || "")}/${s.sessionId}.jsonl`;
  const r = await exec("sh", ["-c", TAIL, "sh", file, `${claude}/projects`, s.sessionId]);
  if (r.code !== 0 && !r.stdout) return null;
  return recapFrom(r.stdout.split("\n").filter(Boolean));
}

// ---------------------------------------------------------------- apps

// Terminal id → its space, from the panes on screen in any tab.
function terminalSpaces() {
  const tabSpace = new Map(), out = new Map();
  for (const s of spaces()) for (const t of s.tabs ?? []) tabSpace.set(t.id, s);
  for (const p of panes()) { const s = p.terminal && tabSpace.get(p.tab); if (s) out.set(p.terminal, s); }
  return out;
}

// The web apps of the open spaces, on every machine that has a space: one
// scan per machine (listeners, their terminal and folder), then one probe of
// only the listeners that belong to a space.
export async function loadApps() {
  const terms = terminalSpaces();
  const dirs = folders();
  const machines = [...new Set(spaces().map((s) => s.machine ?? null))];
  const list = [], errors = [];
  await pool(machines, 3, async (on) => {
    try {
      const scan = parseScan((await exec("sh", ["-c", SCAN], { on })).stdout);
      // A home folder would claim every process on the machine.
      const mine = dirs.filter((f) => (f.on ?? null) === on && !/^\/(home\/[^/]+|Users\/[^/]+|root)\/?$/.test(f.dir));
      const keep = scan.listeners.filter((l) => spaceOf({ ...l, machine: on }, terms, mine));
      if (!keep.length) return;
      const probes = parseProbe((await exec("sh", ["-c", PROBE, "sh", scan.dns, ...probeArgs(keep)], { on })).stdout);
      for (const a of apps({ ...scan, listeners: keep }, probes, on)) {
        const s = spaceOf(a, terms, mine);
        // The app's terminal, when a pane shows it (so a click can focus it).
        const shown = a.term && [a.term, `${on}/${a.term}`].find((id) => terms.get(id) === s);
        list.push({ ...a, space: s.name, spaceId: s.id, terminal: shown || null });
      }
    } catch (e) { errors.push({ machine: on ?? "this Mac", error: String(e).slice(0, 120) }); }
  });
  list.sort((a, b) => a.space.localeCompare(b.space) || a.port - b.port);
  return { at: Date.now(), apps: list, errors };
}

// Share one app on the tailnet with Tailscale Serve (a click does this, never
// a timer). Returns null, or the error text.
export async function shareApp(app) {
  const r = await exec("sh", ["-c", SHARE, "sh", String(app.port), app.target], { on: app.machine });
  return r.code === 0 ? null : (r.stderr || r.stdout || "tailscale serve failed").trim().split("\n").pop().slice(0, 160);
}

// ---------------------------------------------------------------- helpers

// Run fn over items, at most n at a time.
export async function pool(items, n, fn) {
  const queue = [...items];
  const worker = async () => { for (let it; (it = queue.shift()) !== undefined;) await fn(it); };
  await Promise.all(Array.from({ length: Math.min(n, queue.length) }, worker));
}

export function age(ms) {
  if (!ms) return "";
  const m = Math.floor((Date.now() - ms) / 60000);
  if (m < 1) return "now";
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  return h < 24 ? `${h}h` : `${Math.floor(h / 24)}d`;
}
