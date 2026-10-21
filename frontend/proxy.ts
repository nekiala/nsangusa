import { randomBytes } from "node:crypto";
import { NextResponse, type NextRequest } from "next/server";
import { contentSecurityPolicy } from "./lib/content-security-policy";
import { LOCALE_HEADER, splitLocale } from "./lib/i18n";

export function proxy(request: NextRequest) {
  // The locale comes from the address the reader asked for. Mapping /en pages onto the shared
  // routes is a rewrite in next.config.ts: a rewrite issued here carries an absolute URL, which
  // Next treats as another site, and loses the locale, whenever its host differs from the request's.
  const { locale } = splitLocale(request.nextUrl.pathname);
  const nonce = randomBytes(18).toString("base64");
  const policy = contentSecurityPolicy(nonce, {
    apiUrl: process.env.NEXT_PUBLIC_API_URL,
    development: process.env.NODE_ENV === "development",
    origin: request.nextUrl.origin
  });
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set(LOCALE_HEADER, locale);
  // Next reads the request CSP to nonce its own bootstrap and streamed scripts.
  headers.set("Content-Security-Policy", policy);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", policy);
  response.headers.set("Cache-Control", "private, no-store, max-age=0");
  return response;
}

export const config = {
  matcher: ["/((?!api/|_next/static|_next/image|favicon.ico|robots.txt|rss.xml|sitemap.xml|sitemaps/).*)"]
};
