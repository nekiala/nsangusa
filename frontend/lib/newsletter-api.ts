import { api, ApiError, type Page } from "./api";

export type Frequency = "immediate" | "daily" | "weekly" | "all";
export type NewsletterPreference = { status: string; frequency: Frequency; expiresAt: string };
export type Subscription = {
  id: string; status: string; frequency: Frequency; consentSource: string; consentAt: string;
  verifiedAt: string | null; unsubscribedAt: string | null; accountLinked: boolean;
};
export type Consent = { id: string; action: string; source: string; occurredAt: string };
export type Campaign = {
  id: string; campaignKey: string; articleId: string | null; campaignType: string; status: string;
  createdAt: string; completedAt: string | null; recipientCount: number; acceptedCount: number;
  failedCount: number; reconciliationCount: number;
};
export type Delivery = {
  id: string; subscriptionId: string; articleId: string; campaignKey: string; status: string;
  attemptCount: number; failureCode: string | null; createdAt: string; providerAcceptedAt: string | null;
  deliveryConfirmedAt: string | null; attemptStartedAt: string | null; providerMessageId: string | null;
  providerIdempotencyApplied: boolean; reconciledAt: string | null;
  reconciliationOutcome: string | null; reconciliationEvidence: string | null;
};
export type DeliveryAttempt = {
  id: string; attemptNumber: number; status: string; startedAt: string; completedAt: string | null;
  failureCode: string | null; providerMessageId: string | null;
};
export type Suppression = { id: string; reason: string; createdAt: string; subscriptionId: string | null };
export type Reconciliation = {
  outcome: "provider_accepted" | "not_sent" | "abandoned"; evidenceReference: string; providerMessageId: string | null;
};

const root = "/api/v1/newsletter";
const admin = `${root}/admin`;
const fakeId = "00000000-0000-4000-8000-000000000091";
const fakeDate = "2026-09-10T08:00:00Z";
const fakeSubscription: Subscription = {
  id: fakeId, status: "confirmed", frequency: "weekly", consentSource: "explicit-demo-fixture",
  consentAt: fakeDate, verifiedAt: fakeDate, unsubscribedAt: null, accountLinked: false,
};
const fakeDelivery: Delivery = {
  id: "00000000-0000-4000-8000-000000000092", subscriptionId: fakeId, articleId: fakeId,
  campaignKey: "demo:weekly", status: "reconciliation_required", attemptCount: 1, failureCode: "acceptance_unknown",
  createdAt: fakeDate, providerAcceptedAt: null, deliveryConfirmedAt: null, attemptStartedAt: fakeDate,
  providerMessageId: null, providerIdempotencyApplied: false, reconciledAt: null,
  reconciliationOutcome: null, reconciliationEvidence: null,
};
const fakeCampaign: Campaign = {
  id: fakeId, campaignKey: "demo:weekly", articleId: null, campaignType: "weekly", status: "completed",
  createdAt: fakeDate, completedAt: fakeDate, recipientCount: 1, acceptedCount: 0, failedCount: 0, reconciliationCount: 1,
};
const requestMessage = "If this address has a confirmed subscription, a preference link will be emailed.";
const pendingMutations = new Map<string, string>();

async function mutate(path: string, body: object) {
  const payload = JSON.stringify(body);
  const fingerprint = `${path}:${payload}`;
  if (!pendingMutations.has(fingerprint) && pendingMutations.size >= 100) {
    throw new Error("Too many newsletter changes have unconfirmed outcomes. Reconcile them before making more changes.");
  }
  const requestKey = pendingMutations.get(fingerprint) || crypto.randomUUID();
  pendingMutations.set(fingerprint, requestKey);
  try {
    await api.request<void>(path, { method: "POST", body: payload, headers: { "Idempotency-Key": requestKey } }, true, requestKey);
    pendingMutations.delete(fingerprint);
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408) {
      pendingMutations.delete(fingerprint);
    }
    throw error;
  }
}

function query(values: Record<string, string | number | undefined>) {
  return `?${new URLSearchParams(Object.entries(values).filter(([, value]) => value !== undefined && value !== "")
    .map(([key, value]) => [key, String(value)]))}`;
}

function fixturePage<T>(items: T[], page: number, size: number): Page<T> {
  return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length };
}

export const newsletterApi = {
  mode: api.mode,
  async requestLink(email: string) {
    if (api.mode === "fake") return { message: `${requestMessage} Demo mode does not send email.` };
    return api.request<{ message: string }>(`${root}/preferences/link`, { method: "POST", body: JSON.stringify({ email }) }, true);
  },
  async preferences(id: string, token: string): Promise<NewsletterPreference> {
    if (api.mode === "fake") {
      if (id !== fakeId || token !== "demo-preference-token") throw new Error("Invalid demo preference link.");
      return { status: fakeSubscription.status, frequency: fakeSubscription.frequency, expiresAt: "2099-01-01T00:00:00Z" };
    }
    return api.request<NewsletterPreference>(`${root}/preferences${query({ id, token })}`);
  },
  async updatePreferences(id: string, token: string, frequency: Frequency) {
    if (api.mode === "fake") {
      await this.preferences(id, token);
      fakeSubscription.frequency = frequency;
      return;
    }
    return mutate(`${root}/preferences${query({ id, token })}`, { frequency });
  },
  async subscriptions(status = "", frequency = "", page = 0, size = 20) {
    if (api.mode === "fake") return fixturePage([fakeSubscription].filter((item) =>
      (!status || item.status === status) && (!frequency || item.frequency === frequency)), page, size);
    return api.request<Page<Subscription>>(`${admin}/subscriptions${query({ status, frequency, page, size })}`);
  },
  async consent(id: string, page = 0, size = 20) {
    if (api.mode === "fake") return fixturePage<Consent>(id === fakeId
      ? [{ id: fakeId, action: "confirmed", source: "explicit-demo-fixture", occurredAt: fakeDate }] : [], page, size);
    return api.request<Page<Consent>>(`${admin}/subscriptions/${encodeURIComponent(id)}/consent${query({ page, size })}`);
  },
  async campaigns(type = "", status = "", page = 0, size = 20) {
    if (api.mode === "fake") return fixturePage([fakeCampaign].filter((item) =>
      (!type || item.campaignType === type) && (!status || item.status === status)), page, size);
    return api.request<Page<Campaign>>(`${admin}/campaigns${query({ type, status, page, size })}`);
  },
  async deliveries(status = "", subscriptionId = "", campaignKey = "", page = 0, size = 20) {
    if (api.mode === "fake") return fixturePage([fakeDelivery].filter((item) =>
      (!status || item.status === status) && (!subscriptionId || item.subscriptionId === subscriptionId)
      && (!campaignKey || item.campaignKey === campaignKey)), page, size);
    return api.request<Page<Delivery>>(`${admin}/delivery-history${query({ status, subscriptionId, campaignKey, page, size })}`);
  },
  async attempts(id: string, page = 0, size = 20) {
    if (api.mode === "fake") return fixturePage<DeliveryAttempt>(id === fakeDelivery.id
      ? [{ id: fakeId, attemptNumber: 1, status: "reconciliation_required", startedAt: fakeDate,
        completedAt: fakeDate, failureCode: "acceptance_unknown", providerMessageId: null }] : [], page, size);
    return api.request<Page<DeliveryAttempt>>(`${admin}/deliveries/${encodeURIComponent(id)}/attempts${query({ page, size })}`);
  },
  async suppressions(reason = "", page = 0, size = 20) {
    if (api.mode === "fake") return fixturePage<Suppression>([], page, size);
    return api.request<Page<Suppression>>(`${admin}/suppressions${query({ reason, page, size })}`);
  },
  async reconcile(id: string, request: Reconciliation) {
    if (api.mode === "fake") {
      if (id !== fakeDelivery.id || fakeDelivery.status !== "reconciliation_required") throw new Error("Demo delivery cannot be reconciled.");
      Object.assign(fakeDelivery, { status: "reconciled", reconciledAt: new Date().toISOString(),
        reconciliationOutcome: request.outcome, reconciliationEvidence: request.evidenceReference });
      return;
    }
    return mutate(`${admin}/deliveries/${encodeURIComponent(id)}/reconciliation`, request);
  },
};
