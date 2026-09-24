import type { Metadata } from "next";
import Link from "next/link";
import { contentApi, facetPath, facetTitle } from "@/lib/content";
export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Topics", alternates: { canonical: "/topics" } };
export default async function TopicsPage() {
  const topics = await contentApi.topics();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">Browse the archive</p><h1>Topics</h1></div></header><section className="section shell">{topics.length ? <div className="article-grid">{topics.map((topic) => <article className="article-card" key={topic.value}><p className="eyebrow">Topic</p><h2><Link href={facetPath("topics", topic.value)}>{facetTitle(topic.value)}</Link></h2><p>{topic.articleCount} published article{topic.articleCount === 1 ? "" : "s"}.</p></article>)}</div> : <p className="empty">No topics have published articles yet.</p>}<p><Link href="/tags">Browse all tags</Link></p></section></>;
}
