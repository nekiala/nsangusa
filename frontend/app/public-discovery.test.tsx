import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { contentApi, type Article } from "@/lib/content";
import LatestPage, { generateMetadata as latestMetadata } from "./latest/page";
import SearchPage from "./search/page";
import TopicPage from "./topics/[topic]/page";
import TagPage from "./tags/[tag]/page";
import TopicsPage from "./topics/page";
import HomePage from "./page";

vi.mock("next/navigation", () => ({ notFound: () => { throw new Error("NEXT_NOT_FOUND"); } }));
afterEach(() => vi.restoreAllMocks());
const story: Article = { id: "21", slug: "public-health", title: "Health report", dek: "Reporting.", topic: "Public health", author: "Nsangusa", publishedAt: "2026-09-01T12:00:00Z", updatedAt: "2026-09-02T12:00:00Z", minutes: 0, body: [], tags: ["Public life"], commentsEnabled: false, editorialContext: null, sources: [], heroObjectKey: null, imageAltText: null, generatedImage: false, seoTitle: "Health report", seoDescription: "Reporting.", correctionNote: null };

describe("public discovery pages", () => {
  it("renders a real empty homepage rather than an outage", async () => {
    vi.spyOn(contentApi, "latest").mockResolvedValue({ items: [], page: 0, size: 7, total: 0 });
    vi.spyOn(contentApi, "topics").mockResolvedValue([]);
    render(await HomePage());
    expect(screen.getByText("No published articles are available yet.")).toBeVisible();
  });
  it("uses server totals and preserves pagination metadata beyond the first twenty", async () => {
    vi.spyOn(contentApi, "latest").mockResolvedValue({ items: [story], page: 1, size: 20, total: 21 });
    render(await LatestPage({ searchParams: Promise.resolve({ page: "2" }) }));
    expect(contentApi.latest).toHaveBeenCalledWith(1, 20, "en");
    expect(screen.getByText("Page 2 of 2")).toBeVisible();
    expect((await latestMetadata({ searchParams: Promise.resolve({ page: "2" }) })).alternates).toMatchObject({ canonical: "/latest?page=2" });
  });
  it("reports the real search total rather than this page's item count", async () => {
    vi.spyOn(contentApi, "search").mockResolvedValue({ items: [story], page: 1, size: 20, total: 21 });
    render(await SearchPage({ searchParams: Promise.resolve({ q: "health", page: "2" }) }));
    expect(screen.getByText("21 results for “health”")).toBeVisible();
  });
  it("discovers topics outside the old hard-coded categories", async () => {
    vi.spyOn(contentApi, "topics").mockResolvedValue([{ value: "public health", articleCount: 21 }]);
    render(await TopicsPage());
    expect(screen.getByRole("link", { name: "Public health" })).toHaveAttribute("href", "/topics/public%20health");
  });
  it("decodes a facet route exactly once before querying the backend", async () => {
    vi.spyOn(contentApi, "byTag").mockResolvedValue({ items: [story], page: 0, size: 20, total: 1 });
    render(await TagPage({ params: Promise.resolve({ tag: "public%20life" }) }));
    expect(contentApi.byTag).toHaveBeenCalledWith("public life", 0, 20, "en");
    expect(screen.getByRole("heading", { name: "Public life" })).toBeVisible();
  });
  it("returns not-found for unknown facets and out-of-range pages but propagates outages", async () => {
    const empty = { items: [], page: 1, size: 20, total: 0 };
    vi.spyOn(contentApi, "latest").mockResolvedValue(empty);
    vi.spyOn(contentApi, "byTopic").mockResolvedValue(empty);
    vi.spyOn(contentApi, "byTag").mockResolvedValue(empty);
    await expect(LatestPage({ searchParams: Promise.resolve({ page: "2" }) })).rejects.toThrow("NEXT_NOT_FOUND");
    await expect(TopicPage({ params: Promise.resolve({ topic: "unknown" }) })).rejects.toThrow("NEXT_NOT_FOUND");
    await expect(TagPage({ params: Promise.resolve({ tag: "unknown" }) })).rejects.toThrow("NEXT_NOT_FOUND");
    vi.spyOn(contentApi, "latest").mockRejectedValue(new Error("API unavailable"));
    await expect(LatestPage({ searchParams: Promise.resolve({}) })).rejects.toThrow("API unavailable");
  });
});
