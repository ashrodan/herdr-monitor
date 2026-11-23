import { test } from 'node:test';
import assert from 'node:assert/strict';
import { plist, pickEnv, unit, LABEL } from '../scripts/service.js';

test('plist runs server.js with the chosen runtime, keeps it alive and escapes values', () => {
  const p = plist({ runtime: '/opt/bun', root: '/srv/hm', env: { PORT: '7400', PATH: '/a&b:/c', EMPTY: '' }, logDir: '/logs' });
  assert.match(p, new RegExp(`<key>Label</key><string>${LABEL}</string>`));
  assert.match(p, /<string>\/opt\/bun<\/string>\s*<string>\/srv\/hm\/server\.js<\/string>/);
  assert.match(p, /<key>KeepAlive<\/key><true\/>/);
  assert.match(p, /<key>PATH<\/key><string>\/a&amp;b:\/c<\/string>/);
  assert.doesNotMatch(p, /EMPTY/);
  assert.match(p, /<string>\/logs\/server\.log<\/string>/);
});

test('pickEnv keeps only the settings the server reads', () => {
  assert.deepEqual(pickEnv({ PORT: '7400', PATH: '/bin', SECRET_TOKEN: 'x', HOME: '/h' }), { PORT: '7400', PATH: '/bin' });
});

test('unit (Linux) runs server.js with the chosen runtime, restarts it and quotes values', () => {
  const u = unit({ runtime: '/usr/bin/node', root: '/home/me/herdr monitor', env: { PORT: '7400', PATH: '/a:/b c', HC_BIN: 'x"y%z', EMPTY: '' } });
  assert.match(u, /^ExecStart="\/usr\/bin\/node" "\/home\/me\/herdr monitor\/server\.js"$/m);
  // WorkingDirectory takes the whole line as the path: no quotes.
  assert.match(u, /^WorkingDirectory=\/home\/me\/herdr monitor$/m);
  assert.match(u, /^Environment="PORT=7400"$/m);
  assert.match(u, /^Environment="PATH=\/a:\/b c"$/m);
  assert.match(u, /^Environment="HC_BIN=x\\"y%%z"$/m);
  assert.doesNotMatch(u, /EMPTY/);
  assert.match(u, /^Restart=always$/m);
  assert.match(u, /^WantedBy=default\.target$/m);
});
