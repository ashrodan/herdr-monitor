// Claude Code's own transcript for an agent, as chat messages.
//
// terminal pid → ~/.claude/sessions/<pid>.json (sessionId, kept current after
// /clear) → ~/.claude/projects/<slug>/<sessionId>.jsonl. Exact, no guessing
// by folder: several agents often share one folder.
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

export const claudeDir = (env = process.env) => env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), '.claude');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_READ = 4 * 1024 * 1024;   // read at most the last 4 MB of a transcript
const MAX_RESULT = 4000;            // characters of a tool result sent to the page
const MAX_MESSAGES = 300;

// Claude Code's folder name for a cwd: every non-alphanumeric becomes '-'.
export const projectSlug = (cwd) => String(cwd).replace(/[^a-zA-Z0-9]/g, '-');

export async function sessionFor(pid, dir = claudeDir()) {
  if (!Number.isInteger(pid) || pid <= 0) return null;
  try {
    const s = JSON.parse(await fs.readFile(path.join(dir, 'sessions', `${pid}.json`), 'utf8'));
    if (!UUID.test(s.sessionId || '')) return null;
    return { id: s.sessionId, name: s.name || null, status: s.status || null, cwd: s.cwd || null };
  } catch { return null; }
}

const found = new Map(); // sessionId -> transcript path
export async function transcriptPath(session, dir = claudeDir()) {
  if (found.has(session.id)) return found.get(session.id);
  const projects = path.join(dir, 'projects');
  const direct = session.cwd && path.join(projects, projectSlug(session.cwd), `${session.id}.jsonl`);
  let hit = null;
  if (direct && await fs.stat(direct).then(() => true, () => false)) hit = direct;
  else {
    for (const d of await fs.readdir(projects).catch(() => [])) {
      const p = path.join(projects, d, `${session.id}.jsonl`);
      if (await fs.stat(p).then(() => true, () => false)) { hit = p; break; }
    }
  }
  if (hit) found.set(session.id, hit);
  return hit;
}

// The tail of a file as lines (the first, partial line dropped).
export async function readTail(file, max = MAX_READ) {
  const fh = await fs.open(file, 'r');
  try {
    const { size } = await fh.stat();
    const start = Math.max(0, size - max);
    const buf = Buffer.alloc(size - start);
    await fh.read(buf, 0, buf.length, start);
    let text = buf.toString('utf8');
    if (start > 0) text = text.slice(text.indexOf('\n') + 1);
    return { lines: text.split('\n'), truncated: start > 0, size };
  } finally { await fh.close(); }
}

// Text that Claude Code wraps around prompts, not written by the person.
function cleanPrompt(text) {
  let t = String(text).replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '');
  const cmd = t.match(/<command-name>([\s\S]*?)<\/command-name>/);
  if (cmd) {
    const args = t.match(/<command-args>([\s\S]*?)<\/command-args>/)?.[1]?.trim();
    return `${cmd[1].trim()}${args ? ' ' + args : ''}`;
  }
  t = t.replace(/<local-command-(stdout|stderr)>([\s\S]*?)<\/local-command-\1>/g, '$2');
  return t.trim();
}

// One line that says what a tool call does.
export function toolSummary(name, input = {}) {
  const i = input || {};
  const pick = i.command ?? i.file_path ?? i.notebook_path ?? i.pattern ?? i.url ?? i.query ?? i.description ?? i.prompt ?? i.skill;
  if (pick != null) return String(pick).split('\n')[0].slice(0, 300);
  const first = Object.values(i).find((v) => typeof v === 'string');
  return first ? first.split('\n')[0].slice(0, 300) : '';
}

// A question the agent asks you (Claude's AskUserQuestion, omp's ask): shown
// as a card. The page does not answer it; Herdr does.
const QUESTION_TOOLS = new Set(['AskUserQuestion', 'ask']);
export function questionPart(id, name, input) {
  if (!QUESTION_TOOLS.has(name) || !Array.isArray(input?.questions)) return null;
  const questions = input.questions.slice(0, 6).map((q) => ({
    header: String(q.header || ''), question: String(q.question || ''), multi: !!(q.multi ?? q.multiSelect),
    options: (q.options || []).slice(0, 10).map((o) => ({ label: String(o.label || ''), description: String(o.description || '') })),
  }));
  return { kind: 'question', id, name, questions, result: null };
}

function resultText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((b) => (b.type === 'text' ? b.text : b.type === 'image' ? '[image]' : '')).filter(Boolean).join('\n');
  return '';
}

// Transcript rows → chat messages. Consecutive assistant rows (one per
// content block) merge into one message; tool results join their call.
export function parseTranscript(lines) {
  const out = [];
  const tools = new Map(); // tool_use id -> part
  let last = null;
  const push = (m) => { out.push(m); last = m; return m; };

  for (const line of lines) {
    if (!line.trim()) continue;
    let r;
    try { r = JSON.parse(line); } catch { continue; }
    if (r.isSidechain) continue; // sub-agents' own conversations
    const at = r.timestamp || null;

    if (r.type === 'assistant' && Array.isArray(r.message?.content)) {
      const msg = last?.role === 'assistant' ? last : push({ id: r.uuid, role: 'assistant', at, parts: [] });
      for (const b of r.message.content) {
        if (b.type === 'text' && b.text?.trim()) msg.parts.push({ kind: 'text', text: b.text });
        else if (b.type === 'thinking' && b.thinking?.trim()) msg.parts.push({ kind: 'thinking', text: b.thinking });
        else if (b.type === 'tool_use') {
          const part = questionPart(b.id, b.name, b.input) || { kind: 'tool', id: b.id, name: b.name, summary: toolSummary(b.name, b.input), result: null };
          tools.set(b.id, part);
          msg.parts.push(part);
        }
      }
      continue;
    }

    if (r.type === 'user' && r.message) {
      const c = r.message.content;
      if (Array.isArray(c) && c.some((b) => b.type === 'tool_result')) {
        for (const b of c) {
          if (b.type !== 'tool_result') continue;
          const part = tools.get(b.tool_use_id);
          if (!part) continue;
          let text = resultText(b.content);
          const cut = text.length > MAX_RESULT;
          if (cut) text = text.slice(0, MAX_RESULT);
          part.result = { text, error: !!b.is_error, truncated: cut };
        }
        continue;
      }
      if (r.isMeta) continue;
      const raw = typeof c === 'string' ? c : Array.isArray(c) ? c.map((b) => (b.type === 'text' ? b.text : b.type === 'image' ? '[image]' : '')).join('\n') : '';
      const system = r.promptSource === 'system' || /^\s*<(task-notification|system-reminder)/.test(raw);
      if (system) {
        const summary = raw.match(/<summary>([\s\S]*?)<\/summary>/)?.[1]?.trim() || raw.match(/<status>([\s\S]*?)<\/status>/)?.[1]?.trim();
        push({ id: r.uuid, role: 'system', at, parts: [{ kind: 'text', text: summary || 'Background task update' }] });
        continue;
      }
      const text = cleanPrompt(raw);
      if (text) push({ id: r.uuid, role: 'user', at, parts: [{ kind: 'text', text }] });
      continue;
    }

    if (r.type === 'system' && r.subtype === 'away_summary' && r.content) {
      push({ id: r.uuid, role: 'system', at, parts: [{ kind: 'text', text: r.content }] });
    }
  }
  return out.slice(-MAX_MESSAGES);
}

// ---------------------------------------------------------------- omp / pi
//
// omp (and pi, which it is built on) keeps its session open while it runs:
// ~/.omp/agent/sessions/<folder>/<time>_<id>.jsonl. lsof on the agent's pid
// names that file exactly, even when several agents share a folder.

const SESSION_ROOTS = (home = os.homedir()) => [path.join(home, '.omp', 'agent', 'sessions'), path.join(home, '.pi', 'agent', 'sessions')];

const openFiles = (pid) => new Promise((resolve) => {
  execFile('lsof', ['-a', '-p', String(pid), '-Fn'], { timeout: 5000, maxBuffer: 4 * 1024 * 1024 }, (err, out) => resolve(err && !out ? '' : String(out)));
});

const ompFiles = new Map(); // pid -> { at, file }
export async function ompSessionFile(pid, { roots = SESSION_ROOTS(), lsof = openFiles } = {}) {
  if (!Number.isInteger(pid) || pid <= 0) return null;
  const hit = ompFiles.get(pid);
  if (hit && Date.now() - hit.at < 30_000) return hit.file;
  const files = (await lsof(pid)).split('\n').filter((l) => l.startsWith('n') && l.endsWith('.jsonl')).map((l) => l.slice(1));
  const file = files.find((f) => roots.some((r) => f.startsWith(r + path.sep))) || null;
  ompFiles.set(pid, { at: Date.now(), file });
  return file;
}

const blockText = (content) => typeof content === 'string' ? content
  : Array.isArray(content) ? content.map((b) => (b.type === 'text' ? b.text : b.type === 'image' ? '[image]' : '')).filter(Boolean).join('\n') : '';

// A background job's notice, in one line: "Background job X has completed".
function noticeLine(text) {
  const t = String(text).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  return (t.match(/^[^.]*?(?:has (?:completed|failed)|completed|failed)[^.]*/i)?.[0] || t).slice(0, 200);
}

// omp session rows → the same chat messages as parseTranscript.
export function parseOmp(lines) {
  const out = [];
  const tools = new Map();
  let last = null;
  const push = (m) => { out.push(m); last = m; return m; };
  for (const line of lines) {
    if (!line.trim()) continue;
    let r;
    try { r = JSON.parse(line); } catch { continue; }
    const at = r.timestamp || null;
    if (r.type === 'message' && r.message) {
      const m = r.message;
      if (m.role === 'assistant' && Array.isArray(m.content)) {
        const msg = last?.role === 'assistant' ? last : push({ id: r.id, role: 'assistant', at, parts: [] });
        for (const b of m.content) {
          if (b.type === 'text' && b.text?.trim()) msg.parts.push({ kind: 'text', text: b.text });
          else if (b.type === 'thinking' && b.thinking?.trim()) msg.parts.push({ kind: 'thinking', text: b.thinking });
          else if (b.type === 'toolCall') {
            const { i, ...args } = b.arguments || {};
            const part = questionPart(b.id, b.name, args) || { kind: 'tool', id: b.id, name: b.name, summary: toolSummary(b.name, args) || b.intent || i || '', result: null };
            tools.set(b.id, part);
            msg.parts.push(part);
          }
        }
      } else if (m.role === 'toolResult') {
        const part = tools.get(m.toolCallId);
        if (part) {
          let text = blockText(m.content);
          const cut = text.length > MAX_RESULT;
          if (cut) text = text.slice(0, MAX_RESULT);
          part.result = { text, error: !!m.isError, truncated: cut };
        }
      } else if (m.role === 'user' && (m.attribution ?? 'user') === 'user') {
        const text = blockText(m.content).trim();
        if (text) push({ id: r.id, role: 'user', at, parts: [{ kind: 'text', text }] });
      }
      continue; // developer and custom rows are the agent's own context
    }
    if (r.type === 'custom_message' && r.display && r.customType === 'async-result') {
      push({ id: r.id, role: 'system', at, parts: [{ kind: 'text', text: noticeLine(blockText(r.content)) }] });
    } else if (r.type === 'compaction') {
      push({ id: r.id, role: 'system', at, parts: [{ kind: 'text', text: 'Context compacted' }] });
    }
  }
  return out.slice(-MAX_MESSAGES);
}

// ---------------------------------------------------------------- chat

// The chat for a terminal's foreground pid: Claude Code's transcript, else
// an omp or pi session. Cached by file size and mtime.
const cache = new Map(); // path -> { size, mtimeMs, value }
async function chatOf(file, parse) {
  const st = await fs.stat(file);
  const hit = cache.get(file);
  if (hit && hit.size === st.size && hit.mtimeMs === st.mtimeMs) return hit.value;
  const { lines, truncated } = await readTail(file);
  const value = { available: true, truncated, messages: parse(lines), version: `${st.size}:${Math.round(st.mtimeMs)}` };
  cache.set(file, { size: st.size, mtimeMs: st.mtimeMs, value });
  return value;
}

export async function chatFor(pid, { dir = claudeDir(), roots, lsof } = {}) {
  const session = await sessionFor(pid, dir);
  if (session) {
    const file = await transcriptPath(session, dir);
    if (!file) return { available: false, reason: 'The transcript for this session was not found.', session };
    return { ...(await chatOf(file, parseTranscript)), session, source: 'claude' };
  }
  const omp = await ompSessionFile(pid, { roots, lsof });
  if (omp) {
    const value = await chatOf(omp, parseOmp).catch(() => null);
    if (value) return { ...value, session: { id: path.basename(omp, '.jsonl'), name: null }, source: omp.includes(`${path.sep}.pi${path.sep}`) ? 'pi' : 'omp' };
  }
  return { available: false, reason: 'No Claude Code or omp session for this terminal.' };
}
