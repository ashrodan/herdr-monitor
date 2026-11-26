// herdr-ask's logic: the Gemini request, its tools, and the answer turned into
// actions the Summary mod can show as cards. No I/O here, so node:test can
// load it; bin/herdr-ask.js does the network call.
//
// The model only proposes. Each action becomes a card with a Run button in the
// mod; nothing runs without that click.

export const DEFAULT_MODEL = 'gemini-3.8-flash';
export const ENDPOINT = (model) => `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
export const AGENTS = ['claude', 'codex'];
const MAX_TURNS = 12;

export const TOOLS = [{
  functionDeclarations: [
    {
      name: 'spawn_agent',
      description: 'Start a new coding agent session on a piece of work. Use it when the person asks for work to be done in a repo.',
      parameters: {
        type: 'OBJECT',
        properties: {
          space_id: { type: 'STRING', description: 'The id of the space whose repo the work is in (from context.spaces).' },
          title: { type: 'STRING', description: 'A short tab name, at most 40 characters.' },
          brief: { type: 'STRING', description: "The agent's first message: the goal, what done means, and any constraint the person gave. Plain words, no preamble." },
          agent: { type: 'STRING', enum: AGENTS, description: 'Which coding agent. Default claude unless the person names codex.' },
          worktree: { type: 'BOOLEAN', description: 'true: a new git worktree and branch (code changes). false: run in the space folder (questions, reviews, reading).' },
          branch: { type: 'STRING', description: 'With worktree: a branch name like feat/short-slug or fix/short-slug.' },
        },
        required: ['space_id', 'title', 'brief', 'agent', 'worktree'],
      },
    },
    {
      name: 'tell_agent',
      description: 'Send a message to an agent that is already running (from context.agents), as if the person typed it.',
      parameters: {
        type: 'OBJECT',
        properties: {
          terminal: { type: 'STRING', description: "The agent's address from context.agents." },
          text: { type: 'STRING', description: 'The message to send.' },
        },
        required: ['terminal', 'text'],
      },
    },
    {
      name: 'hand_off',
      description: "Move a space's checkout, its uncommitted work and its agent to another machine. Opens the hand-off dialog; the person confirms there.",
      parameters: {
        type: 'OBJECT',
        properties: {
          space_id: { type: 'STRING', description: 'The space to hand off (from context.spaces).' },
          machine: { type: 'STRING', description: 'The target machine name from context.machines, or "here" for this computer.' },
        },
        required: ['space_id'],
      },
    },
  ],
}];

export function systemText(context) {
  return [
    'You are the coordinator inside the Herdr app, a terminal workspace where coding agents run in spaces.',
    'The person types short requests. Answer in one to three short sentences, plain words, no em dashes.',
    'When they ask for work, propose it with a tool call: spawn_agent for new work, tell_agent to steer a running agent, hand_off to move a space to another machine.',
    'Only use ids, addresses and machine names that appear in the context. If the request is ambiguous about which repo or agent, ask one short question instead of calling a tool.',
    'Your tool calls are proposals: the person sees each one as a card and clicks Run. Say what you propose, not that it is done.',
    'Always write text too, in the same answer as any tool call: answer every question the person asked, then one line on what the cards do.',
    'Answer questions about state (what is waiting, what runs, open PRs) from the context only.',
    '',
    'Context (JSON):',
    JSON.stringify(context ?? {}),
  ].join('\n');
}

// The generateContent body. history: [{role: 'user'|'model', text}], oldest first.
export function buildRequest({ message, history = [], context }) {
  const turns = history.slice(-MAX_TURNS).filter((h) => h?.text).map((h) => ({
    role: h.role === 'model' ? 'model' : 'user',
    parts: [{ text: String(h.text) }],
  }));
  return {
    systemInstruction: { parts: [{ text: systemText(context) }] },
    contents: [...turns, { role: 'user', parts: [{ text: String(message) }] }],
    tools: TOOLS,
    toolConfig: { functionCallingConfig: { mode: 'AUTO' } },
    generationConfig: { temperature: 0.2, maxOutputTokens: 1024 },
  };
}

// Gemini often answers a tool call with no text. The second round: the model's
// own turn back as it came (its thoughtSignature included), and one
// functionResponse per call saying it waits for the person; the model then writes the text.
export function needsFollowup(json) {
  const parts = json?.candidates?.[0]?.content?.parts ?? [];
  return parts.some((p) => p.functionCall) && !parts.some((p) => typeof p.text === 'string' && !p.thought && p.text.trim());
}

export function buildFollowup(body, json) {
  const content = json.candidates[0].content;
  const responses = content.parts.filter((p) => p.functionCall).map((p) => ({
    functionResponse: { name: p.functionCall.name, response: { status: 'proposed: shown to the person as a card with a Run button; not run yet' } },
  }));
  return {
    ...body,
    contents: [...body.contents, { role: 'model', parts: content.parts }, { role: 'user', parts: responses }],
    toolConfig: { functionCallingConfig: { mode: 'NONE' } },
  };
}

// The reply text of a (follow-up) answer.
export const textOf = (json) => (json?.candidates?.[0]?.content?.parts ?? [])
  .filter((p) => typeof p.text === 'string' && !p.thought).map((p) => p.text).join('').trim();

export function slug(text, max = 32) {
  const s = String(text ?? '').toLowerCase().normalize('NFKD')
    .split('').map((c) => (/[a-z0-9]/.test(c) ? c : '-')).join('')
    .split('-').filter(Boolean).join('-');
  return s.slice(0, max).replace(/-+$/, '') || 'task';
}

// Branch names the model gives are checked, never trusted: a plain
// prefix/slug, else one is made from the title.
export function branchOf(given, title) {
  const ok = typeof given === 'string' && /^(feat|fix|chore|docs|spike)\/[a-z0-9][a-z0-9-]{0,40}$/.test(given);
  return ok ? given : `feat/${slug(title)}`;
}

// One function call → one action, or {drop: reason} when it names something
// the context doesn't have.
export function toAction(call, context = {}) {
  const a = call?.args ?? {};
  const spaces = context.spaces ?? [];
  const space = (id) => spaces.find((s) => s.id === id);
  if (call?.name === 'spawn_agent') {
    const s = space(a.space_id);
    if (!s) return { drop: `no space ${a.space_id}` };
    const title = String(a.title ?? '').slice(0, 40).trim() || 'task';
    const worktree = a.worktree !== false;
    return {
      type: 'spawn', space_id: s.id, space_name: s.name, machine: s.machine ?? null, folder: s.folder ?? null,
      title, brief: String(a.brief ?? '').trim(), agent: AGENTS.includes(a.agent) ? a.agent : 'claude',
      worktree, branch: worktree ? branchOf(a.branch, title) : null,
    };
  }
  if (call?.name === 'tell_agent') {
    const ag = (context.agents ?? []).find((x) => x.address === a.terminal);
    if (!ag) return { drop: `no agent ${a.terminal}` };
    const text = String(a.text ?? '').trim();
    if (!text) return { drop: 'empty message' };
    return { type: 'tell', terminal: ag.address, label: [ag.space, ag.title].filter(Boolean).join(' · ') || ag.address, text };
  }
  if (call?.name === 'hand_off') {
    const s = space(a.space_id);
    if (!s) return { drop: `no space ${a.space_id}` };
    const names = (context.machines ?? []).map((m) => m.name);
    const machine = a.machine === 'here' || names.includes(a.machine) ? a.machine : null;
    return { type: 'handoff', space_id: s.id, space_name: s.name, machine };
  }
  return { drop: `unknown tool ${call?.name}` };
}

// The generateContent answer → {reply, actions, dropped}.
export function parseResponse(json, context) {
  const cand = json?.candidates?.[0];
  const parts = cand?.content?.parts ?? [];
  const text = parts.filter((p) => typeof p.text === 'string' && !p.thought).map((p) => p.text).join('').trim();
  const actions = [], dropped = [];
  for (const p of parts) {
    if (!p.functionCall) continue;
    const act = toAction(p.functionCall, context);
    if (act.drop) dropped.push(act.drop); else actions.push(act);
  }
  let reply = text;
  if (!reply && actions.length) reply = actions.length === 1 ? 'Here is what I propose:' : `I propose ${actions.length} steps:`;
  if (!reply && !actions.length) {
    const why = json?.promptFeedback?.blockReason || cand?.finishReason;
    reply = why ? `No answer (${why}).` : 'No answer.';
  }
  return { reply, actions, dropped };
}
