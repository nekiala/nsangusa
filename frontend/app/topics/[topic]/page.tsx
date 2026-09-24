import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ArticleCard } from "@/components/article-card";
import { Pagination } from "@/components/pagination";
import { contentApi, facetPath, facetTitle } from "@/lib/content";
import { decodeFacetSegment, pageHref, readPagination, type SearchParams } from "@/lib/public-pagination";

export const dynamic = "force-dynamic";
type Props = { params: Promise<{ topic: string }>; searchParams?: Promise<SearchParams> };
export async function generateMetadata({ params, searchParams }: Props): Promise<Metadata> {
  const topic = (await params).topic.trim().toLowerCase();
  const paging = readPagination(await searchParams || {});
  if (!paging || !topic || topic.length > 100) return { title: "Topic not found", robots: { index: false } };
  return { title: `${facetTitle(topic)}${paging.page ? ` — page ${paging.page + 1}` : ""}`, alternates: { canonical: pageHref(facetPath("topics", topic), paging.page, paging.size) } };
}
export default async function TopicPage({ params, searchParams }: Props) {
  const topic = decodeFacetSegment((await params).topic);
  const paging = readPagination(await searchParams || {});
  if (!paging || !topic || topic.length > 100) notFound();
  const result = await contentApi.byTopic(topic, paging.page, paging.size);
  if (!result.items.length) notFound();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">Topic</p><h1>{facetTitle(topic)}</h1></div></header><section className="section shell"><div className="article-grid">{result.items.map((article) => <ArticleCard key={article.slug} article={article} />)}</div><Pagination {...result} path={facetPath("topics", topic)} /></section></>;
}
