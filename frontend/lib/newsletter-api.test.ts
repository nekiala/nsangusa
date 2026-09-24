import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";
import { newsletterApi } from "./newsletter-api";

afterEach(() => vi.restoreAllMocks());

describe("newsletter production API transport", () => {
  it("uses shared session/CSRF and durable mutation transport without fixtures", async () => {
    const request = vi.spyOn(api, "request").mockResolvedValue(undefined);
    await newsletterApi.updatePreferences("subscription", "private", "daily");
    expect(request).toHaveBeenCalledWith("/api/v1/newsletter/preferences?id=subscription&token=private",
      { method: "POST", body: JSON.stringify({ frequency: "daily" }), headers: { "Idempotency-Key": expect.any(String) } }, true, expect.any(String));
    await newsletterApi.reconcile("delivery", { outcome: "abandoned", evidenceReference: "ticket:1", providerMessageId: null });
    expect(request).toHaveBeenLastCalledWith("/api/v1/newsletter/admin/deliveries/delivery/reconciliation",
      { method: "POST", body: JSON.stringify({ outcome: "abandoned", evidenceReference: "ticket:1", providerMessageId: null }),
        headers: { "Idempotency-Key": expect.any(String) } }, true, expect.any(String));
  });

  it("keeps the same mutation identity after an unconfirmed transport failure", async () => {
    const request = vi.spyOn(api, "request").mockRejectedValueOnce(new Error("Response lost")).mockResolvedValueOnce(undefined);
    await expect(newsletterApi.updatePreferences("retry-subscription", "private", "weekly")).rejects.toThrow("Response lost");
    await newsletterApi.updatePreferences("retry-subscription", "private", "weekly");
    expect(request.mock.calls[0][3]).toBe(request.mock.calls[1][3]);
  });

  it("does not substitute fake records for failed admin or preference responses", async () => {
    const failure = new ApiError(503, { status: 503, title: "Unavailable", detail: "Backend unavailable" });
    vi.spyOn(api, "request").mockRejectedValue(failure);
    await expect(newsletterApi.subscriptions()).rejects.toBe(failure);
    await expect(newsletterApi.preferences("subscription", "token")).rejects.toBe(failure);
    await expect(newsletterApi.requestLink("reader@example.test")).rejects.toBe(failure);
  });
});
