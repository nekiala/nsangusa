"use client";

import Link, { useLocale } from "@/components/locale";
import type { MessageKey } from "@/lib/i18n";
import { useEffect, useState, type FormEvent } from "react";
import { ApiError } from "@/lib/api";
import { newsletterApi, type Frequency, type NewsletterPreference } from "@/lib/newsletter-api";

const frequencies: Frequency[] = ["immediate", "daily", "weekly", "all"];

export function NewsletterPreferences({ id, token }: { id?: string; token?: string }) {
  const [email, setEmail] = useState("");
  const [preference, setPreference] = useState<NewsletterPreference | null>(null);
  const [frequency, setFrequency] = useState<Frequency>("weekly");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(!!id || !!token);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const { locale, t, path } = useLocale();
  const valid = !!id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
    && !!token && token.length <= 128;

  useEffect(() => {
    let active = true;
    if (id || token) window.history.replaceState(null, "", path("/newsletter/preferences"));
    if (!valid || !id || !token) return;
    newsletterApi.preferences(id, token).then((value) => {
      if (active) { setPreference(value); setFrequency(value.frequency); }
    }).catch((failure: unknown) => {
      if (active) setError(failure instanceof ApiError && [400, 403, 404, 410].includes(failure.status)
        ? t("prefs.linkInvalid")
        : t("prefs.loadFailed"));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id, token, valid, t, path]);

  async function requestLink(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setNotice(""); setError("");
    try { const result = await newsletterApi.requestLink(email); setNotice(result.message); setEmail(""); }
    catch { setError(t("prefs.requestFailed")); }
    finally { setBusy(false); }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!valid || !id || !token || !preference) return;
    setBusy(true); setNotice(""); setError("");
    try {
      await newsletterApi.updatePreferences(id, token, frequency);
      setPreference({ ...preference, frequency });
      setNotice(t("prefs.updated"));
    } catch (failure) {
      setError(failure instanceof ApiError && [400, 403, 404, 409, 410].includes(failure.status)
        ? t("prefs.linkUsed")
        : t("prefs.unconfirmed"));
    } finally { setBusy(false); }
  }

  return <section className="shell empty" aria-labelledby="newsletter-preferences-title">
    <p className="eyebrow">{t("prefs.eyebrow")}</p><h1 id="newsletter-preferences-title">{t("prefs.title")}</h1>
    <p>{t("prefs.intro")}</p>
    {newsletterApi.mode === "fake" && <p role="note">{t("prefs.demo")}</p>}
    {(id || token) && !valid && <p role="alert">{t("prefs.incomplete")}</p>}
    {loading && valid && <p role="status">{t("prefs.loading")}</p>}
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {preference && <form className="admin-form" onSubmit={save}>
      <p>{t("prefs.status", { status: preference.status, date: new Date(preference.expiresAt).toLocaleString(locale) })}</p>
      <label htmlFor="newsletter-frequency">{t("prefs.frequency")}</label>
      <select id="newsletter-frequency" value={frequency} onChange={(event) => setFrequency(event.target.value as Frequency)} disabled={busy}>
        {frequencies.map((value) => <option key={value} value={value}>{t(`prefs.frequency.${value}` as MessageKey)}</option>)}
      </select>
      <button disabled={busy}>{t("prefs.save")}</button>
      <p>{t("prefs.stopAll")}</p>
    </form>}
    <form className="admin-form" onSubmit={requestLink}>
      <h2>{t("prefs.requestTitle")}</h2>
      <label htmlFor="newsletter-preference-email">{t("form.email")}</label>
      <input id="newsletter-preference-email" type="email" autoComplete="email" maxLength={320} required value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} />
      <button disabled={busy}>{t("prefs.requestSubmit")}</button>
      <p>{t("prefs.limits")}</p>
    </form>
    <p><Link href="/newsletter">{t("nlaction.signup")}</Link> · <Link href="/">{t("nlaction.return")}</Link></p>
  </section>;
}
