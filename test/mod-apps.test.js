import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { parseScan, parseProbe, probeArgs, servedPorts, programOf, apps, spaceOf, SCAN, PROBE, SHARE } from "../herdr-mod/summary/apps.js";

const DNS = "box.tail0000.ts.net";
const SCAN_OUT = [
  `D ${DNS}`,
  `S {"TCP":{"8443":{"HTTPS":true}},"Web":{"${DNS}:8443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:7400"}}}}}`,
  "L\t100\t127.0.0.1\t7400",
  "L\t200\t[::1]\t5173",
  "L\t300\t*\t8080",
  "L\t400\t127.0.0.1\t5432",
  "L\t500\t100.101.102.103\t3000",
  "E\t100\tt7",
  "E\t200\tt9",
  "C\t100\t/home/me/dev/monitor",
  "C\t200\t/home/me/dev/shop/web",
  "C\t300\t/home/me/dev/api",
  "C\t400\t/var/lib/postgres",
  "C\t500\t/home/me/dev/blog",
  "A\t100\tnode server.js",
  "A\t200\tnode /home/me/dev/shop/web/node_modules/.bin/vite --port 5173",
  "A\t300\t/home/me/dev/api/tmp/main serve",
  "A\t400\tpostgres -D /var/lib/postgres",
  "A\t500\tnode /x/node_modules/next/dist/bin/next dev",
].join("\n");

const PROBE_OUT = [
  "P\t100\t127.0.0.1\t7400\t200\ttext/html; charset=utf-8\t200",
  "P\t200\t[::1]\t5173\t200\ttext/html\t403",
  "P\t300\t*\t8080\t200\ttext/html\t200",
  "P\t400\t127.0.0.1\t5432\t0\t\t0",
  "P\t500\t100.101.102.103\t3000\t200\ttext/html\t200",
].join("\n");

test("parseScan joins listeners with terminal, folder and command line", () => {
  const s = parseScan(SCAN_OUT);
  assert.equal(s.dns, DNS);
  assert.equal(s.listeners.length, 5);
  assert.deepEqual(s.listeners[1], { pid: 200, host: "[::1]", port: 5173, term: "t9", cwd: "/home/me/dev/shop/web", args: "node /home/me/dev/shop/web/node_modules/.bin/vite --port 5173" });
  assert.equal(s.listeners[2].term, null);
});

test("servedPorts maps the proxied port to its https URL", () => {
  const m = servedPorts(parseScan(SCAN_OUT).serve, DNS);
  assert.equal(m.get(7400), `https://${DNS}:8443`);
  const m443 = servedPorts({ Web: { [`${DNS}:443`]: { Handlers: { "/": { Proxy: "http://127.0.0.1:3000" } } } } }, DNS);
  assert.equal(m443.get(3000), `https://${DNS}`);
});

test("programOf names common dev servers", () => {
  assert.equal(programOf("node /a/node_modules/.bin/vite --port 5173"), "vite");
  assert.equal(programOf("node /x/node_modules/next/dist/bin/next dev"), "next");
  assert.equal(programOf("node server.js"), "node server.js");
  assert.equal(programOf("python3 -m http.server 8000"), "http.server");
  assert.equal(programOf("/home/me/dev/api/tmp/main serve"), "main");
  assert.equal(programOf(""), "app");
});

test("apps keeps HTML only and picks the tailnet URL", () => {
  const s = parseScan(SCAN_OUT);
  const list = apps(s, parseProbe(PROBE_OUT), null);
  const by = Object.fromEntries(list.map((a) => [a.port, a]));
  assert.deepEqual(Object.keys(by).map(Number), [3000, 5173, 7400, 8080]); // no postgres
  assert.equal(by[7400].how, "served");
  assert.equal(by[7400].url, `https://${DNS}:8443`);
  assert.equal(by[8080].how, "open");
  assert.equal(by[8080].url, `http://${DNS}:8080`);
  assert.equal(by[3000].how, "open"); // bound to the tailnet address
  assert.equal(by[5173].how, "local");
  assert.equal(by[5173].url, null);
  assert.equal(by[5173].target, "http://[::1]:5173"); // listens on IPv6 loopback only
  assert.equal(by[5173].hostBlocked, false); // local: the hint shows once it is shared
  assert.equal(by[5173].program, "vite");
});

test("hostBlocked when a shared app refuses the tailnet Host", () => {
  const s = parseScan(SCAN_OUT.replace('"Proxy":"http://127.0.0.1:7400"', '"Proxy":"http://127.0.0.1:5173"'));
  const a = apps(s, parseProbe(PROBE_OUT)).find((x) => x.port === 5173);
  assert.equal(a.how, "served");
  assert.equal(a.hostBlocked, true);
});

test("no tailnet: no URL, only the local one", () => {
  const s = parseScan(SCAN_OUT.split("\n").filter((l) => !/^[DS] /.test(l)).join("\n"));
  for (const a of apps(s, parseProbe(PROBE_OUT))) {
    assert.equal(a.url, null);
    assert.equal(a.local, `http://localhost:${a.port}`);
  }
});

test("spaceOf: terminal first, then the longest folder", () => {
  const shop = { id: "s1", name: "shop" }, dev = { id: "s2", name: "dev" }, api = { id: "s3", name: "api" }, remote = { id: "dai/s4", name: "remote" };
  const terms = new Map([["t9", shop], ["dai/t1", remote]]);
  const folders = [{ dir: "/home/me/dev", space: dev }, { dir: "/home/me/dev/api/", space: api }];
  assert.equal(spaceOf({ term: "t9", cwd: "/elsewhere" }, terms, folders), shop);
  assert.equal(spaceOf({ term: "t1", machine: "dai", cwd: null }, terms, folders), remote);
  assert.equal(spaceOf({ term: "t5", cwd: "/home/me/dev/api/sub" }, terms, folders), api);
  assert.equal(spaceOf({ term: null, cwd: "/home/me/dev/blog" }, terms, folders), dev);
  assert.equal(spaceOf({ term: null, cwd: "/home/me/devx" }, terms, folders), null);
  assert.equal(spaceOf({ term: null, cwd: null }, terms, folders), null);
});

test("container port forwarders are left out", () => {
  const s = parseScan(SCAN_OUT.replace("A\t300\t/home/me/dev/api/tmp/main serve", "A\t300\t/Applications/OrbStack.app/Contents/MacOS/OrbStack Helper vmgr"));
  assert.equal(apps(s, parseProbe(PROBE_OUT)).some((a) => a.port === 8080), false);
});

test("probeArgs gives one argument per address", () => {
  const s = parseScan(SCAN_OUT);
  assert.deepEqual(probeArgs(s.listeners.slice(0, 2)), ["100,127.0.0.1,7400", "200,[::1],5173"]);
});

test("the scripts are valid sh", () => {
  for (const script of [SCAN, PROBE, SHARE]) execFileSync("sh", ["-n", "-c", script]);
});

test("PROBE finds a local HTML server", async () => {
  const http = await import("node:http");
  const server = http.createServer((_q, r) => { r.setHeader("content-type", "text/html"); r.end("<p>hi</p>"); });
  await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
  const { port } = server.address();
  try {
    const { execFile } = await import("node:child_process");
    const out = await new Promise((ok, no) => execFile("sh", ["-c", PROBE, "sh", "", `1,127.0.0.1,${port}`], (e, so) => (e ? no(e) : ok(so))));
    const [p] = parseProbe(out);
    assert.equal(p.port, port);
    assert.equal(p.code, 200);
    assert.match(p.type, /text\/html/);
  } finally { server.close(); }
});
