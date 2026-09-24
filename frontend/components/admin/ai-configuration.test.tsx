import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { aiAdminApi, type AiConfiguration, type AiPromptVersion, type AiProviderSetup } from "@/lib/ai-admin-api";
import { AiConfigurationWorkspace } from "./ai-configuration";

const configuration: AiConfiguration = {
  version: 4, provider: "openai", model: "gpt-5-mini", promptVersion: "editorial-v1",
  secretReference: "env:AI_API_KEY", changedBy: "admin-1", changedAt: "2026-09-01T12:00:00Z", reason: "Initial deployment"
};
const prompt: AiPromptVersion = {
  revision: 1, version: "editorial-v1", guidance: "Preserve attribution and communicate uncertainty.",
  createdBy: null, createdAt: "2026-09-01T12:00:00Z", reason: "Built-in"
};
const setup: AiProviderSetup = {
  version: 2, draft: { version: 1, provider: "openai", model: "gpt-5-mini", promptVersion: "editorial-v1", timeoutSeconds: 10, maxOutputTokens: 1024, dailyTokenBudget: 500000 },
  active: null, liveActive: false, liveEnabled: true, masterKeyConfigured: true,
  credentialStatus: "CONFIGURED", canActivate: true, activationBlockers: [],
  limits: { timeoutSeconds: 20, maxOutputTokens: 4096, dailyTokenBudget: 1000000 },
  endpoint: "https://api.openai.com/v1/responses", imageProvider: "fake (simulated)", sourceProvider: "fake (simulated)"
};

beforeEach(() => {
  vi.spyOn(aiAdminApi, "configuration").mockResolvedValue(configuration);
  vi.spyOn(aiAdminApi, "providers").mockResolvedValue([{
    id: "openai", displayName: "OpenAI Responses", approvedModels: ["gpt-5-mini", "gpt-5-mini-2025-08-07"],
    capabilities: ["ANALYSIS", "DRAFT", "CONTENT_SAFETY"], secretReference: "env:AI_API_KEY", simulated: false
  }]);
  vi.spyOn(aiAdminApi, "prompts").mockResolvedValue([prompt]);
  vi.spyOn(aiAdminApi, "prompt").mockResolvedValue(prompt);
  vi.spyOn(aiAdminApi, "history").mockResolvedValue([configuration]);
  vi.spyOn(aiAdminApi, "setup").mockResolvedValue(setup);
});
afterEach(() => vi.restoreAllMocks());

describe("AI configuration workspace", () => {
  it("preserves approved model and prompt selection without offering endpoint editing or credential reads", async () => {
    const select = vi.spyOn(aiAdminApi, "select").mockResolvedValue({ ...configuration, version: 5 });
    render(<AiConfigurationWorkspace />);
    await screen.findByLabelText("Approved model");
    expect(screen.getByText(/Human review is mandatory/)).toBeInTheDocument();
    expect(screen.getByText("env:AI_API_KEY", { selector: "code" })).toBeInTheDocument();
    expect(screen.getByLabelText("New OpenAI API key")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("New OpenAI API key")).toHaveValue("");
    expect(screen.queryByLabelText(/Base URL|Secret reference/i)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Approved model"), { target: { value: "gpt-5-mini-2025-08-07" } });
    fireEvent.change(screen.getByLabelText("Configuration change reason"), { target: { value: "Pin reviewed model" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply configuration" }));
    await waitFor(() => expect(select).toHaveBeenCalledWith({
      expectedVersion: 4, provider: "openai", model: "gpt-5-mini-2025-08-07", promptVersion: "editorial-v1", reason: "Pin reviewed model"
    }));
  });

  it("saves bounded runtime settings without activating or invoking a provider", async () => {
    const save = vi.spyOn(aiAdminApi, "saveSetup").mockResolvedValue(setup);
    const activate = vi.spyOn(aiAdminApi, "activateSetup");
    render(<AiConfigurationWorkspace />);
    await screen.findByLabelText("Response deadline (seconds)");
    fireEvent.change(screen.getByLabelText("Response deadline (seconds)"), { target: { value: "15" } });
    fireEvent.change(screen.getByLabelText("Draft settings reason"), { target: { value: "Reviewed bounds" } });
    fireEvent.click(screen.getByRole("button", { name: "Save provider draft" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith({
      expectedVersion: 2, provider: "openai", model: "gpt-5-mini", promptVersion: "editorial-v1",
      timeoutSeconds: 15, maxOutputTokens: 1024, dailyTokenBudget: 500000, reason: "Reviewed bounds"
    }));
    expect(activate).not.toHaveBeenCalled();
  });

  it("offers live setup while the currently selected provider remains fake", async () => {
    vi.spyOn(aiAdminApi, "configuration").mockResolvedValue({ ...configuration, provider: "fake", model: "deterministic-editorial-v1" });
    vi.spyOn(aiAdminApi, "setup").mockResolvedValue({ ...setup, draft: null });
    vi.spyOn(aiAdminApi, "providers").mockResolvedValue([
      { id: "fake", displayName: "Local fake", approvedModels: ["deterministic-editorial-v1"], capabilities: [], secretReference: "none:local-fake", simulated: true },
      { id: "openai", displayName: "OpenAI Responses", approvedModels: ["gpt-5-mini"], capabilities: [], secretReference: "encrypted:ai", simulated: false }
    ]);
    render(<AiConfigurationWorkspace />);
    const provider = await screen.findByLabelText("Draft provider type");
    expect(provider.querySelector('option[value="openai"]')).not.toBeDisabled();
    fireEvent.change(provider, { target: { value: "openai" } });
    expect(screen.getByLabelText("Draft approved model")).toHaveValue("gpt-5-mini");
    expect(screen.getByLabelText("Deployed provider").querySelector('option[value="openai"]')).toBeDisabled();
  });

  it("requires explicit rights and cost acknowledgement for activation", async () => {
    const activate = vi.spyOn(aiAdminApi, "activateSetup").mockResolvedValue(setup);
    render(<AiConfigurationWorkspace />);
    await screen.findByLabelText("Activation reason");
    expect(screen.getByText(/Image provider: fake.*X source provider: fake/)).toBeInTheDocument();
    expect(screen.getByText(/presence only; not a connectivity test/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Activation reason"), { target: { value: "Rights reviewed" } });
    expect(screen.getByRole("button", { name: "Activate saved provider draft" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: /authorized to send permitted source material/ }));
    fireEvent.click(screen.getByRole("button", { name: "Activate saved provider draft" }));
    await waitFor(() => expect(activate).toHaveBeenCalledWith({
      expectedVersion: 2, expectedConfigurationVersion: 4, acknowledgeOutboundDataAndCost: true, reason: "Rights reviewed"
    }));
  });

  it("clears write-only key inputs on submission and reports missing master keys without pretending to activate", async () => {
    const rotate = vi.spyOn(aiAdminApi, "rotateCredential").mockRejectedValue(new ApiError(409, { status: 409, title: "Conflict", detail: "AI provider setup changed; refresh." }));
    render(<AiConfigurationWorkspace />);
    await screen.findByLabelText("New OpenAI API key");
    fireEvent.change(screen.getByLabelText("New OpenAI API key"), { target: { value: "sk-test-only-not-an-external-credential" } });
    fireEvent.change(screen.getByLabelText("Credential change reason"), { target: { value: "Rotate project key" } });
    fireEvent.click(screen.getByRole("button", { name: "Store or rotate API key" }));
    await waitFor(() => expect(rotate).toHaveBeenCalled());
    expect(screen.getByLabelText("New OpenAI API key")).toHaveValue("");
    expect(await screen.findByRole("alert")).toHaveTextContent("refresh");
    expect(screen.queryByText(/Credential encrypted and stored/)).not.toBeInTheDocument();
  });

  it("disables key storage and activation while required operator settings are missing", async () => {
    vi.spyOn(aiAdminApi, "setup").mockResolvedValue({ ...setup, masterKeyConfigured: false, canActivate: false, activationBlockers: ["Operator must configure AI_CREDENTIAL_MASTER_KEY"] });
    render(<AiConfigurationWorkspace />);
    expect(await screen.findByText("Operator must configure AI_CREDENTIAL_MASTER_KEY")).toBeInTheDocument();
    expect(screen.getByLabelText("New OpenAI API key")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Activate saved provider draft" })).toBeDisabled();
  });

  it("creates a new prompt version without silently activating it or modifying historical prompts", async () => {
    const create = vi.spyOn(aiAdminApi, "createPrompt").mockResolvedValue({ ...prompt, revision: 2, version: "editorial-guidance-v2" });
    const select = vi.spyOn(aiAdminApi, "select");
    render(<AiConfigurationWorkspace />);
    await screen.findByLabelText("Editorial guidance");
    fireEvent.change(screen.getByLabelText("Editorial guidance"), { target: { value: "Prefer short paragraphs and neutral wording." } });
    fireEvent.change(screen.getByLabelText("Prompt change reason"), { target: { value: "Style review" } });
    fireEvent.click(screen.getByRole("button", { name: "Create prompt version" }));
    await waitFor(() => expect(create).toHaveBeenCalledWith({
      expectedRevision: 1, guidance: "Prefer short paragraphs and neutral wording.", reason: "Style review"
    }));
    expect(select).not.toHaveBeenCalled();
    expect(screen.getByRole("region", { name: "Immutable prompt history" })).toHaveTextContent(prompt.guidance);
  });

  it("shows conflicts without claiming success and offers explicit refresh", async () => {
    vi.spyOn(aiAdminApi, "select").mockRejectedValue(new ApiError(409, { status: 409, title: "Conflict", detail: "AI configuration changed. Refresh." }));
    render(<AiConfigurationWorkspace />);
    await screen.findByLabelText("Configuration change reason");
    fireEvent.change(screen.getByLabelText("Configuration change reason"), { target: { value: "Style review" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply configuration" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("AI configuration changed");
    expect(screen.queryByText(/successful change/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh configuration" })).toBeEnabled();
  });

  it("renders authorization failures without configuration controls", async () => {
    vi.spyOn(aiAdminApi, "configuration").mockRejectedValue(new ApiError(403, { status: 403, title: "Forbidden", detail: "Administrator access is required." }));
    render(<AiConfigurationWorkspace />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Administrator access");
    expect(screen.queryByRole("button", { name: "Apply configuration" })).not.toBeInTheDocument();
  });

  it("requires the saved-draft activation workflow to replace a retired provider", async () => {
    vi.spyOn(aiAdminApi, "configuration").mockResolvedValue({ ...configuration, provider: "retired", model: "retired-model" });
    const save = vi.spyOn(aiAdminApi, "saveSetup").mockResolvedValue(setup);
    render(<AiConfigurationWorkspace />);
    expect(await screen.findByRole("option", { name: "retired (no longer deployed)" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Apply configuration" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Draft settings reason"), { target: { value: "Replace retired deployment" } });
    fireEvent.click(screen.getByRole("button", { name: "Save provider draft" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ provider: "openai", reason: "Replace retired deployment" })));
  });
});
