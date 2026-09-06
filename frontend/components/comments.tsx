"use client";

import { useState, type FormEvent } from "react";
import { ApiError, api, type Comment, type Identifier } from "@/lib/api";

export function Comments({ articleId, initialComments }: { articleId: Identifier; initialComments: Comment[] }) {
  const [comments] = useState(initialComments);
  const [notice, setNotice] = useState({ message: "", error: false });
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const body = String(new FormData(form).get("comment")).trim();
    if (!body) return;
    setBusy(true);
    try {
      await api.articles.submitComment(articleId, body);
      form.reset();
      setNotice({ message: "Your comment has been submitted for moderation.", error: false });
    } catch (error) {
      setNotice({ message: error instanceof ApiError ? error.problem.detail : "We could not submit your comment.", error: true });
    } finally { setBusy(false); }
  }
  return <section className="comments shell" aria-labelledby="comments-title">
    <h2 id="comments-title">Comments</h2>
    {comments.length ? <ul className="comment-list">{comments.map((comment) => <li key={comment.id}>{comment.body}</li>)}</ul> : <p>No comments yet.</p>}
    <form onSubmit={submit}>
      <label htmlFor="comment">Add a comment</label>
      <textarea id="comment" name="comment" required maxLength={5000} />
      <button type="submit" disabled={busy}>{busy ? "Submitting" : "Submit for moderation"}</button>
      <p role={notice.error ? "alert" : "status"} className="form-note">{notice.message}</p>
    </form>
  </section>;
}
