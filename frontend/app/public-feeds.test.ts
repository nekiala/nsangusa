import { afterEach, describe, expect, it, vi } from "vitest";
import { publicApi, type ArticleSummary } from "@/lib/public-api";
import { GET as sitemap } from "./sitemap.xml/route";
import { GET as segment } from "./sitemaps/articles/[segment]/route";
import { GET as rss } from "./rss.xml/route";
import { FEED_PAGE_SIZE, MAX_SITEMAP_SEGMENTS } from "@/lib/public-feeds";

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs(); });
const record = (id: number): ArticleSummary => ({ id: String(id), slug: `report-${id}`, headline: "News <script> & analysis", summary: "Plain text & <b>context</b>.", topic: "Science", tags: [], publishedAt: "2026-09-01T12:00:00Z", updatedAt: "2026-09-02T12:00:00Z" });
const parse = (xml: string) => new DOMParser().parseFromString(xml, "application/xml");
const request = (path: string) => new Request(`http://localhost:3000${path}`);

describe("bounded public syndication", () => {
  it("uses the runtime publication origin for sitemap and RSS absolute URLs", async () => {
    vi.stubEnv("PUBLIC_BASE_URL", "https://publication.example.test");
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "http://build-time.invalid");
    vi.spyOn(publicApi, "published").mockResolvedValue({ items: [record(1)], page: 0, size: 100, total: 1 });
    expect(await (await sitemap()).text()).toContain("https://publication.example.test/sitemaps/articles/0.xml");
    const feed = await (await rss()).text();
    expect(feed).toContain("https://publication.example.test/articles/report-1");
    expect(feed).not.toContain("build-time.invalid");
  });
  it("indexes every bounded segment rather than just the latest twenty", async () => {
    const records = Array.from({ length: 235 }, (_, index) => record(index));
    const published = vi.spyOn(publicApi, "published").mockImplementation(async (page = 0, size = 20) => ({ items: records.slice(page * size, (page + 1) * size), page, size, total: records.length }));
    const index = await sitemap();
    const xml = parse(await index.text());
    expect(xml.querySelector("parsererror")).toBeNull();
    expect([...xml.querySelectorAll("loc")].map((node) => node.textContent)).toEqual([
      "http://localhost:3000/sitemaps/static.xml",
      "http://localhost:3000/sitemaps/articles/0.xml",
      "http://localhost:3000/sitemaps/articles/1.xml",
      "http://localhost:3000/sitemaps/articles/2.xml"
    ]);
    const response = await segment(request("/sitemaps/articles/2.xml"), { params: Promise.resolve({ segment: "2.xml" }) });
    const last = parse(await response.text());
    // Each article is listed once per language, with both languages as alternates.
    expect(last.querySelectorAll("url")).toHaveLength(70);
    expect(last.querySelectorAll("url")[1].querySelector("loc")?.textContent).toContain("/en/articles/");
    expect(last.querySelectorAll("url")[0].getElementsByTagName("xhtml:link")).toHaveLength(2);
    expect(last.querySelector("lastmod")?.textContent).toBe("2026-09-02T12:00:00Z");
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(published).toHaveBeenLastCalledWith(2, FEED_PAGE_SIZE);
  });
  it("refuses sitemap protocol overflow rather than silently dropping URLs", async () => {
    vi.spyOn(publicApi, "published").mockResolvedValue({ items: [], page: 0, size: 100, total: (MAX_SITEMAP_SEGMENTS + 1) * 100 });
    expect((await sitemap()).status).toBe(503);
  });
  it("supports bounded RSS page traversal with valid XML and escaped plain text", async () => {
    vi.spyOn(publicApi, "published").mockResolvedValue({ items: [record(101)], page: 1, size: 100, total: 235 });
    const response = await rss(request("/rss.xml?page=2"));
    const xml = parse(await response.text());
    expect(xml.querySelector("parsererror")).toBeNull();
    expect(xml.querySelector("item title")?.textContent).toBe("News <script> & analysis");
    expect(xml.querySelector("description")?.querySelector("b")).toBeNull();
    const links = [...xml.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link")];
    expect(links.map((node) => [node.getAttribute("rel"), node.getAttribute("href")])).toContainEqual(["next", "http://localhost:3000/rss.xml?page=3"]);
    expect(links.map((node) => node.getAttribute("rel"))).toEqual(["self", "first", "last", "previous", "next"]);
    expect(publicApi.published).toHaveBeenCalledWith(1, 100, "fr");
  });
  it("reflects withdrawals and reports invalid pages or outages explicitly", async () => {
    vi.spyOn(publicApi, "published").mockResolvedValue({ items: [], page: 0, size: 100, total: 0 });
    expect((await segment(request("/"), { params: Promise.resolve({ segment: "0.xml" }) })).status).toBe(404);
    expect((await rss(request("/rss.xml?page=2"))).status).toBe(404);
    expect((await rss(request("/rss.xml?page=-1"))).status).toBe(404);
    expect((await segment(request("/"), { params: Promise.resolve({ segment: "50000.xml" }) })).status).toBe(404);
    vi.spyOn(publicApi, "published").mockRejectedValue(new Error("Unavailable"));
    const failed = await rss();
    expect(failed.status).toBe(503);
    expect(failed.headers.get("cache-control")).toContain("no-store");
  });
});
