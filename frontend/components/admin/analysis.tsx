"use client";

import { useCallback } from "react";
import { api, type AiRequest } from "@/lib/api";
import { dateLabel, Feedback, useResource } from "./shared";

function text(value: unknown) { return typeof value === "string" ? value : ""; }
function strings(value: unknown): string[] { return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []; }
function record(value: unknown): Record<string, unknown> | null { return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null; }

function RequestResult({ request }: { request: AiRequest }) {
  const result = request.result;
  if (!result) {
    const message = request.status === "redacted"
      ? "Result redacted because source content or eligibility changed. Request provenance is retained."
      : ["failed", "blocked"].includes(request.status)
        ? "No result is available for this request."
        : request.operation === "safety" && request.status === "completed"
          ? "Safety check completed. Flags are included in the analysis and draft warnings."
          : "No result yet.";
    return <p>{message}</p>;
  }
  const claims: unknown[] = Array.isArray(result.claims) ? result.claims : [];
  return <>
    {text(result.analysis) && <p className="plain-text">{text(result.analysis)}</p>}
    {typeof result.confidence === "number" && <p>Analysis confidence: {Math.round(result.confidence * 100)}%</p>}
    {!!strings(result.warnings).length && <div><h4>Warnings</h4><ul>{strings(result.warnings).map((warning, index) => <li key={index}>{warning}</li>)}</ul></div>}
    {!!claims.length && <div><h4>Claims and supporting evidence</h4><ul>{claims.map((value, index) => {
      const claim = record(value);
      return claim ? <li key={index}><p>{text(claim.text)} — <strong>{text(claim.classification)}</strong></p><p>Supporting sources: {strings(claim.supportingSourceIds).join(", ") || "None supplied"}</p></li> : null;
    })}</ul></div>}
    {text(result.headline) && <p>Generated headline: {text(result.headline)}</p>}
    <details><summary>Full structured result</summary><pre className="provenance-json">{JSON.stringify(result, null, 2)}</pre></details>
  </>;
}

export function AnalysisReview({ candidateId }: { candidateId: string }) {
  const load = useCallback(() => api.admin.aiRequests(candidateId), [candidateId]);
  const requests = useResource(load);
  return <section className="review-panel" aria-label="Analysis and provenance">
    <h2>Analysis and provenance</h2><p>Model output is untrusted editorial material. Review claims against the original sources.</p>
    <button type="button" onClick={requests.refresh} disabled={requests.loading}>Refresh analysis</button>
    <Feedback error={requests.error} />
    {requests.loading && <p>Loading AI requests…</p>}
    {requests.data?.length === 0 && <p>No AI requests yet. This candidate may still be queued.</p>}
    {requests.data?.map((request) => <article key={request.id} className="review-panel">
      <h3>{request.operation} · {request.status}</h3>
      <dl className="facts"><dt>Provider / model</dt><dd>{request.provider} / {request.model}</dd><dt>Prompt version</dt><dd>{request.promptVersion}</dd><dt>Requested</dt><dd>{dateLabel(request.createdAt)}</dd><dt>Completed</dt><dd>{dateLabel(request.completedAt)}</dd><dt>Tokens (input / output)</dt><dd>{request.inputTokens ?? "Unknown"} / {request.outputTokens ?? "Unknown"}</dd></dl>
      {request.errorCode && <p role="alert">Provider error: {request.errorCode}</p>}
      <RequestResult request={request} />
    </article>)}
  </section>;
}
