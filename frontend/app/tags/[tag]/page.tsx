import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import { contentApi } from "@/lib/content";

export const dynamic = "force-dynamic";
type Props = { params: Promise<{ tag: string }> };
export async function generateMetadata({ params }: Props): Promise<Metadata> { const tag = (await params).tag; return { title: `Tag: ${tag}`, alternates: { canonical: `/tags/${tag}` } }; }
export default async function TagPage({ params }: Props) { const tag = (await params).tag; const articles = await contentApi.byTag(tag); return <><header className="page-head"><div className="shell"><p className="eyebrow">Tag</p><h1>{tag}</h1></div></header><section className="section shell">{articles.length ? <div className="article-grid">{articles.map((article) => <ArticleCard key={article.slug} article={article} />)}</div> : <p className="empty">No published work has this tag yet.</p>}</section></>; }
