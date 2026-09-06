import type { Metadata } from "next";
import { notFound } from "next/navigation";
import Link from "next/link";
import { formatDate } from "@/components/article-card";
import { contentApi, siteUrl } from "@/lib/content";

export const dynamic = "force-dynamic";
import { api } from "@/lib/api";
import { Comments } from "@/components/comments";

type Props = { params: Promise<{ slug: string }> };
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const article = await contentApi.bySlug((await params).slug);
  if (!article) return { title: "Article not found" };
  return { title: article.title, description: article.dek, alternates: { canonical: `/articles/${article.slug}` }, openGraph: { type: "article", title: article.title, description: article.dek, publishedTime: article.publishedAt, authors: [article.author], url: `/articles/${article.slug}` } };
}
export default async function ArticlePage({ params }: Props) {
  const article = await contentApi.bySlug((await params).slug);
  if (!article) notFound();
  const comments = article.commentsEnabled ? await api.articles.comments(article.id) : [];
  const structuredData = { "@context": "https://schema.org", "@type": "NewsArticle", headline: article.title, description: article.dek, datePublished: article.publishedAt, author: { "@type": "Person", name: article.author }, publisher: { "@type": "Organization", name: "Nsangusa" }, mainEntityOfPage: `${siteUrl}/articles/${article.slug}` };
  return <><article><header className="article-header shell"><p className="eyebrow"><Link href={`/topics/${article.topic.toLowerCase()}`}>{article.topic}</Link></p><h1>{article.title}</h1><p className="dek">{article.dek}</p><p className="byline">By {article.author} · <time dateTime={article.publishedAt}>{formatDate(article.publishedAt)}</time> · {article.minutes} min read</p><div className="meta-list" aria-label="Tags">{article.tags.map((tag) => <Link key={tag} href={`/tags/${tag.toLowerCase()}`}>{tag}</Link>)}</div></header><div className="article-body shell">{article.body.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}</div></article>{article.commentsEnabled && <Comments articleId={article.id} initialComments={comments} />}<script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(structuredData).replace(/</g, "\\u003c") }} /></>;
}
