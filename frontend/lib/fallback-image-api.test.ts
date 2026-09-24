import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./api";
import { article } from "@/test/editorial-fixtures";

describe("explicit fallback image selection", () => {
  it("sends the editor reason and alt text with CSRF and a stable action identity", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ headerName: "X-XSRF-TOKEN", token: "csrf" }))
      .mockResolvedValueOnce(Response.json({ eventId: "event-1" }, { status: 202 }));
    const api = createApiClient({ mode: "api", baseUrl: "http://localhost", fetch: fetcher });
    expect(await api.admin.requestFallbackImage("article-1", "A neutral newspaper illustration", "Provider is unavailable", 3, "fallback-key")).toEqual({ eventId: "event-1" });
    const [url, request] = fetcher.mock.calls[1];
    expect(url).toBe("http://localhost/api/v1/admin/articles/article-1/images/fallback");
    expect(JSON.parse(String(request?.body))).toEqual({ altText: "A neutral newspaper illustration", reason: "Provider is unavailable", expectedVersion: 3 });
    expect(new Headers(request?.headers).get("X-XSRF-TOKEN")).toBe("csrf");
    expect(new Headers(request?.headers).get("Idempotency-Key")).toBe("fallback-key");
  });

  it("keeps fallback selection separate from approval and discloses non-AI provenance", async () => {
    const api = createApiClient({ mode: "fake" });
    const created = await api.admin.createArticle({ ...article, slugSuggestion: "explicit-fallback" });
    const current = await api.admin.article(created.id);
    const requested = await api.admin.requestFallbackImage(created.id, "A neutral illustration", "Editorial choice", current.version);
    const before = await api.admin.article(created.id);
    expect(before.approvedImageGenerationId).toBeNull();
    const candidates = await api.admin.images(created.id);
    expect(candidates[0]).toMatchObject({ id: requested.eventId, provider: "nsangusa-editorial", safetyStatus: "review_required", approvedAt: null });
    await api.admin.approveImage(candidates[0].id);
    expect(await api.admin.article(created.id)).toMatchObject({ approvedImageGenerationId: candidates[0].id, generatedImage: false });
  });
});
