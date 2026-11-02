// Shapes the Node server sends (see lib/hc.js summarize, lib/sidebar.js, lib/pr.js, lib/git.js).
export type AgentState = 'working' | 'blocked' | 'done' | 'idle' | 'error';

export interface Terminal {
  address: string;
  name: string | null;
  agent: string;
  cwd: string;
  state: 'live' | 'finished' | string;
  title: string | null;
  where: string | null;
  reported: boolean;
  status: { app?: string; state?: AgentState; kind?: string; msg?: string; progress?: number } | null;
  exit_code: number | null;
  created_ms: number | null;
  parent: string | null;
  space: string | null;
  tab: string | null;
  changed_ms: number | null;
  seen_ms?: number | null; // when Herdr itself showed this finished agent
}

export interface SpaceTab { id: string; name: string; named: boolean; terminals: string[] }

export interface Space {
  id: string;
  name: string;
  color: string;
  repo: string | null;
  mainRepo: string | null;
  branch: string | null;
  behind: number;
  worktree: boolean;
  parent: string | null;
  virtual?: boolean;
  tabs: SpaceTab[];
}

export interface Commit { sha: string; author: string; date: string; subject: string; local?: boolean }
export interface Commits {
  repo: string | null; reason?: string; error?: string;
  branch?: string; base?: string | null; unpushed?: number | null; dirty?: number;
  pr?: { number: number; title: string; url: string; state: string; isDraft: boolean; baseRefName: string; headRefName: string; additions: number; deletions: number; changedFiles: number } | null;
  commits: Commit[];
}
export interface CommitDetail { sha: string; author: string; date: string; message: string; files: { file: string; additions: number | null; deletions: number | null }[] }

export type Bucket = 'pass' | 'fail' | 'pending' | 'skip';
export interface Check { name: string; workflow: string | null; bucket: Bucket; url: string | null; startedAt: string | null; completedAt: string | null }
export interface PrStatus {
  repo: string | null; reason?: string; ghError?: boolean; error?: string; branch?: string;
  pr: null | { number: number; title: string; url: string; state: string; isDraft: boolean; baseRefName: string; headRefName: string; updatedAt: string; reviewDecision: string };
  readiness?: { state: 'ready' | 'blocked' | 'merged' | 'closed' | 'unknown'; canMerge: boolean; reasons: string[] };
  reviews?: { login: string; state: string; submittedAt: string | null }[];
  checks?: Check[];
  counts?: Record<Bucket, number>;
}

export interface OverviewPr {
  space: string; spaceName: string; color: string; branch: string;
  pr: NonNullable<PrStatus['pr']>; readiness: NonNullable<PrStatus['readiness']>; counts: Record<Bucket, number>;
}
export interface Overview { prs: OverviewPr[]; at: number }
