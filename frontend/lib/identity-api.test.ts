import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "./api";
import { createIdentityApi } from "./identity-api";

function fakeClient(roles = ["ADMINISTRATOR"]) {
  const client = createApiClient({ mode: "fake" });
  vi.spyOn(client.auth, "me").mockResolvedValue({ id: "current", email: "admin@example.test", displayName: "Current user", roles });
  vi.spyOn(client.auth, "logout").mockResolvedValue(undefined);
  return client;
}

describe("identity feature client", () => {
  it("uses real CSRF-protected transport, current account, versions and command keys", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf" }), { status: 200, headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const identity = createIdentityApi(createApiClient({ mode: "api", baseUrl: "https://service.test", fetch: fetcher }));
    await identity.changeRoles("user/1", ["EDITOR", "READER"], 4, "reader@example.test", "identity-command-1");
    const [url, request] = fetcher.mock.calls[1];
    expect(url).toBe("https://service.test/api/v1/admin/users/user%2F1/roles");
    expect(request?.method).toBe("PUT");
    expect(new Headers(request?.headers).get("X-XSRF-TOKEN")).toBe("csrf");
    expect(new Headers(request?.headers).get("Idempotency-Key")).toBe("identity-command-1");
    expect(JSON.parse(String(request?.body))).toEqual({ roles: ["EDITOR", "READER"], expectedVersion: 4, confirmation: "reader@example.test" });
    expect(request?.credentials).toBe("include");
    expect(request?.cache).toBe("no-store");
  });

  it("never falls back to demonstration data after API errors", async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new TypeError("Network unavailable"));
    const identity = createIdentityApi(createApiClient({ mode: "api", fetch: fetcher }));
    await expect(identity.profile()).rejects.toThrow("Network unavailable");
    await expect(identity.users()).rejects.toThrow("Network unavailable");
  });

  it("fake current profile updates are versioned, exportable and reflected in the session", async () => {
    const client = createApiClient({ mode: "fake" });
    await client.auth.login("reader@example.test", "a-secure-password");
    vi.spyOn(client.auth, "logout");
    const identity = createIdentityApi(client);
    const profile = await identity.profile();
    expect(profile.email).toBe("reader@example.test");
    await identity.updateProfile({ displayName: "My new name", newsletterFrequency: "daily", expectedVersion: 0 });
    expect((await identity.exportData()).profile).toMatchObject({ displayName: "My new name", newsletter: { frequency: "daily" }, version: 1 });
    await expect(identity.updateProfile({ displayName: "Stale", expectedVersion: 0 })).rejects.toMatchObject({ status: 409 });
    expect((await createIdentityApi(client).profile()).displayName).toBe("My new name");
    expect((await client.auth.me()).displayName).toBe("My new name");
    const sessions = await identity.sessions();
    await identity.revokeSession(sessions.find((session) => !session.current)!.id);
    expect(await identity.sessions()).toHaveLength(1);
    await identity.deleteAccount("DELETE", 1);
    expect(client.auth.logout).toHaveBeenCalled();
  });

  it("fake admin role changes enforce confirmation, versions, current actor and replay semantics", async () => {
    const identity = createIdentityApi(fakeClient());
    const users = await identity.users({ role: "READER" });
    expect(users.items.some((user) => user.id === "identity-reader")).toBe(true);
    await identity.changeRoles("identity-reader", ["READER", "EDITOR"], 0, "reader@example.test", "key-identity-1");
    await identity.changeRoles("identity-reader", ["EDITOR", "READER"], 0, "reader@example.test", "key-identity-1");
    expect((await identity.user("identity-reader")).version).toBe(1);
    await expect(identity.changeRoles("identity-reader", ["ADMINISTRATOR"], 1, "reader@example.test", "key-identity-1")).rejects.toMatchObject({ status: 409 });
    await expect(identity.changeRoles("current", ["READER"], 0, "admin@example.test")).rejects.toThrow("own roles");
    await expect(identity.changeRoles("identity-pending", ["ADMINISTRATOR"], 0, "pending@example.test")).rejects.toThrow("verified");
    await expect(createIdentityApi(fakeClient(["EDITOR"])).users()).rejects.toBeInstanceOf(ApiError);
  });

  it("fake tokens are single use and expired tokens fail without external providers", async () => {
    const identity = createIdentityApi(fakeClient());
    const token = "a".repeat(43);
    await identity.verifyEmail(token);
    await expect(identity.verifyEmail(token)).rejects.toMatchObject({ status: 400 });
    await expect(identity.confirmPasswordReset(`expired${"a".repeat(36)}`, "safe-new-password")).rejects.toMatchObject({ status: 400 });
  });
});
