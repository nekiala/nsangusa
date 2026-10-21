import type { Metadata } from "next";
import { EmailForm } from "@/components/forms";
import { alternates } from "@/lib/i18n";
import { requestLocale } from "@/lib/i18n/server";
export async function generateMetadata(): Promise<Metadata> {
  const { prefix, t } = await requestLocale();
  return { title: t("newsletter.pageTitle"), alternates: alternates(prefix, "/newsletter") };
}
export default async function NewsletterPage() {
  const { t } = await requestLocale();
  return <section className="section shell"><p className="eyebrow">{t("newsletter.eyebrow")}</p><h1>{t("newsletter.title")}</h1><p className="intro">{t("newsletter.intro")}</p><EmailForm /></section>;
}
