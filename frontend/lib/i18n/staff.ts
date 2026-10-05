import { staffFrench } from "./staff-fr";
import type { Locale } from ".";

let staffLocale: Locale = "en";

/** Called by the locale provider in the browser, where a page has exactly one language. */
export function setStaffLocale(locale: Locale) {
  staffLocale = locale;
}

/**
 * Staff-workspace text for the current language. The English text is the key, so an untranslated
 * string is shown in English rather than missing. Server rendering always yields English: one
 * server process renders many languages, and the workspace only renders after sign-in in the
 * browser.
 */
export function tx(source: string): string {
  if (typeof window === "undefined" || staffLocale !== "fr") return source;
  return staffFrench[source] ?? source;
}
