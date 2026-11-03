// Pure view-model helpers: names, groups, ages, the "seen" rule.
import type { AgentState, Space, Terminal } from './types';

const SHELLS = new Set(['zsh', 'bash', 'fish', 'sh', 'nu', 'pwsh']);
export const isAgent = (t: Terminal) => t.reported || !SHELLS.has(t.agent);
export const isShell = (t: Terminal) => !isAgent(t);

// Agents list order, like Herdr.
export const GROUPS: { state: AgentState; label: string }[] = [
  { state: 'blocked', label: 'Blocked' },
  { state: 'error', label: 'Error' },
  { state: 'done', label: 'Done' },
  { state: 'working', label: 'Working' },
  { state: 'idle', label: 'Idle' },
];

// "Done" turns into "idle" once you opened the agent after it finished.
const SEEN_KEY = 'herdr-monitor.seen';
function readSeen(): Record<string, number> { try { return JSON.parse(localStorage.getItem(SEEN_KEY) || '{}'); } catch { return {}; } }
let seen = readSeen();
export function markSeen(addr: string) {
  seen = { ...seen, [addr]: Date.now() };
  try { localStorage.setItem(SEEN_KEY, JSON.stringify(seen)); } catch { /* private mode */ }
}

export function stateOf(t: Terminal): AgentState {
  const s = (t.status?.state || 'idle') as AgentState;
  if (s === 'done' && t.changed_ms && Math.max(seen[t.address] || 0, t.seen_ms || 0) >= t.changed_ms) return 'idle';
  return s;
}
// The raw state for the detail view (not lowered by "seen").
export const rawStateOf = (t: Terminal): AgentState | 'finished' => (t.state === 'finished' ? 'finished' : (t.status?.state || 'idle') as AgentState);

export interface Where { space?: Space; tabName?: string; tabNamed?: boolean }
export function whereOf(t: Terminal, spaces: Space[]): Where {
  for (const s of spaces) {
    if (t.space && s.id !== t.space) continue;
    const tab = s.tabs.find((tb) => (t.tab ? tb.id === t.tab : tb.terminals.includes(t.address)));
    if (tab || (t.space && s.id === t.space)) return { space: s, tabName: tab?.name, tabNamed: tab?.named };
  }
  return {};
}

// Name: its own name, else the space name (+ the tab when the tab has a name).
export function displayName(t: Terminal, spaces: Space[]): string {
  if (t.name) return t.name;
  const w = whereOf(t, spaces);
  if (w.space) return w.space.name + (w.tabNamed ? ` · ${w.tabName}` : '');
  return t.where?.split(' / ')[0] || t.agent;
}

export function age(ms: number | null | undefined, now = Date.now()): string {
  if (!ms) return '';
  const s = Math.max(0, (now - ms) / 1000);
  if (s < 60) return '<1m';
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return '';
  const a = age(new Date(iso).getTime());
  return a === '<1m' ? 'just now' : `${a} ago`;
}

export const shortPath = (p: string | null | undefined) => (p || '').replace(/^\/(Users|home)\/[^/]+(?=\/|$)/, '~');

// Agents grouped by their own state, flat: the space view keeps the
// hierarchy. Only terminals with a status count as agents (as in Herdr), so dev
// servers and shells stay out.
export interface AgentRow { t: Terminal; state: AgentState }
export function groupAgents(terminals: Terminal[]) {
  const agents = terminals.filter((t) => t.state !== 'finished' && !!t.status);
  return GROUPS.map((g) => {
    const rows: AgentRow[] = agents.filter((t) => stateOf(t) === g.state)
      .sort((a, b) => (b.changed_ms || 0) - (a.changed_ms || 0))
      .map((t) => ({ t, state: g.state }));
    return { ...g, rows, count: rows.length };
  }).filter((g) => g.rows.length);
}

// Agents as trees: each agent with the sub-agents and terminals it started
// (dev servers, shells), no state sections. Families sort by their latest
// activity. Only terminals with a status start a family.
export interface TreeRow { t: Terminal; depth: number; agent: boolean }
export function agentTrees(terminals: Terminal[]): TreeRow[][] {
  const live = terminals.filter((t) => t.state !== 'finished');
  const by = new Map(live.map((t) => [t.address, t]));
  const kids = new Map<string, Terminal[]>();
  for (const t of live) if (t.parent && by.has(t.parent)) kids.set(t.parent, [...(kids.get(t.parent) || []), t]);
  const isRoot = (t: Terminal) => !!t.status && !(t.parent && by.get(t.parent)?.status);
  const families = live.filter(isRoot).map((root) => {
    const rows: TreeRow[] = [];
    let latest = 0;
    const walk = (t: Terminal, depth: number) => {
      rows.push({ t, depth, agent: !!t.status });
      latest = Math.max(latest, t.changed_ms || 0);
      for (const c of (kids.get(t.address) || []).sort((a, b) => (b.changed_ms || 0) - (a.changed_ms || 0))) walk(c, depth + 1);
    };
    walk(root, 0);
    return { rows, latest };
  });
  return families.sort((a, b) => b.latest - a.latest).map((f) => f.rows);
}

// The most urgent state among a space's terminals, for its dot.
export function spaceState(s: Space, terminals: Map<string, Terminal>): AgentState | null {
  const states = s.tabs.flatMap((tb) => tb.terminals).map((a) => terminals.get(a)).filter((t): t is Terminal => !!t && t.state !== 'finished').map(stateOf);
  return (['blocked', 'error', 'working'] as AgentState[]).find((x) => states.includes(x)) || null;
}

// The pane to open for a space: the one that most needs you (waiting, then
// done, then working), else its first agent, else its first terminal.
const RANK: Record<string, number> = { blocked: 0, error: 0, done: 1, working: 2 };
export function bestTerminal(s: Space, terminals: Map<string, Terminal>): string | null {
  let best: string | null = null, bestRank = 9;
  for (const tb of s.tabs) for (const a of tb.terminals) {
    const t = terminals.get(a);
    if (!t) continue;
    const r = t.state === 'finished' ? 5 : RANK[stateOf(t)] ?? (isAgent(t) ? 3 : 4);
    if (r < bestRank) { best = a; bestRank = r; }
  }
  return best;
}
