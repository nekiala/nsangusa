"use client";

import { useId, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link, { useLocale } from "@/components/locale";
import type { Translate } from "@/lib/i18n";
import { ApiError, api } from "@/lib/api";
import { useSession } from "./use-session";

function messageFor(error: unknown, t: Translate) {
  return error instanceof ApiError ? error.problem.detail : t("form.failed");
}

export function EmailForm({ compact = false }: { compact?: boolean }) {
  const id = useId();
  const [notice, setNotice] = useState({ message: "", error: false });
  const [sending, setSending] = useState(false);
  const { t } = useLocale();
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSending(true);
    try {
      const data = new FormData(event.currentTarget);
      const frequency = data.get("frequency");
      await api.newsletter.subscribe(String(data.get("email")), frequency === "immediate" || frequency === "daily" ? frequency : "weekly");
      setNotice({ message: t("subscribe.confirmInbox"), error: false });
    } catch (error) { setNotice({ message: messageFor(error, t), error: true }); } finally { setSending(false); }
  }
  return <form className={compact ? "email-form compact" : "email-form"} onSubmit={submit}>
    <label htmlFor={`${id}-email`}>{t("form.email")}</label>
    <div className="form-row">
      <input id={`${id}-email`} name="email" type="email" autoComplete="email" required placeholder={t("form.emailPlaceholder")} />
      <button type="submit" disabled={sending}>{sending ? t("subscribe.sending") : t("subscribe.submit")}</button>
    </div>
    <label htmlFor={`${id}-frequency`}>{t("subscribe.frequency")}</label><select id={`${id}-frequency`} name="frequency" defaultValue="weekly"><option value="immediate">{t("subscribe.immediate")}</option><option value="daily">{t("subscribe.daily")}</option><option value="weekly">{t("subscribe.weekly")}</option></select>
    <p className="form-note" role={notice.error ? "alert" : "status"}>{notice.message || t("subscribe.note")}</p>
  </form>;
}

export function AuthForm({ kind, nextPath }: { kind: "sign-in" | "register" | "reset"; nextPath?: string }) {
  const router = useRouter();
  const session = useSession();
  const [notice, setNotice] = useState({ message: "", error: false });
  const [busy, setBusy] = useState(false);
  const { t, path } = useLocale();
  const title = t(kind === "sign-in" ? "auth.welcome" : kind === "register" ? "auth.createTitle" : "auth.resetTitle");
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
        setNotice({ message: t("auth.signedIn"), error: false });
        router.replace(path(destination));
      } else if (kind === "register") {
        await api.auth.register({ email, password: String(values.get("password")), displayName: String(values.get("displayName")) });
        setNotice({ message: t("auth.verifyInbox"), error: false });
      } else {
        await api.auth.requestPasswordReset(email);
        setNotice({ message: t("auth.resetSent"), error: false });
      }
    } catch (error) { setNotice({ message: messageFor(error, t), error: true }); } finally { setBusy(false); }
  }
  if (kind === "sign-in" && session.status === "checking") return <section className="auth-panel"><h1>{t("auth.welcome")}</h1><p role="status">{t("session.checking")}</p></section>;
  if (kind === "sign-in" && session.status === "authenticated") return <section className="auth-panel" aria-labelledby="signed-in-title">
    <h1 id="signed-in-title">{t("auth.alreadyTitle")}</h1><p>{t("auth.signedInAs", { name: session.user.displayName, email: session.user.email })}</p>
    <Link href={destination}>{t(destination === "/profile" ? "auth.continueAccount" : "auth.continuePage")}</Link>
    <p>{t("auth.otherAccount")}</p>
  </section>;
  if (kind === "sign-in" && session.status === "error") return <section className="auth-panel"><h1>{t("auth.unavailableTitle")}</h1><p role="alert">{t("auth.unavailable")}</p><button onClick={session.refresh}>{t("auth.retryCheck")}</button></section>;
  return <section className="auth-panel" aria-labelledby="auth-title">
    <p className="eyebrow">{t("auth.eyebrow")}</p><h1 id="auth-title">{title}</h1>
    <form onSubmit={submit}>
      {kind === "register" && <><label htmlFor="name">{t("auth.name")}</label><input id="name" name="displayName" required maxLength={100} autoComplete="name" /></>}
      <label htmlFor="auth-email">{t("form.email")}</label><input id="auth-email" name="email" type="email" required maxLength={320} autoComplete="email" />
      {kind !== "reset" && <><label htmlFor="password">{t("auth.password")}</label><input id="password" name="password" type="password" required minLength={12} maxLength={128} autoComplete={kind === "sign-in" ? "current-password" : "new-password"} /></>}
      <button type="submit" disabled={busy}>{busy ? t("auth.working") : t(kind === "sign-in" ? "session.signIn" : kind === "register" ? "auth.register" : "auth.sendReset")}</button>
      <p role={notice.error ? "alert" : "status"} className="form-note">{notice.message}</p>
    </form>
    <nav className="workflow-actions" aria-label={t("auth.accessLabel")}>
      {kind !== "sign-in" && <Link href="/sign-in">{t("session.signIn")}</Link>}
      {kind !== "register" && <Link href="/register">{t("auth.createAccount")}</Link>}
      {kind !== "reset" && <Link href="/password-reset">{t("auth.forgot")}</Link>}
    </nav>
  </section>;
}
