import { useEffect, useRef, useState } from 'react';
import { ArrowUp, ChevronLeft, GitCommitHorizontal, GitPullRequest, Square, Terminal as TerminalIcon } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { getJson, post } from '@/api';
import type { Live } from '@/live';
import { displayName, markSeen, rawStateOf, shortPath } from '@/model';
import { back, go, type AgentTab } from '@/route';
import type { PrStatus } from '@/types';
import { CommitsPane } from './CommitsPane';
import { StatusPane } from './StatusPane';
import { Glyph } from './marks';

const PILL: Record<string, string> = {
  working: 'text-working border-working/40', blocked: 'text-blocked border-blocked/40', error: 'text-error border-error/40',
  done: 'text-done border-done/40', idle: 'text-muted-foreground', finished: 'text-muted-foreground',
};

export function AgentView({ live, addr, tab }: { live: Live; addr: string; tab: AgentTab }) {
  const t = live.terminals.get(addr);
  const [pr, setPr] = useState<PrStatus | null>(null);

  // Opening an agent marks its "done" as seen, like Herdr. Again when it changes.
  useEffect(() => { if (t) markSeen(addr); }, [addr, t?.changed_ms]); // eslint-disable-line react-hooks/exhaustive-deps

  // The Status tab's dot needs PR data even while another tab shows.
  useEffect(() => {
    setPr(null);
    let stop = false;
    getJson<PrStatus>(`/api/terminals/${addr}/status`).then((d) => { if (!stop) setPr(d); }).catch(() => {});
    return () => { stop = true; };
  }, [addr]);

  if (!t) {
    return (
      <div className="flex h-full flex-col">
        <Header onBack={back}><h2 className="text-base font-semibold">{addr}</h2></Header>
        <p className="p-6 text-sm text-muted-foreground">{live.connected ? 'This terminal is gone.' : 'Connecting…'}</p>
      </div>
    );
  }
  const st = rawStateOf(t);
  const setTab = (v: string) => go({ view: 'agent', addr, tab: v as AgentTab }, { replace: true });
  const dot = pr?.counts?.fail ? 'bg-error' : pr?.counts?.pending ? 'bg-blocked' : null;

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* One header: who, what it does, and the view switch. */}
      <Header onBack={back}>
        <Glyph agent={t.agent} reported={t.reported} />
        <div className="min-w-0 flex-1" title={`${t.address}${t.where ? ` · ${t.where}` : ''} · ${t.cwd}`}>
          <div className="flex items-center gap-2">
            <h2 className="truncate text-[15px] font-semibold leading-tight">{displayName(t, live.spaces)}</h2>
            <Badge variant="outline" className={cn('shrink-0 px-1.5 py-0 text-[11px] font-normal', PILL[st])}>{st}{t.status?.kind ? ` · ${t.status.kind}` : ''}</Badge>
          </div>
          <p className="truncate text-xs text-muted-foreground">
            {t.status?.msg || t.title || <span className="font-mono">{t.where || t.address} · {shortPath(t.cwd)}</span>}
          </p>
        </div>
        <Tabs value={tab} onValueChange={setTab} className="shrink-0">
          <TabsList className="h-8">
            <TabsTrigger value="output" className="px-2" aria-label="Output" title="Output"><TerminalIcon /><span className="hidden sm:inline">Output</span></TabsTrigger>
            <TabsTrigger value="commits" className="px-2" aria-label="Commits" title="Commits"><GitCommitHorizontal /><span className="hidden sm:inline">Commits</span></TabsTrigger>
            <TabsTrigger value="status" className="relative px-2" aria-label="PR status" title="PR status">
              <GitPullRequest /><span className="hidden sm:inline">Status</span>
              {dot && <span className={cn('absolute top-1 right-1 size-1.5 rounded-full sm:static sm:ml-0.5', dot)} />}
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </Header>
      {t.status?.progress != null && <div className="h-0.5 bg-border"><div className="h-full bg-working" style={{ width: `${Math.min(100, t.status.progress)}%` }} /></div>}

      <div className="min-h-0 flex-1">
        {tab === 'output' && <OutputPane addr={addr} working={st === 'working'} stateKey={st} />}
        {tab === 'commits' && <CommitsPane addr={addr} />}
        {tab === 'status' && <StatusPane addr={addr} data={pr} onData={setPr} />}
      </div>
      <Composer addr={addr} blocked={st === 'blocked'} finished={t.state === 'finished'} kind={t.status?.kind} exitCode={t.exit_code} />
    </div>
  );
}

function Header({ onBack, children }: { onBack: () => void; children: React.ReactNode }) {
  return (
    <header className="flex items-center gap-2 border-b py-1.5 pr-2 pl-1 md:px-4">
      <Button variant="ghost" size="icon" className="-mr-1 lg:hidden" onClick={onBack} aria-label="Back"><ChevronLeft /></Button>
      {children}
    </header>
  );
}

// The agent's screen. It refreshes while the agent works. History on request:
// it makes hc scroll a full-screen agent, which takes seconds.
function OutputPane({ addr, working, stateKey }: { addr: string; working: boolean; stateKey: string }) {
  const [text, setText] = useState<string | null>(null);
  const [history, setHistory] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const busy = useRef(false);

  const load = async (withHistory = history) => {
    if (busy.current) return;
    busy.current = true;
    const el = ref.current;
    const atBottom = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    try {
      const r = await getJson<{ text?: string; error?: string }>(`/api/terminals/${addr}/read?lines=${withHistory ? 300 : 0}`);
      setText(r.text ?? r.error ?? '');
    } catch (err) { setText(`Could not read: ${(err as Error).message}`); }
    finally { busy.current = false; setLoadingHistory(false); }
    if (atBottom && !withHistory) requestAnimationFrame(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight; });
  };

  useEffect(() => { setText(null); setHistory(false); load(false); }, [addr]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (text !== null) load(); }, [stateKey]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!working || history) return;
    const id = setInterval(() => load(false), 2500);
    return () => clearInterval(id);
  }, [working, history, addr]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div ref={ref} className="h-full overflow-auto bg-code text-code-foreground">
      {!history && (
        <Button variant="secondary" size="sm" className="m-3 mb-0" disabled={loadingHistory}
          onClick={() => { setHistory(true); setLoadingHistory(true); load(true); }}>
          {loadingHistory ? 'Loading history…' : 'Load history'}
        </Button>
      )}
      <pre className="m-0 whitespace-pre-wrap px-4 py-3 font-mono text-[12px] leading-[1.4] [overflow-wrap:anywhere]">{text ?? 'Loading…'}</pre>
    </div>
  );
}

// The reply box, shaped like a phone chat composer:
// - 16 px text, so iOS does not zoom the page when the box gets focus;
// - grows with its text up to about 6 lines, then scrolls;
// - Send keeps the keyboard open (the button does not take focus);
// - on a touch screen Enter adds a new line, and the button sends;
// - Stop (interrupt) sits apart, on the left, so a thumb does not hit it by mistake.
function Composer({ addr, blocked, finished, kind, exitCode }: { addr: string; blocked: boolean; finished: boolean; kind?: string; exitCode: number | null }) {
  const [value, setValue] = useState('');
  const [note, setNote] = useState<{ text: string; tone?: 'err' } | null>(null);
  const [sending, setSending] = useState(false);
  const box = useRef<HTMLTextAreaElement>(null);
  const coarse = typeof matchMedia !== 'undefined' && matchMedia('(pointer: coarse)').matches;
  useEffect(() => { setValue(''); setNote(null); }, [addr]);

  // Grow with the text (field-sizing is not in every Safari yet).
  useEffect(() => {
    const el = box.current; if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [value]);

  // A note such as "Sent." goes away after a few seconds.
  useEffect(() => {
    if (!note || note.tone === 'err') return;
    const id = setTimeout(() => setNote(null), 2500);
    return () => clearTimeout(id);
  }, [note]);

  const canSend = !!value.trim() && !blocked && !finished && !sending;
  const send = async () => {
    if (!canSend) return;
    setSending(true);
    const r = await post(`/api/terminals/${addr}/send`, { text: value });
    setSending(false);
    if (r.ok) { setValue(''); setNote({ text: 'Sent.' }); } else setNote({ text: `Not sent: ${r.body.error}`, tone: 'err' });
  };
  const interrupt = async () => {
    const r = await post(`/api/terminals/${addr}/interrupt`);
    setNote(r.ok ? { text: 'Stop key sent.' } : { text: `Interrupt failed: ${r.body.error}`, tone: 'err' });
  };
  // Keep focus (and so the keyboard) in the box when a button is tapped.
  const keepFocus = (e: React.PointerEvent | React.MouseEvent) => e.preventDefault();

  const info = blocked ? `Waiting at a ${kind || 'dialog'}. Answer it in Herdr: this page does not answer approvals.`
    : finished ? `Finished${exitCode != null ? ` with exit code ${exitCode}` : ''}.` : null;

  return (
    <form className="safe-bottom border-t bg-card/95 px-2 pt-2 backdrop-blur md:px-4" onSubmit={(e) => { e.preventDefault(); send(); }}>
      {(info || note) && (
        <p role="status" className={cn('px-2 pb-1.5 text-xs', info && blocked ? 'text-blocked' : note?.tone === 'err' ? 'text-error' : 'text-muted-foreground')}>{info || note?.text}</p>
      )}
      <div className="flex items-end gap-1.5">
        <Button type="button" variant="ghost" size="icon" className="size-10 shrink-0 rounded-full text-muted-foreground"
          disabled={finished} onPointerDown={keepFocus} onMouseDown={keepFocus} onClick={interrupt} aria-label="Interrupt (stop key)" title="Interrupt (stop key)">
          <Square className="size-3.5 fill-current" />
        </Button>
        <div className={cn('flex min-w-0 flex-1 items-end rounded-[22px] border bg-background pl-4 pr-1 transition-colors focus-within:border-ring',
          (blocked || finished) && 'opacity-60')}>
          <textarea ref={box} value={value} rows={1} disabled={finished}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => { if (!coarse && e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } }}
            placeholder={blocked ? 'Agent is waiting at a dialog' : 'Reply to the agent'}
            title={coarse ? undefined : 'Enter sends, Shift+Enter adds a new line'}
            aria-label="Reply to the agent"
            enterKeyHint={coarse ? 'enter' : 'send'} autoCapitalize="sentences" autoCorrect="on" spellCheck autoComplete="off"
            className="max-h-40 min-h-[40px] w-full resize-none bg-transparent py-[9px] text-[16px] leading-[22px] outline-none placeholder:text-muted-foreground md:text-[14px]" />
          <Button type="submit" size="icon" className={cn('my-1 ml-1 size-8 shrink-0 rounded-full transition-opacity', !canSend && 'opacity-30')}
            disabled={!canSend} onPointerDown={keepFocus} onMouseDown={keepFocus} aria-label="Send" title="Send">
            <ArrowUp className="size-4" strokeWidth={2.5} />
          </Button>
        </div>
      </div>
    </form>
  );
}
