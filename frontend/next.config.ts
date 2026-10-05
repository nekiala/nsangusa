import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  output: "standalone",
  distDir: process.env.NEXT_DIST_DIR || ".next",
  turbopack: { root: __dirname },
  allowedDevOrigins: ["127.0.0.1"],
  // English pages live under /en and are served by the same routes; the proxy records the locale.
  async rewrites() {
    return { beforeFiles: [{ source: "/en", destination: "/" }, { source: "/en/:path*", destination: "/:path*" }], afterFiles: [], fallback: [] };
  },
  // French, the default language, has one address: the unprefixed one.
  async redirects() {
    return [{ source: "/fr", destination: "/", permanent: true }, { source: "/fr/:path*", destination: "/:path*", permanent: true }];
  },
  async headers() {
    return [{
      source: "/(.*)",
      headers: [
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" }
      ]
    }, {
      source: "/:locale(en)?/newsletter/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }, { key: "Referrer-Policy", value: "no-referrer" }]
    }, {
      source: "/:locale(en)?/:path(robots.txt|rss.xml|sitemap.xml)",
      headers: [{ key: "Cache-Control", value: "private, no-store, max-age=0" }]
    }, {
      source: "/sitemaps/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store, max-age=0" }]
    }, {
      source: "/:locale(en)?/articles/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store, max-age=0" }]
    }, {
      source: "/:locale(en)?/admin/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }]
    }, {
      source: "/:locale(en)?/:path(sign-in|register|password-reset|profile|verify-email)",
      headers: [{ key: "Cache-Control", value: "private, no-store" }, { key: "Referrer-Policy", value: "no-referrer" }]
    }, {
      source: "/:locale(en)?/password-reset/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }, { key: "Referrer-Policy", value: "no-referrer" }]
    }];
  }
};

export default nextConfig;
