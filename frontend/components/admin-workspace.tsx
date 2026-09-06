"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import { ApiError, api, type ApiArticle, type ArticleCommand } from "@/lib/api";

type Props = { section: string };
const actions = ["approve", "publish", "schedule", "reject", "unpublish", "restore", "archive"] as const;
const source = { sourcePostId: "00000000-0000-4000-8000-000000000001", account: "@source", postId: "example-post", url: "https://example.invalid/source", publishedAt: "2026-09-01T12:00:00.000Z" };

function notice(error: unknown) {
  if (error instanceof ApiError) return `${error.problem.title}: ${error.problem.detail}`;
  return error instanceof Error ? error.message : "The request could not be completed.";
}
function articleCommand(form: HTMLFormElement): ArticleCommand {
  const data = new FormData(form);
  return {
    headline: String(data.get("headline")), summary: String(data.get("summary")), body: String(data.get("body")),
    editorialContext: String(data.get("editorialContext")) || null, seoTitle: String(data.get("seoTitle")),
    seoDescription: String(data.get("seoDescription")), slugSuggestion: String(data.get("slugSuggestion")),
    topic: String(data.get("topic")), tags: String(data.get("tags")).split(",").map((tag) => tag.trim()).filter(Boolean),
    sources: [{ ...source, account: String(data.get("sourceAccount")) || source.account, url: String(data.get("sourceUrl")) || source.url }],
    commentsEnabled: data.get("commentsEnabled") === "on"
  };
}

export function AdminWorkspace({ section }: Props) {
  const [article, setArticle] = useState<ApiArticle | null>(null);
  const [articleId, setArticleId] = useState("");
  const [status, setStatus] = useState("");
  const [summary, setSummary] = useState("");
  const [busy, setBusy] = useState(false);
  const run = async (work: () => Promise<unknown>, success?: string) => {
    setBusy(true); setStatus("");
    try { await work(); if (success) setStatus(success); } catch (error) { setStatus(notice(error)); } finally { setBusy(false); }
  };
  const loadArticle = () => run(async () => { const result = await api.admin.article(articleId); setArticle(result); setStatus(`Loaded ${result.headline}.`); });
  const transition = (action: typeof actions[number]) => run(async () => {
    if (!articleId) throw new Error("Enter an article ID first.");
    if (action === "approve") await api.admin.approveArticle(articleId);
    if (action === "publish") await api.admin.publishArticle(articleId);
    if (action === "schedule") await api.admin.scheduleArticle(articleId, new Date(Date.now() + 86_400_000).toISOString());
    if (action === "reject") await api.admin.rejectArticle(articleId);
    if (action === "unpublish") await api.admin.unpublishArticle(articleId);
    if (action === "restore") await api.admin.restoreArticle(articleId);
    if (action === "archive") await api.admin.archiveArticle(articleId);
    const result = await api.admin.article(articleId);
    setArticle(result);
    setStatus(`${action[0].toUpperCase()}${action.slice(1)} request completed. Current state: ${result.state}.`);
  });

  if (section === "dashboard") return <AdminSection title="Operations summary" description="The implemented administrative health endpoint is available to administrators.">
    <button disabled={busy} onClick={() => run(async () => { const value = await api.admin.operationsSummary(); setSummary(`${value.status}: ${value.details} Checked ${new Date(value.checkedAt).toLocaleString()}.`); })}>Refresh operations summary</button>
    <p role="status" className="notice">{status || summary || "No summary loaded."}</p>
  </AdminSection>;
  if (section === "comments") return <Moderation run={run} status={status} busy={busy} />;
  if (section === "handles") return <XAccounts run={run} status={status} busy={busy} />;
  return <AdminSection title="Article editor" description="Create or load one article by ID. The backend currently exposes no administrative article list or queue.">
    <form className="admin-form" onSubmit={(event) => { event.preventDefault(); loadArticle(); }}>
      <label htmlFor="article-id">Article ID</label><input id="article-id" value={articleId} onChange={(event) => setArticleId(event.target.value)} placeholder="UUID" />
      <button disabled={busy}>Load article</button>
    </form>
    <form key={article?.id || "new"} className="admin-form" onSubmit={(event) => { event.preventDefault(); const form = event.currentTarget; run(async () => {
      const command = articleCommand(form);
      if (article) {
        await api.admin.editArticle(article.id, article.version, command);
        const result = await api.admin.article(article.id);
        setArticle(result);
        setStatus(`Article saved and returned to review. Current state: ${result.state}.`);
      } else {
        const created = await api.admin.createArticle(command);
        const result = await api.admin.article(created.id);
        setArticleId(created.id);
        setArticle(result);
        setStatus(`Article created. ID: ${created.id}`);
      }
    }); }}>
      <h2>{article ? "Edit article" : "Create article"}</h2>
      <label htmlFor="headline">Headline</label><input id="headline" name="headline" required maxLength={300} defaultValue={article?.headline} />
      <label htmlFor="summary">Summary</label><textarea id="summary" name="summary" required maxLength={2000} defaultValue={article?.summary} />
      <label htmlFor="body">Body</label><textarea id="body" name="body" required maxLength={100000} defaultValue={article?.body} />
      <label htmlFor="topic">Topic</label><input id="topic" name="topic" required maxLength={100} defaultValue={article?.topic || "Ideas"} />
      <label htmlFor="tags">Tags, separated by commas</label><input id="tags" name="tags" required defaultValue={article?.tags.join(", ") || "News"} />
      <label htmlFor="seo-title">SEO title</label><input id="seo-title" name="seoTitle" required maxLength={300} defaultValue={article?.headline} />
      <label htmlFor="seo-description">SEO description</label><input id="seo-description" name="seoDescription" required maxLength={500} defaultValue={article?.summary} />
      <label htmlFor="slug">Slug suggestion</label><input id="slug" name="slugSuggestion" required maxLength={250} defaultValue={article?.slug} />
      <label htmlFor="context">Editorial context</label><textarea id="context" name="editorialContext" maxLength={20000} defaultValue={article?.editorialContext || ""} />
      <fieldset><legend>Required source</legend><label htmlFor="source-account">Account</label><input id="source-account" name="sourceAccount" defaultValue={article?.sources[0]?.account || "@source"} required /><label htmlFor="source-url">URL</label><input id="source-url" name="sourceUrl" type="url" defaultValue={article?.sources[0]?.url || source.url} required /></fieldset>
      <label className="check"><input name="commentsEnabled" type="checkbox" defaultChecked={article?.commentsEnabled ?? true} /> Enable comments</label>
      <button disabled={busy}>{article ? "Save article" : "Create article"}</button>
    </form>
    {articleId && <div className="workflow-actions" aria-label="Editorial workflow">{actions.map((action) => <button key={action} type="button" disabled={busy} onClick={() => transition(action)}>{action}</button>)}</div>}
    <p role="status" className="notice">{status || (article ? `Current state: ${article.state}` : "Create an article or load one by ID.")}</p>
  </AdminSection>;
}

function AdminSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <section className="admin-content"><p className="eyebrow">Protected workspace</p><h1>{title}</h1><p>{description}</p>{children}</section>;
}
function Moderation({ run, status, busy }: { run: (_work: () => Promise<unknown>, _success?: string) => Promise<void>; status: string; busy: boolean }) {
  return <AdminSection title="Comment moderation" description="Moderate a comment by ID. Queue, report, policy, and suspension APIs are available for expanded moderation views.">
    <form className="admin-form" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); const data = new FormData(event.currentTarget); run(() => api.admin.moderateComment(String(data.get("commentId")), String(data.get("decision")) as "approved" | "rejected" | "spam" | "deleted"), "Comment moderation decision applied."); }}>
      <label htmlFor="comment-id">Comment ID</label><input id="comment-id" name="commentId" required /><label htmlFor="decision">Decision</label><select id="decision" name="decision"><option value="approved">Approve</option><option value="rejected">Reject</option><option value="spam">Mark spam</option><option value="deleted">Delete</option></select><button disabled={busy}>Apply decision</button>
    </form><p role="status" className="notice">{status}</p>
  </AdminSection>;
}
function XAccounts({ run, status, busy }: { run: (_work: () => Promise<unknown>, _success?: string) => Promise<void>; status: string; busy: boolean }) {
  const [id, setId] = useState("");
  return <AdminSection title="X accounts" description="Add a monitored account, change its monitoring state, or inject a permitted simulated post.">
    <form className="admin-form" onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); run(async () => { const result = await api.admin.addXAccount({ accountId: String(data.get("accountId")), handle: String(data.get("handle")), displayName: String(data.get("displayName")), topics: String(data.get("topics")).split(",").map((item) => item.trim()).filter(Boolean), relevanceThreshold: Number(data.get("threshold")) }); setId(result.id); }, "Account added."); }}><h2>Add account</h2><label htmlFor="account-id">Official account ID</label><input id="account-id" name="accountId" required /><label htmlFor="handle">Handle</label><input id="handle" name="handle" required /><label htmlFor="display-name">Display name</label><input id="display-name" name="displayName" required /><label htmlFor="x-topics">Topics</label><input id="x-topics" name="topics" required /><label htmlFor="threshold">Relevance threshold</label><input id="threshold" name="threshold" type="number" min="0" max="1" step="0.01" defaultValue="0.5" required /><button disabled={busy}>Add account</button></form>
    <form className="admin-form" onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); run(() => api.admin.setXAccountMonitoring(id, data.get("enabled") === "true"), "Monitoring state updated."); }}><h2>Monitoring</h2><label htmlFor="x-id">Account ID</label><input id="x-id" value={id} onChange={(event) => setId(event.target.value)} required /><label htmlFor="enabled">State</label><select id="enabled" name="enabled"><option value="true">Active</option><option value="false">Paused</option></select><button disabled={busy}>Update monitoring</button></form>
    <form className="admin-form" onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); run(() => api.admin.simulateXPost(id, { postId: String(data.get("postId")), canonicalUrl: String(data.get("url")), permittedText: String(data.get("text")), publishedAt: new Date(String(data.get("publishedAt"))).toISOString() }), "Simulated post accepted."); }}><h2>Simulated post</h2><label htmlFor="post-id">Post ID</label><input id="post-id" name="postId" required /><label htmlFor="post-url">Canonical URL</label><input id="post-url" name="url" type="url" required /><label htmlFor="post-text">Permitted text</label><textarea id="post-text" name="text" required maxLength={10000} /><label htmlFor="published-at">Published at</label><input id="published-at" name="publishedAt" type="datetime-local" required /><button disabled={busy}>Simulate post</button></form><p role="status" className="notice">{status}</p>
  </AdminSection>;
}
