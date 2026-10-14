"use client";

import NextLink from "next/link";
import { createContext, useContext, useMemo, type ComponentProps, type ReactNode } from "react";
import { localePrefix, localizePath, translator, unlocalized, type Locale } from "@/lib/i18n";
import { setStaffLocale } from "@/lib/i18n/staff";

const LocaleContext = createContext(unlocalized);

export function LocaleProvider({ locale, children }: { locale: Locale; children: ReactNode }) {
  setStaffLocale(locale);
  const value = useMemo(() => ({ locale, prefix: localePrefix(locale) }), [locale]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale() {
  const { locale, prefix } = useContext(LocaleContext);
  return useMemo(() => ({ locale, prefix, t: translator(locale), path: (href: string) => localizePath(prefix, href) }), [locale, prefix]);
}

/** next/link that keeps site-internal navigation inside the reader's language. */
export default function Link({ href, ...props }: ComponentProps<typeof NextLink>) {
  const { prefix } = useContext(LocaleContext);
  return <NextLink href={typeof href === "string" ? localizePath(prefix, href) : href} {...props} />;
}
