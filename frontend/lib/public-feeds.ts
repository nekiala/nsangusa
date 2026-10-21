import { LOCALE_HEADER, localePrefix, localizePath, locales, type Locale } from "@/lib/i18n";
import { publicBaseUrl } from "@/lib/public-base-url";

export const FEED_PAGE_SIZE = 100;
export const MAX_SITEMAP_SEGMENTS = 49_999;
export const feedHeaders = { "Cache-Control": "private, no-store, max-age=0" };
export const absoluteUrl = (path: string) => new URL(path, publicBaseUrl()).href;
export const articleUrl = (slug: string, locale?: Locale) => absoluteUrl(localizePath(locale ? localePrefix(locale) : "", `/articles/${encodeURIComponent(slug)}`));
/** The language of a feed request: the proxy marks /en feeds, and the root feed is the default language. */
export function feedLocale(request?: Request): Locale | undefined {
  if (!request) return undefined;
  return request.headers.get(LOCALE_HEADER) === "en" ? "en" : "fr";
}
/** A sitemap entry for every language of a page, each naming the others as alternates. */
export function sitemapUrls(path: string, lastModified?: string) {
  const alternates = locales.map((locale) => `<xhtml:link rel="alternate" hreflang="${locale}" href="${escapeXml(absoluteUrl(localizePath(localePrefix(locale), path)))}"/>`).join("");
  return locales.map((locale) => `<url><loc>${escapeXml(absoluteUrl(localizePath(localePrefix(locale), path)))}</loc>${lastModified ? `<lastmod>${escapeXml(lastModified)}</lastmod>` : ""}${alternates}</url>`).join("");
}
export const SITEMAP_NAMESPACES = 'xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml"';
export function escapeXml(value: string) {
  // eslint-disable-next-line no-control-regex -- XML 1.0 allows only these character ranges.
  return value.replace(/[^\u0009\u000A\u000D\u0020-\uD7FF\uE000-\uFFFD\u{10000}-\u{10FFFF}]/gu, "")
    .replace(/[<>&'"]/g, (character) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", "'": "&apos;", "\"": "&quot;" })[character]!);
}
export function xmlResponse(body: string, type = "application/xml") {
  return new Response(`<?xml version="1.0" encoding="UTF-8"?>${body}`, { headers: { ...feedHeaders, "Content-Type": `${type}; charset=utf-8` } });
}
export function unavailableFeed(status = 503) {
  return new Response(status === 404 ? "Feed page not found." : "Publication feed is temporarily unavailable.", { status, headers: feedHeaders });
}
