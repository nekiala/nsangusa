import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";
import { notFound } from "next/navigation";
import { Pagination } from "@/components/pagination";
import { alternates, localizePath, plural } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
import { pageHref, readPagination, type SearchParams } from "@/lib/public-pagination";

export const dynamic = "force-dynamic";
type Props = { searchParams: Promise<SearchParams> };
export async function generateMetadata({ searchParams }: Props): Promise<Metadata> {
  const params = await searchParams;
  const paging = readPagination(params);
  const query = typeof params.q === "string" ? params.q.trim() : "";
  const { prefix, t } = await requestLocale();
  return { title: t("search.title"), robots: { index: false, follow: true }, alternates: alternates(prefix, paging ? pageHref("/search", paging.page, paging.size, query) : "/search") };
}
export default async function SearchPage({ searchParams }: Props) {
  const params = await searchParams;
  const paging = readPagination(params);
  if (!paging || Array.isArray(params.q)) notFound();
  const query = (params.q || "").trim();
  const tooLong = query.length > 200;
  const { locale, prefix, t } = await requestLocale();
  const results = tooLong ? { items: [], ...paging, total: 0 } : await contentApi.search(query, paging.page, paging.size, locale);
  if (paging.page > 0 && !results.items.length) notFound();
  return <section className="section shell"><p className="eyebrow">{t("search.eyebrow")}</p><h1>{t("search.title")}</h1><form className="search-form" action={localizePath(prefix, "/search")}><label className="visually-hidden" htmlFor="search">{t("search.label")}</label><input className="search-input" id="search" name="q" type="search" maxLength={200} defaultValue={query} placeholder={t("search.placeholder")} /><button>{t("search.submit")}</button></form>{tooLong ? <p role="alert">{t("search.tooLong")}</p> : <>{query ? <p className="section-title">{plural(locale, t, "search.results", results.total).replace("{query}", query)}</p> : <p>{t("search.prompt")}</p>}<div className="search-results">{results.items.map((article) => <ArticleCard key={article.slug} article={article} />)}</div>{query && !results.total && <p className="empty">{t("search.none")}</p>}<Pagination {...results} path="/search" query={query} /></>}</section>;
}
