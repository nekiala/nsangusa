# Local development and debugging

## Prerequisites

A JDK capable of running the Gradle wrapper, Node.js 24.20.0, Docker Compose, Git, and IntelliJ
IDEA. The pinned Gradle toolchain resolver downloads Java 25 when it is unavailable locally.
`.java-version` and `.nvmrc` declare the preferred developer runtimes.

Run `make toolchains` from the repository root to inspect the effective Java, Node, Docker Compose,
and Gradle Java toolchains.

## Start dependencies

```bash
make dependencies
docker compose -f infrastructure/compose/compose.yaml ps
```

Defaults match `application.yml`: PostgreSQL `news/news/news` on `5432`; Kafka `9092`; Redis `6379`; Mailpit SMTP/UI `1025/8025`; MinIO API/UI `9000/9001`, credentials `minioadmin/minioadmin`. Init services create private bucket `news-media`, the four workflow topics, and their `.dlt` topics.

Development Redis permits connections from the Compose bridge so the host application can use its
loopback-published port. It has no authentication locally; never expose the port or reuse this
Compose configuration in production, where authenticated TLS Redis is mandatory.

The Spring `local` profile references this Compose file and selects `news.providers.mode=fake`.
Real X/AI/image credentials are intentionally absent. Storage remains real private S3/MinIO and
mail is real SMTP to Mailpit; local fakes do not replace those transports.

`make dev` also starts both applications and initializes missing frontend dependencies. The local
seed ingests a clearly synthetic `NsangusaDemo` library post through the real outbox/Kafka pipeline.
After the short collection window, sign in at `/admin/candidates` or `/admin/editor`, inspect the
analysis/source trail, approve the illustration, approve the article and publish. No internal UUID
lookup is required. Set `LOCAL_SEED=false` to disable this content seed; demo-role users remain local.

Subscribe at `/newsletter` with immediate delivery, open Mailpit at `http://localhost:8025`, follow
the confirmation link and press **Confirm subscription** before publishing. The publication email
includes a browser unsubscribe link. Inspect the private `news-media` bucket in the MinIO console
at `http://localhost:9001`; image previews are authenticated backend reads, not public bucket URLs.

The editor includes stored revision comparison and explicit correction withdrawal/reapproval.
Corrections keep the canonical URL and original publication date and do not send another
newsletter. `/admin/schedules` lists, reschedules and cancels version-bound schedules; cancel a
schedule before editing its article. Refresh and review again after a stale-version conflict.
Old schedules with no captured article version are cancel-only.

Reader accounts use `/verify-email`, `/password-reset` and `/profile` for explicit email-token
completion, profile/preferences, session revocation, export and confirmed deletion. Inspect only
the intended recipient's messages in Mailpit; never publish verification/reset tokens in logs.
`/newsletter/preferences` requests an expiring consent-bound link without requiring an account.

The staff dashboard links only to permitted controls: editors manage publication; moderators
handle `/admin/comments`; administrators additionally manage users/roles, newsletter evidence,
deployed AI selections/prompts, audit, failed events and workflow health. Queues and account/article
selectors replace internal UUID entry. Sensitive writes use displayed versions and confirmations;
refresh after a 409 rather than silently overwriting another user's change.

Local MinIO has no KMS, so the local profile explicitly uses `S3_SERVER_SIDE_ENCRYPTION=none`.
Production requires `AES256` and a compatible encrypted storage service. Filesystem storage is
available only when explicitly selecting `STORAGE_PROVIDER=local` and `LOCAL_MEDIA_ROOT`, under
local/test profiles; it is not an automatic fallback for S3 failures.

## Persistent local preview

The current application can run as local containers without changing an existing developer stack.
This is a testing deployment, not a production release. From the
repository root, with Docker Compose 2.24.4 or newer:

```bash
(cd backend && ./gradlew bootJar --no-daemon --console=plain)
docker compose -p nsangusa-preview \
  -f infrastructure/compose/compose.yaml \
  -f infrastructure/compose/preview.yaml up -d --build --wait
```

Open `http://localhost:3000`, with the backend at `http://localhost:8080`, Mailpit at
`http://localhost:18025`, and the MinIO console at `http://localhost:19001`.
Use the README's local demo accounts. A clearly synthetic draft appears in the candidate/editor
queues for explicit image/article review and publication. X/AI/image providers remain fake;
PostgreSQL, Kafka, Redis, object storage and SMTP are real local services.

Images are built from the current working tree, including uncommitted changes. The frontend's
same-origin API proxy reads `NSANGUSA_API_URL=http://backend:8080` at runtime rather than baking
a rewrite into the image; server-rendered requests use the same runtime destination.
`PUBLIC_BASE_URL=http://localhost:3000` supplies the public metadata origin. The backend defaults to
`news-platform-0.1.0.jar`; set `PREVIEW_BACKEND_JAR` if its artifact version changes.
Preview builds use separate `step6-preview` image tags, preserving the previous `step5-preview`
artifacts. `PREVIEW_BACKEND_IMAGE` and `PREVIEW_FRONTEND_IMAGE` can select already-built candidates.

Only loopback ports are exposed. Preview volumes/network belong to `nsangusa-preview`; other
Compose projects are not reused or modified. Containers restart with Docker unless explicitly
stopped, and data persists in project volumes. Policy pages remain drafts pending publisher approval.

```bash
# Stop the preview without deleting its data.
docker compose -p nsangusa-preview \
  -f infrastructure/compose/compose.yaml \
  -f infrastructure/compose/preview.yaml stop

# Remove preview containers/network while preserving its named data volumes.
docker compose -p nsangusa-preview \
  -f infrastructure/compose/compose.yaml \
  -f infrastructure/compose/preview.yaml down
```

The preview was undeployed on 2026-09-19 and redeployed with the user's approval on 2026-09-21,
initially using `step5-preview` images and its existing named volumes. The combined Step 5/6
update on the same date recreated only backend/frontend using the qualified `step6-preview`
artifacts and applied V22/V23. Data services and unrelated developer services were not recreated.
It remains a loopback-only manual-testing deployment; external qualification blockers are not waived.

To make encrypted OpenAI setup available in that preview, generate its private, git-ignored
operator key once:

```bash
node infrastructure/scripts/prepare-preview-ai.mjs --allow-live
docker compose --env-file .local/runtime-secrets/nsangusa-preview-ai.env \
  -p nsangusa-preview -f infrastructure/compose/compose.yaml \
  -f infrastructure/compose/preview.yaml up -d --build --wait
```

The helper preserves an existing master key, never prints it, and creates the file with mode
`0600`. Omit `--allow-live` to leave operator permission disabled on initial creation.
Use the same env file for subsequent recreations and keep a secure recovery copy separate
from database backups. Changing/losing the master key makes stored credentials unreadable.
Granting operator permission does not activate a provider: the active text provider remains
fake until an administrator saves settings, stores a project API key, and explicitly acknowledges
and activates the draft at `/admin/ai`. Other providers remain independently configured.
See [AI provider setup](ai-provider-configuration.md); never put a project key in chat or Git.

The current preview has operator permission and its private master key configured, but no OpenAI
project credential is stored and the active provider is still fake. The administrator must
perform the credential/configuration and explicit activation steps deliberately.

Kafka now explicitly stores logs and metadata at `/var/lib/kafka/data` in the preview volume.
The previous preview used the image's container-local `/tmp/kafka-logs` default despite mounting
a volume, so its earlier broker history was not preserved by undeployment. Existing database,
media and mail data remains available; no historic broker events were reconstructed or replayed.
The following acceptance command is independent of this persistent deployment.

## Isolated Step 5 acceptance

With frontend dependencies and Playwright Chromium already installed, run from the repository root:

```bash
bash .github/scripts/fullstack-acceptance.sh
```

The runner builds current backend/frontend images and creates a unique
`nsangusa-acceptance-*` Compose project. It uses fake external providers, real
PostgreSQL/Kafka/Redis/MinIO/SMTP, normal application quotas and production Next.
All ports bind loopback: frontend `13000`, backend `18080`, management `18081`, PostgreSQL `15432`,
MinIO `19000`, SMTP `11025` and Mailpit `18026`. They must be free; these defaults do not
conflict with the persistent preview. Override `ACCEPTANCE_FRONTEND_PORT`,
`ACCEPTANCE_BACKEND_PORT`, `ACCEPTANCE_MANAGEMENT_PORT`, `ACCEPTANCE_POSTGRES_PORT`, `ACCEPTANCE_S3_PORT`,
`ACCEPTANCE_SMTP_PORT` or `ACCEPTANCE_MAILPIT_PORT` when other local tools occupy them.
Generated public links and all acceptance clients use the same configured ports.
Do not run acceptance against preview data, stop preview services, or delete their volumes
to free a port.
It runs all three live-service backend opt-ins and the real browser suite; optional arguments
are passed directly to Playwright for local targeted diagnosis.
Live-service tests use Gradle's task-specific `--rerun`: a newly created database/object/mail
stack cannot reuse a previous run's up-to-date JUnit report.
The runner supplies a fresh per-run AI credential-encryption key while explicitly disabling
live AI activation. After browser acceptance it exercises the private metrics listener and
interrupts only its own Redis container: liveness stays available, readiness and application
requests fail closed, and readiness recovers after Redis returns. These are local failure
exercises, not production failover or multi-replica SLO evidence.

The frontend image deliberately has unreachable build-time origins: its correct backend and
public origin must be runtime configuration, not an environment-specific rebuild. Service logs
and status go to ignored `.github/artifacts/fullstack/<run-id>`. The exit trap removes only that
run's containers/network/volumes on success or failure; it never deletes preview/developer data.
Built images and ordinary build/browser reports remain available for diagnosis and scanning.

CI always runs the complete command, alongside mandatory real-broker suites and the ordinary
unit/module/security/contract/build gates. `assert-junit.mjs` rejects missing or skipped broker,
payload/compatibility/migration-overlap and delivery-workflow suites; the only ordinary-run skip
exceptions are the three opt-ins, all required without skips in the isolated service job.
Do not substitute a filtered local run for the complete release gate.

## IntelliJ

1. Open the repository, then link `backend/build.gradle.kts` as a Gradle project using the checked-in
   wrapper. IntelliJ may use another supported JDK to run Gradle; compilation still uses Java 25.
2. Run the Spring Boot application with profile `local`; set `COOKIE_SECURE=false` only for local HTTP.
3. Use Node.js 24.20.0 for frontend tasks.
4. Add breakpoints at source discovery/normalization, story candidate consumer, editorial workflow consumer, article/image consumers, publication consumer, outbox relay, and DLT/retry handling.
5. Correlate `eventId`, `aggregateId`, `correlationId`, `causationId`, `traceContext`, and `idempotencyKey`.
6. Use Modulith/integration tests for boundaries and Testcontainers for isolated data tests. Shared Compose is for interactive development.

## Provider switching

Set `PROVIDER_MODE=fake` for deterministic local work. The independent text-AI gate
`AI_LIVE_ENABLED` and deployment-managed `AI_CREDENTIAL_MASTER_KEY` make administrator-managed
OpenAI setup possible without enabling live X, image or mail providers. `/admin/ai` stores the
write-only project credential encrypted, saves bounded drafts and requires separate explicit
activation. Production still requires global production mode and all unrelated secure-provider
configuration. `AI_API_KEY` is no longer the text adapter's credential source; image credentials
remain independently operator-managed.

`AI_AUTHORIZED_MODELS` is a comma-separated deployment ceiling, defaulting to `AI_MODEL`.
`/admin/ai` selects within that catalog and publishes immutable editorial guidance versions.
The stored prompt registry/selection governs generation; the legacy `AI_PROMPT_VERSION`
environment variable is not an arbitrary way to create or select new stored prompt content.
The supported OpenAI model catalog, endpoint and maximum bounds remain operator-controlled.
Administrators can set up the live credential and activate settings within those bounds; image
models/credentials and X credentials remain separate. Configuration does not imply account access
has been verified, and saving or activating settings makes no provider call.
Captured workflows keep their provider/model/prompt provenance; configuration changes do not
rewrite already approved content. See [the setup and recovery guide](ai-provider-configuration.md).

The Java editorial adapter implements OpenAI's documented `POST /v1/responses` protocol with strict
structured-output schemas, bounded payloads and application-owned provider/model/prompt metadata.
`AI_BASE_URL` is the provider origin, not an invented editorial gateway. Configure approved model
access, `AI_TIMEOUT`, payload/output limits, request rate and daily token budget explicitly.
The image adapter uses the documented image-generation protocol; model access, output quality and
provider compatibility still require qualification with the approved account.

Run the isolated full backend pipeline with
`cd backend && ./gradlew test --tests '*EditorialBrokerIntegrationTests'`.
It requires Docker and uses PostgreSQL, Kafka, Redis, MinIO and Mailpit Testcontainers with fake
X/editorial/image providers. The optional real-browser workflow and required environment variables
are documented in [the frontend guide](../frontend/README.md#optional-real-backend-browser-validation).
The browser suite exercises real rate limits; allow the advertised `Retry-After` interval between
rapid repeated runs against the same backend rather than disabling those protections.

Real accounts require region/privacy/retention review, quotas, X access-tier confirmation, representative schema/safety tests, latency/cost checks, and operational alerts. Never silently fall back between models/providers for an already approved revision.

## Debugging safety

- Mailpit and MinIO contain local data only.
- Do not log X bearer tokens, AI/image keys, passwords, sessions, CSRF values, complete prompts, article/comment personal data, or provider response bodies.
- Do not expose debuggers, Kafka, PostgreSQL, Redis, MinIO, Mailpit, or OTel ports beyond loopback.
