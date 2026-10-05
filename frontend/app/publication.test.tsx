import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { contentApi } from "@/lib/content";
import { publicApi } from "@/lib/public-api";
import { article } from "@/test/editorial-fixtures";
import { Comments } from "@/components/comments";
import ArticlePage, { dynamic, generateMetadata, revalidate } from "./articles/[slug]/page";
import { GET, revalidate as feedRevalidate } from "./rss.xml/route";
import { revalidate as sitemapRevalidate } from "./sitemap.xml/route";
import config from "../next.config";

vi.mock("@/components/comments", () => ({ Comments: vi.fn(() => null) }));
vi.mock("@/components/forms", () => ({ EmailForm: () => null }));
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs(); });

describe("public publication freshness", () => {
  it("publishes structured elements while disclosing a non-AI illustration truthfully", async () => {
    vi.spyOn(api.articles, "bySlug").mockResolvedValue({ ...article, state: "PUBLISHED",
      body: "Canonical search text", heroObjectKey: "fallback-image", generatedImage: false,
      content: { version: 1, blocks: [
        { type: "heading", text: "Structured section" }, { type: "quote", text: "A literal <b>quote</b>" },
        { type: "unordered_list", items: ["First finding", "Second finding"] },
        { type: "link", text: "Primary evidence", url: "https://example.test/evidence" }
      ] }
    });
    vi.spyOn(contentApi, "related").mockResolvedValue([]);
    const { container } = render(await ArticlePage({ params: Promise.resolve({ slug: article.slug }) }));
    expect(screen.getByRole("heading", { name: "Structured section", level: 2 })).toBeVisible();
    expect(screen.getByRole("link", { name: "Primary evidence" })).toHaveAttribute("href", "https://example.test/evidence");
    expect(container.querySelector("blockquote")).toHaveTextContent("A literal <b>quote</b>");
    expect(container.querySelector(".article-body b")).toBeNull();
    expect(screen.getByText("Editorial illustration, not AI-generated. This is not a documentary photograph.")).toBeVisible();
  });

  it("displays a published correction distinctly and treats its note as plain text", async () => {
    vi.spyOn(api.articles, "bySlug").mockResolvedValue({ ...article, state: "PUBLISHED", correctionNote: "Correction: <b>the time was 18:00</b>." });
    vi.spyOn(api.articles, "comments").mockResolvedValue([]);
    vi.spyOn(contentApi, "related").mockResolvedValue([]);
    render(await ArticlePage({ params: Promise.resolve({ slug: article.slug }) }));
    const note = screen.getByRole("complementary", { name: "Correction note" });
    expect(note).toHaveTextContent("Correction: <b>the time was 18:00</b>.");
    expect(note.querySelector("b")).toBeNull();
  });

  it("never presents unpublished correction drafts as public articles", async () => {
    vi.spyOn(api.articles, "bySlug").mockResolvedValue({ ...article, state: "AWAITING_REVIEW", correctionNote: "Not yet public." });
    expect(await contentApi.bySlug(article.slug)).toBeUndefined();
  });

  it("preserves canonical, OG and NewsArticle dates, source links and safe image/context disclosures", async () => {
    vi.stubEnv("PUBLIC_BASE_URL", "https://publication.example.test");
    vi.spyOn(api.articles, "bySlug").mockResolvedValue({
      ...article, state: "PUBLISHED", commentsEnabled: false,
      headline: "A <b>literal</b> headline", body: "Plain <script>not executable</script> text.",
      editorialContext: "Context <b>not markup</b>.", publishedAt: "2026-09-01T12:00:00Z",
      updatedAt: "2026-09-02T12:00:00Z", heroObjectKey: "approved-image", generatedImage: true
    });
    vi.spyOn(contentApi, "related").mockResolvedValue([]);
    const props = { params: Promise.resolve({ slug: article.slug }) };
    const metadata = await generateMetadata(props);
    expect(metadata.alternates).toMatchObject({ canonical: `/articles/${article.slug}` });
    expect(metadata.openGraph).toMatchObject({ type: "article", publishedTime: "2026-09-01T12:00:00Z", modifiedTime: "2026-09-02T12:00:00Z" });
    const { container } = render(await ArticlePage(props));
    expect(within(screen.getByRole("navigation", { name: "Tags" })).getAllByRole("link")).toHaveLength(article.tags.length);
    const structured = container.querySelector('script[type="application/ld+json"]')!;
    expect(JSON.parse(structured.textContent || "{}")).toMatchObject({ "@type": "NewsArticle", datePublished: "2026-09-01T12:00:00Z", dateModified: "2026-09-02T12:00:00Z", headline: "A <b>literal</b> headline" });
    expect(JSON.parse(structured.textContent || "{}")).toMatchObject({
      mainEntityOfPage: `https://publication.example.test/articles/${article.slug}`,
      image: `https://publication.example.test/api/v1/articles/${article.slug}/image?variant=hero`
    });
    expect(structured.textContent).not.toContain("<b>");
    expect(container.querySelector(".article-body script")).toBeNull();
    expect(screen.getByText("Context <b>not markup</b>.")).toBeVisible();
    expect(screen.getByText("AI-generated editorial illustration. This is not a documentary photograph.")).toBeVisible();
    expect(screen.getByRole("link", { name: "library · post-1" })).toHaveAttribute("href", "https://x.com/library/status/1");
    expect(vi.mocked(Comments).mock.calls.at(-1)?.[0]).toEqual({ articleId: article.id });
  });

  it("disables article/feed/sitemap revalidation and shared-cache retention", async () => {
    vi.spyOn(publicApi, "published").mockResolvedValue({ items: [], page: 0, size: 100, total: 0 });
    expect(dynamic).toBe("force-dynamic");
    expect([revalidate, feedRevalidate, sitemapRevalidate]).toEqual([0, 0, 0]);
    expect((await GET()).headers.get("cache-control")).toContain("no-store");
    const headers = await config.headers!();
    expect(headers).toEqual(expect.arrayContaining([
      expect.objectContaining({ source: "/articles/:path*", headers: expect.arrayContaining([expect.objectContaining({ key: "Cache-Control", value: expect.stringContaining("no-store") })]) }),
      expect.objectContaining({ source: "/:path(robots.txt|rss.xml|sitemap.xml)", headers: expect.arrayContaining([expect.objectContaining({ key: "Cache-Control", value: expect.stringContaining("no-store") })]) })
    ]));
  });
});
