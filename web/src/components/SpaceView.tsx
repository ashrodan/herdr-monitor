import { ChevronLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { Live } from '@/live';
import { age, isShell, rawStateOf, shortPath } from '@/model';
import { back, go } from '@/route';
import { Glyph, SpaceAvatar, StatusMark } from './marks';

export function SpaceView({ live, id, now }: { live: Live; id: string; now: number }) {
  const s = live.spaces.find((x) => x.id === id);
  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-2 py-2 md:px-4">
        <Button variant="ghost" size="icon" className="lg:hidden" onClick={back} aria-label="Back"><ChevronLeft /></Button>
        {s && <SpaceAvatar space={s} />}
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-base font-semibold">{s?.name || 'Space'}</h2>
          {s && <p className="truncate font-mono text-xs text-muted-foreground">{shortPath(s.repo)}{s.branch ? ` · ${s.branch}` : ''}{s.behind ? ` · ↓${s.behind}` : ''}</p>}
        </div>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto p-2 md:p-4">
        {!s && <p className="p-4 text-sm text-muted-foreground">{live.connected ? 'This space is gone.' : 'Connecting…'}</p>}
        {s?.tabs.map((tb) => (
          <section key={tb.id} className="mb-4">
            <h3 className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{tb.named ? tb.name : `Tab ${tb.name}`}</h3>
            <ul className="overflow-hidden rounded-lg border bg-card">
              {tb.terminals.map((a) => {
                const t = live.terminals.get(a);
                if (!t) return null;
                const st = rawStateOf(t);
                return (
                  <li key={a} className="border-b last:border-b-0">
                    <button className="flex w-full items-center gap-2.5 px-3 py-2.5 text-left hover:bg-accent/70 active:bg-accent"
                      onClick={() => go({ view: 'agent', addr: a, tab: 'output' })}>
                      <Glyph agent={t.agent} reported={t.reported} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-medium">{t.name || t.agent}{isShell(t) && <span className="ml-1.5 text-xs font-normal text-muted-foreground">shell</span>}</span>
                        <span className="block truncate text-xs text-muted-foreground">{t.status?.msg || t.title || shortPath(t.cwd)}</span>
                      </span>
                      <span className="text-xs tabular-nums text-muted-foreground">{age(t.changed_ms, now)}</span>
                      <StatusMark state={isShell(t) && st === 'idle' ? null : st} />
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}
