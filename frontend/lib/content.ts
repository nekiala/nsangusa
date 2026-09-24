import { api, type ApiArticle, type ArticleSource, type Page } from "@/lib/api";
import { publicApi, type ArticleSummary, type PublicSearchResult } from "@/lib/public-api";
import { resolvedContent, type ArticleContent } from "@/lib/article-content";

export type Topic = "Culture" | "Cities" | "Technology" | "Ideas";
export type Article = {
  id: string; slug: string; title: string; dek: string; topic: string; author: string; publishedAt: string; minutes: number;
  body: string[]; content?: ArticleContent; tags: string[]; commentsEnabled: boolean; updatedAt: string; editorialContext: string | null;
  sources: ArticleSource[]; heroObjectKey: string | null; imageAltText: string | null; generatedImage: boolean; seoTitle: string; seoDescription: string;
  correctionNote: string | null;
};

export const topics: Topic[] = ["Culture", "Cities", "Technology", "Ideas"];

function present(article: ApiArticle): Article {
  return {
    id: article.id, slug: article.slug, title: article.headline, dek: article.summary, topic: article.topic,
    author: "Nsangusa", publishedAt: article.publishedAt || article.updatedAt,
    minutes: Math.max(1, Math.ceil(article.body.trim().split(/\s+/).length / 220)),
    body: article.body.split(/\n\s*\n/).filter(Boolean), content: resolvedContent(article.content, article.body), tags: article.tags, commentsEnabled: article.commentsEnabled,
    updatedAt: article.updatedAt, editorialContext: article.editorialContext, sources: article.sources,
    heroObjectKey: article.heroObjectKey || null, imageAltText: article.imageAltText || null, generatedImage: article.generatedImage,
    seoTitle: article.seoTitle || article.headline, seoDescription: article.seoDescription || article.summary,
    correctionNote: article.state === "PUBLISHED" ? article.correctionNote : null
  };
}

function presentSummary(article: ArticleSummary | PublicSearchResult): Article {
  return {
    id: "articleId" in article ? article.articleId : article.id,
    slug: article.slug,
    title: article.headline,
    dek: article.summary,
    topic: article.topic,
    author: "Nsangusa",
    publishedAt: article.publishedAt,
    minutes: 0,
    body: [],
    tags: article.tags,
    commentsEnabled: false,
    updatedAt: article.updatedAt, editorialContext: null, sources: [], heroObjectKey: null, imageAltText: null,
    generatedImage: false, seoTitle: article.headline, seoDescription: article.summary, correctionNote: null
  };
}

export type ArticlePage = Page<Article>;
function presentPage(page: Page<ArticleSummary | PublicSearchResult>): ArticlePage {
  return { ...page, items: page.items.map(presentSummary) };
}
export const facetTitle = (value: string) => value.charAt(0).toUpperCase() + value.slice(1);
export const facetPath = (kind: "topics" | "tags", value: string) => `/${kind}/${encodeURIComponent(value.trim().toLowerCase())}`;

export const contentApi = {
  async latest(page = 0, size = 20): Promise<ArticlePage> { return presentPage(await publicApi.published(page, size)); },
  async featured(): Promise<Article> {
    const article = (await contentApi.latest(0, 1)).items[0];
    if (!article) throw new Error("No published articles are available.");
    return article;
  },
  async bySlug(slug: string): Promise<Article | undefined> {
    try {
      const article = await api.articles.bySlug(slug);
      return article.state === "PUBLISHED" ? present(article) : undefined;
    } catch (error) {
      if (error instanceof Error && "status" in error && error.status === 404) return undefined;
      throw error;
    }
  },
  topics: publicApi.topics,
  tags: publicApi.tags,
  async related(slug: string): Promise<Article[]> { return (await publicApi.related(slug)).map(presentSummary); },
  async byTopic(topic: string, page = 0, size = 20): Promise<ArticlePage> { return presentPage(await publicApi.byTopic(topic, page, size)); },
  async byTag(tag: string, page = 0, size = 20): Promise<ArticlePage> { return presentPage(await publicApi.byTag(tag, page, size)); },
  async search(term: string, page = 0, size = 20): Promise<ArticlePage> {
    const query = term.trim();
    return query ? presentPage(await publicApi.search(query, page, size)) : { items: [], page, size, total: 0 };
  }
};
