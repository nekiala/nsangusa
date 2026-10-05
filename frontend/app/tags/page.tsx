import type { Metadata } from "next";
import Link from "@/components/locale";
import { contentApi, facetPath, facetTitle } from "@/lib/content";
import { alternates, plural } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> {
  const { prefix, t } = await requestLocale();
  return { title: t("tags.title"), alternates: alternates(prefix, "/tags") };
}
export default async function TagsPage() {
  const [tags, { locale, t }] = await Promise.all([contentApi.tags(), requestLocale()]);
  return <><header className="page-head"><div className="shell"><p className="eyebrow">{t("topics.eyebrow")}</p><h1>{t("tags.title")}</h1></div></header><section className="section shell">{tags.length ? <ul>{tags.map((tag) => <li key={tag.value}><Link href={facetPath("tags", tag.value)}>{facetTitle(tag.value)}</Link> — {plural(locale, t, "published", tag.articleCount)}</li>)}</ul> : <p className="empty">{t("tags.none")}</p>}<p><Link href="/topics">{t("tags.browseTopics")}</Link></p></section></>;
}
