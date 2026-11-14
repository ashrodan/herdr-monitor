// What the MCP endpoint offers about Herdr: tools, the agent directory
// resource, change events and the instructions. The protocol is lib/mcp.js.
//
// Two scopes, from the URL (server.js):
//   {kind: 'board'}                 /mcp: every space; coordination.
//   {kind: 'space', space: '<id>'}  /mcp/spaces/<id or name>: one space;
//                                   tools refuse agents outside it.
//
// Progressive disclosure: the instructions name the first tool to call;
// every tool result ends with what to do next; herdr_guide and the skills
// hold the longer workflows.

export const AGENTS_URI = 'herdr://agents';
export const WAIT_DEFAULT_S = 20;
export const WAIT_MAX_S = 120;
const TEXT_MAX = 2000;   // per chat part
const READ_MAX = 14000;  // per herdr_read answer

// ---- Pure helpers ----

export const isAgent = (t) => !!(t.reported || t.status?.app || t.status?.state);

// One word for where a terminal is: working, blocked, done, idle, error,
// running (a program with no status) or exited.
export function stateOf(t) {
  if (t.state && t.state !== 'live') return 'exited';
  return t.status?.state || (isAgent(t) ? 'idle' : 'running');
}

const NEEDS_YOU = new Set(['blocked', 'error']);

export function ago(ms, now = Date.now()) {
  if (!ms) return '';
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86400) return `${Math.round(s / 3600)}h`;
  return `${Math.round(s / 86400)}d`;
}

const clip = (s, n) => { s = String(s ?? ''); return s.length > n ? `${s.slice(0, n - 1)}…` : s; };

// One terminal as one line: t5 · claude-code · blocked 3m · "title" · tab 1
export function row(t, { now = Date.now(), tabName, spaceName } = {}) {
  const parts = [t.name ? `${t.address} (${t.name})` : t.address, t.agent, `${stateOf(t)} ${ago(t.changed_ms || t.created_ms, now)}`.trim()];
  if (t.status?.msg) parts.push(`asks: ${clip(t.status.msg, 120)}`);
  else if (t.title) parts.push(`"${clip(t.title, 90)}"`);
  if (spaceName) parts.push(`space ${spaceName}`);
  if (tabName) parts.push(`tab ${tabName}`);
  return parts.join(' · ');
}

// The chat as plain text for a model: newest last, tools as one line each,
// a pending question with numbered options.
export function chatText(messages, last = 6) {
  const out = [];
  for (const m of messages.slice(-last)) {
    const lines = [];
    for (const p of m.parts || []) {
      if (p.kind === 'text') lines.push(clip(p.text, TEXT_MAX));
      else if (p.kind === 'tool') lines.push(`→ ${p.name}${p.summary ? `: ${clip(p.summary, 160)}` : ''}`);
      else if (p.kind === 'question') lines.push(questionText(p));
    }
    if (lines.length) out.push(`[${m.role}${m.at ? ` ${m.at.slice(11, 16)}` : ''}]\n${lines.join('\n')}`);
  }
  return out.join('\n\n');
}

export function questionText(p) {
  const head = p.result ? 'Question (answered)' : 'Question (WAITING for an answer: use herdr_answer)';
  return [head, ...p.questions.map((q, i) => [
    `  Q${i + 1}${q.header ? ` [${q.header}]` : ''}${q.multi ? ' (pick any, then submit)' : ''}: ${q.question}`,
    ...q.options.map((o, j) => `    ${j + 1}. ${o.label}${o.description ? ` — ${clip(o.description, 140)}` : ''}`),
  ].join('\n'))].join('\n');
}

// The question the agent waits on: the last question part with no result.
export function pendingQuestion(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    for (const p of [...(messages[i].parts || [])].reverse()) {
      if (p.kind === 'question') return p.result ? null : p;
    }
    if (messages[i].role === 'user') return null; // the person spoke after it
  }
  return null;
}

// Keys allowed for a dialog (same set as POST /api/terminals/:t/keys).
export const isDialogKey = (k) => typeof k === 'string' && /^(up|down|left|right|tab|shift\+tab|space|enter|esc|backspace|[a-z0-9])$/.test(k);

// ---- The app ----

// deps: monitor (terminals, layout, call, on), chatFor(pid), overview,
// startSession({space, harness, prompt}), answer(addr, body),
// worktrees(spaceId), skills (lib/mcp-skills.js), now().
export function herdrMcp(deps) {
  const { monitor, skills } = deps;
  const now = deps.now || Date.now;

  // ---- Scope and lookups ----

  const spaces = () => monitor.layout || [];
  const spaceById = (id) => spaces().find((s) => s.id === id) || null;

  // A space from an id, a name (any case) or its folder's last part.
  function findSpace(ref) {
    if (!ref) return null;
    const r = String(ref).trim();
    const lower = r.toLowerCase();
    return spaces().find((s) => s.id === r)
      || spaces().find((s) => (s.name || '').toLowerCase() === lower)
      || spaces().find((s) => (s.folder || '').split('/').pop().toLowerCase() === lower)
      || null;
  }

  const inScope = (t, scope) => scope.kind !== 'space' || t.space === scope.space;
  const terminalsIn = (scope) => [...monitor.terminals.values()].filter((t) => inScope(t, scope));
  const tabNameOf = (t) => spaceById(t.space)?.tabs?.find((x) => x.id === t.tab)?.name;
  const spaceNameOf = (t) => spaceById(t.space)?.name;

  function findAgent(ref, scope) {
    if (!ref) throw new Error('Name the agent: its address (t5, or machine/t5) or its name. herdr_space lists them.');
    const r = String(ref).trim();
    const all = [...monitor.terminals.values()];
    const t = monitor.terminals.get(r) || all.find((x) => x.name === r);
    if (!t) throw new Error(`No terminal "${r}". Call ${scope.kind === 'space' ? 'herdr_space' : 'herdr_overview'} to list agents.`);
    if (!inScope(t, scope)) throw new Error(`${r} is not in this space. This endpoint only acts on agents in space ${spaceById(scope.space)?.name}.`);
    return t;
  }

  function spaceArg(args, scope, { required = true } = {}) {
    if (scope.kind === 'space') return spaceById(scope.space);
    const s = findSpace(args.space);
    if (!s && required) throw new Error(args.space ? `No space "${args.space}". herdr_overview lists the spaces.` : 'Name the space (id or name). herdr_overview lists the spaces.');
    return s;
  }

  const ensureLinked = () => { if (!monitor.connected) throw new Error('herdr-monitor has no link to herdr-core (hc) right now. Try again in a few seconds.'); };

  // ---- Renderers ----

  function overviewText() {
    const ts = [...monitor.terminals.values()];
    const agents = ts.filter((t) => isAgent(t) && stateOf(t) !== 'exited');
    const by = (st) => agents.filter((t) => stateOf(t) === st);
    const lines = [];
    const needs = agents.filter((t) => NEEDS_YOU.has(stateOf(t)));
    lines.push(`${agents.length} agents in ${spaces().length} spaces: ${needs.length} need you, ${by('working').length} working, ${by('done').length} done, ${by('idle').length} idle.`);
    const list = (title, items) => {
      if (!items.length) return;
      lines.push('', `${title}:`);
      for (const t of items.sort((a, b) => (b.changed_ms || 0) - (a.changed_ms || 0))) lines.push(`- ${row(t, { now: now(), spaceName: spaceNameOf(t), tabName: tabNameOf(t) })}`);
    };
    list('Needs you', needs);
    list('Done (ready to review)', by('done'));
    list('Working', by('working'));
    lines.push('', 'Spaces (agents: working/needs you/done/idle):');
    for (const s of spaces()) {
      const own = agents.filter((t) => t.space === s.id);
      if (!own.length) continue;
      const c = (st) => own.filter((t) => stateOf(t) === st).length;
      const needN = own.filter((t) => NEEDS_YOU.has(stateOf(t))).length;
      lines.push(`- ${s.name}${s.branch ? ` [${s.branch}]` : ''}: ${c('working')}/${needN}/${c('done')}/${c('idle')}`);
    }
    const empty = spaces().filter((s) => !agents.some((t) => t.space === s.id)).length;
    if (empty) lines.push(`- (${empty} more spaces with no live agent)`);
    lines.push('', 'Next: herdr_space for one space; herdr_read to see what an agent said; herdr_answer for an agent that needs you.');
    return lines.join('\n');
  }

  async function spaceText(s, { worktrees = false } = {}) {
    const lines = [`Space ${s.name} (${s.id})`, `Folder: ${s.folder || '-'}`];
    if (s.repo) lines.push(`Repo: ${s.repo}${s.branch ? ` on ${s.branch}` : ''}${s.worktree ? ` (worktree of ${s.mainRepo})` : ''}`);
    const children = spaces().filter((x) => x.parent === s.id);
    if (children.length) lines.push(`Worktree spaces: ${children.map((x) => x.name).join(', ')}`);
    for (const tab of s.tabs || []) {
      lines.push('', `Tab ${tab.name}:`);
      for (const addr of tab.terminals || []) {
        const t = monitor.terminals.get(addr);
        lines.push(`- ${t ? `${isAgent(t) ? 'agent' : 'program'} ${row(t, { now: now() })}` : `${addr} (not running)`}`);
      }
    }
    if (worktrees && deps.worktrees) {
      const w = await deps.worktrees(s.id).catch(() => null);
      if (w?.worktrees?.length) {
        lines.push('', 'Git worktrees:');
        for (const x of w.worktrees) lines.push(`- ${x.path}${x.branch ? ` [${x.branch}]` : ''}${x.space ? ` (space ${spaceById(x.space)?.name})` : ' (no space)'}`);
      }
    }
    lines.push('', 'Next: herdr_read <agent> to see its conversation; herdr_send to give it work; herdr_start to add an agent here.');
    return lines.join('\n');
  }

  // ---- Tools ----
  // scopes: where the tool shows. schema(scope): the input schema there.

  const agentProp = { type: 'string', description: 'The agent: its address (t5, or machine/t5) or its name.' };
  const spaceProp = { type: 'string', description: 'The space: its name or id, from herdr_overview.' };
  const obj = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
  const withSpace = (scope, props, req = []) => (scope.kind === 'space' ? obj(props, req) : obj({ space: spaceProp, ...props }, ['space', ...req]));
  const RO = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
  const WRITE = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };

  const TOOLS = [
    {
      name: 'herdr_overview', title: 'Overview of all agents', scopes: ['board'], annotations: RO,
      description: 'Start here. Every space and agent, with the agents that need you first, then done, then working. Use it to triage and to find a space or agent to act on.',
      schema: () => obj({}),
      run: async () => { ensureLinked(); return overviewText(); },
    },
    {
      name: 'herdr_space', title: 'One space in detail', scopes: ['board', 'space'], annotations: RO,
      description: 'One space (a repo or worktree): its folder, branch, tabs and the agents and programs in each tab. Set worktrees to also list the git worktrees of its repo.',
      schema: (scope) => withSpace(scope, { worktrees: { type: 'boolean', description: 'Also list git worktrees (slower).' } }),
      run: async (a, { scope }) => { ensureLinked(); return spaceText(spaceArg(a, scope), { worktrees: a.worktrees === true }); },
    },
    {
      name: 'herdr_read', title: 'Read an agent', scopes: ['board', 'space'], annotations: RO,
      description: "What an agent said and did: the last messages of its conversation (Claude Code, omp, pi), with any question it waits on. source 'screen' gives the raw terminal text instead (programs, dialogs, other agents).",
      schema: () => obj({
        agent: agentProp,
        source: { type: 'string', enum: ['chat', 'screen'], description: "chat (default) or screen." },
        last: { type: 'integer', minimum: 1, maximum: 40, description: 'Chat messages to show (default 6), or screen lines of history (default 40).' },
      }, ['agent']),
      run: async (a, { scope }) => {
        ensureLinked();
        const t = findAgent(a.agent, scope);
        const head = row(t, { now: now(), spaceName: spaceNameOf(t), tabName: tabNameOf(t) });
        if (a.source !== 'screen' && t.pid) {
          const chat = await deps.chatFor(t.pid).catch(() => ({ available: false }));
          if (chat.available) {
            const body = clip(chatText(chat.messages || [], a.last || 6), READ_MAX);
            const pending = pendingQuestion(chat.messages || []);
            const next = pending ? 'Next: herdr_answer to pick an option.' : stateOf(t) === 'working' ? 'Next: herdr_wait until it stops, then read again.' : 'Next: herdr_send to reply or give it the next task.';
            return `${head}\n\n${body || '(no messages yet)'}\n\n${next}`;
          }
        }
        const r = await monitor.call('terminal.read', { terminal: t.address, lines: Math.min(a.last || 40, 400) });
        const text = String(r?.text ?? '').replace(/\s+$/, '');
        return `${head}\n\n${clip(text.split('\n').slice(-120).join('\n'), READ_MAX) || '(empty screen)'}\n\nNext: herdr_answer with keys for a dialog on screen; herdr_send to type a message.`;
      },
    },
    {
      name: 'herdr_send', title: 'Message an agent', scopes: ['board', 'space'], annotations: WRITE,
      description: 'Type a message into an agent and press Enter, as a person would. Delivery is not completion: follow with herdr_wait, then herdr_read. Refused while the agent waits at a question unless force is true (use herdr_answer for that).',
      schema: () => obj({ agent: agentProp, text: { type: 'string', minLength: 1, maxLength: 8000, description: 'The message.' }, force: { type: 'boolean', description: 'Type even while the agent is blocked at a dialog.' } }, ['agent', 'text']),
      run: async (a, { scope }) => {
        ensureLinked();
        const t = findAgent(a.agent, scope);
        if (typeof a.text !== 'string' || !a.text.trim()) throw new Error('text is empty.');
        try {
          await monitor.call('terminal.send', { terminal: t.address, text: a.text, force: a.force === true }, { timeoutMs: 90000 });
        } catch (err) {
          if (/blocked/.test(err.message)) throw new Error(`${t.address} waits at a question or dialog. Read it (herdr_read), then use herdr_answer, or send again with force: true.`);
          if (/finished/.test(err.message)) throw new Error(`${t.address} has exited. Start a new agent with herdr_start.`);
          throw err;
        }
        return `Delivered to ${t.address}. Next: herdr_wait ${t.address}, then herdr_read. Do not send it again on a wait timeout.`;
      },
    },
    {
      name: 'herdr_answer', title: 'Answer a waiting agent', scopes: ['board', 'space'], annotations: WRITE,
      description: "Answer what an agent is blocked on. For a question form (shown by herdr_read): give option (1-based) and question (1-based, default 1); for a multi-part or pick-any form, pick each answer, then call with submit true. For any other dialog (a permission prompt): give keys, such as ['1'] or ['down','enter'].",
      schema: () => obj({
        agent: agentProp,
        option: { type: 'integer', minimum: 1, description: 'Option number, as herdr_read shows it.' },
        question: { type: 'integer', minimum: 1, description: 'Question number (default 1).' },
        submit: { type: 'boolean', description: 'Submit a form after picking its answers.' },
        keys: { type: 'array', maxItems: 20, items: { type: 'string' }, description: 'Dialog keys: up, down, left, right, tab, shift+tab, space, enter, esc, backspace, a-z, 0-9.' },
      }, ['agent']),
      run: async (a, { scope }) => {
        ensureLinked();
        const t = findAgent(a.agent, scope);
        if (Array.isArray(a.keys) && a.keys.length) {
          if (!a.keys.every(isDialogKey)) throw new Error('keys: only up, down, left, right, tab, shift+tab, space, enter, esc, backspace, a-z, 0-9.');
          await monitor.call('terminal.keys', { terminal: t.address, keys: a.keys });
          return `Pressed ${a.keys.join(' ')} in ${t.address}. Next: herdr_read ${t.address} source screen to check the result.`;
        }
        const chat = t.pid ? await deps.chatFor(t.pid).catch(() => null) : null;
        const q = chat?.available ? pendingQuestion(chat.messages || []) : null;
        if (!q) throw new Error(`No question form in ${t.address}'s conversation. Read the screen (herdr_read source screen) and answer with keys.`);
        const shape = q.questions.map((x) => ({ question: x.question, options: x.options.length, multi: x.multi }));
        if (a.submit === true) {
          await deps.answer(t.address, { questions: shape, action: 'submit' });
          return `Submitted the form in ${t.address}. Next: herdr_wait, then herdr_read.`;
        }
        const qi = (a.question || 1) - 1, oi = (a.option || 0) - 1;
        const target = q.questions[qi];
        if (!target) throw new Error(`There is no question ${qi + 1}; the form has ${q.questions.length}.`);
        if (!(oi >= 0 && oi < target.options.length)) throw new Error(`Give option 1..${target.options.length} for question ${qi + 1}.`);
        await deps.answer(t.address, { questions: shape, q: qi, option: oi });
        const needsSubmit = q.questions.length > 1 || target.multi;
        return `Picked "${target.options[oi].label}" for Q${qi + 1} in ${t.address}.${needsSubmit ? ' Pick the other answers, then call herdr_answer with submit true.' : ' Next: herdr_wait, then herdr_read.'}`;
      },
    },
    {
      name: 'herdr_wait', title: 'Wait for an agent', scopes: ['board', 'space'], annotations: RO,
      description: `Wait until an agent stops working (done, blocked, error or exit), up to timeout_s seconds (default ${WAIT_DEFAULT_S}, max ${WAIT_MAX_S}). A timeout is a check-in, not a failure: call it again, and never resend the task. Keep it short (about 20 s) when your client cuts long tool calls.`,
      schema: () => obj({ agent: agentProp, timeout_s: { type: 'integer', minimum: 1, maximum: WAIT_MAX_S, description: `Seconds to wait (default ${WAIT_DEFAULT_S}).` } }, ['agent']),
      run: async (a, { scope }) => {
        ensureLinked();
        const t = findAgent(a.agent, scope);
        const timeout = Math.min(Math.max(Number(a.timeout_s) || WAIT_DEFAULT_S, 1), WAIT_MAX_S);
        const r = await monitor.call('terminal.wait', { terminal: t.address, timeout }, { timeoutMs: (timeout + 10) * 1000 });
        const cur = monitor.terminals.get(t.address) || t;
        if (!r?.ended) return `${t.address} is still ${r?.status || stateOf(cur)} after ${timeout}s. Call herdr_wait again; do not resend the task.`;
        const st = stateOf(cur);
        const next = NEEDS_YOU.has(st) || r.status === 'blocked' ? 'It needs you: herdr_read, then herdr_answer.' : 'Next: herdr_read to see the result.';
        return `${t.address} stopped: ${r.reason}${r.status ? ` (${r.status})` : ''}${r.exit_code != null ? `, exit code ${r.exit_code}` : ''}. ${next}`;
      },
    },
    {
      name: 'herdr_start', title: 'Start an agent', scopes: ['board', 'space'], annotations: WRITE,
      description: "Start a new coding agent (claude or omp) in a space's folder, with prompt as its first task. It opens in a new tab of that space in the Herdr app. Then herdr_wait and herdr_read.",
      schema: (scope) => withSpace(scope, {
        harness: { type: 'string', enum: ['claude', 'omp'], description: 'claude (Claude Code, default) or omp.' },
        prompt: { type: 'string', minLength: 1, maxLength: 4000, description: 'The task. It cannot start with "-".' },
      }, ['prompt']),
      run: async (a, { scope }) => {
        ensureLinked();
        const s = spaceArg(a, scope);
        const r = await deps.startSession({ space: s.id, harness: a.harness || 'claude', prompt: a.prompt });
        return `Started ${r.address} (${r.name}) in space ${s.name}${r.tab ? '' : ` (not placed in a tab: ${r.placeError})`}. Next: herdr_wait ${r.address}, then herdr_read ${r.address}.`;
      },
    },
    {
      name: 'herdr_interrupt', title: 'Stop what an agent is doing', scopes: ['board', 'space'],
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
      description: 'Press the stop key in an agent (Esc for an agent, Ctrl-C for a program), to end its current turn. It does not close the terminal.',
      schema: () => obj({ agent: agentProp }, ['agent']),
      run: async (a, { scope }) => {
        ensureLinked();
        const t = findAgent(a.agent, scope);
        const r = await monitor.call('terminal.interrupt', { terminal: t.address, timeout: 5 });
        return `Sent the stop key to ${t.address}. hc saw: ${JSON.stringify(r)}. Next: herdr_read ${t.address}.`;
      },
    },
    {
      name: 'herdr_prs', title: 'Open pull requests', scopes: ['board', 'space'], annotations: RO,
      description: 'Open pull requests for the spaces, with checks, review state and why each one cannot merge yet.',
      schema: () => obj({}),
      run: async (a, { scope }) => {
        const { prs = [] } = await deps.overview.get();
        const mine = scope.kind === 'space' ? prs.filter((p) => p.space === scope.space) : prs;
        if (!mine.length) return 'No open pull requests.';
        return mine.map((p) => {
          const r = p.readiness || {};
          return `- #${p.pr.number} ${p.spaceName} [${p.pr.headRefName}] ${p.pr.title}${p.pr.isDraft ? ' (draft)' : ''} — ${r.canMerge ? 'can merge' : (r.reasons || []).join(', ') || r.state || ''} — ${p.pr.url}`;
        }).join('\n');
      },
    },
    {
      name: 'herdr_guide', title: 'How to work in Herdr', scopes: ['board', 'space'], annotations: RO,
      description: 'Workflows for this server, as skills. No topic: the list. Read one before a multi-step job (coordinating agents, working in one space).',
      schema: () => obj({ topic: { type: 'string', enum: skills.names(), description: 'A skill name.' } }),
      run: async (a) => {
        if (!a.topic) return `Guides (call herdr_guide with a topic):\n${skills.describe()}`;
        const body = skills.body(a.topic);
        if (!body) throw new Error(`No guide "${a.topic}". Topics: ${skills.names().join(', ')}.`);
        return body;
      },
    },
  ];

  const tools = (scope) => TOOLS.filter((t) => t.scopes.includes(scope.kind)).map((t) => ({ ...t, inputSchema: t.schema(scope) }));

  // ---- Resources and change events ----

  function directory(scope) {
    const agents = terminalsIn(scope).filter(isAgent).map((t) => ({
      address: t.address, name: t.name, agent: t.agent, state: stateOf(t), question: t.status?.msg ?? null,
      title: t.title, space: t.space, spaceName: spaceNameOf(t) ?? null, tab: tabNameOf(t) ?? null, changed_ms: t.changed_ms ?? null,
    }));
    return { connected: monitor.connected, agents };
  }
  // What a change notification is about: membership and state, not titles.
  const digest = (scope) => JSON.stringify(directory(scope).agents.map((a) => [a.address, a.state, a.space, a.tab]));

  const resources = {
    list: (scope) => [{ uri: AGENTS_URI, name: 'agents', title: 'Herdr agents', mimeType: 'application/json',
      description: `The agents${scope.kind === 'space' ? ' in this space' : ''} and their state. Subscribe (subscriptions/listen) to hear when one changes state, then read again.` }],
    read: async (uri, scope) => (uri === AGENTS_URI ? { mimeType: 'application/json', text: JSON.stringify(directory(scope)) } : null),
  };

  // Debounced: a burst of hc events is one check per watcher.
  const watchers = new Set();
  let timer = null;
  const DEBOUNCE_MS = deps.debounceMs ?? 750;
  const kick = () => {
    if (timer || !watchers.size) return;
    timer = setTimeout(() => {
      timer = null;
      for (const w of watchers) {
        const d = digest(w.scope);
        if (d !== w.last) { w.last = d; w.cb(AGENTS_URI); }
      }
    }, DEBOUNCE_MS);
    timer.unref?.();
  };
  monitor.on?.('event', kick);
  const watch = (scope, cb) => {
    const w = { scope, cb, last: digest(scope) };
    watchers.add(w);
    return () => watchers.delete(w);
  };

  function instructions(scope) {
    if (scope.kind === 'space') {
      const s = spaceById(scope.space);
      return `This endpoint is scoped to one Herdr space: ${s?.name} (${s?.folder}). Its tools act only on the agents in that space. Start with herdr_space. Read an agent with herdr_read; act with herdr_send, herdr_answer or herdr_start; follow up with herdr_wait (short waits; on a timeout call it again, never resend). herdr_guide topic herdr-space has the full workflow.`;
    }
    return 'Herdr runs coding agents (Claude Code, omp, Codex) in spaces; a space is one repo or worktree on this machine. Start with herdr_overview to see who needs you. Then herdr_space for one space, herdr_read for one agent. Act with herdr_send, herdr_answer, herdr_start or herdr_interrupt, and follow up with herdr_wait (short waits; on a timeout call it again, never resend a task). herdr_guide has the workflows: herdr-coordinate for work across spaces, herdr-space for work in one space. A space-scoped endpoint is at /mcp/spaces/<space name>.';
  }

  return { tools, resources, watch, instructions, skills, findSpace };
}
