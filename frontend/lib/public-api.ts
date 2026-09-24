import { api, type ApiArticle, type Page, type SearchResult } from "@/lib/api";

export type ArticleSummary = Pick<ApiArticle, "id" | "slug" | "headline" | "summary" | "topic" | "tags" | "updatedAt"> & { publishedAt: string };
export type PublicSearchResult = SearchResult & { updatedAt: string };
export type Facet = { value: string; articleCount: number };
const normalized = (value: string) => value.trim().toLowerCase();

export function createPublicApi(client = api) {
  async function fakePublished() {
    return (await client.articles.latest(100))
      .filter((article) => article.state === "PUBLISHED" && article.publishedAt)
      .sort((a, b) => b.publishedAt!.localeCompare(a.publishedAt!) || a.id.localeCompare(b.id));
  }
  function summary(article: ApiArticle): ArticleSummary {
    return { id: article.id, slug: article.slug, headline: article.headline, summary: article.summary, topic: article.topic, tags: article.tags, publishedAt: article.publishedAt!, updatedAt: article.updatedAt };
  }
  function bounds(page: number, size: number, maximum = 49_999) {
    if (!Number.isInteger(page) || page < 0 || page > maximum || !Number.isInteger(size) || size < 1 || size > 100) throw new RangeError("Invalid public page");
  }
  function paginate<T>(items: T[], page: number, size: number): Page<T> {
    return { items: items.slice(page * size, (page + 1) * size), page, size, total: items.length };
  }
  async function facetInventory(kind: "topics" | "tags"): Promise<Facet[]> {
    if (client.mode !== "fake") return client.request(`/api/v1/${kind}`, { cache: "no-store" });
    const counts = new Map<string, number>();
    for (const article of await fakePublished()) {
      for (const value of new Set((kind === "topics" ? [article.topic] : article.tags).map(normalized))) counts.set(value, (counts.get(value) || 0) + 1);
    }
    return [...counts].map(([value, articleCount]) => ({ value, articleCount }))
      .sort((a, b) => b.articleCount - a.articleCount || a.value.localeCompare(b.value));
  }
  async function results(kind: "search" | "topics" | "tags", value: string, page: number, size: number): Promise<Page<PublicSearchResult>> {
    bounds(page, size, 10_000);
    const term = normalized(value);
    if (!term || term.length > (kind === "search" ? 200 : 100)) throw new RangeError("Invalid search or facet");
    if (client.mode !== "fake") {
      const path = kind === "search" ? `/api/v1/search?q=${encodeURIComponent(value.trim())}&` : `/api/v1/${kind}/${encodeURIComponent(term)}?`;
      return client.request(`${path}page=${page}&size=${size}`, { cache: "no-store" });
    }
    const articles = (await fakePublished()).filter((article) => kind === "topics" ? normalized(article.topic) === term
      : kind === "tags" ? article.tags.some((tag) => normalized(tag) === term)
        : `${article.headline} ${article.summary} ${article.body} ${article.tags.join(" ")}`.toLowerCase().includes(term));
    return paginate(articles.map((article) => ({ ...summary(article), articleId: article.id, rank: kind === "search" ? 1 : 0 })), page, size);
  }
  return {
    async published(page = 0, size = 20): Promise<Page<ArticleSummary>> {
      bounds(page, size);
      if (client.mode !== "fake") return client.request(`/api/v1/articles/discovery?page=${page}&size=${size}`, { cache: "no-store" });
      return paginate((await fakePublished()).map(summary), page, size);
    },
    async related(slug: string, limit = 3): Promise<ArticleSummary[]> {
      if (!Number.isInteger(limit) || limit < 1 || limit > 20) throw new RangeError("Invalid related limit");
      if (client.mode !== "fake") return client.request(`/api/v1/articles/${encodeURIComponent(slug)}/related?limit=${limit}`, { cache: "no-store" });
      const current = await client.articles.bySlug(slug);
      if (current.state !== "PUBLISHED") return [];
      const tags = new Set(current.tags.map(normalized));
      return (await fakePublished()).filter((article) => article.id !== current.id)
        .map((article) => ({ article, topic: Number(normalized(article.topic) === normalized(current.topic)), tags: new Set(article.tags.map(normalized).filter((tag) => tags.has(tag))).size }))
        .filter((item) => item.topic || item.tags)
        .sort((a, b) => b.topic - a.topic || b.tags - a.tags || b.article.publishedAt!.localeCompare(a.article.publishedAt!) || a.article.id.localeCompare(b.article.id))
        .slice(0, limit).map(({ article }) => summary(article));
    },
    topics: () => facetInventory("topics"),
    tags: () => facetInventory("tags"),
    search: (query: string, page = 0, size = 20) => results("search", query, page, size),
    byTopic: (topic: string, page = 0, size = 20) => results("topics", topic, page, size),
    byTag: (tag: string, page = 0, size = 20) => results("tags", tag, page, size)
  };
}

export const publicApi = createPublicApi();
