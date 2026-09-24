import type { Metadata } from "next";
import { notFound } from "next/navigation";
import Link from "next/link";
import Image from "next/image";
import { ArticleCard, formatDate } from "@/components/article-card";
import { contentApi, facetPath } from "@/lib/content";
import { publicBaseUrl } from "@/lib/public-base-url";

import { publicImageUrl } from "@/lib/api";
import { Comments } from "@/components/comments";
import { EmailForm } from "@/components/forms";
import { SourceLink } from "@/components/admin/shared";
import { ArticleContentView } from "@/components/article-content";

export const dynamic = "force-dynamic";
export const revalidate = 0;

type Props = { params: Promise<{ slug: string }> };
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const article = await contentApi.bySlug((await params).slug);
  if (!article) return { title: "Article not found" };
  return { title: article.seoTitle, description: article.seoDescription, alternates: { canonical: `/articles/${article.slug}` }, openGraph: { type: "article", title: article.seoTitle, description: article.seoDescription, publishedTime: article.publishedAt, modifiedTime: article.updatedAt, authors: [article.author], url: `/articles/${article.slug}`, ...(article.heroObjectKey ? { images: [publicImageUrl(article.slug, "social")] } : {}) } };
}
export default async function ArticlePage({ params }: Props) {
  const article = await contentApi.bySlug((await params).slug);
  if (!article) notFound();
  const related = await contentApi.related(article.slug);
  const siteUrl = publicBaseUrl();
  const structuredData = { "@context": "https://schema.org", "@type": "NewsArticle", headline: article.title, description: article.dek, datePublished: article.publishedAt, dateModified: article.updatedAt, ...(article.heroObjectKey ? { image: new URL(publicImageUrl(article.slug), siteUrl).href } : {}), author: { "@type": "Organization", name: article.author }, publisher: { "@type": "Organization", name: "Nsangusa" }, mainEntityOfPage: `${siteUrl}/articles/${article.slug}` };
  return <><article><header className="article-header shell">
    <p className="eyebrow"><Link href={facetPath("topics", article.topic)}>{article.topic}</Link></p><h1>{article.title}</h1><p className="dek">{article.dek}</p>
    <p className="byline">By {article.author} · <time dateTime={article.publishedAt}>{formatDate(article.publishedAt)}</time> · {article.minutes} min read</p>
    <p className="byline">Updated <time dateTime={article.updatedAt}>{formatDate(article.updatedAt)}</time></p>
    <nav className="meta-list" aria-label="Tags">{article.tags.map((tag) => <Link key={tag} href={facetPath("tags", tag)}>{tag}</Link>)}</nav>
  </header><div className="article-body shell">
    {article.correctionNote && <aside className="notice" aria-label="Correction note"><h2>Correction</h2><p className="plain-text">{article.correctionNote}</p></aside>}
    {article.heroObjectKey && <figure><Image className="editorial-image" unoptimized src={publicImageUrl(article.slug)} width={1200} height={675} alt={article.imageAltText || "Article illustration"} /><figcaption>{article.generatedImage ? "AI-generated editorial illustration. This is not a documentary photograph." : "Editorial illustration, not AI-generated. This is not a documentary photograph."}</figcaption></figure>}
    <ArticleContentView content={article.content} body={article.body.join("\n\n")} />
    {article.editorialContext && <aside className="review-panel"><h2>Editorial context</h2><p className="plain-text">{article.editorialContext}</p></aside>}
    <section className="review-panel" aria-label="Article sources"><h2>Sources</h2>{article.sources.length ? <ul>{article.sources.map((source) => <li key={source.sourcePostId}><SourceLink url={source.url}>{source.account} · {source.postId}</SourceLink> · <time dateTime={source.publishedAt}>{formatDate(source.publishedAt)}</time></li>)}</ul> : <p>No external source links are listed.</p>}</section>
    <aside className="review-panel" aria-label="Article newsletter signup"><h2>Follow the publication</h2><EmailForm /></aside>
  </div></article>{related.length > 0 && <section className="section shell" aria-label="Related articles"><h2>Related reporting</h2><div className="article-grid">{related.map((item) => <ArticleCard key={item.id} article={item} />)}</div></section>}<Comments articleId={article.id} /><script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(structuredData).replace(/</g, "\\u003c") }} /></>;
}
