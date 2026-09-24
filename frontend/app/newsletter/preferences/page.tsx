import type { Metadata } from "next";
import { NewsletterPreferences } from "@/components/newsletter-preferences";

export const dynamic = "force-dynamic";
export const metadata: Metadata = {
  title: "Newsletter preferences", robots: { index: false, follow: false }, referrer: "no-referrer",
};

export default async function NewsletterPreferencesPage({ searchParams }: {
  searchParams: Promise<{ id?: string | string[]; token?: string | string[] }>;
}) {
  const query = await searchParams;
  return <NewsletterPreferences id={typeof query.id === "string" ? query.id : undefined}
    token={typeof query.token === "string" ? query.token : undefined} />;
}
