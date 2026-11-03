import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './index.css';

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);

// Offline shell and Web Push. Service workers need a secure origin:
// https (Tailscale Serve) or localhost.
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch((err) => console.warn('service worker:', err));
  // A tapped notification asks an open window to show its agent.
  navigator.serviceWorker.addEventListener('message', (e) => {
    const open = e.data?.open;
    if (typeof open === 'string' && /^[\w./-]+$/.test(open)) {
      history.pushState({ inApp: true }, '', `#${open}`);
      window.dispatchEvent(new Event('routechange'));
    }
  });
}
