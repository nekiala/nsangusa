import { api, ApiError } from "./api";

export type AiProvider = {
  id: string; displayName: string; capabilities: string[]; approvedModels: string[];
  secretReference: string; simulated: boolean;
};
export type AiConfiguration = {
  version: number; provider: string; model: string; promptVersion: string; secretReference: string;
  changedBy: string | null; changedAt: string | null; reason: string;
};
export type AiPromptVersion = {
  revision: number; version: string; guidance: string; createdBy: string | null; createdAt: string; reason: string;
};
export type AiSelectionInput = {
  expectedVersion: number; provider: string; model: string; promptVersion: string; reason: string;
};
export type AiPromptInput = { expectedRevision: number; guidance: string; reason: string };
export type AiProviderSettings = {
  version: number; provider: string; model: string; promptVersion: string;
  timeoutSeconds: number; maxOutputTokens: number; dailyTokenBudget: number;
};
export type AiProviderSetup = {
  version: number; draft: AiProviderSettings | null; active: AiProviderSettings | null;
  liveActive: boolean; liveEnabled: boolean; masterKeyConfigured: boolean;
  credentialStatus: "NOT_CONFIGURED" | "CONFIGURED" | "MASTER_KEY_UNAVAILABLE" | "UNREADABLE";
  canActivate: boolean; activationBlockers: string[];
  limits: { timeoutSeconds: number; maxOutputTokens: number; dailyTokenBudget: number };
  endpoint: string; imageProvider: string; sourceProvider: string;
};
export type AiSetupInput = Omit<AiProviderSettings, "version"> & { expectedVersion: number; reason: string };
export type AiCredentialInput = { expectedVersion: number; apiKey: string; reason: string };
export type AiActivationInput = {
  expectedVersion: number; expectedConfigurationVersion: number; acknowledgeOutboundDataAndCost: boolean; reason: string;
};

type Transport = Pick<typeof api, "mode" | "request" | "auth">;
const root = "/api/v1/admin/ai-configuration";
const copy = <T>(value: T): T => structuredClone(value);
const hasControls = (value: string, multiline = false) => Array.from(value).some((character) => {
  const code = character.charCodeAt(0);
  return (code < 32 || (code >= 127 && code <= 159)) && !(multiline && (code === 9 || code === 10));
});

function fail(status: number, detail: string): never {
  throw new ApiError(status, { status, title: status === 409 ? "Conflict" : "AI configuration request rejected", detail });
}

export function createAiAdminApi(client: Transport = api) {
  const provider: AiProvider = {
    id: "fake", displayName: "Deterministic local provider", capabilities: ["ANALYSIS", "DRAFT", "CONTENT_SAFETY"],
    approvedModels: ["deterministic-editorial-v1"], secretReference: "none:local-fake", simulated: true
  };
  let current: AiConfiguration = {
    version: 0, provider: provider.id, model: provider.approvedModels[0], promptVersion: "editorial-v1",
    secretReference: provider.secretReference, changedBy: null, changedAt: null, reason: "Operator-deployed default"
  };
  const versions: AiConfiguration[] = [];
  const prompts: AiPromptVersion[] = [{
    revision: 1, version: "editorial-v1",
    guidance: "Use concise, neutral language. Attribute reported claims and make uncertainty explicit.",
    createdBy: null, createdAt: "2026-09-01T00:00:00Z", reason: "Built-in editorial guidance"
  }];
  const receipts = new Map<string, { fingerprint: string; result: unknown }>();
  let setup: AiProviderSetup = {
    version: 0, draft: null, active: null, liveActive: false, liveEnabled: false, masterKeyConfigured: false,
    credentialStatus: "NOT_CONFIGURED", canActivate: false,
    activationBlockers: ["Browser demo cannot store credentials or activate live AI. Connect to the backend."],
    limits: { timeoutSeconds: 20, maxOutputTokens: 4096, dailyTokenBudget: 1000000 },
    endpoint: "https://api.openai.com/v1/responses", imageProvider: "fake (browser simulation)", sourceProvider: "fake (browser simulation)"
  };

  async function administrator() {
    const actor = await client.auth.me();
    if (!actor.roles.includes("ADMINISTRATOR")) fail(403, "Administrator access is required.");
    return actor;
  }
  async function read<T>(work: () => T): Promise<T> {
    await administrator();
    return copy(work());
  }
  async function mutate<T>(operation: string, input: object, key: string | undefined, work: (_actor: string) => T): Promise<T> {
    const actor = await administrator();
    const requestKey = key ?? crypto.randomUUID();
    if (!/^[!-~]{8,200}$/.test(requestKey)) fail(400, "A valid idempotency key is required.");
    const receiptKey = `${actor.id}:${requestKey}`;
    const fingerprint = JSON.stringify({ operation, input });
    const receipt = receipts.get(receiptKey);
    if (receipt) {
      if (receipt.fingerprint !== fingerprint) fail(409, "This idempotency key was used for another request.");
      return copy(receipt.result as T);
    }
    const result = work(actor.id);
    receipts.set(receiptKey, { fingerprint, result: copy(result) });
    return copy(result);
  }
  function page<T>(items: T[], page: number, size: number) {
    if (!Number.isSafeInteger(page) || page < 0 || page > 1_000_000 || !Number.isSafeInteger(size) || size < 1 || size > 100) fail(400, "Invalid page bounds.");
    return items.slice(page * size, (page + 1) * size);
  }
  function prompt(version: string) {
    const result = prompts.find((value) => value.version === version);
    if (!result) fail(404, "Prompt version not found.");
    return result;
  }
  function fields(input: object, allowed: string[]) {
    if (Object.keys(input).some((key) => !allowed.includes(key))) fail(400, "Unknown AI administration field.");
  }
  function reason(value: string) {
    if (typeof value !== "string" || !value.trim() || value.length > 500 || hasControls(value)
      || /\bsk-[a-z0-9_-]{8,}|-----BEGIN|\bBearer\s+/iu.test(value)) fail(400, "An audit reason of 1–500 plain-text characters without credentials is required.");
  }

  return {
    setup: () => client.mode === "fake" ? read(() => setup) : client.request<AiProviderSetup>(`${root}/setup`),
    saveSetup: (input: AiSetupInput, key?: string) => client.mode === "fake"
      ? mutate("setup", input, key, () => {
        fields(input, ["expectedVersion", "provider", "model", "promptVersion", "timeoutSeconds", "maxOutputTokens", "dailyTokenBudget", "reason"]);
        reason(input.reason);
        if (input.expectedVersion !== setup.version) fail(409, "AI provider setup has changed; refresh before saving.");
        if (input.provider !== "fake" || input.model !== provider.approvedModels[0]) fail(400, "Connect to the backend to configure a live provider.");
        prompt(input.promptVersion);
        if (!Number.isInteger(input.timeoutSeconds) || input.timeoutSeconds < 1 || input.timeoutSeconds > setup.limits.timeoutSeconds
          || !Number.isInteger(input.maxOutputTokens) || input.maxOutputTokens < 256 || input.maxOutputTokens > setup.limits.maxOutputTokens
          || !Number.isInteger(input.dailyTokenBudget) || input.dailyTokenBudget < 1 || input.dailyTokenBudget > setup.limits.dailyTokenBudget) fail(400, "Settings exceed operator bounds.");
        const { expectedVersion, provider: providerId, model, promptVersion, timeoutSeconds, maxOutputTokens, dailyTokenBudget } = input;
        setup = { ...setup, version: expectedVersion + 1,
          draft: { provider: providerId, model, promptVersion, timeoutSeconds, maxOutputTokens, dailyTokenBudget, version: expectedVersion + 1 },
          canActivate: true, activationBlockers: [] };
        return setup;
      })
      : client.request<AiProviderSetup>(`${root}/setup`, { method: "PUT", body: JSON.stringify(input) }, true, key),
    rotateCredential: async (input: AiCredentialInput, key?: string) => {
      if (client.mode === "fake") {
        await administrator();
        return fail(409, "Browser demo never stores API keys. Connect to the backend.");
      }
      return client.request<AiProviderSetup>(`${root}/setup/credential`, { method: "PUT", body: JSON.stringify(input) }, true, key);
    },
    removeCredential: async (input: { expectedVersion: number; reason: string }, key?: string) => {
      if (client.mode === "fake") {
        await administrator();
        return fail(409, "Browser demo has no stored credentials.");
      }
      return client.request<AiProviderSetup>(`${root}/setup/credential`, { method: "DELETE", body: JSON.stringify(input) }, true, key);
    },
    activateSetup: (input: AiActivationInput, key?: string) => client.mode === "fake"
      ? mutate("activate-setup", input, key, (actor) => {
        fields(input, ["expectedVersion", "expectedConfigurationVersion", "acknowledgeOutboundDataAndCost", "reason"]);
        reason(input.reason);
        if (input.expectedVersion !== setup.version || input.expectedConfigurationVersion !== current.version) fail(409, "AI configuration changed; refresh before activation.");
        if (!setup.draft || setup.draft.provider !== "fake") fail(409, "Browser demo cannot activate live AI.");
        current = { ...current, version: current.version + 1, model: setup.draft.model, promptVersion: setup.draft.promptVersion,
          changedBy: actor, changedAt: new Date().toISOString(), reason: input.reason.trim() };
        versions.unshift(copy(current));
        setup = { ...setup, version: setup.version + 1, active: setup.draft };
        return setup;
      })
      : client.request<AiProviderSetup>(`${root}/setup/activate`, { method: "POST", body: JSON.stringify(input) }, true, key),
    configuration: () => client.mode === "fake" ? read(() => current) : client.request<AiConfiguration>(root),
    providers: () => client.mode === "fake" ? read(() => [provider]) : client.request<AiProvider[]>(`${root}/providers`),
    history: (index = 0, size = 20) => client.mode === "fake"
      ? read(() => page(versions, index, size))
      : client.request<AiConfiguration[]>(`${root}/history?page=${index}&size=${size}`),
    prompts: (index = 0, size = 20) => client.mode === "fake"
      ? read(() => page(prompts, index, size))
      : client.request<AiPromptVersion[]>(`${root}/prompts?page=${index}&size=${size}`),
    prompt: (version: string) => client.mode === "fake"
      ? read(() => prompt(version))
      : client.request<AiPromptVersion>(`${root}/prompts/${encodeURIComponent(version)}`),
    select: (input: AiSelectionInput, key?: string) => client.mode === "fake"
      ? mutate("select", input, key, (actor) => {
        fields(input, ["expectedVersion", "provider", "model", "promptVersion", "reason"]);
        reason(input.reason);
        if (input.provider !== provider.id || !provider.approvedModels.includes(input.model)) fail(400, "Select a deployed provider and an operator-approved model.");
        prompt(input.promptVersion);
        if (input.expectedVersion !== current.version) fail(409, "AI configuration has changed; refresh before saving.");
        current = { version: current.version + 1, provider: input.provider, model: input.model, promptVersion: input.promptVersion,
          secretReference: provider.secretReference, changedBy: actor, changedAt: new Date().toISOString(), reason: input.reason.trim() };
        versions.unshift(copy(current));
        return current;
      })
      : client.request<AiConfiguration>(root, { method: "PUT", body: JSON.stringify(input) }, true, key),
    createPrompt: (input: AiPromptInput, key?: string) => client.mode === "fake"
      ? mutate("prompt", input, key, (actor) => {
        fields(input, ["expectedRevision", "guidance", "reason"]);
        reason(input.reason);
        if (typeof input.guidance !== "string" || input.guidance.trim().length < 10 || input.guidance.length > 4000
          || hasControls(input.guidance, true)
          || /https?:\/\/|\bsk-[a-z0-9_-]{8,}|-----BEGIN|\bBearer\s+/iu.test(input.guidance)) fail(400, "Guidance must be 10–4000 plain-text characters without URLs or credentials.");
        if (input.expectedRevision !== prompts[0].revision) fail(409, "Prompt registry has changed; refresh before saving.");
        const revision = prompts[0].revision + 1;
        const result = { revision, version: `editorial-guidance-v${revision}`, guidance: input.guidance.trim(),
          createdBy: actor, createdAt: new Date().toISOString(), reason: input.reason.trim() };
        prompts.unshift(result);
        return result;
      })
      : client.request<AiPromptVersion>(`${root}/prompts`, { method: "POST", body: JSON.stringify(input) }, true, key)
  };
}

export const aiAdminApi = createAiAdminApi();
