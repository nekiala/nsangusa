import { publicApi } from "@/lib/public-api";
import { absoluteUrl, escapeXml, FEED_PAGE_SIZE, MAX_SITEMAP_SEGMENTS, unavailableFeed, xmlResponse } from "@/lib/public-feeds";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export async function GET() {
  try {
    const { total } = await publicApi.published(0, FEED_PAGE_SIZE);
    const segments = Math.ceil(total / FEED_PAGE_SIZE);
    if (segments > MAX_SITEMAP_SEGMENTS) return unavailableFeed();
    const paths = ["/sitemaps/static.xml", ...Array.from({ length: segments }, (_, page) => `/sitemaps/articles/${page}.xml`)];
    return xmlResponse(`<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${paths.map((path) => `<sitemap><loc>${escapeXml(absoluteUrl(path))}</loc></sitemap>`).join("")}</sitemapindex>`);
  } catch {
    return unavailableFeed();
  }
}
