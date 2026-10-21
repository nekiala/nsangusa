"use client";

import Link, { useLocale } from "@/components/locale";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";

export function NewsletterAction({ kind, id, token }: { kind: "confirm" | "unsubscribe"; id?: string; token?: string }) {
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  const { t, path } = useLocale();
  const valid = !!id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id) && !!token && token.length <= 1024;
  async function submit() {
    if (!valid || !id || !token) return;
    setBusy(true); setError("");
    try {
      if (kind === "confirm") await api.newsletter.confirm(id, token);
      else await api.newsletter.unsubscribe(id, token);
      setDone(true);
      window.history.replaceState(null, "", path(`/newsletter/${kind}`));
    } catch (failure) {
      setError(failure instanceof ApiError && [400, 403, 404, 409, 410].includes(failure.status)
        ? t("nlaction.invalidLink")
        : t("nlaction.failed"));
    } finally { setBusy(false); }
  }
  return <section className="empty shell" aria-labelledby="newsletter-action-title">
    <p className="eyebrow">{t("nlaction.eyebrow")}</p><h1 id="newsletter-action-title">{t(kind === "confirm" ? "nlaction.confirmTitle" : "nlaction.unsubscribeTitle")}</h1>
    <p>{t(kind === "confirm" ? "nlaction.confirmIntro" : "nlaction.unsubscribeIntro")}</p>
    {!valid && !done && <p role="alert">{t("nlaction.incomplete")}</p>}
    {error && <p role="alert">{error}</p>}
    {done ? <p role="status">{t(kind === "confirm" ? "nlaction.confirmed" : "nlaction.unsubscribed")}</p> : <button disabled={!valid || busy} onClick={submit}>{busy ? t("nlaction.working") : t(kind === "confirm" ? "nlaction.confirm" : "nlaction.unsubscribe")}</button>}
    <p><Link href="/newsletter">{t("nlaction.signup")}</Link> · <Link href="/">{t("nlaction.return")}</Link></p>
  </section>;
}
