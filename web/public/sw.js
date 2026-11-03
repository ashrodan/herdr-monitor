// herdr-monitor service worker.
// - /api/*: network only (live data, never cached).
// - the app shell (pages, hashed assets, icons): network first, cache as fallback,
//   so the app opens even when the Mac is asleep and shows "offline".
// - Web Push: show the notification; a tap opens or focuses the app on the agent.
const CACHE = 'herdr-monitor-v1';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
  event.respondWith((async () => {
    try {
      const res = await fetch(req);
      if (res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req.mode === 'navigate' ? '/' : req, copy));
      }
      return res;
    } catch (err) {
      const hit = await caches.match(req.mode === 'navigate' ? '/' : req);
      if (hit) return hit;
      throw err;
    }
  })());
});

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { title: 'Herdr', body: event.data?.text() }; }
  event.waitUntil(self.registration.showNotification(data.title || 'Herdr', {
    body: data.body || '',
    tag: data.tag || undefined,
    renotify: !!data.tag,
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    data: { url: data.url || '/' },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = event.notification.data?.url || '/';
  const hash = new URL(url, self.location.origin).hash;
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const win = wins.find((w) => new URL(w.url).origin === self.location.origin);
    if (win) {
      await win.focus();
      win.postMessage({ open: hash.slice(1) });
      return;
    }
    await self.clients.openWindow(url);
  })());
});
