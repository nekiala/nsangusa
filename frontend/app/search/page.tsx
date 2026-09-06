import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Search", alternates: { canonical: "/search" } };
export default async function SearchPage({ searchParams }: { searchParams: Promise<{ q?: string }> }) {
  const query = ((await searchParams).q || "").trim(); const results = await contentApi.search(query);
  return <section className="section shell"><p className="eyebrow">Archive</p><h1>Search</h1><form className="search-form" action="/search"><label className="visually-hidden" htmlFor="search">Search Nsangusa</label><input className="search-input" id="search" name="q" type="search" defaultValue={query} placeholder="Search reporting and essays" /><button>Search</button></form>{query && <p className="section-title">{results.length} result{results.length === 1 ? "" : "s"} for “{query}”</p>}<div className="search-results">{results.map((article) => <ArticleCard key={article.slug} article={article} />)}</div>{query && !results.length && <p className="empty">No published work matches this search yet.</p>}</section>;
}
