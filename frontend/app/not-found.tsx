import Link from "@/components/locale";
import { requestLocale } from "@/lib/i18n/server";
export default async function NotFound() {
  const { t } = await requestLocale();
  return <section className="empty shell"><p className="eyebrow">404</p><h1>{t("notFound.title")}</h1><p>{t("notFound.body")} <Link href="/">{t("notFound.home")}</Link></p></section>;
}
