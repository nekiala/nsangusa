"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { api } from "@/lib/api";
import { AdminSection, dateLabel, Feedback, Pagination, useAction, useResource } from "./shared";
import { AnalysisReview } from "./analysis";
import { SourceDetails } from "./sources";

export function CandidateQueue() {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const load = useCallback(() => api.admin.candidates(page), [page]);
  const candidates = useResource(load);
  const action = useAction();
  return <AdminSection title="Story candidates" description="Follow ingested stories through analysis and drafting. Regeneration creates a new candidate and never overwrites an existing article.">
    <div className="workflow-actions"><Link href="/admin/editor">View articles</Link><button onClick={candidates.refresh} disabled={candidates.loading || action.busy}>Refresh candidate queue</button></div>
    <Feedback error={candidates.error || action.error} status={action.status} />
    {candidates.loading && <p role="status">Loading candidates…</p>}
    {candidates.data?.items.length === 0 && <p>No candidates yet. Ingested sources will appear after story processing.</p>}
    <ul className="queue-list">{candidates.data?.items.map((candidate) => <li key={candidate.id}>
      <h2>{candidate.topic}</h2><p>{candidate.status} · {dateLabel(candidate.createdAt)} · {candidate.sourceIds.length} source(s)</p>
      <p className="identifier">Candidate: {candidate.id}</p>
      <div className="workflow-actions">
        <button aria-expanded={selected === candidate.id} onClick={() => setSelected(selected === candidate.id ? null : candidate.id)}>{selected === candidate.id ? "Hide candidate review" : "Review candidate"}</button>
        {candidate.articleId && <Link href={`/admin/editor/${candidate.articleId}`}>Open draft article</Link>}
        <button disabled={action.busy || !["drafted", "blocked_safety"].includes(candidate.status)} onClick={() => action.run(async () => {
          const result = await api.admin.regenerateCandidate(candidate.id); setPage(0); setSelected(result.id); candidates.refresh();
        }, "New candidate requested. The original candidate and article are preserved.")}>Regenerate candidate</button>
      </div>
      {selected === candidate.id && <>
        <h3>Candidate sources</h3>{candidate.sourceIds.map((id) => <SourceDetails key={id} id={id} />)}
        <AnalysisReview candidateId={candidate.id} />
      </>}
    </li>)}</ul>
    {candidates.data && <Pagination page={page} size={candidates.data.size} total={candidates.data.total} onPage={(value) => { setPage(value); setSelected(null); }} />}
  </AdminSection>;
}
