// Hash routes, so the phone's back gesture works:
//   #                 home
//   #space/<id>       a space
//   #list/<kind>      the agents (or PRs) behind an Overview number
//   #<addr>[/<tab>]   an agent (old deep links like #t2s/commits still work)
import { useEffect, useState } from 'react';
import { bestTerminal } from './model';
import type { Space, Terminal } from './types';

export type AgentTab = 'output' | 'commits' | 'status';
export type ListKind = 'waiting' | 'working' | 'done' | 'prs';
export type Route =
  | { view: 'home' }
  | { view: 'space'; id: string }
  | { view: 'list'; kind: ListKind }
  | { view: 'agent'; addr: string; tab: AgentTab };

// A remote agent's address has a slash (`dai/t1`): hrefOf encodes it, and the
// parse below splits the raw hash first, then decodes each part. A bare
// `machine/t1` (typed by hand) still opens the agent.
const dec = (s: string) => { try { return decodeURIComponent(s); } catch { return s; } };

export function parseHash(hash: string): Route {
  const h = hash.replace(/^#\/?/, '');
  if (!h) return { view: 'home' };
  const ls = h.match(/^list\/(waiting|working|done|prs)$/);
  if (ls) return { view: 'list', kind: ls[1] as ListKind };
  const sp = h.match(/^space\/(.+)$/);
  if (sp) return { view: 'space', id: dec(sp[1]) };
  const ag = h.match(/^([^/]+(?:\/(?!(?:output|commits|status)$)[^/]+)?)(?:\/(output|commits|status))?$/);
  if (ag) return { view: 'agent', addr: dec(ag[1]), tab: (ag[2] as AgentTab) || 'output' };
  return { view: 'home' };
}

export function hrefOf(r: Route): string {
  if (r.view === 'home') return '#';
  if (r.view === 'space') return `#space/${encodeURIComponent(r.id)}`;
  if (r.view === 'list') return `#list/${r.kind}`;
  return `#${encodeURIComponent(r.addr)}${r.tab === 'output' ? '' : '/' + r.tab}`;
}

// Navigation has two levels, so Back is always clear:
//   the lists (home: Spaces, Agents, Overview; or an Overview list)
//   → a detail (an agent, a space).
// Opening a detail pushes one history entry that remembers its list ("up").
// Switching pane, tab or space inside a detail replaces that entry, so Back
// (the button or the phone gesture) returns to the list, not the last pane.
export function go(r: Route, { replace = false, up }: { replace?: boolean; up?: Route } = {}) {
  const url = hrefOf(r) === '#' ? location.pathname : hrefOf(r);
  if (replace) history.replaceState(history.state, '', url);
  else history.pushState({ inApp: true, up: hrefOf(up ?? { view: 'home' }) }, '', url);
  window.dispatchEvent(new Event('routechange'));
}

// Open a detail from a list, or switch within a detail.
export const open = (r: Route, from: Route = { view: 'home' }) => go(r, { up: from });
export const switchTo = (r: Route) => go(r, { replace: true });

// The list Back returns to.
// A space opens on its most urgent pane; with no terminals, on the space page.
export function spaceRoute(s: Space, terminals: Map<string, Terminal>, tab: AgentTab = 'output'): Route {
  const a = bestTerminal(s, terminals);
  return a ? { view: 'agent', addr: a, tab } : { view: 'space', id: s.id };
}

export const upRoute = (): Route => parseHash(history.state?.up ?? '#');

const SECTION: Record<string, string> = { spaces: 'Spaces', agents: 'Agents', overview: 'Overview' };
const LIST: Record<ListKind, string> = { waiting: 'Waiting', working: 'Working', done: 'Done', prs: 'PRs' };
export function upLabel(): string {
  const u = upRoute();
  if (u.view === 'list') return LIST[u.kind];
  if (u.view === 'home') return SECTION[localStorage.getItem('herdr-monitor.section') || 'spaces'] || 'Back';
  return 'Back';
}

export function back() {
  // Opened from a deep link: there is no in-app list to go back to.
  if (history.state?.inApp) history.back(); else go(upRoute(), { replace: true });
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseHash(location.hash));
  useEffect(() => {
    const on = () => setRoute(parseHash(location.hash));
    window.addEventListener('popstate', on);
    window.addEventListener('routechange', on);
    window.addEventListener('hashchange', on);
    return () => { window.removeEventListener('popstate', on); window.removeEventListener('routechange', on); window.removeEventListener('hashchange', on); };
  }, []);
  return route;
}
