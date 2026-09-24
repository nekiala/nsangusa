import type { MetadataRoute } from "next";
import { publicBaseUrl } from "@/lib/public-base-url";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export default function robots(): MetadataRoute.Robots { return { rules: [{ userAgent: "*", allow: "/", disallow: ["/admin", "/profile", "/sign-in", "/register", "/password-reset", "/newsletter/confirm", "/newsletter/unsubscribe"] }], sitemap: `${publicBaseUrl()}/sitemap.xml` }; }
