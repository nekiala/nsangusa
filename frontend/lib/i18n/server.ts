import { headers } from "next/headers";
import { defaultLocale, LOCALE_HEADER, localePrefix, locales, translator, unlocalized, type Locale } from ".";

/** The locale the proxy resolved for this request, with its path prefix and translator. */
export async function requestLocale() {
  let requested: string | null;
  try {
    requested = (await headers()).get(LOCALE_HEADER);
  } catch {
    // Outside a request scope there is no proxy-resolved locale.
    return { ...unlocalized, t: translator(unlocalized.locale) };
  }
  const locale = locales.includes(requested as Locale) ? requested as Locale : defaultLocale;
  return { locale, prefix: localePrefix(locale), t: translator(locale) };
}
