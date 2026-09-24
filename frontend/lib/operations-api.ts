import { api, ApiError, type Page } from "./api";

export type AuditRecord = {
  id: string; actorId: string | null; action: string; targetType: string; targetId: string | null;
  occurredAt: string; metadata: Record<string, unknown>;
};
export type AuditFilters = { action?: string; targetType?: string; actorId?: string; targetId?: string; from?: string; to?: string };
export type FailedEvent = {
  id: string; eventId: string | null; eventType: string | null; aggregateId: string | null;
  originalTopic: string; originalPartition: number; originalOffset: number; consumerGroup: string | null;
  exceptionClass: string | null; exceptionMessage: string | null; deliveryAttempt: number;
  poisonMessage: boolean; status: string; failedAt: string; lastUpdatedAt: string;
};
export type Replay = {
  id: string; actorId: string; reason: string; dryRun: boolean; includePoison: boolean;
  messagesPerSecond: number; candidateCount: number; replayedCount: number; blockedCount: number;
  status: string; requestedAt: string; completedAt: string | null;
};
export type ReplayRecord = {
  id: string; failedEventId: string; eventId: string | null; aggregateId: string | null;
  originalTopic: string; outcome: string; detail: string | null; occurredAt: string;
};
export type WorkflowHealth = {
  checkedAt: string; pendingOutbox: number; oldestPendingOutboxAt: string | null;
  failedEvents: Record<string, number>; pendingReplays: number;
};
export type OperationsSummary = { status: string; checkedAt: string; details: string; failedEvents: Record<string, number>; workflow: WorkflowHealth };

const now = new Date().toISOString();
const failures: FailedEvent[] = [{
  id: "10000000-0000-4000-8000-000000000001", eventId: "10000000-0000-4000-8000-000000000002",
  eventType: "StoryAnalysisRequested", aggregateId: "10000000-0000-4000-8000-000000000003",
  originalTopic: "news.editorial.v1", originalPartition: 0, originalOffset: 12,
  consumerGroup: "demo-editorial", exceptionClass: "DemoProviderUnavailable", exceptionMessage: "Synthetic provider outage.",
  deliveryAttempt: 3, poisonMessage: false, status: "eligible", failedAt: now, lastUpdatedAt: now
}];
const fakeReplays: Replay[] = [];
const fakeRecords = new Map<string, ReplayRecord[]>();
const confirmations = new Map<string, string>();
const audits: AuditRecord[] = [{ id: crypto.randomUUID(), actorId: null, action: "DEMO_WORKSPACE_INITIALIZED", targetType: "demo", targetId: null, occurredAt: now, metadata: { mode: "fake" } }];
function paged<T>(items: T[], page: number, size: number): Page<T> { return { items: structuredClone(items.slice(page * size, (page + 1) * size)), page, size, total: items.length }; }
async function administrator() {
  const user = await api.auth.me();
  if (!user.roles.includes("ADMINISTRATOR")) throw new ApiError(403, { title: "Forbidden", status: 403, detail: "Administrator access is required." });
  return user;
}
function notFound(): never { throw new ApiError(404, { title: "Not found", status: 404, detail: "Replay not found." }); }

export const operationsApi = {
  async audit(filters: AuditFilters = {}, page = 0, size = 20): Promise<Page<AuditRecord>> {
    if (api.mode === "fake") {
      await administrator();
      return paged(audits.filter((record) => (!filters.action || record.action === filters.action)
        && (!filters.targetType || record.targetType === filters.targetType)
        && (!filters.actorId || record.actorId === filters.actorId) && (!filters.targetId || record.targetId === filters.targetId)
        && (!filters.from || record.occurredAt >= filters.from) && (!filters.to || record.occurredAt <= filters.to)), page, size);
    }
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    Object.entries(filters).forEach(([key, value]) => { if (value) query.set(key, value); });
    return api.request(`/api/v1/admin/audit-records?${query}`);
  },
  async summary(): Promise<OperationsSummary> {
    if (api.mode !== "fake") return api.request("/api/v1/admin/operations/summary");
    await administrator();
    const counts: Record<string, number> = {};
    failures.forEach(({ status }) => { counts[status] = (counts[status] || 0) + 1; });
    return { status: "demonstration", checkedAt: now, details: "Explicit fake-mode workflow data; no infrastructure is inspected.", failedEvents: counts,
      workflow: { checkedAt: now, pendingOutbox: 0, oldestPendingOutboxAt: null, failedEvents: counts, pendingReplays: 0 } };
  },
  async failures(status?: string, page = 0, size = 20): Promise<Page<FailedEvent>> {
    if (api.mode !== "fake") return api.request(`/api/v1/admin/operations/failed-events/page?page=${page}&size=${size}${status ? `&status=${encodeURIComponent(status)}` : ""}`);
    await administrator(); return paged(failures.filter((item) => !status || item.status === status), page, size);
  },
  async replays(page = 0, size = 20): Promise<Page<Replay>> {
    if (api.mode !== "fake") return api.request(`/api/v1/admin/operations/replays?page=${page}&size=${size}`);
    await administrator(); return paged(fakeReplays, page, size);
  },
  async replay(id: string): Promise<Replay> {
    if (api.mode !== "fake") return api.request(`/api/v1/admin/operations/replays/${encodeURIComponent(id)}`);
    await administrator(); return structuredClone(fakeReplays.find((item) => item.id === id) || notFound());
  },
  async records(id: string): Promise<ReplayRecord[]> {
    if (api.mode !== "fake") return api.request(`/api/v1/admin/operations/replays/${encodeURIComponent(id)}/records`);
    await administrator(); return structuredClone(fakeRecords.get(id) || notFound());
  },
  async preview(failedEventIds: string[], reason: string, messagesPerSecond: number, includePoison: boolean): Promise<Replay> {
    const command = { failedEventIds, reason, messagesPerSecond, includePoison, dryRun: true, maximumMessages: failedEventIds.length };
    if (api.mode !== "fake") return api.request("/api/v1/admin/operations/replays", { method: "POST", body: JSON.stringify(command) }, true);
    const user = await administrator();
    if (!reason.trim() || failedEventIds.length < 1 || failedEventIds.length > 100 || messagesPerSecond < 1 || messagesPerSecond > 20) {
      throw new ApiError(400, { title: "Invalid preview", status: 400, detail: "Select 1-100 events, a reason and a rate from 1 to 20." });
    }
    const selected = failures.filter((item) => failedEventIds.includes(item.id) && ((["eligible", "replay_failed"].includes(item.status) && (!item.poisonMessage || includePoison)) || includePoison && item.status === "poison"));
    const value: Replay = { id: crypto.randomUUID(), actorId: user.id, reason, dryRun: true, includePoison, messagesPerSecond,
      candidateCount: selected.length, replayedCount: 0, blockedCount: 0, status: "dry_run_complete", requestedAt: new Date().toISOString(), completedAt: new Date().toISOString() };
    fakeReplays.unshift(value);
    fakeRecords.set(value.id, selected.map((item) => ({ id: crypto.randomUUID(), failedEventId: item.id, eventId: item.eventId, aggregateId: item.aggregateId, originalTopic: item.originalTopic, outcome: "dry_run", detail: "eligible", occurredAt: value.requestedAt })));
    return structuredClone(value);
  },
  async confirm(id: string): Promise<Replay> {
    if (api.mode !== "fake") return api.request(`/api/v1/admin/operations/replays/${encodeURIComponent(id)}/confirm`, { method: "POST" }, true);
    const user = await administrator();
    const preview = fakeReplays.find((item) => item.id === id) || notFound();
    if (preview.actorId !== user.id) throw new ApiError(403, { title: "Forbidden", status: 403, detail: "Only the preview's administrator may confirm." });
    const previous = confirmations.get(id);
    if (previous) return operationsApi.replay(previous);
    if (!preview.dryRun || !preview.candidateCount || Date.parse(preview.requestedAt) < Date.now() - 900_000) {
      throw new ApiError(409, { title: "Invalid preview", status: 409, detail: "Run a new eligible dry run." });
    }
    const value: Replay = { ...preview, id: crypto.randomUUID(), dryRun: false, replayedCount: preview.candidateCount, status: "completed", requestedAt: new Date().toISOString(), completedAt: new Date().toISOString() };
    fakeReplays.unshift(value); confirmations.set(id, value.id);
    const records = fakeRecords.get(id) || [];
    fakeRecords.set(value.id, records.map((item) => ({ ...item, id: crypto.randomUUID(), outcome: "replayed", detail: "Explicit fake-mode replay." })));
    records.forEach((record) => { const item = failures.find((item) => item.id === record.failedEventId); if (item) item.status = "replayed"; });
    return structuredClone(value);
  }
};
