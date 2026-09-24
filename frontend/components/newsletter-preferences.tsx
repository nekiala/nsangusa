"use client";

import Link from "next/link";
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
  const valid = !!id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
    && !!token && token.length <= 128;

  useEffect(() => {
    let active = true;
    if (id || token) window.history.replaceState(null, "", "/newsletter/preferences");
    if (!valid || !id || !token) return;
    newsletterApi.preferences(id, token).then((value) => {
      if (active) { setPreference(value); setFrequency(value.frequency); }
    }).catch((failure: unknown) => {
      if (active) setError(failure instanceof ApiError && [400, 403, 404, 410].includes(failure.status)
        ? "This private link is invalid or expired. Request a new preference link below."
        : "Preferences could not be loaded. Please request a new link or try again.");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id, token, valid]);

  async function requestLink(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setNotice(""); setError("");
    try { const result = await newsletterApi.requestLink(email); setNotice(result.message); setEmail(""); }
    catch { setError("The request could not be recorded. Please try again."); }
    finally { setBusy(false); }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!valid || !id || !token || !preference) return;
    setBusy(true); setNotice(""); setError("");
    try {
      await newsletterApi.updatePreferences(id, token, frequency);
      setPreference({ ...preference, frequency });
      setNotice("Newsletter frequency updated.");
    } catch (failure) {
      setError(failure instanceof ApiError && [400, 403, 404, 409, 410].includes(failure.status)
        ? "This preference link can no longer be used. Request a new link."
        : "The change could not be confirmed. Please try again.");
    } finally { setBusy(false); }
  }

  return <section className="shell empty" aria-labelledby="newsletter-preferences-title">
    <p className="eyebrow">Newsletter</p><h1 id="newsletter-preferences-title">Newsletter preferences</h1>
    <p>You do not need an account. Request a private email link to manage a confirmed subscription. Opening a link never changes your subscription.</p>
    {newsletterApi.mode === "fake" && <p role="note">Demonstration mode — no emails are sent.</p>}
    {(id || token) && !valid && <p role="alert">This preference link is incomplete or invalid.</p>}
    {loading && valid && <p role="status">Loading preferences…</p>}
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {preference && <form className="admin-form" onSubmit={save}>
      <p>Subscription: {preference.status}. This authorization expires {new Date(preference.expiresAt).toLocaleString()}.</p>
      <label htmlFor="newsletter-frequency">Email frequency</label>
      <select id="newsletter-frequency" value={frequency} onChange={(event) => setFrequency(event.target.value as Frequency)} disabled={busy}>
        {frequencies.map((value) => <option key={value} value={value}>{value === "all" ? "Immediate, daily and weekly" : value}</option>)}
      </select>
      <button disabled={busy}>Save newsletter preferences</button>
      <p>To stop all messages, use the explicit unsubscribe control in your newsletter email. Preference links cannot reinstate an unsubscribed or suppressed address.</p>
    </form>}
    <form className="admin-form" onSubmit={requestLink}>
      <h2>Request a preference link</h2>
      <label htmlFor="newsletter-preference-email">Email address</label>
      <input id="newsletter-preference-email" type="email" autoComplete="email" maxLength={320} required value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} />
      <button disabled={busy}>Email a preference link</button>
      <p>Links expire after 30 minutes. For your protection, requests are limited to one email per address every 10 minutes.</p>
    </form>
    <p><Link href="/newsletter">Newsletter signup</Link> · <Link href="/">Return to the publication</Link></p>
  </section>;
}
