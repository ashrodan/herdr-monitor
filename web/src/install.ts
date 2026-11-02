// "Install the app": Chrome on Android and desktop fire beforeinstallprompt,
// which we keep and fire from our own button. iPhone browsers never prompt:
// the user adds the page from the Share menu, so we show the steps.
import { useSyncExternalStore } from 'react';

interface InstallEvent extends Event { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }> }

let deferred: InstallEvent | null = null;
let installed = false;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferred = e as InstallEvent; emit(); });
window.addEventListener('appinstalled', () => { installed = true; deferred = null; emit(); });

export const isStandalone = () => matchMedia('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true;
export const isIos = () => /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

export type Browser = 'ios-safari' | 'ios-chrome' | 'ios-other' | 'android' | 'desktop';
export function browser(): Browser {
  const ua = navigator.userAgent;
  if (isIos()) return /CriOS/.test(ua) ? 'ios-chrome' : /FxiOS|EdgiOS|OPiOS/.test(ua) ? 'ios-other' : 'ios-safari';
  return /Android/.test(ua) ? 'android' : 'desktop';
}

export async function promptInstall(): Promise<boolean> {
  if (!deferred) return false;
  const e = deferred;
  deferred = null; emit();
  await e.prompt();
  return (await e.userChoice).outcome === 'accepted';
}

let snap = { canPrompt: false, installed: false };
export function useInstall() {
  return useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, () => {
    const next = { canPrompt: !!deferred, installed: installed || isStandalone() };
    if (next.canPrompt !== snap.canPrompt || next.installed !== snap.installed) snap = next;
    return snap;
  });
}
