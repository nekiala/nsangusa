import type { Metadata } from "next";
import type { ReactNode } from "react";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { LocaleProvider } from "@/components/locale";
import { requestLocale } from "@/lib/i18n/server";
import { publicBaseUrl } from "@/lib/public-base-url";
// Self-hosted, OFL-1.1 licensed variable fonts: no third-party font requests at runtime.
import "@fontsource-variable/inter";
import "@fontsource-variable/source-serif-4";
import "./globals.css";
import "./workspaces.css";
import "./front.css";

// A fresh CSP nonce must be present on every HTML render, including public pages.
export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const { locale, t } = await requestLocale();
  return {
    metadataBase: new URL(publicBaseUrl()),
    title: { default: "Nsangusa", template: "%s | Nsangusa" },
    description: t("site.tagline"),
    openGraph: { type: "website", siteName: "Nsangusa", title: "Nsangusa", description: t("site.tagline"), locale },
    robots: { index: true, follow: true }
  };
}

export default async function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  const { locale, t } = await requestLocale();
  return <html lang={locale}><body><LocaleProvider locale={locale}><a className="skip-link" href="#main-content">{t("site.skip")}</a><SiteHeader /><main id="main-content" tabIndex={-1}>{children}</main><SiteFooter /></LocaleProvider></body></html>;
}
