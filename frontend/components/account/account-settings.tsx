"use client";

import Link from "next/link";
import { useCallback, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { identityApi, type AccountFrequency, type AccountProfile } from "@/lib/identity-api";
import { dateLabel, Feedback, useAction, useResource } from "@/components/admin/shared";

function ProfileEditor({ profile, saved }: { profile: AccountProfile; saved: () => void }) {
  const action = useAction();
  const newsletter = profile.newsletter;
  const subscribed = newsletter && ["pending", "confirmed"].includes(newsletter.status);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const updated = await action.run(() => identityApi.updateProfile({
      displayName: String(data.get("displayName")),
      ...(subscribed ? { newsletterFrequency: String(data.get("frequency")) as AccountFrequency } : {}),
      expectedVersion: profile.version
    }), "Profile saved.");
    if (updated) saved();
  }
  return <form onSubmit={submit}>
    <h2>Profile and preferences</h2>
    <p>{profile.email} · {profile.emailVerified ? "Email verified" : "Verification required"}</p>
    <p>Roles: {profile.roles.join(", ")}</p>
    <label htmlFor="account-name">Display name</label>
    <input id="account-name" name="displayName" required maxLength={100} defaultValue={profile.displayName} autoComplete="name" />
    {subscribed ? <>
      <label htmlFor="account-frequency">Newsletter frequency</label>
      <select id="account-frequency" name="frequency" defaultValue={["instant", "all"].includes(newsletter.frequency) ? "immediate" : newsletter.frequency}>
        <option value="immediate">Every published article</option><option value="daily">Daily digest</option><option value="weekly">Weekly digest</option>
      </select>
      <p>Subscription: {newsletter.status}. Changing frequency does not grant consent or confirm a subscription.</p>
    </> : <p>Newsletter: {newsletter?.status || "Not subscribed"}. <Link href="/newsletter">Manage newsletter subscription</Link></p>}
    <button disabled={action.busy}>Save profile</button>
    <Feedback error={action.error} status={action.status} />
  </form>;
}

export function AccountSettings() {
  const router = useRouter();
  const account = useResource(useCallback(() => identityApi.profile(), []));
  const sessions = useResource(useCallback(() => identityApi.sessions(), []));
  const action = useAction();
  const [finished, setFinished] = useState(false);
  const [confirmation, setConfirmation] = useState("");
  const [saved, setSaved] = useState("");
  function signedOut(destination = "/sign-in") {
    setFinished(true); router.replace(destination); router.refresh();
  }
  async function download() {
    await action.run(async () => {
      const data = await identityApi.exportData();
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = url; link.download = "account-data.json"; document.body.append(link); link.click(); link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    }, "Your account data download is ready.");
  }
  async function remove(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (account.data && await action.run(() => identityApi.deleteAccount(confirmation, account.data!.version), "Account deleted.")) signedOut("/sign-in?deleted=1");
  }
  if (finished) return <section className="empty shell"><p role="status">You are signed out.</p><Link href="/sign-in">Sign in</Link></section>;
  return <section className="shell account-settings" aria-labelledby="account-title">
    <p className="eyebrow">Your account</p><h1 id="account-title">Profile and account settings</h1>
    <button onClick={() => { account.refresh(); sessions.refresh(); }}>Refresh account</button>
    <Feedback error={account.error} status={saved} />
    {account.loading ? <p role="status">Loading your account…</p> : !account.error && account.data && <>
      <ProfileEditor key={`${account.data.id}:${account.data.version}`} profile={account.data} saved={() => { setSaved("Profile saved."); account.refresh(); }} />
      <section aria-labelledby="account-export-title">
        <h2 id="account-export-title">Export account data</h2>
        <p>Download your profile, newsletter preference and linked sign-in identities as JSON. This is an account export, not an export of published content or retained operational records.</p>
        <button disabled={action.busy} onClick={download}>Download account data</button>
      </section>
      <section aria-labelledby="account-delete-title">
        <h2 id="account-delete-title">Delete account</h2>
        <p>Deletion disables sign-in, anonymizes your profile, disconnects external identities, signs out sessions and unsubscribes your newsletter. Published contributions and required audit or consent records may be retained. This cannot be undone.</p>
        <form onSubmit={remove}>
          <label htmlFor="delete-confirmation">Type DELETE to confirm account deletion</label>
          <input id="delete-confirmation" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} maxLength={6} autoComplete="off" required />
          <button disabled={action.busy || confirmation !== "DELETE"}>Permanently delete account</button>
        </form>
      </section>
    </>}
    <section aria-labelledby="account-sessions-title">
      <h2 id="account-sessions-title">Active sessions</h2>
      <Feedback error={sessions.error} />
      {sessions.loading ? <p role="status">Loading sessions…</p> : !sessions.error && sessions.data?.length === 0 ? <p>No active sessions were found.</p> : !sessions.error && sessions.data?.map((session, index) => <article key={session.id}>
        <h3>{session.current ? "This session" : `Other session ${index + 1}`}</h3>
        <p>Created {dateLabel(session.createdAt)} · Last active {dateLabel(session.lastAccessedAt)} · Expires {dateLabel(session.expiresAt)}</p>
        <button disabled={action.busy} onClick={async () => {
          if (await action.run(() => identityApi.revokeSession(session.id), "Session revoked.")) {
            if (session.current) signedOut(); else sessions.refresh();
          }
        }}>{session.current ? "Sign out this session" : "Revoke session"}</button>
      </article>)}
      <button disabled={action.busy} onClick={async () => { if (await action.run(() => api.auth.logout(), "Signed out.")) signedOut(); }}>Sign out</button>
    </section>
    <Feedback error={action.error} status={action.status} />
  </section>;
}
