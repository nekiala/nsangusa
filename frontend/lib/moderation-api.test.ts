import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "./api";
import { createModerationApi, type CommunityComment, type ModerationItem } from "./moderation-api";

const comment: CommunityComment = { id: "comment-1", authorId: "reader-1", body: "Original", parentId: null, state: "approved", createdAt: "2026-09-12T12:00:00Z", editedAt: null, deleted: false, deletedByAuthor: false, version: 4 };
type RequestOptions = NonNullable<Parameters<typeof fetch>[1]>;
function fixture() {
  const fetcher = vi.fn(async (url: string | URL | Request) => new Response(JSON.stringify(String(url).endsWith("/auth/csrf") ? { token: "csrf", headerName: "X-XSRF-TOKEN" } : {}), { status: 200, headers: { "Content-Type": "application/json" } }));
  const transport = createApiClient({ mode: "api", baseUrl: "https://example.test", fetch: fetcher });
  return { client: createModerationApi(transport), fetcher };
}
describe("community API transport", () => {
  it("loads a personalized no-store discussion and paginated real moderation inventory", async () => {
    const { client, fetcher } = fixture();
    await client.discussion("article-1"); await client.queue("spam", 2); await client.users("Reader Smith"); await client.articles(3);
    expect(fetcher).toHaveBeenCalledWith("https://example.test/api/v1/articles/article-1/discussion", expect.objectContaining({ credentials: "include", cache: "no-store" }));
    expect(fetcher).toHaveBeenCalledWith("https://example.test/api/v1/admin/comments?state=spam&page=2&size=20", expect.anything());
    expect(fetcher).toHaveBeenCalledWith("https://example.test/api/v1/admin/commenting-users?query=Reader+Smith&limit=20", expect.anything());
  });

  it("sends ownership mutations with CSRF, session, idempotency and versions", async () => {
    const { client, fetcher } = fixture();
    await client.submit("article-1", "Reply", "parent-1");
    await client.edit(comment, "Changed");
    await client.deleteOwn(comment);
    await client.report(comment.id, "abuse", "Details");
    const calls = fetcher.mock.calls as unknown as [string, RequestOptions][];
    const edit = calls.find(([url]) => url.endsWith("/comments/comment-1"))![1];
    expect(JSON.parse(String(edit.body))).toEqual({ body: "Changed", expectedVersion: 4 });
    expect(new Headers(edit.headers).get("X-XSRF-TOKEN")).toBe("csrf");
    expect(new Headers(edit.headers).get("Idempotency-Key")).toBeTruthy();
    expect(calls.some(([url, init]) => url.endsWith("/comments/comment-1?expectedVersion=4") && init.method === "DELETE")).toBe(true);
    const submit = calls.find(([url]) => url.endsWith("/articles/article-1/comments"))![1];
    expect(JSON.parse(String(submit.body))).toEqual({ body: "Reply", parentId: "parent-1" });
  });

  it("sends moderation, policies and suspension to backend with reasons and expected versions", async () => {
    const { client, fetcher } = fixture();
    await client.moderate(comment as ModerationItem, "rejected", "Harassment");
    await client.updateArticleSettings({ articleId: "article-1", enabledOverride: false, requireApprovalOverride: null, version: -1, updatedAt: "" });
    await client.setPrivilege({ userId: "reader-1", status: "allowed", suspendedUntil: null, reason: null, moderatorId: null, updatedAt: "", version: -1 }, "suspend", "Repeated abuse", null);
    const calls = fetcher.mock.calls as unknown as [string, RequestOptions][];
    const moderation = calls.find(([url]) => url.endsWith("/moderate"))![1];
    expect(JSON.parse(String(moderation.body))).toEqual({ decision: "rejected", reason: "Harassment", expectedVersion: 4 });
    expect(new Headers(moderation.headers).get("Idempotency-Key")).toBeTruthy();
    expect(JSON.parse(String(calls.find(([url]) => url.endsWith("/suspend"))![1].body))).toEqual({ reason: "Repeated abuse", expectedVersion: -1, until: null });
  });

  it.each(["network failure", "HTTP 408"])("reuses a reader mutation key after an uncertain %s", async (failure) => {
    const { client, fetcher } = fixture();
    fetcher.mockImplementationOnce(async () => new Response(JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "csrf" }), { status: 200 }));
    if (failure === "network failure") fetcher.mockRejectedValueOnce(new TypeError("Network lost"));
    else fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ title: "Request timeout", status: 408, detail: "The mutation outcome is unknown." }), { status: 408, headers: { "Content-Type": "application/problem+json" } }));
    await expect(client.edit(comment, "Changed")).rejects.toThrow();
    await client.edit(comment, "Changed");
    const calls = fetcher.mock.calls as unknown as [string, RequestOptions][];
    const edits = calls.filter(([url]) => url.endsWith("/comments/comment-1"));
    expect(edits).toHaveLength(2);
    expect(new Headers(edits[0][1].headers).get("Idempotency-Key")).toBeTruthy();
    expect(new Headers(edits[0][1].headers).get("Idempotency-Key")).toBe(new Headers(edits[1][1].headers).get("Idempotency-Key"));
  });

  it("owns explicit fake fixtures and enforces fake staff eligibility and stale versions", async () => {
    const profile = { id: "00000000-0000-4000-8000-000000000099", displayName: "Demo editor", email: "demo@example.test", roles: ["EDITOR", "ADMINISTRATOR"] };
    const transport = createApiClient({ mode: "fake" });
    vi.spyOn(transport.auth, "me").mockResolvedValue(profile);
    const client = createModerationApi(transport);
    const item = (await client.queue()).items[0];
    await client.moderate(item, "approved", "Reviewed");
    await expect(client.moderate(item, "spam", "Wrong version")).rejects.toMatchObject({ status: 409 });
    profile.roles = ["READER"];
    await expect(client.queue()).rejects.toBeInstanceOf(ApiError);
  });
});
