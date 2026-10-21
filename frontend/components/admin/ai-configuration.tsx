"use client";

import { tx } from "@/lib/i18n/staff";
import { useCallback, useState } from "react";
import { aiAdminApi, type AiConfiguration, type AiPromptVersion, type AiProvider, type AiProviderSetup } from "@/lib/ai-admin-api";
import { AdminSection, dateLabel, Feedback, useAction, useResource } from "./shared";

type Loaded = {
  configuration: AiConfiguration; providers: AiProvider[]; prompts: AiPromptVersion[];
  currentPrompt: AiPromptVersion; latestRevision: number;
  setup: AiProviderSetup;
};

function ProviderSetupEditor({ data, locked, onChange }: { data: Loaded; locked: boolean; onChange: () => void }) {
  const action = useAction();
  const setup = data.setup;
  const initial = setup.draft ?? setup.active;
  const [provider, setProvider] = useState(initial?.provider ?? data.configuration.provider);
  const available = data.providers.find((value) => value.id === provider);
  const [model, setModel] = useState(initial?.model ?? data.configuration.model);
  const [promptVersion, setPromptVersion] = useState(initial?.promptVersion ?? data.configuration.promptVersion);
  const [timeoutSeconds, setTimeout] = useState(initial?.timeoutSeconds ?? setup.limits.timeoutSeconds);
  const [maxOutputTokens, setOutput] = useState(initial?.maxOutputTokens ?? setup.limits.maxOutputTokens);
  const [dailyTokenBudget, setBudget] = useState(initial?.dailyTokenBudget ?? setup.limits.dailyTokenBudget);
  const [reason, setReason] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [credentialReason, setCredentialReason] = useState("");
  const [activationReason, setActivationReason] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);
  const disabled = locked || action.busy;
  const liveDraft = setup.draft?.provider === "openai";
  const promptOptions = data.prompts.some((value) => value.version === data.currentPrompt.version)
    ? data.prompts : [data.currentPrompt, ...data.prompts];
  return <section className="review-panel" aria-label={tx("Provider setup and activation")}>
    <h2>{tx("Provider setup and activation")}</h2>
    <p>Setup version {setup.version}. {setup.liveActive ? tx("Live OpenAI activated") : tx("Live OpenAI inactive")}.
      {" "}Credential: {setup.credentialStatus.replaceAll("_", " ").toLowerCase()} (presence only; not a connectivity test).</p>
    <p>Operator live gate: {setup.liveEnabled ? "enabled" : "disabled"}.
      {" "}Credential encryption: {setup.masterKeyConfigured ? "available" : "master key unavailable"}.</p>
    <p>Image provider: {setup.imageProvider}. X source provider: {setup.sourceProvider}. Text AI activation does not enable either provider.</p>
    <p>{tx("Approved endpoint:")} <code>{setup.endpoint}</code>. URLs cannot be edited here. Saving settings or keys makes no provider calls.</p>
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault();
      if (disabled) return;
      action.run(async () => {
        await aiAdminApi.saveSetup({ expectedVersion: setup.version, provider, model, promptVersion, timeoutSeconds, maxOutputTokens, dailyTokenBudget, reason });
        onChange();
      }, tx("Provider draft saved. The active provider is unchanged; activate separately."));
    }}>
      <label htmlFor="ai-setup-provider">{tx("Draft provider type")}</label>
      <select id="ai-setup-provider" disabled={disabled} value={provider} onChange={(event) => {
        setProvider(event.target.value);
        setModel(data.providers.find((value) => value.id === event.target.value)?.approvedModels[0] ?? "");
      }}>
        {!available && <option value={provider} disabled>{provider} (unavailable)</option>}
        {data.providers.map((value) => <option key={value.id} value={value.id}>{value.displayName}{value.simulated ? " (simulated)" : ""}</option>)}
      </select>
      <label htmlFor="ai-setup-model">{tx("Draft approved model")}</label>
      <select id="ai-setup-model" disabled={disabled} value={model} onChange={(event) => setModel(event.target.value)}>
        {!available?.approvedModels.includes(model) && <option value={model} disabled>{model} (unavailable)</option>}
        {available?.approvedModels.map((value) => <option key={value}>{value}</option>)}
      </select>
      <label htmlFor="ai-setup-prompt">{tx("Draft prompt version")}</label>
      <select id="ai-setup-prompt" disabled={disabled} value={promptVersion} onChange={(event) => setPromptVersion(event.target.value)}>
        {!promptOptions.some((value) => value.version === promptVersion) && <option value={promptVersion}>{promptVersion}</option>}
        {promptOptions.map((value) => <option key={value.version}>{value.version}</option>)}
      </select>
      <label htmlFor="ai-timeout">{tx("Response deadline (seconds)")}</label>
      <input id="ai-timeout" type="number" required min={1} max={setup.limits.timeoutSeconds} value={timeoutSeconds} disabled={disabled} onChange={(event) => setTimeout(Number(event.target.value))} />
      <label htmlFor="ai-output-tokens">{tx("Maximum output tokens per call")}</label>
      <input id="ai-output-tokens" type="number" required min={256} max={setup.limits.maxOutputTokens} value={maxOutputTokens} disabled={disabled} onChange={(event) => setOutput(Number(event.target.value))} />
      <label htmlFor="ai-daily-budget">{tx("Daily token budget (UTC, shared across live requests)")}</label>
      <input id="ai-daily-budget" type="number" required min={1} max={setup.limits.dailyTokenBudget} value={dailyTokenBudget} disabled={disabled} onChange={(event) => setBudget(Number(event.target.value))} />
      <p>Token budgets are conservative admission limits, not a currency spend guarantee. Retries may incur costs; configure OpenAI project spending limits too.</p>
      <label htmlFor="ai-setup-reason">{tx("Draft settings reason")}</label>
      <input id="ai-setup-reason" required maxLength={500} value={reason} disabled={disabled} onChange={(event) => setReason(event.target.value)} />
      <button disabled={disabled || !reason.trim() || !available?.approvedModels.includes(model)}>{tx("Save provider draft")}</button>
    </form>
    <form className="admin-form" autoComplete="off" onSubmit={(event) => {
      event.preventDefault();
      if (disabled || !setup.masterKeyConfigured) return;
      const value = apiKey;
      setApiKey("");
      action.run(async () => {
        await aiAdminApi.rotateCredential({ expectedVersion: setup.version, apiKey: value, reason: credentialReason });
        onChange();
      }, tx("Credential encrypted and stored. Live AI is inactive until explicit activation."));
    }}>
      <h3>{tx("Write-only credential")}</h3>
      <p>{tx("Use a restricted OpenAI project key. The key is never returned. Rotation or removal immediately blocks subsequent live calls until activation. Never put credentials in prompts or reasons.")}</p>
      <label htmlFor="ai-api-key">{tx("New OpenAI API key")}</label>
      <input id="ai-api-key" type="password" autoComplete="new-password" spellCheck={false} minLength={20} maxLength={512}
        value={apiKey} disabled={disabled || !setup.masterKeyConfigured} onChange={(event) => setApiKey(event.target.value)} />
      <label htmlFor="ai-credential-reason">{tx("Credential change reason")}</label>
      <input id="ai-credential-reason" required maxLength={500} value={credentialReason} disabled={disabled} onChange={(event) => setCredentialReason(event.target.value)} />
      <button disabled={disabled || !setup.masterKeyConfigured || apiKey.length < 20 || !credentialReason.trim()}>{tx("Store or rotate API key")}</button>
      <button type="button" disabled={disabled || setup.credentialStatus === "NOT_CONFIGURED" || !credentialReason.trim()} onClick={() => {
        setApiKey("");
        action.run(async () => {
          await aiAdminApi.removeCredential({ expectedVersion: setup.version, reason: credentialReason });
          onChange();
        }, tx("Credential removed. Live AI is blocked; revoke the old key in OpenAI as well."));
      }}>{tx("Remove stored API key")}</button>
    </form>
    <form className="admin-form" onSubmit={(event) => {
      event.preventDefault();
      if (disabled || !setup.canActivate || (liveDraft && !acknowledged)) return;
      action.run(async () => {
        await aiAdminApi.activateSetup({ expectedVersion: setup.version, expectedConfigurationVersion: data.configuration.version,
          acknowledgeOutboundDataAndCost: acknowledged, reason: activationReason });
        onChange();
      }, tx("Saved provider draft activated for new requests. No connectivity or generation call was made."));
    }}>
      <h3>{tx("Deliberate activation")}</h3>
      <p>Saved draft: {setup.draft ? `${setup.draft.provider} / ${setup.draft.model}` : "none"}. Unsaved form edits are not activated.</p>
      {!!setup.activationBlockers.length && <ul>{setup.activationBlockers.map((blocker) => <li key={blocker}>{blocker}</li>)}</ul>}
      <label><input type="checkbox" checked={acknowledged} disabled={disabled} onChange={(event) => setAcknowledged(event.target.checked)} />
        I am authorized to send permitted source material to OpenAI, have reviewed data rights and privacy, and accept provider charges and retry costs.</label>
      <label htmlFor="ai-activation-reason">{tx("Activation reason")}</label>
      <input id="ai-activation-reason" required maxLength={500} value={activationReason} disabled={disabled} onChange={(event) => setActivationReason(event.target.value)} />
      <button disabled={disabled || !setup.canActivate || !activationReason.trim() || (liveDraft && !acknowledged)}>{tx("Activate saved provider draft")}</button>
    </form>
    <Feedback error={action.error} status={action.status} />
    {action.error && <p>Refresh to reconcile the outcome before retrying. Re-enter a credential only if rotation is still needed; the input is cleared on every submission.</p>}
  </section>;
}

function ConfigurationEditor({ data, locked, onChange }: { data: Loaded; locked: boolean; onChange: () => void }) {
  const selection = useAction();
  const promptAction = useAction();
  const [providerId, setProviderId] = useState(data.configuration.provider);
  const provider = data.providers.find((value) => value.id === providerId);
  const [model, setModel] = useState(data.configuration.model);
  const [promptVersion, setPromptVersion] = useState(data.configuration.promptVersion);
  const [reason, setReason] = useState("");
  const [guidance, setGuidance] = useState(data.currentPrompt.guidance);
  const [promptReason, setPromptReason] = useState("");
  const prompts = data.prompts.some((value) => value.version === data.currentPrompt.version)
    ? data.prompts : [data.currentPrompt, ...data.prompts];
  const selectedPrompt = prompts.find((value) => value.version === promptVersion);
  const disabled = locked || selection.busy || promptAction.busy;
  return <>
    <section className="review-panel" aria-label={tx("Active generation configuration")}>
      <h2>{tx("Active generation configuration")}</h2>
      <p>Configuration version {data.configuration.version} · Changed by {data.configuration.changedBy ?? "deployment"} · {dateLabel(data.configuration.changedAt)}</p>
      <form className="admin-form" onSubmit={(event) => {
        event.preventDefault();
        if (disabled) return;
        selection.run(async () => {
          await aiAdminApi.select({ expectedVersion: data.configuration.version, provider: providerId, model, promptVersion, reason });
          onChange();
        }, tx("Configuration selected for new AI requests."));
      }}>
        <label htmlFor="ai-provider">{tx("Deployed provider")}</label>
        <select id="ai-provider" value={providerId} disabled={disabled} onChange={(event) => {
          setProviderId(event.target.value);
          setModel(data.providers.find((value) => value.id === event.target.value)?.approvedModels[0] ?? "");
        }}>
          {!provider && <option value={providerId} disabled>{providerId} (no longer deployed)</option>}
          {data.providers.map((value) => <option key={value.id} value={value.id} disabled={value.id !== data.configuration.provider}>{value.displayName}{value.simulated ? " (simulated)" : ""}</option>)}
        </select>
        <p>Capabilities: {provider?.capabilities.join(", ")}. One selection applies to analysis, drafting, and the independent content-safety gate. Change provider type using the saved-draft activation workflow, not this selector.</p>
        <p>{tx("Secret reference:")} <code>{provider?.secretReference}</code>. OpenAI must first be set up and explicitly activated below. This selector preserves the activated runtime limits.</p>
        <label htmlFor="ai-model">{tx("Approved model")}</label>
        <select id="ai-model" value={model} disabled={disabled} onChange={(event) => setModel(event.target.value)}>
          {!provider?.approvedModels.includes(model) && <option value={model} disabled>{model} (no longer authorized)</option>}
          {provider?.approvedModels.map((value) => <option key={value}>{value}</option>)}
        </select>
        <label htmlFor="ai-prompt-version">{tx("Prompt version")}</label>
        <select id="ai-prompt-version" value={promptVersion} disabled={disabled} onChange={(event) => setPromptVersion(event.target.value)}>
          {prompts.map((value) => <option key={value.version}>{value.version}</option>)}
        </select>
        <p className="preserve-lines">{selectedPrompt?.guidance}</p>
        <label htmlFor="ai-selection-reason">{tx("Configuration change reason")}</label>
        <input id="ai-selection-reason" value={reason} required maxLength={500} disabled={disabled} onChange={(event) => setReason(event.target.value)} />
        <button disabled={disabled || !reason.trim() || !provider?.approvedModels.includes(model)}>{tx("Apply configuration")}</button>
      </form>
      <Feedback error={selection.error} status={selection.status} />
      {selection.error && <p>{tx("Refresh configuration to reconcile changes before editing a stale version. Retrying an unchanged request preserves its request identity.")}</p>}
    </section>
    <section className="review-panel" aria-label={tx("Create editorial prompt version")}>
      <h2>{tx("Create editorial prompt version")}</h2>
      <p>Guidance may change tone, structure, and clarity only. No URLs or credentials. Safety decisions never use this editable guidance. Saving creates an immutable version; select it above to activate it.</p>
      <form className="admin-form" onSubmit={(event) => {
        event.preventDefault();
        if (disabled) return;
        promptAction.run(async () => {
          await aiAdminApi.createPrompt({ expectedRevision: data.latestRevision, guidance, reason: promptReason });
          onChange();
        }, tx("Prompt version created. Select it explicitly to activate it."));
      }}>
        <label htmlFor="ai-guidance">{tx("Editorial guidance")}</label>
        <textarea id="ai-guidance" value={guidance} required minLength={10} maxLength={4000} rows={6} disabled={disabled} onChange={(event) => setGuidance(event.target.value)} />
        <label htmlFor="ai-prompt-reason">{tx("Prompt change reason")}</label>
        <input id="ai-prompt-reason" value={promptReason} required maxLength={500} disabled={disabled} onChange={(event) => setPromptReason(event.target.value)} />
        <button disabled={disabled || guidance.trim().length < 10 || !promptReason.trim()}>{tx("Create prompt version")}</button>
      </form>
      <Feedback error={promptAction.error} status={promptAction.status} />
    </section>
  </>;
}

export function AiConfigurationWorkspace() {
  const [promptPage, setPromptPage] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [notice, setNotice] = useState("");
  const load = useCallback(async () => {
    const [configuration, providers, prompts, latest, history, setup] = await Promise.all([
      aiAdminApi.configuration(), aiAdminApi.providers(), aiAdminApi.prompts(promptPage),
      aiAdminApi.prompts(0, 1), aiAdminApi.history(historyPage), aiAdminApi.setup()
    ]);
    const currentPrompt = prompts.find((value) => value.version === configuration.promptVersion)
      ?? await aiAdminApi.prompt(configuration.promptVersion);
    return { configuration, providers, prompts, currentPrompt, latestRevision: latest[0].revision, history, setup };
  }, [promptPage, historyPage]);
  const resource = useResource(load);
  const refresh = () => { setNotice(tx("Configuration refreshed after successful change. Existing drafts and approvals are unchanged.")); resource.refresh(); };
  return <AdminSection title={tx("AI configuration")} description={tx("Administrator-only, audited selection of deployed providers, approved models, and immutable editorial guidance.")}>
    <p className="notice">Human review is mandatory. System safety, publication rules, schema bounds, operator budget ceilings, and network restrictions remain enforced. Saved drafts do not activate themselves. Credentials and the operator gate can block queued calls; stored drafts and approved articles are unchanged.</p>
    <p>Image generation remains independently configured by operators; this workspace does not change approved images or image models.</p>
    <button disabled={resource.loading} onClick={resource.refresh}>{tx("Refresh configuration")}</button>
    <Feedback error={resource.error} status={notice} />
    {resource.loading && <p role="status">{tx("Loading AI configuration…")}</p>}
    {resource.data && <>
      {resource.data.configuration.provider === "fake" && <p className="notice">{tx("Simulation only: the deterministic provider records selected prompt versions but does not generate prose from guidance.")}</p>}
      <ProviderSetupEditor key={`setup-${resource.data.setup.version}-${resource.data.configuration.version}`}
        data={resource.data} locked={resource.loading || !!resource.error} onChange={refresh} />
      <ConfigurationEditor key={`${resource.data.configuration.version}-${resource.data.latestRevision}-${promptPage}`}
        data={resource.data} locked={resource.loading || !!resource.error} onChange={refresh} />
      <section aria-label={tx("Immutable prompt history")}>
        <h2>{tx("Immutable prompt history")}</h2>
        <ul className="queue-list">{resource.data.prompts.map((prompt) => <li key={prompt.version}>
          <details><summary>{prompt.version} · {dateLabel(prompt.createdAt)}</summary>
            <p className="preserve-lines">{prompt.guidance}</p>
            <p>Created by {prompt.createdBy ?? "deployment"} · {prompt.reason}</p>
          </details>
        </li>)}</ul>
        <nav aria-label={tx("Prompt history pages")}>
          <button disabled={resource.loading || promptPage === 0} onClick={() => setPromptPage(promptPage - 1)}>{tx("Previous prompt versions")}</button>
          <span>Page {promptPage + 1}</span>
          <button disabled={resource.loading || resource.data.prompts.length < 20} onClick={() => setPromptPage(promptPage + 1)}>{tx("Older prompt versions")}</button>
        </nav>
      </section>
      <section aria-label={tx("Configuration audit history")}>
        <h2>{tx("Configuration audit history")}</h2>
        {!resource.data.history.length && <p>{tx("No administrator selections yet.")}</p>}
        <ul className="queue-list">{resource.data.history.map((configuration) => <li key={configuration.version}>
          <p>Version {configuration.version} · {configuration.provider} / {configuration.model} · {configuration.promptVersion}</p>
          <p>{dateLabel(configuration.changedAt)} · {configuration.changedBy} · {configuration.reason}</p>
        </li>)}</ul>
        <nav aria-label={tx("Configuration history pages")}>
          <button disabled={resource.loading || historyPage === 0} onClick={() => setHistoryPage(historyPage - 1)}>{tx("Previous configuration versions")}</button>
          <span>Page {historyPage + 1}</span>
          <button disabled={resource.loading || resource.data.history.length < 20} onClick={() => setHistoryPage(historyPage + 1)}>{tx("Older configuration versions")}</button>
        </nav>
      </section>
    </>}
  </AdminSection>;
}
