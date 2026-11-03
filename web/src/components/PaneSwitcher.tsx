// The agent header's name is a menu: every pane of this space, by tab, and
// the other spaces. Picking one switches in place: Back still goes to the list.
import { useState } from 'react';
import { Check, ChevronDown, ChevronRight } from 'lucide-react';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import type { Live } from '@/live';
import { displayName, isShell, rawStateOf, spaceState, stateOf, whereOf } from '@/model';
import { spaceRoute, switchTo, type AgentTab } from '@/route';
import type { Terminal } from '@/types';
import { Glyph, SpaceAvatar, StatusMark } from './marks';

const TONE: Record<string, string> = {
  working: 'text-working', blocked: 'text-blocked', error: 'text-error', done: 'text-done', idle: 'text-muted-foreground', finished: 'text-muted-foreground',
};
const WORD: Record<string, string> = { blocked: 'waiting', working: 'working', done: 'done', idle: 'idle', error: 'error', finished: 'finished' };

export function PaneSwitcher({ t, live, tab }: { t: Terminal; live: Live; tab: AgentTab }) {
  // Other spaces open inside the menu (a side submenu falls off a phone screen).
  const [spacesOpen, setSpacesOpen] = useState(false);
  const where = whereOf(t, live.spaces);
  const space = where.space;
  const st = rawStateOf(t);
  const others = live.spaces.filter((s) => !s.virtual && s.id !== space?.id);
  const pick = (addr: string) => { if (addr !== t.address) switchTo({ view: 'agent', addr, tab }); };
  const sub = t.status?.msg || t.title || (where.tabName ? (where.tabNamed ? where.tabName : `Tab ${where.tabName}`) : t.where || '');

  return (
    <DropdownMenu onOpenChange={(o) => { if (!o) setSpacesOpen(false); }}>
      <DropdownMenuTrigger asChild>
        <button className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-1 py-0.5 text-left hover:bg-accent/60 active:bg-accent"
          aria-label="Switch pane" title={`${t.address}${t.where ? ` · ${t.where}` : ''} · ${t.cwd}`}>
          <Glyph agent={t.agent} reported={t.reported} />
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-1">
              <span className="truncate text-[15px] font-semibold leading-tight">{displayName(t, live.spaces)}</span>
              <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" />
            </span>
            <span className="block truncate text-xs text-muted-foreground">
              <span className={TONE[st]}>{WORD[st] ?? st}{t.status?.kind ? ` · ${t.status.kind}` : ''}</span>{sub ? ` · ${sub}` : ''}
            </span>
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="max-h-[70vh] w-72 max-w-[calc(100vw-1rem)] overflow-y-auto">
        {space ? (
          <>
            <DropdownMenuLabel className="flex items-center gap-2"><SpaceAvatar space={space} />{space.name}</DropdownMenuLabel>
            {space.tabs.map((tb) => (
              <div key={tb.id}>
                <DropdownMenuLabel className="pt-2 pb-0.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                  {tb.named ? tb.name : `Tab ${tb.name}`}
                </DropdownMenuLabel>
                {tb.terminals.map((a) => {
                  const x = live.terminals.get(a);
                  if (!x) return null;
                  const xs = rawStateOf(x);
                  return (
                    <DropdownMenuItem key={a} onSelect={() => pick(a)} className={cn('gap-2', a === t.address && 'bg-accent/60')}>
                      <Glyph agent={x.agent} reported={x.reported} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate">{x.name || x.agent}{isShell(x) && <span className="ml-1.5 text-xs text-muted-foreground">shell</span>}</span>
                        {(x.status?.msg || x.title) && <span className="block truncate text-xs text-muted-foreground">{x.status?.msg || x.title}</span>}
                      </span>
                      {a === t.address ? <Check className="size-4" /> : <StatusMark state={isShell(x) && xs === 'idle' ? null : xs === 'finished' ? 'finished' : stateOf(x)} />}
                    </DropdownMenuItem>
                  );
                })}
              </div>
            ))}
          </>
        ) : (
          <DropdownMenuLabel className="text-muted-foreground">This terminal is in no space.</DropdownMenuLabel>
        )}
        {others.length > 0 && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setSpacesOpen((v) => !v); }} className="gap-2 text-muted-foreground">
              {spacesOpen ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}Switch space
            </DropdownMenuItem>
            {spacesOpen && others.map((s) => {
              const ss = spaceState(s, live.terminals);
              return (
                <DropdownMenuItem key={s.id} onSelect={() => switchTo(spaceRoute(s, live.terminals, tab))} className={cn('gap-2 pl-4', s.parent && 'pl-8')}>
                  <SpaceAvatar space={s} />
                  <span className="min-w-0 flex-1 truncate">{s.name}</span>
                  {ss && <StatusMark state={ss} className="size-2" />}
                </DropdownMenuItem>
              );
            })}
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
