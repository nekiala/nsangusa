"use client";

import Link, { useLocale } from "@/components/locale";
import { useCallback, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { identityApi, type AccountFrequency, type AccountProfile } from "@/lib/identity-api";
import { dateLabel, Feedback, useAction, useResource } from "@/components/admin/shared";

function ProfileEditor({ profile, saved }: { profile: AccountProfile; saved: () => void }) {
  const action = useAction();
  const { t } = useLocale();
  const newsletter = profile.newsletter;
  const subscribed = newsletter && ["pending", "confirmed"].includes(newsletter.status);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const updated = await action.run(() => identityApi.updateProfile({
      displayName: String(data.get("displayName")),
      ...(subscribed ? { newsletterFrequency: String(data.get("frequency")) as AccountFrequency } : {}),
      expectedVersion: profile.version
    }), t("account.saved"));
    if (updated) saved();
  }
  return <form onSubmit={submit}>
    <h2>{t("account.profileTitle")}</h2>
    <p>{profile.email} · {t(profile.emailVerified ? "account.verified" : "account.unverified")}</p>
    <p>{t("account.roles", { roles: profile.roles.join(", ") })}</p>
    <label htmlFor="account-name">{t("account.displayName")}</label>
    <input id="account-name" name="displayName" required maxLength={100} defaultValue={profile.displayName} autoComplete="name" />
    {subscribed ? <>
      <label htmlFor="account-frequency">{t("account.frequency")}</label>
      <select id="account-frequency" name="frequency" defaultValue={["instant", "all"].includes(newsletter.frequency) ? "immediate" : newsletter.frequency}>
        <option value="immediate">{t("subscribe.immediate")}</option><option value="daily">{t("subscribe.daily")}</option><option value="weekly">{t("subscribe.weekly")}</option>
      </select>
      <p>{t("account.subscription", { status: newsletter.status })}</p>
    </> : <p>{t("account.newsletter", { status: newsletter?.status || t("account.notSubscribed") })} <Link href="/newsletter">{t("account.manageNewsletter")}</Link></p>}
    <button disabled={action.busy}>{t("account.save")}</button>
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
  const { t, path } = useLocale();
  function signedOut(destination = "/sign-in") {
    setFinished(true); router.replace(path(destination)); router.refresh();
  }
  async function download() {
    await action.run(async () => {
      const data = await identityApi.exportData();
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = url; link.download = "account-data.json"; document.body.append(link); link.click(); link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    }, t("account.exportReady"));
  }
  async function remove(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (account.data && await action.run(() => identityApi.deleteAccount(confirmation, account.data!.version), t("account.deleted"))) signedOut("/sign-in?deleted=1");
  }
  if (finished) return <section className="empty shell"><p role="status">{t("access.signedOut")}</p><Link href="/sign-in">{t("session.signIn")}</Link></section>;
  return <section className="shell account-settings" aria-labelledby="account-title">
    <p className="eyebrow">{t("account.eyebrow")}</p><h1 id="account-title">{t("account.title")}</h1>
    <button onClick={() => { account.refresh(); sessions.refresh(); }}>{t("account.refresh")}</button>
    <Feedback error={account.error} status={saved} />
    {account.loading ? <p role="status">{t("account.loading")}</p> : !account.error && account.data && <>
      <ProfileEditor key={`${account.data.id}:${account.data.version}`} profile={account.data} saved={() => { setSaved(t("account.saved")); account.refresh(); }} />
      <section aria-labelledby="account-export-title">
        <h2 id="account-export-title">{t("account.exportTitle")}</h2>
        <p>{t("account.exportBody")}</p>
        <button disabled={action.busy} onClick={download}>{t("account.exportButton")}</button>
      </section>
      <section aria-labelledby="account-delete-title">
        <h2 id="account-delete-title">{t("account.deleteTitle")}</h2>
        <p>{t("account.deleteBody")}</p>
        <form onSubmit={remove}>
          <label htmlFor="delete-confirmation">{t("account.deleteLabel")}</label>
          <input id="delete-confirmation" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} maxLength={6} autoComplete="off" required />
          <button disabled={action.busy || confirmation !== "DELETE"}>{t("account.deleteButton")}</button>
        </form>
      </section>
    </>}
    <section aria-labelledby="account-sessions-title">
      <h2 id="account-sessions-title">{t("account.sessionsTitle")}</h2>
      <Feedback error={sessions.error} />
      {sessions.loading ? <p role="status">{t("account.sessionsLoading")}</p> : !sessions.error && sessions.data?.length === 0 ? <p>{t("account.sessionsNone")}</p> : !sessions.error && sessions.data?.map((session, index) => <article key={session.id}>
        <h3>{session.current ? t("account.thisSession") : t("account.otherSession", { number: index + 1 })}</h3>
        <p>{t("account.sessionDates", { created: dateLabel(session.createdAt), active: dateLabel(session.lastAccessedAt), expires: dateLabel(session.expiresAt) })}</p>
        <button disabled={action.busy} onClick={async () => {
          if (await action.run(() => identityApi.revokeSession(session.id), t("account.sessionRevoked"))) {
            if (session.current) signedOut(); else sessions.refresh();
          }
        }}>{t(session.current ? "account.signOutSession" : "account.revokeSession")}</button>
      </article>)}
      <button disabled={action.busy} onClick={async () => { if (await action.run(() => api.auth.logout(), t("account.signedOut"))) signedOut(); }}>{t("session.signOut")}</button>
    </section>
    <Feedback error={action.error} status={action.status} />
  </section>;
}
