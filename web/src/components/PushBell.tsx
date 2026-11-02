// The notifications bell: subscribe this device to Web Push, test it, turn it off.
import { useEffect, useState } from 'react';
import { Bell, BellOff, BellRing } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { getJson, post } from '@/api';

type State = 'loading' | 'unsupported' | 'insecure' | 'install' | 'denied' | 'off' | 'on';

const isIos = () => /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isStandalone = () => matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true;

function keyBytes(base64url: string) {
  const b64 = (base64url + '='.repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

async function currentSub() {
  const reg = await navigator.serviceWorker.ready;
  return reg.pushManager.getSubscription();
}

export function PushBell() {
  const [state, setState] = useState<State>('loading');
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      if (!window.isSecureContext) return setState('insecure');
      if (isIos() && !isStandalone()) return setState('install');
      if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return setState('unsupported');
      if (Notification.permission === 'denied') return setState('denied');
      setState((await currentSub()) ? 'on' : 'off');
    })().catch(() => setState('unsupported'));
  }, []);

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

  const hint: Partial<Record<State, string>> = {
    install: 'To get notifications on iPhone, add this page to the Home Screen (Share → Add to Home Screen), then open it from there.',
    insecure: 'Notifications need https. Open the Tailscale Serve address (https://…ts.net:7400).',
    unsupported: 'This browser does not support Web Push.',
    denied: 'Notifications are blocked for this site. Allow them in the browser or iOS settings.',
  };

  if (state === 'loading') return null;
  return (
    <div className="relative">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" className="size-8" aria-label="Notifications"
            onClick={(e) => { if (state === 'off') { e.preventDefault(); subscribe(); } }}>
            {state === 'on' ? <BellRing className="text-done" /> : state === 'off' ? <Bell /> : <BellOff className="text-muted-foreground" />}
          </Button>
        </DropdownMenuTrigger>
        {state !== 'off' && (
          <DropdownMenuContent align="end" className="w-72">
            {state === 'on' ? (
              <>
                <DropdownMenuLabel>Notifications are on for this device</DropdownMenuLabel>
                <p className="px-2 pb-1.5 text-xs text-muted-foreground">You get one when an agent needs you, finishes or fails.</p>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={test}>Send a test</DropdownMenuItem>
                <DropdownMenuItem onSelect={unsubscribe} className="text-error focus:text-error">Turn off</DropdownMenuItem>
              </>
            ) : <p className="p-2 text-sm">{hint[state]}</p>}
          </DropdownMenuContent>
        )}
      </DropdownMenu>
      {note && <p role="status" className="absolute top-9 right-0 z-50 w-56 rounded-md border bg-popover p-2 text-xs shadow-md">{note}</p>}
    </div>
  );
}
