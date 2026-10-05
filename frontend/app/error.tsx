"use client";
import { useLocale } from "@/components/locale";
export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const { t } = useLocale();
  return <section className="empty shell"><p className="eyebrow">{t("error.eyebrow")}</p><h1>{t("error.title")}</h1><p>{t("error.body")}</p><button onClick={reset}>{t("error.retry")}</button></section>;
}
