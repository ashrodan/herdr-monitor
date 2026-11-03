import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import { getJson } from '@/api';
import { ago } from '@/model';
import type { CommitDetail, Commits } from '@/types';

export function CommitsPane({ addr }: { addr: string }) {
  const [d, setD] = useState<Commits | { error: string } | null>(null);
  const [open, setOpen] = useState<{ sha: string; detail?: CommitDetail; error?: string } | null>(null);

  useEffect(() => {
    let stop = false;
    setD(null); setOpen(null);
    getJson<Commits>(`/api/terminals/${addr}/commits`).then((x) => !stop && setD(x)).catch((e) => !stop && setD({ error: e.message }));
    return () => { stop = true; };
  }, [addr]);

  const toggle = async (sha: string) => {
    if (open?.sha === sha) { setOpen(null); return; }
    setOpen({ sha });
    try { const detail = await getJson<CommitDetail>(`/api/terminals/${addr}/commits/${sha}`); setOpen((o) => (o?.sha === sha ? { sha, detail } : o)); }
    catch { setOpen((o) => (o?.sha === sha ? { sha, error: 'Commit is not in the local clone yet (git fetch to see it).' } : o)); }
  };

  if (!d) return <Msg>Loading commits…</Msg>;
  if ('error' in d && d.error) return <Msg>{d.error}</Msg>;
  const c = d as Commits;
  if (!c.repo) return <Msg>{c.reason}</Msg>;
  const facts = [
    <>branch <b className="text-foreground">{c.branch}</b></>, c.base && <>base {c.base}</>,
    c.unpushed != null ? `${c.unpushed} unpushed` : 'no upstream',
    c.dirty ? `${c.dirty} uncommitted file${c.dirty > 1 ? 's' : ''}` : 'clean tree',
  ].filter(Boolean);

  return (
    <div className="h-full space-y-3 overflow-y-auto p-3 md:p-4">
      <div className="rounded-lg border bg-card p-3">
        {c.pr
          ? <a href={c.pr.url} target="_blank" rel="noopener" className="font-semibold text-working hover:underline">#{c.pr.number} {c.pr.title}</a>
          : <span className="text-sm text-muted-foreground">No PR for this branch. Commits since {c.base}:</span>}
        <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
          {c.pr && <><span>{c.pr.isDraft ? 'draft' : c.pr.state.toLowerCase()}</span><span>{c.pr.baseRefName} ← {c.pr.headRefName}</span>
            <span><span className="text-done">+{c.pr.additions}</span> <span className="text-error">−{c.pr.deletions}</span> in {c.pr.changedFiles} files</span></>}
          {facts.map((f, i) => <span key={i}>{f}</span>)}
        </div>
      </div>
      {!c.commits.length && <p className="text-sm text-muted-foreground">No commits on this branch yet.</p>}
      <ul className="space-y-1.5">
        {c.commits.map((k) => (
          <li key={k.sha} className="rounded-lg border bg-card">
            <button className="grid w-full grid-cols-[4.5rem_1fr] gap-x-3 gap-y-0.5 px-3 py-2 text-left text-sm sm:grid-cols-[4.5rem_1fr_auto]" onClick={() => toggle(k.sha)}>
              <span className="font-mono text-xs text-working">{k.sha.slice(0, 8)}</span>
              <span className="min-w-0 [overflow-wrap:anywhere]">{k.subject}
                {k.local && <span className="ml-1.5 rounded-full border border-blocked/50 px-1.5 text-[11px] text-blocked">not pushed</span>}</span>
              <span className="col-start-2 text-xs text-muted-foreground sm:col-start-3">{k.author} · {ago(k.date)}</span>
            </button>
            {open?.sha === k.sha && (
              <div className="border-t px-3 py-2.5 text-sm">
                {!open.detail && !open.error && <span className="text-muted-foreground">Loading…</span>}
                {open.error && <span className="text-muted-foreground">{open.error}</span>}
                {open.detail && (
                  <>
                    <pre className="mb-2 whitespace-pre-wrap font-mono text-[12.5px]">{open.detail.message}</pre>
                    <p className="mb-1.5 text-xs text-muted-foreground">{open.detail.author} · {new Date(open.detail.date).toLocaleString()}</p>
                    <table className="w-full font-mono text-xs"><tbody>
                      {open.detail.files.map((f) => (
                        <tr key={f.file} className="border-t"><td className="py-0.5 pr-2 [overflow-wrap:anywhere]">{f.file}</td>
                          <td className="w-12 text-right text-done">{f.additions ?? 'bin'}</td><td className="w-12 text-right text-error">{f.deletions ?? ''}</td></tr>
                      ))}
                    </tbody></table>
                  </>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

export function Msg({ children, className }: { children: React.ReactNode; className?: string }) {
  return <p className={cn('p-4 text-sm text-muted-foreground', className)}>{children}</p>;
}
