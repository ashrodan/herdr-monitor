// npm run dev: rebuild the web UI on change (vite build --watch) and restart
// the server on change (node --watch). One Ctrl+C stops both.
import { spawn } from 'node:child_process';

const procs = [
  spawn('npx', ['vite', 'build', '--watch', '--logLevel', 'warn'], { stdio: 'inherit' }),
  spawn(process.execPath, ['--watch', 'server.js'], { stdio: 'inherit' }),
];
const stop = () => { for (const p of procs) p.kill('SIGTERM'); process.exit(0); };
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
for (const p of procs) p.on('exit', (code) => { if (code) { console.error(`${p.spawnargs.join(' ')} exited with ${code}`); stop(); } });
