import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, type RevisionView } from "@/lib/api";
import { article } from "@/test/editorial-fixtures";
import { RevisionHistory } from "./revisions";

const original: RevisionView = {
  id: "revision-1", revisionNumber: 1, reason: "CREATED", actorId: null, createdAt: article.updatedAt,
  snapshot: { ...article, humanReviewRequired: true }, aiGenerationResult: null, legacy: false
};
const latest: RevisionView = {
  ...original, id: "revision-2", revisionNumber: 2, reason: "EDITED",
  snapshot: { ...original.snapshot!, headline: "New headline", body: "<script>plain text only</script>" }
};
afterEach(() => vi.restoreAllMocks());

describe("revision history", () => {
  it("renders structured revision comparisons without manufacturing content for older snapshots", async () => {
    const structured: RevisionView = { ...latest, snapshot: { ...latest.snapshot!, content: {
      version: 1, blocks: [{ type: "heading", text: "Stored section" }, { type: "quote", text: "<script>Literal quote</script>" }]
    } } };
    vi.spyOn(api.admin, "revisions").mockResolvedValue({ items: [structured, original], page: 0, size: 20, total: 2 });
    vi.spyOn(api.admin, "compareRevisions").mockResolvedValue({ from: original, to: structured, changedFields: ["content"] });
    render(<RevisionHistory articleId={article.id} />);
    fireEvent.click(await screen.findByRole("button", { name: "Compare from revision 1" }));
    fireEvent.click(screen.getByRole("button", { name: "Compare to revision 2" }));
    fireEvent.click(screen.getByRole("button", { name: "Compare revisions" }));
    const comparison = await screen.findByRole("region", { name: "Revision comparison" });
    expect(within(comparison).getByRole("heading", { name: "Stored section", level: 2 })).toBeVisible();
    expect(comparison).toHaveTextContent("Not stored in this revision");
    expect(comparison.querySelector("blockquote")).toHaveTextContent("<script>Literal quote</script>");
    expect(comparison.querySelector("script")).toBeNull();
  });

  it("paginates immutable history and compares selected stored content safely", async () => {
    const revisions = vi.spyOn(api.admin, "revisions").mockImplementation(async (_id, page = 0) => ({
      items: page === 0 ? [latest] : [original], page, size: 1, total: 2
    }));
    const compare = vi.spyOn(api.admin, "compareRevisions").mockResolvedValue({ from: original, to: latest, changedFields: ["headline", "body"] });
    render(<RevisionHistory articleId={article.id} />);
    fireEvent.click(await screen.findByRole("button", { name: "Compare to revision 2" }));
    fireEvent.click(within(screen.getByRole("navigation", { name: "Revision pages" })).getByRole("button", { name: "Next page" }));
    fireEvent.click(await screen.findByRole("button", { name: "Compare from revision 1" }));
    expect(revisions).toHaveBeenLastCalledWith(article.id, 1);
    fireEvent.click(screen.getByRole("button", { name: "Compare revisions" }));
    await waitFor(() => expect(compare).toHaveBeenCalledWith(article.id, 1, 2));
    const comparison = await screen.findByRole("region", { name: "Revision comparison" });
    expect(comparison).toHaveTextContent(article.headline);
    expect(comparison).toHaveTextContent("New headline");
    expect(comparison).toHaveTextContent("<script>plain text only</script>");
    expect(comparison.querySelector("script")).toBeNull();
  });

  it("labels unavailable snapshots without inventing missing history or actor metadata", async () => {
    const legacy: RevisionView = { ...original, legacy: true, snapshot: null, headline: "Legacy stored headline", summary: "Stored summary", body: "Stored body" };
    vi.spyOn(api.admin, "revisions").mockResolvedValue({ items: [latest, legacy], page: 0, size: 20, total: 2 });
    vi.spyOn(api.admin, "compareRevisions").mockResolvedValue({ from: legacy, to: latest, changedFields: ["headline", "sources"] });
    render(<RevisionHistory articleId={article.id} />);
    expect(await screen.findByText(/Full snapshot unavailable/)).toBeInTheDocument();
    expect(screen.getAllByText("Actor: Not recorded")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Compare from revision 1" }));
    fireEvent.click(screen.getByRole("button", { name: "Compare to revision 2" }));
    fireEvent.click(screen.getByRole("button", { name: "Compare revisions" }));
    const comparison = await screen.findByRole("region", { name: "Revision comparison" });
    expect(comparison).toHaveTextContent("Legacy stored headline");
    expect(comparison).toHaveTextContent("Not stored in this revision");
  });

  it("also labels redacted or otherwise unavailable snapshots without claiming they predate migration", async () => {
    vi.spyOn(api.admin, "revisions").mockResolvedValue({
      items: [{ ...original, snapshot: null, legacy: false, headline: "Stored title", summary: null, body: null }],
      page: 0, size: 20, total: 1
    });
    render(<RevisionHistory articleId={article.id} />);
    expect(await screen.findByText(/Full snapshot unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/legacy|migration/i)).not.toBeInTheDocument();
  });
});
