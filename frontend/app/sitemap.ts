import type { MetadataRoute } from "next";
import { contentApi, siteUrl, topics } from "@/lib/content";

export const dynamic = "force-dynamic";
export default async function sitemap(): Promise<MetadataRoute.Sitemap> { const articles = await contentApi.latest(); return [{ url: siteUrl, lastModified: new Date() }, { url: `${siteUrl}/latest`, lastModified: new Date() }, { url: `${siteUrl}/newsletter`, lastModified: new Date() }, ...topics.map((topic) => ({ url: `${siteUrl}/topics/${topic.toLowerCase()}`, lastModified: new Date() })), ...articles.map((article) => ({ url: `${siteUrl}/articles/${article.slug}`, lastModified: article.publishedAt }))]; }
