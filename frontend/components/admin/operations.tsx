"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useAuthenticatedUser } from "@/components/authenticated-area";
import { api } from "@/lib/api";
import { operationsApi, type AuditFilters, type Replay } from "@/lib/operations-api";
import { AdminSection, Feedback, Pagination, dateLabel, useAction, useResource } from "./shared";

export function EditorialDashboard() {
  const user = useAuthenticatedUser();
  const editorial = user?.roles.some((role) => ["EDITOR", "ADMINISTRATOR"].includes(role));
  return <AdminSection title="Editorial dashboard" description="Open the queues and controls available to your current role.">
    {editorial && <EditorialCounts />}
    {user?.roles.some((role) => ["MODERATOR", "ADMINISTRATOR"].includes(role)) && <section className="review-panel"><h2>Community review</h2><Link href="/admin/comments">Review comments and abuse reports</Link></section>}
    {user?.roles.includes("ADMINISTRATOR") && <section className="review-panel"><h2>Publication administration</h2><p>Manage subscriptions, users, AI configuration and audited operational recovery.</p><div className="workflow-actions"><Link href="/admin/newsletter">Newsletter</Link><Link href="/admin/users">Users and roles</Link><Link href="/admin/ai">AI configuration</Link><Link href="/admin/operations">Operational health</Link></div></section>}
  </AdminSection>;
}

function EditorialCounts() {
  const load = useCallback(async () => {
    const [drafts, candidates, schedules] = await Promise.all([api.admin.articles("AWAITING_REVIEW", 0, 1), api.admin.candidates(0, 1), api.admin.publicationSchedules("scheduled", 0, 1)]);
    return { drafts: drafts.total, candidates: candidates.total, schedules: schedules.total };
  }, []);
  const data = useResource(load);
  return <section className="review-panel"><h2>Editorial queues</h2><button onClick={data.refresh} disabled={data.loading}>Refresh editorial dashboard</button><Feedback error={data.error} />
    {data.data && <dl className="facts"><dt>Awaiting review</dt><dd>{data.data.drafts}</dd><dt>Story candidates</dt><dd>{data.data.candidates}</dd><dt>Scheduled publications</dt><dd>{data.data.schedules}</dd></dl>}
    <div className="workflow-actions"><Link href="/admin/editor">Review articles</Link><Link href="/admin/candidates">Review story candidates</Link><Link href="/admin/schedules">Manage schedules</Link></div>
  </section>;
}

export function OperationsHealth() {
  const load = useCallback(() => operationsApi.summary(), []);
  const summary = useResource(load);
  return <AdminSection title="Operations summary" description="Database-backed workflow inventory. These counts do not certify Kafka connectivity or infrastructure availability.">
    <button onClick={summary.refresh} disabled={summary.loading}>Refresh operations summary</button><Feedback error={summary.error} />
    {summary.loading && <p role="status">Loading operational inventory...</p>}
    {summary.data && <><p>{summary.data.details}</p><p>Checked {dateLabel(summary.data.checkedAt)}</p><dl className="facts">
      <dt>Unpublished outbox events</dt><dd>{summary.data.workflow.pendingOutbox}</dd><dt>Oldest pending event</dt><dd>{dateLabel(summary.data.workflow.oldestPendingOutboxAt)}</dd><dt>Pending replay jobs</dt><dd>{summary.data.workflow.pendingReplays}</dd>
      {Object.entries(summary.data.workflow.failedEvents).map(([status, total]) => <div key={status}><dt>Failed events: {status}</dt><dd>{total}</dd></div>)}
    </dl></>}
    <div className="workflow-actions"><Link href="/admin/events">Inspect failures and replay history</Link><Link href="/admin/audit">Inspect audit records</Link></div>
    <p>Use the deployment&apos;s Actuator, consumer-lag metrics and observability backend for dependency diagnostics. No provider credentials or event payloads are exposed here.</p>
  </AdminSection>;
}

export function AuditLogs() {
  const [filters, setFilters] = useState<AuditFilters>({});
  const [page, setPage] = useState(0);
  const load = useCallback(() => operationsApi.audit(filters, page), [filters, page]);
  const result = useResource(load);
  return <AdminSection title="Audit logs" description="Search permitted audit metadata. The default window is seven days; explicit ranges are limited to ninety days.">
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault(); const data = new FormData(event.currentTarget);
      const next: AuditFilters = {};
      for (const key of ["action", "targetType", "actorId", "targetId"] as const) { const value = String(data.get(key) || "").trim(); if (value) next[key] = value; }
      for (const key of ["from", "to"] as const) { const value = String(data.get(key) || ""); if (value) next[key] = new Date(value).toISOString(); }
      setFilters(next); setPage(0);
    }}>
      <label>Action<input name="action" maxLength={100} placeholder="ARTICLE_PUBLISHED" /></label>
      <label>Target type<input name="targetType" maxLength={100} placeholder="article" /></label>
      <label>Actor ID (optional)<input name="actorId" /></label><label>Target ID (optional)<input name="targetId" /></label>
      <label>From (local time)<input name="from" type="datetime-local" /></label><label>To (local time)<input name="to" type="datetime-local" /></label>
      <button disabled={result.loading}>Search audit records</button>
    </form>
    <Feedback error={result.error} /><button onClick={result.refresh} disabled={result.loading}>Refresh audit records</button>
    {!result.loading && result.data?.total === 0 && <p>No audit records match these filters.</p>}
    <ul className="queue-list">{result.data?.items.map((record) => <li key={record.id}><h2>{record.action}</h2><p>{dateLabel(record.occurredAt)} · {record.targetType}</p>
      <dl className="facts"><dt>Actor</dt><dd className="identifier">{record.actorId || "System"}</dd><dt>Target</dt><dd className="identifier">{record.targetId || "Not applicable"}</dd></dl>
      <details><summary>Recorded metadata</summary><pre className="plain-text">{JSON.stringify(record.metadata, null, 2)}</pre></details>
    </li>)}</ul>
    {result.data && <Pagination page={page} size={result.data.size} total={result.data.total} onPage={setPage} label="Audit pages" />}
  </AdminSection>;
}

export function FailedEvents() {
  const [status, setStatus] = useState("eligible");
  const [page, setPage] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<Replay | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [inspected, setInspected] = useState<string | null>(null);
  const load = useCallback(() => operationsApi.failures(status || undefined, page), [status, page]);
  const historyLoad = useCallback(() => operationsApi.replays(historyPage), [historyPage]);
  const failures = useResource(load);
  const history = useResource(historyLoad);
  const action = useAction();
  return <AdminSection title="Failed events and replay" description="Inspect bounded failure metadata, preview an exact selection, then explicitly confirm replay. Source suppression and consumer idempotency still apply.">
    <label>Failure status<select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); setSelected([]); setPreview(null); }}><option value="">All statuses</option>{["eligible", "poison", "replay_pending", "replayed", "replay_failed", "suppressed"].map((value) => <option key={value}>{value}</option>)}</select></label>
    <button disabled={failures.loading || action.busy} onClick={failures.refresh}>Refresh failed events</button>
    <Feedback error={failures.error || action.error} status={action.status} />
    {!failures.loading && failures.data?.total === 0 && <p>No failed events match this queue.</p>}
    <ul className="queue-list">{failures.data?.items.map((failure) => <li key={failure.id}>
      <h2>{failure.eventType || "Unrecognized event"}</h2><p>{failure.status} · {dateLabel(failure.failedAt)}</p>
      <p>{failure.originalTopic} / partition {failure.originalPartition} / offset {failure.originalOffset} · Attempts {failure.deliveryAttempt}</p>
      <p className="plain-text">{failure.exceptionClass}: {failure.exceptionMessage}</p>
      {failure.poisonMessage && <p className="notice">Poison message: verify its schema and safety before selecting replay.</p>}
      <label className="check"><input type="checkbox" checked={selected.includes(failure.id)} disabled={action.busy || !["eligible", "poison", "replay_failed"].includes(failure.status) || selected.length >= 100 && !selected.includes(failure.id)} onChange={(event) => { setSelected(event.target.checked ? [...selected, failure.id] : selected.filter((id) => id !== failure.id)); setPreview(null); setReviewed(false); }} />Select event {failure.id}</label>
    </li>)}</ul>
    {failures.data && <Pagination page={page} size={failures.data.size} total={failures.data.total} onPage={setPage} label="Failure pages" />}
    <form className="admin-form" onChange={() => { setPreview(null); setReviewed(false); }} onSubmit={(event) => {
      event.preventDefault(); const data = new FormData(event.currentTarget);
      action.run(async () => {
        const value = await operationsApi.preview(selected, String(data.get("reason")), Number(data.get("rate")), data.get("poison") === "on");
        setPreview(value); setInspected(value.id); setReviewed(false); history.refresh();
      }, "Dry run complete. Inspect its records before confirming.");
    }}>
      <h2>Preview selected messages</h2><p>{selected.length} selected; maximum 100. Previews expire after fifteen minutes.</p>
      <label>Replay reason<textarea name="reason" required maxLength={500} /></label>
      <label>Messages per second<input name="rate" type="number" min={1} max={20} defaultValue={1} required /></label>
      <label className="check"><input name="poison" type="checkbox" />Include selected poison messages after schema review</label>
      <button disabled={action.busy || !selected.length}>Run replay dry run</button>
    </form>
    {preview && <section className="review-panel" aria-label="Replay confirmation"><h2>Confirm reviewed replay</h2><p>{preview.candidateCount} candidates; {preview.blockedCount} blocked; {preview.messagesPerSecond} message(s) per second.</p>
      <label className="check"><input type="checkbox" checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} />I reviewed these exact messages and accept the replay effects.</label>
      <button disabled={action.busy || !reviewed || preview.candidateCount <= preview.blockedCount} onClick={() => action.run(async () => {
        const value = await operationsApi.confirm(preview.id); setInspected(value.id); setPreview(null); setSelected([]); failures.refresh(); history.refresh();
      }, "Replay request accepted. Inspect its progress and records; acceptance is not delivery confirmation.")}>Confirm replay</button>
    </section>}
    <section className="review-panel"><h2>Replay history</h2><button onClick={history.refresh} disabled={history.loading}>Refresh replay history</button><Feedback error={history.error} />
      <ul>{history.data?.items.map((replay) => <li key={replay.id}><button onClick={() => { setInspected(replay.id); setPreview(null); }}>{replay.dryRun ? "Inspect dry run" : "Inspect replay"} · {dateLabel(replay.requestedAt)}</button><p>{replay.reason} · {replay.status}</p></li>)}</ul>
      {history.data && <Pagination page={historyPage} size={history.data.size} total={history.data.total} onPage={setHistoryPage} label="Replay history pages" />}
    </section>
    {inspected && <ReplayInspection key={inspected} id={inspected} />}
  </AdminSection>;
}

function ReplayInspection({ id }: { id: string }) {
  const load = useCallback(async () => { const [replay, records] = await Promise.all([operationsApi.replay(id), operationsApi.records(id)]); return { replay, records }; }, [id]);
  const result = useResource(load);
  return <section className="review-panel" aria-label="Replay details"><h2>Replay details</h2><button onClick={result.refresh} disabled={result.loading}>Refresh replay details</button><Feedback error={result.error} />
    {result.data && <><p>{result.data.replay.status} · Replayed {result.data.replay.replayedCount} · Blocked {result.data.replay.blockedCount}</p>
      <ul>{result.data.records.map((record) => <li key={record.id}><p>{record.outcome}: {record.detail}</p><p className="identifier">{record.failedEventId} · {record.originalTopic} · {dateLabel(record.occurredAt)}</p></li>)}</ul></>}
  </section>;
}
