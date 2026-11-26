// Decide: what waits on a call from you, as cards with one-click options.
// Pure logic, no "herdr" import, so node:test can load it; main.js gathers
// the facts and ask.js runs the options.
//
// Sources, in this order:
//   ask     an agent blocked on a question or a permission prompt
//   merge   your PR, not a draft, checks green, not waiting on a review
//   fix     your PR with failing checks or changes requested
//   review  someone else's PR that waits on a review
//   done    an agent that finished while you weren't looking
// Ids carry the agent's seq or the PR's updatedAt, so a card hidden with Later
// comes back when its subject changes.

export const APPROVE_TEXT = "Looks good. Commit anything left, push the branch and open a pull request (or update the open one).";
export const fixText = (p) => `Checks fail or changes were requested on PR #${p.number} (${p.url}). Read them with gh pr checks ${p.number} and gh pr view ${p.number} --comments, fix the cause, and push.`;

const KEYS_APPS = new Set(["claude-code", "claude", "codex"]);
const opt = (id, label, tone = "plain") => ({ id, label, tone });
const ORDER = ["ask", "merge", "fix", "review", "done", "bots"];
// Dependabot, Renovate and other apps: one card for all of them, not one each.
export const isBot = (author) => /\[bot\]$|^app\//.test(String(author));

// agents: agents(); prs: the shared PR list; me: your GitHub login (null: unknown);
// later: {id: true}; status: Map address -> {kind, msg} (hc get, blocked agents only).
export function decisions({ agents = [], prs = [], me = null, later = {}, status = new Map() }) {
  const out = [];
  for (const a of agents) {
    const where = [a.space_name, a.tab_name].filter(Boolean).join(" · ") || a.address;
    const title = String(a.title ?? "").replace(/^[^\p{L}\p{N}]+/u, "").trim();
    if (a.status === "blocked") {
      const s = status.get(a.address);
      const keys = s?.kind === "permission" && KEYS_APPS.has(a.app ?? a.program?.key);
      out.push({
        id: `ask:${a.address}:${a.seq}`, kind: "ask", where, detail: s?.msg || title || "Waiting for you", agent: a.address,
        options: [...(keys ? [opt("yes", "Yes", "go"), opt("no", "No", "stop")] : []), opt("review", "Review"), opt("later", "Later")],
      });
    } else if (a.status === "done") {
      out.push({
        id: `done:${a.address}:${a.seq}`, kind: "done", where, detail: title, agent: a.address,
        options: [opt("approve", "Approve: open PR", "go"), opt("review", "Review"), opt("later", "Later")],
      });
    }
  }
  const bots = [];
  for (const p of prs) {
    if (p.draft) continue;
    if (isBot(p.author)) { bots.push(p); continue; }
    const mine = me && p.author === me;
    const c = p.checks ?? {};
    let kind = null;
    if (mine && (c.fail > 0 || p.review === "CHANGES_REQUESTED")) kind = "fix";
    else if (mine && !c.pending && p.review !== "REVIEW_REQUIRED") kind = "merge";
    else if (!mine && me && p.review === "REVIEW_REQUIRED") kind = "review";
    if (!kind) continue;
    const why = kind === "fix" ? (c.fail ? `${c.fail} check${c.fail === 1 ? "" : "s"} failing` : "changes requested")
      : kind === "merge" ? `${c.pass ? "checks green" : "no checks"}${p.review === "APPROVED" ? " · approved" : ""}` : `@${p.author} asks for a review`;
    const first = kind === "merge" ? opt("merge", "Merge", "go") : kind === "fix" ? opt("fix", "Fix with agent", "go") : opt("approve-pr", "Approve", "go");
    out.push({
      id: `${kind}:${p.url}:${p.updatedAt}`, kind, where: `${p.repo.split("/").pop()} #${p.number}`, detail: p.title, why, pr: p,
      options: [first, opt("review", "Review"), opt("later", "Later")],
    });
  }
  if (bots.length) {
    const repos = new Set(bots.map((p) => p.repo)).size;
    const newest = bots.map((p) => p.updatedAt).sort().at(-1);
    out.push({
      id: `bots:${bots.length}:${newest}`, kind: "bots", where: `${bots.length} dependency PR${bots.length === 1 ? "" : "s"}`,
      why: `${repos} repo${repos === 1 ? "" : "s"}`, detail: bots.slice(0, 3).map((p) => p.title).join(" · "),
      options: [opt("show-prs", "Show"), opt("later", "Later")],
    });
  }
  return out.filter((d) => !later[d.id]).sort((a, b) => ORDER.indexOf(a.kind) - ORDER.indexOf(b.kind));
}

// A copy of ship-it's mergePlan (mods/ship-it/merge.js): squash, else merge,
// else rebase, the first the repo allows; blocked by the base branch's rules
// means no merge from here (ship-it's Override stays in ship-it).
export const MERGE_QUERY = `query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    squashMergeAllowed mergeCommitAllowed rebaseMergeAllowed
    pullRequest(number: $number) { mergeStateStatus headRefOid baseRefName }
  }
}`;
export function mergePlan(repo) {
  const pr = repo?.pullRequest ?? {};
  const method = repo?.squashMergeAllowed !== false ? "squash" : repo?.mergeCommitAllowed ? "merge" : "rebase";
  return { method, head: pr.headRefOid ?? null, base: pr.baseRefName ?? "main", blocked: pr.mergeStateStatus === "BLOCKED" || pr.mergeStateStatus === "DIRTY" };
}

// Later: hide one more id, keeping the newest 200 (old ids name a seq or
// updatedAt that no card has any more).
export const withLater = (later, id) => Object.fromEntries([...Object.entries(later ?? {}), [id, true]].slice(-200));
