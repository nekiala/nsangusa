import Link from "next/link";
import { facetPath, type Article } from "@/lib/content";

export function ArticleCard({ article, featured = false }: { article: Article; featured?: boolean }) {
  return <article className={featured ? "article-card featured" : "article-card"}>
    <p className="eyebrow"><Link href={facetPath("topics", article.topic)}>{article.topic}</Link></p>
    <h2><Link href={`/articles/${article.slug}`}>{article.title}</Link></h2>
    <p className="dek">{article.dek}</p>
    <p className="byline">By {article.author} <span aria-hidden="true">·</span> <time dateTime={article.publishedAt}>{formatDate(article.publishedAt)}</time>{article.minutes > 0 && <> <span aria-hidden="true">·</span> {article.minutes} min read</>}</p>
  </article>;
}

export function formatDate(date: string) {
  return new Intl.DateTimeFormat("en", { month: "long", day: "numeric", year: "numeric" }).format(new Date(date));
}
