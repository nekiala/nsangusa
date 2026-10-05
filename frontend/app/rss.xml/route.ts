import { publicApi } from "@/lib/public-api";
import { localePrefix, localizePath, translator } from "@/lib/i18n";
import { absoluteUrl, articleUrl, escapeXml, feedLocale, FEED_PAGE_SIZE, unavailableFeed, xmlResponse } from "@/lib/public-feeds";

export const dynamic = "force-dynamic";
export const revalidate = 0;
const feedUrl = (page: number, prefix = "") => absoluteUrl(localizePath(prefix, page === 0 ? "/rss.xml" : `/rss.xml?page=${page + 1}`));
export async function GET(request?: Request) {
  const params = new URL(request?.url || absoluteUrl("/rss.xml")).searchParams;
  const rawPage = params.get("page") || "1";
  if (params.getAll("page").length > 1 || !/^[1-9]\d*$/.test(rawPage)) return unavailableFeed(404);
  const locale = feedLocale(request);
  const prefix = locale ? localePrefix(locale) : "";
  const page = Number(rawPage) - 1;
  if (!Number.isSafeInteger(page) || page > 49_999) return unavailableFeed(404);
  try {
    const result = await publicApi.published(page, FEED_PAGE_SIZE, locale);
    const pages = Math.max(1, Math.ceil(result.total / FEED_PAGE_SIZE));
    if (pages > 50_000) return unavailableFeed();
    if (page >= pages) return unavailableFeed(404);
    const links: [string, number][] = [["self", page], ["first", 0], ["last", pages - 1]];
    if (page > 0) links.push(["previous", page - 1]);
    if (page + 1 < pages) links.push(["next", page + 1]);
    const items = result.items.map((article) => `<item><title>${escapeXml(article.headline)}</title><link>${escapeXml(articleUrl(article.slug, locale))}</link><guid isPermaLink="true">${escapeXml(articleUrl(article.slug, locale))}</guid><pubDate>${new Date(article.publishedAt).toUTCString()}</pubDate><description>${escapeXml(article.summary)}</description></item>`).join("");
    return xmlResponse(`<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Nsangusa</title><link>${escapeXml(absoluteUrl(localizePath(prefix, "/")))}</link><description>${escapeXml(translator(locale || "en")("feed.description"))}</description>${locale ? `<language>${locale}</language>` : ""}${links.map(([rel, target]) => `<atom:link href="${escapeXml(feedUrl(target, prefix))}" rel="${rel}" type="application/rss+xml"/>`).join("")}${items}</channel></rss>`, "application/rss+xml");
  } catch {
    return unavailableFeed();
  }
}
