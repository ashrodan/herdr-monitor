// Open PRs across the spaces' repos (GET /api/overview). One `gh pr view`
// per repo, 4 at a time: about 6 s cold for a dozen repos. So the answer
// is always the last result, at once, and a result older than maxAgeMs
// starts one refresh in the background. The last result is also kept on
// disk, so a restart does not start cold. `fresh` waits for a new result.
import fs from 'node:fs';
import path from 'node:path';

export class Overview {
  // repos(): [[repo, space], ...]; status(repo, {fresh}): prStatus output.
  // ready(): false while the layout is not loaded (hc down): then the answer
  // is the last result, and nothing runs or replaces it.
  constructor({ repos, status, ready = () => true, file = null, maxAgeMs = 60000, workers = 4, now = Date.now, log = () => {} }) {
    Object.assign(this, { repos, status, ready, file, maxAgeMs, workers, now, log });
    this.value = this.#load();
    this.running = null;
  }

  async get({ fresh = false } = {}) {
    if (!this.ready()) return this.value ?? { prs: [], at: 0 };
    if (fresh) return this.refresh({ fresh: true });
    if (!this.value) return this.refresh();
    if (this.now() - this.value.at > this.maxAgeMs) this.refresh().catch(() => {});
    return this.value;
  }

  // One run at a time: a second caller shares the run in progress.
  refresh({ fresh = false } = {}) {
    if (this.running && !fresh) return this.running;
    const run = this.#collect(fresh).then((value) => {
      this.value = value;
      this.#save(value);
      return value;
    }).finally(() => { if (this.running === run) this.running = null; });
    this.running = run;
    return run;
  }

  async #collect(fresh) {
    const jobs = [...this.repos()];
    const prs = [];
    const worker = async () => {
      for (let job; (job = jobs.shift());) {
        const [repo, sp] = job;
        const st = await this.status(repo, { fresh }).catch(() => null);
        if (st?.pr && st.pr.state === 'OPEN') {
          prs.push({ space: sp.id, spaceName: sp.name, color: sp.color, branch: st.branch, pr: st.pr, readiness: st.readiness, counts: st.counts });
        }
      }
    };
    await Promise.all(Array.from({ length: this.workers }, worker));
    prs.sort((a, b) => String(b.pr.updatedAt).localeCompare(String(a.pr.updatedAt)));
    return { prs, at: this.now() };
  }

  #load() {
    if (!this.file) return null;
    try {
      const v = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      return Array.isArray(v?.prs) && Number.isFinite(v?.at) ? v : null;
    } catch { return null; }
  }

  // Temp file, then rename: a crash mid-write leaves the old copy.
  #save(value) {
    if (!this.file) return;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 });
      const tmp = `${this.file}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(value), { mode: 0o600 });
      fs.renameSync(tmp, this.file);
    } catch (err) { this.log(`overview cache not saved: ${err.message}`); }
  }
}

// The repos the Overview covers: each repo once, with the first space that
// shows it.
export function reposOf(layout) {
  const repos = new Map();
  for (const sp of layout) if (sp.repo && !sp.virtual && !repos.has(sp.repo)) repos.set(sp.repo, sp);
  return [...repos.entries()];
}
