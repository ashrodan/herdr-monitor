// POST /api/sessions: start one coding agent in an existing space.
// Pure: it checks the request against the layout and returns the
// terminal.run params. The client never sends a path or an argv.

export const HARNESSES = ['claude', 'omp'];
export const PROMPT_MAX = 4000;

// A name hc accepts (1-32 of a-z 0-9 - _): hm- (herdr-monitor), the
// harness, and a short time stamp.
export function sessionName(harness, now = Date.now()) {
  return `hm-${harness}-${now.toString(36).slice(-6)}`;
}

// The harness reads a leading '-' as an option (claude
// --dangerously-skip-permissions, omp --system-prompt=...). claude stops
// reading options at '--'; omp does not (checked: `omp -p -- --help` prints
// its help). So a prompt that starts with '-' is refused for both, and
// claude also gets '--' before the prompt.
export const startsLikeAFlag = (prompt) => /^-/.test(prompt);
const ARGV = {
  claude: (prompt) => ['claude', '--', prompt],
  omp: (prompt) => ['omp', prompt],
};

export function sessionRequest(body, layout, now = Date.now()) {
  const { space, harness, prompt } = body || {};
  if (typeof space !== 'string' || !space) return { ok: false, status: 400, error: 'space required' };
  if (!HARNESSES.includes(harness)) return { ok: false, status: 400, error: `harness must be one of: ${HARNESSES.join(', ')}` };
  if (typeof prompt !== 'string' || !prompt.trim()) return { ok: false, status: 400, error: 'prompt required' };
  if (prompt.length > PROMPT_MAX) return { ok: false, status: 400, error: `prompt is longer than ${PROMPT_MAX} characters` };
  if (startsLikeAFlag(prompt)) return { ok: false, status: 400, error: "a prompt cannot start with '-'" };
  const s = (layout || []).find((x) => x.id === space);
  if (!s || s.virtual) return { ok: false, status: 404, error: 'no such space' };
  if (!s.folder) return { ok: false, status: 409, error: 'that space has no folder' };
  return {
    ok: true,
    params: {
      // The prompt is one argument: no shell, nothing to quote.
      argv: ARGV[harness](prompt),
      cwd: s.folder,
      name: sessionName(harness, now),
      no_parent: true,
      no_notify: true,
      wait: false,
    },
  };
}

// The answer to POST /api/sessions from hc's terminal.run reply, which is
// the terminal record itself or {terminal: record}.
export function sessionResult(reply, params) {
  const t = reply?.terminal || reply || {};
  if (!t.address) return null;
  return { address: t.address, name: t.name ?? params.name };
}

// The Herdr app draws a terminal only when its layout doc has it. A new
// session goes into a new tab at the end of its space, the way the
// herdr-app helper adds one. Pure: returns a new doc, or null when the space
// is not in it. Every other space and tab is left as it was.
export function withNewTab(doc, spaceId, terminal, label, newId) {
  const spaces = doc?.spaces;
  if (!Array.isArray(spaces) || !spaces.some((s) => s.id === spaceId)) return null;
  const tid = newId('tb');
  const tab = { id: tid, name: label || '', root: { children: [{ content: { terminal }, id: newId('p'), type: 'block' }], id: tid, type: 'row' } };
  return { ...doc, spaces: spaces.map((s) => (s.id === spaceId ? { ...s, tabs: [...(s.tabs || []), tab] } : s)) };
}

// `git worktree list --porcelain`: one block per worktree.
export function parseWorktrees(text) {
  const out = [];
  let cur = null;
  for (const line of String(text || '').split('\n')) {
    if (line.startsWith('worktree ')) { cur = { path: line.slice(9), branch: null, head: null, detached: false, bare: false }; out.push(cur); continue; }
    if (!cur) continue;
    if (line.startsWith('HEAD ')) cur.head = line.slice(5);
    else if (line.startsWith('branch ')) cur.branch = line.slice(7).replace(/^refs\/heads\//, '');
    else if (line === 'detached') cur.detached = true;
    else if (line === 'bare') cur.bare = true;
  }
  return out.filter((w) => !w.bare);
}
