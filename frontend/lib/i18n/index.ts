import { en } from "./en";
import { fr } from "./fr";

export const locales = ["fr", "en"] as const;
export type Locale = (typeof locales)[number];
export type MessageKey = keyof typeof en;
export type Messages = Record<MessageKey, string>;
export type Translate = ReturnType<typeof translator>;

export const LOCALE_HEADER = "x-locale";

/** French is served at the site root; English lives under /en. */
export const defaultLocale: Locale = "fr";
const catalogues: Record<Locale, Messages> = { en, fr };

/** Where a request carries no locale (component tests): the English catalogue on unprefixed paths. */
export const unlocalized = { locale: "en" as Locale, prefix: "" };

export const localePrefix = (locale: Locale) => (locale === defaultLocale ? "" : `/${locale}`);

/** Prefixes a site-internal path for the locale; external and relative URLs are returned unchanged. */
export function localizePath(prefix: string, href: string) {
  if (!prefix || !href.startsWith("/") || href.startsWith("//")) return href;
  // Already inside this locale, such as a return path captured from the address bar.
  if (href === prefix || href.startsWith(`${prefix}/`) || href.startsWith(`${prefix}?`)) return href;
  return href === "/" ? prefix : `${prefix}${href}`;
}

/** Splits a URL path into its locale and the locale-independent path the routes are written for. */
export function splitLocale(pathname: string): { locale: Locale; pathname: string; prefixed: boolean } {
  for (const locale of locales) {
    if (pathname === `/${locale}` || pathname.startsWith(`/${locale}/`)) {
      return { locale, pathname: pathname.slice(locale.length + 1) || "/", prefixed: true };
    }
  }
  return { locale: defaultLocale, pathname, prefixed: false };
}

export function translator(locale: Locale) {
  const messages = catalogues[locale];
  return (key: MessageKey, values?: Record<string, string | number>): string => {
    const message = messages[key];
    return values ? message.replace(/\{(\w+)\}/g, (match, name: string) => name in values ? String(values[name]) : match) : message;
  };
}

/** Picks the `.one` or `.other` variant of a message by the locale's plural rule. */
export function plural(locale: Locale, t: Translate, key: string, count: number) {
  const form = new Intl.PluralRules(locale).select(count) === "one" ? "one" : "other";
  return t(`${key}.${form}` as MessageKey, { count });
}

export function formatDate(date: string, locale: Locale) {
  return new Intl.DateTimeFormat(locale, { month: "long", day: "numeric", year: "numeric" }).format(new Date(date));
}

/** A short relative time for recent items ("3 h ago"), and the full date once it is a day old. */
export function formatWhen(date: string, locale: Locale, now = Date.now()) {
  const minutes = Math.round((now - new Date(date).getTime()) / 60_000);
  if (!Number.isFinite(minutes) || minutes < 0 || minutes >= 24 * 60) return formatDate(date, locale);
  if (minutes < 1) return translator(locale)("time.justNow");
  const relative = new Intl.RelativeTimeFormat(locale, { style: "short" });
  return minutes < 60 ? relative.format(-minutes, "minute") : relative.format(-Math.round(minutes / 60), "hour");
}

export function alternates(prefix: string, path: string) {
  return {
    canonical: localizePath(prefix, path),
    languages: Object.fromEntries([
      ...locales.map((locale) => [locale, localizePath(localePrefix(locale), path)]),
      ["x-default", path]
    ]) as Record<string, string>
  };
}
