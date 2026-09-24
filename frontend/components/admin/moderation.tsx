"use client";

import { useCallback, useState, type FormEvent } from "react";
import { moderationApi, type ArticleCommentSettings, type CommentSettings, type Decision, type Privilege } from "@/lib/moderation-api";
import { AdminSection, Feedback, Pagination, dateLabel, useAction, useResource } from "./shared";

export function ModerationWorkspace({ roles }: { roles: string[] }) {
  if (!roles.some((role) => ["MODERATOR", "ADMINISTRATOR"].includes(role))) return <AdminSection title="Community moderation" description="Moderator or administrator access is required."><p role="alert">Your role cannot access community moderation.</p></AdminSection>;
  return <ModerationContent administrator={roles.includes("ADMINISTRATOR")} />;
}

function ModerationContent({ administrator }: { administrator: boolean }) {
  const [section, setSection] = useState("queue");
  const [selectedComment, setSelectedComment] = useState<string | null>(null);
  const [selectedUser, setSelectedUser] = useState<{ id: string; name: string } | null>(null);
  const [revision, setRevision] = useState(0);
  const inspectUser = (id: string, name: string) => { setSelectedUser({ id, name }); setSection("privileges"); };
  return <AdminSection title="Community moderation" description="Review comments and reports, inspect decision history, and manage community participation. All changes are authorized and audited by the server.">
    <nav className="workflow-actions" aria-label="Community sections">
      {[["queue", "Comments"], ["reports", "Abuse reports"], ["privileges", "Commenting privileges"], ["policies", "Comment policies"]].map(([value, label]) => <button key={value} aria-pressed={section === value} onClick={() => setSection(value)}>{label}</button>)}
    </nav>
    {section === "queue" && <CommentQueue key={revision} inspect={setSelectedComment} />}
    {section === "reports" && <Reports key={revision} inspect={setSelectedComment} />}
    {(section === "queue" || section === "reports") && selectedComment && <CommentReview key={selectedComment} id={selectedComment} inspectUser={inspectUser} changed={() => setRevision((value) => value + 1)} close={() => setSelectedComment(null)} />}
    {section === "privileges" && <>
      <UserDirectory inspect={inspectUser} />
      {selectedUser && <PrivilegeEditor key={selectedUser.id} id={selectedUser.id} name={selectedUser.name} />}
    </>}
    {section === "policies" && <Policies administrator={administrator} />}
  </AdminSection>;
}

function CommentQueue({ inspect }: { inspect: (_id: string) => void }) {
  const [state, setState] = useState("pending");
  const [page, setPage] = useState(0);
  const load = useCallback(() => moderationApi.queue(state, page), [state, page]);
  const queue = useResource(load);
  return <section aria-label="Moderation queue">
    <h2>Comments</h2>
    <label htmlFor="comment-state">Comment state</label><select id="comment-state" value={state} onChange={(event) => { setState(event.target.value); setPage(0); }}>
      <option value="">All states</option>{["pending", "approved", "rejected", "spam", "deleted"].map((value) => <option key={value}>{value}</option>)}
    </select>
    <button disabled={queue.loading} onClick={queue.refresh}>Refresh queue</button>
    <Feedback error={queue.error} />
    {queue.loading ? <p role="status">Loading comments…</p> : <><ul className="queue-list">{queue.data?.items.map((comment) => <li key={comment.id}>
      <h3>{comment.articleHeadline}</h3><p>{comment.body}</p>
      <p>{comment.authorName || "Reader"} · {comment.state} · {comment.openReports} open reports · {dateLabel(comment.createdAt)}</p>
      <button onClick={() => inspect(comment.id)}>Review comment</button>
    </li>)}</ul>{queue.data?.items.length === 0 && <p>No comments match this state.</p>}</>}
    {queue.data && <Pagination label="Comment pages" page={page} size={queue.data.size} total={queue.data.total} onPage={setPage} />}
  </section>;
}

function Reports({ inspect }: { inspect: (_id: string) => void }) {
  const reports = useResource(useCallback(() => moderationApi.reports(), []));
  return <section aria-label="Abuse reports"><h2>Open abuse reports</h2><p>Showing up to 100 oldest open reports. A moderation decision resolves all open reports for that comment.</p>
    <button disabled={reports.loading} onClick={reports.refresh}>Refresh reports</button><Feedback error={reports.error} />
    {reports.loading && <p role="status">Loading reports…</p>}
    {reports.data?.length === 0 && <p>No open abuse reports.</p>}
    <ul className="queue-list">{reports.data?.map((report) => <li key={report.id}>
      <h3>{report.reason}</h3><p>{report.details || "No additional details."}</p><p>{dateLabel(report.createdAt)}</p>
      <button onClick={() => inspect(report.commentId)}>Inspect reported comment</button>
    </li>)}</ul>
  </section>;
}

function CommentReview({ id, inspectUser, changed, close }: { id: string; inspectUser: (_id: string, _name: string) => void; changed: () => void; close: () => void }) {
  const load = useCallback(async () => ({ comment: await moderationApi.detail(id), history: await moderationApi.history(id) }), [id]);
  const review = useResource(load);
  const action = useAction();
  const [decision, setDecision] = useState<Decision>("approved");
  const [reason, setReason] = useState("");
  const comment = review.data?.comment;
  return <section className="review-panel" aria-label="Comment review">
    <h2>Comment review</h2><button onClick={close} disabled={action.busy}>Close review</button>
    <button onClick={review.refresh} disabled={action.busy || review.loading}>Reload comment and history</button>
    <Feedback error={review.error || action.error} status={action.status} />
    {review.loading && <p role="status">Loading comment and history…</p>}
    {comment && !review.loading && <>
      <h3>{comment.articleHeadline}</h3><p>{comment.body}</p>
      <p>{comment.state} · Version {comment.version} · {comment.openReports} open reports</p>
      <p>Spam score: {comment.spamScore}. {comment.spamReason || "No spam signals recorded."}</p>
      {comment.parentId && <p>This is a reply to a top-level comment.</p>}
      <button onClick={() => inspectUser(comment.authorId, comment.authorName || "Selected comment author")}>Manage author&apos;s commenting privilege</button>
      {!comment.deleted ? <form className="admin-form" onSubmit={async (event) => {
        event.preventDefault();
        if (await action.run(async () => { await moderationApi.moderate(comment, decision, reason); review.refresh(); changed(); }, "Moderation decision recorded; open reports resolved.")) setReason("");
      }}>
        <label htmlFor="moderation-decision">Decision</label><select id="moderation-decision" disabled={action.busy} value={decision} onChange={(event) => setDecision(event.target.value as Decision)}>
          <option value="approved">Approve</option><option value="rejected">Reject</option><option value="spam">Mark as spam</option><option value="deleted">Delete permanently (soft-delete record)</option>
        </select>
        <label htmlFor="moderation-reason">Decision reason</label><textarea id="moderation-reason" required maxLength={2000} value={reason} disabled={action.busy} onChange={(event) => setReason(event.target.value)} />
        {decision === "deleted" && <p>Comment text will be removed and cannot be restored. Existing replies retain a deleted-parent marker.</p>}
        <button disabled={action.busy || !reason.trim()}>Record decision</button>
      </form> : <p>This comment was deleted. Its text cannot be restored.</p>}
      <h3>Moderation history</h3>
      <p>Latest 100 decisions. Older records remain in the audit log.</p>
      {review.data?.history.length === 0 && <p>No moderation decisions recorded.</p>}
      <ul className="queue-list">{review.data?.history.map((entry) => <li key={entry.id}>
        <p>{entry.previousState} → {entry.action} · {dateLabel(entry.createdAt)}</p><p>{entry.reason}</p><p className="identifier">Moderator: {entry.moderatorId}</p>
      </li>)}</ul>
    </>}
  </section>;
}

function UserDirectory({ inspect }: { inspect: (_id: string, _name: string) => void }) {
  const [query, setQuery] = useState("");
  const users = useResource(useCallback(() => query ? moderationApi.users(query) : Promise.resolve([]), [query]));
  return <section aria-label="Community accounts"><h2>Find an account</h2>
    <p>Search by name or email using 2–100 characters. At most 20 active accounts are returned; refine your search if needed. Email addresses are not displayed.</p>
    <form className="admin-form" onSubmit={(event) => { event.preventDefault(); setQuery(String(new FormData(event.currentTarget).get("query")).trim()); }}>
      <label htmlFor="community-user-query">Search accounts</label><input id="community-user-query" name="query" minLength={2} maxLength={100} required />
      <button>Search accounts</button>
    </form>
    <Feedback error={users.error} />
    {users.loading && <p role="status">Loading accounts…</p>}
    {query && users.data?.length === 0 && <p>No matching accounts.</p>}
    <ul className="queue-list">{users.data?.map((user) => <li key={user.id}>
      <p>{user.displayName} · {user.staff ? "Staff" : "Reader"}</p><button onClick={() => inspect(user.id, user.displayName)}>Manage {user.displayName}</button>
    </li>)}</ul>
  </section>;
}

function PrivilegeEditor({ id, name }: { id: string; name: string }) {
  const load = useCallback(async () => {
    const [account, privilege, history] = await Promise.all([moderationApi.user(id), moderationApi.privilege(id), moderationApi.privilegeHistory(id)]);
    return { account, privilege, history };
  }, [id]);
  const resource = useResource(load);
  const action = useAction();
  const [operation, setOperation] = useState<"suspend" | "restore">("suspend");
  async function submit(event: FormEvent<HTMLFormElement>, value: Privilege) {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    const until = String(data.get("until") || "");
    if (await action.run(async () => {
      await moderationApi.setPrivilege(value, operation, String(data.get("reason")), until ? new Date(until).toISOString() : null);
      resource.refresh();
    }, "Commenting privilege updated and audited.")) form.reset();
  }
  return <section className="review-panel" aria-label="Account commenting privilege"><h2>{resource.data?.account.displayName || name}</h2>
    <button disabled={resource.loading || action.busy} onClick={resource.refresh}>Reload privilege</button>
    <Feedback error={resource.error || action.error} status={action.status} />
    {resource.loading && <p role="status">Loading privilege…</p>}
    {resource.data && !resource.loading && <>
      <p>Commenting: {resource.data.privilege.status}{resource.data.privilege.suspendedUntil ? ` until ${dateLabel(resource.data.privilege.suspendedUntil)}` : ""}</p>
      <p>{resource.data.privilege.reason}</p>
      {!resource.data.account.active && <p>This account is inactive. Changing commenting privileges does not reactivate it.</p>}
      <form className="admin-form" onSubmit={(event) => submit(event, resource.data!.privilege)}>
        <label htmlFor="privilege-action">Privilege action</label><select id="privilege-action" value={operation} disabled={action.busy} onChange={(event) => setOperation(event.target.value as "suspend" | "restore")}>
          <option value="suspend">Suspend commenting</option><option value="restore">Restore commenting</option>
        </select>
        {operation === "suspend" && <><label htmlFor="suspended-until">Suspended until (local time; empty means indefinite)</label><input id="suspended-until" name="until" type="datetime-local" disabled={action.busy} /></>}
        <label htmlFor="privilege-reason">Privilege change reason</label><textarea id="privilege-reason" name="reason" required maxLength={2000} disabled={action.busy} />
        <button disabled={action.busy}>Save commenting privilege</button>
      </form>
      <h3>Privilege history</h3><p>Latest 100 changes. Older records remain in the audit log.</p>{resource.data.history.length === 0 && <p>No privilege changes recorded.</p>}
      <ul className="queue-list">{resource.data.history.map((entry) => <li key={entry.id}><p>{entry.action} · {dateLabel(entry.createdAt)}</p>
        {entry.action === "suspended" && <p>Until: {entry.suspendedUntil ? dateLabel(entry.suspendedUntil) : "Indefinite"}</p>}
        <p>{entry.reason}</p><p className="identifier">Moderator: {entry.moderatorId}</p></li>)}</ul>
    </>}
  </section>;
}

function Policies({ administrator }: { administrator: boolean }) {
  const global = useResource(useCallback(() => moderationApi.globalSettings(), []));
  const [page, setPage] = useState(0);
  const articles = useResource(useCallback(() => moderationApi.articles(page), [page]));
  const [article, setArticle] = useState<{ id: string; headline: string; enabled: boolean } | null>(null);
  return <section aria-label="Comment policies"><h2>Comment policies</h2>
    {!administrator && <p>Policies are read-only for moderators. An administrator can change them.</p>}
    <Feedback error={global.error || articles.error} />
    <button disabled={global.loading} onClick={global.refresh}>Reload global policy</button>
    {global.loading && <p role="status">Loading policy…</p>}
    {global.data && !global.loading && <GlobalPolicy key={`${global.data.version}-${global.data.updatedAt}`} value={global.data} administrator={administrator} changed={global.refresh} />}
    <h3>Per-article overrides</h3><p>Overrides cannot enable comments when globally disabled or when the article itself disables comments. Unpublished articles never accept comments.</p>
    <ul className="queue-list">{articles.data?.items.map((item) => <li key={item.id}>
      <p>{item.headline} · {item.state} · Article switch {item.commentsEnabled ? "on" : "off"}</p>
      <button onClick={() => setArticle({ id: item.id, headline: item.headline, enabled: item.commentsEnabled })}>Comment policy for {item.headline}</button>
    </li>)}</ul>
    {articles.data?.items.length === 0 && <p>No articles available.</p>}
    {articles.data && <Pagination label="Comment policy article pages" page={page} size={articles.data.size} total={articles.data.total} onPage={setPage} />}
    {article && <ArticlePolicy key={article.id} id={article.id} headline={article.headline} administrator={administrator} />}
  </section>;
}

function GlobalPolicy({ value, administrator, changed }: { value: CommentSettings; administrator: boolean; changed: () => void }) {
  const [draft, setDraft] = useState(value);
  const action = useAction();
  const numbers = [
    ["editingWindowMinutes", "Own-comment editing window (minutes)", 0, 10080, 1],
    ["reviewSpamThreshold", "Spam review threshold", 0, 1, 0.01],
    ["rejectSpamThreshold", "Spam rejection threshold", 0, 1, 0.01],
    ["reportEscalationThreshold", "Reports before escalation", 1, 100, 1],
  ] as const;
  return <form className="admin-form" onSubmit={(event) => {
    event.preventDefault(); action.run(async () => { await moderationApi.updateGlobalSettings(draft); changed(); }, "Global comment policy saved.");
  }}>
    <h3>Global policy</h3><Feedback error={action.error} status={action.status} />
    <fieldset disabled={!administrator || action.busy}><legend>Comment availability and safeguards</legend>
      <label><input type="checkbox" checked={draft.enabled} onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })} />Enable comments globally</label>
      <label><input type="checkbox" checked={draft.requireApproval} onChange={(event) => setDraft({ ...draft, requireApproval: event.target.checked })} />Require approval by default</label>
      {numbers.map(([field, label, min, max, step]) => <div key={field}><label htmlFor={`policy-${field}`}>{label}</label><input id={`policy-${field}`} type="number" min={min} max={max} step={step} required value={draft[field]} onChange={(event) => setDraft({ ...draft, [field]: Number(event.target.value) })} /></div>)}
      <button>Save global comment policy</button>
    </fieldset>
  </form>;
}

function ArticlePolicy({ id, headline, administrator }: { id: string; headline: string; administrator: boolean }) {
  const settings = useResource(useCallback(() => moderationApi.articleSettings(id), [id]));
  return <section className="review-panel" aria-label="Article comment policy"><h3>{headline}</h3>
    <button disabled={settings.loading} onClick={settings.refresh}>Reload article policy</button><Feedback error={settings.error} />
    {settings.loading && <p role="status">Loading article policy…</p>}
    {settings.data && !settings.loading && <ArticlePolicyForm key={`${settings.data.version}-${settings.data.updatedAt}`} value={settings.data} administrator={administrator} changed={settings.refresh} />}
  </section>;
}

function ArticlePolicyForm({ value, administrator, changed }: { value: ArticleCommentSettings; administrator: boolean; changed: () => void }) {
  const [draft, setDraft] = useState(value);
  const action = useAction();
  return <form className="admin-form" onSubmit={(event) => { event.preventDefault(); action.run(async () => { await moderationApi.updateArticleSettings(draft); changed(); }, "Article comment policy saved."); }}>
    <Feedback error={action.error} status={action.status} />
    <fieldset disabled={!administrator || action.busy}><legend>Article overrides</legend>
      {([["enabledOverride", "Article comment availability"], ["requireApprovalOverride", "Article approval policy"]] as const).map(([field, label]) => <div key={field}>
        <label htmlFor={field}>{label}</label><select id={field} value={draft[field] === null ? "" : String(draft[field])} onChange={(event) => setDraft({ ...draft, [field]: event.target.value === "" ? null : event.target.value === "true" })}>
          <option value="">Inherit global policy</option><option value="true">{field === "enabledOverride" ? "Enabled when article and global switches permit" : "Require approval"}</option><option value="false">{field === "enabledOverride" ? "Disabled" : "Automatic approval unless spam checks intervene"}</option>
        </select>
      </div>)}
      <button>Save article comment policy</button>
    </fieldset>
  </form>;
}
