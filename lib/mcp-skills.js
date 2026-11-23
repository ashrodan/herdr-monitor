// Agent Skills served over MCP (SEP-2640). A skill is a folder with a
// SKILL.md (YAML front matter: name, description) and any other files.
// Each file is a resource at skill://<name>/<path>. Loaded once, from disk.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const MIME = { '.md': 'text/markdown', '.json': 'application/json', '.txt': 'text/plain', '.sh': 'text/x-shellscript', '.js': 'text/javascript' };

// Flat YAML front matter: `key: value` lines, quotes optional.
export function frontMatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!m) return null;
  const out = {};
  for (const line of m[1].split(/\r?\n/)) {
    const kv = /^([A-Za-z0-9_-]+):\s*(.*)$/.exec(line);
    if (!kv) continue;
    let v = kv[2].trim();
    if (/^(["']).*\1$/.test(v)) v = v.slice(1, -1);
    out[kv[1]] = v;
  }
  return out;
}

function filesIn(dir, base = dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) filesIn(p, base, out);
    else if (e.isFile()) out.push(path.relative(base, p).split(path.sep).join('/'));
  }
  return out.sort();
}

export function loadSkills(root) {
  const skills = []; // {entry, body, files: Map uri -> {text, mimeType}}
  let dirs = [];
  try { dirs = fs.readdirSync(root, { withFileTypes: true }).filter((d) => d.isDirectory()); } catch { /* no skills */ }
  for (const d of dirs) {
    const dir = path.join(root, d.name);
    let main;
    try { main = fs.readFileSync(path.join(dir, 'SKILL.md'), 'utf8'); } catch { continue; }
    const fm = frontMatter(main);
    if (!fm?.name || !fm.description || fm.name !== d.name) continue; // the folder name is the skill name
    const files = new Map();
    const resources = [];
    for (const rel of filesIn(dir)) {
      const buf = fs.readFileSync(path.join(dir, rel));
      const uri = `skill://${fm.name}/${rel}`;
      files.set(uri, { text: buf.toString('utf8'), mimeType: MIME[path.extname(rel)] || 'text/plain' });
      resources.push({ uri, digest: `sha256:${crypto.createHash('sha256').update(buf).digest('hex')}`, size: buf.length });
    }
    const body = main.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/, '').trim();
    skills.push({ entry: { uri: `skill://${fm.name}/SKILL.md`, frontmatter: fm, resources }, body, files });
  }

  return {
    names: () => skills.map((s) => s.entry.frontmatter.name),
    list: () => skills.map((s) => s.entry),
    get: (uri) => skills.find((s) => s.entry.uri === uri)?.entry ?? null,
    // The SKILL.md resources, for resources/list (clients without skills/list).
    resources: () => skills.map((s) => ({ uri: s.entry.uri, name: s.entry.frontmatter.name, description: s.entry.frontmatter.description, mimeType: 'text/markdown' })),
    read: (uri) => { for (const s of skills) { const f = s.files.get(uri); if (f) return f; } return null; },
    // For the herdr_guide tool: the body of one skill, by name.
    body: (name) => skills.find((s) => s.entry.frontmatter.name === name)?.body ?? null,
    describe: () => skills.map((s) => `- ${s.entry.frontmatter.name}: ${s.entry.frontmatter.description}`).join('\n'),
  };
}
