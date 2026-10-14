import type { Metadata } from "next";
import Link from "@/components/locale";
import { contentApi, facetPath, facetTitle } from "@/lib/content";
import { alternates, plural } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> {
  const { prefix, t } = await requestLocale();
  return { title: t("topics.title"), alternates: alternates(prefix, "/topics") };
}
export default async function TopicsPage() {
  const [topics, { locale, t }] = await Promise.all([contentApi.topics(), requestLocale()]);
  return <><header className="page-head"><div className="shell"><p className="eyebrow">{t("topics.eyebrow")}</p><h1>{t("topics.title")}</h1></div></header><section className="section shell">{topics.length ? <div className="article-grid">{topics.map((topic) => <article className="article-card" key={topic.value}><p className="eyebrow">{t("topics.label")}</p><h2><Link href={facetPath("topics", topic.value)}>{facetTitle(topic.value)}</Link></h2><p>{plural(locale, t, "published", topic.articleCount)}.</p></article>)}</div> : <p className="empty">{t("topics.none")}</p>}<p><Link href="/tags">{t("topics.browseTags")}</Link></p></section></>;
}
