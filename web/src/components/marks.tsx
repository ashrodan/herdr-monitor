import { cn } from '@/lib/utils';
import type { AgentState, Space } from '@/types';
import { iconFor, useIcons } from '@/icons';

// Pulse = working, filled = done (unseen), hollow = idle, amber = blocked, red = error.
export function StatusMark({ state, className }: { state: AgentState | 'finished' | null; className?: string }) {
  const base = 'inline-block size-2.5 shrink-0 rounded-full';
  if (state === 'working') return <span className={cn(base, 'bg-working animate-pulse-soft', className)} title="working" />;
  if (state === 'blocked') return <span className={cn(base, 'bg-blocked ring-2 ring-blocked/25', className)} title="blocked" />;
  if (state === 'error') return <span className={cn(base, 'bg-error', className)} title="error" />;
  if (state === 'done') return <span className={cn(base, 'bg-done', className)} title="done" />;
  if (state === 'finished') return <span className={cn(base, 'bg-idle/40', className)} title="finished" />;
  return <span className={cn(base, 'border-[1.5px] border-idle', className)} title="idle" />;
}

// The program's icon from Herdr (claude, codex, make, shell, …), else a letter.
// Mono icons are drawn as a mask, so they take the text colour in both themes.
export function Glyph({ agent, reported = false, className }: { agent: string; reported?: boolean; className?: string }) {
  const idx = useIcons();
  const name = idx && iconFor(agent, reported, idx);
  const box = cn('inline-flex size-5 shrink-0 items-center justify-center', className);
  if (!name) {
    return <span className={cn(box, 'font-mono text-[12px] text-muted-foreground')} aria-hidden>{agent.slice(0, 1).toUpperCase()}</span>;
  }
  const url = `/api/icons/${name}.svg`;
  if (idx!.mono.has(name)) {
    return <span className={box} aria-hidden><span className="size-4 bg-muted-foreground" style={{ mask: `url(${url}) center / contain no-repeat`, WebkitMask: `url(${url}) center / contain no-repeat` }} /></span>;
  }
  return <span className={box} aria-hidden><img src={url} alt="" className="size-4" loading="lazy" /></span>;
}

export function SpaceAvatar({ space, className }: { space: Pick<Space, 'name' | 'color'>; className?: string }) {
  const letter = (space.name.replace(/^[~.]/, '') || space.name).slice(0, 1).toUpperCase() || '~';
  return (
    <span className={cn('inline-flex size-6 shrink-0 items-center justify-center rounded-md text-[12px] font-semibold text-[#131417]', className)}
      style={{ background: space.color }} aria-hidden>{letter}</span>
  );
}
