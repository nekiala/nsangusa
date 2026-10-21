import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ArticleCard } from "@/components/article-card";
import { Pagination } from "@/components/pagination";
import { contentApi, facetPath, facetTitle } from "@/lib/content";
import { alternates } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
import { decodeFacetSegment, pageHref, readPagination, type SearchParams } from "@/lib/public-pagination";

export const dynamic = "force-dynamic";
type Props = { params: Promise<{ tag: string }>; searchParams?: Promise<SearchParams> };
export async function generateMetadata({ params, searchParams }: Props): Promise<Metadata> {
  const tag = (await params).tag.trim().toLowerCase();
  const paging = readPagination(await searchParams || {});
  const { prefix, t } = await requestLocale();
  if (!paging || !tag || tag.length > 100) return { title: t("tags.notFound"), robots: { index: false } };
  const title = t("tags.titled", { tag: facetTitle(tag) });
  return { title: paging.page ? t("page.suffix", { title, page: paging.page + 1 }) : title, alternates: alternates(prefix, pageHref(facetPath("tags", tag), paging.page, paging.size)) };
}
export default async function TagPage({ params, searchParams }: Props) {
  const tag = decodeFacetSegment((await params).tag);
  const paging = readPagination(await searchParams || {});
  if (!paging || !tag || tag.length > 100) notFound();
  const { locale, t } = await requestLocale();
  const result = await contentApi.byTag(tag, paging.page, paging.size, locale);
  if (!result.items.length) notFound();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">{t("tags.label")}</p><h1>{facetTitle(tag)}</h1></div></header><section className="section shell"><div className="article-grid">{result.items.map((article) => <ArticleCard key={article.slug} article={article} />)}</div><Pagination {...result} path={facetPath("tags", tag)} /></section></>;
}
