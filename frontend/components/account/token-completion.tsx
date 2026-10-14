"use client";

import Link, { useLocale } from "@/components/locale";
import { useState, type FormEvent } from "react";
import { identityApi } from "@/lib/identity-api";
import { errorNotice, Feedback, useAction } from "@/components/admin/shared";

function clearTokenUrl() {
  window.history.replaceState(window.history.state, "", window.location.pathname);
}

export function VerifyEmail({ token }: { token: string }) {
  const action = useAction();
  const resend = useAction();
  const [complete, setComplete] = useState(false);
  const { t } = useLocale();
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (await action.run(() => identityApi.verifyEmail(token), t("verify.done"))) {
      clearTokenUrl(); setComplete(true);
    }
  }
  async function requestLink(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    await resend.run(() => identityApi.requestVerification(String(data.get("email"))), t("verify.resent"));
  }
  return <section className="auth-panel" aria-labelledby="verification-title">
    <p className="eyebrow">{t("verify.eyebrow")}</p><h1 id="verification-title">{t("verify.title")}</h1>
    <p>{t("verify.intro")}</p>
    {!complete && <form onSubmit={submit}>
      {!token && <p role="alert">{t("verify.missing")}</p>}
      <button disabled={action.busy || !token || token.length > 200}>{t("verify.submit")}</button>
    </form>}
    <Feedback error={action.error} status={action.status} />
    {complete ? <Link href="/sign-in">{t("session.signIn")}</Link> : <form onSubmit={requestLink}>
      <h2>{t("verify.needLink")}</h2>
      <label htmlFor="verification-email">{t("form.email")}</label>
      <input id="verification-email" name="email" type="email" autoComplete="email" maxLength={320} required />
      <button disabled={resend.busy}>{t("verify.send")}</button>
      <Feedback error={resend.error} status={resend.status} />
    </form>}
  </section>;
}

export function PasswordResetCompletion({ token }: { token: string }) {
  const action = useAction();
  const [complete, setComplete] = useState(false);
  const [validationError, setValidationError] = useState("");
  const { t } = useLocale();
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const password = String(data.get("newPassword"));
    if (password !== data.get("confirmPassword")) { setValidationError(t("reset.mismatch")); return; }
    if (password.length < 12 || password.length > 128) { setValidationError(t("reset.length")); return; }
    setValidationError("");
    try {
      if (await action.run(() => identityApi.confirmPasswordReset(token, password), t("reset.done"))) {
        form.reset(); clearTokenUrl(); setComplete(true);
      }
    } catch (error) { setValidationError(errorNotice(error)); }
  }
  return <section className="auth-panel" aria-labelledby="reset-title">
    <p className="eyebrow">{t("reset.eyebrow")}</p><h1 id="reset-title">{t("reset.title")}</h1>
    {!complete && <form onSubmit={submit}>
      {!token && <p role="alert">{t("reset.missing")}</p>}
      <label htmlFor="new-password">{t("reset.new")}</label>
      <input id="new-password" name="newPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" />
      <label htmlFor="confirm-password">{t("reset.confirm")}</label>
      <input id="confirm-password" name="confirmPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" />
      <button disabled={action.busy || !token || token.length > 200}>{t("reset.submit")}</button>
    </form>}
    <Feedback error={validationError || action.error} status={action.status} />
    {complete ? <Link href="/sign-in">{t("reset.signIn")}</Link> : <Link href="/password-reset">{t("reset.request")}</Link>}
  </section>;
}
