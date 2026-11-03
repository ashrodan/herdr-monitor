// Adds the Summary blocks to a Herdr layout: the bar in the status row (before
// workspace-status) and the panel in the panel-body stack. Backs the layout up
// once to <name>.json.before-summary and never overwrites that backup.
import fs from 'node:fs';

const file = process.argv[2];
if (!file) { console.error('usage: node scripts/mod-layout.js <layout.json>'); process.exit(2); }
const base = file.split('/').pop().replace(/\.json$/, '');
if (['main', 'terminals', 'win98'].includes(base)) {
  console.error(`Herdr replaces "${base}" on every start. Clone it first (layout.clone), then run this on the clone.`);
  process.exit(2);
}
const text = fs.readFileSync(file, 'utf8');
const doc = JSON.parse(text);
const find = (n, id) => { if (n?.id === id) return n; for (const c of n?.children ?? []) { const f = find(c, id); if (f) return f; } return null; };
const root = doc.root ?? doc;
let changed = false;

const status = find(root, 'status');
if (!status) console.warn('No "status" row: add {"type":"block","id":"summary-bar","content":{"mod":"summary","props":{"bar":true}}} by hand.');
else if (!find(root, 'summary-bar')) {
  const at = status.children.findIndex((c) => c.id === 'status-original');
  const block = { type: 'block', id: 'summary-bar', size: { weight: 1 }, content: { mod: 'summary', props: { bar: true } } };
  status.children.splice(at < 0 ? status.children.length : at, 0, block);
  changed = true;
}
const body = find(root, 'panel-body');
if (!body) console.warn('No "panel-body" stack: the bar opens the summary as a pane instead.');
else if (!find(root, 'summary')) {
  body.children.push({ type: 'block', id: 'summary', content: { mod: 'summary' } });
  changed = true;
}
if (!changed) { console.log('The layout already has the Summary blocks.'); process.exit(0); }
const backup = `${file}.before-summary`;
if (!fs.existsSync(backup)) fs.writeFileSync(backup, text);
fs.writeFileSync(file, JSON.stringify(doc, null, 2) + '\n');
console.log(`Added the Summary blocks to ${file} (backup: ${backup}).`);
