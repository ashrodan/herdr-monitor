// The agent stream (GET /api/agents/stream): a small SSE feed for the
// Android app. It holds one connection open all day, so it carries only what
// a notification or a widget needs, and only when that changes.
//
// An agent is a terminal that reports a status (Claude Code, omp, ...), not a
// plain shell. Title churn does not count as a change.

// The phone's view of one terminal, or null when it is not an agent.
// sp: the terminal's space in the layout ({name, branch}), when known. The
// context fields come from the terminal and the layout only: no parsing of
// the display name.
export function agentOf(t, name, sp = null) {
  const state = t?.status?.state;
  if (!state) return null;
  return {
    address: t.address,
    name: name ?? t.name ?? t.address,
    state,
    kind: t.status.kind ?? null,
    msg: t.status.msg ?? null,
    changed_ms: t.changed_ms ?? null,
    seen_ms: t.seen_ms ?? null,
    harness: t.agent ?? null,
    space_id: t.space ?? null,
    space_name: sp?.name ?? null,
    branch: sp?.branch ?? null,
  };
}

// What counts as a change: state, name, status kind and msg. seen_ms too:
// Herdr sets it when you look at a finished agent on the Mac, and then the
// phone drops its "done" alert. And the context a notification shows
// (harness, space, branch).
const keyOf = (a) => JSON.stringify([a.state, a.name, a.kind, a.msg, a.seen_ms, a.harness, a.space_id, a.space_name, a.branch]);

export class AgentFeed {
  constructor() { this.keys = new Map(); } // address -> keyOf

  // All agents now. Pure: call diff() first so the clients already
  // connected do not miss a change.
  static snapshot(terminals, nameOf, spaceOf = () => null) {
    const agents = [];
    for (const t of terminals) {
      const a = agentOf(t, nameOf(t), spaceOf(t));
      if (a) agents.push(a);
    }
    return agents;
  }

  // The events since the last call: ['agent', a] for each new or changed
  // agent, ['removed', {address}] for each one that went away or stopped
  // reporting a status.
  diff(terminals, nameOf, spaceOf = () => null) {
    const out = [];
    const now = new Set();
    for (const t of terminals) {
      const a = agentOf(t, nameOf(t), spaceOf(t));
      if (!a) continue;
      now.add(a.address);
      const k = keyOf(a);
      if (this.keys.get(a.address) === k) continue;
      this.keys.set(a.address, k);
      out.push(['agent', a]);
    }
    for (const address of [...this.keys.keys()]) {
      if (now.has(address)) continue;
      this.keys.delete(address);
      out.push(['removed', { address }]);
    }
    return out;
  }
}

export const sseFrame = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
