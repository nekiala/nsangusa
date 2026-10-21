"use client";

import { tx } from "@/lib/i18n/staff";
import { useCallback, useState } from "react";
import { api, type ArticleSource } from "@/lib/api";
import { dateLabel, Feedback, SourceLink, useAction, useResource } from "./shared";

export function SourceDetails({ id }: { id: string }) {
  const load = useCallback(() => api.admin.source(id), [id]);
  const source = useResource(load);
  return <div className="source-detail">
    <Feedback error={source.error} />
    {source.loading && <p>{tx("Loading source…")}</p>}
    {source.data && <>
      <p><SourceLink url={source.data.canonicalUrl}>@{source.data.handle.replace(/^@/, "")} · {source.data.postId}</SourceLink> · {dateLabel(source.data.publishedAt)}</p>
      <p className="plain-text">{source.data.permittedText || tx("Source content is unavailable.")}</p>
      <p>Status: {source.data.status} · Reconciliation: {source.data.reconciliationState}</p>
      {source.data.complianceReason && <p>Compliance: {source.data.complianceReason}</p>}
      {!!source.data.relationships.length && <ul>{source.data.relationships.map((item) => <li key={item.id}>{item.relationshipType}: {item.relatedPostId}</li>)}</ul>}
    </>}
  </div>;
}

export function SourceSelector({ value, onChange, disabled }: { value: ArticleSource[]; onChange: (_sources: ArticleSource[]) => void; disabled: boolean }) {
  const sources = useResource(api.admin.sources);
  const [selected, setSelected] = useState("");
  const action = useAction();
  const add = async () => {
    if (!selected) return;
    await action.run(async () => {
      const source = await api.admin.source(selected);
      if (source.status !== "active" || source.excludedAt || source.contentDeletedAt) throw new Error(tx("This source is not eligible. Refresh and choose an active source."));
      if (!value.some((item) => item.sourcePostId === source.id)) {
        onChange([...value, { sourcePostId: source.id, account: source.handle, postId: source.postId, url: source.canonicalUrl, publishedAt: source.publishedAt }]);
      }
      setSelected("");
    }, tx("Source added."));
  };
  return <fieldset><legend>{tx("Sources and attribution")}</legend>
    <p>{tx("Choose ingested sources. Existing attributions are preserved until you explicitly remove them.")}</p>
    <label htmlFor="source-select">{tx("Available source")}</label>
    <select id="source-select" value={selected} disabled={disabled || action.busy} onChange={(event) => setSelected(event.target.value)}>
      <option value="">{tx("Select an active source")}</option>
      {sources.data?.map((source) => <option key={source.id} value={source.id} disabled={value.some((item) => item.sourcePostId === source.id)}>@{source.handle.replace(/^@/, "")} · {source.postId} · {dateLabel(source.publishedAt)}</option>)}
    </select>
    <div className="workflow-actions"><button type="button" disabled={disabled || action.busy || !selected} onClick={add}>{tx("Add selected source")}</button><button type="button" disabled={sources.loading} onClick={sources.refresh}>{tx("Refresh sources")}</button></div>
    {sources.data?.length === 0 && <p>{tx("No active sources. Ask an administrator to add an account and ingest a permitted post.")}</p>}
    <Feedback error={sources.error || action.error} status={action.status} />
    <ul className="source-list">{value.map((source) => <li key={source.sourcePostId}>
      <SourceLink url={source.url}>{source.account} · {source.postId}</SourceLink> · {dateLabel(source.publishedAt)}
      <details><summary>Inspect source {source.postId}</summary><SourceDetails id={source.sourcePostId} /></details>
      <button type="button" disabled={disabled} onClick={() => onChange(value.filter((item) => item.sourcePostId !== source.sourcePostId))}>Remove source {source.postId}</button>
    </li>)}</ul>
    {!value.length && <p>{tx("At least one source is required to save.")}</p>}
  </fieldset>;
}
