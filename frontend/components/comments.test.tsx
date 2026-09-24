import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { moderationApi, type CommunityComment, type Discussion } from "@/lib/moderation-api";
import { Comments } from "./comments";

const comment: CommunityComment = { id: "c1", authorId: "reader", body: "Original response", parentId: null, state: "approved", version: 2, createdAt: new Date().toISOString(), editedAt: null, deleted: false, deletedByAuthor: false };
const discussion: Discussion = { comments: [], viewerId: "reader", authenticated: true, eligible: true, enabled: true, requireApproval: true, editingWindowMinutes: 15, maximumReplyDepth: 1, privilegeStatus: "allowed", suspendedUntil: null, canComment: true };
const load = (overrides: Partial<Discussion> = {}) => vi.spyOn(moderationApi, "discussion").mockResolvedValue({ ...discussion, ...overrides });
afterEach(() => vi.restoreAllMocks());

describe("Comments", () => {
  it("loads real eligibility then submits comments and clears the form", async () => {
    const read = load();
    const submit = vi.spyOn(moderationApi, "submit").mockResolvedValue({ id: "comment-1" });
    render(<Comments articleId="article-1" initialComments={[]} />);
    const field = await screen.findByLabelText("Add a comment");
    fireEvent.change(field, { target: { value: "A considered response." } });
    fireEvent.click(screen.getByRole("button", { name: "Submit for moderation" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith("article-1", "A considered response.", null));
    expect(read).toHaveBeenCalledWith("article-1");
    expect(field).toHaveValue("");
    expect(await screen.findByText("Your comment has been submitted for moderation.")).toBeInTheDocument();
  });

  it("shows sign in instead of a fake authenticated form", async () => {
    load({ viewerId: null, authenticated: false, eligible: false, canComment: false });
    render(<Comments articleId="article-1" initialComments={[]} />);
    expect(await screen.findByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/sign-in");
    expect(screen.queryByLabelText("Add a comment")).not.toBeInTheDocument();
  });

  it("honors disabled policy and does not render stale server comments", async () => {
    load({ enabled: false, canComment: false });
    render(<Comments articleId="article-1" initialComments={[comment]} />);
    expect(await screen.findByText("Comments are disabled for this article.")).toBeInTheDocument();
    expect(screen.queryByText("Original response")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Add a comment")).not.toBeInTheDocument();
  });

  it("suspended readers can delete their own comment but cannot post or edit", async () => {
    load({ comments: [comment], canComment: false, privilegeStatus: "suspended" });
    const remove = vi.spyOn(moderationApi, "deleteOwn").mockResolvedValue();
    render(<Comments articleId="article-1" initialComments={[]} />);
    fireEvent.click(await screen.findByRole("button", { name: "Delete comment" }));
    expect(screen.queryByRole("button", { name: "Edit comment" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Add a comment")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete" }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith(comment));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Comments" })).toHaveFocus());
  });

  it("focuses the safe confirmation action and restores its trigger on Escape or cancellation", async () => {
    load({ comments: [comment] });
    const remove = vi.spyOn(moderationApi, "deleteOwn").mockResolvedValue();
    render(<Comments articleId="article-1" />);
    const trigger = await screen.findByRole("button", { name: "Delete comment" });
    fireEvent.click(trigger);
    expect(screen.getByRole("button", { name: "Cancel deletion" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("button", { name: "Cancel deletion" }), { key: "Escape" });
    expect(trigger).toHaveFocus();
    expect(screen.queryByRole("group", { name: "Confirm comment deletion" })).not.toBeInTheDocument();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole("button", { name: "Cancel deletion" }));
    expect(trigger).toHaveFocus();
    expect(remove).not.toHaveBeenCalled();
  });
  it("edits only own comments inside the window and sends the loaded version", async () => {
    load({ comments: [comment, { ...comment, id: "c2", authorId: "other", body: "Other response" }] });
    const edit = vi.spyOn(moderationApi, "edit").mockResolvedValue();
    render(<Comments articleId="article-1" initialComments={[]} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit comment" }));
    fireEvent.change(screen.getByLabelText("Edit your comment"), { target: { value: "Updated response" } });
    fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
    await waitFor(() => expect(edit).toHaveBeenCalledWith(comment, "Updated response"));
    expect(screen.getAllByRole("button", { name: "Delete comment" })).toHaveLength(1);
  });

  it("hides editing after the backend configured window", async () => {
    load({ comments: [{ ...comment, createdAt: "2020-01-01T00:00:00Z" }] });
    render(<Comments articleId="article-1" initialComments={[]} />);
    await screen.findByText("Original response");
    expect(screen.queryByRole("button", { name: "Edit comment" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete comment" })).toBeInTheDocument();
  });

  it("renders accessible single-level threads and submits a reply", async () => {
    const parent = { ...comment, authorId: "other" };
    const child = { ...comment, id: "reply", parentId: comment.id, body: "Thread response" };
    load({ comments: [parent, child] });
    const submit = vi.spyOn(moderationApi, "submit").mockResolvedValue({ id: "new" });
    render(<Comments articleId="article-1" initialComments={[]} />);
    expect(within(await screen.findByRole("list", { name: "Replies" })).getByText("Thread response")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Reply" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Reply" }));
    fireEvent.change(screen.getByLabelText("Your reply"), { target: { value: "Another reply" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit reply" }));
    await waitFor(() => expect(submit).toHaveBeenCalledWith("article-1", "Another reply", "c1"));
  });

  it("reports abuse with category and details", async () => {
    load({ comments: [{ ...comment, authorId: "other" }] });
    const report = vi.spyOn(moderationApi, "report").mockResolvedValue({ id: "report" });
    render(<Comments articleId="article-1" initialComments={[]} />);
    fireEvent.click(await screen.findByRole("button", { name: "Report abuse" }));
    fireEvent.change(screen.getByLabelText("Report reason"), { target: { value: "harassment" } });
    fireEvent.change(screen.getByLabelText("Report details (optional)"), { target: { value: "Targeted insults." } });
    fireEvent.click(screen.getByRole("button", { name: "Send report" }));
    await waitFor(() => expect(report).toHaveBeenCalledWith("c1", "harassment", "Targeted insults."));
    expect(await screen.findByRole("button", { name: "Reported" })).toBeDisabled();
  });

  it("renders text safely and announces conflict errors without losing edits", async () => {
    load({ comments: [{ ...comment, body: "<script>alert('x')</script>" }] });
    vi.spyOn(moderationApi, "edit").mockRejectedValue(new ApiError(409, { title: "Conflict", status: 409, detail: "The comment changed. Refresh and retry." }));
    render(<Comments articleId="article-1" initialComments={[]} />);
    expect(await screen.findByText("<script>alert('x')</script>")).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Edit comment" }));
    fireEvent.change(screen.getByLabelText("Edit your comment"), { target: { value: "My updated text" } });
    fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Refresh and retry");
    expect(screen.getByLabelText("Edit your comment")).toHaveValue("My updated text");
  });
});
