import { randomBytes } from "node:crypto";
import { NextResponse, type NextRequest } from "next/server";
import { contentSecurityPolicy } from "./lib/content-security-policy";

export function proxy(request: NextRequest) {
  const nonce = randomBytes(18).toString("base64");
  const policy = contentSecurityPolicy(nonce, {
    apiUrl: process.env.NEXT_PUBLIC_API_URL,
    development: process.env.NODE_ENV === "development",
    origin: request.nextUrl.origin
  });
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
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
