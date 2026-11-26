#!/usr/bin/env node
// herdr-ask <request.json>: one turn of the Summary mod's chat.
// The request file holds {message, history, context}; prints {reply, actions, dropped}
// as JSON on stdout, or {error} with exit code 1. A file, not stdin: a mod's exec has no stdin.
//
// Key: GEMINI_API_KEY, else the macOS Keychain item herdr-ask/gemini
//   (security add-generic-password -U -s herdr-ask -a gemini -w <key>).
// Model: HERDR_ASK_MODEL, else lib/ask.js DEFAULT_MODEL.
import { readFile, rm } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { DEFAULT_MODEL, ENDPOINT, buildRequest, buildFollowup, needsFollowup, parseResponse, textOf } from '../lib/ask.js';

const run = promisify(execFile);

async function apiKey() {
  if (process.env.GEMINI_API_KEY) return process.env.GEMINI_API_KEY;
  try {
    const { stdout } = await run('security', ['find-generic-password', '-s', 'herdr-ask', '-a', 'gemini', '-w']);
    return stdout.trim() || null;
  } catch { return null; }
}

function out(obj, code = 0) {
  process.stdout.write(JSON.stringify(obj) + '\n');
  process.exit(code);
}

// --consume: delete the request file once read (the mod writes one per turn).
const args = process.argv.slice(2);
const consume = args[0] === '--consume';
const file = consume ? args[1] : args[0];
if (!file) out({ error: 'usage: herdr-ask [--consume] <request.json>' }, 2);

let req;
try { req = JSON.parse(await readFile(file, 'utf8')); } catch (e) { out({ error: `can't read ${file}: ${e.message}` }, 1); }
if (consume) await rm(file, { force: true });
if (!req?.message) out({ error: 'the request has no message' }, 1);

const key = await apiKey();
if (!key) out({ error: 'No Gemini key: set GEMINI_API_KEY or run: security add-generic-password -U -s herdr-ask -a gemini -w <key>' }, 1);

const model = process.env.HERDR_ASK_MODEL || DEFAULT_MODEL;

async function generate(body) {
  let res;
  try {
    res = await fetch(ENDPOINT(model), {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': key },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(45_000),
    });
  } catch (e) { out({ error: `Gemini unreachable: ${e.message}` }, 1); }
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not JSON: reported below */ }
  if (!res.ok) out({ error: `Gemini ${res.status}: ${json?.error?.message ?? text.slice(0, 200)}` }, 1);
  if (!json) out({ error: 'Gemini answered with something that is not JSON' }, 1);
  return json;
}

const body = buildRequest(req);
const json = await generate(body);
const result = parseResponse(json, req.context);
// A tool call with no words: one more round for the text (see lib/ask.js buildFollowup).
if (needsFollowup(json) && result.actions.length) {
  const said = textOf(await generate(buildFollowup(body, json)));
  if (said) result.reply = said;
}
out({ model, ...result });
