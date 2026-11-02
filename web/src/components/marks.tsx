import { cn } from '@/lib/utils';
import type { AgentState, Space } from '@/types';

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

const GLYPHS: Record<string, string> = { 'claude-code': '✳', claude: '✳', codex: '◎', cursor: '▲', gemini: '✦', opencode: '◇', omp: '◆' };
export function Glyph({ agent, className }: { agent: string; className?: string }) {
  const g = GLYPHS[agent] || (/^(zsh|bash|fish|sh|nu|pwsh)$/.test(agent) ? '$' : agent.slice(0, 1).toUpperCase());
  return <span className={cn('inline-flex size-5 shrink-0 items-center justify-center font-mono text-[13px] text-muted-foreground', className)} aria-hidden>{g}</span>;
}

export function SpaceAvatar({ space, className }: { space: Pick<Space, 'name' | 'color'>; className?: string }) {
  const letter = (space.name.replace(/^[~.]/, '') || space.name).slice(0, 1).toUpperCase() || '~';
  return (
    <span className={cn('inline-flex size-6 shrink-0 items-center justify-center rounded-md text-[12px] font-semibold text-[#131417]', className)}
      style={{ background: space.color }} aria-hidden>{letter}</span>
  );
}
