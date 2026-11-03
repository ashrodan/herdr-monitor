// Herdr's program icons, served by /api/icons from the local Herdr install.
import { useSyncExternalStore } from 'react';

interface Index { names: Set<string>; mono: Set<string> }
let index: Index | null = null;
const listeners = new Set<() => void>();
let started = false;
function load() {
  if (started) return;
  started = true;
  fetch('/api/icons').then((r) => r.json()).then((d) => {
    index = { names: new Set(d.names), mono: new Set(d.mono) };
    listeners.forEach((l) => l());
  }).catch(() => {});
}

export function useIcons(): Index | null {
  return useSyncExternalStore((l) => { listeners.add(l); load(); return () => listeners.delete(l); }, () => index);
}

const ALIAS: Record<string, string> = { 'claude-code': 'claude', 'cursor-agent': 'cursor', 'gemini-cli': 'gemini', 'github-copilot': 'copilot', nvim: 'nvim', vi: 'vim' };
const SHELLS = new Set(['zsh', 'bash', 'fish', 'sh', 'nu', 'pwsh']);

// The icon for a program name: itself, an alias, a shell, an agent, or a program.
export function iconFor(agent: string, reported: boolean, idx: Index): string | null {
  const a = agent.toLowerCase();
  for (const n of [ALIAS[a], a, a.replace(/-cli$/, '')]) if (n && idx.names.has(n)) return n;
  if (SHELLS.has(a) && idx.names.has('shell')) return 'shell';
  if (reported && idx.names.has('agent')) return 'agent';
  return idx.names.has('program') ? 'program' : null;
}
