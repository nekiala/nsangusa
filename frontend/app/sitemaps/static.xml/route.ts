import { SITEMAP_NAMESPACES, sitemapUrls, xmlResponse } from "@/lib/public-feeds";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export function GET() {
  const paths = ["/", "/latest", "/topics", "/tags", "/newsletter"];
  return xmlResponse(`<urlset ${SITEMAP_NAMESPACES}>${paths.map((path) => sitemapUrls(path)).join("")}</urlset>`);
}
