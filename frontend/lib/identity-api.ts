import { api, ApiError, type Page, type UserProfile, type createApiClient } from "@/lib/api";

export const accountRoles = ["READER", "MODERATOR", "EDITOR", "ADMINISTRATOR"] as const;
export type AccountRole = typeof accountRoles[number];
export type AccountFrequency = "immediate" | "daily" | "weekly";
export type AccountProfile = UserProfile & {
  emailVerified: boolean; createdAt: string; lastLoginAt: string | null; version: number;
  newsletter: { subscriptionId: string; status: string; frequency: AccountFrequency | "instant" | "all" } | null;
};
export type AccountSession = { id: string; createdAt: string; lastAccessedAt: string; expiresAt: string; current: boolean };
export type AccountExport = {
  generatedAt: string; profile: AccountProfile;
  externalIdentities: { issuer: string; subject: string; emailAtLink: string; linkedAt: string; lastLoginAt: string | null }[];
};
export type AdminUser = UserProfile & {
  emailVerified: boolean; enabled: boolean; deletedAt: string | null; createdAt: string;
  lastLoginAt: string | null; version: number; rolesManagedLocally: boolean;
};
export type UserStatus = "active" | "unverified" | "disabled" | "deleted";
export type UserFilters = { q?: string; role?: AccountRole; status?: UserStatus; page?: number; size?: number };
type Client = Pick<ReturnType<typeof createApiClient>, "mode" | "request" | "auth">;

function failure(status: number, detail: string): never {
  throw new ApiError(status, { status, title: status === 409 ? "Conflict" : "Request rejected", detail });
}

export function createIdentityApi(client: Client = api) {
  // This state exists only in explicitly selected fake mode; API failures never use it.
  const profiles = new Map<string, AccountProfile>();
  const sessionLists = new Map<string, AccountSession[]>();
  const consumed = new Set<string>();
  const receipts = new Map<string, string>();
  const now = "2026-09-01T12:00:00Z";
  const demoUsers: AdminUser[] = [
    { id: "identity-reader", email: "reader@example.test", displayName: "Reader", roles: ["READER"], emailVerified: true, enabled: true, deletedAt: null, createdAt: now, lastLoginAt: now, version: 0, rolesManagedLocally: false },
    { id: "identity-moderator", email: "moderator@example.test", displayName: "Moderator", roles: ["MODERATOR"], emailVerified: true, enabled: true, deletedAt: null, createdAt: now, lastLoginAt: now, version: 0, rolesManagedLocally: false },
    { id: "identity-pending", email: "pending@example.test", displayName: "Pending reader", roles: ["READER"], emailVerified: false, enabled: true, deletedAt: null, createdAt: now, lastLoginAt: null, version: 0, rolesManagedLocally: false }
  ];
  const clone = <T,>(value: T): T => structuredClone(value);
  async function fakeProfile() {
    const user = await client.auth.me();
    if (!profiles.has(user.id)) profiles.set(user.id, { ...user, emailVerified: true, createdAt: now, lastLoginAt: now, version: 0, newsletter: { subscriptionId: `newsletter-${user.id}`, status: "confirmed", frequency: "weekly" } });
    return profiles.get(user.id)!;
  }
  async function fakeAdministrator() {
    const user = await fakeProfile();
    if (!user.roles.includes("ADMINISTRATOR")) failure(403, "Administrator access is required.");
    return user;
  }
  async function fakeUsers() {
    const me = await fakeAdministrator();
    return [{ ...me, enabled: true, deletedAt: null, rolesManagedLocally: false }, ...demoUsers];
  }
  const json = (method: string, body: unknown) => ({ method, body: JSON.stringify(body) });
  async function consumeToken(token: string, purpose: string) {
    if (!/^[A-Za-z0-9_-]{43}$/.test(token) || token.startsWith("expired") || consumed.has(token)) failure(400, "Token is invalid or expired. Request a new link.");
    if (purpose === "password-reset" && token.startsWith("verification") || purpose === "verification" && token.startsWith("password-reset")) failure(400, "This link is for a different account action.");
    consumed.add(token);
  }
  return {
    profile: async (): Promise<AccountProfile> => client.mode === "fake" ? clone(await fakeProfile()) : client.request("/api/v1/auth/me"),
    updateProfile: async (input: { displayName: string; newsletterFrequency?: AccountFrequency; expectedVersion: number }): Promise<AccountProfile> => {
      if (client.mode !== "fake") return client.request("/api/v1/auth/me", json("PATCH", input), true);
      const profile = await fakeProfile();
      if (profile.version !== input.expectedVersion) failure(409, "The profile changed. Refresh and retry.");
      if (!input.displayName.trim() || input.displayName.trim().length > 100) failure(400, "Display name is invalid.");
      await client.request("/api/v1/auth/me", json("PATCH", { displayName: input.displayName }), true);
      profile.displayName = input.displayName.trim();
      if (input.newsletterFrequency && profile.newsletter) profile.newsletter.frequency = input.newsletterFrequency;
      profile.version++;
      return clone(profile);
    },
    sessions: async (): Promise<AccountSession[]> => {
      if (client.mode !== "fake") return client.request("/api/v1/auth/sessions");
      const user = await fakeProfile();
      if (!sessionLists.has(user.id)) sessionLists.set(user.id, [
        { id: "current-session", createdAt: now, lastAccessedAt: now, expiresAt: "2030-01-01T12:00:00Z", current: true },
        { id: "other-session", createdAt: now, lastAccessedAt: now, expiresAt: "2030-01-01T12:00:00Z", current: false }
      ]);
      return clone(sessionLists.get(user.id)!);
    },
    revokeSession: async (id: string): Promise<void> => {
      if (client.mode !== "fake") return client.request(`/api/v1/auth/sessions/${encodeURIComponent(id)}`, { method: "DELETE" }, true);
      const user = await fakeProfile();
      const sessions = sessionLists.get(user.id) || [];
      const found = sessions.find((session) => session.id === id);
      if (!found) failure(400, "Session not found.");
      sessionLists.set(user.id, sessions.filter((session) => session.id !== id));
      if (found.current) await client.auth.logout();
    },
    exportData: async (): Promise<AccountExport> => client.mode === "fake"
      ? { generatedAt: new Date().toISOString(), profile: clone(await fakeProfile()), externalIdentities: [] }
      : client.request("/api/v1/auth/me/export"),
    deleteAccount: async (confirmation: string, expectedVersion: number): Promise<void> => {
      if (client.mode !== "fake") return client.request("/api/v1/auth/me", json("DELETE", { confirmation, expectedVersion }), true);
      const user = await fakeProfile();
      if (confirmation !== "DELETE") failure(400, "Type DELETE to confirm.");
      if (expectedVersion !== user.version) failure(409, "The profile changed. Refresh and retry.");
      if (user.roles.includes("ADMINISTRATOR")) failure(409, "The last active administrator cannot delete their account.");
      profiles.delete(user.id);
      sessionLists.delete(user.id);
      await client.auth.logout();
    },
    verifyEmail: async (token: string): Promise<void> => client.mode === "fake"
      ? consumeToken(token, "verification") : client.request("/api/v1/auth/verify-email", json("POST", { token }), true),
    requestVerification: async (email: string): Promise<void> => {
      if (client.mode !== "fake") return client.request("/api/v1/auth/verification/request", json("POST", { email }), true);
      if (!email.includes("@") || email.length > 320) failure(400, "Enter a valid email address.");
    },
    confirmPasswordReset: async (token: string, newPassword: string): Promise<void> => {
      if (client.mode !== "fake") return client.request("/api/v1/auth/password-reset/confirm", json("POST", { token, newPassword }), true);
      if (newPassword.length < 12 || newPassword.length > 128) failure(400, "Use a password of 12–128 characters.");
      await consumeToken(token, "password-reset");
    },
    users: async (filters: UserFilters = {}): Promise<Page<AdminUser>> => {
      const { q = "", role, status, page = 0, size = 20 } = filters;
      if (client.mode !== "fake") {
        const query = new URLSearchParams({ page: String(page), size: String(size) });
        if (q) query.set("q", q);
        if (role) query.set("role", role);
        if (status) query.set("status", status);
        return client.request(`/api/v1/admin/users?${query}`);
      }
      const users = (await fakeUsers()).filter((user) =>
        (!q || `${user.email} ${user.displayName}`.toLowerCase().includes(q.toLowerCase()))
        && (!role || user.roles.includes(role))
        && (!status || status === "active" && user.emailVerified || status === "unverified" && !user.emailVerified)
      );
      return { items: clone(users.slice(page * size, (page + 1) * size)), page, size, total: users.length };
    },
    user: async (id: string): Promise<AdminUser> => {
      if (client.mode !== "fake") return client.request(`/api/v1/admin/users/${encodeURIComponent(id)}`);
      return clone((await fakeUsers()).find((user) => user.id === id) || failure(404, "User not found."));
    },
    changeRoles: async (id: string, roles: AccountRole[], expectedVersion: number, confirmation: string, requestKey?: string): Promise<void> => {
      const input = { roles: [...roles].sort(), expectedVersion, confirmation };
      if (client.mode !== "fake") return client.request(`/api/v1/admin/users/${encodeURIComponent(id)}/roles`, json("PUT", input), true, requestKey);
      const actor = await fakeAdministrator();
      const fingerprint = JSON.stringify({ id, ...input });
      const key = requestKey ? `${actor.id}:${requestKey}` : null;
      if (key && receipts.has(key)) {
        if (receipts.get(key) !== fingerprint) failure(409, "Idempotency-Key was already used for a different request.");
        return;
      }
      if (actor.id === id) failure(409, "Administrators cannot change their own roles.");
      const target = demoUsers.find((user) => user.id === id) || failure(404, "User not found.");
      if (target.version !== expectedVersion) failure(409, "The user changed. Refresh and retry.");
      if (!target.emailVerified) failure(409, "Only active, verified accounts may have their roles changed.");
      if (!roles.length || roles.some((role) => !accountRoles.includes(role))) failure(400, "Select at least one allowed role.");
      if (confirmation !== target.email) failure(400, "Type the selected user's email address to confirm.");
      target.roles = [...roles]; target.version++; target.rolesManagedLocally = true;
      if (key) receipts.set(key, fingerprint);
    }
  };
}

export const identityApi = createIdentityApi();
