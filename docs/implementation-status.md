# Implementation status

Updated on **2026-09-21** against the current working tree, including pre-existing uncommitted
changes. Reader/editorial/administration implementation is present; the application is **not
production-qualified**. Local checks do not constitute production, provider or legal qualification.

The [completion matrix](completion-matrix.md) is the detailed requirement, evidence, ownership,
dependency and acceptance register. Steps 1-4 are complete against their local implementation
gates. Step 4 was explicitly reauthorized and completed on 2026-09-18.
**Steps 5 and 6 are now a combined implementation/qualification workstream.** The user
authorized Step 6 on **2026-09-21**, explicitly including remaining Step 5 prerequisites and
live OpenAI configuration. Step 5's earlier local application evidence is recorded below;
outstanding external/security/manual gates are not silently waived by this authorization.
Step 7 remains unauthorized. The separately approved local preview continues using its
existing data volumes and fake providers while these changes are implemented.

The [staging qualification guide](staging-qualification.md) defines reviewed, expiring evidence
bound to the exact source/environment/image digests, read-only hosted-protection inspection
and a non-mutating post-deployment application smoke gate. These controls fail on missing
evidence; implementing a validator does not supply the external approvals it requires.

## Step 2 verification

On **2026-09-12**, all 182 ordinary backend tests passed; three opt-in migration/S3/SMTP checks
passed separately against an isolated Compose project. Frontend validation passed 38 unit/component
tests, lint, production build, 10 fake-mode browser tests and one real production-mode browser
workflow. The latter used Spring, Kafka, PostgreSQL, Redis, private MinIO storage and SMTP/Mailpit,
not frontend API mocks. A separate real-broker test verified consumed duplicate publication events
did not deliver another email. X/editorial/image providers were explicit local fakes.

The Java OpenAI Responses/image adapters were verified with protocol fixtures and local HTTP
tests, not paid provider accounts. Factual quality, distributed provider quotas, production egress
remain qualification gates; explicit reviewed fallback artwork was added in Step 4.
No automatic production publication was enabled.

## Step 3 verification

On **2026-09-12**, the clean backend suite passed **262 ordinary tests**; the three opt-in
migration/S3/SMTP checks passed separately, with no skips in that targeted run. Coverage includes
full revision snapshots, corrections, scheduler concurrency/failure isolation, durable command
receipts, legacy migrations, module boundaries and API route contracts. Real HTTP/Kafka checks
also verified stale-version rejection, synchronous search withdrawal, restoration despite delayed
events, source redaction and no second newsletter after correction or restoration.

Frontend verification passed **74 unit/component tests**, lint/type checking, production build,
**11 fake-mode browser tests** and the extended **real-backend browser workflow**. The browser
covered revision comparison, correction withdrawal/reapproval, canonical/date preservation,
feed/sitemap withdrawal and versioned schedule rescheduling/cancellation. Explicit refresh is
required when asynchronous updates or another editor invalidate the displayed version.
No paid provider API or production deployment was used.

## Step 4 verification

On **2026-09-18**, the integrated Step 4 acceptance completed using an owned isolated Compose stack:
PostgreSQL, Kafka, Redis, private MinIO storage and SMTP/Mailpit, with local fake X/AI/image
providers. No production data, paid provider call, deployment or legal approval was involved.

| Scope | Result |
|---|---|
| Backend articles/media/contracts and Modulith boundaries | 129 passed, no skips, including three PostgreSQL migration scenarios and S3 persistence |
| Frontend targeted content/editor/images/API/policies | 109 unit/component tests passed; lint and production builds passed |
| Fake browser editorial/public scenarios | 11 passed |
| Real-backend browser acceptance | All 8 scenarios passed together with normal rate limits enabled |
| OpenAPI and changed image event | Pinned Redocly lint and Ajv compilation passed; 51 nonfatal OpenAPI warnings remain |

The browser exercised structured blocks, literal HTML, preview/save/revision/public rendering,
real fallback PNG bytes, stale-version denial, retained image selection until separate approval,
non-AI disclosure, corrections and scheduling. It also exercised verified reader lifecycle,
session revocation, moderation/policies/suspension, confirmed role changes, immutable AI guidance,
newsletter consent-bound preferences, audit inventory and role denials. Article creation now uses
Next navigation instead of a raw history replacement that could remount a blank creation form.
Discussion acceptance creates and withdraws its own sourced article rather than depending on
another scenario's restored article having comments enabled.

Browser scenarios start in separate fixed-minute windows because the local reverse proxy shares
one IP quota. Mutations are still sent once; no production rate limit is disabled or increased.
These are targeted implementation results, not a clean full-repository CI or Step 5 durability,
schema-evolution, accessibility or release qualification.

Commands used (backend migration/S3 environment pointed to the isolated stack):

```bash
cd backend
./gradlew test --tests 'com.nsangusa.news.articles.*' \
  --tests 'com.nsangusa.news.media.*' --tests 'com.nsangusa.news.contracts.*' \
  --tests 'com.nsangusa.news.ModularityTests' --no-daemon --console=plain -q
cd ../frontend
npm test -- lib/article-content.test.ts components/article-content.test.tsx \
  components/admin-workspace.test.tsx components/admin/revisions.test.tsx \
  app/publication.test.tsx lib/editorial-workflow.test.ts lib/api.test.ts \
  components/admin/images.test.tsx lib/fallback-image-api.test.ts \
  lib/publication-policies.test.ts app/policies.test.tsx
npm run lint
CI=true npm run test:e2e -- e2e/auth-editor.spec.ts e2e/public.spec.ts
npm run test:e2e:fullstack
```

Backend opt-ins were `MEDIA_MIGRATION_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/news`
and `S3_SMOKE_ENDPOINT=http://127.0.0.1:19000`, with the local Compose credentials/bucket.
Fullstack origins were API `http://127.0.0.1:18081`, frontend `http://127.0.0.1:13001`
and Mailpit `http://127.0.0.1:18025`; see the frontend README for environment variable names.
Both browser commands build production Next servers. The separate test project and temporary
application processes are removed after acceptance; pre-existing developer services are untouched.

Policy templates remain visibly draft until the publisher supplies real configuration and approves
the exact version. Legal approval, provider qualification, cross-store retention, recovery,
monitoring, performance and production release remain open gates.

## Step 5 local evidence and qualification limits

The coordinated run on **2026-09-20/21** used only local fake external providers and isolated
real infrastructure. The acceptance runner removed its containers, network and volumes after
each run. Neither the preserved preview volumes nor `nsangusa-phase4` developer services were
removed. During qualification, no persistent deployment, paid provider call, commit or Step 6
work was performed.

| Scope | Result |
|---|---|
| Ordinary backend build, formatting and SBOM | 1304 executed cases passed; five cases in the three named live-service opt-ins deferred to the isolated job |
| Live PostgreSQL migration, S3 and SMTP opt-ins | All five cases passed with no skips |
| Broker durability | Eight real-Kafka failure/rollback/recovery/replay scenarios and the existing full editorial broker workflow passed |
| HTTP/event compatibility | Actual MVC/Jackson payloads, all 16 event schemas, strict reader negatives, frozen synthetic v1 examples and mandatory PostgreSQL overlap passed |
| Frontend | 259 unit/component cases, lint, TypeScript, production builds and 21 fake browser cases passed; metadata/accessibility regressions passed 18 repeated cases |
| Immutable frontend configuration | One artifact built against unusable origins worked with two runtime API/public origins; the container acceptance also used unusable build-time origins |
| Complete real-backend browser suite | All 11 cases passed together, zero skips, failures or flaky retries, in approximately 10.3 minutes with normal quotas |
| Automated accessibility | 14 persisted desktop/mobile axe reports: zero violations and zero incomplete checks; keyboard, visible focus, reduced motion and production CSP behavior passed |
| Contracts and infrastructure | Redocly lint passed with 51 existing warnings; all event schemas compiled; all four Helm overlays rendered and 104 Kubernetes resources validated without skips |
| Gate enforcement | Four report-guard regressions and backend delivery-workflow assertions passed; required suites cannot disappear or skip unnoticed |

Integration repaired absent legacy token-counter handling without allowing explicit null
primitives, syntax-only email validation for reserved fixture domains, stale newsletter test
fixtures, canonical pagination navigation and article tag navigation semantics. Broker retries
now use four blocking attempts with acknowledged DLT recovery and consumer-scoped replay.
The frontend has nonce-based production CSP, runtime API forwarding and a runtime public origin.
The common CI/release workflow is fail-closed and retains evidence; image signatures bind the
qualified release tag, source commit and promoted chart.

The fullstack run's service/browser evidence is under ignored
`.github/artifacts/fullstack/local-1789946681-82260/`; detailed axe reports are in
`frontend/test-results/`. Ordinary backend and final fullstack reports plus SBOM/scan evidence
were also archived in the session workspace before filtered runs could replace them.

**At that checkpoint, Step 5 was not fully qualified.** The frontend runtime had **4 CRITICAL and
52 HIGH OS package/vulnerability findings** in the cached scan, with no vendor-fixed version recorded.
Fixable Tomcat, Bouncy Castle, bundled npm and PCRE findings were addressed without suppressing
unfixed results. Fresh vulnerability-data retrieval failed, and a complete backend image scan
could not initialize its Java database; separate cached backend OS and resolved-SBOM scans are
not a substitute for that missing result. The subsequent combined work below supersedes those
image-security blockers for the exact new artifacts, not the old images.
See [security evidence](security-compliance.md#step-5-security-evidence-and-release-blockers).
Manual screen-reader acceptance and actual hosted required-check/tag/environment protection,
signing and promotion remain unperformed. Local automation does not certify those gates.

### Subsequent local preview redeployment

The user separately requested redeployment on **2026-09-21**, before resolving the outstanding
qualification blockers. `nsangusa-preview` is running on loopback: frontend `3000`, backend
`8080`, Mailpit `18025` and MinIO console `19001`, with fake external providers and automatic
container restart. Existing article, account, media and mail storage was reused; developer
services were not recreated.

Redeployment exposed a preview persistence defect: Kafka had written to container-local
`/tmp/kafka-logs`, leaving its named volume unused. Its earlier local topic history was therefore
not retained across undeployment. The preview now explicitly sets
`KAFKA_LOG_DIRS=/var/lib/kafka/data`; broker logs and metadata use the named volume. No old
events were fabricated or replayed to hide that loss. The retained database had no unpublished
outbox records at cutover. This local deployment does not waive the qualification blockers.

The subsequent **2026-09-21** authentication-navigation fix replaces the static sign-in header
with the current account, sign-out control and role-filtered Administration, Editor and
Moderation workspace links. Header, sign-in form and protected routes share session-checking
behavior, with simultaneous account reads deduplicated and stale responses discarded after
authentication changes. Returning to sign-in recognizes an existing session; history, reload,
explicit sign-out and session revocation remain consistent. Background revalidation preserves
unsaved fields, and account-service failures are not presented as successful sign-outs.

The original reported session remained valid; the static header falsely suggested otherwise.
Only the preview frontend was recreated. Backend, Redis and PostgreSQL containers were not
restarted. Targeted coverage includes all four roles, signed-in mobile/keyboard navigation,
account-action redirects and in-flight response races. The deployed cookie-backed journeys
used owned sessions and left existing application data untouched; those sessions were logged
out afterward. The outstanding Step 5 qualification blockers are unchanged.

## Combined Step 5/6 implementation and local evidence

The combined work adds administrator-managed OpenAI Responses configuration at `/admin/ai`:
approved model/prompt selection, bounded deadlines/output/daily tokens, AES-256-GCM encrypted
write-only project credentials, rotation/removal, and a separate acknowledged activation.
An independent operator gate and persistent encryption master key are mandatory. Saving a draft,
storing a credential or activating configuration does not call OpenAI or certify account access.
Captured settings remain causal across analysis, drafting, safety and retries; image/X modes remain
independent. See [AI provider configuration](ai-provider-configuration.md).

Operational additions include database-wide inventory metrics, opt-in private management scraping,
Redis-independent liveness, dependency-aware readiness, bounded HTTP histograms, optional ECS logs,
Prometheus rules and a Grafana dashboard. V23 provides explicitly approved, bounded retention for
two operational payload classes, legal holds and suppressed-publication safeguards. Original event
IDs as well as DLT-record and aggregate IDs protect failure payloads and exception details.
These controls do not constitute comprehensive cross-store legal-hold enforcement.

| Scope | Current local evidence |
|---|---|
| Backend | 1331 ordinary cases in the full build; subsequently all five delivery-workflow cases passed, including the new fresh-infrastructure guard |
| Real service opt-ins | Five PostgreSQL migration/S3/SMTP cases executed again without skips; the runner now forces `test --rerun` instead of accepting an earlier stack's cached result |
| Frontend | 294 unit/component cases, lint/types and production builds; 27 fake-browser cases |
| Complete real browser suite | All 16 cases passed together without skips/retries, including four cookie/session roles and encrypted OpenAI setup with authoritative live-activation denial |
| Automated accessibility | 15 archived desktop/mobile axe reports with zero violations or incomplete findings; manual assistive-technology acceptance remains open |
| Management failure exercise | Main-port and forwarded-port-spoof scraping denied; private scraping and liveness survive an owned Redis outage while readiness/application requests fail closed, then recover |
| Contracts and deployment templates | OpenAPI lint with 51 existing warnings, all 16 event schemas, four Helm overlays and 104 valid Kubernetes resources |
| Monitoring | 12 Helm safety/render checks and seven real promtool scenarios |
| Logical recovery and retention | 17 checks against corrected V23: encrypted PostgreSQL/object/config recovery, integrity/tamper rejection, deletion/suppression reconciliation, bounded cleanup and original-event hold/release regression |
| Real binary rollback/forward recovery | Candidate -> previous -> candidate passed 47 real HTTP assertions against one migrated database; comparable business data/schema stayed intact, with monotonic source-observation metadata retained separately |
| ARM64 and AMD64 image security | Both actual-source image pairs pass complete fresh OS/library/secret gates with zero HIGH/CRITICAL or secret findings; lower-severity findings remain documented |
| AMD64 runtime scope | Actual frontend native Sharp, SSR/proxy/CSP, restricted filesystem, DNS and signals exercised under emulation; backend Java startup probe only, not a full AMD64 application stack |
| Release guard tooling | 13 report/evidence/governance/smoke/private-key guard cases; no real hosted protection or approval record was fabricated |
| Binary-driver regression guards | 19 cases cover ownership, immutable inputs, fake/live-state separation, bounded real HTTP, representation negotiation and constrained source-observation comparisons; required by the operational CI job |

Complete browser/service evidence is in ignored
`.github/artifacts/fullstack/local-1789962553-2745/`; the fresh-service follow-up and two AI cases
are in `local-1789964166-4652/`. The corrected logical drill is
`.local/operational-evidence/parent-event-hold-v1/evidence.json`. Ordinary/fresh-service JUnit,
the complete browser accessibility reports, per-image SBOMs and scanner receipts are preserved
in the session workspace. Earlier failed evidence is retained rather than overwritten.
The separate real-binary evidence is
`.local/operational-evidence/binary-candidate-internal-v4/evidence.json`. Its explicit internal
HTTP transport retains network isolation on Docker engines that cannot publish internal-network
ports. It does not qualify ingress, live-provider rollback, concurrent rolling replicas or
production PITR. All owned rehearsal resources were removed.

The persistent preview was updated at **2026-09-21 04:37 UTC** using the exact scan-bound ARM64
artifacts, under separate `step6-preview` tags. Only backend/frontend were recreated; data services,
named volumes and `nsangusa-phase4` remained intact. V22/V23 applied normally, with the existing
one article and four accounts retained. Public/API smoke and encrypted-setup status are available
at `http://localhost:3000`; the active provider remains fake, no project credential is stored,
and operator permission does not itself activate OpenAI. The private master key is reused through
the documented env file, never committed or baked into an image.

**Neither Step 5 nor Step 6 is fully qualified.** Named manual accessibility acceptance and actual
hosted branch/tag/environment protections remain open. Step 6 additionally needs approved provider
accounts/data rights, target TLS/ACL/secret-manager and encrypted-storage evidence, routed on-call
alerts, approved capacity/cost/SLO measurements, managed PITR/RPO/RTO and operational ownership.
Q03 still requires approved per-store retention and cross-store/provider/backup deletion and holds;
the new two-class retention function does not replace legacy identity cleanup.
No paid provider request, production release, commit or Step 7 work has been performed.

### Shared-VPS staging bootstrap

The owner subsequently supplied `nekiala/nsangusa`, designated `@nekiala01` as the additional
reviewer, and explicitly retained independent review/no self-approval. Invitation acceptance,
hosted protection configuration and actual approval remain unverified. SSH key access and
`staging.nsangusa.com` DNS are established for an approved shared Ubuntu VPS; the owner confirms
a full backup, recent snapshot and provider rescue access.

Pinned k3s **v1.36.4+k3s1** is installed with an additive firewall prerequisite, separate
containerd, restricted loopback NodePorts, encrypted Secrets and metadata-only API audit.
The existing Docker application, Nginx configuration/service and unrelated Actions runner were
preserved. Real bootstrap exercises cover cluster DNS, verified pod-to-API TLS, ingress/egress
NetworkPolicy enforcement, external control-port denial and a k3s-only restart with encrypted
Secret persistence. The [infrastructure notes](infrastructure.md#shared-vps-staging-bootstrap)
record the exact assets, preservation boundaries and stock metrics aggregation TLS caveat.

On **2026-09-22**, owner-authorized Let's Encrypt HTTPS and isolated automatic renewal were
enabled for `staging.nsangusa.com`. The existing site remains intact. The staging hostname
deliberately returns an uncached, non-indexable **503** until the qualified application is
deployed. Actual HTTP-01 issuance, a sandboxed renewal/deploy-hook drill and public TLS 1.2/1.3
passed; a post-renewal verifier rejects stale or untrusted served certificates.
See [HTTPS operations](infrastructure.md#staging-https-and-certificate-renewal).

This closes the absence of a staging Kubernetes base and public HTTPS, **not Steps 5/6 qualification**.
Staging application/data-service deployment, verified metrics aggregation TLS, recovery
and capacity evidence, provider/policy approval and the existing human/hosted release gates
remain open. The shared single node is not HA; no host reboot or production promotion occurred.

## Specification conformance changes (2026-10-01)

A re-read of the original specification found seven gaps; all are addressed in the working tree.
These are implementation changes with local evidence, not deployment or qualification.

| Gap (specification section) | Change | Local evidence |
|---|---|---|
| Python tooling (1, 28) | Drills, guards and tests ported to Node `.mjs` with `node --test`; host ACME verifier ported to POSIX `sh` + OpenSSL ([ADR-016](adrs.md#adr-016-operational-tooling-without-an-additional-language-runtime)) | 39 script tests (real TLS fixtures for the verifier); real restore drill passed 17 checks; real Helm 4.2.4 + promtool monitoring check passed. Binary qualification covered by unit tests only, not a four-image run |
| Kafka transactions claim (9, 28) | `events.md` now states they are not used and why ([ADR-015](adrs.md#adr-015-no-kafka-transactions-database-outbox-and-inbox-instead)) | Documentation only; producer idempotence and `read_committed` unchanged |
| Retry topics (9) | Group-scoped `<topic>.retry` tier after blocking retries ([ADR-014](adrs.md#adr-014-group-scoped-delayed-retry-topics)); topics added to Compose and k3s | 10 real-Kafka reliability scenarios, including delayed recovery and cross-group isolation, plus policy/interceptor unit tests |
| Missing workflow events (9) | Seven events published via the outbox; `XAccountMonitoringRequested` triggers an account's first sync ([ADR-017](adrs.md#adr-017-publish-the-remaining-workflow-facts-as-events)) | Schemas compile with pinned Ajv; separately pinned additions baseline; contract suite and workflow integration test pass |
| Backend static analysis (4, 26) | PMD 7.28.0 with a bug-focused ruleset, run by `check`/`build` (CI) and `make test` | Two proven false positives suppressed in place with reasons; no other findings |
| Typography (17) | Self-hosted OFL-1.1 Inter (body/interface) and Source Serif 4 (headlines/decks), pinned at 5.3.0 | Fonts load under the production CSP in a real browser; frontend lint, types, 296 unit cases and production build pass |
| Stray files (24) | Removed superseded `files/phase4-*.yaml` drafts (all 32 paths are in `contracts/openapi.yaml`) and an empty `src/` | Both frontend Dockerfiles were kept: one builds the preview from source, the other packages CI artifacts |

Not repeated here: the full real-backend browser suite, manual accessibility review, and
redeployment of the persistent preview or staging host. A staging host keeps its installed
Python ACME verifier until the shell version is reinstalled.

## Implementation present

These are existing code surfaces, not claims that their complete acceptance gates have passed.

- Java 25 / Spring Boot 4.1.1 modular monolith, pinned Gradle/frontend dependencies, Spring Modulith
  boundary verification test, Flyway migrations, public module services and versioned DTO APIs.
- Official-X-API polling, lookup reconciliation, edit-chain deduplication, source tombstones,
  conversation relationships, topic/relevance filtering and account/source compliance controls.
- Time-bounded deterministic story clustering and durable analysis dispatch after a quiet period.
- Event envelope/catalog, outbox relay, processed-event tracking, retry/DLT handling and
  administrative replay APIs with audit metadata.
- Java AI/image interfaces, deterministic fake providers, a documented OpenAI Responses adapter,
  strict typed schemas, source/claim validation, persisted analysis/draft provenance, prompt
  boundaries, source/output safety checks, bounded retries, rate/token budgets and usage metrics.
- Image generation, optimized hero/thumbnail/social variants, storage adapters and explicit
  image-selection approval APIs. Editors can explicitly request original neutral fallback artwork
  with a reason, current article version and durable key, then separately approve it. Fallback
  provenance remains non-AI in the database, events, revisions and UI; provider failures never
  silently substitute imagery.
- Article create/read/edit/approve/reject/schedule/publish/unpublish/restore/archive services,
  full new revision snapshots/comparison, approval provenance, reviewed corrections, optimistic
  guards and durable mutation receipts. Public discovery/search is transactionally reconciled;
  publication-sensitive HTTP/Next.js responses are not cached.
- Safe version-one article blocks (paragraphs, h2 headings, quotations, lists and HTTP(S) links),
  canonical plain bodies, content-only commands, structured editing/preview/public rendering and
  immutable revision content. Legacy bodies remain readable without fabricated historical snapshots.
- Version-bound schedule inventory, rescheduling/cancellation, responsible-editor and failure
  metadata, independent due-item transactions, approved-source and draft-generation-only policies.
  Older schedules without captured article versions require cancellation and renewed review.
- Identity APIs for Argon2id credentials, registration, verification/reset, OIDC linking, sessions,
  profile changes, export/deletion and linked-newsletter opt-out; CSRF, RBAC and rate-limit controls.
- Comment submission/editing/deletion, reports, policy overrides, moderation history, spam
  scoring, suspensions and limited replies.
- Double-opt-in newsletter, consent/preferences, immediate/digest delivery, persisted
  idempotency keys, uncertain-send reconciliation, signed webhook processing and suppression.
- Next.js public routes, registration/verification/reset-completion, versioned profile/preferences,
  session inventory/revocation, export and confirmed deletion; comments, pageable article and
  candidate queues, analysis/source inspection, sourced manual editing, preview, image review,
  regeneration, revision comparison, correction withdrawal/reapproval, schedule management and
  publication controls. Unconfirmed editor requests retain retry keys within the current client.
- X account resolution and health/configuration controls; public source/context/illustration
  presentation and explicit browser newsletter confirmation/unsubscribe/preferences pages.
- Role-aware moderation queues/reports/policies/privileges, user inventory and confirmed role
  changes, newsletter consent/delivery evidence, immutable AI guidance and deployed model selection,
  audit filters, workflow inventory and controlled dry-run/replay screens.
- Versioned factual privacy, terms, editorial and corrections templates with explicit draft
  status. Publisher/contact/jurisdiction/retention/effective-date configuration and exact-version
  approval attestation are required before claiming approval; no legal approval is fabricated.
- Java-syntax-based static OpenAPI route enforcement, including controllers with no class mapping.
  Unsupported mapping declarations and duplicate routes fail closed. Payload evolution qualification
  remains a separate gate.
- Compose dependencies, local fake providers, a pipeline seed and four seeded demo roles; real
  local S3/SMTP transports, Helm environments,
  external-service/secret references, collector configuration, CI/image/deployment workflows and
  operational/security documentation.
- Production configuration guards against fake providers, missing credentials, placeholder
  secrets and insecure required endpoints. Production publication remains human-review-only.

## Remaining implementation gaps

| Area | Incomplete or missing behavior | Completion requirements |
|---|---|---|
| AI integration | Provider/model/prompt administration is implemented. Expanded editorial evaluation and approved-account quality/safety/cost/region qualification remain open; generated content is still a human-reviewed draft | A01-A07 |
| Editorial acceptance | Revisions/corrections, schedule controls, requested policy modes and regeneration are implemented. Editorial/legal acceptance of correction policy and production automation remain open; old or source-redacted snapshots are not reconstructed | P03-P06, D05 |
| Public articles | Structured content, sources/context, truthful image disclosure, public pagination, related inventory, RSS and sitemap segments are wired; relevance, visual and performance qualification remain open | W02-W03, M02 |
| Accounts | Real verification/reset, profile/preferences, session revocation, export/deletion and user/role controls are wired. Production OIDC/MFA/logout and complete cross-store erasure/retention remain external/operational gates | I01-I04 |
| Administration | Moderation, newsletter evidence, users/roles, AI selections/guidance, audit and replay controls are present. Retention-job administration and exhaustive failure/replay qualification remain open | W04, A07, C02, N04, E03 |
| API and data | Editorial mutations have durable receipts and queues are bounded/pageable. Idempotency for unrelated APIs, exhaustive DTO/payload compatibility, approved receipt retention and cross-store retention automation remain open | F04-F05, Q03, T03 |
| End-to-end assurance | Real HTTP/Kafka/PostgreSQL/Redis/S3/mail, broker failure/replay and all 11 real-backend browser scenarios passed locally; image-security, distributed production and deployment qualification remain open | E02, T01-T03 |
| User experience | Automated desktop/mobile WCAG, keyboard/focus and reduced-motion coverage passed. Manual assistive-technology/performance qualification and publisher/legal approval remain open | W01-W05, D09 |
| Operations | Collector/runbooks and Helm are not a deployed alert/dashboard package, recovery exercise, capacity result or production approval | O01-O03, K01, R01-R02 |

The requirement IDs above link conceptually to the [completion register](completion-matrix.md).
Missing implementation must not be relabeled as requiring only production credentials.

## External qualification still required

- Ongoing version/compatibility review and approved provider/model selection; official artifact
  provenance is recorded in the version matrix, not inferred from version numbers.
- Approved X access, synchronization limits, retained/displayed fields, deletion deadlines and
  rights to send permitted source content to the selected external AI provider.
- AI/image quality, safety, data region/retention, quotas, cost and latency acceptance.
- Identity-provider tenant, redirects, claims, MFA/step-up and logout acceptance.
- Email provider idempotency, uncertain-send reconciliation, webhook, delivery and suppression
  qualification with an approved account.
- Production PostgreSQL, Kafka, Redis, S3, secret manager, registry, Kubernetes, DNS/TLS and egress
  details, together with load, encryption/access-control, backup/restore and rollback evidence.
- Named editorial, privacy/security, operations and release owners; approved public policies,
  capacity/budgets, SLOs, retention and deletion rules.

See decisions D01-D10 in the completion matrix. Local fakes and test fixtures cannot close these
qualification gates, and automatic production publication is not enabled by this baseline.
