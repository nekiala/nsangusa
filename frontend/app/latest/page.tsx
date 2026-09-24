import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";
import { notFound } from "next/navigation";
import { Pagination } from "@/components/pagination";
import { pageHref, readPagination, type SearchParams } from "@/lib/public-pagination";

export const dynamic = "force-dynamic";

type Props = { searchParams: Promise<SearchParams> };
export async function generateMetadata({ searchParams }: Props): Promise<Metadata> {
  const paging = readPagination(await searchParams, 50_000);
  if (!paging) return { title: "Page not found", robots: { index: false } };
  return { title: paging.page ? `Latest — page ${paging.page + 1}` : "Latest", alternates: { canonical: pageHref("/latest", paging.page, paging.size) } };
}
export default async function LatestPage({ searchParams }: Props) {
  const paging = readPagination(await searchParams, 50_000);
  if (!paging) notFound();
  const result = await contentApi.latest(paging.page, paging.size);
  if (paging.page > 0 && !result.items.length) notFound();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">The publication</p><h1>Latest</h1></div></header><section className="section shell">{result.items.length ? <div className="article-grid">{result.items.map((article) => <ArticleCard key={article.slug} article={article} />)}</div> : <p className="empty">No published articles are available yet.</p>}<Pagination {...result} path="/latest" maximumPage={50_000} /></section></>;
}
