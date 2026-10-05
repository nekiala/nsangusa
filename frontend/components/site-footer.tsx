import { EmailForm } from "@/components/forms";
import Link from "@/components/locale";
import { requestLocale } from "@/lib/i18n/server";

export async function SiteFooter() {
  const { t } = await requestLocale();
  return <footer className="site-footer"><div className="shell footer-grid">
    <div><p className="wordmark">NSANGUSA</p><p>{t("site.tagline")}</p></div>
    <EmailForm compact />
    <nav aria-label={t("footer.navigation")}><Link href="/editorial">{t("footer.editorial")}</Link><Link href="/corrections">{t("footer.corrections")}</Link><Link href="/privacy">{t("footer.privacy")}</Link><Link href="/terms">{t("footer.terms")}</Link><Link href="/admin">{t("footer.staff")}</Link></nav>
  </div><p className="shell copyright">{t("footer.copyright")}</p></footer>;
}
