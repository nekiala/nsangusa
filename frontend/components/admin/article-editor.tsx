"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useRef, useState } from "react";
import { api, type ApiArticle, type ArticleCommand, type ArticleSource, type ArticleState } from "@/lib/api";
import { AdminSection, dateLabel, Feedback, Pagination, SourceLink, useAction, useResource } from "./shared";
import { SourceSelector } from "./sources";
import { AnalysisReview } from "./analysis";
import { GenerationImage, ImageReview } from "./images";
import { RevisionHistory } from "./revisions";
import { ArticleContentView } from "@/components/article-content";
import { contentError, contentPlainText, resolvedContent, type ArticleContent } from "@/lib/article-content";
import { ContentEditor } from "./content-editor";

const states: ArticleState[] = ["DISCOVERED", "ANALYZING", "DRAFTING", "AWAITING_REVIEW", "APPROVED", "SCHEDULED", "PUBLISHED", "UNPUBLISHED", "REJECTED", "ARCHIVED"];

export function ArticleQueue() {
  const [state, setState] = useState<ArticleState | undefined>();
  const [page, setPage] = useState(0);
  const load = useCallback(() => api.admin.articles(state, page), [state, page]);
  const articles = useResource(load);
  return <AdminSection title="Articles" description="Discover drafts, review evidence and images, and publish approved articles.">
    <div className="workflow-actions"><Link href="/admin/editor/new">Create an article</Link><Link href="/admin/candidates">View story candidates</Link><Link href="/admin/schedules">Publication schedules</Link><button disabled={articles.loading} onClick={articles.refresh}>Refresh article queue</button></div>
    <div className="admin-form"><label htmlFor="article-state">Article state</label><select id="article-state" value={state || ""} onChange={(event) => { setState(states.find((value) => value === event.target.value)); setPage(0); }}>
      <option value="">All states</option>{states.map((value) => <option key={value}>{value}</option>)}
    </select></div>
    <Feedback error={articles.error} />
    {articles.loading && <p role="status">Loading articles…</p>}
    {articles.data?.items.length === 0 && <p>No articles in this queue.</p>}
    <ul className="queue-list">{articles.data?.items.map((article) => <li key={article.id}>
      <h2><Link href={`/admin/editor/${article.id}`}>{article.headline}</Link></h2>
      <p>{article.state} · {article.topic} · Updated {dateLabel(article.updatedAt)}</p>
      <p>{article.sources.length} source(s) · Confidence {Math.round(article.confidence * 100)}% · {article.warnings.length} warning(s)</p>
      {article.imageApprovalRequired && <p>Image approval required</p>}
    </li>)}</ul>
    {articles.data && <Pagination page={page} size={articles.data.size} total={articles.data.total} onPage={setPage} />}
  </AdminSection>;
}

function commandFrom(form: HTMLFormElement, sources: ArticleSource[], content: ArticleContent): ArticleCommand {
  const data = new FormData(form);
  return {
    headline: String(data.get("headline") || ""), summary: String(data.get("summary") || ""), body: contentPlainText(content), content,
    editorialContext: String(data.get("editorialContext") || "") || null, seoTitle: String(data.get("seoTitle") || ""),
    seoDescription: String(data.get("seoDescription") || ""), slugSuggestion: String(data.get("slugSuggestion") || ""),
    topic: String(data.get("topic") || ""), tags: String(data.get("tags") || "").split(",").map((tag) => tag.trim()).filter(Boolean),
    sources, commentsEnabled: data.get("commentsEnabled") === "on"
  };
}

function ArticlePreview({ value, article }: { value: ArticleCommand; article?: ApiArticle }) {
  return <section className="review-panel" aria-label="Article preview">
    <p className="eyebrow">Unpublished preview · structured editorial content</p><h2>{value.headline}</h2><p className="dek">{value.summary}</p>
    {article?.approvedImageGenerationId && <figure><GenerationImage generation={{ id: article.approvedImageGenerationId, altText: article.imageAltText || "" }} /><figcaption>{article.generatedImage ? "AI-generated illustration, not a documentary photograph." : "Editorial illustration, not AI-generated. This is not a documentary photograph."}</figcaption></figure>}
    <ArticleContentView content={value.content} body={value.body} />
    {value.editorialContext && <aside><h3>Editorial context</h3><p className="plain-text">{value.editorialContext}</p></aside>}
    <h3>Sources</h3><ul>{value.sources.map((source) => <li key={source.sourcePostId}><SourceLink url={source.url}>{source.account} · {source.postId}</SourceLink></li>)}</ul>
    <p>SEO title: {value.seoTitle}</p><p>SEO description: {value.seoDescription}</p>
  </section>;
}

function ArticleForm({ article, onSave, onDirty, busy }: { article?: ApiArticle; onSave: (_command: ArticleCommand) => void; onDirty?: () => void; busy: boolean }) {
  const [sources, setSources] = useState(article?.sources || []);
  const [preview, setPreview] = useState<ArticleCommand | null>(null);
  const [content, setContent] = useState<ArticleContent>(() => article
    ? resolvedContent(article.content, article.body)
    : { version: 1, blocks: [{ type: "paragraph", text: "" }] });
  const [validationError, setValidationError] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const editable = !article || ["DRAFTING", "AWAITING_REVIEW", "APPROVED", "REJECTED"].includes(article.state);
  const validatedCommand = (element: HTMLFormElement) => {
    const error = contentError(content);
    setValidationError(error);
    return error ? null : commandFrom(element, sources, content);
  };
  return <>
    {!editable && <p>This article is read-only in state {article?.state}.</p>}
    {article?.state === "SCHEDULED" && <p className="notice">Cancel its publication schedule before editing text or images. <Link href="/admin/schedules">Manage publication schedules</Link>.</p>}
    {article?.state === "PUBLISHED" && <p>Start a correction below before changing published content.</p>}
    <form ref={form} className="admin-form" onSubmit={(event) => {
      event.preventDefault();
      if (!editable || !sources.length) return;
      const command = validatedCommand(event.currentTarget);
      if (command) onSave(command);
    }}>
      <h2>{article ? "Article text and metadata" : "Create article"}</h2>
      <fieldset disabled={busy || !editable} onChange={() => { onDirty?.(); setPreview(null); }}><legend>Editorial content and metadata</legend>
        <label htmlFor="headline">Headline</label><input id="headline" name="headline" required maxLength={300} defaultValue={article?.headline} />
        <label htmlFor="summary">Summary</label><textarea id="summary" name="summary" required maxLength={2000} defaultValue={article?.summary} />
        <ContentEditor value={content} disabled={busy || !editable} onChange={(value) => {
          setContent(value); onDirty?.(); setPreview(null); setValidationError(null);
        }} />
        <label htmlFor="topic">Topic</label><input id="topic" name="topic" required maxLength={100} defaultValue={article?.topic || "Ideas"} />
        <label htmlFor="tags">Tags, separated by commas</label><input id="tags" name="tags" required defaultValue={article?.tags.join(", ") || "News"} />
        <label htmlFor="seo-title">SEO title</label><input id="seo-title" name="seoTitle" required maxLength={300} defaultValue={article?.seoTitle} />
        <label htmlFor="seo-description">SEO description</label><input id="seo-description" name="seoDescription" required maxLength={500} defaultValue={article?.seoDescription} />
        <label htmlFor="slug">Slug suggestion</label><input id="slug" name="slugSuggestion" required maxLength={250} defaultValue={article?.slug} />
        {article && <p>The existing URL is retained when editing: /articles/{article.slug}</p>}
        <label htmlFor="context">Editorial context</label><textarea id="context" name="editorialContext" maxLength={20000} defaultValue={article?.editorialContext || ""} />
        <label className="check"><input name="commentsEnabled" type="checkbox" defaultChecked={article?.commentsEnabled ?? true} /> Enable comments</label>
      </fieldset>
      <SourceSelector value={sources} onChange={(value) => { setSources(value); onDirty?.(); setPreview(null); }} disabled={busy || !editable} />
      {validationError && <p role="alert">{validationError}</p>}
      <div className="workflow-actions">
        <button disabled={busy || !editable || !sources.length}>{article ? "Save article" : "Create article"}</button>
        <button type="button" disabled={busy || !editable} onClick={() => { if (form.current) setPreview(validatedCommand(form.current)); }}>Preview article</button>
      </div>
    </form>
    {preview && <ArticlePreview value={preview} article={article} />}
    {!editable && article && <ArticlePreview article={article} value={{ ...article, slugSuggestion: article.slug }} />}
  </>;
}

export function ArticleEditor({ id }: { id: string }) {
  const router = useRouter();
  const [createdId, setCreatedId] = useState<string | null>(null);
  const action = useAction();
  if (id !== "new" || createdId) return <ArticleDetail id={createdId || id} />;
  return <AdminSection title="Article editor" description="Create a manual article from real ingested sources using safe editorial blocks.">
    <Link href="/admin/editor">Back to article queue</Link>
    <Feedback error={action.error} status={action.status} />
    <ArticleForm busy={action.busy} onSave={(command) => action.run(async () => {
      const created = await api.admin.createArticle(command);
      setCreatedId(created.id);
      router.replace(`/admin/editor/${created.id}`);
    }, "Article created.")} />
  </AdminSection>;
}

function ArticleDetail({ id }: { id: string }) {
  const load = useCallback(() => api.admin.article(id), [id]);
  const resource = useResource(load);
  const action = useAction();
  const [dirty, setDirty] = useState(false);
  const [publishAt, setPublishAt] = useState("");
  const [correctionNote, setCorrectionNote] = useState("");
  const [withdrawalAccepted, setWithdrawalAccepted] = useState(false);
  const article = resource.data;
  const locked = dirty || action.busy || resource.loading || !!resource.error;
  const refresh = () => { if (!dirty) resource.refresh(); };
  const transition = (operation: "approve" | "publish" | "reject" | "schedule" | "unpublish" | "restore" | "archive") => action.run(async () => {
    if (!article) return;
    if (operation === "approve") await api.admin.approveArticle(id, undefined, article.version);
    if (operation === "publish") await api.admin.publishArticle(id, undefined, article.version);
    if (operation === "reject") await api.admin.rejectArticle(id, undefined, article.version);
    if (operation === "unpublish") await api.admin.unpublishArticle(id, undefined, article.version);
    if (operation === "restore") await api.admin.restoreArticle(id, undefined, article.version);
    if (operation === "archive") await api.admin.archiveArticle(id, undefined, article.version);
    if (operation === "schedule") {
      const date = new Date(publishAt);
      if (!publishAt || !Number.isFinite(date.getTime()) || date.getTime() <= Date.now()) throw new Error("Choose a future publication date and time.");
      await api.admin.scheduleArticle(id, date.toISOString());
    }
    resource.refresh();
  }, operation === "schedule" ? "Publication scheduled at your selected time." : `${operation[0].toUpperCase()}${operation.slice(1)} request accepted. The current state refreshes below.`);
  return <AdminSection title="Article editor" description="Review sources, analysis and images before approving. Saving text returns an editable article to review.">
    <Link href="/admin/editor">Back to article queue</Link>
    <div className="workflow-actions"><button onClick={() => { setDirty(false); resource.refresh(); }} disabled={resource.loading || action.busy}>{dirty ? "Discard unsaved changes and refresh" : "Refresh article"}</button></div>
    <Feedback error={resource.error || action.error} status={action.status} />
    {resource.loading && <p>Loading article…</p>}
    {article && <>
      <p className="notice" data-testid="article-state">Current state: <strong>{article.state}</strong> · Version {article.version}</p>
      <p>Approved by: <span className="identifier">{article.approvedBy || "Not recorded"}</span> · Approved at: {dateLabel(article.approvedAt)}</p>
      {article.publishedAt && <p>Original publication: {dateLabel(article.publishedAt)} · Canonical URL: /articles/{article.slug}</p>}
      {article.correctionNote && <aside className="notice"><h2>Correction note</h2><p className="plain-text">{article.correctionNote}</p><p>The note is public only when this article is published.</p></aside>}
      {article.state === "PUBLISHED" && <Link href={`/articles/${article.slug}`}>Read published article</Link>}
      <section className="review-panel" aria-label="Editorial assessment"><h2>Editorial assessment</h2><p>Confidence: {Math.round(article.confidence * 100)}%</p><h3>Warnings</h3>{article.warnings.length ? <ul>{article.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul> : <p>No warnings reported. Verify sources before publication.</p>}</section>
      {article.storyCandidateId && <AnalysisReview candidateId={article.storyCandidateId} />}
      <ArticleForm key={`${article.id}-${article.version}`} article={article} busy={action.busy || resource.loading} onDirty={() => setDirty(true)} onSave={(command) => action.run(async () => {
        await api.admin.editArticle(id, article.version, command); setDirty(false); resource.refresh();
      }, "Article saved and returned to review.")} />
      {dirty && <p role="status">Unsaved changes. Save before approving images or publishing.</p>}
      <ImageReview article={article} onChange={refresh} locked={locked} />
      <section className="review-panel" aria-label="Editorial workflow"><h2>Approval and publication</h2>
        <p>{article.imageApprovalRequired ? "Approve the generated image first." : "Approval requires an article awaiting review. Publication and scheduling require approval."}</p>
        <div className="workflow-actions">
          <button disabled={locked || article.state !== "AWAITING_REVIEW" || article.imageApprovalRequired} onClick={() => transition("approve")}>Approve article</button>
          <button disabled={locked || !["APPROVED", "SCHEDULED"].includes(article.state) || article.imageApprovalRequired} onClick={() => transition("publish")}>Publish now</button>
          <button disabled={locked || article.state !== "AWAITING_REVIEW"} onClick={() => transition("reject")}>Reject article</button>
        </div>
        <form className="admin-form" onSubmit={(event) => { event.preventDefault(); transition("schedule"); }}>
          <label htmlFor="publish-at">Publication date and time (your local time)</label><input id="publish-at" type="datetime-local" required value={publishAt} onChange={(event) => setPublishAt(event.target.value)} disabled={locked || article.state !== "APPROVED"} />
          {publishAt && Number.isFinite(new Date(publishAt).getTime()) && <p>Selected instant: {new Date(publishAt).toISOString()}</p>}
          <button disabled={locked || article.state !== "APPROVED" || article.imageApprovalRequired || !publishAt}>Schedule publication</button>
        </form>
      </section>
      {["PUBLISHED", "UNPUBLISHED", "REJECTED"].includes(article.state) && <section className="review-panel" aria-label="Publication lifecycle">
        <h2>Publication lifecycle</h2>
        <p>Unpublishing withdraws the article from readers. Restoring keeps the canonical URL and original publication time, without another publication newsletter. Archiving ends the editorial workflow.</p>
        <div className="workflow-actions">
          {article.state === "PUBLISHED" && <button disabled={locked} onClick={() => transition("unpublish")}>Unpublish article</button>}
          {article.state === "UNPUBLISHED" && <button disabled={locked || article.imageApprovalRequired} onClick={() => transition("restore")}>Restore publication</button>}
          {["UNPUBLISHED", "REJECTED"].includes(article.state) && <button disabled={locked} onClick={() => transition("archive")}>Archive article</button>}
        </div>
        {["PUBLISHED", "UNPUBLISHED"].includes(article.state) && <form className="admin-form" onSubmit={(event) => {
          event.preventDefault();
          if (locked || !withdrawalAccepted || !correctionNote.trim()) return;
          action.run(async () => {
            await api.admin.startCorrection(id, article.version, correctionNote.trim());
            setCorrectionNote(""); setWithdrawalAccepted(false); resource.refresh();
          }, "Correction started. The article is withdrawn; edit, save, approve and republish after review.");
        }}>
          <h3>Start a correction</h3>
          <p className="notice" id="correction-warning">Starting a correction immediately withdraws the live article from public access and returns it to review. It stays unavailable until approved and republished. The same article ID, canonical URL and original publication time are retained; republication does not send a second publication newsletter.</p>
          <label htmlFor="correction-note">Public correction note (required)</label><textarea id="correction-note" required maxLength={2000} disabled={locked} value={correctionNote} onChange={(event) => setCorrectionNote(event.target.value)} aria-describedby="correction-warning" />
          <label className="check"><input type="checkbox" required checked={withdrawalAccepted} disabled={locked} onChange={(event) => setWithdrawalAccepted(event.target.checked)} /> I understand the article will be withdrawn until reviewed and republished.</label>
          <button disabled={locked || !withdrawalAccepted || !correctionNote.trim()}>Withdraw and start correction</button>
        </form>}
      </section>}
      <RevisionHistory key={`revisions-${article.id}-${article.version}`} articleId={article.id} />
    </>}
  </AdminSection>;
}
