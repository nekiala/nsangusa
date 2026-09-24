import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  output: "standalone",
  distDir: process.env.NEXT_DIST_DIR || ".next",
  turbopack: { root: __dirname },
  allowedDevOrigins: ["127.0.0.1"],
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
      source: "/newsletter/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }, { key: "Referrer-Policy", value: "no-referrer" }]
    }, {
      source: "/:path(robots.txt|rss.xml|sitemap.xml)",
      headers: [{ key: "Cache-Control", value: "private, no-store, max-age=0" }]
    }, {
      source: "/sitemaps/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store, max-age=0" }]
    }, {
      source: "/articles/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store, max-age=0" }]
    }, {
      source: "/admin/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }]
    }, {
      source: "/:path(sign-in|register|password-reset|profile|verify-email)",
      headers: [{ key: "Cache-Control", value: "private, no-store" }, { key: "Referrer-Policy", value: "no-referrer" }]
    }, {
      source: "/password-reset/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }, { key: "Referrer-Policy", value: "no-referrer" }]
    }];
  }
};

export default nextConfig;
