// Live state from the server's SSE stream, as a React external store.
import { useSyncExternalStore } from 'react';
import type { Space, Terminal } from './types';

export interface Live { connected: boolean; terminals: Map<string, Terminal>; spaces: Space[] }

let live: Live = { connected: false, terminals: new Map(), spaces: [] };
const listeners = new Set<() => void>();
const set = (patch: Partial<Live>) => { live = { ...live, ...patch }; listeners.forEach((l) => l()); };

let es: EventSource | null = null;
function connect() {
  es = new EventSource('/api/events');
  es.addEventListener('snapshot', (e) => {
    const d = JSON.parse((e as MessageEvent).data);
    set({ connected: d.connected, terminals: new Map(d.terminals.map((t: Terminal) => [t.address, t])), spaces: d.spaces || [] });
  });
  es.addEventListener('terminal', (e) => {
    const t: Terminal = JSON.parse((e as MessageEvent).data);
    const terminals = new Map(live.terminals); terminals.set(t.address, t); set({ terminals });
  });
  es.addEventListener('removed', (e) => {
    const terminals = new Map(live.terminals); terminals.delete(JSON.parse((e as MessageEvent).data).address); set({ terminals });
  });
  es.addEventListener('layout', (e) => set({ spaces: JSON.parse((e as MessageEvent).data).spaces }));
  es.addEventListener('connection', (e) => set({ connected: JSON.parse((e as MessageEvent).data).connected }));
  es.onerror = () => set({ connected: false });
}

// A phone suspends the page: on return, reconnect so the snapshot is fresh.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && es?.readyState === EventSource.CLOSED) connect();
});

export function useLive(): Live {
  return useSyncExternalStore((l) => {
    listeners.add(l);
    if (!es) connect();
    return () => listeners.delete(l);
  }, () => live);
}
