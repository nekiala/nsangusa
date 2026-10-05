"use client";

import Link, { useLocale } from "@/components/locale";
import type { MessageKey } from "@/lib/i18n";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { type Comment, type Identifier } from "@/lib/api";
import { moderationApi, type CommunityComment } from "@/lib/moderation-api";
import { Feedback, useAction, useResource } from "./admin/shared";
import { CommentDeletion } from "./comment-deletion";

export function Comments({ articleId }: { articleId: Identifier; initialComments?: Comment[] }) {
  const load = useCallback(() => moderationApi.discussion(articleId), [articleId]);
  const discussion = useResource(load);
  const action = useAction();
  const { locale, t } = useLocale();
  // Unknown server values are shown as received rather than hidden.
  const label = (group: string, value: string) => { const text = t(`${group}.${value}` as MessageKey); return text === undefined ? value : text; };
  const [editing, setEditing] = useState<string | null>(null);
  const [replying, setReplying] = useState<string | null>(null);
  const [reporting, setReporting] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const deletionTrigger = useRef<HTMLButtonElement | null>(null);
  const commentsTitle = useRef<HTMLHeadingElement>(null);
  const [reported, setReported] = useState<string[]>([]);
  const [clock, setClock] = useState(() => Date.now());
  useEffect(() => {
    const interval = setInterval(() => setClock(Date.now()), 15_000);
    return () => clearInterval(interval);
  }, []);
  const data = discussion.error ? null : discussion.data;
  const comments = data?.comments || [];
  const roots = comments.filter((comment) => !comment.parentId || !comments.some((parent) => parent.id === comment.parentId));

  async function submit(event: FormEvent<HTMLFormElement>, parentId: string | null = null) {
    event.preventDefault();
    const form = event.currentTarget;
    const body = String(new FormData(form).get("body")).trim();
    if (await action.run(async () => {
      await moderationApi.submit(articleId, body, parentId);
      discussion.refresh();
    }, t(data?.requireApproval ? "comments.submittedModeration" : "comments.submitted"))) {
      form.reset(); setReplying(null);
    }
  }
  function renderComment(comment: CommunityComment, nested = false) {
    const own = data?.viewerId === comment.authorId;
    const canEdit = own && !comment.deleted && data?.canComment && clock < Date.parse(comment.createdAt) + data.editingWindowMinutes * 60000;
    const children = comments.filter((child) => child.parentId === comment.id);
    return <li key={comment.id}>
      <article aria-label={t(own ? "comments.yourComment" : "comments.readerComment")}>
        <p className="eyebrow">{own ? t("comments.you") : comment.authorName || t("comments.reader")} · {label("comments.state", comment.state)}{comment.editedAt ? ` · ${t("comments.edited")}` : ""}</p>
        <p className="comment-body">{comment.body}</p>
        {own && !comment.deleted && !canEdit && <p>{t("comments.windowClosed")}</p>}
        {!comment.deleted && <div className="workflow-actions">
          {canEdit && <button disabled={action.busy} onClick={() => setEditing(comment.id)}>{t("comments.edit")}</button>}
          {own && <button disabled={action.busy} onClick={(event) => { deletionTrigger.current = event.currentTarget; setDeleting(comment.id); }}>{t("comments.delete")}</button>}
          {!own && comment.state === "approved" && data?.authenticated && data.eligible && <button disabled={action.busy || reported.includes(comment.id)} onClick={() => setReporting(comment.id)}>{t(reported.includes(comment.id) ? "comments.reported" : "comments.report")}</button>}
          {!nested && !comment.parentId && comment.state === "approved" && data?.canComment && <button disabled={action.busy} onClick={() => setReplying(comment.id)}>{t("comments.reply")}</button>}
        </div>}
        {deleting === comment.id && <CommentDeletion busy={action.busy} cancel={() => {
          setDeleting(null); deletionTrigger.current?.focus();
        }} confirm={() => action.run(async () => {
          await moderationApi.deleteOwn(comment); setDeleting(null); discussion.refresh(); commentsTitle.current?.focus();
        }, t("comments.deleted"))} />}
        {editing === comment.id && <form onSubmit={async (event) => {
          event.preventDefault(); const body = String(new FormData(event.currentTarget).get("body"));
          await action.run(async () => { await moderationApi.edit(comment, body); setEditing(null); discussion.refresh(); }, t("comments.updated"));
        }}>
          <label htmlFor={`edit-${comment.id}`}>{t("comments.editLabel")}</label><textarea id={`edit-${comment.id}`} name="body" defaultValue={comment.body} required maxLength={5000} disabled={action.busy} />
          <button disabled={action.busy}>{t("comments.save")}</button><button type="button" disabled={action.busy} onClick={() => setEditing(null)}>{t("comments.cancelEdit")}</button>
        </form>}
        {reporting === comment.id && <form onSubmit={async (event) => {
          event.preventDefault(); const form = new FormData(event.currentTarget);
          await action.run(async () => { await moderationApi.report(comment.id, String(form.get("reason")), String(form.get("details"))); setReported((values) => [...values, comment.id]); setReporting(null); discussion.refresh(); }, t("comments.reportReceived"));
        }}>
          <label htmlFor={`report-reason-${comment.id}`}>{t("comments.reportReason")}</label><select id={`report-reason-${comment.id}`} name="reason" disabled={action.busy}>
            {["abuse", "harassment", "hate", "misinformation", "spam", "other"].map((reason) => <option key={reason} value={reason}>{label("comments.reason", reason)}</option>)}
          </select>
          <label htmlFor={`report-details-${comment.id}`}>{t("comments.reportDetails")}</label><textarea id={`report-details-${comment.id}`} name="details" maxLength={2000} disabled={action.busy} />
          <button disabled={action.busy}>{t("comments.sendReport")}</button><button type="button" disabled={action.busy} onClick={() => setReporting(null)}>{t("comments.cancelReport")}</button>
        </form>}
        {replying === comment.id && <form onSubmit={(event) => submit(event, comment.id)}>
          <label htmlFor={`reply-${comment.id}`}>{t("comments.yourReply")}</label><textarea id={`reply-${comment.id}`} name="body" required maxLength={5000} disabled={action.busy} />
          <button disabled={action.busy}>{t("comments.submitReply")}</button><button type="button" disabled={action.busy} onClick={() => setReplying(null)}>{t("comments.cancelReply")}</button>
        </form>}
      </article>
      {!nested && children.length > 0 && <ul className="comment-list" aria-label={t("comments.replies")}>{children.map((child) => renderComment(child, true))}</ul>}
    </li>;
  }
  return <section className="comments shell" aria-labelledby="comments-title">
    <h2 ref={commentsTitle} id="comments-title" tabIndex={-1}>{t("comments.title")}</h2>
    <button disabled={discussion.loading || action.busy} onClick={discussion.refresh}>{t("comments.refresh")}</button>
    {discussion.loading && <p role="status">{t("comments.checking")}</p>}
    <Feedback error={discussion.error || action.error} status={action.status} />
    {data && <>
      {!data.enabled ? <p>{t("comments.disabled")}</p> : <>
        <p>{t(data.requireApproval ? "comments.requireApproval" : "comments.spamChecked")} {t("comments.rules", { minutes: data.editingWindowMinutes })}</p>
        {!data.authenticated && <p><Link href="/sign-in">{t("session.signIn")}</Link> {t("comments.signInTo")}</p>}
        {data.authenticated && !data.eligible && <p>{t("comments.notEligible")}</p>}
        {data.privilegeStatus === "suspended" && <p>{data.suspendedUntil ? t("comments.suspendedUntil", { date: new Date(data.suspendedUntil).toLocaleString(locale) }) : t("comments.suspended")}</p>}
        {roots.length ? <ul className="comment-list">{roots.map((comment) => renderComment(comment))}</ul> : <p>{t("comments.none")}</p>}
        {data.canComment && <form onSubmit={submit}>
          <label htmlFor="comment">{t("comments.add")}</label><textarea id="comment" name="body" required maxLength={5000} disabled={action.busy} />
          <button type="submit" disabled={action.busy}>{t(action.busy ? "comments.submitting" : data.requireApproval ? "comments.submitModeration" : "comments.submit")}</button>
        </form>}
      </>}
    </>}
  </section>;
}
