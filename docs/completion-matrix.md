# Application completion baseline

Assessment baseline: **2026-09-12**, with Step 5 authorization on **2026-09-19**.
Scope: the current working tree, including pre-existing
uncommitted changes, compared with the original 28-section application specification.

This is the completion register, not a production-readiness certificate. Source and test files
were inspected; existing test files are evidence of coverage intent, not evidence that the current
tree passes them. The original baseline was documentation-only. Step 2-3 local runtime evidence is recorded below;
production provider, load, accessibility, deployment and recovery qualification remain open.

## Approval checkpoints

Steps **1 through 6** are authorized. On **2026-09-21**, the user requested finishing Step 5,
adding live OpenAI configuration, and combining remaining Step 5 prerequisites with Step 6.
Report actual evidence and blockers; stop for explicit user approval before Step 7. Approval to
continue implementation is not approval to deploy, incur provider charges, accept legal terms,
or enable automatic production publication.

| Step | Deliverable | Exit gate | Current state |
|---|---|---|---|
| 1 | Completion baseline | Requirements mapped to evidence, accountable roles, dependencies, acceptance criteria, and qualification decisions; implementation claims reconciled | Completed and approved |
| 2 | Usable vertical slice | An editor discovers a candidate, inspects analysis and sources, reviews an image, approves and publishes through the browser; a confirmed subscriber receives one email through real application infrastructure with fake external providers | Completed; local full-stack gate passed |
| 3 | Editorial domain completion | Full revisions/corrections, regeneration, scheduler controls, policies, mutation idempotency, and consistent unpublication/restoration | Completed; local implementation and full-stack gates passed |
| 4 | Reader and administration completion | All mandatory pages and role-appropriate controls work without demonstration-only fields or manual UUID discovery | Completed on 2026-09-18; local implementation and integrated acceptance passed |
| 5 | Durability and compatibility qualification | Real-broker integration, full-stack browser coverage, failure/replay scenarios, accessibility checks, and contract evolution gates run without paid APIs | Local application gates and exact new ARM64/AMD64 image-security gates passed; manual accessibility, hosted protections and remaining release qualification remain open |
| 6 | Operational and staging qualification | Approved providers and infrastructure meet agreed compliance, performance, monitoring, security, restore, and rollback gates | Live AI setup, local operational exercises, shared-VPS Kubernetes and public HTTPS/renewal completed; application promotion, remaining platform TLS/recovery/capacity, provider/policy and operational qualification remain incomplete |
| 7 | Controlled release | Approved immutable artifacts, environment protection, operational ownership, and pilot acceptance; human review remains the default | Not started; requires approval after Step 6 |

On **2026-09-18**, the user explicitly authorized finishing Step 4 and repairing contract
enforcement, followed by a stop for approval before Step 5. That scope includes safe structured
article content, explicit reviewed fallback imagery, completed reader/admin browser workflows,
publisher-policy configuration and the associated implementation-level acceptance. It does not
authorize Step 5's CI/release-gate program, retry-topic redesign, production provider calls,
deployment or legal approval.

Steps identify delivery checkpoints, not permission to defer safety or testing. Each implementation
step includes its own smallest relevant checks, authorization, failure handling, and documentation.
Step 5 expands cross-system coverage; it does not postpone tests for Steps 2-4.

## Reading the register

Step 2 verification on **2026-09-12**: full backend run had **182 passed / 3 opt-in checks skipped**;
those three live-service checks subsequently passed against the isolated Compose stack.
Frontend: **38 unit/component tests**, lint/build, **10 fake-mode browser tests**, and **1 full
production-mode browser workflow** passed. The real HTTP/Kafka/PostgreSQL/Redis/S3/SMTP test
verified image approval, publication, source redaction and committed duplicate-event consumption
without an extra email. The browser exercised source resolution/clustering, analysis, image
replacement, editing, publication, selected-time scheduling, regeneration and consent/unsubscribe.
X/AI/image providers were explicitly fake; no paid production provider was invoked.

Step 3 verification on **2026-09-12**: **262 ordinary backend tests** passed in a clean run;
the three opt-in live-service checks passed separately. Frontend: **74 unit/component tests**,
lint/type checking/build, **11 fake browser tests** and the extended **real-backend browser
workflow** passed. New coverage includes versioned full history/comparison, reviewed corrections,
stable canonical/publication identity, schedule concurrency/failure/legacy migration behavior,
durable request receipts and unconfirmed-client retry identities. Real broker checks confirmed
stale-version conflicts, immediate search withdrawal and delayed-event-safe restoration without
another newsletter. Frontend publication-sensitive routes use no-store rather than stale ISR.

The Step 3 implementation closes the local P03-P06 editorial controls and adds relevant F04/F05
storage/API evidence; it does not close cross-store retention, exhaustive contract evolution,
production automation, load/accessibility or provider qualification. Corrections withdraw the
canonical article during review, and historical full snapshots are never fabricated.

Step 4 verification on **2026-09-18**: **129 targeted backend tests passed with no skips**, including
article/media behavior, real PostgreSQL migrations, real S3 persistence, module boundaries and the
repaired static route contract guard. Frontend acceptance passed **109 targeted unit/component
tests**, lint and production builds, **11 targeted fake-mode browser tests**, and **all 8 real-backend
browser scenarios** with normal rate limits enabled. Scenarios use separate fixed-minute windows;
they do not raise limits or automatically retry mutations. OpenAPI lint and the changed image-event
schema compilation passed; 51 nonfatal OpenAPI warnings remain, not a claim of exhaustive payload
or backward-compatibility qualification.

The Step 4 scope adds safe structured content across editing, previews, public rendering and
revisions; explicit original fallback artwork with separate approval and truthful provenance;
configurable versioned policy pages; and completed reader/admin acceptance. It also fixes manual
article creation navigation and replaces fragile controller regex discovery with syntax parsing.
Real workflows cover verification/reset/profile/session/export/deletion, discussion/moderation,
roles and revoked sessions, immutable AI guidance, newsletter preferences/consent, audit and replay
inventory. Publisher policy approval remains an external attestation; default policy pages are
drafts. No paid providers, production deployment or Step 5 work were performed.

After Step 4, the user separately authorized deploying the current application for manual testing,
without authorizing Step 5. The isolated `nsangusa-preview` local Docker deployment uses persistent
volumes and fake external providers; its lifecycle is documented in the
[development guide](development.md#persistent-local-preview). This is not a production release.

On **2026-09-19**, the user explicitly requested undeploying that preview and starting Step 5.
The `nsangusa-preview` containers and network were removed; PostgreSQL, Kafka, MinIO and Mailpit
volumes were preserved. Existing `nsangusa-phase4` developer infrastructure was left untouched.
Step 5 may create disposable, isolated acceptance infrastructure with fake external providers.
It does not authorize Step 6, a new persistent deployment, production changes, paid APIs or a commit.
Hosted required checks, manual screen-reader acceptance and release qualification remain distinct
from repository implementation; unperformed external gates must stay open.

Step 5 local evidence on **2026-09-20/21**: 1304 ordinary backend cases passed, followed by all
five live-service cases without skips; all 11 real-backend browser cases passed together with
normal quotas. Fourteen real-browser axe reports contained no violations or incomplete checks.
Executable HTTP/event/frozen-v1/migration-overlap contracts, broker failure/replay coverage,
nonce CSP, runtime image configuration and mandatory CI/release dependencies are implemented.
The [implementation status](implementation-status.md#step-5-local-evidence-and-qualification-limits)
records the complete evidence and limitations. The final frontend runtime retains 4 CRITICAL
and 52 HIGH unfixed OS findings in cached scanner data. Fresh/full image security evidence,
manual screen-reader acceptance and hosted protections remain prerequisites; these are not
waived or silently converted into a completed Step 5. Step 6 had not yet been authorized at
that checkpoint.

After that checkpoint, the user separately authorized **local preview redeployment on
2026-09-21**, before resolving the blocked qualification. The current application is running
again in `nsangusa-preview`, with its existing database/media/mail data and fake providers.
Redeployment also corrected Kafka's previously unused volume mapping: earlier broker history
had been container-local and did not survive undeployment. The preview now persists Kafka logs
and metadata in its named volume. This is not Step 6 authorization or a production release.

The evidence and requirement rows below retain the Step 1 assessment baseline. Step 2-4 changes and
their current limits are described in [implementation status](implementation-status.md); baseline
labels must not be read as a second claim about the updated working tree.

The **combined Step 5/6 follow-up** supersedes the old cached-image blocker for the exact new
ARM64 and AMD64 artifacts: the frontend uses a digest-pinned, signature-verified distroless runtime and
the backend uses Bouncy Castle 1.85. Complete fresh scans pass the unchanged HIGH/CRITICAL
threshold without suppressions. Current-source acceptance passed all 16 real-browser cases,
with 15 zero-finding automated accessibility reports and real private-management/Redis-outage
exercises. Corrected-V23 logical recovery passes 17 checks, and real candidate -> previous ->
candidate binaries preserve comparable business data/schema across 47 HTTP assertions.
This remains fake-provider/internal-network rehearsal, not managed PITR or live-provider rollback.
See the
[current evidence and limits](implementation-status.md#combined-step-56-implementation-and-local-evidence);
none of this invents manual review, actual hosted protection or production qualification.

In particular, the old account/admin UI gaps are not a current implementation backlog: account
verification/reset/profile/export/deletion, moderation, newsletter management/preferences,
user/role administration, AI model/prompt selection, audit search and controlled replay now have
real backend and browser surfaces. Use the current implementation summary and Step 4 acceptance
record rather than rebuilding those capabilities from historical evidence descriptions.

- **Present**: relevant implementation exists; the listed acceptance gate still needs execution.
- **Partial**: some required behavior exists, but the complete gate is not met.
- **Missing**: the specified surface is absent from the inspected implementation.
- **Qualification**: approval or environment-backed evidence is required, not merely code.
- **Documented**: a requirement or procedure is recorded, not proven operational.

Each requirement row has an evidence group below. Those groups distinguish backend, API, UI,
test, and documentation evidence. No production capability is marked qualified. A schema, interface,
endpoint, mock, or README alone cannot close a requirement.

Accountable roles: **ARCH** architecture, **BE** Java/backend, **FE** frontend, **QA** quality,
**SEC** security, **OPS** platform/operations, **ED** editorial/product, **LEGAL** privacy/legal,
**REL** release management. The first role owns delivery; additional roles review it. These are
responsibility assignments, not invented personnel assignments; named operational owners are
required by decision D01 before release.

Dependencies refer to requirement or decision IDs. All work also depends on the preceding
approval checkpoint. The step column gives the planned checkpoint for closing the implementation
or qualification gap, not a claim that existing code must be rebuilt.

## Evidence groups

| ID | Backend / infrastructure and API evidence | UI evidence | Existing automated coverage and documentation |
|---|---|---|---|
| EV01 | [Gradle build](../backend/build.gradle.kts), [lockfile](../backend/gradle.lockfile), [frontend manifest](../frontend/package.json), [frontend lockfile](../frontend/package-lock.json) | Not applicable | [Version matrix](version-matrix.md), [repository](../README.md); official release provenance and current qualification remain open |
| EV02 | [Module packages](../backend/src/main/java/com/nsangusa/news), public services and `package-info.java`; [migrations](../backend/src/main/resources/db/migration) | Not applicable | [ModularityTests](../backend/src/test/java/com/nsangusa/news/ModularityTests.java), [migration tests](../backend/src/test/java/com/nsangusa/news/integration/internal/CorePipelineMigrationTests.java), [architecture](architecture.md), [data model](data-model.md) |
| EV03 | [Makefile](../Makefile), [Compose](../infrastructure/compose/compose.yaml), [local profile](../backend/src/main/resources/application-local.yml), [demo users](../backend/src/main/java/com/nsangusa/news/identity/internal/LocalDemoUsers.java) | Next.js local server; application-backed content still requires ingestion and publication | [Infrastructure smoke test](../backend/src/test/java/com/nsangusa/news/InfrastructureContainersTests.java), [development guide](development.md), [editor settings](../.editorconfig); no full clean-machine startup evidence recorded here |
| EV04 | [Source-ingestion implementation and controllers](../backend/src/main/java/com/nsangusa/news/sourceingestion/internal) expose account/source controls, polling, and compliance operations | [Admin workspace](../frontend/components/admin-workspace.tsx) exposes add/pause/simulation, not the full management/health workflow | [Ingestion tests](../backend/src/test/java/com/nsangusa/news/sourceingestion/internal), [compliance requirements](security-compliance.md) |
| EV05 | [Story processing](../backend/src/main/java/com/nsangusa/news/storyprocessing/internal) clusters sources and dispatches analysis; no candidate queue controller | No candidate queue | [Clustering tests](../backend/src/test/java/com/nsangusa/news/storyprocessing/internal/StoryClusteringTests.java), [architecture](architecture.md) |
| EV06 | [Editorial providers/workflow](../backend/src/main/java/com/nsangusa/news/aieditorial), [draft records](../backend/src/main/java/com/nsangusa/news/integration/NewsEvents.java); production adapter expects custom editorial HTTP endpoints; no analysis/configuration administration API | No result, claim, model, or prompt management view | [AI tests](../backend/src/test/java/com/nsangusa/news/aieditorial/internal), [workflow](api-and-workflow.md), [security requirements](security-compliance.md) |
| EV07 | [Event processing](../backend/src/main/java/com/nsangusa/news/eventprocessing), [event contracts](../contracts/events), [operations controller](../backend/src/main/java/com/nsangusa/news/administration/OperationalHealthController.java) | Summary refresh only; no failed-event/replay workspace | [Event tests](../backend/src/test/java/com/nsangusa/news/eventprocessing/internal), [delivery semantics](events.md) |
| EV08 | [Article aggregate, revisions, service, controller](../backend/src/main/java/com/nsangusa/news/articles); single-article operations exist, but not list/revision/regeneration APIs | [Editor](../frontend/components/admin-workspace.tsx) loads by UUID and submits constant source identifiers | [Article tests](../backend/src/test/java/com/nsangusa/news/articles/internal), [workflow](api-and-workflow.md) |
| EV09 | [Publication service/policies/scheduler](../backend/src/main/java/com/nsangusa/news/publication), [search projection](../backend/src/main/java/com/nsangusa/news/search) | Transition buttons; schedule action always selects approximately one day later | [Publication tests](../backend/src/test/java/com/nsangusa/news/publication/internal), [search tests](../backend/src/test/java/com/nsangusa/news/search/internal), [workflow](api-and-workflow.md) |
| EV10 | [Media adapters/storage/variants](../backend/src/main/java/com/nsangusa/news/media); generation list, regeneration, approval, deletion APIs exist | No image-review or public-illustration integration | [Media tests](../backend/src/test/java/com/nsangusa/news/media/internal), [data model](data-model.md) |
| EV11 | [Identity services/controllers](../backend/src/main/java/com/nsangusa/news/identity); verification/reset, profile, export/deletion, sessions, OIDC and RBAC exist; no general user/role management controller | [Auth forms](../frontend/components/forms.tsx) support login/register/reset request; [profile](../frontend/app/[page]/page.tsx) is read-only demonstration content; token-completion screens are missing | [Identity tests](../backend/src/test/java/com/nsangusa/news/identity/internal), [security requirements](security-compliance.md) |
| EV12 | [Comments](../backend/src/main/java/com/nsangusa/news/comments) includes policy, reports, moderation history, suspension and editing APIs | [Public comments](../frontend/components/comments.tsx); moderation uses UUID entry instead of queues | [Comment tests](../backend/src/test/java/com/nsangusa/news/comments/internal), [component tests](../frontend/components/comments.test.tsx), [API guide](api-and-workflow.md) |
| EV13 | [Newsletter](../backend/src/main/java/com/nsangusa/news/newsletter) includes consent, preferences, deliveries, digests, suppression, signed webhooks and duplicate prevention | Subscription form exists; preference/confirmation/unsubscribe and campaign operations are not complete browser workflows | [Newsletter tests](../backend/src/test/java/com/nsangusa/news/newsletter/internal), [delivery operations](reliability-operations.md) |
| EV14 | Public article DTOs provide sources/context/image fields; public list is bounded, search is pageable | [Presentation mapping](../frontend/lib/content.ts) discards sources/context/media; [article page](../frontend/app/articles/[slug]/page.tsx), [routes](../frontend/app), [styles](../frontend/app/globals.css) | [Metadata tests](../frontend/app/metadata.test.ts), [public E2E](../frontend/e2e/public.spec.ts), [API guide](api-and-workflow.md); real-backend/a11y/performance evidence incomplete |
| EV15 | [OpenAPI](../contracts/openapi.yaml), [error mapping](../backend/src/main/java/com/nsangusa/news/integration/ApiExceptionHandler.java), [event catalog](../backend/src/main/java/com/nsangusa/news/integration/EventCatalog.java) | [API client](../frontend/lib/api.ts); hand-maintained DTOs and incomplete administration methods | [Route drift test](../backend/src/test/java/com/nsangusa/news/contracts/StaticOpenApiContractTests.java), [client tests](../frontend/lib/api.test.ts), [contracts guide](../contracts/README.md) |
| EV16 | [Security configuration](../backend/src/main/java/com/nsangusa/news/identity/internal/SecurityConfiguration.java), [production validator](../backend/src/main/java/com/nsangusa/news/integration/internal/ProductionConfigurationValidator.java), [audit module](../backend/src/main/java/com/nsangusa/news/audit) | Authenticated-area component exists; no audit-search UI | [Security tests](../backend/src/test/java/com/nsangusa/news/identity/internal/SecurityConfigurationTests.java), [startup validation tests](../backend/src/test/java/com/nsangusa/news/integration/internal/ProductionConfigurationValidatorTests.java), [threat/control document](security-compliance.md) |
| EV17 | [Helm](../infrastructure/helm/nsangusa), [observability](../infrastructure/observability); external service references and collector exist, but no deployable dashboard/alert pack or automated restore exercise | Operational summary is not an infrastructure dashboard | [CI](../.github/workflows/ci.yml) renders/lints manifests; [infrastructure](infrastructure.md), [runbooks/SLOs](reliability-operations.md) are not deployment or recovery evidence |
| EV18 | [CI](../.github/workflows/ci.yml), [images](../.github/workflows/images.yml), [deployment](../.github/workflows/deploy.yml) | [Playwright config](../frontend/playwright.config.ts) uses fake frontend APIs and desktop Chromium | [Workflow integration test](../backend/src/test/java/com/nsangusa/news/newsletter/internal/EditorialPublicationWorkflowIntegrationTests.java) uses PostgreSQL and direct consumer invocation, not Kafka transport; [release runbook](reliability-operations.md) |

## Foundation and ingestion requirements

| ID / specification sections | Required scope and acceptance gate | Owner | Step / dependencies | Baseline / evidence |
|---|---|---|---|---|
| F01 / 2, 24, 28 | Retain one Java/Spring MVC backend, one Next.js/TypeScript frontend and the required data/platform tools. Pin stable compatible dependencies/images, commit supported locks, and record official verification URLs/date and resolved artifacts; no preview versions or floating production tags | ARCH, OPS | 2; D02 | Partial provenance; EV01 |
| F02 / 3, 24, 28 | Every actual module owns its data, declares a public interface/dependencies, hides internals, avoids another module's repositories, and has boundary/module coverage. Document consolidated identity/moderation responsibilities rather than adding empty suggested modules | ARCH, BE | 2; F01 | Present boundaries, partial module-level coverage/ownership documentation; EV02 |
| F03 / 4, 25 | A clean checkout opens in IntelliJ with the wrapper/toolchain, formatting/static analysis, environment templates, migration and debugging instructions. One documented startup command runs dependencies and both apps with fake providers, four demo roles, representative seed content, mail/object inspection; no paid APIs | OPS, BE, FE | 2; F01 | Partial; demo users already exist; EV03 |
| F04 / 18 | Migrations cover every owned model, UUIDs, timestamps, mutation versions, keys/indexes, revisions, idempotency, soft deletion and retention. Distinguish implemented storage from unused schema; no production automatic schema generation | BE, ARCH | 3; F02, A03, P03 | Partial; EV02 |
| F05 / 19 | All APIs use versioned DTOs, validation, bounded pagination/filtering/sorting, consistent 401/403/404/409/429 errors and trace IDs. Check actual request/response shapes against OpenAPI; never expose JPA entities | BE, FE | 3; F02 | Partial; EV15 |
| X01 / 5, 16 | Administrators can discover/select an official account, add/edit/topic-classify/threshold/pause/resume/remove it, block accounts, exclude posts, and inspect errors, rate limits and last success without entering internal UUIDs | BE, FE | 2; F02 | API partial UI; EV04 |
| X02 / 1, 5 | Official polling respects account/project limits, synchronization configuration, bounded catch-up and concurrent replicas; source edits/deletions follow approved plan rules. Account/plan qualification must not be replaced with scraping | BE, OPS, LEGAL | 6; X01, D03, D08 | Code present; provider qualification open; EV04 |
| X03 / 1, 5, 18 | Retain only permitted account/post IDs, canonical URL, content/time, conversation/reference/media relationships and candidate/article associations. Validate identity/URL consistency and reject unauthorized or excluded sources throughout the workflow | BE, LEGAL | 2; X01, D03 | Partial; EV04, EV05, EV08 |
| X04 / 5, 20 | A deletion/edit drills through source text, candidates, AI data, article derivatives, caches/search, retained events, objects, backups and provider copies within the approved deadline. Tombstones and restore procedures cannot resurrect prohibited content | BE, OPS, LEGAL | 6; X03, P05, Q03, D03, D04 | Partial local controls; end-to-end compliance open; EV04, EV16, EV17 |
| S01 / 1, 6, 16 | Group related sources reproducibly with bounded windows; expose candidates and status, exclude blocked sources, and evaluate false merges/splits. AI/embedding assistance stays adapter-independent where used; a declared interface alone is not capability | BE, ED | 2; X03, A01 | Partial deterministic clustering; EV05, EV06 |

## AI and durable workflow requirements

| ID / specification sections | Required scope and acceptance gate | Owner | Step / dependencies | Baseline / evidence |
|---|---|---|---|---|
| A01 / 6, 28 | Implement analysis, drafting and safety against a selected provider's documented production API in Java. Enforce structured output, model capabilities and fake/provider separation; no custom editorial gateway or extra backend is assumed | BE, ARCH | 2; F01, D02 | Missing selected-provider protocol; custom HTTP adapter exists; EV06 |
| A02 / 6, 7 | Validate and persist headline, summary, body, context, SEO title/description, slug, tags/topic, source references, classified claims, confidence, uncertainty, safety/review flags, image prompt/alt text, social text, provider/model/prompt version/time. Reject invalid ranges, nested values and unsupported references before draft creation | BE, ED | 2; A01, X03 | Partial typed records; EV06, EV15 |
| A03 / 7, 18 | Store inspectable AI requests/results and claims linked to the originating candidate and article revision, with actual prompt/model/token/cost provenance. Failure records survive failed generation; retention removes content without erasing permitted audit metadata | BE, ED | 2; A02 | Missing complete result/provenance workflow; EV06, EV08 |
| A04 / 7 | Evidence checks distinguish reported/verified/disputed claims and single-source reporting; flag conflict, missing context, satire/parody, manipulated media and sensitive subjects. Verify quotations, limit copying, label analysis, and block unsupported claims. Evaluate adversarial cases; prompts alone never certify truth | BE, ED, QA | 2; A02, D05 | Partial prompt/safety controls; EV06 |
| A05 / 8, 20 | Source/admin/model data cannot change policy, roles, tools, network destinations or approved models. Bound inputs/responses, separate instructions from data, enforce allowed HTTPS destinations, DNS/IP/redirect/size/type safeguards and platform egress; rejected input produces explicit safe errors | BE, SEC | 2; A01 | Partial application defenses; production egress qualification in Step 6; EV06, EV10, EV16, EV17 |
| A06 / 6, 21 | Apply explicit timeouts, bounded backoff/retries, circuit breakers, rate limits and bulkheads to each required provider path. Record per-operation latency/errors, tokens and available cost; preserve auditability under failure and enforce approved spending limits | BE, OPS | 2; A01, D08 | Partial AI executor/metrics; other provider paths need qualification; EV06, EV10, EV13 |
| A07 / 6, 16 | Authorized administrators manage provider capability, model allow-lists and versioned prompts using secret references, validation and audit. Each generation snapshots its configuration; approved content cannot silently switch model/provider | BE, FE, SEC | 4; A03, I04 | Missing configuration workflow; EV06 |
| E01 / 9, 14 | Envelopes carry all required identifiers, timestamp, producer, schema, trace context, idempotency key and validated payload. Domain changes and outbox writes commit together; newsletter dispatch cannot begin after a rolled-back publication | BE | 2; F02 | Present core, runtime transport gate pending; EV07, EV13 |
| E02 / 9, 23 | Real broker tests prove duplicate/reordered delivery, inbox atomicity, retry/backoff, poison/DLT handling and schema evolution. State exactly where Kafka transactions apply; do not claim end-to-end exactly-once effects across external systems | BE, QA | 5; E01, T01 | Partial; EV07, EV18 |
| E03 / 9, 16 | Administrators inspect failure metadata, dry-run scoped/rate-bounded replay, confirm it and inspect results/audit. Replay respects source tombstones, publication state, dedupe retention and email reconciliation; E02/X04 later qualify full broker/retention failure scenarios | BE, FE, OPS | 4; E01, N02, X03 | Backend operations present, UI missing; EV07 |

## Editorial, publication and media requirements

| ID / specification sections | Required scope and acceptance gate | Owner | Step / dependencies | Baseline / evidence |
|---|---|---|---|---|
| P01 / 10 | Enforce every required lifecycle transition in the domain; manual creation/editing uses real authorized sources and optimistic versions. Preserve all selected sources on edits; simultaneous edits cannot silently overwrite content | BE, FE | 2; X03, A02 | Domain partial; editor submits constant source identity; EV08 |
| P02 / 10, 16 | Provide candidate/draft queues, analysis/confidence/warning/source inspection, article preview, approval/rejection and AI regeneration. Regeneration creates reviewable work without replacing an approved revision or bypassing image review | BE, FE, ED | 2; S01, A03, P01, M03 | Missing discoverable end-to-end workflow; EV05, EV06, EV08 |
| P03 / 10, 18 | Snapshot and compare complete revisions: text, context, source evidence, metadata, image and approval. A correction edits a new revision, requires renewed approval and displays a correction note when appropriate; slug, record and audit survive | BE, FE, ED | 3; P01, A03, D05 | Partial text-only snapshots; comparison/correction workflow missing; EV08 |
| P04 / 10 | Select publication date/time with explicit timezone; list, change and cancel schedules. Multi-replica scheduling checks the current approved revision, eligible sources and responsible actor and does not publish twice | BE, FE | 3; P01, E01 | Partial create-only API/fixed-time UI; EV09 |
| P05 / 10, 15 | Unpublish/archive remove public detail/list/search/feed/sitemap access, prevent comments and preserve canonical/history records. Restore reindexes and restores listings without another newsletter unless separately approved. Exercise already-cached and delayed-event cases against D06 | BE, FE, OPS | 3; P01, E01, D06 | Partial domain/cache/projection handling; EV08, EV09, EV14 |
| P06 / 10 | Explicitly support human-review-always, confidence threshold, approved-source-only and draft-generation-only policies. Warnings/sensitive or unqualified material cannot auto-publish. Production stays human-review-only until a separate approved qualification | BE, ED | 3; A04, P03, D05 | Partial human/threshold/topic modes; requested source/draft-only modes missing; EV09 |
| M01 / 11 | Use a selected Java image-provider adapter with model/dimension capability checks, editorial-illustration prompts, safety evaluation and provenance. Reject deceptive evidence, fabricated screenshots, unauthorized marks/characters and prohibited identifiable-person depictions | BE, ED | 2; A01, D02, D05 | Partial generic HTTP adapter/safety review; EV10 |
| M02 / 11, 15 | Store/serve optimized hero, thumbnail and social variants with alt text, integrity metadata and disclosure. Use an explicitly approved fallback image without treating a failed provider call as successful generation; storage access and frontend responsive rendering must work together | BE, FE | 2; M01 | Variants/storage present; serving/presentation/fallback workflow incomplete; EV10, EV14 |
| M03 / 10, 11, 16 | Editors inspect prompts/metadata, preview, approve, regenerate or replace images. Only explicit selection changes the public image; stale responses cannot override a later approved choice; failures remain visible/retryable | BE, FE | 2; P01, M01 | API present, UI absent; EV08, EV10 |

## Identity, comments and newsletter requirements

| ID / specification sections | Required scope and acceptance gate | Owner | Step / dependencies | Baseline / evidence |
|---|---|---|---|---|
| I01 / 12, 15 | Complete registration, verification-email landing, password-reset request and token-completion screens. Enforce short-lived single-use tokens, documented Argon2id parameters and enumeration/brute-force defenses; browser flows work with local email | BE, FE, SEC | 4; F03 | Backend present; token-completion UI missing; EV11 |
| I02 / 12 | Support selected external OIDC/OAuth2 login mechanisms, conservative identity linking, issuer/audience/claims validation and selected-provider logout/role behavior. Qualify redirect URIs, MFA/step-up policy and tenant configuration | BE, SEC | 6; I01, D02, D07 | OIDC implementation present; tenant and broader provider support unqualified; EV11 |
| I03 / 12, 15 | Secure HttpOnly production cookies, appropriate SameSite, CSRF and backend RBAC protect all personalized operations. Users inspect/revoke sessions and log out; no auth tokens in local storage or public personalized caching | BE, FE, SEC | 4; I01 | Backend present, session UI missing; EV11, EV16 |
| I04 / 12, 16 | Wire profile/preferences/export/deletion; administrators list users and manage reader/moderator/editor/administrator roles. Enforce authorization, version conflicts, sensitive-action audit and session effects; prevent accidental privilege escalation | BE, FE, SEC | 4; I03, F05 | Profile API present/read-only UI; user-role administration missing; EV11 |
| C01 / 12, 13 | Only eligible authenticated users comment on published, comment-enabled articles. Enforce global/per-article switches, approval policy, pending/approved/rejected/spam/deleted states, safe rendering, rate limits and spam controls | BE, FE | 4; I03, P05 | Backend present, complete policy UI missing; EV12 |
| C02 / 13 | Users report abuse, edit within the configured window and soft-delete their own comments. Moderators inspect queues/reports/history and suspend/restore privileges; authorization and audit cover each operation | BE, FE | 4; C01 | Backend present, browser management partial; EV12 |
| C03 / 13 | If nested replies remain enabled, enforce depth, parent visibility and deletion semantics consistently; display threads accessibly. Nesting is optional in the original specification, not a reason to delay required moderation | BE, FE | 4; C01 | Limited backend nesting; browser coverage partial; EV12 |
| N00 / 14, 27 | The vertical-slice subscriber can opt in, open the local confirmation email and complete confirmation in the browser without database edits. No publication email is sent before confirmation; the received message has a working unsubscribe path | BE, FE | 2; F03 | Backend present, minimum token landing workflow missing; EV13 |
| N01 / 14, 15 | Double opt-in, consent timestamp/source, verification landing, preference management and one-click unsubscribe work in email and browser, including unauthenticated subscribers. Provide safe HTML/plain-text email and no address exposure | BE, FE, LEGAL | 4; N00, I01, D09 | Backend present, browser completion/preferences partial; EV13 |
| N02 / 9, 14 | Publication commits before campaign dispatch. Persist per-recipient attempts/idempotency and test concurrent delivery, crash-after-acceptance, provider retry windows and uncertain outcomes. Reconcile expired/legacy uncertain sends rather than automatically resending | BE, OPS | 2; E01, N00, D10 | Duplicate-prevention code present; provider/runtime evidence pending; EV13 |
| N03 / 14 | Verify webhook raw-body signatures and timestamps, dedupe callbacks, process bounce/complaint suppression, and prohibit sending to suppressed/unconfirmed recipients under retries and preference changes | BE, SEC | 6; N01, N02, D02 | Code present; provider qualification open; EV13 |
| N04 / 14, 16 | Administrators inspect subscriptions/consent as authorized, campaigns, attempts, failures/suppression and reconciliation. Existing optional daily/weekly digests retain preference and duplicate safeguards | BE, FE, LEGAL | 4; N01, N02, I04 | Delivery API/digest backend present; management UI/API partial; EV13 |

## Public experience and administration requirements

| ID / specification sections | Required scope and acceptance gate | Owner | Step / dependencies | Baseline / evidence |
|---|---|---|---|---|
| W01 / 15 | Home, latest, article, topic/tag listings, search, newsletter, sign-in/register/reset, profile/preferences, privacy/terms/editorial/corrections and accessible error pages work with the real backend, including empty/not-found/error states | FE, ED | 4; I01, I04, N01, F05 | Routes present but several workflows/content are demonstrational; EV11, EV14 |
| W02 / 1, 7, 15 | Public article mapping/rendering preserves headline/summary/body, editorial attribution, published/updated dates, sources, tags/topic, separately labeled context, illustration/disclosure, related articles, enabled comments and newsletter signup | FE, ED | 4; P03, M02, C01, N01 | Missing required data/presentation blocks; EV14 |
| W03 / 15 | SSR, canonical/OG metadata, structured data, sitemap, robots and RSS/Atom reflect current publication state and complete pageable inventory. Responsive images work; public caching never includes authenticated or personalized responses | FE, BE | 4; F05, P05, M02 | SEO routes present, completeness/cache qualification partial; EV14, EV15 |
| W04 / 16 | Provide dashboard, candidate/analysis/review queues, editor/source/revisions/scheduler, X controls, moderation, newsletter/delivery, users/roles, AI/model/prompt/image, audit, failures/replay and operational health interfaces with backend-enforced role boundaries | FE, BE | 4; P02, P03, P04, A07, I04, C02, N04, E03 | Partial small UUID-driven workspace; EV04-EV13, EV16-EV17 |
| W05 / 16, 17 | Use an explicitly defined structured-block or sanitized rich-text format; neutral two-color editorial styling, licensed serif/sans typography, visible focus, reduced motion, mobile readability and WCAG 2.2 AA. Prove no critical/serious automated a11y findings and complete manual keyboard/screen-reader checks; qualify Core Web Vitals under D08 | FE, ED, QA | 5; W01, W02, W04, D08 | Plain-text rendering/design foundation present; structured editor/licenses/a11y/performance acceptance incomplete; EV08, EV14, EV18 |

## Security, operations, delivery and qualification requirements

| ID / specification sections | Required scope and acceptance gate | Owner | Step / dependencies | Baseline / evidence |
|---|---|---|---|---|
| Q01 / 8, 20 | Expand the threat model to all specified abuse, identity, publication, provider, injection, upload, dependency, Kafka, Kubernetes and secret threats. Trace each to controls, negative coverage and residual risk; verify validation, encoding/sanitization, CSP/headers and audit rather than treating policy text as proof | SEC, BE, QA | 5; A05, I03, W05 | Partial control inventory; EV16 |
| Q02 / 20, 22 | Qualify TLS and at-rest encryption, least-privilege database/Kafka permissions, workload accounts, network policies/FQDN egress and secret-manager integration. Rotate secrets without logging/exposing them; production rejects fake modes and placeholders | OPS, SEC | 6; Q01, K01, D07 | Application/chart safeguards present; environment qualification open; EV16, EV17 |
| Q03 / 5, 18, 20 | Apply approved retention/legal holds to every data class, minimize PII in events/logs, support scoped data export/deletion, and retain lawful consent/audit evidence. Exercise cleanup and restored-backup deletion ledgers | BE, OPS, LEGAL | 6; F04, I04, D04 | Partial application cleanup; comprehensive automation/evidence missing; EV02, EV11, EV16 |
| Q04 / 20, 26 | Run dependency/container/secret scans, generate SBOMs and provenance, sign release images and verify approved digests at promotion. Resolve or explicitly approve findings; lock supported tool dependencies and CI supply-chain inputs | OPS, SEC | 5; F01, R01 | Workflow foundation present; enforced release evidence unqualified; EV01, EV18 |
| O01 / 21 | Startup/readiness/liveness, graceful shutdown, retry/bulkhead behavior, resource bounds, HPA/PDB and external dependency failures work under multi-replica execution without lost committed work or misleading health success | OPS, BE, QA | 6; E02, K01, D08 | Configuration/code partial; operational exercise pending; EV06, EV07, EV17 |
| O02 / 21 | Deploy structured logs, correlated traces, dashboards and actionable alerts for public availability, publication/ingestion/AI delay, lag, pool saturation, X limits, provider cost/errors, newsletter success and moderation backlog. Exercise alert routing against approved SLIs/SLOs | OPS, BE | 6; O01, D08 | Metrics/collector/runbooks present; dashboard/alert package missing; EV06, EV17 |
| O03 / 20, 21 | Automate encrypted backup/PITR and object/config recovery; perform isolated restore and deletion-ledger replay within approved RPO/RTO. Rehearse schema-compatible rollback and forward recovery; retain evidence and runbooks | OPS, SEC | 6; Q03, K01, D07, D08 | Documented, executable qualification missing; EV17, EV18 |
| K01 / 22 | Helm deploys backend/frontend, ingress/TLS, service accounts, secret references, ConfigMaps, autoscaling/PDB/network policy and OTel with external Kafka/PostgreSQL/Redis/S3. Render local/test/staging/production overlays without duplicated manifests or bundled production stateful services | OPS | 6; F01, D07 | Chart assets present; environment deployment unqualified; EV17 |
| T01 / 23 | Unit/domain/module/repository/API/security and provider tests cover lifecycle, comments, newsletter, outbox/inbox, schema/safety/prompt injection, retries/timeouts. Required integration CI exercises PostgreSQL/Kafka/Redis and storage together and fails rather than silently skipping when its infrastructure is unavailable | QA, BE | 5; E01, P01, N02 | Partial targeted coverage; current workflow bypasses Kafka transport; EV02, EV04-EV13, EV18 |
| T02 / 23 | Component, responsive, accessibility, auth and editorial browser tests use the real Spring backend with fake external providers. Complete the vertical slice and token flows in-browser; check reader/moderator/editor/admin denial cases and supported mobile/desktop layouts | QA, FE | 5; T01, W01, W04, W05 | Component/fake-API desktop tests present; full-stack qualification missing; EV18 |
| T03 / 23 | Validate OpenAPI payload behavior and backward-compatible event evolution, plus existing Helm/Kubernetes schemas, scans and container builds. Demonstrate a supported old/new producer-consumer and application/migration rolling-upgrade matrix; K01 later qualifies the actual target deployment | QA, OPS | 5; F01, F05, E02 | Syntax/route/manifest checks present; compatibility qualification missing; EV15, EV18 |
| R01 / 26 | CI enforces formatting/static analysis/boundaries, unit/integration/browser/contract checks, builds and scans; emits SBOM/provenance and immutable artifacts. Required checks protect the release, not merely exist as optional jobs | OPS, QA | 5; T01, T02, T03 | Workflows present; complete gate coverage/branch protection unqualified; EV18 |
| R02 / 26, 27, 28 | Promote approved immutable artifacts through controlled environments with protected production approval, smoke checks, rollback instructions, named ownership and pilot evidence. Report actual functionality/results/limitations; no paid provider or production deployment is implied by a coding checkpoint | REL, OPS, ED, SEC | 7; R01, Q02, O02, O03, D01-D10 | Qualification; EV17, EV18 |

## Decisions and external blockers

These decisions are not silently accepted by documenting them. Use conservative behavior until
the accountable reviewer approves a decision. Credentials must be supplied through secret tooling,
never committed to this register.

External account/legal/environment decisions block live qualification, not implementation and
offline tests using synthetic data. Record a blocked production gate explicitly; never substitute
fake output for a real-provider result or treat checkpoint approval as provider/legal acceptance.

| ID | Decision / accountable role | Conservative implementation boundary | Evidence required to close |
|---|---|---|---|
| D01 | Named owners and release authority / REL | Roles above own work; no fictitious person or implicit deployment authority | Named engineering, editorial, security/privacy and on-call owners; explicit release approvers and environment protections |
| D02 | Versions, providers, models and contracts / ARCH | Keep the existing pinned baseline until official compatibility is checked; use Java adapters and deterministic local fakes, never assumed custom provider endpoints | Official release/compatibility URLs and verification date, exact artifacts/digests; selected X/AI/image/mail/OIDC contracts, model capabilities, quotas, regions and retention terms |
| D03 | X access and downstream AI processing rights / LEGAL | No scraping or rate-limit bypass; no real X content sent to AI until applicable terms permit that processing | Account/plan access approval, permitted fields/display/attribution rules, synchronization limits, deletion deadline, third-party AI processing rights and provider retention agreement |
| D04 | Retention, deletion and legal holds / LEGAL | Treat existing data-model periods as proposals; prefer minimal content and block replay of tombstoned sources | Approved per-store retention/deletion schedule including Kafka, DLT, backups and provider copies; hold process and restore/deletion drill |
| D05 | Editorial evidence, correction and automation policy / ED | Mandatory human approval; AI confidence is not verification. Keep draft/source/image warnings visible and disallow unqualified automatic publication | Approved claim classifications, sensitive topics, quote/copy limits, evaluation corpus/thresholds, correction/retraction and image standards; separately signed automation qualification |
| D06 | Visibility after unpublication / ED, OPS | New origin reads must not return unpublished content after commit. Do not claim to retract bytes already downloaded by a reader; disable or explicitly invalidate caching where the guarantee cannot be maintained | Defined origin/cache/CDN/search/feed/sitemap contract and approved maximum propagation delay (zero for authoritative new origin reads); tests starting with populated caches and delayed/replayed events |
| D07 | Production infrastructure and identity / OPS, SEC | External managed stateful services, least privilege, TLS, secret references and human-gated deployment; local configuration is not production acceptance | Cluster/CNI/egress, registry, DNS/TLS, secret manager, service accounts, encrypted stores, Kafka ACLs and OIDC tenant settings; access and rotation evidence |
| D08 | Capacity, cost, SLOs and recovery / ED, OPS | Use existing runbook objectives as provisional targets; no invented workload or provider budget | Approved accounts/posts/articles/subscribers/concurrency envelope and spending caps. Confirm public availability 99.9%, p95 read latency <=400 ms, publication 99% <=2 min, draft 95% <=10 min, RPO <=5 min and RTO <=4 h, or explicitly revise them; define newsletter-success/moderation-backlog and all remaining thresholds before qualification |
| D09 | Jurisdiction, consent and public policies / LEGAL, ED | No demonstration legal claims/contact addresses at launch; double opt-in and immediate suppression on unsubscribe remain conservative defaults | Applicable jurisdictions, actual publication identity/contact, approved privacy/terms/editorial/corrections copy, consent/unsubscribe/complaint and accessibility obligations |
| D10 | Provider acceptance and duplicate email semantics / BE, OPS | Keep persisted idempotency keys and reconciliation-required outcomes; do not promise exactly-once email or automatically retry uncertain sends after provider protection expires | Provider-confirmed idempotency scope/window and webhook contract; acceptance/crash/retry/expiry/concurrency exercises; operator reconciliation process and explicit republish campaign policy |

## Original specification coverage

Grouped rows retain the mandatory capabilities in each section; examples of module names and
event names are not requirements to create unused modules or events. Optional nested replies and
digests remain explicitly optional even where backend support exists.

| Original section | Completion register |
|---|---|
| 1 Product goal | X01-X04, S01, A01-A04, P01-P02, M01-M03, N01-N02, C01, W02 |
| 2 Technology requirements | F01-F03, D02 |
| 3 Architecture | F02, E01 |
| 4 IntelliJ project | F01, F03 |
| 5 X ingestion | X01-X04, D03-D04 |
| 6 AI integration | S01, A01-A07, M01 |
| 7 AI editorial rules | A02-A04, P03, W02, D05 |
| 8 Prompt-injection protection | A05, Q01-Q02 |
| 9 Event processing | E01-E03, N02, T01, T03 |
| 10 Article lifecycle | P01-P06, M03, D05-D06 |
| 11 Images | M01-M03, W02-W03 |
| 12 Authentication | I01-I04, Q01-Q02 |
| 13 Comments | C01-C03 |
| 14 Newsletter | N00-N04, D09-D10 |
| 15 Frontend | W01-W03, I01-I04, C01-C02, N01 |
| 16 Administration | W04-W05, X01, P02-P04, M03, A07, I04, C02, N04, E03, O02 |
| 17 Visual design | W05 |
| 18 Database | F04, A03, P03, Q03 |
| 19 API | F05, P01, EV15 |
| 20 Security | Q01-Q04, A05, I01-I04, X04, N03, O03, D03-D04, D07, D09 |
| 21 Reliability | A06, O01-O03, D08 |
| 22 Kubernetes | K01, Q02 |
| 23 Testing | T01-T03, W05 |
| 24 Repository | F01-F02 |
| 25 Local development | F03 |
| 26 CI/CD | Q04, R01-R02 |
| 27 Implementation sequence | Approval checkpoints, R02 |
| 28 Mandatory rules | F01-F02, A01, P06, R02, approval checkpoints |

## Evidence required when closing a row

Update the affected requirement and [implementation summary](implementation-status.md) together.
Record the actual code/API/UI paths, reproducible command or scenario, result/date, relevant
artifact or CI run, and remaining provider/operational limitations. A row is only complete when its
whole gate is met; a new API does not close a missing UI, and a fake response does not qualify a
production adapter. Keep unresolved blockers visible and stop at the current approval checkpoint.
