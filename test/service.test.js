import { test } from 'node:test';
import assert from 'node:assert/strict';
import { plist, pickEnv, LABEL } from '../scripts/service.js';

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
