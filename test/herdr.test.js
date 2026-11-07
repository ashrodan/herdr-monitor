import { test } from 'node:test';
import assert from 'node:assert/strict';
import { discover, parseVersion, plistVersion, tailnetPeers } from '../lib/herdr.js';

test('parseVersion', () => {
  assert.equal(parseVersion('hc 0.1.18\n'), '0.1.18');
  assert.equal(parseVersion('herdr 0.9.5'), '0.9.5');
  assert.equal(parseVersion('v1.2.0-beta.1'), '1.2.0-beta.1');
  assert.equal(parseVersion(''), null);
  assert.equal(parseVersion(null), null);
});

test('plistVersion', () => {
  const xml = '<dict>\n\t<key>CFBundleName</key><string>Herdr</string>\n\t<key>CFBundleShortVersionString</key>\n\t<string>0.1.13</string>\n</dict>';
  assert.equal(plistVersion(xml), '0.1.13');
  assert.equal(plistVersion('<dict></dict>'), null);
});

const STATUS = {
  Self: { DNSName: 'Mac.tail1.ts.net.', OS: 'macOS' },
  Peer: {
    a: { DNSName: 'box.tail1.ts.net.', OS: 'linux', Online: true },
    b: { DNSName: 'ipad.tail1.ts.net.', OS: 'iOS', Online: false },
    c: { DNSName: '', OS: 'linux', Online: true },
  },
};

test('tailnetPeers: self first, online peers with a name only', () => {
  assert.deepEqual(tailnetPeers(STATUS), [
    { name: 'mac.tail1.ts.net', os: 'macOS', self: true },
    { name: 'box.tail1.ts.net', os: 'linux', self: false },
  ]);
  assert.deepEqual(tailnetPeers(null), []);
});

test('discover: keeps peers that answer as herdr-monitor', async () => {
  const asked = [];
  const fetchJson = async (url) => {
    asked.push(url);
    return url.startsWith('https://box.') ? { app: 'herdr-monitor', herdr: { tui: { version: '0.9.5' } } } : null;
  };
  const self = { app: 'herdr-monitor', herdr: { ui: { version: '0.1.13' } } };
  const { servers } = await discover({ status: STATUS, port: 7400, self, fetchJson });
  assert.deepEqual(asked, ['https://box.tail1.ts.net:7400/api/info']);
  assert.deepEqual(servers.map((s) => [s.url, s.self, s.herdr]), [
    ['https://mac.tail1.ts.net:7400', true, { ui: { version: '0.1.13' } }],
    ['https://box.tail1.ts.net:7400', false, { tui: { version: '0.9.5' } }],
  ]);
  const none = await discover({ status: STATUS, port: 443, self: null, fetchJson: async () => null });
  assert.deepEqual(none.servers, []);
});
