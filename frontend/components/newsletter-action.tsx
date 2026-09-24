"use client";

import Link from "next/link";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";

export function NewsletterAction({ kind, id, token }: { kind: "confirm" | "unsubscribe"; id?: string; token?: string }) {
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  const valid = !!id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id) && !!token && token.length <= 1024;
  async function submit() {
    if (!valid || !id || !token) return;
    setBusy(true); setError("");
    try {
      if (kind === "confirm") await api.newsletter.confirm(id, token);
      else await api.newsletter.unsubscribe(id, token);
      setDone(true);
      window.history.replaceState(null, "", `/newsletter/${kind}`);
    } catch (failure) {
      setError(failure instanceof ApiError && [400, 403, 404, 409, 410].includes(failure.status)
        ? "This link is invalid or has expired. Request a new subscription email or use the latest email link."
        : "The newsletter service could not complete your request. Please try again.");
    } finally { setBusy(false); }
  }
  return <section className="empty shell" aria-labelledby="newsletter-action-title">
    <p className="eyebrow">Newsletter preferences</p><h1 id="newsletter-action-title">{kind === "confirm" ? "Confirm your subscription" : "Unsubscribe from the newsletter"}</h1>
    <p>{kind === "confirm" ? "Confirm below to start receiving your chosen newsletter." : "Nothing changes until you choose to unsubscribe below."}</p>
    {!valid && !done && <p role="alert">This link is incomplete or invalid. Please use the full link from your latest newsletter email.</p>}
    {error && <p role="alert">{error}</p>}
    {done ? <p role="status">{kind === "confirm" ? "Your subscription is confirmed." : "You have been unsubscribed."}</p> : <button disabled={!valid || busy} onClick={submit}>{busy ? "Working…" : kind === "confirm" ? "Confirm subscription" : "Unsubscribe"}</button>}
    <p><Link href="/newsletter">Newsletter signup</Link> · <Link href="/">Return to the publication</Link></p>
  </section>;
}
