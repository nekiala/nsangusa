import { api, type ApiArticle, type SearchResult } from "@/lib/api";

export type Topic = "Culture" | "Cities" | "Technology" | "Ideas";
export type Article = { id: string; slug: string; title: string; dek: string; topic: string; author: string; publishedAt: string; minutes: number; body: string[]; tags: string[]; commentsEnabled: boolean };

export const topics: Topic[] = ["Culture", "Cities", "Technology", "Ideas"];
export const siteUrl = process.env.NEXT_PUBLIC_SITE_URL || "http://localhost:3000";

function present(article: ApiArticle): Article {
  return {
    id: article.id, slug: article.slug, title: article.headline, dek: article.summary, topic: article.topic,
    author: "Nsangusa", publishedAt: article.publishedAt || article.updatedAt,
    minutes: Math.max(1, Math.ceil(article.body.trim().split(/\s+/).length / 220)),
    body: article.body.split(/\n\s*\n/).filter(Boolean), tags: article.tags, commentsEnabled: article.commentsEnabled
  };
}

function presentSearch(article: SearchResult): Article {
  return {
    id: article.articleId,
    slug: article.slug,
    title: article.headline,
    dek: article.summary,
    topic: article.topic,
    author: "Nsangusa",
    publishedAt: article.publishedAt,
    minutes: 1,
    body: [],
    tags: article.tags,
    commentsEnabled: false,
  };
}

export const contentApi = {
  async latest(): Promise<Article[]> { return (await api.articles.latest()).map(present); },
  async featured(): Promise<Article> {
    const article = (await contentApi.latest())[0];
    if (!article) throw new Error("No published articles are available.");
    return article;
  },
  async bySlug(slug: string): Promise<Article | undefined> {
    try { return present(await api.articles.bySlug(slug)); } catch (error) {
      if (error instanceof Error && "status" in error && error.status === 404) return undefined;
      throw error;
    }
  },
  async byTopic(topic: string): Promise<Article[]> { return (await api.articles.byTopic(topic)).items.map(presentSearch); },
  async byTag(tag: string): Promise<Article[]> { return (await api.articles.byTag(tag)).items.map(presentSearch); },
  async search(term: string): Promise<Article[]> {
    const query = term.trim();
    return query ? (await api.articles.search(query)).items.map(presentSearch) : [];
  }
};
