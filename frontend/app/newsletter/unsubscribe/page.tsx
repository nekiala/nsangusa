import type { Metadata } from "next";
import { requestLocale } from "@/lib/i18n/server";
import { NewsletterAction } from "@/components/newsletter-action";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> {
  return { title: (await requestLocale()).t("meta.unsubscribeNewsletter"), robots: { index: false, follow: false }, referrer: "no-referrer" };
}

export default async function UnsubscribeNewsletterPage({ searchParams }: { searchParams: Promise<{ id?: string | string[]; token?: string | string[] }> }) {
  const query = await searchParams;
  return <NewsletterAction kind="unsubscribe" id={typeof query.id === "string" ? query.id : undefined} token={typeof query.token === "string" ? query.token : undefined} />;
}
