import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";
import { notFound } from "next/navigation";
import { Pagination } from "@/components/pagination";
import { alternates } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
import { pageHref, readPagination, type SearchParams } from "@/lib/public-pagination";

export const dynamic = "force-dynamic";

type Props = { searchParams: Promise<SearchParams> };
export async function generateMetadata({ searchParams }: Props): Promise<Metadata> {
  const paging = readPagination(await searchParams, 50_000);
  const { prefix, t } = await requestLocale();
  if (!paging) return { title: t("page.notFound"), robots: { index: false } };
  return { title: paging.page ? t("page.suffix", { title: t("latest.title"), page: paging.page + 1 }) : t("latest.title"), alternates: alternates(prefix, pageHref("/latest", paging.page, paging.size)) };
}
export default async function LatestPage({ searchParams }: Props) {
  const paging = readPagination(await searchParams, 50_000);
  if (!paging) notFound();
  const { locale, t } = await requestLocale();
  const result = await contentApi.latest(paging.page, paging.size, locale);
  if (paging.page > 0 && !result.items.length) notFound();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">{t("latest.eyebrow")}</p><h1>{t("latest.title")}</h1></div></header><section className="section shell">{result.items.length ? <div className="article-grid">{result.items.map((article) => <ArticleCard key={article.slug} article={article} />)}</div> : <p className="empty">{t("articles.none")}</p>}<Pagination {...result} path="/latest" maximumPage={50_000} /></section></>;
}
