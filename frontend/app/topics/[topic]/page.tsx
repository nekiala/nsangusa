import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ArticleCard } from "@/components/article-card";
import { contentApi, topics } from "@/lib/content";

export const dynamic = "force-dynamic";
type Props = { params: Promise<{ topic: string }> };
function topicName(slug: string) { return topics.find((value) => value.toLowerCase() === slug.toLowerCase()); }
export async function generateMetadata({ params }: Props): Promise<Metadata> { const slug = (await params).topic; const topic = topicName(slug); return topic ? { title: topic, alternates: { canonical: `/topics/${topic.toLowerCase()}` } } : { title: "Topic not found" }; }
export default async function TopicPage({ params }: Props) { const slug = (await params).topic; const topic = topicName(slug); if (!topic) notFound(); const articles = await contentApi.byTopic(topic); return <><header className="page-head"><div className="shell"><p className="eyebrow">Topic</p><h1>{topic}</h1></div></header><section className="section shell"><div className="article-grid">{articles.map((article) => <ArticleCard key={article.slug} article={article} />)}</div></section></>; }
