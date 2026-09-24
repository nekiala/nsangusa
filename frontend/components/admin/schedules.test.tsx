import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, type PublicationSchedule } from "@/lib/api";
import { PublicationSchedules } from "./schedules";

const schedule: PublicationSchedule = {
  id: "schedule-1", articleId: "article-1", publishAt: "2035-01-01T10:00:00Z", status: "scheduled",
  version: 4, articleVersion: 8, scheduledBy: "editor-1", updatedBy: "editor-1",
  createdAt: "2026-09-01T10:00:00Z", updatedAt: "2026-09-01T10:00:00Z", completedAt: null,
  lastAttemptAt: null, attemptCount: 0, lastError: null
};

beforeEach(() => {
  vi.spyOn(api.admin, "publicationPolicy").mockResolvedValue({
    policy: "HUMAN_REVIEW_ALWAYS", confidenceThreshold: 0.92, topicRules: { Ideas: 0.95 },
    approvedSourceAccounts: ["official_library"], humanPublicationAllowed: true,
    explanation: "Every article must be reviewed before publication."
  });
});
afterEach(() => vi.restoreAllMocks());

describe("publication schedule workspace", () => {
  it("shows current backend policy without edit controls and filters paginated queue entries", async () => {
    const list = vi.spyOn(api.admin, "publicationSchedules").mockResolvedValue({ items: [schedule], page: 0, size: 1, total: 2 });
    render(<PublicationSchedules />);
    expect(await screen.findByRole("link", { name: "Article article-1" })).toHaveAttribute("href", "/admin/editor/article-1");
    const policy = screen.getByRole("region", { name: "Current publication policy" });
    expect(policy).toHaveTextContent("HUMAN_REVIEW_ALWAYS");
    expect(policy).toHaveTextContent("92%");
    expect(policy).toHaveTextContent("Ideas: 95%");
    expect(policy).toHaveTextContent("official_library");
    expect(within(policy).queryByRole("textbox")).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByRole("navigation", { name: "Schedule pages" })).getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith("scheduled", 1));
    fireEvent.change(screen.getByLabelText("Schedule status"), { target: { value: "failed" } });
    await waitFor(() => expect(list).toHaveBeenLastCalledWith("failed", 0));
  });

  it("reschedules an exact selected instant then cancels using the refreshed schedule version", async () => {
    let current = schedule;
    vi.spyOn(api.admin, "publicationSchedules").mockImplementation(async () => ({ items: [current], page: 0, size: 20, total: 1 }));
    const reschedule = vi.spyOn(api.admin, "reschedulePublication").mockImplementation(async (_id, _version, publishAt) => {
      current = { ...current, publishAt, version: current.version + 1 }; return current;
    });
    const cancel = vi.spyOn(api.admin, "cancelPublication").mockImplementation(async () => {
      current = { ...current, status: "cancelled", version: current.version + 1 }; return current;
    });
    render(<PublicationSchedules />);
    await screen.findByLabelText("New publication time (your local time)");
    const selected = "2035-02-15T10:30";
    fireEvent.change(screen.getByLabelText("New publication time (your local time)"), { target: { value: selected } });
    fireEvent.click(screen.getByRole("button", { name: "Reschedule publication" }));
    await waitFor(() => expect(reschedule).toHaveBeenCalledWith(schedule.id, 4, new Date(selected).toISOString()));
    await waitFor(() => expect(screen.getByText(/Schedule version 5/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Cancel publication schedule" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledWith(schedule.id, 5));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Cancel publication schedule" })).not.toBeInTheDocument());
    expect(screen.getByText("cancelled", { selector: "strong" })).toBeInTheDocument();
  });

  it("surfaces optimistic conflicts and publication failures without claiming a successful mutation", async () => {
    vi.spyOn(api.admin, "publicationSchedules").mockResolvedValue({ items: [{ ...schedule, status: "failed", lastError: "Source account became ineligible.", attemptCount: 2 }], page: 0, size: 20, total: 1 });
    vi.spyOn(api.admin, "cancelPublication").mockRejectedValue(new ApiError(409, { title: "Conflict", status: 409, detail: "Schedule version changed. Refresh the queue." }));
    render(<PublicationSchedules />);
    expect(await screen.findByText(/Source account became ineligible/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel publication schedule" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Schedule version changed. Refresh the queue.");
    expect(screen.queryByText(/Publication schedule cancelled/)).not.toBeInTheDocument();
  });

  it("keeps completed publications read-only", async () => {
    vi.spyOn(api.admin, "publicationSchedules").mockResolvedValue({ items: [{ ...schedule, status: "published", completedAt: schedule.publishAt }], page: 0, size: 20, total: 1 });
    render(<PublicationSchedules />);
    await screen.findByRole("link", { name: "Article article-1" });
    expect(screen.queryByRole("button", { name: "Reschedule publication" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel publication schedule" })).not.toBeInTheDocument();
  });

  it("offers cancellation, never retry, when a legacy schedule has no captured article version", async () => {
    vi.spyOn(api.admin, "publicationSchedules").mockResolvedValue({
      items: [{ ...schedule, status: "failed", articleVersion: null, lastError: "Legacy schedule requires review." }],
      page: 0, size: 20, total: 1
    });
    const cancel = vi.spyOn(api.admin, "cancelPublication").mockResolvedValue({ ...schedule, status: "cancelled" });
    render(<PublicationSchedules />);
    expect(await screen.findByText(/captured article version is unavailable/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reschedule publication" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("New publication time (your local time)")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel publication schedule" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledWith(schedule.id, schedule.version));
  });
});
