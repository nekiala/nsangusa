import type { Metadata } from "next";
import { notFound } from "next/navigation";
import Link from "@/components/locale";
import Image from "next/image";
import { ArticleCard } from "@/components/article-card";
import { alternates, formatDate, localizePath } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
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
  const { locale, prefix, t } = await requestLocale();
  const article = await contentApi.bySlug((await params).slug, locale);
  if (!article) return { title: t("article.notFound") };
  return { title: article.seoTitle, description: article.seoDescription, alternates: alternates(prefix, `/articles/${article.slug}`), openGraph: { type: "article", title: article.seoTitle, description: article.seoDescription, publishedTime: article.publishedAt, modifiedTime: article.updatedAt, authors: [article.author], url: localizePath(prefix, `/articles/${article.slug}`), ...(article.heroObjectKey ? { images: [publicImageUrl(article.slug, "social")] } : {}) } };
}
export default async function ArticlePage({ params }: Props) {
  const { locale, prefix, t } = await requestLocale();
  const article = await contentApi.bySlug((await params).slug, locale);
  if (!article) notFound();
  const related = await contentApi.related(article.slug, locale);
  // The text may be the original when the article has no version in the reader's language.
  const language = article.language || locale;
  const siteUrl = publicBaseUrl();
  const structuredData = { "@context": "https://schema.org", "@type": "NewsArticle", headline: article.title, description: article.dek, datePublished: article.publishedAt, dateModified: article.updatedAt, ...(article.heroObjectKey ? { image: new URL(publicImageUrl(article.slug), siteUrl).href } : {}), author: { "@type": "Organization", name: article.author }, publisher: { "@type": "Organization", name: "Nsangusa" }, mainEntityOfPage: `${siteUrl}${localizePath(prefix, `/articles/${article.slug}`)}`, inLanguage: language };
  return <><article lang={language}><header className="article-header shell">
    <p className="eyebrow"><Link href={facetPath("topics", article.topic)}>{article.topic}</Link></p><h1>{article.title}</h1><p className="dek">{article.dek}</p>
    <p className="byline">{t("card.by", { author: article.author })} · <time dateTime={article.publishedAt}>{formatDate(article.publishedAt, locale)}</time> · {t("card.minutes", { count: article.minutes })}</p>
    <p className="byline">{t("article.updated")} <time dateTime={article.updatedAt}>{formatDate(article.updatedAt, locale)}</time></p>
    <nav className="meta-list" aria-label={t("article.tags")}>{article.tags.map((tag) => <Link key={tag} href={facetPath("tags", tag)}>{tag}</Link>)}</nav>
  </header><div className="article-body shell">
    {article.correctionNote && <aside className="notice" aria-label={t("article.correctionLabel")}><h2>{t("article.correction")}</h2><p className="plain-text">{article.correctionNote}</p></aside>}
    {article.heroObjectKey && <figure><Image className="editorial-image" unoptimized src={publicImageUrl(article.slug)} width={1200} height={675} alt={article.imageAltText || t("article.imageAlt")} /><figcaption>{t(article.generatedImage ? "article.imageGenerated" : "article.imageEditorial")}</figcaption></figure>}
    <ArticleContentView content={article.content} body={article.body.join("\n\n")} />
    {article.editorialContext && <aside className="review-panel"><h2>{t("article.context")}</h2><p className="plain-text">{article.editorialContext}</p></aside>}
    <section className="review-panel" aria-label={t("article.sourcesLabel")}><h2>{t("article.sources")}</h2>{article.sources.length ? <ul>{article.sources.map((source) => <li key={source.sourcePostId}><SourceLink url={source.url}>{source.account} · {source.postId}</SourceLink> · <time dateTime={source.publishedAt}>{formatDate(source.publishedAt, locale)}</time></li>)}</ul> : <p>{t("article.noSources")}</p>}</section>
    <aside className="review-panel" aria-label={t("article.newsletterLabel")}><h2>{t("article.follow")}</h2><EmailForm /></aside>
  </div></article>{related.length > 0 && <section className="section shell" aria-label={t("article.relatedLabel")}><h2>{t("article.related")}</h2><div className="article-grid">{related.map((item) => <ArticleCard key={item.id} article={item} />)}</div></section>}<Comments articleId={article.id} /><script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(structuredData).replace(/</g, "\\u003c") }} /></>;
}
