import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  output: "standalone",
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
      source: "/rss.xml",
      headers: [{ key: "Cache-Control", value: "public, max-age=300, s-maxage=3600, stale-while-revalidate=86400" }]
    }, {
      source: "/admin/:path*",
      headers: [{ key: "Cache-Control", value: "private, no-store" }]
    }, {
      source: "/:path(sign-in|register|password-reset|profile)",
      headers: [{ key: "Cache-Control", value: "private, no-store" }]
    }];
  }
};

export default nextConfig;
