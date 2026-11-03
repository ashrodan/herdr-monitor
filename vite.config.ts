import path from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The UI lives in web/ and builds to dist/, which server.js serves.
// `vite` (dev) proxies /api to the Node server on :7400.
export default defineConfig({
  root: 'web',
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(import.meta.dirname, 'web/src') } },
  build: { outDir: '../dist', emptyOutDir: true },
  server: { proxy: { '/api': { target: `http://127.0.0.1:${process.env.PORT || 7400}`, changeOrigin: false } } },
});
