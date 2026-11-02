// Claude Code's own conversation for an agent, read from its transcript:
// your prompts as bubbles, Claude's replies as Markdown, tool calls as
// compact rows that open to show their result.
import { useEffect, useRef, useState } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AlertCircle, Brain, ChevronRight, Wrench } from 'lucide-react';
import { cn } from '@/lib/utils';
import { getJson } from '@/api';

interface ToolPart { kind: 'tool'; id: string; name: string; summary: string; result: { text: string; error: boolean; truncated: boolean } | null }
interface TextPart { kind: 'text' | 'thinking'; text: string }
type Part = TextPart | ToolPart;
interface Message { id: string; role: 'user' | 'assistant' | 'system'; at: string | null; parts: Part[] }
interface Chat { available: boolean; reason?: string; truncated?: boolean; messages?: Message[]; version?: string; unchanged?: boolean; session?: { name: string | null } }

export function ChatPane({ addr, working }: { addr: string; working: boolean }) {
  const [chat, setChat] = useState<Chat | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const version = useRef<string | undefined>(undefined);
  const scroller = useRef<HTMLDivElement>(null);
  const atBottom = useRef(true);

  useEffect(() => {
    let stop = false;
    version.current = undefined;
    setChat(null); setErr(null); atBottom.current = true;
    const load = async () => {
      try {
        const d = await getJson<Chat>(`/api/terminals/${addr}/chat${version.current ? `?v=${encodeURIComponent(version.current)}` : ''}`);
        if (stop || d.unchanged) return;
        version.current = d.version;
        setChat(d); setErr(null);
      } catch (e) { if (!stop) setErr((e as Error).message); }
    };
    load();
    // Fast while the agent works; slower when it is idle.
    const id = setInterval(load, working ? 2000 : 8000);
    return () => { stop = true; clearInterval(id); };
  }, [addr, working]);

  // Stay at the bottom when new messages come, unless the reader scrolled up.
  useEffect(() => {
    const el = scroller.current;
    if (el && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [chat]);

  if (err && !chat) return <p className="p-4 text-sm text-error">Could not read the transcript: {err}</p>;
  if (!chat) return <p className="p-4 text-sm text-muted-foreground">Loading the conversation…</p>;
  if (!chat.available) return <p className="p-4 text-sm text-muted-foreground">{chat.reason} Use Live or Text.</p>;

  return (
    <div ref={scroller} className="h-full overflow-y-auto overscroll-contain"
      onScroll={(e) => { const el = e.currentTarget; atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60; }}>
      <div className="mx-auto flex max-w-3xl flex-col gap-3 px-3 pt-11 pb-4 md:px-6">
        {chat.truncated && <p className="text-center text-xs text-muted-foreground">Older messages are not shown (long transcript).</p>}
        {!chat.messages?.length && <p className="text-sm text-muted-foreground">No messages yet.</p>}
        {chat.messages?.map((m) => <MessageView key={m.id} m={m} />)}
        {working && <p className="flex items-center gap-2 text-xs text-working"><span className="size-1.5 animate-pulse-soft rounded-full bg-working" />Working…</p>}
      </div>
    </div>
  );
}

function MessageView({ m }: { m: Message }) {
  if (m.role === 'user') {
    return (
      <div className="ml-auto max-w-[85%] rounded-2xl rounded-br-md bg-secondary px-3.5 py-2 text-[15px] whitespace-pre-wrap [overflow-wrap:anywhere] md:text-sm">
        {m.parts.map((p) => (p.kind === 'text' ? p.text : '')).join('\n')}
      </div>
    );
  }
  if (m.role === 'system') {
    return <p className="text-center text-xs text-muted-foreground [overflow-wrap:anywhere]">{m.parts.map((p) => (p.kind === 'text' ? p.text : '')).join(' ')}</p>;
  }
  // Assistant: text blocks in order; runs of tool calls fold into one group.
  const blocks: (TextPart | ToolPart[])[] = [];
  for (const p of m.parts) {
    if (p.kind === 'tool') {
      const prev = blocks[blocks.length - 1];
      if (Array.isArray(prev)) prev.push(p); else blocks.push([p]);
    } else blocks.push(p);
  }
  return (
    <div className="flex flex-col gap-2">
      {blocks.map((b, i) => Array.isArray(b) ? <ToolGroup key={i} tools={b} />
        : b.kind === 'thinking' ? <Thinking key={i} text={b.text} />
        : <Prose key={i} text={b.text} />)}
    </div>
  );
}

function Prose({ text }: { text: string }) {
  return (
    <div className="prose-chat text-[15px] leading-relaxed [overflow-wrap:anywhere] md:text-sm">
      <Markdown remarkPlugins={[remarkGfm]} components={{ a: ({ node: _n, ...p }) => <a {...p} target="_blank" rel="noopener noreferrer" /> }}>{text}</Markdown>
    </div>
  );
}

function Thinking({ text }: { text: string }) {
  return (
    <details className="group text-xs text-muted-foreground">
      <summary className="flex cursor-pointer list-none items-center gap-1.5 select-none">
        <ChevronRight className="size-3 transition-transform group-open:rotate-90" /><Brain className="size-3" />Thinking
      </summary>
      <p className="mt-1 ml-5 whitespace-pre-wrap italic">{text}</p>
    </details>
  );
}

function ToolGroup({ tools }: { tools: ToolPart[] }) {
  const failed = tools.filter((t) => t.result?.error).length;
  const names = [...new Set(tools.map((t) => t.name))].slice(0, 3).join(', ');
  return (
    <details className="group rounded-lg border bg-card/60 text-xs">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-2.5 py-1.5 text-muted-foreground select-none">
        <ChevronRight className="size-3 shrink-0 transition-transform group-open:rotate-90" />
        <Wrench className="size-3 shrink-0" />
        <span className="min-w-0 truncate">
          {tools.length === 1 ? <><b className="font-medium text-foreground">{tools[0].name}</b> {tools[0].summary}</> : <>{tools.length} tool calls · {names}</>}
        </span>
        {failed > 0 && <span className="ml-auto flex shrink-0 items-center gap-1 text-error"><AlertCircle className="size-3" />{failed}</span>}
      </summary>
      <ul className="border-t">
        {tools.map((t) => <ToolRow key={t.id} t={t} />)}
      </ul>
    </details>
  );
}

function ToolRow({ t }: { t: ToolPart }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="border-b last:border-b-0">
      <button className="flex w-full items-baseline gap-2 px-2.5 py-1.5 text-left hover:bg-accent/50" onClick={() => setOpen(!open)}>
        <b className={cn('shrink-0 font-medium', t.result?.error ? 'text-error' : 'text-foreground')}>{t.name}</b>
        <span className="min-w-0 truncate font-mono text-[11px] text-muted-foreground">{t.summary}</span>
        {!t.result && <span className="ml-auto shrink-0 text-blocked">running</span>}
      </button>
      {open && t.result && (
        <pre className="max-h-72 overflow-auto bg-code px-2.5 py-2 font-mono text-[11px] leading-snug whitespace-pre-wrap text-code-foreground [overflow-wrap:anywhere]">
          {t.result.text || '(no output)'}{t.result.truncated ? '\n… (cut)' : ''}
        </pre>
      )}
    </li>
  );
}
