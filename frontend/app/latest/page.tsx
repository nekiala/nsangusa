import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";

export const dynamic = "force-dynamic";

export const metadata: Metadata = { title: "Latest", alternates: { canonical: "/latest" } };
export default async function LatestPage() {
  const articles = await contentApi.latest();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">The publication</p><h1>Latest</h1></div></header><section className="section shell"><div className="article-grid">{articles.map((article) => <ArticleCard key={article.slug} article={article} />)}</div></section></>;
}
