import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, type ImageGeneration } from "@/lib/api";
import { article } from "@/test/editorial-fixtures";
import { ImageReview } from "./images";

const fallback: ImageGeneration = {
  id: "fallback-1", articleId: article.id, prompt: "Original neutral editorial fallback illustration",
  altText: "A neutral newspaper illustration", objectKey: "articles/article-1/hero-fallback.png",
  provider: "nsangusa-editorial", model: "neutral-illustration-v1", safetyStatus: "review_required",
  createdAt: "2026-09-18T10:00:00Z", approvedAt: null
};
const generated: ImageGeneration = {
  ...fallback, id: "generated-1", provider: "configured-provider", model: "configured-model",
  prompt: "Editorial illustration", altText: "An AI illustration",
  objectKey: "articles/article-1/hero-generated.png"
};

beforeEach(() => {
  const BaseURL = URL;
  vi.stubGlobal("URL", class extends BaseURL {
    static createObjectURL = vi.fn(() => "blob:image-preview");
    static revokeObjectURL = vi.fn();
  });
  vi.spyOn(api.admin, "images").mockResolvedValue([]);
  vi.spyOn(api.admin, "imageContent").mockResolvedValue(new Blob(["image"], { type: "image/png" }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("explicit fallback image review", () => {
  it("requires an editor reason and keeps creation separate from approval", async () => {
    const request = vi.spyOn(api.admin, "requestFallbackImage").mockResolvedValue({ eventId: "event-1" });
    const approve = vi.spyOn(api.admin, "approveImage").mockResolvedValue();
    const regenerate = vi.spyOn(api.admin, "regenerateImage");
    const onChange = vi.fn();
    render(<ImageReview article={article} onChange={onChange} locked={false} />);
    await screen.findByText(/No image generations yet/);

    const button = screen.getByRole("button", { name: "Create fallback for review" });
    fireEvent.click(button);
    expect(request).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Fallback selection reason")).toBeRequired();
    expect(screen.getByLabelText("Fallback selection reason")).toHaveAttribute("maxLength", "2000");
    expect(screen.getByLabelText("Fallback alternative text")).toHaveAttribute("maxLength", "500");
    fireEvent.change(screen.getByLabelText("Fallback alternative text"), { target: { value: fallback.altText } });
    fireEvent.change(screen.getByLabelText("Fallback selection reason"), { target: { value: "Provider unavailable; use neutral artwork" } });
    fireEvent.click(button);

    await waitFor(() => expect(request).toHaveBeenCalledWith(article.id, fallback.altText, "Provider unavailable; use neutral artwork", article.version));
    expect(await screen.findByRole("status")).toHaveTextContent("Inspect and explicitly approve");
    expect(approve).not.toHaveBeenCalled();
    expect(regenerate).not.toHaveBeenCalled();
    expect(onChange).toHaveBeenCalledOnce();
    expect(api.admin.images).toHaveBeenCalledTimes(2);
  });

  it("preserves the selected image and distinguishes fallback provenance for review", async () => {
    vi.mocked(api.admin.images).mockResolvedValue([
      fallback, { ...generated, safetyStatus: "approved", approvedAt: generated.createdAt }
    ]);
    const approve = vi.spyOn(api.admin, "approveImage").mockResolvedValue();
    render(<ImageReview article={{ ...article, approvedImageGenerationId: generated.id }} onChange={vi.fn()} locked={false} />);

    const selected = (await screen.findByRole("heading", { name: "Selected image" })).closest("article")!;
    expect(within(selected).getByText("AI-generated illustration, not a documentary photograph.")).toBeInTheDocument();
    expect(within(selected).getByRole("button", { name: "Approve this image" })).toBeDisabled();
    const pending = screen.getByRole("heading", { name: "Image awaiting review" }).closest("article")!;
    expect(within(pending).getByText(/Original neutral editorial fallback illustration, not AI-generated/)).toBeInTheDocument();
    expect(within(pending).getByText("nsangusa-editorial / neutral-illustration-v1")).toBeInTheDocument();
    expect(within(pending).queryByText(/^AI-generated illustration/)).not.toBeInTheDocument();
    expect(screen.getByText(/A replacement will not be selected until approved/)).toBeInTheDocument();

    fireEvent.click(within(pending).getByRole("button", { name: "Approve this image" }));
    await waitFor(() => expect(approve).toHaveBeenCalledWith(fallback.id));
  });

  it("keeps an approved fallback selected when a later provider candidate appears", async () => {
    vi.mocked(api.admin.images).mockResolvedValue([
      generated, { ...fallback, safetyStatus: "approved", approvedAt: fallback.createdAt }
    ]);
    render(<ImageReview article={{ ...article, approvedImageGenerationId: fallback.id, generatedImage: false }} onChange={vi.fn()} locked={false} />);

    const selected = (await screen.findByRole("heading", { name: "Selected image" })).closest("article")!;
    expect(within(selected).getByText("Original neutral editorial fallback illustration, not AI-generated or a documentary photograph.")).toBeInTheDocument();
    expect(within(selected).queryByText(/^AI-generated illustration/)).not.toBeInTheDocument();
    const pending = screen.getByRole("heading", { name: "Image awaiting review" }).closest("article")!;
    expect(within(pending).getByText("AI-generated illustration, not a documentary photograph.")).toBeInTheDocument();
  });

  it("keeps the reason and alternative text after failure for an explicit retry", async () => {
    const request = vi.spyOn(api.admin, "requestFallbackImage")
      .mockRejectedValueOnce(new Error("Object storage unavailable"))
      .mockResolvedValueOnce({ eventId: "event-1" });
    const approve = vi.spyOn(api.admin, "approveImage");
    render(<ImageReview article={article} onChange={vi.fn()} locked={false} />);
    await screen.findByText(/No image generations yet/);
    fireEvent.change(screen.getByLabelText("Fallback alternative text"), { target: { value: fallback.altText } });
    fireEvent.change(screen.getByLabelText("Fallback selection reason"), { target: { value: "Editorial choice" } });
    fireEvent.click(screen.getByRole("button", { name: "Create fallback for review" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Object storage unavailable");
    expect(screen.getByLabelText("Fallback alternative text")).toHaveValue(fallback.altText);
    expect(screen.getByLabelText("Fallback selection reason")).toHaveValue("Editorial choice");
    fireEvent.click(screen.getByRole("button", { name: "Create fallback for review" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Neutral fallback created for review");
    expect(request.mock.calls).toEqual([
      [article.id, fallback.altText, "Editorial choice", article.version],
      [article.id, fallback.altText, "Editorial choice", article.version]
    ]);
    expect(approve).not.toHaveBeenCalled();
  });

  it("does not create a fallback automatically after a provider failure", async () => {
    vi.spyOn(api.admin, "regenerateImage").mockRejectedValue(new Error("AI provider unavailable"));
    const request = vi.spyOn(api.admin, "requestFallbackImage");
    render(<ImageReview article={article} onChange={vi.fn()} locked={false} />);
    await screen.findByText(/No image generations yet/);
    fireEvent.click(screen.getByRole("button", { name: "Request image generation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("AI provider unavailable");
    expect(request).not.toHaveBeenCalled();
  });

  it.each(["PUBLISHED", "REJECTED", "ARCHIVED"] as const)("disables fallback requests for %s articles", async (state) => {
    render(<ImageReview article={{ ...article, state }} onChange={vi.fn()} locked={false} />);
    await screen.findByText(/No image generations yet/);
    expect(screen.getByRole("button", { name: "Create fallback for review" })).toBeDisabled();
    expect(screen.getByLabelText("Fallback alternative text")).toBeDisabled();
    expect(screen.getByLabelText("Fallback selection reason")).toBeDisabled();
  });

  it("disables fallback requests while the article is locked", async () => {
    render(<ImageReview article={article} onChange={vi.fn()} locked />);
    await screen.findByText(/No image generations yet/);
    expect(screen.getByRole("button", { name: "Create fallback for review" })).toBeDisabled();
    expect(screen.getByLabelText("Fallback selection reason")).toBeDisabled();
  });
});
