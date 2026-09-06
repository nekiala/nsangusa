import Link from "next/link";
import { ArticleCard } from "@/components/article-card";
import { contentApi, topics } from "@/lib/content";

export const dynamic = "force-dynamic";
export default async function HomePage() {
  const latest = await contentApi.latest();
  const featured = latest[0];
  if (!featured) throw new Error("No published articles are available.");
  return <>
    <section className="hero"><div className="shell"><p className="eyebrow">An independent publication</p><h1>For a more attentive public life.</h1><p className="intro">Nsangusa publishes reporting, criticism and essays about the systems and places that shape how we live.</p></div></section>
    <section className="section shell"><p className="section-title">Lead story</p><ArticleCard article={featured} featured /></section>
    <section className="section shell"><p className="section-title">From the desk</p><div className="article-grid">{latest.slice(1).map((article) => <ArticleCard key={article.slug} article={article} />)}</div></section>
    <section className="section shell"><p className="section-title">Follow a subject</p><div className="meta-list">{topics.map((topic) => <Link key={topic} href={`/topics/${topic.toLowerCase()}`}>{topic}</Link>)}</div></section>
  </>;
}
