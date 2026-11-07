import { useEffect } from 'react';
import { cn } from '@/lib/utils';
import { getJson, termPath } from '@/api';
import { ago } from '@/model';
import type { PrStatus } from '@/types';
import { Msg } from './CommitsPane';

const DECISION: Record<string, string> = { APPROVED: 'Approved', CHANGES_REQUESTED: 'Changes requested', REVIEW_REQUIRED: 'Review required' };
const BUCKET_DOT = { pass: 'bg-done', fail: 'bg-error', pending: 'bg-blocked animate-pulse-soft', skip: 'bg-idle' } as const;
const PR_PILL: Record<string, string> = { open: 'text-done border-done/40', draft: 'text-muted-foreground', merged: 'text-working border-working/40', closed: 'text-error border-error/40' };

const dur = (a: string | null, b: string | null) => {
  if (!a) return '';
  const s = Math.max(0, ((b ? new Date(b) : new Date()).getTime() - new Date(a).getTime()) / 1000);
  return s < 60 ? `${Math.round(s)}s` : s < 3600 ? `${Math.floor(s / 60)}m ${Math.round(s % 60)}s` : `${Math.floor(s / 3600)}h ${Math.round((s % 3600) / 60)}m`;
};

export function StatusPane({ addr, data, onData }: { addr: string; data: PrStatus | null; onData: (d: PrStatus) => void }) {
  // Fresh on open; then every 20 s while a check is pending.
  useEffect(() => {
    getJson<PrStatus>(termPath(addr, `status?fresh`)).then(onData).catch((e) => onData({ repo: null, pr: null, error: e.message }));
  }, [addr]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!data?.counts?.pending) return;
    const id = setInterval(() => getJson<PrStatus>(termPath(addr, `status?fresh`)).then(onData).catch(() => {}), 20000);
    return () => clearInterval(id);
  }, [addr, data?.counts?.pending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return <Msg>Loading PR status…</Msg>;
  if (data.error) return <Msg>{data.error}</Msg>;
  if (!data.repo) return <Msg>{data.reason}</Msg>;
  if (!data.pr) {
    return (
      <div className="p-3 md:p-4"><div className="rounded-lg border bg-card p-3 text-sm">
        <span className={data.ghError ? 'font-medium text-blocked' : 'text-muted-foreground'}>{data.reason}</span>
        <p className="mt-1 text-xs text-muted-foreground">branch <b className="text-foreground">{data.branch}</b></p>
      </div></div>
    );
  }
  const { pr, readiness: r, counts: c, checks = [], reviews = [] } = data;
  const st = pr.state === 'OPEN' && pr.isDraft ? 'draft' : pr.state.toLowerCase();
  return (
    <div className="h-full space-y-3 overflow-y-auto p-3 md:p-4">
      <div className="rounded-lg border bg-card p-3">
        <a href={pr.url} target="_blank" rel="noopener" className="font-semibold text-working hover:underline">#{pr.number} {pr.title}</a>
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span className={cn('rounded-full border px-2 py-px', PR_PILL[st])}>{st[0].toUpperCase() + st.slice(1)}</span>
          <span>{pr.baseRefName} ← {pr.headRefName}</span><span>updated {ago(pr.updatedAt)}</span>
        </div>
        <p className="mt-2.5 text-sm">
          {r?.state === 'merged' ? <b className="text-muted-foreground">Merged.</b>
            : r?.state === 'closed' ? <b className="text-muted-foreground">Closed without merging.</b>
            : r?.canMerge ? <b className="text-done">Ready to merge.</b>
            : <><b className="text-blocked">Cannot merge yet:</b> {r?.reasons.join(', ')}.</>}
        </p>
      </div>
      <div className="rounded-lg border bg-card p-3">
        <h3 className="mb-1.5 text-xs font-semibold text-muted-foreground">Review{pr.reviewDecision ? `: ${DECISION[pr.reviewDecision] || pr.reviewDecision}` : ''}</h3>
        {reviews.length ? reviews.map((v) => (
          <div key={v.login} className="flex flex-wrap gap-x-3 border-t py-1.5 text-sm">
            <b>{v.login}</b>
            <span className={v.state === 'APPROVED' ? 'text-done' : v.state === 'CHANGES_REQUESTED' ? 'text-error' : 'text-muted-foreground'}>{v.state.toLowerCase().replace(/_/g, ' ')}</span>
            <span className="text-muted-foreground">{ago(v.submittedAt)}</span>
          </div>
        )) : <p className="text-sm text-muted-foreground">No reviews yet.</p>}
      </div>
      <div className="rounded-lg border bg-card p-3">
        <h3 className="mb-1.5 text-xs font-semibold text-muted-foreground">Checks</h3>
        {c && checks.length > 0 && (
          <p className="mb-1.5 flex flex-wrap gap-x-3 text-xs">
            <span className="text-done">{c.pass} passed</span><span className="text-error">{c.fail} failed</span>
            <span className="text-blocked">{c.pending} pending</span><span className="text-muted-foreground">{c.skip} skipped</span>
          </p>
        )}
        {checks.length ? checks.map((k, i) => (
          <div key={i} className="grid grid-cols-[0.75rem_1fr_auto] items-baseline gap-x-2.5 border-t py-1.5 text-sm">
            <span className={cn('inline-block size-2 rounded-full', BUCKET_DOT[k.bucket])} />
            <span className="min-w-0 [overflow-wrap:anywhere]">{k.url ? <a href={k.url} target="_blank" rel="noopener" className="text-working hover:underline">{k.name}</a> : k.name}</span>
            <span className="text-xs text-muted-foreground">{k.bucket}{k.startedAt ? ` · ${dur(k.startedAt, k.completedAt)}` : ''}</span>
            {k.workflow && <span className="col-start-2 text-xs text-muted-foreground">{k.workflow}</span>}
          </div>
        )) : <p className="text-sm text-muted-foreground">No checks on this PR.</p>}
      </div>
    </div>
  );
}
