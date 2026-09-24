import type { Metadata } from "next";
import type { ReactNode } from "react";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { publicBaseUrl } from "@/lib/public-base-url";
import "./globals.css";
import "./workspaces.css";

// A fresh CSP nonce must be present on every HTML render, including public pages.
export const dynamic = "force-dynamic";

export function generateMetadata(): Metadata {
  return {
    metadataBase: new URL(publicBaseUrl()),
    title: { default: "Nsangusa", template: "%s | Nsangusa" },
    description: "Independent reporting and essays for a more attentive public life.",
    openGraph: { type: "website", siteName: "Nsangusa", title: "Nsangusa", description: "Independent reporting and essays for a more attentive public life." },
    robots: { index: true, follow: true }
  };
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <html lang="en"><body><a className="skip-link" href="#main-content">Skip to content</a><SiteHeader /><main id="main-content" tabIndex={-1}>{children}</main><SiteFooter /></body></html>;
}
