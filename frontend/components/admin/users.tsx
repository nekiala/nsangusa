"use client";

import { useCallback, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { accountRoles, identityApi, type AccountRole, type AdminUser, type UserStatus } from "@/lib/identity-api";
import { AdminSection, dateLabel, Feedback, Pagination, useAction, useResource } from "./shared";

function RoleEditor({ user, actorId, changed }: { user: AdminUser; actorId: string; changed: () => void }) {
  const action = useAction();
  const [roles, setRoles] = useState<AccountRole[]>(user.roles.filter((role): role is AccountRole => accountRoles.includes(role as AccountRole)));
  const [confirmation, setConfirmation] = useState("");
  const self = user.id === actorId;
  const eligible = !self && user.enabled && user.emailVerified && !user.deletedAt;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (await action.run(() => identityApi.changeRoles(user.id, roles, user.version, confirmation), "Roles updated. Existing sessions are revoked.")) changed();
  }
  return <section aria-labelledby="user-roles-title">
    <h2 id="user-roles-title">Roles for {user.displayName}</h2>
    <p>{user.email} · Version {user.version} · Last sign-in {dateLabel(user.lastLoginAt)}</p>
    {self && <p>You cannot change your own roles. Ask another administrator.</p>}
    {!self && !eligible && <p>Only active, verified accounts may have their roles changed.</p>}
    {user.rolesManagedLocally && <p>Roles are managed here; external sign-in will not restore removed roles.</p>}
    {eligible && <form onSubmit={submit}>
      <fieldset disabled={action.busy}><legend>Allowed roles</legend>
        {accountRoles.map((role) => <label key={role}><input type="checkbox" checked={roles.includes(role)} onChange={(event) => setRoles(event.target.checked ? [...roles, role] : roles.filter((value) => value !== role))} />{role}</label>)}
      </fieldset>
      {roles.includes("ADMINISTRATOR") && !user.roles.includes("ADMINISTRATOR") && <p role="note">Administrator access grants control over users, roles and protected administration settings. Grant it only to an authorized operator.</p>}
      <p>At least one role is required. Saving signs this user out of existing sessions.</p>
      <label htmlFor="role-confirmation">Type {user.email} to confirm</label>
      <input id="role-confirmation" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="off" maxLength={320} required />
      <button disabled={action.busy || !roles.length || confirmation !== user.email}>Save roles</button>
    </form>}
    <Feedback error={action.error} status={action.status} />
  </section>;
}

function SelectedUser({ id, actorId, saved }: { id: string; actorId: string; saved: () => void }) {
  const user = useResource(useCallback(() => identityApi.user(id), [id]));
  return <>
    <button onClick={user.refresh}>Refresh selected user</button>
    <Feedback error={user.error} />
    {user.loading ? <p role="status">Loading selected user…</p> : !user.error && user.data && <RoleEditor key={`${user.data.id}:${user.data.version}`} user={user.data} actorId={actorId} changed={() => { user.refresh(); saved(); }} />}
  </>;
}

export function UsersWorkspace() {
  const [query, setQuery] = useState("");
  const [role, setRole] = useState<AccountRole | "">("");
  const [status, setStatus] = useState<UserStatus | "">("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState("");
  const [notice, setNotice] = useState("");
  const queue = useResource(useCallback(async () => {
    const actor = await api.auth.me();
    if (!actor.roles.includes("ADMINISTRATOR")) throw new ApiError(403, { title: "Access restricted", detail: "Administrator access is required.", status: 403 });
    return { actor, users: await identityApi.users({ q: query, role: role || undefined, status: status || undefined, page }) };
  }, [query, role, status, page]));
  return <AdminSection title="Users and roles" description="Find current accounts and manage verified users' roles. Changes require confirmation, an up-to-date version and an administrator account.">
    <form onSubmit={(event) => { event.preventDefault(); setQuery(String(new FormData(event.currentTarget).get("q")).trim()); setPage(0); setSelected(""); }}>
      <label htmlFor="user-query">Search users by name or email</label>
      <input id="user-query" name="q" maxLength={100} /><button>Search users</button>
    </form>
    <label htmlFor="user-role">Filter by role</label>
    <select id="user-role" value={role} onChange={(event) => { setRole(event.target.value as AccountRole | ""); setPage(0); setSelected(""); }}>
      <option value="">All roles</option>{accountRoles.map((value) => <option key={value}>{value}</option>)}
    </select>
    <label htmlFor="user-status">Account status</label>
    <select id="user-status" value={status} onChange={(event) => { setStatus(event.target.value as UserStatus | ""); setPage(0); setSelected(""); }}>
      <option value="">All non-deleted accounts</option><option value="active">Active and verified</option><option value="unverified">Unverified</option><option value="disabled">Disabled</option><option value="deleted">Deleted</option>
    </select>
    <button onClick={queue.refresh}>Refresh users</button>
    <Feedback error={queue.error} status={notice} />
    {queue.loading ? <p role="status">Loading users…</p> : !queue.error && queue.data && <>
      {!queue.data.users.items.length && <p>No accounts match these filters.</p>}
      <ul>{queue.data.users.items.map((user) => <li key={user.id}>
        <strong>{user.displayName}</strong> · {user.email} · {user.roles.join(", ") || "No roles"} · {user.deletedAt ? "Deleted" : !user.enabled ? "Disabled" : user.emailVerified ? "Verified" : "Unverified"}{" "}
        <button onClick={() => { setSelected(user.id); setNotice(""); }}>Manage roles for {user.displayName}</button>
      </li>)}</ul>
      <Pagination {...queue.data.users} onPage={(value) => { setPage(value); setSelected(""); }} label="User pages" />
      {selected && <SelectedUser id={selected} actorId={queue.data.actor.id} saved={() => { setNotice("Roles updated. Existing sessions are revoked."); queue.refresh(); }} />}
    </>}
  </AdminSection>;
}
