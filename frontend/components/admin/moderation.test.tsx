import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { moderationApi, type ModerationItem } from "@/lib/moderation-api";
import { ApiError } from "@/lib/api";
import { ModerationWorkspace } from "./moderation";

const item: ModerationItem = { id: "c1", articleId: "a1", authorId: "u1", body: "A pending comment", parentId: null, state: "pending", spamScore: .6, spamReason: "Many links", openReports: 2, createdAt: "2026-09-12T12:00:00Z", editedAt: null, deleted: false, deletedByAuthor: false, version: 3, articleHeadline: "Published article", articleSlug: "article" };
afterEach(() => vi.restoreAllMocks());
function queue() {
  vi.spyOn(moderationApi, "queue").mockResolvedValue({ items: [item], page: 0, size: 20, total: 21 });
  vi.spyOn(moderationApi, "detail").mockResolvedValue(item);
  vi.spyOn(moderationApi, "history").mockResolvedValue([]);
  vi.spyOn(moderationApi, "user").mockResolvedValue({ id: "u1", displayName: "Alex Reader", staff: false, active: true });
}
describe("moderation workspace", () => {
  it("denies readers and editors without requesting moderation data", () => {
    const request = vi.spyOn(moderationApi, "queue");
    render(<ModerationWorkspace roles={["EDITOR"]} />);
    expect(screen.getByRole("alert")).toHaveTextContent("cannot access community moderation");
    expect(request).not.toHaveBeenCalled();
  });

  it("discovers bounded comments and records a reasoned moderation action using the version", async () => {
    queue(); const moderate = vi.spyOn(moderationApi, "moderate").mockResolvedValue();
    render(<ModerationWorkspace roles={["MODERATOR"]} />);
    fireEvent.click(await screen.findByRole("button", { name: "Review comment" }));
    expect(await screen.findByText("Spam score: 0.6. Many links")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Decision"), { target: { value: "spam" } });
    fireEvent.change(screen.getByLabelText("Decision reason"), { target: { value: "Repeated promotion" } });
    fireEvent.click(screen.getByRole("button", { name: "Record decision" }));
    await waitFor(() => expect(moderate).toHaveBeenCalledWith(item, "spam", "Repeated promotion"));
  });

  it("filters and pages the server queue", async () => {
    queue(); render(<ModerationWorkspace roles={["MODERATOR"]} />);
    await screen.findByText("A pending comment");
    fireEvent.change(screen.getByLabelText("Comment state"), { target: { value: "rejected" } });
    await waitFor(() => expect(moderationApi.queue).toHaveBeenCalledWith("rejected", 0));
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(moderationApi.queue).toHaveBeenCalledWith("rejected", 1));
  });

  it("opens reported comments without entering an identifier", async () => {
    queue();
    vi.spyOn(moderationApi, "reports").mockResolvedValue([{ id: "r1", commentId: "c1", reporterId: "u2", reason: "harassment", details: "Targeted abuse", status: "open", createdAt: item.createdAt }]);
    render(<ModerationWorkspace roles={["MODERATOR"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Abuse reports" }));
    fireEvent.click(await screen.findByRole("button", { name: "Inspect reported comment" }));
    expect(await screen.findByLabelText("Decision reason")).toBeInTheDocument();
    expect(moderationApi.detail).toHaveBeenCalledWith("c1");
  });

  it("discovers accounts and suspends with an audited reason and version", async () => {
    queue();
    vi.spyOn(moderationApi, "users").mockResolvedValue([{ id: "u1", displayName: "Alex Reader", staff: false, active: true }]);
    const value = { userId: "u1", status: "allowed", suspendedUntil: null, reason: null, moderatorId: null, updatedAt: "", version: -1 };
    vi.spyOn(moderationApi, "privilege").mockResolvedValue(value);
    vi.spyOn(moderationApi, "privilegeHistory").mockResolvedValue([]);
    const suspend = vi.spyOn(moderationApi, "setPrivilege").mockResolvedValue();
    render(<ModerationWorkspace roles={["MODERATOR"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Commenting privileges" }));
    fireEvent.change(screen.getByLabelText("Search accounts"), { target: { value: "Alex" } });
    fireEvent.click(screen.getByRole("button", { name: "Search accounts" }));
    fireEvent.click(await screen.findByRole("button", { name: "Manage Alex Reader" }));
    fireEvent.change(await screen.findByLabelText("Privilege change reason"), { target: { value: "Repeated harassment" } });
    fireEvent.click(screen.getByRole("button", { name: "Save commenting privilege" }));
    await waitFor(() => expect(suspend).toHaveBeenCalledWith(value, "suspend", "Repeated harassment", null));
  });

  it("lets moderators inspect but not modify global policies", async () => {
    queue();
    vi.spyOn(moderationApi, "globalSettings").mockResolvedValue({ enabled: true, requireApproval: true, editingWindowMinutes: 15, reviewSpamThreshold: .55, rejectSpamThreshold: .9, reportEscalationThreshold: 3, updatedAt: "", version: -1 });
    vi.spyOn(moderationApi, "articles").mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
    render(<ModerationWorkspace roles={["MODERATOR"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Comment policies" }));
    expect(await screen.findByRole("button", { name: "Save global comment policy" })).toBeDisabled();
    expect(screen.getByLabelText("Enable comments globally")).toBeDisabled();
  });

  it("administrators change global and inherited per-article policies using loaded versions", async () => {
    queue();
    const global = { enabled: true, requireApproval: true, editingWindowMinutes: 15, reviewSpamThreshold: .55, rejectSpamThreshold: .9, reportEscalationThreshold: 3, updatedAt: "", version: 6 };
    vi.spyOn(moderationApi, "globalSettings").mockResolvedValue(global);
    vi.spyOn(moderationApi, "articles").mockResolvedValue({ items: [{ id: "a1", headline: "Community article", slug: "community", state: "PUBLISHED", commentsEnabled: true }], page: 0, size: 20, total: 1 });
    vi.spyOn(moderationApi, "articleSettings").mockResolvedValue({ articleId: "a1", enabledOverride: null, requireApprovalOverride: null, updatedAt: "", version: -1 });
    const saveGlobal = vi.spyOn(moderationApi, "updateGlobalSettings").mockResolvedValue();
    const saveArticle = vi.spyOn(moderationApi, "updateArticleSettings").mockResolvedValue();
    render(<ModerationWorkspace roles={["ADMINISTRATOR"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Comment policies" }));
    fireEvent.click(await screen.findByLabelText("Enable comments globally"));
    fireEvent.click(screen.getByRole("button", { name: "Save global comment policy" }));
    await waitFor(() => expect(saveGlobal).toHaveBeenCalledWith({ ...global, enabled: false }));
    fireEvent.click(screen.getByRole("button", { name: "Comment policy for Community article" }));
    fireEvent.change(await screen.findByLabelText("Article comment availability"), { target: { value: "false" } });
    fireEvent.click(screen.getByRole("button", { name: "Save article comment policy" }));
    await waitFor(() => expect(saveArticle).toHaveBeenCalledWith(expect.objectContaining({ articleId: "a1", enabledOverride: false, requireApprovalOverride: null, version: -1 })));
  });

  it("preserves a rejected moderation reason and offers a reload after a version conflict", async () => {
    queue();
    vi.spyOn(moderationApi, "moderate").mockRejectedValue(new ApiError(409, { status: 409, title: "Conflict", detail: "The comment changed. Refresh and retry." }));
    render(<ModerationWorkspace roles={["MODERATOR"]} />);
    fireEvent.click(await screen.findByRole("button", { name: "Review comment" }));
    fireEvent.change(await screen.findByLabelText("Decision reason"), { target: { value: "Policy checked" } });
    fireEvent.click(screen.getByRole("button", { name: "Record decision" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Refresh and retry");
    expect(screen.getByLabelText("Decision reason")).toHaveValue("Policy checked");
    expect(screen.getByRole("button", { name: "Reload comment and history" })).toBeEnabled();
  });

  it("restores suspended accounts and displays historical reasons and expiration", async () => {
    queue();
    vi.spyOn(moderationApi, "users").mockResolvedValue([{ id: "u1", displayName: "Alex Reader", staff: false, active: true }]);
    const privilege = { userId: "u1", status: "suspended", suspendedUntil: null, reason: "Previous decision", moderatorId: "mod1", updatedAt: "", version: 2 };
    vi.spyOn(moderationApi, "privilege").mockResolvedValue(privilege);
    vi.spyOn(moderationApi, "privilegeHistory").mockResolvedValue([{ id: "h1", action: "suspended", reason: "Earlier abuse", moderatorId: "mod1", createdAt: item.createdAt, suspendedUntil: null }]);
    const restore = vi.spyOn(moderationApi, "setPrivilege").mockResolvedValue();
    render(<ModerationWorkspace roles={["MODERATOR"]} />);
    fireEvent.click(screen.getByRole("button", { name: "Commenting privileges" }));
    fireEvent.change(screen.getByLabelText("Search accounts"), { target: { value: "Alex" } });
    fireEvent.click(screen.getByRole("button", { name: "Search accounts" }));
    fireEvent.click(await screen.findByRole("button", { name: "Manage Alex Reader" }));
    expect(await screen.findByText("Earlier abuse")).toBeInTheDocument();
    expect(screen.getByText("Until: Indefinite")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Privilege action"), { target: { value: "restore" } });
    fireEvent.change(screen.getByLabelText("Privilege change reason"), { target: { value: "Appeal upheld" } });
    fireEvent.click(screen.getByRole("button", { name: "Save commenting privilege" }));
    await waitFor(() => expect(restore).toHaveBeenCalledWith(privilege, "restore", "Appeal upheld", null));
  });
});
