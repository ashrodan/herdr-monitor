// POST /api/sessions: start one coding agent in an existing space.
// Pure: it checks the request against the layout and returns the
// terminal.run params. The client never sends a path or an argv.

export const HARNESSES = ['claude', 'omp'];
export const PROMPT_MAX = 4000;

// A name hc accepts (1-32 of a-z 0-9 - _) that starts with mnt1-.
export function sessionName(harness, now = Date.now()) {
  return `mnt1-${harness}-${now.toString(36).slice(-6)}`;
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
