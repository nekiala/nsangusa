import { absoluteUrl, escapeXml, xmlResponse } from "@/lib/public-feeds";

export const dynamic = "force-dynamic";
export const revalidate = 0;
export function GET() {
  const paths = ["/", "/latest", "/topics", "/tags", "/newsletter"];
  return xmlResponse(`<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${paths.map((path) => `<url><loc>${escapeXml(absoluteUrl(path))}</loc></url>`).join("")}</urlset>`);
}
