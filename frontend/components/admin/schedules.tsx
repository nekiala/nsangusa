"use client";

import { tx } from "@/lib/i18n/staff";
import Link from "@/components/locale";
import { useCallback, useState } from "react";
import { api, type PublicationSchedule, type ScheduleStatus } from "@/lib/api";
import { AdminSection, dateLabel, Feedback, Pagination, useAction, useResource } from "./shared";

const statuses: ScheduleStatus[] = ["scheduled", "failed", "published", "cancelled"];

function localDateTime(instant: string) {
  const date = new Date(instant);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

export function CurrentPublicationPolicy() {
  const load = useCallback(() => api.admin.publicationPolicy(), []);
  const policy = useResource(load);
  return <section className="review-panel" aria-label={tx("Current publication policy")}>
    <h2>{tx("Current publication policy")}</h2>
    <p>{tx("Read-only backend configuration. Editors cannot change production publication policy here.")}</p>
    <button disabled={policy.loading} onClick={policy.refresh}>{tx("Refresh publication policy")}</button>
    <Feedback error={policy.error} />
    {policy.loading && <p role="status">{tx("Loading publication policy…")}</p>}
    {policy.data && <>
      <dl className="facts">
        <dt>{tx("Policy")}</dt><dd>{policy.data.policy}</dd>
        <dt>{tx("Confidence threshold")}</dt><dd>{Math.round(policy.data.confidenceThreshold * 100)}%</dd>
        <dt>{tx("Human publication allowed")}</dt><dd>{policy.data.humanPublicationAllowed ? "Yes" : "No"}</dd>
      </dl>
      <p>{policy.data.explanation}</p>
      <h3>{tx("Topic confidence rules")}</h3>
      {Object.keys(policy.data.topicRules).length ? <ul>{Object.entries(policy.data.topicRules).map(([topic, threshold]) => <li key={topic}>{topic}: {Math.round(threshold * 100)}%</li>)}</ul> : <p>{tx("No topic-specific confidence rules.")}</p>}
      <h3>{tx("Approved source accounts")}</h3>
      {policy.data.approvedSourceAccounts.length ? <ul>{policy.data.approvedSourceAccounts.map((account) => <li key={account} className="identifier">{account}</li>)}</ul> : <p>{tx("No approved source accounts configured.")}</p>}
    </>}
  </section>;
}

function ScheduleEntry({ schedule, onChange, locked }: { schedule: PublicationSchedule; onChange: (_message: string) => void; locked: boolean }) {
  const action = useAction();
  const [publishAt, setPublishAt] = useState(() => localDateTime(schedule.publishAt));
  const active = ["scheduled", "failed"].includes(schedule.status);
  const disabled = locked || action.busy || !active;
  return <li aria-label={`Publication schedule ${schedule.id}`}>
    <h2><Link href={`/admin/editor/${schedule.articleId}`}>Article {schedule.articleId}</Link></h2>
    <p><strong>{schedule.status}</strong> · Scheduled for <time dateTime={schedule.publishAt}>{dateLabel(schedule.publishAt)}</time></p>
    <p className="identifier">UTC: {schedule.publishAt} · Schedule version {schedule.version} · Approved article version {schedule.articleVersion ?? tx("Not recorded")}</p>
    <p>Attempts: {schedule.attemptCount} · Last attempt: {dateLabel(schedule.lastAttemptAt)} · Completed: {dateLabel(schedule.completedAt)}</p>
    {schedule.lastError && <p className="notice">Last publication error: {schedule.lastError}</p>}
    <details><summary>{tx("Schedule audit details")}</summary><dl className="facts">
      <dt>{tx("Schedule ID")}</dt><dd>{schedule.id}</dd><dt>{tx("Scheduled by")}</dt><dd>{schedule.scheduledBy}</dd>
      <dt>{tx("Updated by")}</dt><dd>{schedule.updatedBy}</dd><dt>{tx("Created")}</dt><dd>{dateLabel(schedule.createdAt)}</dd>
      <dt>{tx("Updated")}</dt><dd>{dateLabel(schedule.updatedAt)}</dd>
    </dl></details>
    {active && <>
      {schedule.articleVersion === null ? <p className="notice">The captured article version is unavailable. Cancel this schedule, review the article and create a new schedule; this entry cannot be retried.</p> : <form className="admin-form" onSubmit={(event) => {
        event.preventDefault();
        if (disabled) return;
        action.run(async () => {
          const date = new Date(publishAt);
          if (!publishAt || !Number.isFinite(date.getTime()) || date.getTime() <= Date.now()) throw new Error(tx("Choose a future publication date and time."));
          await api.admin.reschedulePublication(schedule.id, schedule.version, date.toISOString());
          onChange(tx("Publication rescheduled."));
        }, tx("Publication rescheduled."));
      }}>
        <label htmlFor={`reschedule-${schedule.id}`}>{tx("New publication time (your local time)")}</label>
        <input id={`reschedule-${schedule.id}`} type="datetime-local" required value={publishAt} disabled={disabled} onChange={(event) => setPublishAt(event.target.value)} />
        {publishAt && Number.isFinite(new Date(publishAt).getTime()) && <p>Selected instant: {new Date(publishAt).toISOString()}</p>}
        <button disabled={disabled || !publishAt}>{tx("Reschedule publication")}</button>
      </form>}
      {schedule.status === "failed" && schedule.articleVersion !== null && <p>{tx("Failed publications do not retry automatically. Reschedule to explicitly retry at a future time, or cancel.")}</p>}
      <p>Cancel this schedule before editing its article. Cancellation prevents this scheduled publication; it does not archive the article.</p>
      <button disabled={disabled} onClick={() => action.run(async () => {
        await api.admin.cancelPublication(schedule.id, schedule.version);
        onChange(tx("Publication schedule cancelled. Reopen the article to review or edit it."));
      }, tx("Publication schedule cancelled. Reopen the article to review or edit it."))}>{tx("Cancel publication schedule")}</button>
    </>}
    <Feedback error={action.error} status={action.status} />
  </li>;
}

export function PublicationSchedules() {
  const [status, setStatus] = useState<ScheduleStatus | undefined>("scheduled");
  const [page, setPage] = useState(0);
  const [notice, setNotice] = useState("");
  const load = useCallback(() => api.admin.publicationSchedules(status, page), [status, page]);
  const schedules = useResource(load);
  return <AdminSection title={tx("Publication schedules")} description={tx("Review queued publication, reschedule or cancel active entries, and inspect the current backend policy.")}>
    <div className="workflow-actions"><Link href="/admin/editor">{tx("Back to articles")}</Link><button disabled={schedules.loading} onClick={schedules.refresh}>{tx("Refresh schedule queue")}</button></div>
    <CurrentPublicationPolicy />
    <div className="admin-form"><label htmlFor="schedule-status">{tx("Schedule status")}</label><select id="schedule-status" value={status || ""} onChange={(event) => {
      setStatus(statuses.find((value) => value === event.target.value)); setPage(0);
    }}><option value="">{tx("All statuses")}</option>{statuses.map((value) => <option key={value}>{value}</option>)}</select></div>
    <Feedback error={schedules.error} status={notice} />
    {schedules.loading && <p role="status">{tx("Loading publication schedules…")}</p>}
    {!schedules.loading && schedules.data?.items.length === 0 && <p>{tx("No publication schedules in this queue.")}</p>}
    <ul className="queue-list">{schedules.data?.items.map((schedule) => <ScheduleEntry key={`${schedule.id}-${schedule.version}`} schedule={schedule} onChange={(message) => { setNotice(message); schedules.refresh(); }} locked={schedules.loading || !!schedules.error} />)}</ul>
    {schedules.data && <Pagination page={page} size={schedules.data.size} total={schedules.data.total} onPage={setPage} label="Schedule pages" />}
  </AdminSection>;
}
