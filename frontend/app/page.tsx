import type { Metadata } from "next";
import { ArticleCard } from "@/components/article-card";
import Link from "@/components/locale";
import { contentApi, facetPath, facetTitle } from "@/lib/content";
import { alternates } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> {
  const { prefix, t } = await requestLocale();
  return { alternates: alternates(prefix, "/"), openGraph: { url: prefix || "/", title: "Nsangusa", type: "website", description: t("site.tagline") } };
}
export default async function HomePage() {
  const { locale, t } = await requestLocale();
  const [result, topics] = await Promise.all([contentApi.latest(0, 13, locale), contentApi.topics()]);
  const [lead, ...rest] = result.items;
  const secondary = rest.slice(0, 4);
  const more = rest.slice(4);
  return <>
    <h1 className="visually-hidden">{t("home.title")}</h1>
    {lead ? <section className="front shell" aria-labelledby="front-lead"><h2 className="front-label" id="front-lead">{t("home.lead")}</h2><div className="front-body">
      <ArticleCard article={lead} featured />
      {secondary.length > 0 && <div className="front-grid">{secondary.map((article) => <ArticleCard key={article.slug} article={article} />)}</div>}
    </div></section> : <section className="section shell"><p className="empty">{t("articles.none")}</p></section>}
    {more.length > 0 && <section className="front shell" aria-labelledby="front-more"><h2 className="front-label" id="front-more">{t("home.more")}</h2><div className="front-body">
      <div className="front-list">{more.map((article) => <ArticleCard key={article.slug} article={article} variant="compact" />)}</div>
      <p className="front-all"><Link href="/latest">{t("home.readAll")}</Link></p>
    </div></section>}
    <section className="front shell" aria-labelledby="front-topics"><h2 className="front-label" id="front-topics">{t("home.follow")}</h2><div className="front-body">
      <div className="meta-list">{topics.slice(0, 12).map((topic) => <Link key={topic.value} href={facetPath("topics", topic.value)}>{facetTitle(topic.value)}</Link>)}<Link href="/topics">{t("home.allTopics")}</Link><Link href="/tags">{t("home.allTags")}</Link></div>
    </div></section>
    <section className="about-strip"><div className="shell"><p className="eyebrow">{t("home.eyebrow")}</p><p className="about-title">{t("home.title")}</p><p className="intro">{t("home.intro")}</p></div></section>
  </>;
}
