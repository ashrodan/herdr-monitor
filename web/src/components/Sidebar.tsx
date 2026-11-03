import { GitBranch } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Live } from '@/live';
import { age, displayName, groupAgents, spaceState } from '@/model';
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

export function AgentsList({ live, selected, now }: { live: Live; selected?: string; now: number }) {
  const groups = groupAgents([...live.terminals.values()]);
  if (!groups.length) return <Empty>{live.connected ? 'No agents running.' : 'Connecting to Herdr…'}</Empty>;
  return (
    <div className="px-1.5">
      {groups.map((g) => (
        <section key={g.state} className="mb-2">
          <h3 className="flex items-center gap-2 px-3 pt-2 pb-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            {g.label}<span className="font-normal tabular-nums">{g.count}</span>
          </h3>
          <ul>
            {g.rows.map(({ t, depth, state }) => (
              <li key={t.address}>
                <button className={cn(row, selected === t.address && 'bg-accent')} style={{ paddingLeft: 12 + depth * 18 }}
                  onClick={() => open({ view: 'agent', addr: t.address, tab: 'output' })}>
                  {depth > 0 && <span className="-ml-3 text-muted-foreground/60" aria-hidden>└</span>}
                  <Glyph agent={t.agent} reported={t.reported} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{displayName(t, live.spaces)}</span>
                    {(t.status?.msg || t.title) && <span className="block truncate text-xs text-muted-foreground">{t.status?.msg || t.title}</span>}
                  </span>
                  <span className="text-xs tabular-nums text-muted-foreground">{age(t.changed_ms, now)}</span>
                  <StatusMark state={state} />
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="px-4 py-6 text-sm text-muted-foreground">{children}</p>;
}
