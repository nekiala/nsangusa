"use client";

import Image from "next/image";
import Link, { useLocale } from "@/components/locale";
import { publicImageUrl } from "@/lib/api";
import { facetPath, type Article } from "@/lib/content";
import { formatWhen } from "@/lib/i18n";

/**
 * A story teaser. `lead` is the front-page opener with a large image beside the text, `compact`
 * is a headline-first row for long lists, and the default is an image-topped card.
 */
export function ArticleCard({ article, featured = false, variant }: { article: Article; featured?: boolean; variant?: "lead" | "compact" }) {
  const { locale, t } = useLocale();
  const kind = variant || (featured ? "lead" : "standard");
  const href = `/articles/${article.slug}`;
  return <article className={`article-card card-${kind}${featured ? " featured" : ""}`}>
    {article.hasImage && <Link className="card-media" href={href} tabIndex={-1} aria-hidden="true">
      <Image unoptimized src={publicImageUrl(article.slug, kind === "lead" ? "hero" : "thumbnail")} width={kind === "lead" ? 1200 : 480} height={kind === "lead" ? 675 : 270}
        alt="" loading={kind === "lead" ? "eager" : "lazy"} />
    </Link>}
    <div className="card-text">
      <p className="eyebrow kicker"><Link href={facetPath("topics", article.topic)}>{article.topic}</Link></p>
      <h2><Link href={href}>{article.title}</Link></h2>
      {kind !== "compact" && <p className="dek">{article.dek}</p>}
      <p className="byline">{kind === "lead" && <>{t("card.by", { author: article.author })} <span aria-hidden="true">·</span> </>}<time dateTime={article.publishedAt} suppressHydrationWarning>{formatWhen(article.publishedAt, locale)}</time>{article.minutes > 0 && <> <span aria-hidden="true">·</span> {t("card.minutes", { count: article.minutes })}</>}</p>
    </div>
  </article>;
}
