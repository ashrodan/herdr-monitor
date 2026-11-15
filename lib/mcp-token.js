// The MCP endpoint's bearer token: HERDR_MCP_TOKEN, else <configDir>/mcp-token
// (32 random bytes as hex, mode 600, made on first start). A client puts it
// in an `Authorization: Bearer <token>` header. To replace it, delete the
// file and restart the server.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export function mcpToken(dir, env = process.env) {
  if (env.HERDR_MCP_TOKEN) {
    if (env.HERDR_MCP_TOKEN.length < 32) throw new Error('HERDR_MCP_TOKEN must be at least 32 characters');
    return { token: env.HERDR_MCP_TOKEN, file: null, created: false };
  }
  const file = path.join(dir, 'mcp-token');
  try {
    const token = fs.readFileSync(file, 'utf8').trim();
    if (token.length >= 32) return { token, file, created: false };
  } catch (err) {
    if (err.code !== 'ENOENT') throw err;
  }
  const token = crypto.randomBytes(32).toString('hex');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, token + '\n', { mode: 0o600 });
  fs.chmodSync(file, 0o600);
  return { token, file, created: true };
}
