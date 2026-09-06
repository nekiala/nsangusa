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
    expect(await client.auth.me()).toMatchObject({ email: "editor@example.com", roles: ["EDITOR", "ADMINISTRATOR"] });
    expect(sessionStorage.getItem("nsangusa.fake-user")).toContain("editor@example.com");
    await client.auth.logout();
    await expect(client.auth.me()).rejects.toMatchObject({ status: 401 });
  });
});
