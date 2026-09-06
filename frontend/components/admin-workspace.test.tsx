import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, type ApiArticle } from "@/lib/api";
import { AdminWorkspace } from "./admin-workspace";

const created: ApiArticle = {
  id: "article-1", slug: "tested-workflow", headline: "Tested workflow", summary: "A deterministic summary.",
  body: "A complete article body.", editorialContext: null, topic: "Ideas", tags: ["Workflow"],
  state: "AWAITING_REVIEW", generatedImage: false, commentsEnabled: true, publishedAt: null,
  updatedAt: "2026-09-03T08:00:00.000Z", version: 1, sources: [], warnings: [], confidence: 1
};

afterEach(() => vi.restoreAllMocks());

describe("AdminWorkspace", () => {
  it("creates an article and reports the loaded editorial transition state", async () => {
    vi.spyOn(api.admin, "createArticle").mockResolvedValue({ id: created.id });
    vi.spyOn(api.admin, "approveArticle").mockResolvedValue();
    const load = vi.spyOn(api.admin, "article")
      .mockResolvedValueOnce(created)
      .mockResolvedValueOnce({ ...created, state: "APPROVED", version: 2 });

    render(<AdminWorkspace section="editor" />);
    fireEvent.change(screen.getByLabelText("Headline"), { target: { value: created.headline } });
    fireEvent.change(screen.getByLabelText("Summary"), { target: { value: created.summary } });
    fireEvent.change(screen.getByLabelText("Body"), { target: { value: created.body } });
    fireEvent.change(screen.getByLabelText("SEO title"), { target: { value: created.headline } });
    fireEvent.change(screen.getByLabelText("SEO description"), { target: { value: created.summary } });
    fireEvent.change(screen.getByLabelText("Slug suggestion"), { target: { value: created.slug } });
    fireEvent.click(screen.getByRole("button", { name: "Create article" }));

    expect(await screen.findByText(`Article created. ID: ${created.id}`)).toBeInTheDocument();
    expect(load).toHaveBeenCalledWith(created.id);
    fireEvent.click(screen.getByRole("button", { name: "approve" }));
    expect(await screen.findByText("Approve request completed. Current state: APPROVED.")).toBeInTheDocument();
    expect(api.admin.approveArticle).toHaveBeenCalledWith(created.id);
  });

  it("surfaces a useful load failure", async () => {
    vi.spyOn(api.admin, "article").mockRejectedValue(new Error("Enter a valid article ID."));
    render(<AdminWorkspace section="editor" />);
    fireEvent.change(screen.getByLabelText("Article ID"), { target: { value: "invalid" } });
    fireEvent.click(screen.getByRole("button", { name: "Load article" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Enter a valid article ID."));
  });
});
