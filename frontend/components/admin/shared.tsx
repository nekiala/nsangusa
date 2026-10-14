"use client";

import { tx } from "@/lib/i18n/staff";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "@/lib/api";

export function errorNotice(error: unknown) {
  if (error instanceof ApiError) return `${error.problem.title}: ${error.problem.detail}`;
  return error instanceof Error ? error.message : tx("The request could not be completed.");
}

export function useResource<T>(load: () => Promise<T>) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadedFor, setLoadedFor] = useState<(() => Promise<T>) | null>(null);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => { setLoading(true); setRevision((value) => value + 1); }, []);
  useEffect(() => {
    let active = true;
    load().then((result) => {
      if (active) { setData(result); setError(""); }
    }).catch((failure: unknown) => {
      if (active) setError(errorNotice(failure));
    }).finally(() => { if (active) { setLoading(false); setLoadedFor(() => load); } });
    return () => { active = false; };
  }, [load, revision]);
  return { data, error, loading: loading || loadedFor !== load, refresh };
}

export function useAction() {
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  async function run(work: () => Promise<unknown>, success: string) {
    if (running.current) return false;
    running.current = true;
    setBusy(true); setStatus(""); setError("");
    try { await work(); setStatus(success); return true; }
    catch (failure) { setError(errorNotice(failure)); return false; }
    finally { running.current = false; setBusy(false); }
  }
  return { busy, status, error, run };
}

export function AdminSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <section className="admin-content"><p className="eyebrow">{tx("Protected workspace")}</p><h1>{title}</h1><p>{description}</p>{children}</section>;
}

export function Feedback({ error, status }: { error?: string; status?: string }) {
  return <>{error && <p role="alert" className="notice">{error}</p>}{status && <p role="status" className="notice">{status}</p>}</>;
}

export function Pagination({ page, size, total, onPage, label = tx("Queue pages") }: { page: number; size: number; total: number; onPage: (_page: number) => void; label?: string }) {
  return <nav className="workflow-actions" aria-label={label}>
    <button disabled={page === 0} onClick={() => onPage(page - 1)}>{tx("Previous page")}</button>
    <span>Page {page + 1} · {total} total</span>
    <button disabled={(page + 1) * size >= total} onClick={() => onPage(page + 1)}>{tx("Next page")}</button>
  </nav>;
}

export function dateLabel(value: string | null) {
  return value ? new Date(value).toLocaleString() : tx("Not yet");
}

export function safeHttpUrl(value: string) {
  try { const url = new URL(value); return ["https:", "http:"].includes(url.protocol) ? url.href : undefined; }
  catch { return undefined; }
}

export function SourceLink({ url, children }: { url: string; children: ReactNode }) {
  const href = safeHttpUrl(url);
  return href ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children} (invalid source URL)</span>;
}
