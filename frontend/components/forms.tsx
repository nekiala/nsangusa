"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { ApiError, api } from "@/lib/api";

function messageFor(error: unknown) {
  return error instanceof ApiError ? error.problem.detail : "We could not complete that request. Please try again.";
}

export function EmailForm({ compact = false }: { compact?: boolean }) {
  const [notice, setNotice] = useState({ message: "", error: false });
  const [sending, setSending] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSending(true);
    try {
      await api.newsletter.subscribe(String(new FormData(event.currentTarget).get("email")));
      setNotice({ message: "Check your inbox to confirm your subscription.", error: false });
    } catch (error) { setNotice({ message: messageFor(error), error: true }); } finally { setSending(false); }
  }
  return <form className={compact ? "email-form compact" : "email-form"} onSubmit={submit}>
    <label htmlFor={compact ? "footer-email" : "email"}>Email address</label>
    <div className="form-row">
      <input id={compact ? "footer-email" : "email"} name="email" type="email" autoComplete="email" required placeholder="you@example.com" />
      <button type="submit" disabled={sending}>{sending ? "Subscribing" : "Subscribe"}</button>
    </div>
    <p className="form-note" role={notice.error ? "alert" : "status"}>{notice.message || "A considered weekly briefing. Unsubscribe whenever you wish."}</p>
  </form>;
}

export function AuthForm({ kind, nextPath }: { kind: "sign-in" | "register" | "reset"; nextPath?: string }) {
  const router = useRouter();
  const [notice, setNotice] = useState({ message: "", error: false });
  const [busy, setBusy] = useState(false);
  const title = kind === "sign-in" ? "Welcome back" : kind === "register" ? "Create your account" : "Reset your password";
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    const values = new FormData(event.currentTarget);
    const email = String(values.get("email"));
    try {
      if (kind === "sign-in") {
        await api.auth.login(email, String(values.get("password")));
        setNotice({ message: "You are signed in.", error: false });
        const destination = nextPath?.startsWith("/") && !nextPath.startsWith("//") ? nextPath : "/profile";
        router.replace(destination);
      } else if (kind === "register") {
        await api.auth.register({ email, password: String(values.get("password")), displayName: String(values.get("displayName")) });
        setNotice({ message: "Check your inbox to verify your account.", error: false });
      } else {
        await api.auth.requestPasswordReset(email);
        setNotice({ message: "If this address is registered, reset instructions are on their way.", error: false });
      }
    } catch (error) { setNotice({ message: messageFor(error), error: true }); } finally { setBusy(false); }
  }
  return <section className="auth-panel" aria-labelledby="auth-title">
    <p className="eyebrow">Member access</p><h1 id="auth-title">{title}</h1>
    <form onSubmit={submit}>
      {kind === "register" && <><label htmlFor="name">Name</label><input id="name" name="displayName" required autoComplete="name" /></>}
      <label htmlFor="auth-email">Email address</label><input id="auth-email" name="email" type="email" required autoComplete="email" />
      {kind !== "reset" && <><label htmlFor="password">Password</label><input id="password" name="password" type="password" required minLength={12} autoComplete={kind === "sign-in" ? "current-password" : "new-password"} /></>}
      <button type="submit" disabled={busy}>{busy ? "Working" : kind === "sign-in" ? "Sign in" : kind === "register" ? "Register" : "Send reset link"}</button>
      <p role={notice.error ? "alert" : "status"} className="form-note">{notice.message}</p>
    </form>
  </section>;
}
