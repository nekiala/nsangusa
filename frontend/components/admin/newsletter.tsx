"use client";

import { useCallback, useState } from "react";
import { newsletterApi, type Delivery, type Reconciliation } from "@/lib/newsletter-api";
import { AdminSection, dateLabel, Feedback, Pagination, useAction, useResource } from "./shared";

function ConsentHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const load = useCallback(() => newsletterApi.consent(id, page), [id, page]);
  const history = useResource(load);
  return <section aria-label="Consent history">
    <h3>Consent history</h3><Feedback error={history.error} />
    {history.loading && <p role="status">Loading consent…</p>}
    {!history.loading && !history.error && history.data?.total === 0 && <p>No consent events recorded.</p>}
    <ul>{history.data?.items.map((event) => <li key={event.id}>{event.action} · {event.source} · {dateLabel(event.occurredAt)}</li>)}</ul>
    {history.data && <Pagination page={page} size={history.data.size} total={history.data.total} onPage={setPage} label="Consent pages" />}
  </section>;
}

function Subscriptions({ onDeliveries }: { onDeliveries: (_id: string) => void }) {
  const [status, setStatus] = useState("");
  const [frequency, setFrequency] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState("");
  const load = useCallback(() => newsletterApi.subscriptions(status, frequency, page), [status, frequency, page]);
  const subscriptions = useResource(load);
  return <>
    <div className="admin-form">
      <label htmlFor="subscription-status">Subscription status</label><select id="subscription-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
        <option value="">All statuses</option>{["pending", "confirmed", "unsubscribed", "suppressed"].map((value) => <option key={value}>{value}</option>)}
      </select>
      <label htmlFor="subscription-frequency">Subscription frequency</label><select id="subscription-frequency" value={frequency} onChange={(event) => { setFrequency(event.target.value); setPage(0); }}>
        <option value="">All frequencies</option>{["immediate", "daily", "weekly", "all"].map((value) => <option key={value}>{value}</option>)}
      </select>
      <button onClick={subscriptions.refresh} disabled={subscriptions.loading}>Refresh subscriptions</button>
    </div>
    <Feedback error={subscriptions.error} />{subscriptions.loading && <p role="status">Loading subscriptions…</p>}
    {!subscriptions.loading && !subscriptions.error && subscriptions.data?.total === 0 && <p>No subscriptions match these filters.</p>}
    <ul className="queue-list">{subscriptions.data?.items.map((subscription) => <li key={subscription.id}>
      <h2>Subscription <span className="identifier">{subscription.id}</span></h2>
      <p>{subscription.status} · {subscription.frequency} · {subscription.accountLinked ? "Account linked" : "Email-only subscriber"}</p>
      <dl className="facts"><dt>Consent source</dt><dd>{subscription.consentSource}</dd><dt>Consent requested</dt><dd>{dateLabel(subscription.consentAt)}</dd>
        <dt>Verified</dt><dd>{dateLabel(subscription.verifiedAt)}</dd><dt>Unsubscribed/suppressed</dt><dd>{dateLabel(subscription.unsubscribedAt)}</dd></dl>
      <button onClick={() => setSelected(selected === subscription.id ? "" : subscription.id)} aria-expanded={selected === subscription.id}>View consent history</button>
      <button onClick={() => onDeliveries(subscription.id)}>View subscription deliveries</button>
      {selected === subscription.id && <ConsentHistory id={subscription.id} />}
    </li>)}</ul>
    {subscriptions.data && <Pagination page={page} size={subscriptions.data.size} total={subscriptions.data.total} onPage={setPage} label="Subscription pages" />}
  </>;
}

function Campaigns({ onDeliveries }: { onDeliveries: (_key: string) => void }) {
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const load = useCallback(() => newsletterApi.campaigns(type, status, page), [type, status, page]);
  const campaigns = useResource(load);
  return <>
    <p>Campaign completion means dispatch processing finished, not that every message reached an inbox.</p>
    <div className="admin-form">
      <label htmlFor="campaign-type">Campaign type</label><select id="campaign-type" value={type} onChange={(event) => { setType(event.target.value); setPage(0); }}>
        <option value="">All types</option>{["immediate", "daily", "weekly"].map((value) => <option key={value}>{value}</option>)}
      </select>
      <label htmlFor="campaign-status">Campaign status</label><select id="campaign-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
        <option value="">All statuses</option>{["pending", "dispatching", "completed"].map((value) => <option key={value}>{value}</option>)}
      </select>
      <button onClick={campaigns.refresh} disabled={campaigns.loading}>Refresh campaigns</button>
    </div>
    <Feedback error={campaigns.error} />{campaigns.loading && <p role="status">Loading campaigns…</p>}
    {!campaigns.loading && !campaigns.error && campaigns.data?.total === 0 && <p>No campaigns match these filters.</p>}
    <ul className="queue-list">{campaigns.data?.items.map((campaign) => <li key={campaign.id}>
      <h2 className="identifier">{campaign.campaignKey}</h2><p>{campaign.campaignType} · {campaign.status}</p>
      <p>Created {dateLabel(campaign.createdAt)} · Completed {dateLabel(campaign.completedAt)}</p>
      <p>{campaign.recipientCount} recipient reservations · {campaign.acceptedCount} provider acceptances · {campaign.failedCount} failed/bounced · {campaign.reconciliationCount} awaiting reconciliation</p>
      <button onClick={() => onDeliveries(campaign.campaignKey)}>Inspect campaign deliveries</button>
    </li>)}</ul>
    {campaigns.data && <Pagination page={page} size={campaigns.data.size} total={campaigns.data.total} onPage={setPage} label="Campaign pages" />}
  </>;
}

function AttemptHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const load = useCallback(() => newsletterApi.attempts(id, page), [id, page]);
  const attempts = useResource(load);
  return <section aria-label="Delivery attempt history">
    <h3>Delivery attempts</h3><p>Older attempts without durable records cannot be reconstructed.</p>
    <Feedback error={attempts.error} />{attempts.loading && <p role="status">Loading attempts…</p>}
    {!attempts.loading && !attempts.error && attempts.data?.total === 0 && <p>No individual attempts recorded.</p>}
    <ul>{attempts.data?.items.map((attempt) => <li key={attempt.id}>
      Attempt {attempt.attemptNumber}: {attempt.status} · {dateLabel(attempt.startedAt)} · {attempt.failureCode || "No recorded error"}
      {attempt.providerMessageId && <span className="identifier"> · Provider message {attempt.providerMessageId}</span>}
    </li>)}</ul>
    {attempts.data && <Pagination page={page} size={attempts.data.size} total={attempts.data.total} onPage={setPage} label="Attempt pages" />}
  </section>;
}

function Reconcile({ delivery, onChange, locked }: { delivery: Delivery; onChange: () => void; locked: boolean }) {
  const action = useAction();
  const [outcome, setOutcome] = useState<Reconciliation["outcome"]>("abandoned");
  const [evidence, setEvidence] = useState("");
  const [messageId, setMessageId] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  return <form className="admin-form" aria-label="Reconcile uncertain send" onSubmit={(event) => {
    event.preventDefault();
    if (!confirmed || action.busy || locked) return;
    action.run(async () => {
      await newsletterApi.reconcile(delivery.id, { outcome, evidenceReference: evidence, providerMessageId: messageId || null });
      onChange();
    }, "Reconciliation recorded. No message was resent.");
  }}>
    <h3>Record operator reconciliation</h3>
    <p>Check provider records first. This records your evidence, not an automated provider verification. Every outcome closes this delivery without resending.</p>
    <label htmlFor={`outcome-${delivery.id}`}>Reconciliation outcome</label>
    <select id={`outcome-${delivery.id}`} value={outcome} disabled={locked || action.busy} onChange={(event) => setOutcome(event.target.value as Reconciliation["outcome"])}>
      <option value="abandoned">Abandon without resend</option><option value="not_sent">Provider evidence: not sent</option><option value="provider_accepted">Provider evidence: accepted</option>
    </select>
    <label htmlFor={`evidence-${delivery.id}`}>Evidence reference (ticket or provider record, no personal data)</label>
    <input id={`evidence-${delivery.id}`} required minLength={3} maxLength={200} pattern="[A-Za-z0-9][A-Za-z0-9._:/#\-]{2,199}" value={evidence} disabled={locked || action.busy} onChange={(event) => setEvidence(event.target.value)} />
    <label htmlFor={`message-${delivery.id}`}>Provider message identifier</label>
    <input id={`message-${delivery.id}`} required={outcome === "provider_accepted"} maxLength={200} value={messageId} disabled={locked || action.busy} onChange={(event) => setMessageId(event.target.value)} />
    <label><input type="checkbox" checked={confirmed} disabled={locked || action.busy} onChange={(event) => setConfirmed(event.target.checked)} />I reviewed the evidence and understand this will not resend.</label>
    <button disabled={!confirmed || locked || action.busy}>Record reconciliation</button><Feedback error={action.error} status={action.status} />
  </form>;
}

function Deliveries({ subscriptionId, campaignKey, clear }: { subscriptionId: string; campaignKey: string; clear: () => void }) {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState("");
  const [checkedAt] = useState(() => Date.now());
  const load = useCallback(() => newsletterApi.deliveries(status, subscriptionId, campaignKey, page), [status, subscriptionId, campaignKey, page]);
  const deliveries = useResource(load);
  return <>
    <p>Provider acceptance is not inbox delivery. Only a signed delivery callback establishes delivery confirmation. Uncertain sends require human reconciliation; there is no resend action.</p>
    {(subscriptionId || campaignKey) && <p>Filtered by {subscriptionId || campaignKey} <button onClick={clear}>Clear recipient/campaign filter</button></p>}
    <div className="admin-form">
      <label htmlFor="delivery-status">Delivery status</label><select id="delivery-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
        <option value="">All statuses</option>{["pending", "provider_accepted", "delivery_confirmed", "failed", "bounced", "reconciliation_required", "reconciled"].map((value) => <option key={value}>{value}</option>)}
      </select><button onClick={deliveries.refresh} disabled={deliveries.loading}>Refresh deliveries</button>
    </div>
    <Feedback error={deliveries.error} />{deliveries.loading && <p role="status">Loading deliveries…</p>}
    {!deliveries.loading && !deliveries.error && deliveries.data?.total === 0 && <p>No deliveries match these filters.</p>}
    <ul className="queue-list">{deliveries.data?.items.map((delivery) => <li key={delivery.id}>
      <h2 className="identifier">{delivery.campaignKey}</h2><p>Subscription {delivery.subscriptionId}</p>
      <p><strong>{delivery.status}</strong> · {delivery.attemptCount} attempts · {delivery.failureCode || "No recorded error"}</p>
      <p>Provider accepted: {dateLabel(delivery.providerAcceptedAt)} · Delivery confirmed: {dateLabel(delivery.deliveryConfirmedAt)}</p>
      <p>Provider idempotency applied: {delivery.providerIdempotencyApplied ? "Yes" : "No — never automatically retry"}</p>
      {delivery.reconciledAt && <p>Reconciled {dateLabel(delivery.reconciledAt)}: {delivery.reconciliationOutcome} · Evidence: {delivery.reconciliationEvidence}</p>}
      <button aria-expanded={selected === delivery.id} onClick={() => setSelected(selected === delivery.id ? "" : delivery.id)}>Inspect delivery attempts</button>
      {selected === delivery.id && <AttemptHistory id={delivery.id} />}
      {(["reconciliation_required", "failed"].includes(delivery.status)
        || (delivery.status === "pending" && !!delivery.attemptStartedAt && Date.parse(delivery.attemptStartedAt) < checkedAt - 600_000))
        && <Reconcile delivery={delivery} locked={deliveries.loading || !!deliveries.error} onChange={deliveries.refresh} />}
    </li>)}</ul>
    {deliveries.data && <Pagination page={page} size={deliveries.data.size} total={deliveries.data.total} onPage={setPage} label="Delivery pages" />}
  </>;
}

function Suppressions() {
  const [reason, setReason] = useState("");
  const [page, setPage] = useState(0);
  const load = useCallback(() => newsletterApi.suppressions(reason, page), [reason, page]);
  const suppressions = useResource(load);
  return <>
    <p>Suppression remains in force after unsubscribe or preference changes. Email addresses and address hashes are not exposed.</p>
    <div className="admin-form">
      <label htmlFor="suppression-reason">Suppression reason</label><select id="suppression-reason" value={reason} onChange={(event) => { setReason(event.target.value); setPage(0); }}>
        <option value="">All reasons</option><option value="bounce">bounce</option><option value="complaint">complaint</option>
      </select><button onClick={suppressions.refresh} disabled={suppressions.loading}>Refresh suppressions</button>
    </div><Feedback error={suppressions.error} />{suppressions.loading && <p role="status">Loading suppressions…</p>}
    {!suppressions.loading && !suppressions.error && suppressions.data?.total === 0 && <p>No suppressions match these filters.</p>}
    <ul className="queue-list">{suppressions.data?.items.map((suppression) => <li key={suppression.id}>
      <h2>{suppression.reason}</h2><p>Subscription {suppression.subscriptionId || "No linked subscription"} · {dateLabel(suppression.createdAt)}</p>
    </li>)}</ul>
    {suppressions.data && <Pagination page={page} size={suppressions.data.size} total={suppressions.data.total} onPage={setPage} label="Suppression pages" />}
  </>;
}

export function NewsletterWorkspace() {
  const [section, setSection] = useState("subscriptions");
  const [subscriptionId, setSubscriptionId] = useState("");
  const [campaignKey, setCampaignKey] = useState("");
  return <AdminSection title="Newsletter management" description="Administrator-only consent, campaign, delivery and suppression records. Personal data is minimized; actions are audited.">
    {newsletterApi.mode === "fake" && <p role="note">Demonstration fixtures — no real email or provider verification.</p>}
    <nav aria-label="Newsletter management sections" className="workflow-actions">
      {["subscriptions", "campaigns", "deliveries", "suppressions"].map((value) => <button key={value} aria-pressed={section === value} onClick={() => setSection(value)}>{value[0].toUpperCase() + value.slice(1)}</button>)}
    </nav>
    {section === "subscriptions" && <Subscriptions onDeliveries={(id) => { setSubscriptionId(id); setCampaignKey(""); setSection("deliveries"); }} />}
    {section === "campaigns" && <Campaigns onDeliveries={(key) => { setCampaignKey(key); setSubscriptionId(""); setSection("deliveries"); }} />}
    {section === "deliveries" && <Deliveries key={`${subscriptionId}:${campaignKey}`} subscriptionId={subscriptionId} campaignKey={campaignKey} clear={() => { setSubscriptionId(""); setCampaignKey(""); }} />}
    {section === "suppressions" && <Suppressions />}
  </AdminSection>;
}
