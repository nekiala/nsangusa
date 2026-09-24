"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { type Comment, type Identifier } from "@/lib/api";
import { moderationApi, type CommunityComment } from "@/lib/moderation-api";
import { Feedback, useAction, useResource } from "./admin/shared";
import { CommentDeletion } from "./comment-deletion";

export function Comments({ articleId }: { articleId: Identifier; initialComments?: Comment[] }) {
  const load = useCallback(() => moderationApi.discussion(articleId), [articleId]);
  const discussion = useResource(load);
  const action = useAction();
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
    }, data?.requireApproval ? "Your comment has been submitted for moderation." : "Your comment was submitted. Spam checks may require moderation.")) {
      form.reset(); setReplying(null);
    }
  }
  function renderComment(comment: CommunityComment, nested = false) {
    const own = data?.viewerId === comment.authorId;
    const canEdit = own && !comment.deleted && data?.canComment && clock < Date.parse(comment.createdAt) + data.editingWindowMinutes * 60000;
    const children = comments.filter((child) => child.parentId === comment.id);
    return <li key={comment.id}>
      <article aria-label={`${own ? "Your" : "Reader"} comment`}>
        <p className="eyebrow">{own ? "You" : comment.authorName || "Reader"} · {comment.state}{comment.editedAt ? " · edited" : ""}</p>
        <p className="comment-body">{comment.body}</p>
        {own && !comment.deleted && !canEdit && <p>The editing window is closed or commenting is unavailable.</p>}
        {!comment.deleted && <div className="workflow-actions">
          {canEdit && <button disabled={action.busy} onClick={() => setEditing(comment.id)}>Edit comment</button>}
          {own && <button disabled={action.busy} onClick={(event) => { deletionTrigger.current = event.currentTarget; setDeleting(comment.id); }}>Delete comment</button>}
          {!own && comment.state === "approved" && data?.authenticated && data.eligible && <button disabled={action.busy || reported.includes(comment.id)} onClick={() => setReporting(comment.id)}>{reported.includes(comment.id) ? "Reported" : "Report abuse"}</button>}
          {!nested && !comment.parentId && comment.state === "approved" && data?.canComment && <button disabled={action.busy} onClick={() => setReplying(comment.id)}>Reply</button>}
        </div>}
        {deleting === comment.id && <CommentDeletion busy={action.busy} cancel={() => {
          setDeleting(null); deletionTrigger.current?.focus();
        }} confirm={() => action.run(async () => {
          await moderationApi.deleteOwn(comment); setDeleting(null); discussion.refresh(); commentsTitle.current?.focus();
        }, "Comment deleted.")} />}
        {editing === comment.id && <form onSubmit={async (event) => {
          event.preventDefault(); const body = String(new FormData(event.currentTarget).get("body"));
          await action.run(async () => { await moderationApi.edit(comment, body); setEditing(null); discussion.refresh(); }, "Comment updated and rechecked for moderation.");
        }}>
          <label htmlFor={`edit-${comment.id}`}>Edit your comment</label><textarea id={`edit-${comment.id}`} name="body" defaultValue={comment.body} required maxLength={5000} disabled={action.busy} />
          <button disabled={action.busy}>Save comment</button><button type="button" disabled={action.busy} onClick={() => setEditing(null)}>Cancel edit</button>
        </form>}
        {reporting === comment.id && <form onSubmit={async (event) => {
          event.preventDefault(); const form = new FormData(event.currentTarget);
          await action.run(async () => { await moderationApi.report(comment.id, String(form.get("reason")), String(form.get("details"))); setReported((values) => [...values, comment.id]); setReporting(null); discussion.refresh(); }, "Report received by the moderation team.");
        }}>
          <label htmlFor={`report-reason-${comment.id}`}>Report reason</label><select id={`report-reason-${comment.id}`} name="reason" disabled={action.busy}>
            {["abuse", "harassment", "hate", "misinformation", "spam", "other"].map((reason) => <option key={reason}>{reason}</option>)}
          </select>
          <label htmlFor={`report-details-${comment.id}`}>Report details (optional)</label><textarea id={`report-details-${comment.id}`} name="details" maxLength={2000} disabled={action.busy} />
          <button disabled={action.busy}>Send report</button><button type="button" disabled={action.busy} onClick={() => setReporting(null)}>Cancel report</button>
        </form>}
        {replying === comment.id && <form onSubmit={(event) => submit(event, comment.id)}>
          <label htmlFor={`reply-${comment.id}`}>Your reply</label><textarea id={`reply-${comment.id}`} name="body" required maxLength={5000} disabled={action.busy} />
          <button disabled={action.busy}>Submit reply</button><button type="button" disabled={action.busy} onClick={() => setReplying(null)}>Cancel reply</button>
        </form>}
      </article>
      {!nested && children.length > 0 && <ul className="comment-list" aria-label="Replies">{children.map((child) => renderComment(child, true))}</ul>}
    </li>;
  }
  return <section className="comments shell" aria-labelledby="comments-title">
    <h2 ref={commentsTitle} id="comments-title" tabIndex={-1}>Comments</h2>
    <button disabled={discussion.loading || action.busy} onClick={discussion.refresh}>Refresh comments</button>
    {discussion.loading && <p role="status">Checking comment availability…</p>}
    <Feedback error={discussion.error || action.error} status={action.status} />
    {data && <>
      {!data.enabled ? <p>Comments are disabled for this article.</p> : <>
        <p>{data.requireApproval ? "Comments require approval before appearing publicly." : "Comments are checked for spam; some may require moderation."} You may edit your own comments for {data.editingWindowMinutes} minutes. Replies are limited to one level.</p>
        {!data.authenticated && <p><Link href="/sign-in">Sign in</Link> to comment or report abuse.</p>}
        {data.authenticated && !data.eligible && <p>Your account is not eligible to comment.</p>}
        {data.privilegeStatus === "suspended" && <p>Your commenting privilege is suspended{data.suspendedUntil ? ` until ${new Date(data.suspendedUntil).toLocaleString()}` : " indefinitely"}. You may still delete your own comments.</p>}
        {roots.length ? <ul className="comment-list">{roots.map((comment) => renderComment(comment))}</ul> : <p>No comments yet.</p>}
        {data.canComment && <form onSubmit={submit}>
          <label htmlFor="comment">Add a comment</label><textarea id="comment" name="body" required maxLength={5000} disabled={action.busy} />
          <button type="submit" disabled={action.busy}>{action.busy ? "Submitting…" : data.requireApproval ? "Submit for moderation" : "Submit comment"}</button>
        </form>}
      </>}
    </>}
  </section>;
}
