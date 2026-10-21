"use client";

import { tx } from "@/lib/i18n/staff";
import { useCallback, useState, type ChangeEvent } from "react";
import { api, type ResolvedXAccount, type XAccount } from "@/lib/api";
import { AdminSection, dateLabel, Feedback, useAction, useResource } from "./shared";
import { SourceDetails } from "./sources";

const loadAccounts = async () => {
  const [accounts, blocked, capabilities] = await Promise.all([api.admin.xAccounts(), api.admin.blockedAccounts(), api.admin.xCapabilities()]);
  return { accounts, blocked, capabilities };
};
const splitTopics = (value: unknown) => String(value || "").split(",").map((item) => item.trim()).filter(Boolean);

function AccountFields({ account, nameControl }: { account?: XAccount; nameControl?: { value: string; onChange: (_value: string) => void } }) {
  return <>
    <label htmlFor="display-name">{tx("Display name")}</label><input id="display-name" name="displayName" required maxLength={100} {...(nameControl ? { value: nameControl.value, onChange: (event: ChangeEvent<HTMLInputElement>) => nameControl.onChange(event.target.value) } : { defaultValue: account?.displayName })} />
    <label htmlFor="x-topics">{tx("Topics, separated by commas")}</label><input id="x-topics" name="topics" required defaultValue={account?.topics.join(", ")} />
    <label htmlFor="threshold">{tx("Relevance threshold")}</label><input id="threshold" name="threshold" type="number" min="0" max="1" step="0.01" defaultValue={account?.relevanceThreshold ?? 0.5} required />
  </>;
}

function AccountSources({ accountId }: { accountId: string }) {
  const load = useCallback(() => api.admin.sources("active", accountId), [accountId]);
  const sources = useResource(load);
  const action = useAction();
  const [id, setId] = useState("");
  return <section className="review-panel"><h3>{tx("Active source posts")}</h3><button onClick={sources.refresh} disabled={sources.loading}>{tx("Refresh account sources")}</button>
    <Feedback error={sources.error || action.error} status={action.status} />
    {sources.data?.length === 0 && <p>{tx("No active posts for this account yet.")}</p>}
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault(); const data = new FormData(event.currentTarget);
      action.run(async () => { await api.admin.excludeSource(id, String(data.get("reason"))); setId(""); sources.refresh(); }, tx("Source excluded from editorial use."));
    }}>
      <label htmlFor="account-source">{tx("Source post")}</label><select id="account-source" value={id} onChange={(event) => setId(event.target.value)}>
        <option value="">{tx("Choose a source to inspect")}</option>{sources.data?.map((source) => <option key={source.id} value={source.id}>{source.postId} · {dateLabel(source.publishedAt)}</option>)}
      </select>
      {id && <SourceDetails key={id} id={id} />}
      <label htmlFor="source-reason">{tx("Source exclusion reason")}</label><input id="source-reason" name="reason" required maxLength={500} disabled={!id} />
      <label className="check"><input type="checkbox" required disabled={!id} /> {tx("Exclude only the selected source from future editorial use")}</label>
      <button disabled={!id || action.busy}>{tx("Exclude selected source")}</button>
    </form>
  </section>;
}

export function XAccounts() {
  const resource = useResource(loadAccounts);
  const action = useAction();
  const [id, setId] = useState("");
  const [adding, setAdding] = useState(false);
  const [resolved, setResolved] = useState<ResolvedXAccount | null>(null);
  const [newHandle, setNewHandle] = useState("");
  const [newAccountId, setNewAccountId] = useState("");
  const [newDisplayName, setNewDisplayName] = useState("");
  const account = resource.data?.accounts.find((item) => item.id === id);
  const blocked = resource.data?.blocked.find((item) => item.accountId === account?.accountId);
  return <AdminSection title={tx("X accounts")} description={tx("Choose a monitored account to inspect its health, manage ingestion, or review permitted sources. No internal ID entry is needed.")}>
    <div className="workflow-actions"><button disabled={resource.loading || action.busy} onClick={resource.refresh}>{tx("Refresh accounts")}</button><button onClick={() => { setAdding(!adding); setId(""); setResolved(null); setNewHandle(""); setNewAccountId(""); setNewDisplayName(""); }}>{adding ? tx("Close add account") : tx("Add an account")}</button></div>
    <Feedback error={resource.error || action.error} status={action.status} />
    {resource.loading && <p>{tx("Loading accounts…")}</p>}
    {resource.data?.accounts.length === 0 && <p>{tx("No monitored accounts. Add an official account to begin.")}</p>}
    <div className="admin-form"><label htmlFor="selected-account">{tx("Monitored account")}</label><select id="selected-account" value={id} onChange={(event) => { setId(event.target.value); setAdding(false); }}>
      <option value="">{tx("Select an account")}</option>{resource.data?.accounts.map((item) => <option key={item.id} value={item.id}>@{item.handle.replace(/^@/, "")} · {item.displayName} · {item.monitoringEnabled ? "Active" : "Paused"} · {item.syncHealth}</option>)}
    </select></div>
    {adding && <form className="admin-form" onSubmit={(event) => {
      event.preventDefault(); const data = new FormData(event.currentTarget);
      action.run(async () => {
        const result = await api.admin.addXAccount({ accountId: String(data.get("accountId")), handle: String(data.get("handle")), displayName: String(data.get("displayName")), topics: splitTopics(data.get("topics")), relevanceThreshold: Number(data.get("threshold")) });
        setId(result.id); setAdding(false); resource.refresh();
      }, tx("Account added."));
    }}>
      <h2>{tx("Add account")}</h2>
      <fieldset disabled={action.busy}><legend>{tx("Account identity")}</legend>
        <label htmlFor="handle">{tx("Handle")}</label><input id="handle" name="handle" required pattern="@?[A-Za-z0-9_]{1,15}" value={newHandle} onChange={(event) => { setNewHandle(event.target.value); setResolved(null); }} />
        <button type="button" disabled={!newHandle} onClick={() => action.run(async () => {
          const identity = await api.admin.resolveXAccount(newHandle);
          setResolved(identity); setNewHandle(identity.handle); setNewAccountId(identity.accountId); setNewDisplayName(identity.displayName);
        }, tx("Account identity resolved. Review the details before adding."))}>{tx("Resolve handle")}</button>
        {resolved && <p>{resolved.simulated ? tx("Resolved by the simulated provider.") : tx("Resolved through the official X provider.")}</p>}
        <label htmlFor="official-account-id">{tx("Official X account ID")}</label><input id="official-account-id" name="accountId" required value={newAccountId} onChange={(event) => { setNewAccountId(event.target.value); setResolved(null); }} /><p>Resolve a handle above, or enter the provider&apos;s account identifier manually. Do not enter an internal database UUID.</p>
        <AccountFields nameControl={{ value: newDisplayName, onChange: (value) => { setNewDisplayName(value); setResolved(null); } }} />
      </fieldset>
      <button disabled={action.busy}>{tx("Add account")}</button>
    </form>}
    {account && <>
      <section className="review-panel"><h2>@{account.handle.replace(/^@/, "")} · {account.displayName}</h2>
        <p>Monitoring: {account.monitoringEnabled ? "Active" : "Paused"} · Health: {account.syncHealth}{blocked ? " · Blocked" : ""}</p>
        <dl className="facts"><dt>{tx("Last successful sync")}</dt><dd>{dateLabel(account.lastSuccessfulSyncAt)}</dd><dt>{tx("Last attempt")}</dt><dd>{dateLabel(account.lastSyncAttemptAt)}</dd><dt>{tx("Rate limit remaining / limit")}</dt><dd>{account.rateLimitRemaining ?? "Unknown"} / {account.rateLimitLimit ?? "Unknown"}</dd><dt>{tx("Rate limit resets")}</dt><dd>{dateLabel(account.rateLimitResetAt)}</dd><dt>{tx("Consecutive errors")}</dt><dd>{account.consecutiveErrors}</dd></dl>
        {account.lastError && <p role="alert">{account.lastError}</p>}
        {blocked && <p>Block reason: {blocked.reason}</p>}
        <button disabled={action.busy} onClick={() => action.run(async () => { await api.admin.setXAccountMonitoring(account.id, !account.monitoringEnabled); resource.refresh(); }, tx("Monitoring state updated."))}>{account.monitoringEnabled ? tx("Pause monitoring") : tx("Resume monitoring")}</button>
      </section>
      <form key={`${account.id}-${account.version}`} className="admin-form" onSubmit={(event) => {
        event.preventDefault(); const data = new FormData(event.currentTarget);
        action.run(async () => { await api.admin.editXAccount(account.id, { displayName: String(data.get("displayName")), topics: splitTopics(data.get("topics")), relevanceThreshold: Number(data.get("threshold")), monitoringEnabled: account.monitoringEnabled }); resource.refresh(); }, tx("Account updated."));
      }}><h2>{tx("Edit account")}</h2><AccountFields account={account} /><button disabled={action.busy}>{tx("Save account")}</button></form>
      <AccountSources key={account.id} accountId={account.accountId} />
      {resource.data?.capabilities.simulationEnabled && <form key={`post-${account.id}`} className="admin-form" onSubmit={(event) => {
        event.preventDefault(); const data = new FormData(event.currentTarget);
        action.run(() => api.admin.simulateXPost(account.id, { postId: String(data.get("postId")), canonicalUrl: String(data.get("url")), permittedText: String(data.get("text")), publishedAt: new Date(String(data.get("publishedAt"))).toISOString() }), tx("Simulated post accepted. Refresh sources and candidate queue after processing."));
      }}>
        <h2>{tx("Simulated permitted post")}</h2><p>{tx("For a configured simulation environment. Submit only content you have permission to use.")}</p>
        <label htmlFor="post-id">{tx("Post ID")}</label><input id="post-id" name="postId" required />
        <label htmlFor="post-url">{tx("Canonical URL")}</label><input id="post-url" name="url" type="url" required aria-describedby="post-url-help" />
        <p id="post-url-help">Paste the HTTPS x.com link for this account and Post ID. Share parameters such as ?s=20 and fragments are removed automatically.</p>
        <label htmlFor="post-text">{tx("Permitted text")}</label><textarea id="post-text" name="text" required maxLength={10000} />
        <label htmlFor="published-at">{tx("Published at")}</label><input id="published-at" name="publishedAt" type="datetime-local" required aria-describedby="published-at-help" />
        <p id="published-at-help">Use the original post&apos;s publication time in your local time zone, not a future date.</p>
        <button disabled={action.busy || !account.monitoringEnabled || !!blocked}>{tx("Simulate post")}</button>
      </form>}
      <form className="admin-form" onSubmit={(event) => {
        event.preventDefault(); const data = new FormData(event.currentTarget); const operation = String(data.get("operation"));
        action.run(async () => {
          if (operation === "remove") { await api.admin.removeXAccount(account.id, String(data.get("reason"))); setId(""); }
          else if (operation === "unblock") await api.admin.unblockAccount(account.accountId);
          else await api.admin.blockAccount(account.accountId, String(data.get("reason")));
          resource.refresh();
        }, tx("Selected account action applied."));
      }}>
        <h2>{tx("Account controls")}</h2><label htmlFor="account-operation">{tx("Action for selected account")}</label>
        <select id="account-operation" name="operation"><option value={blocked ? "unblock" : "block"}>{blocked ? tx("Unblock account") : tx("Block account from sourcing")}</option><option value="remove">{tx("Remove monitored account")}</option></select>
        <label htmlFor="account-reason">{tx("Account action reason")}</label><input id="account-reason" name="reason" required maxLength={500} />
        <label className="check"><input type="checkbox" required /> Apply this action only to @{account.handle.replace(/^@/, "")}</label><button disabled={action.busy}>{tx("Apply account action")}</button>
      </form>
    </>}
  </AdminSection>;
}
