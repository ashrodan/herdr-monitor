// Open PRs of many repos in one GitHub GraphQL call (one alias per repo),
// instead of one `gh pr list` per repo. Pure logic, no "herdr" import, so
// node:test can load it; data.js runs the query.

export const PR_LIMIT = 30;
const CHUNK = 20; // repos per query: well under GitHub's node limit (20 × 30 PRs × 50 checks)

// "owner/name" from a GitHub remote URL (https, ssh or scp form), else null.
export function ghRepo(url) {
  const m = String(url ?? "").trim().match(/github\.com[:/]([^/\s]+)\/([^/\s]+?)(?:\.git)?\/?$/);
  return m ? `${m[1]}/${m[2]}` : null;
}

const FIELDS = `number title url isDraft headRefName author { login } updatedAt reviewDecision
  commits(last: 1) { nodes { commit { statusCheckRollup { contexts(first: 50) { nodes {
    __typename ... on CheckRun { status conclusion } ... on StatusContext { state } } } } } } }`;

// One query per CHUNK repos. names: ["owner/name", ...]. JSON.stringify makes
// a valid GraphQL string literal, so names can't break out of the query.
export function prQueries(names) {
  const out = [];
  for (let i = 0; i < names.length; i += CHUNK) {
    const part = names.slice(i, i + CHUNK).map((n, j) => {
      const [owner, name] = n.split("/");
      return `r${i + j}: repository(owner: ${JSON.stringify(owner)}, name: ${JSON.stringify(name)}) {
  pullRequests(states: OPEN, first: ${PR_LIMIT}, orderBy: { field: UPDATED_AT, direction: DESC }) { nodes { ${FIELDS} } } }`;
    });
    out.push(`query {\n${part.join("\n")}\n}`);
  }
  return out;
}

// A query's data → { name: [raw PR, ...] } with the rollup flattened as
// `gh pr list --json statusCheckRollup` gives it (checksOf reads either).
export function parsePrQuery(data, names) {
  const out = {};
  for (const [alias, repo] of Object.entries(data ?? {})) {
    const name = names[Number(alias.slice(1))];
    if (!name || !repo) continue;
    out[name] = (repo.pullRequests?.nodes ?? []).map((p) => ({
      ...p,
      statusCheckRollup: p.commits?.nodes?.[0]?.commit?.statusCheckRollup?.contexts?.nodes ?? [],
    }));
  }
  return out;
}

// A PR's checks as counts (from the rollup above, or gh's statusCheckRollup).
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
