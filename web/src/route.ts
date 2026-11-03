// Hash routes, so the phone's back gesture works:
//   #                 home
//   #space/<id>       a space
//   #list/<kind>      the agents (or PRs) behind an Overview number
//   #<addr>[/<tab>]   an agent (old deep links like #t2s/commits still work)
import { useEffect, useState } from 'react';

export type AgentTab = 'output' | 'commits' | 'status';
export type ListKind = 'waiting' | 'working' | 'done' | 'prs';
export type Route =
  | { view: 'home' }
  | { view: 'space'; id: string }
  | { view: 'list'; kind: ListKind }
  | { view: 'agent'; addr: string; tab: AgentTab };

export function parseHash(hash: string): Route {
  const h = decodeURIComponent(hash.replace(/^#\/?/, ''));
  if (!h) return { view: 'home' };
  const ls = h.match(/^list\/(waiting|working|done|prs)$/);
  if (ls) return { view: 'list', kind: ls[1] as ListKind };
  const sp = h.match(/^space\/(.+)$/);
  if (sp) return { view: 'space', id: sp[1] };
  const ag = h.match(/^([^/]+)(?:\/(output|commits|status))?$/);
  if (ag) return { view: 'agent', addr: ag[1], tab: (ag[2] as AgentTab) || 'output' };
  return { view: 'home' };
}

export function hrefOf(r: Route): string {
  if (r.view === 'home') return '#';
  if (r.view === 'space') return `#space/${encodeURIComponent(r.id)}`;
  if (r.view === 'list') return `#list/${r.kind}`;
  return `#${r.addr}${r.tab === 'output' ? '' : '/' + r.tab}`;
}

// Push a new history entry (a new view), or replace it (a tab switch).
export function go(r: Route, { replace = false } = {}) {
  const url = hrefOf(r) === '#' ? location.pathname : hrefOf(r);
  if (replace) history.replaceState(history.state, '', url); else history.pushState({ inApp: true }, '', url);
  window.dispatchEvent(new Event('routechange'));
}

export function back() {
  // Opened from a deep link: there is no in-app page to go back to.
  if (history.state?.inApp) history.back(); else go({ view: 'home' }, { replace: true });
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
