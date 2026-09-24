# AI provider setup and deliberate activation

The administrator AI configuration workspace can configure **live OpenAI text analysis,
drafting, and the independent content-safety gate**. Saving settings, rotating a key, reading
status, and activating a draft make **no provider calls**. A configured credential means
it can be decrypted locally; it does not prove billing, model access, connectivity, or output quality.

Local defaults remain deterministic simulation. Live text AI does **not** require changing
global `PROVIDER_MODE=production`: the separate operator gate enables text AI without
enabling live X ingestion, newsletter delivery, or image generation. Production retains its
strict global provider, TLS, storage, identity, and publication validation; fake AI is not allowed there.

## Operator prerequisites

Supply these through deployment secret/configuration management, not source control:

| Property | Environment mapping | Default / constraint |
| --- | --- | --- |
| `news.providers.ai.live-enabled` | `AI_LIVE_ENABLED` | `false`; administrator cannot override |
| `news.providers.ai.credential-master-key` | `AI_CREDENTIAL_MASTER_KEY` | Unset; base64 encoding of **32 cryptographically random bytes** |
| `news.providers.ai.base-url` | `AI_BASE_URL` | `https://api.openai.com`; only this HTTPS origin, no custom paths/ports/redirects |
| `news.providers.ai.model` | `AI_MODEL` | `gpt-5-mini` |
| `news.providers.ai.authorized-models` | `AI_AUTHORIZED_MODELS` | Subset of `gpt-5-mini,gpt-5-mini-2025-08-07`; include the default model |
| `news.providers.ai.timeout` | `AI_TIMEOUT` | Existing operator ceiling, default `20s`, hard maximum 120 seconds |
| `news.providers.ai.max-output-tokens` | `AI_MAX_OUTPUT_TOKENS` | Existing operator ceiling, default 4096, hard maximum 16384 |
| `news.providers.ai.daily-token-budget` | `AI_DAILY_TOKEN_BUDGET` | Existing operator ceiling, default 1000000, hard maximum 1000000000 |

The application/environment files must bind these properties to deployment settings. The
master key is independent of the OpenAI project API key. Generate it with a CSPRNG
(for example an operator's `openssl rand -base64 32`), securely distribute the same value
to all backend replicas, and protect a recovery copy separately from database backups.
Do not place key material in commands captured by shared logs, screenshots, audit reasons,
prompts, Git, or browser storage. Use HTTPS for non-loopback administrative access.

Production startup requires the master key and an explicitly enabled live gate, replacing
the former requirement for an `AI_API_KEY` environment credential. Existing plaintext
environment keys are **not automatically imported or used by the text adapter**. Existing
selection/history rows are preserved, but legacy OpenAI selections cannot call a provider
until the new setup is activated. Apply migration V22 through the normal migration process.

## Administrator workflow

1. In **Provider setup and activation**, select OpenAI and an operator-approved actual
   model, an existing prompt version, and timeout/output/daily-token limits. Supply an
   audit reason and **Save provider draft**. Existing generation settings remain unchanged.
2. Enter a restricted OpenAI project key in **New OpenAI API key**, provide a reason, and
   **Store or rotate API key**. The input is cleared on submission. No connectivity call
   occurs. The key is write-only and stored using AES-256-GCM, a fresh 96-bit nonce, and
   credential-identity-bound authenticated data. Database/history/audit/receipt responses
   never contain plaintext keys. Rotation deliberately blocks subsequent live calls.
3. Review the saved draft, current operator gate, encryption status, and activation blockers.
   Explicitly acknowledge permitted outbound source-data rights, privacy obligations, and
   provider/retry charges. Provide a reason and **Activate saved provider draft**.
4. New editorial requests use the activated runtime limits and selected model/prompt.
   Provider-type changes require this workflow; the existing model/prompt selector remains
   available within the currently activated provider. New prompts remain immutable and
   are not automatically selected.

The browser-only fake API does not store API keys or activate live AI. Its status explicitly
requires connecting to the backend. Backend fake results remain labeled `fake`; no live
failure is replaced with a fake result.

All setup routes are administrator-only, including service-level authorization. Mutations
require CSRF, an actor-scoped `Idempotency-Key`, an audit reason, and the current setup
version. Activation also checks the current editorial selection version. Stale or changed-key
requests fail with 409. Successful setup command replays do not re-execute the mutation and
return **current redacted status**. Model/prompt selection replays retain their existing
immutable-version response behavior. A credential command's durable fingerprint includes
a digest, never the key; browser retry bookkeeping likewise retains only a digest.

| Route relative to `/api/v1/admin/ai-configuration` | Purpose |
| --- | --- |
| `GET /setup` | Draft, last activated settings, configured status, ceilings, blockers |
| `PUT /setup` | Save bounded draft |
| `PUT /setup/credential` | Store/rotate encrypted write-only key |
| `DELETE /setup/credential` | Erase current encrypted credential and block live calls |
| `POST /setup/activate` | Deliberately activate saved draft |

## Revocation, recovery, and limits

Disable `AI_LIVE_ENABLED` operationally or remove the stored credential to block subsequent
live calls; calls already transmitted cannot be recalled. Removal remains available without
the master key. Revoke removed/rotated project keys in OpenAI too; local removal does not
revoke upstream credentials or erase encrypted backups.

A missing/invalid master key disables storage and activation. A changed key makes old
ciphertext unreadable and status actionable, never falls back to plaintext or an environment
key. Restore the correct deployment key, or issue a new OpenAI key, rotate the stored
credential under the new master key, and explicitly reactivate. There is no automatic
multi-key rewrapping process. Coordinate rotation across replicas.

Each workflow preserves immutable provider/model/prompt/runtime-setting provenance across
source safety, analysis, drafting, generated-content safety, and retries. Drafting inherits its
analysis event's durable snapshot through the existing causal event identifier; a missing linked
snapshot fails closed rather than substituting the latest provider. Saved settings
do not rewrite historical requests, drafts, approvals, or articles. Operator authorization
revocation, gate disabling, and key rotation/removal can block queued work; credential
rotation is a security boundary, not a promise that every in-flight retry will complete.

Daily accounting uses UTC and conservative reservations for pending/failed requests. Very
small budgets intentionally prevent admission: allow room for the operator maximum request
bytes plus output-token reservation, including schema/instruction overhead. Existing rate,
retry, circuit-breaker, bulkhead, payload, token-usage, and deadline controls remain enforced.
These are token admission limits, **not a currency spend guarantee**; timeouts/retries may
incur provider charges. Set independent project billing limits and monitor actual provider
usage. There is no automatic paid “test connection” button.

## Actual provider protocol and safety boundaries

The text adapter uses `POST https://api.openai.com/v1/responses`, `store:false`,
`max_output_tokens`, and strict Structured Outputs under `text.format` with
`type:json_schema`. It requests the selected supported GPT-5 mini model with minimal
reasoning effort. It reads the Responses envelope's completed assistant `output_text`,
model, and input/output usage; it rejects refusals, incomplete responses, unexpected models,
unknown/invalid structured fields, foreign source IDs, and unbounded payloads. No invented
`/analyze` or `/draft` endpoint, tool execution, or redirect following is used.

Official documentation checked for this implementation:

- [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [GPT-5 mini model, supported Responses endpoint, and snapshot](https://developers.openai.com/api/docs/models/gpt-5-mini)

Sources and editable guidance remain untrusted user data, separate from fixed system
instructions. Editable guidance is excluded from the independent safety evaluation.
Application semantic/source validation and mandatory human publication review still apply.

**Images are separate.** The existing image adapter uses the Images API with GPT image
settings; GPT-5 mini is not an image-generation model. This workspace neither configures
that adapter nor shares its encrypted key with it. Live images require their existing
operator-managed `IMAGE_API_KEY`, image model/endpoint, and provider-mode settings.
Status explicitly labels both image and X-source provider modes independently of text AI,
including their simulated status when the global provider mode is fake.
Do not enable unrelated live providers merely to enable text AI. Generated/fallback
image provenance and explicit image approval remain unchanged.

Local automated validation uses stubs/loopback HTTP, not an external provider. A separately
authorized operator qualification remains necessary for real network access, OpenAI project
permissions/billing, model quality, and recovery procedures. No deployment or paid call is
part of configuration implementation or its regression tests.
