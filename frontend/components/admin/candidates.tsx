"use client";

import { tx } from "@/lib/i18n/staff";
import Link from "@/components/locale";
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
  return <AdminSection title={tx("Story candidates")} description={tx("Follow ingested stories through analysis and drafting. Regeneration creates a new candidate and never overwrites an existing article.")}>
    <div className="workflow-actions"><Link href="/admin/editor">{tx("View articles")}</Link><button onClick={candidates.refresh} disabled={candidates.loading || action.busy}>{tx("Refresh candidate queue")}</button></div>
    <Feedback error={candidates.error || action.error} status={action.status} />
    {candidates.loading && <p role="status">{tx("Loading candidates…")}</p>}
    {candidates.data?.items.length === 0 && <p>{tx("No candidates yet. Ingested sources will appear after story processing.")}</p>}
    <ul className="queue-list">{candidates.data?.items.map((candidate) => <li key={candidate.id}>
      <h2>{candidate.topic}</h2><p>{candidate.status} · {dateLabel(candidate.createdAt)} · {candidate.sourceIds.length} source(s)</p>
      <p className="identifier">Candidate: {candidate.id}</p>
      <div className="workflow-actions">
        <button aria-expanded={selected === candidate.id} onClick={() => setSelected(selected === candidate.id ? null : candidate.id)}>{selected === candidate.id ? tx("Hide candidate review") : tx("Review candidate")}</button>
        {candidate.articleId && <Link href={`/admin/editor/${candidate.articleId}`}>{tx("Open draft article")}</Link>}
        <button disabled={action.busy || !["drafted", "blocked_safety"].includes(candidate.status)} onClick={() => action.run(async () => {
          const result = await api.admin.regenerateCandidate(candidate.id); setPage(0); setSelected(result.id); candidates.refresh();
        }, tx("New candidate requested. The original candidate and article are preserved."))}>{tx("Regenerate candidate")}</button>
      </div>
      {selected === candidate.id && <>
        <h3>{tx("Candidate sources")}</h3>{candidate.sourceIds.map((id) => <SourceDetails key={id} id={id} />)}
        <AnalysisReview candidateId={candidate.id} />
      </>}
    </li>)}</ul>
    {candidates.data && <Pagination page={page} size={candidates.data.size} total={candidates.data.total} onPage={(value) => { setPage(value); setSelected(null); }} />}
  </AdminSection>;
}
