// The agent's real terminal, read only: hc streams its bytes (colours, the
// status bar, box lines) and xterm.js draws them at the agent's own size,
// scaled to fit the pane.
import { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { WebglAddon } from '@xterm/addon-webgl';
import '@xterm/xterm/css/xterm.css';

const FONT = 'ui-monospace, "SF Mono", Menlo, Consolas, monospace';
const FONT_SIZE = 13;   // drawn at this size, then scaled to fit the pane
const MIN_SCALE = 0.5;  // below this, scroll instead of shrinking further
const MAX_SCALE = 1.15;

function decode(b64: string) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function LiveTerminal({ addr, cols, rows }: { addr: string; cols: number; rows: number }) {
  const host = useRef<HTMLDivElement>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const term = useRef<Terminal | null>(null);
  const [status, setStatus] = useState<'connecting' | 'live' | 'ended' | 'error'>('connecting');

  // Create the terminal and its stream once per agent.
  useEffect(() => {
    const t = new Terminal({
      cols, rows, disableStdin: true, cursorBlink: false, cursorInactiveStyle: 'none', scrollback: 2000,
      fontFamily: FONT, fontSize: FONT_SIZE, lineHeight: 1.1, customGlyphs: true, allowTransparency: false,
      theme: { background: '#0d0e10', foreground: '#d9dde4', cursor: '#0d0e10' },
    });
    t.open(host.current!);
    try {
      const gl = new WebglAddon();
      gl.onContextLoss(() => gl.dispose()); // falls back to the DOM renderer
      t.loadAddon(gl);
    } catch { /* no WebGL: the DOM renderer still works, without custom glyphs */ }
    term.current = t;

    const es = new EventSource(`/api/terminals/${addr}/attach`);
    es.onmessage = (e) => { t.write(decode(e.data)); setStatus('live'); };
    es.addEventListener('size', (e) => {
      const d = JSON.parse((e as MessageEvent).data);
      if (d.cols && d.rows && (d.cols !== t.cols || d.rows !== t.rows)) t.resize(d.cols, d.rows);
    });
    es.addEventListener('end', () => { setStatus('ended'); es.close(); });
    es.onerror = () => setStatus((s) => (s === 'live' ? 'connecting' : s));
    return () => { es.close(); t.dispose(); term.current = null; };
  }, [addr]); // eslint-disable-line react-hooks/exhaustive-deps

  // Follow the agent's size, and fit the whole screen in the pane (width and
  // height: the status bar is at the bottom) with a CSS scale. The layout
  // size xterm reports is not changed by the transform, so this is exact.
  const [scale, setScale] = useState(1);
  const [natural, setNatural] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const t = term.current; const el = wrap.current;
    if (!t || !el) return;
    if (t.cols !== cols || t.rows !== rows) t.resize(cols, rows);
    const fit = () => {
      const screen = host.current?.querySelector('.xterm-screen') as HTMLElement | null;
      if (!screen?.offsetWidth || !screen.offsetHeight) return;
      const w = screen.offsetWidth, h = screen.offsetHeight;
      const s = Math.min(MAX_SCALE, (el.clientWidth - 16) / w, el.clientHeight > 120 ? (el.clientHeight - 16) / h : Infinity);
      setNatural({ w, h });
      setScale(Math.max(MIN_SCALE, Math.floor(s * 1000) / 1000));
    };
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    const screen = host.current?.querySelector('.xterm-screen');
    if (screen) ro.observe(screen);
    fit();
    return () => ro.disconnect();
  }, [cols, rows]);

  return (
    <div ref={wrap} className="relative h-full overflow-auto bg-code p-2">
      {/* Sized to the scaled terminal, so scrolling (when it is clamped) is right. */}
      <div style={natural.w ? { width: natural.w * scale, height: natural.h * scale } : undefined}>
        <div ref={host} className="w-max origin-top-left" style={{ transform: `scale(${scale})` }} />
      </div>
      {status !== 'live' && (
        <p className="absolute top-10 right-3 rounded bg-black/60 px-2 py-0.5 text-[11px] text-code-foreground">
          {status === 'connecting' ? 'Connecting…' : status === 'ended' ? 'Stream ended' : 'Not available'}
        </p>
      )}
    </div>
  );
}
