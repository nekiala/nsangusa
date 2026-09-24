import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./api";
import { sessionChangedEvent } from "./session-events";
import { deferred } from "@/test/deferred";

const user = { id: "admin", email: "admin@example.test", displayName: "Administrator", roles: ["ADMINISTRATOR"] };
const csrf = { headerName: "X-XSRF-TOKEN", token: "csrf-value" };

describe("session reads and invalidation", () => {
  it("shares in-flight reads but never retains a settled session response", async () => {
    const fetcher = vi.fn().mockImplementation(async () => Response.json(user));
    const client = createApiClient({ mode: "api", fetch: fetcher });
    const first = client.auth.me();
    const second = client.auth.me();
    expect(first).toBe(second);
    expect(await second).toEqual(user);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(await client.auth.me()).toEqual(user);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it.each(["login", "logout"] as const)("never reuses a pre-%s session request after the mutation", async (mutation) => {
    const stale = deferred<Response>();
    const current = deferred<Response>();
    const fetcher = vi.fn()
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(Response.json(csrf))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockReturnValueOnce(current.promise);
    const client = createApiClient({ mode: "api", fetch: fetcher });
    const before = client.auth.me().catch((error: unknown) => error);
    if (mutation === "login") await client.auth.login(user.email, "a-secure-password");
    else await client.auth.logout();
    const after = client.auth.me();
    stale.resolve(Response.json(user));
    await before;
    expect(client.auth.me()).toBe(after);
    current.resolve(mutation === "login" ? Response.json(user) : new Response(null, { status: 401 }));
    if (mutation === "login") expect(await after).toEqual(user);
    else await expect(after).rejects.toMatchObject({ status: 401 });
    expect(fetcher).toHaveBeenCalledTimes(4);
  });

  it.each([
    { path: "/api/v1/auth/me", method: "PATCH", reset: false },
    { path: "/api/v1/auth/me", method: "DELETE", reset: true },
    { path: "/api/v1/auth/sessions/current", method: "DELETE", reset: false },
    { path: "/api/v1/auth/password-reset/confirm", method: "POST", reset: true }
  ])("notifies session consumers after $method $path", async ({ path, method, reset }) => {
    const changed = vi.fn();
    window.addEventListener(sessionChangedEvent, changed);
    try {
      const fetcher = vi.fn().mockResolvedValueOnce(Response.json(csrf)).mockResolvedValueOnce(new Response(null, { status: 204 }));
      const client = createApiClient({ mode: "api", fetch: fetcher });
      await client.request(path, { method }, true);
      expect(changed).toHaveBeenCalledWith(expect.objectContaining({ detail: { reset, ending: method === "DELETE" } }));
    } finally { window.removeEventListener(sessionChangedEvent, changed); }
  });

  it("revalidates on an expired protected request without looping on anonymous session reads", async () => {
    const changed = vi.fn();
    window.addEventListener(sessionChangedEvent, changed);
    try {
      const fetcher = vi.fn().mockImplementation(async () => new Response(null, { status: 401 }));
      const client = createApiClient({ mode: "api", fetch: fetcher });
      await expect(client.auth.me()).rejects.toMatchObject({ status: 401 });
      expect(changed).not.toHaveBeenCalled();
      await expect(client.request("/api/v1/admin/users")).rejects.toMatchObject({ status: 401 });
      expect(changed).toHaveBeenCalledTimes(1);
    } finally { window.removeEventListener(sessionChangedEvent, changed); }
  });
});
