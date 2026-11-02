// A small home page: what needs you, what runs, open PRs.
import { useEffect, useState } from 'react';
import { CircleAlert, GitPullRequest, Loader, CheckCheck } from 'lucide-react';
import { cn } from '@/lib/utils';
import { getJson } from '@/api';
import type { Live } from '@/live';
import { age, displayName, isAgent, stateOf } from '@/model';
import { go } from '@/route';
import type { Overview as OverviewData, Terminal } from '@/types';
import { Glyph, SpaceAvatar, StatusMark } from './marks';

export function Overview({ live, now, onShowAgents }: { live: Live; now: number; onShowAgents?: () => void }) {
  const [data, setData] = useState<OverviewData | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    let stop = false;
    const load = () => getJson<OverviewData>('/api/overview').then((d) => { if (!stop) { setData(d); setErr(null); } }).catch((e) => !stop && setErr(e.message));
    load();
    const id = setInterval(load, 60000);
    return () => { stop = true; clearInterval(id); };
  }, []);

  const agents = [...live.terminals.values()].filter((t) => isAgent(t) && t.state !== 'finished');
  const by = (s: string) => agents.filter((t) => stateOf(t) === s).sort((a, b) => (b.changed_ms || 0) - (a.changed_ms || 0));
  const waiting = [...by('blocked'), ...by('error')];
  const working = by('working');
  const done = by('done');
  const prs = data?.prs || [];
  const ready = prs.filter((p) => p.readiness.canMerge).length;

  return (
    <div className="space-y-4 p-3 lg:mx-auto lg:max-w-3xl lg:p-6">
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <Tile icon={<CircleAlert />} label="Waiting for you" value={waiting.length} tone={waiting.length ? 'text-blocked' : undefined} onClick={onShowAgents} />
        <Tile icon={<Loader />} label="Working" value={working.length} tone={working.length ? 'text-working' : undefined} onClick={onShowAgents} />
        <Tile icon={<CheckCheck />} label="Done, not seen" value={done.length} tone={done.length ? 'text-done' : undefined} onClick={onShowAgents} />
        <Tile icon={<GitPullRequest />} label="Open PRs" value={data ? prs.length : '…'} sub={data && prs.length ? `${ready} ready to merge` : undefined} />
      </div>

      <Section title="Waiting for you" empty="Nothing needs you right now." rows={waiting} live={live} now={now} />
      <Section title="Done, not seen" rows={done} live={live} now={now} />
      <Section title="Working" rows={working} live={live} now={now} />

      <section>
        <h3 className="px-1 pb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Open PRs</h3>
        {err && <p className="px-1 text-sm text-error">Could not load PRs: {err}</p>}
        {!data && !err && <p className="px-1 text-sm text-muted-foreground">Checking each repo with gh…</p>}
        {data && !prs.length && <p className="px-1 text-sm text-muted-foreground">No open PRs in these spaces.</p>}
        <ul className="overflow-hidden rounded-lg border bg-card empty:hidden">
          {prs.map((p) => {
            const c = p.counts;
            const dot = c.fail ? 'fail' : c.pending ? 'pending' : null;
            return (
              <li key={`${p.space}-${p.pr.number}`} className="border-b last:border-b-0">
                <button className="flex w-full items-start gap-2.5 px-3 py-2.5 text-left hover:bg-accent/70" onClick={() => go({ view: 'space', id: p.space })}>
                  <SpaceAvatar space={{ name: p.spaceName, color: p.color }} className="mt-0.5" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">#{p.pr.number} {p.pr.title}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {p.spaceName} · {p.readiness.canMerge ? <span className="text-done">ready to merge</span> : p.readiness.reasons.join(', ')}
                    </span>
                  </span>
                  {dot && <span className={cn('mt-1.5 size-2 rounded-full', dot === 'fail' ? 'bg-error' : 'bg-blocked')} title={dot === 'fail' ? 'a check fails' : 'a check is pending'} />}
                </button>
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}

function Tile({ icon, label, value, sub, tone, onClick }: { icon: React.ReactNode; label: string; value: number | string; sub?: string; tone?: string; onClick?: () => void }) {
  return (
    <button onClick={onClick} disabled={!onClick} className="rounded-xl border bg-card p-3 text-left enabled:hover:bg-accent/60 disabled:cursor-default">
      <span className={cn('flex items-center gap-1.5 text-xs text-muted-foreground [&_svg]:size-3.5', tone)}>{icon}{label}</span>
      <span className={cn('mt-1 block text-2xl font-semibold tabular-nums', tone)}>{value}</span>
      {sub && <span className="block text-xs text-muted-foreground">{sub}</span>}
    </button>
  );
}

function Section({ title, rows, live, now, empty }: { title: string; rows: Terminal[]; live: Live; now: number; empty?: string }) {
  if (!rows.length && !empty) return null;
  return (
    <section>
      <h3 className="px-1 pb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{title}</h3>
      {!rows.length ? <p className="px-1 text-sm text-muted-foreground">{empty}</p> : (
        <ul className="overflow-hidden rounded-lg border bg-card">
          {rows.slice(0, 6).map((t) => (
            <li key={t.address} className="border-b last:border-b-0">
              <button className="flex w-full items-center gap-2.5 px-3 py-2.5 text-left hover:bg-accent/70" onClick={() => go({ view: 'agent', addr: t.address, tab: 'output' })}>
                <Glyph agent={t.agent} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium">{displayName(t, live.spaces)}</span>
                  {(t.status?.msg || t.title) && <span className="block truncate text-xs text-muted-foreground">{t.status?.msg || t.title}</span>}
                </span>
                <span className="text-xs tabular-nums text-muted-foreground">{age(t.changed_ms, now)}</span>
                <StatusMark state={stateOf(t)} />
              </button>
            </li>
          ))}
          {rows.length > 6 && <li className="px-3 py-2 text-xs text-muted-foreground">and {rows.length - 6} more</li>}
        </ul>
      )}
    </section>
  );
}
