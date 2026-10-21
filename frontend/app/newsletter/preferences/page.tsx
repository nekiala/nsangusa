import type { Metadata } from "next";
import { requestLocale } from "@/lib/i18n/server";
import { NewsletterPreferences } from "@/components/newsletter-preferences";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> {
  return { title: (await requestLocale()).t("prefs.title"), robots: { index: false, follow: false }, referrer: "no-referrer" };
}

export default async function NewsletterPreferencesPage({ searchParams }: {
  searchParams: Promise<{ id?: string | string[]; token?: string | string[] }>;
}) {
  const query = await searchParams;
  return <NewsletterPreferences id={typeof query.id === "string" ? query.id : undefined}
    token={typeof query.token === "string" ? query.token : undefined} />;
}
