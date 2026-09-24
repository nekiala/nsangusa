"use client";

import { useId, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ApiError, api } from "@/lib/api";
import { useSession } from "./use-session";

function messageFor(error: unknown) {
  return error instanceof ApiError ? error.problem.detail : "We could not complete that request. Please try again.";
}

export function EmailForm({ compact = false }: { compact?: boolean }) {
  const id = useId();
  const [notice, setNotice] = useState({ message: "", error: false });
  const [sending, setSending] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSending(true);
    try {
      const data = new FormData(event.currentTarget);
      const frequency = data.get("frequency");
      await api.newsletter.subscribe(String(data.get("email")), frequency === "immediate" || frequency === "daily" ? frequency : "weekly");
      setNotice({ message: "Check your inbox to confirm your subscription.", error: false });
    } catch (error) { setNotice({ message: messageFor(error), error: true }); } finally { setSending(false); }
  }
  return <form className={compact ? "email-form compact" : "email-form"} onSubmit={submit}>
    <label htmlFor={`${id}-email`}>Email address</label>
    <div className="form-row">
      <input id={`${id}-email`} name="email" type="email" autoComplete="email" required placeholder="you@example.com" />
      <button type="submit" disabled={sending}>{sending ? "Subscribing" : "Subscribe"}</button>
    </div>
    <label htmlFor={`${id}-frequency`}>Delivery frequency</label><select id={`${id}-frequency`} name="frequency" defaultValue="weekly"><option value="immediate">Every published article</option><option value="daily">Daily digest</option><option value="weekly">Weekly digest</option></select>
    <p className="form-note" role={notice.error ? "alert" : "status"}>{notice.message || "Choose when to hear from us. Confirm by email; unsubscribe whenever you wish."}</p>
  </form>;
}

export function AuthForm({ kind, nextPath }: { kind: "sign-in" | "register" | "reset"; nextPath?: string }) {
  const router = useRouter();
  const session = useSession();
  const [notice, setNotice] = useState({ message: "", error: false });
  const [busy, setBusy] = useState(false);
  const title = kind === "sign-in" ? "Welcome back" : kind === "register" ? "Create your account" : "Reset your password";
  const destination = nextPath?.startsWith("/") && !nextPath.startsWith("//")
    && !/[\\\s]/.test(nextPath) ? nextPath : "/profile";
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    const values = new FormData(event.currentTarget);
    const email = String(values.get("email"));
    try {
      if (kind === "sign-in") {
        await api.auth.login(email, String(values.get("password")));
        setNotice({ message: "You are signed in.", error: false });
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
  if (kind === "sign-in" && session.status === "checking") return <section className="auth-panel"><h1>Welcome back</h1><p role="status">Checking sign-in...</p></section>;
  if (kind === "sign-in" && session.status === "authenticated") return <section className="auth-panel" aria-labelledby="signed-in-title">
    <h1 id="signed-in-title">You are already signed in</h1><p>Signed in as {session.user.displayName} ({session.user.email}).</p>
    <Link href={destination}>Continue to {destination === "/profile" ? "your account" : "your requested page"}</Link>
    <p>To use another account, sign out first.</p>
  </section>;
  if (kind === "sign-in" && session.status === "error") return <section className="auth-panel"><h1>Sign-in service unavailable</h1><p role="alert">We could not check your session. Please try again.</p><button onClick={session.refresh}>Retry sign-in check</button></section>;
  return <section className="auth-panel" aria-labelledby="auth-title">
    <p className="eyebrow">Member access</p><h1 id="auth-title">{title}</h1>
    <form onSubmit={submit}>
      {kind === "register" && <><label htmlFor="name">Name</label><input id="name" name="displayName" required maxLength={100} autoComplete="name" /></>}
      <label htmlFor="auth-email">Email address</label><input id="auth-email" name="email" type="email" required maxLength={320} autoComplete="email" />
      {kind !== "reset" && <><label htmlFor="password">Password</label><input id="password" name="password" type="password" required minLength={12} maxLength={128} autoComplete={kind === "sign-in" ? "current-password" : "new-password"} /></>}
      <button type="submit" disabled={busy}>{busy ? "Working" : kind === "sign-in" ? "Sign in" : kind === "register" ? "Register" : "Send reset link"}</button>
      <p role={notice.error ? "alert" : "status"} className="form-note">{notice.message}</p>
    </form>
    <nav className="workflow-actions" aria-label="Account access">
      {kind !== "sign-in" && <Link href="/sign-in">Sign in</Link>}
      {kind !== "register" && <Link href="/register">Create an account</Link>}
      {kind !== "reset" && <Link href="/password-reset">Forgot your password?</Link>}
    </nav>
  </section>;
}
