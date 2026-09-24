import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, type ApiArticle, type ImageGeneration } from "@/lib/api";
import { account, article, sources } from "@/test/editorial-fixtures";
import { AdminWorkspace } from "./admin-workspace";
import * as accountContext from "./authenticated-area";
import { moderationApi } from "@/lib/moderation-api";

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));

beforeEach(() => {
  vi.spyOn(api.admin, "sources").mockResolvedValue(sources);
  vi.spyOn(api.admin, "source").mockImplementation(async (id) => sources.find((source) => source.id === id) || sources[0]);
  vi.spyOn(api.admin, "images").mockResolvedValue([]);
  vi.spyOn(api.admin, "revisions").mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
  vi.spyOn(api.admin, "aiRequests").mockResolvedValue([]);
  vi.spyOn(api.admin, "xCapabilities").mockResolvedValue({ simulationEnabled: true });
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("Article workspace", () => {
  it("discovers articles and filters a real paged queue", async () => {
    const list = vi.spyOn(api.admin, "articles").mockResolvedValue({ items: [article], page: 0, size: 20, total: 1 });
    render(<AdminWorkspace section="editor" />);
    expect(await screen.findByRole("link", { name: article.headline })).toHaveAttribute("href", `/admin/editor/${article.id}`);
    fireEvent.change(screen.getByLabelText("Article state"), { target: { value: "AWAITING_REVIEW" } });
    await waitFor(() => expect(list).toHaveBeenLastCalledWith("AWAITING_REVIEW", 0));
    expect(screen.queryByLabelText("Article ID")).not.toBeInTheDocument();
  });

  it("preserves multiple source identities and existing SEO values when saving", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue(article);
    const edit = vi.spyOn(api.admin, "editArticle").mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    await screen.findByDisplayValue(article.headline);
    expect(screen.getByLabelText("SEO title")).toHaveValue(article.seoTitle);
    fireEvent.change(screen.getByLabelText("Headline"), { target: { value: "Edited title" } });
    expect(screen.getByRole("button", { name: "Approve article" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Save article" }));
    await waitFor(() => expect(edit).toHaveBeenCalledWith(article.id, article.version, expect.objectContaining({ headline: "Edited title", sources: article.sources, seoTitle: article.seoTitle, seoDescription: article.seoDescription })));
  });

  it("creates only after a real source is selected and loads that created article", async () => {
    const create = vi.spyOn(api.admin, "createArticle").mockResolvedValue({ id: article.id });
    vi.spyOn(api.admin, "article").mockResolvedValue(article);
    render(<AdminWorkspace section="editor" id="new" />);
    expect(screen.getByRole("button", { name: "Create article" })).toBeDisabled();
    await screen.findByRole("option", { name: /@library · post-1/ });
    fireEvent.change(screen.getByLabelText("Available source"), { target: { value: sources[0].id } });
    fireEvent.click(screen.getByRole("button", { name: "Add selected source" }));
    await screen.findByText("Source added.");
    for (const [label, value] of [["Headline", article.headline], ["Summary", article.summary], ["Body", article.body], ["SEO title", article.seoTitle], ["SEO description", article.seoDescription], ["Slug suggestion", article.slug]]) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
    }
    fireEvent.click(screen.getByRole("button", { name: "Create article" }));
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ sources: [article.sources[0]] })));
    expect(await screen.findByTestId("article-state")).toHaveTextContent("AWAITING_REVIEW");
    expect(navigation.replace).toHaveBeenCalledWith(`/admin/editor/${article.id}`);
  });

  it("gates approval/publication and sends the selected future instant", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, state: "APPROVED" });
    const schedule = vi.spyOn(api.admin, "scheduleArticle").mockResolvedValue({ scheduleId: "schedule-1" });
    render(<AdminWorkspace section="editor" id={article.id} />);
    await screen.findByDisplayValue(article.headline);
    expect(screen.getByRole("button", { name: "Approve article" })).toBeDisabled();
    const value = "2035-02-15T10:30";
    fireEvent.change(screen.getByLabelText("Publication date and time (your local time)"), { target: { value } });
    fireEvent.click(screen.getByRole("button", { name: "Schedule publication" }));
    await waitFor(() => expect(schedule).toHaveBeenCalledWith(article.id, new Date(value).toISOString()));
  });

  it("requires image approval and reviews bytes before selection", async () => {
    vi.stubGlobal("URL", class extends URL { static createObjectURL() { return "blob:review"; } static revokeObjectURL() {} });
    const image: ImageGeneration = { id: "image-1", articleId: article.id, prompt: "A safe illustration", altText: "Library illustration", objectKey: "test.png", provider: "fake", model: "test", safetyStatus: "review_required", createdAt: article.updatedAt, approvedAt: null };
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, state: "DRAFTING", pendingImageGenerationId: image.id, imageApprovalRequired: true });
    vi.mocked(api.admin.images).mockResolvedValue([image]);
    vi.spyOn(api.admin, "imageContent").mockResolvedValue(new Blob(["image"]));
    const approve = vi.spyOn(api.admin, "approveImage").mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    expect(await screen.findByRole("img", { name: "Library illustration" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve article" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Publish now" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Approve this image" }));
    await waitFor(() => expect(approve).toHaveBeenCalledWith(image.id));
  });

  it("shows plain text previews without executing markup", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, body: "<script>alert('unsafe')</script>" });
    render(<AdminWorkspace section="editor" id={article.id} />);
    await screen.findByDisplayValue(article.headline);
    fireEvent.click(screen.getByRole("button", { name: "Preview article" }));
    expect(screen.getByRole("region", { name: "Article preview" })).toHaveTextContent("<script>alert('unsafe')</script>");
    expect(document.querySelector("script")).toBeNull();
  });

  it("retains and reorders existing blocks, adds lists and links, and derives the complete saved body", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, content: { version: 1, blocks: [
      { type: "paragraph", text: "Original paragraph" }, { type: "heading", text: "Original heading" }
    ] } });
    const edit = vi.spyOn(api.admin, "editArticle").mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    await screen.findByDisplayValue("Original paragraph");
    fireEvent.click(screen.getByRole("button", { name: "Move block 2 up" }));
    expect(screen.getByLabelText("Heading text 1")).toHaveValue("Original heading");
    expect(screen.getByLabelText("Paragraph text 2")).toHaveValue("Original paragraph");
    fireEvent.change(screen.getByLabelText("New block type"), { target: { value: "ordered_list" } });
    fireEvent.click(screen.getByRole("button", { name: "Add content block" }));
    fireEvent.change(screen.getByLabelText("List item 1 in block 3"), { target: { value: "First item" } });
    fireEvent.click(screen.getByRole("button", { name: "Add list item to block 3" }));
    fireEvent.change(screen.getByLabelText("List item 2 in block 3"), { target: { value: "Second item" } });
    fireEvent.change(screen.getByLabelText("New block type"), { target: { value: "link" } });
    fireEvent.click(screen.getByRole("button", { name: "Add content block" }));
    fireEvent.change(screen.getByLabelText("Link text 4"), { target: { value: "Evidence" } });
    fireEvent.change(screen.getByLabelText("Link URL 4"), { target: { value: "https://example.test/evidence" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview article" }));
    expect(screen.getByRole("heading", { name: "Original heading", level: 2 })).toBeVisible();
    expect(screen.getByRole("link", { name: "Evidence" })).toHaveAttribute("href", "https://example.test/evidence");
    fireEvent.click(screen.getByRole("button", { name: "Save article" }));
    await waitFor(() => expect(edit).toHaveBeenCalledWith(article.id, article.version, expect.objectContaining({
      body: "Original heading\n\nOriginal paragraph\n\nFirst item\nSecond item\n\nEvidence (https://example.test/evidence)",
      content: { version: 1, blocks: [
        { type: "heading", text: "Original heading" }, { type: "paragraph", text: "Original paragraph" },
        { type: "ordered_list", items: ["First item", "Second item"] },
        { type: "link", text: "Evidence", url: "https://example.test/evidence" }
      ] }
    })));
  });

  it("provides actionable content errors and does not save or preview unsafe links", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue(article);
    const edit = vi.spyOn(api.admin, "editArticle").mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    await screen.findByDisplayValue(article.headline);
    fireEvent.change(screen.getByLabelText("New block type"), { target: { value: "link" } });
    fireEvent.click(screen.getByRole("button", { name: "Add content block" }));
    fireEvent.change(screen.getByLabelText("Link text 2"), { target: { value: "Unsafe target" } });
    fireEvent.change(screen.getByLabelText("Link URL 2"), { target: { value: "javascript:alert(1)" } });
    fireEvent.click(screen.getByRole("button", { name: "Save article" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Block 2: enter an absolute HTTP(S) link");
    expect(edit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Preview article" }));
    expect(screen.queryByRole("region", { name: "Article preview" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Remove block 2" }));
    fireEvent.click(screen.getByRole("button", { name: "Remove block 1" }));
    fireEvent.click(screen.getByRole("button", { name: "Save article" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Use between 1 and 200 content blocks.");
    expect(edit).not.toHaveBeenCalled();
  });

  it("does not describe approved non-AI fallback previews as generated illustrations", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, approvedImageGenerationId: "fallback-1", generatedImage: false });
    vi.spyOn(api.admin, "imageContent").mockResolvedValue(new Blob(["image"]));
    vi.stubGlobal("URL", class extends URL { static createObjectURL() { return "blob:fallback"; } static revokeObjectURL() {} });
    render(<AdminWorkspace section="editor" id={article.id} />);
    await screen.findByDisplayValue(article.headline);
    fireEvent.click(screen.getByRole("button", { name: "Preview article" }));
    expect(screen.getByRole("region", { name: "Article preview" })).toHaveTextContent("Editorial illustration, not AI-generated.");
    await waitFor(() => expect(screen.queryAllByText("Loading image preview…")).toHaveLength(0));
  });

  it("surfaces retryable load errors instead of an ID entry workaround", async () => {
    vi.spyOn(api.admin, "articles").mockRejectedValue(new Error("The queue is unavailable."));
    render(<AdminWorkspace section="editor" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("The queue is unavailable.");
    expect(screen.getByRole("button", { name: "Refresh article queue" })).toBeEnabled();
  });

  it("requires a public note and withdrawal acknowledgment before starting correction review", async () => {
    let current: ApiArticle = { ...article, state: "PUBLISHED", publishedAt: article.updatedAt };
    vi.spyOn(api.admin, "article").mockImplementation(async () => current);
    const correct = vi.spyOn(api.admin, "startCorrection").mockImplementation(async (_id, _version, note) => {
      current = { ...current, state: "AWAITING_REVIEW", version: current.version + 1, correctionNote: note };
    });
    render(<AdminWorkspace section="editor" id={article.id} />);
    const submit = await screen.findByRole("button", { name: "Withdraw and start correction" });
    expect(submit).toBeDisabled();
    expect(screen.getByLabelText("Headline")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Request image generation" })).toBeDisabled();
    expect(screen.getByText(/Starting a correction immediately withdraws/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Public correction note (required)"), { target: { value: "  Clarified the source attribution.  " } });
    expect(submit).toBeDisabled();
    fireEvent.click(screen.getByLabelText("I understand the article will be withdrawn until reviewed and republished."));
    fireEvent.click(submit);
    await waitFor(() => expect(correct).toHaveBeenCalledWith(article.id, article.version, "Clarified the source attribution."));
    await waitFor(() => expect(screen.getByTestId("article-state")).toHaveTextContent("AWAITING_REVIEW"));
    expect(screen.getByLabelText("Headline")).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Restore publication" })).not.toBeInTheDocument();
  });

  it.each([
    ["PUBLISHED", "Unpublish article", "unpublishArticle"],
    ["UNPUBLISHED", "Restore publication", "restoreArticle"],
    ["UNPUBLISHED", "Archive article", "archiveArticle"],
    ["REJECTED", "Archive article", "archiveArticle"]
  ] as const)("offers the correct %s lifecycle action: %s", async (state, label, operation) => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, state, publishedAt: article.updatedAt });
    const action = vi.spyOn(api.admin, operation).mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    fireEvent.click(await screen.findByRole("button", { name: label }));
    await waitFor(() => expect(action).toHaveBeenCalledWith(article.id, undefined, article.version));
    expect(await screen.findByText(/request accepted/)).toBeInTheDocument();
  });

  it.each([
    ["AWAITING_REVIEW", "Approve article", "approveArticle"],
    ["AWAITING_REVIEW", "Reject article", "rejectArticle"],
    ["APPROVED", "Publish now", "publishArticle"]
  ] as const)("sends the displayed review version for %s action %s", async (state, label, operation) => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, state, version: 42 });
    const action = vi.spyOn(api.admin, operation).mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    fireEvent.click(await screen.findByRole("button", { name: label }));
    await waitFor(() => expect(action).toHaveBeenCalledWith(article.id, undefined, 42));
    expect(await screen.findByText(/request accepted/)).toBeInTheDocument();
  });

  it("requires explicit refresh after a stale review conflict instead of retrying against a newer revision", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValueOnce(article).mockResolvedValue({ ...article, version: 2, headline: "Another editor's revision" });
    const approve = vi.spyOn(api.admin, "approveArticle").mockRejectedValueOnce(new ApiError(409, {
      title: "Conflict", status: 409, detail: "Refresh and review the current revision before retrying."
    })).mockResolvedValue();
    render(<AdminWorkspace section="editor" id={article.id} />);
    fireEvent.click(await screen.findByRole("button", { name: "Approve article" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Refresh and review the current revision");
    expect(approve).toHaveBeenCalledExactlyOnceWith(article.id, undefined, article.version);
    fireEvent.click(screen.getByRole("button", { name: "Refresh article" }));
    await screen.findByDisplayValue("Another editor's revision");
    fireEvent.click(screen.getByRole("button", { name: "Approve article" }));
    await waitFor(() => expect(approve).toHaveBeenLastCalledWith(article.id, undefined, 2));
    expect(await screen.findByText(/Approve request accepted/)).toBeInTheDocument();
  });

  it("requires schedule cancellation before text or image edits", async () => {
    vi.spyOn(api.admin, "article").mockResolvedValue({ ...article, state: "SCHEDULED" });
    render(<AdminWorkspace section="editor" id={article.id} />);
    expect(await screen.findByRole("link", { name: "Manage publication schedules" })).toHaveAttribute("href", "/admin/schedules");
    expect(screen.getByLabelText("Headline")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save article" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Request image generation" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Withdraw and start correction" })).not.toBeInTheDocument();
  });
});

describe("Candidate and account discovery", () => {
  it("opens a discoverable moderator queue instead of requiring comment identifiers", async () => {
    vi.spyOn(accountContext, "useAuthenticatedUser").mockReturnValue({ id: "moderator", email: "moderator@example.test", displayName: "Moderator", roles: ["MODERATOR"] });
    const queue = vi.spyOn(moderationApi, "queue").mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
    render(<AdminWorkspace section="comments" />);
    await waitFor(() => expect(queue).toHaveBeenCalledWith("pending", 0));
    expect(screen.getByRole("heading", { name: "Community moderation" })).toBeVisible();
    expect(screen.queryByLabelText("Comment ID")).toBeNull();
  });

  it("reviews analysis and regenerates a new candidate without editing the original article", async () => {
    vi.spyOn(api.admin, "candidates").mockResolvedValue({ items: [{ id: "candidate-1", topic: "Ideas", status: "drafted", createdAt: article.updatedAt, sourceIds: sources.map((source) => source.id), articleId: article.id }], page: 0, size: 20, total: 1 });
    const regenerate = vi.spyOn(api.admin, "regenerateCandidate").mockResolvedValue({ id: "candidate-2" });
    render(<AdminWorkspace section="candidates" />);
    fireEvent.click(await screen.findByRole("button", { name: "Review candidate" }));
    expect(await screen.findByRole("heading", { name: "Analysis and provenance" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open draft article" })).toHaveAttribute("href", `/admin/editor/${article.id}`);
    fireEvent.click(screen.getByRole("button", { name: "Regenerate candidate" }));
    await waitFor(() => expect(regenerate).toHaveBeenCalledWith("candidate-1"));
    expect(await screen.findByRole("status")).toHaveTextContent("original candidate and article are preserved");
  });

  it("selects a listed account and pauses monitoring without UUID input", async () => {
    vi.spyOn(api.admin, "xAccounts").mockResolvedValue([account]);
    vi.spyOn(api.admin, "blockedAccounts").mockResolvedValue([]);
    const monitoring = vi.spyOn(api.admin, "setXAccountMonitoring").mockResolvedValue();
    render(<AdminWorkspace section="handles" />);
    await screen.findByRole("option", { name: /@library/ });
    fireEvent.change(screen.getByLabelText("Monitored account"), { target: { value: account.id } });
    expect(screen.getByText(/Health: healthy/)).toBeInTheDocument();
    expect(screen.getByLabelText("Canonical URL")).toHaveAccessibleDescription(/Share parameters.*removed automatically/);
    expect(screen.getByLabelText("Published at")).toHaveAccessibleDescription(/not a future date/);
    fireEvent.click(screen.getByRole("button", { name: "Pause monitoring" }));
    await waitFor(() => expect(monitoring).toHaveBeenCalledWith(account.id, false));
    expect(screen.queryByLabelText("Account ID")).not.toBeInTheDocument();
  });

  it("resolves a handle and hides simulated ingestion when unavailable", async () => {
    vi.spyOn(api.admin, "xAccounts").mockResolvedValue([account]);
    vi.spyOn(api.admin, "blockedAccounts").mockResolvedValue([]);
    vi.mocked(api.admin.xCapabilities).mockResolvedValue({ simulationEnabled: false });
    const resolve = vi.spyOn(api.admin, "resolveXAccount").mockResolvedValue({ accountId: "123456", handle: "library", displayName: "Official Library", simulated: false });
    render(<AdminWorkspace section="handles" />);
    await screen.findByRole("option", { name: /@library/ });
    fireEvent.change(screen.getByLabelText("Monitored account"), { target: { value: account.id } });
    expect(screen.queryByRole("button", { name: "Simulate post" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add an account" }));
    fireEvent.change(screen.getByLabelText("Handle"), { target: { value: "@library" } });
    fireEvent.click(screen.getByRole("button", { name: "Resolve handle" }));
    await waitFor(() => expect(resolve).toHaveBeenCalledWith("@library"));
    expect(await screen.findByDisplayValue("123456")).toBeInTheDocument();
    expect(screen.getByLabelText("Display name")).toHaveValue("Official Library");
    expect(screen.getByText("Resolved through the official X provider.")).toBeInTheDocument();
  });
});
