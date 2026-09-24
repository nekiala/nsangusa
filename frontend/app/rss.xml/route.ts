import { publicApi } from "@/lib/public-api";
import { absoluteUrl, articleUrl, escapeXml, FEED_PAGE_SIZE, unavailableFeed, xmlResponse } from "@/lib/public-feeds";

export const dynamic = "force-dynamic";
export const revalidate = 0;
const feedUrl = (page: number) => absoluteUrl(page === 0 ? "/rss.xml" : `/rss.xml?page=${page + 1}`);
export async function GET(request?: Request) {
  const params = new URL(request?.url || absoluteUrl("/rss.xml")).searchParams;
  const rawPage = params.get("page") || "1";
  if (params.getAll("page").length > 1 || !/^[1-9]\d*$/.test(rawPage)) return unavailableFeed(404);
  const page = Number(rawPage) - 1;
  if (!Number.isSafeInteger(page) || page > 49_999) return unavailableFeed(404);
  try {
    const result = await publicApi.published(page, FEED_PAGE_SIZE);
    const pages = Math.max(1, Math.ceil(result.total / FEED_PAGE_SIZE));
    if (pages > 50_000) return unavailableFeed();
    if (page >= pages) return unavailableFeed(404);
    const links: [string, number][] = [["self", page], ["first", 0], ["last", pages - 1]];
    if (page > 0) links.push(["previous", page - 1]);
    if (page + 1 < pages) links.push(["next", page + 1]);
    const items = result.items.map((article) => `<item><title>${escapeXml(article.headline)}</title><link>${escapeXml(articleUrl(article.slug))}</link><guid isPermaLink="true">${escapeXml(articleUrl(article.slug))}</guid><pubDate>${new Date(article.publishedAt).toUTCString()}</pubDate><description>${escapeXml(article.summary)}</description></item>`).join("");
    return xmlResponse(`<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Nsangusa</title><link>${escapeXml(absoluteUrl("/"))}</link><description>Independent reporting and essays.</description>${links.map(([rel, target]) => `<atom:link href="${escapeXml(feedUrl(target))}" rel="${rel}" type="application/rss+xml"/>`).join("")}${items}</channel></rss>`, "application/rss+xml");
  } catch {
    return unavailableFeed();
  }
}
