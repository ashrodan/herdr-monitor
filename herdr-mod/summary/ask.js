// Ask: the Summary panel's chat. One turn = one `herdr-ask` run (bin/herdr-ask.js,
// linked into ~/.local/bin by scripts/install-mod.sh): the mod writes the message,
// the recent turns and a small snapshot of Herdr to a file, and reads back
// {reply, actions}. The model only proposes; each action runs here on a click.
import { exec, hc, home, writeTextFile, spaces, agents, shared, run, openOverlay } from "herdr";
import { APPROVE_TEXT, fixText, MERGE_QUERY, mergePlan } from "./decide.js";

const READ = ["--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "diff.autoRefreshIndex=false"];
const DIR = () => `${home()}/.cache/herdr-ask`;
const ARGV = { claude: ["claude"], codex: ["codex"] };

// Machines change rarely: asked once a minute at most.
let machines = { at: 0, list: [] };
async function machineList() {
  if (Date.now() - machines.at < 60_000) return machines.list;
  try {
    const r = await hc(["machine", "ls", "--json"]);
    const list = JSON.parse(r.stdout).machines ?? [];
    machines = { at: Date.now(), list: list.filter((m) => m.state === "connected").map((m) => ({ name: m.name })) };
  } catch { machines = { at: Date.now(), list: [] }; }
  return machines.list;
}

// What the model may refer to: ids, names and states only, no transcripts.
async function context(prsKey) {
  const sp = spaces().map((s) => ({ id: s.id, name: s.name, folder: s.folder ?? null, machine: s.machine ?? null, branch: s.branch ?? null }));
  const ag = agents().map((a) => ({ address: a.address, space: a.space_name, tab: a.tab_name, status: a.status, title: String(a.title ?? "").slice(0, 80) }));
  const all = shared(prsKey)?.prs ?? [];
  const prs = all.slice(0, 20).map((p) => ({ repo: p.repo, number: p.number, title: p.title, branch: p.branch, draft: p.draft }));
  return { spaces: sp, agents: ag, machines: await machineList(), prs_open_total: all.length, prs_latest: prs };
}

let made = false;
// One chat turn. history: [{role, text}] before this message.
export async function ask(message, history, prsKey, decide = []) {
  if (!made) { await exec("mkdir", ["-p", DIR()]); made = true; }
  const file = `${DIR()}/turn-${Date.now()}-${Math.floor(Math.random() * 1e6)}.json`;
  await writeTextFile(file, JSON.stringify({ message, history, context: { ...(await context(prsKey)), decisions: decide } }), null);
  const r = await exec("herdr-ask", ["--consume", file], { timeout: 60_000 });
  let out = null;
  try { out = JSON.parse(r.stdout); } catch { /* reported below */ }
  if (!out) throw new Error((r.stderr || r.stdout || `herdr-ask exited ${r.code}`).trim().slice(0, 200));
  if (out.error) throw new Error(out.error);
  return out;
}

// ------------------------------------------------------------ actions

async function git(args, cwd, on) {
  const r = await exec("git", args, { cwd, on, timeout: 60_000 });
  if (r.code !== 0) throw new Error(`git ${args.find((a) => !a.startsWith("-") && !a.includes("=")) ?? ""}: ${(r.stderr || r.stdout).trim().slice(0, 160)}`);
  return r.stdout.trim();
}

// A new worktree from the remote's default branch, in <repo>/.worktrees/<slug>
// (only when the repo ignores .worktrees/), then a space on it with the agent.
async function spawnWorktree(a) {
  const on = a.machine;
  const root = await git([...READ, "rev-parse", "--show-toplevel"], a.folder, on);
  const ignored = await exec("git", ["check-ignore", "-q", ".worktrees/x"], { cwd: root, on });
  if (ignored.code !== 0) throw new Error(`${root} doesn't ignore .worktrees/: add it to .gitignore first`);
  await git(["fetch", "-q", "origin"], root, on);
  const dir = `${root}/.worktrees/${a.branch.split("/").pop()}`;
  if (a.existing) {
    // A PR's branch: check it out as it is on origin.
    await git(["worktree", "add", "--track", "-B", a.branch, dir, `origin/${a.branch}`], root, on);
  } else {
    let base = "origin/main";
    try { base = await git([...READ, "symbolic-ref", "--short", "refs/remotes/origin/HEAD"], root, on); } catch { /* origin/main */ }
    await git(["worktree", "add", dir, "-b", a.branch, base], root, on);
  }
  run("space.new", { name: a.title, folder: dir, argv: ARGV[a.agent], task: a.brief, machine: on ?? "" });
  return `Started ${a.agent} on ${a.branch} in ${dir}`;
}

export async function runAction(a) {
  if (a.type === "spawn") {
    if (a.worktree) return spawnWorktree(a);
    run("tab.new", { name: a.title, space: a.space_id, folder: a.folder, argv: ARGV[a.agent], task: a.brief });
    return `Started ${a.agent} in ${a.space_name}`;
  }
  if (a.type === "tell") {
    const r = await hc(["send", a.terminal, a.text]);
    if (r.code !== 0) throw new Error((r.stderr || r.stdout).trim().slice(0, 160) || `hc send exited ${r.code}`);
    return `Sent to ${a.label}`;
  }
  if (a.type === "handoff") {
    openOverlay({ mod: "handoff", kind: "dialog", width: 600, props: { space: a.space_id, to: a.machine } });
    return "Opened the hand-off dialog";
  }
  throw new Error(`unknown action ${a.type}`);
}

// ------------------------------------------------------------ decide

async function gh(args) {
  const r = await exec("gh", args, { timeout: 60_000 });
  if (r.code !== 0) throw new Error((r.stderr || r.stdout).trim().split("\n")[0].slice(0, 200) || `gh exited ${r.code}`);
  return r.stdout;
}

let login = null;
export async function me() {
  if (!login) { try { login = (await gh(["api", "user", "-q", ".login"])).trim() || null; } catch { /* unknown: no PR cards */ } }
  return login;
}

// A blocked agent's question or permission prompt: {kind, msg}.
export async function statusOf(terminal) {
  const r = await hc(["get", terminal, "--json"]);
  const s = JSON.parse(r.stdout || "{}").status ?? {};
  return { state: s.state ?? null, kind: s.kind ?? null, msg: s.msg ? String(s.msg).slice(0, 300) : null };
}

// The merge a Merge click would do, for its confirm: method, head, base, blocked.
export async function mergePreview(p) {
  const [owner, name] = p.repo.split("/");
  const out = await gh(["api", "graphql", "-f", `query=${MERGE_QUERY}`, "-F", `owner=${owner}`, "-F", `name=${name}`, "-F", `number=${p.number}`]);
  return mergePlan(JSON.parse(out).data?.repository);
}

// The space that has a PR's branch checked out, if any.
const spaceOfBranch = (branch) => spaces().find((s) => s.branch === branch && s.folder);

// Review: the agent itself; a PR in ship-it's review pane when a space has its
// branch, else null (main.js opens it on GitHub).
export function review(d) {
  if (d.agent) { run("agent.focus", { terminal: d.agent }); return true; }
  const s = spaceOfBranch(d.pr.branch);
  if (!s) return false;
  run("space.select", { id: s.id });
  run("pane.split", { direction: "right", mod: "ship-it-review", props: { root: s.folder, on: s.machine ?? null } });
  return true;
}

// One option of a decision card. plan: the confirmed merge plan.
export async function decide(d, option, plan) {
  if (option === "yes" || option === "no") {
    const now = await statusOf(d.agent);
    if (now.state !== "blocked") throw new Error("It isn't waiting any more");
    const r = await hc(["keys", d.agent, option === "yes" ? "enter" : "esc"]);
    if (r.code !== 0) throw new Error((r.stderr || r.stdout).trim().slice(0, 160));
    return option === "yes" ? "Answered Yes" : "Answered No";
  }
  if (option === "approve") return runAction({ type: "tell", terminal: d.agent, text: APPROVE_TEXT, label: d.where });
  if (option === "merge") {
    const p = d.pr;
    await gh(["pr", "merge", String(p.number), "--repo", p.repo, `--${plan.method}`, ...(plan.head ? ["--match-head-commit", plan.head] : [])]);
    return `Merged #${p.number} into ${plan.base} (${plan.method})`;
  }
  if (option === "approve-pr") {
    await gh(["pr", "review", String(d.pr.number), "--repo", d.pr.repo, "--approve"]);
    return `Approved #${d.pr.number}`;
  }
  if (option === "fix") {
    const p = d.pr, s = spaceOfBranch(p.branch);
    const ag = s && agents().find((a) => a.space === s.id && a.status !== "working");
    if (ag) return runAction({ type: "tell", terminal: ag.address, text: fixText(p), label: [ag.space_name, ag.tab_name].filter(Boolean).join(" · ") });
    const host = s ?? spaces().find((x) => x.id === p.spaceId);
    if (!host?.folder) throw new Error(`No space has ${p.repo}`);
    return runAction({ type: "spawn", worktree: true, existing: true, branch: p.branch, agent: "claude", title: `Fix #${p.number}`, brief: fixText(p), folder: host.folder, machine: host.machine ?? null, space_id: host.id, space_name: host.name });
  }
  throw new Error(`unknown option ${option}`);
}
