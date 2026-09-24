"use client";

import { useCallback, useState } from "react";
import { api, type RevisionComparison, type RevisionView } from "@/lib/api";
import { dateLabel, Feedback, Pagination, useAction, useResource } from "./shared";
import { ArticleContentView } from "@/components/article-content";
import { contentError, type ArticleContent } from "@/lib/article-content";

function storedValue(revision: RevisionView, field: string): unknown {
  const snapshot = revision.snapshot as Record<string, unknown> | null;
  if (snapshot && Object.hasOwn(snapshot, field)) return snapshot[field];
  if (["headline", "summary", "body"].includes(field)) return revision[field as "headline" | "summary" | "body"];
  return undefined;
}

function displayValue(value: unknown) {
  if (value === undefined) return "Not stored in this revision";
  if (value === null) return "None";
  return typeof value === "string" ? value || "(empty)" : JSON.stringify(value, null, 2);
}

function RevisionValue({ revision, field }: { revision: RevisionView; field: string }) {
  const value = storedValue(revision, field);
  if (field === "content" && value != null && !contentError(value)) {
    return <ArticleContentView content={value as ArticleContent} />;
  }
  if (field === "body" && typeof value === "string") {
    return <ArticleContentView body={value} />;
  }
  return <pre className="provenance-json">{displayValue(value)}</pre>;
}

function RevisionFacts({ revision }: { revision: RevisionView }) {
  return <>
    <p>Revision {revision.revisionNumber} · {revision.reason} · {dateLabel(revision.createdAt)}</p>
    <p className="identifier">Actor: {revision.actorId || "Not recorded"}</p>
    {!revision.snapshot && <p className="notice">Full snapshot unavailable. Only stored content is shown; missing metadata is unknown, not unchanged.</p>}
  </>;
}

export function RevisionHistory({ articleId }: { articleId: string }) {
  const [page, setPage] = useState(0);
  const load = useCallback(() => api.admin.revisions(articleId, page), [articleId, page]);
  const revisions = useResource(load);
  const action = useAction();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [comparison, setComparison] = useState<RevisionComparison | null>(null);
  const choose = (side: "from" | "to", value: string) => {
    if (side === "from") setFrom(value); else setTo(value);
    setComparison(null);
  };
  return <section className="review-panel" aria-label="Revision history">
    <h2>Revision history</h2>
    <p>Immutable editorial snapshots. Comparing revisions does not change or restore an article.</p>
    <button disabled={revisions.loading} onClick={revisions.refresh}>Refresh revisions</button>
    <Feedback error={revisions.error || action.error} />
    {revisions.loading && <p role="status">Loading revisions…</p>}
    {revisions.data?.items.length === 0 && <p>No stored revisions.</p>}
    <ul className="queue-list">{revisions.data?.items.map((revision) => <li key={revision.id}>
      <RevisionFacts revision={revision} />
      <div className="workflow-actions">
        <button disabled={action.busy} onClick={() => choose("from", String(revision.revisionNumber))}>Compare from revision {revision.revisionNumber}</button>
        <button disabled={action.busy} onClick={() => choose("to", String(revision.revisionNumber))}>Compare to revision {revision.revisionNumber}</button>
      </div>
      <details><summary>Stored revision {revision.revisionNumber}</summary>
        <h3>Stored article body</h3>
        {!revision.snapshot?.content && <p>Structured content was not stored in this revision. Showing only its stored plain-text body.</p>}
        <ArticleContentView content={revision.snapshot?.content} body={revision.snapshot?.body ?? revision.body ?? ""} />
        <pre className="provenance-json">{JSON.stringify(revision.snapshot || {
          headline: revision.headline, summary: revision.summary, body: revision.body
        }, null, 2)}</pre>
        {revision.aiGenerationResult && <><h3>Stored AI generation result</h3><pre className="provenance-json">{JSON.stringify(revision.aiGenerationResult, null, 2)}</pre></>}
      </details>
    </li>)}</ul>
    {revisions.data && <Pagination label="Revision pages" page={page} size={revisions.data.size} total={revisions.data.total} onPage={setPage} />}
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault();
      action.run(async () => {
        if (!Number.isSafeInteger(Number(from)) || !Number.isSafeInteger(Number(to)) || Number(from) < 1 || Number(to) < 1 || Number(from) === Number(to)) throw new Error("Choose two different revision numbers.");
        setComparison(await api.admin.compareRevisions(articleId, Number(from), Number(to)));
      }, "Revisions compared.");
    }}>
      <h3>Compare stored revisions</h3><p>Select records on any page, or enter their revision numbers.</p>
      <label htmlFor="revision-from">From revision number</label><input id="revision-from" type="number" min="1" step="1" required value={from} disabled={action.busy} onChange={(event) => choose("from", event.target.value)} />
      <label htmlFor="revision-to">To revision number</label><input id="revision-to" type="number" min="1" step="1" required value={to} disabled={action.busy} onChange={(event) => choose("to", event.target.value)} />
      <button disabled={action.busy || !from || !to || Number(from) === Number(to)}>Compare revisions</button>
    </form>
    {comparison && <section aria-label="Revision comparison">
      <h3>Revision {comparison.from.revisionNumber} → {comparison.to.revisionNumber}</h3>
      <div className="revision-columns"><div><RevisionFacts revision={comparison.from} /></div><div><RevisionFacts revision={comparison.to} /></div></div>
      {!comparison.changedFields.length && <p>No differences in comparable stored fields.</p>}
      {comparison.changedFields.map((field) => <section className="revision-change" key={field}><h4>{field}</h4>
        <div className="revision-columns">
          <div><h5>From revision {comparison.from.revisionNumber}</h5><RevisionValue revision={comparison.from} field={field} /></div>
          <div><h5>To revision {comparison.to.revisionNumber}</h5><RevisionValue revision={comparison.to} field={field} /></div>
        </div>
      </section>)}
    </section>}
  </section>;
}
