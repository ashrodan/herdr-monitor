import { useEffect, useState } from 'react';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { AgentView } from '@/components/AgentView';
import { AgentsList, SpacesList } from '@/components/Sidebar';
import { SpaceView } from '@/components/SpaceView';
import { PushBell } from '@/components/PushBell';
import { ListView, Overview } from '@/components/Overview';
import { useLive, type Live } from '@/live';
import { useRoute } from '@/route';

// Keep --app-h equal to the visible height, so the composer stays above the
// phone keyboard and the home bar.
function useViewportHeight() {
  useEffect(() => {
    const vv = window.visualViewport;
    const set = () => {
      document.documentElement.style.setProperty('--app-h', `${Math.round(vv?.height ?? window.innerHeight)}px`);
      if (vv && vv.offsetTop > 0) window.scrollTo(0, 0); // iOS scrolls the page under the keyboard
    };
    set();
    vv?.addEventListener('resize', set);
    window.addEventListener('resize', set);
    return () => { vv?.removeEventListener('resize', set); window.removeEventListener('resize', set); };
  }, []);
}

function useNow(ms = 30000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(id); }, [ms]);
  return now;
}

export default function App() {
  useViewportHeight();
  const live = useLive();
  const route = useRoute();
  const now = useNow();
  const selAgent = route.view === 'agent' ? route.addr : undefined;
  const selSpace = route.view === 'space' ? route.id : undefined;

  const main = route.view === 'agent' ? <AgentView key={route.addr} live={live} addr={route.addr} tab={route.tab} />
    : route.view === 'space' ? <SpaceView live={live} id={route.id} now={now} />
    : route.view === 'list' ? <ListView live={live} kind={route.kind} now={now} />
    : null;

  return (
    <div className="app-shell flex overflow-hidden">
      {/* Home: full screen on a phone, a fixed column on a desktop. */}
      <aside className={cn('min-h-0 flex-col border-r bg-sidebar lg:flex lg:w-[300px] lg:shrink-0', main ? 'hidden' : 'flex w-full')}>
        <Home live={live} now={now} selAgent={selAgent} selSpace={selSpace} />
      </aside>
      <main className={cn('min-w-0 flex-1 flex-col', main ? 'flex' : 'hidden lg:flex')}>
        {main ?? <div className="min-h-0 flex-1 overflow-y-auto"><Overview live={live} now={now} /></div>}
      </main>
    </div>
  );
}

function Home({ live, now, selAgent, selSpace }: { live: Live; now: number; selAgent?: string; selSpace?: string }) {
  const [section, setSection] = useState(() => localStorage.getItem('herdr-monitor.section') || 'agents');
  const pick = (v: string) => { setSection(v); try { localStorage.setItem('herdr-monitor.section', v); } catch { /* private mode */ } };
  return (
    <>
      <header className="flex items-center gap-2 px-4 pt-3 pb-2">
        <h1 className="text-[15px] font-semibold">Herdr</h1>
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <span className={cn('size-1.5 rounded-full', live.connected ? 'bg-done' : 'bg-error')} />{live.connected ? 'live' : 'offline'}
        </span>
        <span className="ml-auto"><PushBell /></span>
      </header>
      {/* Phone: a segmented control. Desktop: both sections, stacked. */}
      <div className="px-3 pb-2 lg:hidden">
        <Tabs value={section} onValueChange={pick}>
          <TabsList className="w-full"><TabsTrigger value="agents">Agents</TabsTrigger><TabsTrigger value="spaces">Spaces</TabsTrigger><TabsTrigger value="overview">Overview</TabsTrigger></TabsList>
        </Tabs>
      </div>
      <div className="safe-bottom min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className={cn(section === 'agents' ? 'block' : 'hidden', 'lg:block')}>
          <h2 className="hidden px-4 pt-2 text-xs font-semibold text-muted-foreground lg:block">Agents</h2>
          <AgentsList live={live} selected={selAgent} now={now} />
        </div>
        {/* Overview: a phone segment; on a desktop it fills the main area. */}
        {section === 'overview' && <div className="lg:hidden"><Overview live={live} now={now} /></div>}
        <div className={cn(section === 'spaces' ? 'block' : 'hidden', 'lg:block')}>
          <h2 className="hidden border-t px-4 pt-3 pb-1 text-xs font-semibold text-muted-foreground lg:block">Spaces</h2>
          <SpacesList live={live} selected={selSpace} />
        </div>
      </div>
    </>
  );
}
