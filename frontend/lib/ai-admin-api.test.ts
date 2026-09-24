import { describe, expect, it, vi } from "vitest";
import { api, ApiError, createApiClient } from "./api";
import { createAiAdminApi, type AiSelectionInput } from "./ai-admin-api";

function transport(roles = ["ADMINISTRATOR"]) {
  return { ...api, mode: "fake" as const, auth: { ...api.auth, me: vi.fn().mockResolvedValue({ id: "admin-1", roles }) } };
}

describe("AI administration feature client", () => {
  it("routes production writes through common CSRF/idempotency transport", async () => {
    const requests: { url: string; init?: NonNullable<Parameters<typeof fetch>[1]> }[] = [];
    const client = createApiClient({ mode: "api", baseUrl: "https://news.test", fetch: vi.fn(async (url, init) => {
      requests.push({ url: String(url), init });
      return new Response(JSON.stringify(String(url).endsWith("/csrf")
        ? { headerName: "X-CSRF", token: "csrf-value" } : { version: 1 }), { headers: { "content-type": "application/json" } });
    }) as typeof fetch });
    const ai = createAiAdminApi(client);
    const input = { expectedVersion: 0, provider: "openai", model: "gpt-5-mini", promptVersion: "editorial-v1", reason: "Approved model" };
    await ai.select(input, "ai-selection-key");
    const write = requests.find((value) => value.init?.method === "PUT")!;
    expect(write.url).toBe("https://news.test/api/v1/admin/ai-configuration");
    expect(new Headers(write.init?.headers).get("X-CSRF")).toBe("csrf-value");
    expect(new Headers(write.init?.headers).get("Idempotency-Key")).toBe("ai-selection-key");
    expect(write.init?.credentials).toBe("include");
    expect(JSON.parse(write.init?.body as string)).toEqual(input);
  });

  it("versions prompts without activating them, preserves history and replays exact actor-key receipts", async () => {
    const ai = createAiAdminApi(transport());
    const old = await ai.configuration();
    const original = await ai.prompt(old.promptVersion);
    const promptInput = { expectedRevision: 1, guidance: "Prefer brief paragraphs and explicit attribution.", reason: "Editorial style" };
    const created = await ai.createPrompt(promptInput, "prompt-receipt-key");
    expect(await ai.createPrompt(promptInput, "prompt-receipt-key")).toEqual(created);
    expect(await ai.configuration()).toEqual(old);
    const selection = { expectedVersion: 0, provider: old.provider, model: old.model, promptVersion: created.version, reason: "Activate reviewed guidance" };
    const selected = await ai.select(selection, "selection-receipt-key");
    await ai.select({ ...selection, expectedVersion: 1, promptVersion: original.version }, "another-selection-key");
    expect(await ai.select(selection, "selection-receipt-key")).toEqual(selected);
    expect(await ai.prompt(original.version)).toEqual(original);
    expect((await ai.history()).map((value) => value.version)).toEqual([2, 1]);
    expect(await ai.prompts()).toHaveLength(2);
    await expect(ai.select(selection, "stale-selection-key")).rejects.toMatchObject({ status: 409 });
    await expect(ai.select({ ...selection, reason: "Different request" }, "selection-receipt-key")).rejects.toMatchObject({ status: 409 });
    selected.model = "mutated client result";
    expect((await ai.history())[1].model).toBe(old.model);
  });

  it("denies editors and unknown credentials/providers/network models even in fake mode", async () => {
    const reader = createAiAdminApi(transport(["EDITOR"]));
    await expect(reader.configuration()).rejects.toMatchObject({ status: 403 });
    await expect(reader.createPrompt({ expectedRevision: 1, guidance: "Concise prose.", reason: "Test" })).rejects.toMatchObject({ status: 403 });
    const ai = createAiAdminApi(transport());
    const current = await ai.configuration();
    const valid = { expectedVersion: 0, provider: current.provider, model: current.model, promptVersion: current.promptVersion, reason: "Test" };
    for (const bad of [{ ...valid, provider: "openai" }, { ...valid, model: "https://evil.invalid" },
      { ...valid, apiKey: "secret" }, { ...valid, secretReference: "env:OTHER_KEY" }]) {
      await expect(ai.select(bad as AiSelectionInput)).rejects.toBeInstanceOf(ApiError);
    }
    for (const guidance of ["Visit https://evil.invalid", "Use sk-secretvalue123456", "x".repeat(4001)]) {
      await expect(ai.createPrompt({ expectedRevision: 1, guidance, reason: "Test" })).rejects.toMatchObject({ status: 400 });
    }
    expect((await ai.configuration()).version).toBe(0);
  });

  it("routes secret rotation and activation with CSRF, stable retry identity, and no browser secret storage", async () => {
    const writes: { url: string; init?: NonNullable<Parameters<typeof fetch>[1]> }[] = [];
    let attempts = 0;
    const client = createApiClient({ mode: "api", baseUrl: "https://news.test", fetch: vi.fn(async (url, init) => {
      writes.push({ url: String(url), init });
      if (String(url).endsWith("/csrf")) return Response.json({ headerName: "X-CSRF", token: "csrf-value" });
      if (init?.method === "PUT" && ++attempts === 1) throw new TypeError("Local stub connection lost");
      return Response.json({ version: 1 });
    }) as typeof fetch });
    const ai = createAiAdminApi(client);
    const input = { expectedVersion: 0, apiKey: "sk-test-only-not-an-external-credential", reason: "Reviewed project" };
    await expect(ai.rotateCredential(input)).rejects.toThrow("connection lost");
    await ai.rotateCredential(input);
    const rotations = writes.filter((value) => value.init?.method === "PUT");
    expect(new Headers(rotations[0].init?.headers).get("Idempotency-Key"))
      .toEqual(new Headers(rotations[1].init?.headers).get("Idempotency-Key"));
    expect(new Headers(rotations[1].init?.headers).get("X-CSRF")).toBe("csrf-value");
    expect(rotations[1].url).toBe("https://news.test/api/v1/admin/ai-configuration/setup/credential");
    await ai.activateSetup({ expectedVersion: 1, expectedConfigurationVersion: 0, acknowledgeOutboundDataAndCost: true, reason: "Rights and costs approved" }, "activation-key");
    expect(writes.at(-1)?.url).toBe("https://news.test/api/v1/admin/ai-configuration/setup/activate");
    expect(new Headers(writes.at(-1)?.init?.headers).get("Idempotency-Key")).toBe("activation-key");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("makes fake setup explicit and never stores credentials or claims live activation", async () => {
    const ai = createAiAdminApi(transport());
    expect((await ai.setup()).liveEnabled).toBe(false);
    await expect(ai.rotateCredential({ expectedVersion: 0, apiKey: "sk-test-only-not-an-external-credential", reason: "No live backend" }))
      .rejects.toMatchObject({ status: 409 });
    expect((await ai.setup()).credentialStatus).toBe("NOT_CONFIGURED");
    const current = await ai.configuration();
    await ai.saveSetup({ expectedVersion: 0, provider: current.provider, model: current.model, promptVersion: current.promptVersion,
      timeoutSeconds: 10, maxOutputTokens: 1024, dailyTokenBudget: 500000, reason: "Demo only" });
    expect(await ai.configuration()).toEqual(current);
    await ai.activateSetup({ expectedVersion: 1, expectedConfigurationVersion: 0, acknowledgeOutboundDataAndCost: false, reason: "Activate simulation" });
    expect((await ai.configuration()).provider).toBe("fake");
    expect((await ai.setup()).liveActive).toBe(false);
  });
});
