import Link from "next/link";
import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi, facetPath, facetTitle } from "@/lib/content";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { alternates: { canonical: "/" }, openGraph: { url: "/", title: "Nsangusa", type: "website", description: "Independent reporting and essays for a more attentive public life." } };
export default async function HomePage() {
  const [result, topics] = await Promise.all([contentApi.latest(0, 7), contentApi.topics()]);
  const latest = result.items;
  const featured = latest[0];
  return <>
    <section className="hero"><div className="shell"><p className="eyebrow">An independent publication</p><h1>For a more attentive public life.</h1><p className="intro">Nsangusa publishes reporting, criticism and essays about the systems and places that shape how we live.</p></div></section>
    {featured ? <section className="section shell"><p className="section-title">Lead story</p><ArticleCard article={featured} featured /></section> : <section className="section shell"><p className="empty">No published articles are available yet.</p></section>}
    {latest.length > 1 && <section className="section shell"><p className="section-title">From the desk</p><div className="article-grid">{latest.slice(1).map((article) => <ArticleCard key={article.slug} article={article} />)}</div><p><Link href="/latest">Read all published articles</Link></p></section>}
    <section className="section shell"><p className="section-title">Follow a subject</p><div className="meta-list">{topics.slice(0, 12).map((topic) => <Link key={topic.value} href={facetPath("topics", topic.value)}>{facetTitle(topic.value)}</Link>)}<Link href="/topics">All topics</Link><Link href="/tags">All tags</Link></div></section>
  </>;
}
