import { describe, expect, it, vi } from "vitest";
import { createApiClient, type ArticleCommand } from "./api";

const command: ArticleCommand = {
  headline: "A headline", summary: "A summary", body: "A body", seoTitle: "A headline", seoDescription: "A summary",
  slugSuggestion: "a-headline", topic: "Ideas", tags: ["Ideas"], commentsEnabled: true,
  sources: [{ sourcePostId: "00000000-0000-4000-8000-000000000001", account: "@source", postId: "post", url: "https://example.invalid/source", publishedAt: "2026-09-01T00:00:00.000Z" }]
};

describe("API client", () => {
  it("bootstraps CSRF and sends session credentials without storing tokens", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf-value" }), { headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "article-id" }), { status: 201, headers: { "content-type": "application/json" } }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.admin.createArticle(command);
    expect(fetcher).toHaveBeenNthCalledWith(1, "https://api.example.test/api/v1/auth/csrf", expect.objectContaining({ credentials: "include", cache: "no-store" }));
    expect(fetcher).toHaveBeenNthCalledWith(2, "https://api.example.test/api/v1/admin/articles", expect.objectContaining({
      credentials: "include", cache: "no-store", method: "POST", headers: expect.any(Headers)
    }));
    expect((fetcher.mock.calls[1][1].headers as Headers).get("X-XSRF-TOKEN")).toBe("csrf-value");
  });

  it("exposes RFC problem details as a typed error", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ title: "Conflict", status: 409, detail: "Refresh and retry.", traceId: "trace-1" }), { status: 409, headers: { "content-type": "application/problem+json" } }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await expect(client.articles.latest()).rejects.toMatchObject({ status: 409, problem: { traceId: "trace-1", detail: "Refresh and retry." } });
  });

  it("keeps authenticated reads out of browser and framework caches", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "1", email: "reader@example.com", displayName: "Reader", roles: ["READER"] }), { headers: { "content-type": "application/json" } }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.auth.me();
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/auth/me", expect.objectContaining({
      credentials: "include", cache: "no-store", next: { revalidate: 0 }
    }));
  });

  it("accepts the manual login redirect used by session authentication", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf-value" }), { headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: "/" } }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await expect(client.auth.login("editor@example.com", "a-secure-password")).resolves.toBeUndefined();
    const request = fetcher.mock.calls[1][1];
    expect(request).toEqual(expect.objectContaining({ credentials: "include", cache: "no-store", redirect: "manual" }));
    expect((request.headers as Headers).get("X-XSRF-TOKEN")).toBe("csrf-value");
  });

  it("retains fake-mode authentication across page module reloads", async () => {
    const client = createApiClient({ mode: "fake" });
    await client.auth.login("editor@example.com", "a-secure-password");
    expect(await client.auth.me()).toMatchObject({ email: "editor@example.com", roles: ["EDITOR"] });
    expect(sessionStorage.getItem("nsangusa.fake-user")).toContain("editor@example.com");
    await client.auth.logout();
    await expect(client.auth.me()).rejects.toMatchObject({ status: 401 });
  });

  it("refreshes the CSRF token after authentication rotates it", async () => {
    const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { "content-type": "application/json" } });
    const fetcher = vi.fn()
      .mockResolvedValueOnce(json({ headerName: "X-XSRF-TOKEN", token: "before-login" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ headerName: "X-XSRF-TOKEN", token: "after-login" }))
      .mockResolvedValueOnce(json({ id: "article-id" }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.auth.login("editor@example.com", "a-secure-password");
    await client.admin.createArticle(command);
    expect(fetcher).toHaveBeenNthCalledWith(3, "https://api.example.test/api/v1/auth/csrf", expect.anything());
    expect((fetcher.mock.calls[3][1].headers as Headers).get("X-XSRF-TOKEN")).toBe("after-login");
  });

  it("keeps editorial queues and newsletter token requests out of caches", async () => {
    const fetcher = vi.fn().mockImplementation(async (url: string) => url.endsWith("/auth/csrf")
      ? Response.json({ headerName: "X-XSRF-TOKEN", token: "newsletter-csrf" })
      : new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.admin.articles("AWAITING_REVIEW", 2);
    await client.admin.candidates(1);
    await client.admin.aiRequests("candidate");
    await client.admin.sources();
    await client.newsletter.confirm("subscription", "private-token");
    for (const [, request] of fetcher.mock.calls) expect(request).toMatchObject({ credentials: "include", cache: "no-store", next: { revalidate: 0 } });
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/admin/articles?page=2&size=20&state=AWAITING_REVIEW", expect.anything());
    expect(fetcher).toHaveBeenLastCalledWith("https://api.example.test/api/v1/newsletter/confirm?id=subscription&token=private-token", expect.objectContaining({ method: "POST" }));
    expect(fetcher.mock.lastCall?.[1].headers.get("X-XSRF-TOKEN")).toBe("newsletter-csrf");
  });

  it("never caches public articles, discovery, or comments after withdrawal", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.articles.latest();
    await client.articles.bySlug("canonical");
    await client.articles.search("words");
    await client.articles.byTopic("Ideas");
    await client.articles.byTag("News");
    await client.articles.comments("article-id");
    for (const [, request] of fetcher.mock.calls) expect(request).toMatchObject({ cache: "no-store", next: { revalidate: 0 } });
  });

  it("uses one mutation key per action and accepts a preserved key for explicit retries", async () => {
    const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { "content-type": "application/json" } });
    const fetcher = vi.fn().mockImplementation(async (url: string) => url.endsWith("/csrf")
      ? json({ headerName: "X-XSRF-TOKEN", token: "csrf" }) : new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.admin.approveArticle("article");
    await client.admin.publishArticle("article");
    const keys = fetcher.mock.calls.filter(([url]) => !String(url).endsWith("/csrf")).map(([, request]) => new Headers(request.headers).get("Idempotency-Key"));
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(keys[1]).not.toBe(keys[0]);
    const retryKey = crypto.randomUUID();
    await client.admin.startCorrection("article", 4, "Corrected attribution.", retryKey);
    await client.admin.startCorrection("article", 4, "Corrected attribution.", retryKey);
    for (const [, request] of fetcher.mock.calls.slice(-2)) {
      expect(new Headers(request.headers).get("Idempotency-Key")).toBe(retryKey);
      expect(JSON.parse(request.body)).toEqual({ expectedVersion: 4, note: "Corrected attribution." });
    }
  });

  it("requests paginated history and exact revision numbers for comparison", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.admin.revisions("article", 2, 10);
    await client.admin.compareRevisions("article", 3, 9);
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/admin/articles/article/revisions?page=2&size=10", expect.objectContaining({ cache: "no-store" }));
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/admin/articles/article/revisions/compare?from=3&to=9", expect.objectContaining({ cache: "no-store" }));
  });

  it.each([
    ["transport failure", () => Promise.reject(new TypeError("Connection lost"))],
    ["gateway failure", async () => new Response(null, { status: 502 })],
    ["truncated success response", async () => new Response("{", { status: 201, headers: { "content-type": "application/json" } })]
  ] as const)("retains a mutation key after %s until an unchanged retry succeeds", async (_description, failure) => {
    const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { "content-type": "application/json" } });
    const fetcher = vi.fn()
      .mockResolvedValueOnce(json({ headerName: "X-XSRF-TOKEN", token: "csrf" }))
      .mockImplementationOnce(failure)
      .mockResolvedValueOnce(json({ id: "created" }))
      .mockResolvedValueOnce(json({ id: "another" }));
    const client = createApiClient({ mode: "api", fetch: fetcher });
    await expect(client.admin.createArticle(command)).rejects.toBeDefined();
    expect(await client.admin.createArticle(command)).toEqual({ id: "created" });
    expect(await client.admin.createArticle(command)).toEqual({ id: "another" });
    const keys = fetcher.mock.calls.slice(1).map(([, init]) => new Headers(init.headers).get("Idempotency-Key"));
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it("keeps uncertain identities separate for changed payloads and preserves array order", async () => {
    const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { "content-type": "application/json" } });
    const fetcher = vi.fn()
      .mockResolvedValueOnce(json({ headerName: "X-XSRF-TOKEN", token: "csrf" }))
      .mockRejectedValueOnce(new TypeError("Connection lost"))
      .mockResolvedValueOnce(json({ id: "changed" }))
      .mockResolvedValueOnce(json({ id: "original" }));
    const client = createApiClient({ mode: "api", fetch: fetcher });
    const original = { ...command, tags: ["Ideas", "News"] };
    await expect(client.admin.createArticle(original)).rejects.toThrow("Connection lost");
    await client.admin.createArticle({ ...command, tags: ["News", "Ideas"] });
    const { tags, ...rest } = original;
    await client.admin.createArticle({ tags, ...rest });
    const keys = fetcher.mock.calls.slice(1).map(([, init]) => new Headers(init.headers).get("Idempotency-Key"));
    expect(keys[1]).not.toBe(keys[0]);
    expect(keys[2]).toBe(keys[0]);
  });

  it("clears uncertain identities when a different authentication session starts", async () => {
    const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { "content-type": "application/json" } });
    const fetcher = vi.fn()
      .mockResolvedValueOnce(json({ headerName: "X-XSRF-TOKEN", token: "before" }))
      .mockRejectedValueOnce(new TypeError("Connection lost"))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ headerName: "X-XSRF-TOKEN", token: "after" }))
      .mockResolvedValueOnce(json({ id: "created" }));
    const client = createApiClient({ mode: "api", fetch: fetcher });
    await expect(client.admin.createArticle(command)).rejects.toThrow("Connection lost");
    await client.auth.login("another@example.test", "password");
    await client.admin.createArticle(command);
    const mutations = fetcher.mock.calls.filter(([path]) => String(path).endsWith("/admin/articles"));
    expect(new Headers(mutations[1][1].headers).get("Idempotency-Key")).not.toBe(new Headers(mutations[0][1].headers).get("Idempotency-Key"));
  });

  it("does not silently evict uncertain request keys when the bounded retry registry fills", async () => {
    const fetcher = vi.fn().mockImplementation(async (url: string) => url.endsWith("/csrf")
      ? new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf" }), { headers: { "content-type": "application/json" } })
      : new Response(null, { status: 502 }));
    const client = createApiClient({ mode: "api", fetch: fetcher });
    for (let index = 0; index < 100; index++) {
      await expect(client.admin.regenerateCandidate(`candidate-${index}`)).rejects.toMatchObject({ status: 502 });
    }
    await expect(client.admin.regenerateCandidate("new-candidate")).rejects.toThrow("unconfirmed outcomes");
    await expect(client.admin.regenerateCandidate("candidate-0")).rejects.toMatchObject({ status: 502 });
    const retries = fetcher.mock.calls.filter(([path]) => String(path).endsWith("/candidate-0/regenerate"));
    expect(new Headers(retries[1][1].headers).get("Idempotency-Key")).toBe(new Headers(retries[0][1].headers).get("Idempotency-Key"));
  });

  it("uses schedule versions, not article versions, for reschedule and cancellation requests", async () => {
    const fetcher = vi.fn().mockImplementation(async (url: string) => url.endsWith("/csrf")
      ? new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf" }), { headers: { "content-type": "application/json" } })
      : new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    await client.admin.publicationSchedules("failed", 2, 10, "article-id");
    await client.admin.publicationPolicy();
    const instant = "2035-01-01T10:00:00.000Z";
    await client.admin.reschedulePublication("schedule-id", 4, instant);
    await client.admin.cancelPublication("schedule-id", 5);
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/admin/publication-schedules?page=2&size=10&status=failed&articleId=article-id", expect.objectContaining({ cache: "no-store" }));
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/admin/publication-schedules/schedule-id", expect.objectContaining({ method: "PATCH", body: JSON.stringify({ expectedVersion: 4, publishAt: instant }) }));
    expect(fetcher).toHaveBeenCalledWith("https://api.example.test/api/v1/admin/publication-schedules/schedule-id/cancel", expect.objectContaining({ method: "POST", body: JSON.stringify({ expectedVersion: 5 }) }));
    for (const [, request] of fetcher.mock.calls.filter(([, request]) => ["POST", "PATCH"].includes(request.method))) {
      expect(new Headers(request.headers).get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    }
  });

  it("guards every article lifecycle action with the displayed version and preserves retry keys", async () => {
    const fetcher = vi.fn().mockImplementation(async (url: string) => url.endsWith("/csrf")
      ? new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf" }), { headers: { "content-type": "application/json" } })
      : new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: "https://api.example.test", fetch: fetcher });
    const actions = [
      ["approveArticle", "approve"], ["publishArticle", "publish"], ["rejectArticle", "reject"],
      ["unpublishArticle", "unpublish"], ["restoreArticle", "restore"], ["archiveArticle", "archive"]
    ] as const;
    for (const [method, path] of actions) {
      const key = crypto.randomUUID();
      await client.admin[method]("article-id", key, 7);
      await client.admin[method]("article-id", key, 7);
      expect(fetcher).toHaveBeenLastCalledWith(`https://api.example.test/api/v1/admin/articles/article-id/${path}?expectedVersion=7`, expect.objectContaining({ method: "POST" }));
      const calls = fetcher.mock.calls.slice(-2);
      for (const [, request] of calls) expect(new Headers(request.headers).get("Idempotency-Key")).toBe(key);
    }
    await client.admin.approveArticle("article-id", undefined, 0);
    expect(fetcher).toHaveBeenLastCalledWith("https://api.example.test/api/v1/admin/articles/article-id/approve?expectedVersion=0", expect.anything());
    await client.admin.approveArticle("article-id");
    expect(fetcher).toHaveBeenLastCalledWith("https://api.example.test/api/v1/admin/articles/article-id/approve", expect.anything());
  });
});
