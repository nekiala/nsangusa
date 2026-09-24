import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuditLogs, FailedEvents, OperationsHealth } from "./operations";
import { operationsApi, type FailedEvent, type Replay } from "@/lib/operations-api";

afterEach(() => vi.restoreAllMocks());
const failure: FailedEvent = { id: "failure-1", eventId: "event-1", eventType: "StoryAnalysisRequested", aggregateId: "story-1",
  originalTopic: "news.editorial.v1", originalPartition: 0, originalOffset: 9, consumerGroup: "editorial",
  exceptionClass: "Timeout", exceptionMessage: "<script>untrusted</script>", deliveryAttempt: 3, poisonMessage: false,
  status: "eligible", failedAt: "2026-09-01T10:00:00Z", lastUpdatedAt: "2026-09-01T10:00:00Z" };
const preview: Replay = { id: "preview-1", actorId: "admin", reason: "Reviewed provider recovery", dryRun: true,
  includePoison: false, messagesPerSecond: 1, candidateCount: 1, replayedCount: 0, blockedCount: 0,
  status: "dry_run_complete", requestedAt: "2026-09-01T10:00:00Z", completedAt: "2026-09-01T10:00:00Z" };

describe("administrative operations", () => {
  it("requires preview and explicit acknowledgment before replay confirmation", async () => {
    vi.spyOn(operationsApi, "failures").mockResolvedValue({ items: [failure], page: 0, size: 20, total: 1 });
    vi.spyOn(operationsApi, "replays").mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
    const create = vi.spyOn(operationsApi, "preview").mockResolvedValue(preview);
    vi.spyOn(operationsApi, "replay").mockResolvedValue(preview);
    vi.spyOn(operationsApi, "records").mockResolvedValue([{ id: "record", failedEventId: failure.id, eventId: failure.eventId, aggregateId: failure.aggregateId, originalTopic: failure.originalTopic, outcome: "dry_run", detail: "eligible", occurredAt: failure.failedAt }]);
    const confirm = vi.spyOn(operationsApi, "confirm").mockResolvedValue({ ...preview, id: "replay-1", dryRun: false, status: "pending" });
    const { container } = render(<FailedEvents />);
    expect(screen.getByRole("button", { name: "Run replay dry run" })).toBeDisabled();
    fireEvent.click(await screen.findByLabelText("Select event failure-1"));
    fireEvent.change(screen.getByLabelText("Replay reason"), { target: { value: preview.reason } });
    fireEvent.click(screen.getByRole("button", { name: "Run replay dry run" }));
    const button = await screen.findByRole("button", { name: "Confirm replay" });
    expect(button).toBeDisabled(); expect(confirm).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledWith([failure.id], preview.reason, 1, false);
    expect(container.querySelector("script")).toBeNull();
    fireEvent.click(screen.getByLabelText("I reviewed these exact messages and accept the replay effects."));
    fireEvent.click(button);
    await waitFor(() => expect(confirm).toHaveBeenCalledWith(preview.id));
    expect(await screen.findByText(/acceptance is not delivery confirmation/)).toBeVisible();
  });

  it("filters and pages actual audit rows while escaping stored metadata", async () => {
    const query = vi.spyOn(operationsApi, "audit").mockResolvedValue({ items: [{ id: "audit-1", actorId: "actor", action: "ARTICLE_APPROVED", targetType: "article", targetId: "article", occurredAt: failure.failedAt, metadata: { note: "<img onerror=alert(1)>" } }], page: 0, size: 20, total: 22 });
    const { container } = render(<AuditLogs />);
    await screen.findByRole("heading", { name: "ARTICLE_APPROVED" });
    fireEvent.change(screen.getByLabelText("Action"), { target: { value: "ARTICLE_APPROVED" } });
    fireEvent.click(screen.getByRole("button", { name: "Search audit records" }));
    await waitFor(() => expect(query).toHaveBeenCalledWith({ action: "ARTICLE_APPROVED" }, 0));
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(query).toHaveBeenCalledWith({ action: "ARTICLE_APPROVED" }, 1));
    expect(container.querySelector("img")).toBeNull();
  });

  it("distinguishes workflow counts from infrastructure health", async () => {
    vi.spyOn(operationsApi, "summary").mockResolvedValue({ status: "available", checkedAt: failure.failedAt,
      details: "Database counts only.", failedEvents: { eligible: 305 },
      workflow: { checkedAt: failure.failedAt, pendingOutbox: 12, oldestPendingOutboxAt: failure.failedAt, failedEvents: { eligible: 305 }, pendingReplays: 2 } });
    render(<OperationsHealth />);
    expect(await screen.findByText("305")).toBeVisible();
    expect(screen.getByText(/do not certify Kafka connectivity/)).toBeVisible();
    expect(screen.getByText("12")).toBeVisible();
  });
});
