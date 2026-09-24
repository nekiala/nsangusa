import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";
import { notFound } from "next/navigation";
import { Pagination } from "@/components/pagination";
import { pageHref, readPagination, type SearchParams } from "@/lib/public-pagination";

export const dynamic = "force-dynamic";
type Props = { searchParams: Promise<SearchParams> };
export async function generateMetadata({ searchParams }: Props): Promise<Metadata> {
  const params = await searchParams;
  const paging = readPagination(params);
  const query = typeof params.q === "string" ? params.q.trim() : "";
  return { title: "Search", robots: { index: false, follow: true }, alternates: { canonical: paging ? pageHref("/search", paging.page, paging.size, query) : "/search" } };
}
export default async function SearchPage({ searchParams }: Props) {
  const params = await searchParams;
  const paging = readPagination(params);
  if (!paging || Array.isArray(params.q)) notFound();
  const query = (params.q || "").trim();
  const tooLong = query.length > 200;
  const results = tooLong ? { items: [], ...paging, total: 0 } : await contentApi.search(query, paging.page, paging.size);
  if (paging.page > 0 && !results.items.length) notFound();
  return <section className="section shell"><p className="eyebrow">Archive</p><h1>Search</h1><form className="search-form" action="/search"><label className="visually-hidden" htmlFor="search">Search Nsangusa</label><input className="search-input" id="search" name="q" type="search" maxLength={200} defaultValue={query} placeholder="Search reporting and essays" /><button>Search</button></form>{tooLong ? <p role="alert">Search must be 200 characters or fewer.</p> : <>{query ? <p className="section-title">{results.total} result{results.total === 1 ? "" : "s"} for “{query}”</p> : <p>Enter a word or phrase to search published reporting and essays.</p>}<div className="search-results">{results.items.map((article) => <ArticleCard key={article.slug} article={article} />)}</div>{query && !results.total && <p className="empty">No published work matches this search yet.</p>}<Pagination {...results} path="/search" query={query} /></>}</section>;
}
