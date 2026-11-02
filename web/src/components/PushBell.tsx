// The bell: install the app, and turn Web Push on, test it, or turn it off.
import { useEffect, useState } from 'react';
import { Bell, BellOff, BellRing, Download, Share } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { getJson, post } from '@/api';
import { browser, isIos, promptInstall, useInstall } from '@/install';

type State = 'loading' | 'unsupported' | 'insecure' | 'install' | 'denied' | 'off' | 'on';

function keyBytes(base64url: string) {
  const b64 = (base64url + '='.repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

async function currentSub() {
  const reg = await navigator.serviceWorker.ready;
  return reg.pushManager.getSubscription();
}

const STEPS: Record<string, string[]> = {
  'ios-safari': ['Tap the Share button (the square with an arrow) in the toolbar.', 'Tap “Add to Home Screen”, then “Add”.', 'Open Herdr from the Home Screen.'],
  'ios-chrome': ['Tap the Share button in the address bar (top right).', 'Tap “Add to Home Screen”, then “Add”.', 'Open Herdr from the Home Screen.'],
  'ios-other': ['Open the browser’s Share menu.', 'Tap “Add to Home Screen”, then “Add”.', 'Open Herdr from the Home Screen.'],
  android: ['Open the ⋮ menu (top right).', 'Tap “Install app” or “Add to Home screen”.'],
  desktop: ['Click the install icon at the right of the address bar,', 'or open the ⋮ menu → “Cast, save and share” → “Install page as app”.'],
};

export function PushBell() {
  const [state, setState] = useState<State>('loading');
  const [note, setNote] = useState<string | null>(null);
  const install = useInstall();

  useEffect(() => {
    (async () => {
      if (!window.isSecureContext) return setState('insecure');
      if (isIos() && !install.installed) return setState('install'); // iOS pushes only to a home-screen app
      if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return setState('unsupported');
      if (Notification.permission === 'denied') return setState('denied');
      setState((await currentSub()) ? 'on' : 'off');
    })().catch(() => setState('unsupported'));
  }, [install.installed]);

  useEffect(() => { if (!note) return; const id = setTimeout(() => setNote(null), 4000); return () => clearTimeout(id); }, [note]);

  const subscribe = async () => {
    try {
      const perm = await Notification.requestPermission();
      if (perm !== 'granted') { setState(perm === 'denied' ? 'denied' : 'off'); return; }
      const { publicKey } = await getJson<{ publicKey: string }>('/api/push/key');
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
      const r = await post('/api/push/subscribe', { subscription: sub.toJSON() });
      if (!r.ok) throw new Error(r.body.error || 'subscribe failed');
      setState('on'); setNote('Notifications on.');
    } catch (err) { setNote(`Could not turn on: ${(err as Error).message}`); }
  };
  const unsubscribe = async () => {
    const sub = await currentSub();
    if (sub) { await post('/api/push/unsubscribe', { endpoint: sub.endpoint }); await sub.unsubscribe(); }
    setState('off'); setNote('Notifications off.');
  };
  const test = async () => {
    const r = await post<{ sent: number; failed: unknown[] }>('/api/push/test');
    setNote(r.ok ? `Test sent to ${r.body.sent} device${r.body.sent === 1 ? '' : 's'}.` : `Test failed: ${r.body.error}`);
  };
  const doInstall = async () => {
    const ok = await promptInstall();
    setNote(ok ? 'Installed. Open Herdr from your apps.' : 'Not installed.');
  };

  const hint: Partial<Record<State, string>> = {
    install: 'On iPhone, notifications work only in the installed app. Install it first (above), then open it from the Home Screen and tap the bell again.',
    insecure: 'Notifications need https. Open https://…ts.net:7400.',
    unsupported: 'This browser does not support Web Push.',
    denied: 'Notifications are blocked for this site. Allow them in the browser or iOS settings.',
  };
  const showInstall = !install.installed;
  const nudge = showInstall || state === 'off'; // a dot on the bell: something to do

  if (state === 'loading') return null;
  return (
    <div className="relative">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" className="relative size-8" aria-label="Install and notifications">
            {state === 'on' ? <BellRing className="text-done" /> : state === 'off' || state === 'install' ? <Bell /> : <BellOff className="text-muted-foreground" />}
            {nudge && <span className="absolute top-1.5 right-1.5 size-1.5 rounded-full bg-working" />}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-80 max-w-[calc(100vw-1rem)]">
          {showInstall && (
            <>
              <DropdownMenuLabel className="flex items-center gap-2"><Download className="size-4" />Install Herdr as an app</DropdownMenuLabel>
              {install.canPrompt ? (
                <DropdownMenuItem onSelect={doInstall} className="font-medium">Install app</DropdownMenuItem>
              ) : (
                <ol className="list-decimal space-y-1 px-2 pb-2 pl-7 text-sm">
                  {STEPS[browser()].map((s, i) => <li key={i}>{s}</li>)}
                </ol>
              )}
              {!install.canPrompt && browser().startsWith('ios') && (
                <p className="flex items-center gap-1.5 px-2 pb-2 text-xs text-muted-foreground"><Share className="size-3.5" />iPhone browsers have no install button: use Share.</p>
              )}
              <DropdownMenuSeparator />
            </>
          )}
          <DropdownMenuLabel>Notifications</DropdownMenuLabel>
          {state === 'on' && (
            <>
              <p className="px-2 pb-1.5 text-xs text-muted-foreground">On for this device. You get one when an agent needs you, finishes or fails.</p>
              <DropdownMenuItem onSelect={test}>Send a test</DropdownMenuItem>
              <DropdownMenuItem onSelect={unsubscribe} className="text-error focus:text-error">Turn off</DropdownMenuItem>
            </>
          )}
          {state === 'off' && (
            <>
              <p className="px-2 pb-1.5 text-xs text-muted-foreground">Get one when an agent needs you, finishes or fails.</p>
              <DropdownMenuItem onSelect={subscribe} className="font-medium">Turn on notifications</DropdownMenuItem>
            </>
          )}
          {hint[state] && <p className={cn('px-2 pb-2 text-sm', state === 'denied' && 'text-blocked')}>{hint[state]}</p>}
        </DropdownMenuContent>
      </DropdownMenu>
      {note && <p role="status" className="absolute top-9 right-0 z-50 w-56 rounded-md border bg-popover p-2 text-xs shadow-md">{note}</p>}
    </div>
  );
}
