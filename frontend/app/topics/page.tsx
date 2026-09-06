import type { Metadata } from "next";
import Link from "next/link";
import { topics } from "@/lib/content";
export const metadata: Metadata = { title: "Topics", alternates: { canonical: "/topics" } };
export default function TopicsPage() { return <><header className="page-head"><div className="shell"><p className="eyebrow">Browse the archive</p><h1>Topics</h1></div></header><section className="section shell"><div className="article-grid">{topics.map((topic) => <article className="article-card" key={topic}><p className="eyebrow">Topic</p><h2><Link href={`/topics/${topic.toLowerCase()}`}>{topic}</Link></h2><p>Reporting and ideas held together by a shared question.</p></article>)}</div></section></>; }
