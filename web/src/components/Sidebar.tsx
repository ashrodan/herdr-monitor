import { useState } from 'react';
import { GitBranch } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Live } from '@/live';
import { age, agentTrees, displayName, groupAgents, spaceState, stateOf } from '@/model';
import type { Space, Terminal } from '@/types';
import { open, spaceRoute } from '@/route';
import { Glyph, SpaceAvatar, StatusMark } from './marks';

const row = 'flex w-full items-center gap-2.5 px-3 py-2 text-left rounded-md hover:bg-accent/70 active:bg-accent focus-visible:outline-2';

export function SpacesList({ live, selected }: { live: Live; selected?: string }) {
  if (!live.spaces.length) return <Empty>{live.connected ? 'No spaces.' : 'Connecting to Herdr…'}</Empty>;
  return (
    <ul className="px-1.5">
      {live.spaces.map((s) => {
        const st = spaceState(s, live.terminals);
        const body = (
          <>
            {s.parent && <GitBranch className="ml-2 size-3.5 shrink-0 text-muted-foreground" />}
            {!s.parent && <SpaceAvatar space={s} />}
            <span className={cn('min-w-0 flex-1 truncate', s.virtual ? 'text-muted-foreground' : 'font-medium')}>{s.name}</span>
            {st && <StatusMark state={st} className="size-2" />}
            {s.branch && <span className="max-w-[40%] truncate text-xs text-muted-foreground">{s.branch}</span>}
            {s.behind > 0 && <span className="text-xs font-medium text-error">↓{s.behind}</span>}
          </>
        );
        return (
          <li key={s.id}>
            {s.virtual
              ? <div className={cn(row, 'hover:bg-transparent active:bg-transparent cursor-default')}>{body}</div>
              : <button className={cn(row, s.parent && 'pl-5', selected === s.id && 'bg-accent')} onClick={() => open(spaceRoute(s, live.terminals))}>{body}</button>}
          </li>
        );
      })}
    </ul>
  );
}

// Agents three ways: by state (default, flat); by space (each space in
// Herdr's order, worktrees under their repo, its agents under it); nested
// (each agent with the sub-agents and terminals it started).
type AgentsView = 'state' | 'space' | 'nested';
const VIEW_KEY = 'herdr-monitor.agentsView';

export function AgentsList({ live, selected, now }: { live: Live; selected?: string; now: number }) {
  const [view, setView] = useState<AgentsView>(() => (localStorage.getItem(VIEW_KEY) as AgentsView) || 'state');
  const pick = (v: AgentsView) => { setView(v); try { localStorage.setItem(VIEW_KEY, v); } catch { /* private mode */ } };
  const groups = groupAgents([...live.terminals.values()]);
  if (!groups.length) return <Empty>{live.connected ? 'No agents running.' : 'Connecting to Herdr…'}</Empty>;
  const agentRow = (t: Terminal, label: string, indent = false, depth = 0) => (
    <li key={t.address}>
      <button className={cn(row, indent && 'pl-8', selected === t.address && 'bg-accent')} style={depth ? { paddingLeft: 12 + depth * 20 } : undefined}
        onClick={() => open({ view: 'agent', addr: t.address, tab: 'output' })}>
        {depth > 0 && <span className="-ml-3.5 w-2.5 shrink-0 text-muted-foreground/60" aria-hidden>└</span>}
        <Glyph agent={t.agent} reported={t.reported} />
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{label}</span>
          {(t.status?.msg || t.title) && <span className="block truncate text-xs text-muted-foreground">{t.status?.msg || t.title}</span>}
        </span>
        <span className="text-xs tabular-nums text-muted-foreground">{age(t.changed_ms, now)}</span>
        <StatusMark state={t.status ? stateOf(t) : null} />
      </button>
    </li>
  );
  return (
    <div className="px-1.5">
      <div className="flex justify-end px-2 pt-1" role="group" aria-label="Group agents">
        <div className="flex overflow-hidden rounded-md border text-[11px]">
          {(['state', 'space', 'nested'] as const).map((v) => (
            <button key={v} onClick={() => pick(v)} aria-pressed={view === v}
              className={cn('px-2.5 py-1', view === v ? 'bg-accent font-medium' : 'text-muted-foreground')}>
              {v === 'state' ? 'By state' : v === 'space' ? 'By space' : 'Nested'}
            </button>
          ))}
        </div>
      </div>
      {view === 'state' ? groups.map((g) => (
        <section key={g.state} className="mb-2">
          <h3 className="flex items-center gap-2 px-3 pt-2 pb-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            {g.label}<span className="font-normal tabular-nums">{g.count}</span>
          </h3>
          <ul>{g.rows.map(({ t }) => agentRow(t, displayName(t, live.spaces)))}</ul>
        </section>
      )) : view === 'nested' ? (
        <div className="pt-1">{agentTrees([...live.terminals.values()]).map((rows) => (
          <ul key={rows[0].t.address} className="mb-1 border-b border-border/50 pb-1 last:border-b-0">
            {rows.map(({ t, depth }) => agentRow(t, depth ? (t.name || t.agent) : displayName(t, live.spaces), false, depth))}
          </ul>
        ))}</div>
      ) : <BySpace live={live} render={(s, agents) => {
        return (
          <section key={s.id} className="mb-1">
            <h3 className={cn('flex items-center gap-2 px-3 pt-2 pb-0.5 text-[13px] font-semibold', s.parent && 'pl-5')}>
              {s.parent ? <GitBranch className="size-3.5 shrink-0 text-muted-foreground" /> : <SpaceAvatar space={s} />}
              <span className="min-w-0 flex-1 truncate">{s.name}</span>
              {s.branch && <span className="max-w-[40%] truncate text-xs font-normal text-muted-foreground">{s.branch}</span>}
            </h3>
            <ul>{agents.map(({ t, tab }) => agentRow(t, t.name || tab || PROGRAM[t.status?.app || ''] || PROGRAM[t.agent] || t.agent, true))}</ul>
          </section>
        );
      }} other={(rest) => rest.length > 0 && (
        <section className="mb-1">
          <h3 className="px-3 pt-2 pb-0.5 text-[13px] font-semibold text-muted-foreground">Not in a space</h3>
          <ul>{rest.map((t) => agentRow(t, displayName(t, live.spaces), true))}</ul>
        </section>
      )} />}
    </div>
  );
}

function BySpace({ live, render, other }: { live: Live; render: (s: Space, agents: { t: Terminal; tab: string }[]) => React.ReactNode; other: (rest: Terminal[]) => React.ReactNode }) {
  const seen = new Set<string>();
  const parts = live.spaces.map((s) => {
    const agents = agentsIn(s, live.terminals);
    agents.forEach(({ t }) => seen.add(t.address));
    return agents.length ? <div key={s.id}>{render(s, agents)}</div> : null;
  });
  const rest = [...live.terminals.values()].filter((t) => t.state !== 'finished' && t.status && !seen.has(t.address));
  return <>{parts}{other(rest)}</>;
}

// A space's agents (terminals with a status), in tab order, with the tab's
// name when it has one.
const PROGRAM: Record<string, string> = { 'claude-code': 'Claude Code', claude: 'Claude Code', codex: 'Codex', omp: 'omp', pi: 'pi', cursor: 'Cursor' };
function agentsIn(s: Space, terminals: Map<string, Terminal>) {
  const out: { t: Terminal; tab: string }[] = [];
  for (const tb of s.tabs) for (const a of tb.terminals) {
    const t = terminals.get(a);
    if (t && t.state !== 'finished' && t.status) out.push({ t, tab: tb.named ? tb.name : '' });
  }
  return out;
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="px-4 py-6 text-sm text-muted-foreground">{children}</p>;
}
