import { publicApi } from "@/lib/public-api";
import { articleUrl, escapeXml, FEED_PAGE_SIZE, MAX_SITEMAP_SEGMENTS, unavailableFeed, xmlResponse } from "@/lib/public-feeds";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export async function GET(_request: Request, { params }: { params: Promise<{ segment: string }> }) {
  const { segment } = await params;
  if (!/^(0|[1-9]\d*)\.xml$/.test(segment)) return unavailableFeed(404);
  const page = Number(segment.slice(0, -4));
  if (!Number.isSafeInteger(page) || page >= MAX_SITEMAP_SEGMENTS) return unavailableFeed(404);
  try {
    const result = await publicApi.published(page, FEED_PAGE_SIZE);
    if (!result.items.length) return unavailableFeed(404);
    return xmlResponse(`<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${result.items.map((article) => `<url><loc>${escapeXml(articleUrl(article.slug))}</loc><lastmod>${escapeXml(article.updatedAt)}</lastmod></url>`).join("")}</urlset>`);
  } catch {
    return unavailableFeed();
  }
}
