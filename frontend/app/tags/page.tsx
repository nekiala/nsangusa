import type { Metadata } from "next";
import Link from "next/link";
import { contentApi, facetPath, facetTitle } from "@/lib/content";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Tags", alternates: { canonical: "/tags" } };
export default async function TagsPage() {
  const tags = await contentApi.tags();
  return <><header className="page-head"><div className="shell"><p className="eyebrow">Browse the archive</p><h1>Tags</h1></div></header><section className="section shell">{tags.length ? <ul>{tags.map((tag) => <li key={tag.value}><Link href={facetPath("tags", tag.value)}>{facetTitle(tag.value)}</Link> — {tag.articleCount} published article{tag.articleCount === 1 ? "" : "s"}</li>)}</ul> : <p className="empty">No tags have published articles yet.</p>}<p><Link href="/topics">Browse all topics</Link></p></section></>;
}
