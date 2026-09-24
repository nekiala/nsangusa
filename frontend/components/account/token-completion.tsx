"use client";

import Link from "next/link";
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
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (await action.run(() => identityApi.verifyEmail(token), "Your email is verified. You can now sign in.")) {
      clearTokenUrl(); setComplete(true);
    }
  }
  async function requestLink(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    await resend.run(() => identityApi.requestVerification(String(data.get("email"))), "If the address needs verification, a new link is on its way.");
  }
  return <section className="auth-panel" aria-labelledby="verification-title">
    <p className="eyebrow">Account verification</p><h1 id="verification-title">Verify your email</h1>
    <p>Use the button below to confirm this email address. Opening a link does not change your account.</p>
    {!complete && <form onSubmit={submit}>
      {!token && <p role="alert">This link is missing its verification token. Request a new link below.</p>}
      <button disabled={action.busy || !token || token.length > 200}>Verify email address</button>
    </form>}
    <Feedback error={action.error} status={action.status} />
    {complete ? <Link href="/sign-in">Sign in</Link> : <form onSubmit={requestLink}>
      <h2>Need a new link?</h2>
      <label htmlFor="verification-email">Email address</label>
      <input id="verification-email" name="email" type="email" autoComplete="email" maxLength={320} required />
      <button disabled={resend.busy}>Send verification link</button>
      <Feedback error={resend.error} status={resend.status} />
    </form>}
  </section>;
}

export function PasswordResetCompletion({ token }: { token: string }) {
  const action = useAction();
  const [complete, setComplete] = useState(false);
  const [validationError, setValidationError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const password = String(data.get("newPassword"));
    if (password !== data.get("confirmPassword")) { setValidationError("The passwords do not match."); return; }
    if (password.length < 12 || password.length > 128) { setValidationError("Use a password of 12–128 characters."); return; }
    setValidationError("");
    try {
      if (await action.run(() => identityApi.confirmPasswordReset(token, password), "Your password has been reset. Previous sessions are signed out.")) {
        form.reset(); clearTokenUrl(); setComplete(true);
      }
    } catch (error) { setValidationError(errorNotice(error)); }
  }
  return <section className="auth-panel" aria-labelledby="reset-title">
    <p className="eyebrow">Account recovery</p><h1 id="reset-title">Choose a new password</h1>
    {!complete && <form onSubmit={submit}>
      {!token && <p role="alert">This link is missing its reset token.</p>}
      <label htmlFor="new-password">New password</label>
      <input id="new-password" name="newPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" />
      <label htmlFor="confirm-password">Confirm new password</label>
      <input id="confirm-password" name="confirmPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" />
      <button disabled={action.busy || !token || token.length > 200}>Reset password</button>
    </form>}
    <Feedback error={validationError || action.error} status={action.status} />
    {complete ? <Link href="/sign-in">Sign in with your new password</Link> : <Link href="/password-reset">Request a new reset link</Link>}
  </section>;
}
