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
  if (s === 'done' && t.changed_ms && (seen[t.address] || 0) >= t.changed_ms) return 'idle';
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

// Agents grouped by state; children right after their parent.
export function groupAgents(terminals: Terminal[]) {
  const live = terminals.filter((t) => isAgent(t) && t.state !== 'finished');
  const byState = new Map<AgentState, Terminal[]>();
  for (const g of GROUPS) byState.set(g.state, []);
  for (const t of live) (byState.get(stateOf(t)) || byState.get('idle')!).push(t);
  return GROUPS.map((g) => {
    const rows = byState.get(g.state)!.sort((a, b) => (b.changed_ms || 0) - (a.changed_ms || 0));
    const inGroup = new Set(rows.map((t) => t.address));
    const out: { t: Terminal; depth: number }[] = [];
    const add = (t: Terminal, depth: number) => {
      out.push({ t, depth });
      for (const c of rows) if (c.parent === t.address) add(c, depth + 1);
    };
    for (const t of rows) if (!t.parent || !inGroup.has(t.parent)) add(t, t.parent ? 1 : 0);
    return { ...g, rows: out };
  }).filter((g) => g.rows.length);
}

// The most urgent state among a space's terminals, for its dot.
export function spaceState(s: Space, terminals: Map<string, Terminal>): AgentState | null {
  const states = s.tabs.flatMap((tb) => tb.terminals).map((a) => terminals.get(a)).filter((t): t is Terminal => !!t && t.state !== 'finished').map(stateOf);
  return (['blocked', 'error', 'working'] as AgentState[]).find((x) => states.includes(x)) || null;
}
