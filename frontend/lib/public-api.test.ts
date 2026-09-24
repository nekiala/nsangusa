import { afterEach, describe, expect, it, vi } from "vitest";
import { api, type ApiArticle } from "@/lib/api";
import { createPublicApi, publicApi } from "@/lib/public-api";
import { contentApi } from "@/lib/content";
import { article } from "@/test/editorial-fixtures";

afterEach(() => vi.restoreAllMocks());
const published = (id: number, overrides: Partial<ApiArticle> = {}): ApiArticle => ({
  ...article, id: String(id).padStart(3, "0"), slug: `report-${id}`, state: "PUBLISHED",
  publishedAt: "2026-09-01T12:00:00Z", updatedAt: "2026-09-02T12:00:00Z", ...overrides
});

describe("public feature API", () => {
  it("uses only bounded no-store public endpoints in HTTP mode", async () => {
    const request = vi.fn().mockResolvedValue({ items: [], page: 1, size: 20, total: 22 });
    const client = createPublicApi({ ...api, mode: "api", request });
    await client.published(1, 20);
    await client.byTag("Public Life", 1, 20);
    await client.topics();
    await client.related("a-story", 3);
    expect(request.mock.calls).toEqual([
      ["/api/v1/articles/discovery?page=1&size=20", { cache: "no-store" }],
      ["/api/v1/tags/public%20life?page=1&size=20", { cache: "no-store" }],
      ["/api/v1/topics", { cache: "no-store" }],
      ["/api/v1/articles/a-story/related?limit=3", { cache: "no-store" }]
    ]);
  });

  it("never falls back to fixtures after a production error", async () => {
    const latest = vi.fn();
    const client = createPublicApi({ ...api, mode: "api", request: vi.fn().mockRejectedValue(new Error("Unavailable")), articles: { ...api.articles, latest } });
    await expect(client.published()).rejects.toThrow("Unavailable");
    expect(latest).not.toHaveBeenCalled();
  });

  it("pages more than twenty explicit fake publications with stable sorting, inventory and withdrawal", async () => {
    const records = Array.from({ length: 25 }, (_, i) => published(i, { topic: "Public health", tags: ["Evidence"] }));
    const latest = vi.fn(async () => records);
    const client = createPublicApi({ ...api, mode: "fake", articles: { ...api.articles, latest } });
    expect((await client.published(1, 20)).items.map((item) => item.id)).toEqual(records.slice(20).map((item) => item.id));
    expect((await client.byTopic(" PUBLIC HEALTH ", 1, 20)).total).toBe(25);
    expect(await client.topics()).toEqual([{ value: "public health", articleCount: 25 }]);
    expect(await client.tags()).toEqual([{ value: "evidence", articleCount: 25 }]);
    records[24].state = "UNPUBLISHED";
    expect((await client.published(1, 20)).items).toHaveLength(4);
    expect((await client.byTag("Evidence", 1, 20)).total).toBe(24);
  });

  it("relates only by topic or whole tags, excludes self and unpublished records", async () => {
    const current = published(1, { topic: "Science", tags: ["Trust"] });
    const sameTopic = published(2, { topic: "Science", tags: ["Other"] });
    const sameTag = published(3, { topic: "Cities", tags: ["trust"] });
    const unrelated = published(4, { topic: "Ideas", tags: ["Trustworthy"] });
    const hidden = published(5, { state: "UNPUBLISHED", topic: "Science" });
    const client = createPublicApi({ ...api, mode: "fake", articles: { ...api.articles, latest: vi.fn(async () => [current, unrelated, hidden, sameTag, sameTopic]), bySlug: vi.fn(async () => current) } });
    expect((await client.related(current.slug)).map((item) => item.id)).toEqual([sameTopic.id, sameTag.id]);
  });

  it("preserves page totals and modification dates in presentation", async () => {
    vi.spyOn(publicApi, "published").mockResolvedValue({ items: [{ ...published(21), publishedAt: "2026-09-01T12:00:00Z" }], page: 1, size: 20, total: 21 });
    const result = await contentApi.latest(1, 20);
    expect(result).toMatchObject({ page: 1, size: 20, total: 21, items: [{ updatedAt: "2026-09-02T12:00:00Z", body: [] }] });
  });
});
