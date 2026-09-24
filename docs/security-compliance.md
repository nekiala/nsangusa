# Security, abuse resistance, and X compliance

This inventory distinguishes implemented application safeguards from unqualified deployment
controls. Test references identify negative coverage; their existence alone does not establish
a passing current run. Runtime evidence and open approvals belong in the
[completion matrix](completion-matrix.md). This is not a penetration-test report, legal approval
or production certification.

## Threat model

Trust boundaries are browser/Next, Next/Spring, application/PostgreSQL/Redis/Kafka/object storage,
application/external providers, CI/registry and operator/cluster. All source text, provider
responses, user input and broker payloads remain untrusted; an event's producer-name field is
metadata, not authentication.

| Threat | Implemented safeguard and negative coverage | Residual qualification / owner |
|---|---|---|
| Credential stuffing and account enumeration | Argon2id, generic login failures, lockout and stricter token-action quotas; `IdentityLifecycleSecurityTests`, `RedisRateLimitFilterTests` | Distributed abuse exercise, IdP MFA and account-recovery policy / Identity, Security |
| Session fixation, theft and stale roles | Session-ID rotation, Redis sessions, session inventory/revocation and current-account validity; `IdentityInfrastructureIntegrationTests`, `IdentitySessionValidityTests`, real role-change browser flow | Production cookie/TLS and IdP logout evidence / Identity, Operations |
| CSRF and forged browser commands | Spring CSRF for mutations, narrowly scoped signed-webhook/one-click exceptions; `SecurityConfigurationTests`, `CommentControllerTests` | SameSite/proxy behavior under actual ingress; XSRF cookie is intentionally readable, session cookie is not / Security |
| Object or privilege escalation | Method roles, ownership checks, versioned commands and revoked-session denial; `CommentApplicationServiceTests`, `IdentityAdministrationControllerTests` | Opaque IDs are not an authorization control; production staff provisioning / Identity |
| Unauthorized publication, stale approval and races | Human review, separately approved imagery, captured revisions/versions and command receipts; `ArticleStateTransitionTests`, `PublicationConcurrencyTests`, `DurableCommandExecutorIntegrationTests` | Editorial signoff, scheduler cutover and controlled automation approval / Editorial |
| Stored XSS and script injection | Safe structured blocks, URL validation, React text rendering and fresh production script nonces; `ArticleContentTests`, literal-HTML and enforced-CSP real-browser scenarios | Style attributes remain allowed; arbitrary HTML/embed formats are not supported. Production ingress/header qualification remains open / Frontend, Security |
| SQL/schema/request injection | Bound repository queries, Bean Validation, explicit domain limits and syntax-based route enforcement; `ApiExceptionHandlerTests`, controller negative cases | Executable payload/evolution coverage must pass; route equality alone is insufficient / Backend |
| Prompt injection and invented evidence | Untrusted-data boundaries, constrained provider results, authorized source IDs/quotes and no model publication tool; `PromptBoundaryTests`, `EditorialSemanticValidatorTests` | Representative adversarial provider evaluation and factual/editorial review / AI, Editorial |
| SSRF, DNS rebinding and provider exfiltration | Trusted configured origins, HTTPS/literal-private-address checks, bounded transport and no image-URL fallback; `OpenAiHttpTransportTests`, `ProductionImageGenerationProviderTests` | A URI guard is not DNS/redirect/egress enforcement; FQDN-aware network controls and provider-region approval remain unqualified / Operations, Security |
| Malicious media or upload expansion | Provider base64/decoded limits and PNG validation; original fallback PNGs require explicit review; `ProductionImageGenerationProviderTests`, `NeutralEditorialFallbackTests` | No general user-upload endpoint is implemented; any future upload requires its own MIME/decompression/storage review / Media |
| Spam, harassment and resource exhaustion | Comment ownership/verification, moderation, suspension, bounded queues and fail-closed Redis quotas; `CommentControllerTests`, `CommentApplicationServiceTests`, `RedisRateLimitFilterTests` | Heuristics do not replace moderation staffing, load or multi-replica qualification / Moderation, Operations |
| Forged newsletter events or duplicate sending | Raw-body signature/time checks, event identity, consent and provider-side idempotency window; newsletter controller tests and `NewsletterDuplicatePreventionTests` | Provider acceptance after an uncertain send is not an atomic database transaction; reconciliation remains mandatory / Newsletter |
| Kafka poison data, duplicate/reordered work and lost acknowledgements | Transactional outbox/inbox, acknowledged DLT recovery and consumer-scoped replay; eight actual-broker failure/rollback/replay scenarios in `EventReliabilityBrokerIntegrationTests` | TLS/SASL/ACL identity and real multi-replica operation are Step 6 gates; no end-to-end exactly-once claim / Backend, Operations |
| Privileged replay or audit misuse | Role separation, redacted failure metadata, bounded dry-run and same-operator confirmation with reason/audit; `ReplayConfirmationIntegrationTests`, `AuditControllerTests` | Two-stage confirmation is **not** two-person approval. Independent approval/retention ownership is not implemented / Operations, Security |
| Prohibited source retention and privacy leakage | Official API only, source tombstones, derivative withdrawal, account anonymization and newsletter unlinking; `SourceIngestionComplianceTests`, identity/newsletter cases | Cross-store expiry, Kafka/backups/provider deletion ledgers and legal deadlines remain open / Privacy, Operations |
| Dependency compromise or release-gate bypass | Locked dependencies, SHA-pinned actions, scan/SBOM jobs, common required gates and signed immutable digests; `DeliveryWorkflowTests` and fail-closed JUnit guard cases | Hosted required checks, approved tag/environment protection and actual release attestations require a configured repository / Release, Security |
| Kubernetes escape, overbroad networking and stolen secrets | Restricted workload contexts, secret references, network-policy templates and production configuration guards; Helm/schema checks, `ProductionConfigurationValidatorTests` | Actual admission policy, least-privilege DB/Kafka credentials, secret-manager rotation, TLS and at-rest encryption are unqualified / Operations |

HIGH/CRITICAL scan findings fail the quality/release workflow, including currently unfixed
findings. There is no blanket `ignore-unfixed` exception. A future exception must identify the
finding, affected artifact, justified exposure, accountable approver and expiry; it cannot be
silently added to make a release green. CI diagnostic artifacts expire after seven days and
must not contain production data or credentials.

## Step 5 security evidence and release blockers

The **2026-09-21** local follow-up used Trivy **0.74.0** and refreshed both official OCI
databases. Bounded normal downloads from public ECR, GHCR and the supported GCR mirror
failed or stalled. Bounded 1 MiB range downloads from `mirror.gcr.io/aquasec/` succeeded;
the assembled layers were SHA-256 verified against the manifests, and GHCR was rechecked
at **02:05 UTC**, **03:41 UTC**, **03:59 UTC** and **04:43 UTC** before the successive integrated-image
scans, to confirm that both still matched the latest published layers.
No image, source code or SBOM was uploaded to a scanning service.

| Intelligence | Upstream `UpdatedAt` | Acquisition completed (UTC) | Verified OCI layer SHA-256 |
|---|---|---|---|
| Vulnerability DB, schema 2 | 2026-09-20 19:19:55 UTC | 2026-09-21 01:47:40 | `d42d90efdb7ecc49abcf2c796cf6478f3bab596e907fd74c786ec8070dbf6bca` |
| Java package index, schema 1 | 2026-09-19 01:01:52 UTC | 2026-09-21 02:03:16 | `cf45b46809a5759bf7b4478c52ea2b4e7f31d44ea35a2fd467cfd88af6ac3830` |

These are different timestamps: a freshly acquired Java index is not a newly published
September 21 index. The preserved upstream archives leave `DownloadedAt` unset; the
separate verified-download receipts record the actual acquisition times. Local scans reused
these verified snapshots with update-skipping flags and offline dependency identification;
hosted workflows still update normally and fail closed on errors. No ignore-unfixed setting,
finding suppression or severity-threshold change was introduced.

| Artifact/scope | Fresh complete result and limitation |
|---|---|
| Retained historical Step 5 frontend image, Debian 12.15; no longer running | **4 CRITICAL / 52 HIGH** OS entries; zero HIGH/CRITICAL Node-package entries. Its original tag and image ID are preserved |
| Frontend runtime candidates, Debian 13.7, ARM64 and emulated AMD64 | **0 CRITICAL / 0 HIGH**; 13 MEDIUM / 7 LOW OS entries remain. Fourteen OS packages and 22 ARM64 / 24 augmented AMD64 Node-package records were retained. No secret findings |
| Retained historical Step 5 backend image, Ubuntu 24.04; no longer running | **1 CRITICAL / 1 HIGH**, both in Bouncy Castle 1.84. All 108 OS and 251 Java package records were scanned; OS scope has zero HIGH/CRITICAL. Also 50 MEDIUM / 4 LOW OS and one MEDIUM Java entry; no secret findings |
| Latest integrated-source ARM64 frontend, acceptance build `1789962553-2745` | **0 CRITICAL / 0 HIGH**, no secret findings; 14 OS / 22 Node-package records. Residual OS findings: 13 MEDIUM / 7 LOW |
| Latest integrated-source ARM64 backend, acceptance build `1789962553-2745` | **0 CRITICAL / 0 HIGH**, no secret findings; 108 OS / 251 Java package records, including Bouncy Castle **1.85**. Residual findings: 50 MEDIUM / 4 LOW OS and one MEDIUM Java |
| Actual-source AMD64 frontend, `step6-candidate-amd64` | **0 CRITICAL / 0 HIGH**, no secret findings; 14 OS / 22 Node-package records. Residual OS findings: 13 MEDIUM / 7 LOW |
| Actual-source AMD64 backend, `step6-candidate-amd64` | **0 CRITICAL / 0 HIGH**, no secret findings; 108 OS / 251 Java package records, including Bouncy Castle **1.85**. Residual findings: 50 MEDIUM / 4 LOW OS and one MEDIUM Java |

The full **retained Step 5 backend image** scan completes successfully as a scan, but **fails the vulnerability
gate**. It identifies `org.bouncycastle:bcprov-jdk18on:1.84` inside
`app/app.jar/BOOT-INF/lib/bcprov-jdk18on-1.84.jar`:

- **CRITICAL `CVE-2026-8763`** — certificate name-constraints bypass.
- **HIGH `CVE-2026-13506`** — lazy ASN.1 processing denial of service.

Both record **1.85** as the fix. Its POM and published JAR checksum were confirmed at
[Maven Central](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.85/).
The integration owner subsequently updated `backend/build.gradle.kts` and
`backend/gradle.lockfile` to **1.85** and rebuilt the application. The integrated ARM64 and
actual-source AMD64 images' complete scans and packaged-JAR checksum verifications below
establish the remediation for those exact artifacts, not the retained historical Step 5 images. The earlier
September 18 database's separate OS/SBOM and filesystem results remain historical; they
do not override complete-image findings.

The historical Step 5 frontend image's four critical entries are `perl-base`
`CVE-2026-13221`, `CVE-2026-42496`, `CVE-2026-8376`, and `zlib1g`
`CVE-2023-45853`. Both frontend Dockerfiles now use the signature-verified, digest-pinned
official distroless Node 24/Debian 13 runtime documented in the
[version matrix](version-matrix.md#step-56-frontend-runtime-qualification-2026-09-21).
This replaces unnecessary runtime packages at the supported upstream-image boundary;
it does not delete required libraries or conceal package records. Node 24.21.0, glibc,
CA/TLS, ICU, native Sharp, UID/GID 10001, existing exec-form health checks, read-only mounts,
dynamic rendering/proxy/image behavior and SIGTERM were exercised in isolated containers
without published ports. Both runtime Dockerfile paths passed ARM64 checks; the AMD64
runtime was additionally checked under emulation.

**Candidate provenance matters.** The initial runtime-compatibility candidates reuse the historical Step 5
real-mode frontend
standalone artifact from image
`sha256:2c80c8f01e537e30783b9287f9c32ad81a39886693b0427ea4f8b0c85fedb465`
(built 2026-09-21 00:15:47 UTC). AMD64 adds only the already locked, SHA-512-verified
`@img/sharp-linux-x64:0.35.4` and `@img/sharp-libvips-linux-x64:1.3.3` packages.
This is not a current-source or AMD64 application rebuild. The initial backend evidence aliases
the retained historical Step 5 image
`sha256:a4d9458d64d5774c5f796fa15d4cc76a939dd702edcf4606a271c2b4fdd3dee5`.
The original Step 5 tags and image IDs are preserved, but these images are no longer running.
Security probes changed only their owned disposable resources; the integration owner's later
preview refresh is recorded below.

### Latest integrated-source ARM64 image evidence, 04:00 UTC

The parent-built acceptance images were scanned by their immutable local image IDs, not
floating tags. Their OCI index descriptors and Docker-reported repository digests match
the IDs below. The scanner's rootfs layer lists were compared with the captured image
inspection; both matched, and the original tags remained unchanged.

| Image tag | Immutable ARM64 image/index ID |
|---|---|
| `nsangusa-backend:acceptance-local-1789962553-2745` | `sha256:838c9ee4872a726853af332586d7b6d7167967031c12521a28843c4d10e010a9` |
| `nsangusa-frontend:acceptance-local-1789962553-2745` | `sha256:e607b5d4ef45237751ca443f1fb80d5d14402effdf3001e9681b333025a3b0d9` |

The earlier acceptance build `1789961351-1292` also passed its exact-image scans at
03:42 UTC. Its reports, SBOMs, IDs and checksum evidence remain unchanged as historical
evidence; the latest IDs above supersede it only as the final selected artifacts.

Complete OS/library vulnerability and secret scans retained all identified package records;
separate unchanged `HIGH,CRITICAL --exit-code 1` scans returned **0 for both images**.
Per-image CycloneDX inventories contain **360 backend / 37 frontend components**, including
the OS component. These local descriptors are not claims of registry publication, signing,
production deployment or cross-platform application acceptance.

Read-only `docker image save` inspection found only
`BOOT-INF/lib/bcprov-jdk18on-1.85.jar`. Its actual nested JAR SHA-256,
`20af26bf6060bb8005cc2389916812c1e0e998dc48d2ced7131b89461b54cff7`,
matches the checksum fetched from official Maven Central. No container was created,
executed in, stopped or modified for this check, and the disposable image export was removed.

At **2026-09-21 04:37 UTC**, the integration owner recreated **only** the preview backend
and frontend using these exact scan-qualified ARM64 IDs via separate `step6-preview` tags.
The original `step5-preview` tags/IDs remain retained historical artifacts. PostgreSQL,
Kafka, Redis, S3/MinIO, Mailpit and phase4 services were untouched; only additive V22/V23
migrations were applied, retaining one article and four accounts.

The integration owner's read-only deployed smoke and setup checks passed: the active
provider remains fake, `liveActive=false`, master configured=true, operator permission=true,
and `credential=NOT_CONFIGURED`. No paid calls were made. This local preview refresh is
not a production deployment or approval to activate a live provider.

### Actual-source AMD64 image and bounded runtime evidence, 04:45–04:47 UTC

These are **real parent-built AMD64 application images**, not the earlier preview-derived
artifact with additional native packages. The retained
`s56-{backend,frontend}-amd64-build.log` files record their builds, and their SHA-256 hashes
are included with the image provenance.

| Image tag | Immutable AMD64 image/index ID |
|---|---|
| `nsangusa-backend:step6-candidate-amd64` | `sha256:7078e44e3ae2c40527968c5468dd76316dd203c9c1383de3d89a98b246e13279` |
| `nsangusa-frontend:step6-candidate-amd64` | `sha256:c1dff8342a59b76186d04e3d38d1c2925b30e258f278dab832f4bc4dff1ac924` |

Both complete vulnerability/secret scans retained their full identified package inventory;
both unchanged HIGH/CRITICAL gates returned **0**. The per-image CycloneDX inventories
contain **360 backend / 37 frontend components**. The AMD64 backend's packaged BC 1.85
JAR independently matched the same official Maven Central checksum quoted above.

The actual AMD64 frontend passed the existing synthetic runtime fixture under local
AMD64 emulation: real Next SSR/CSP/static assets, streamed API proxying, mutations/cookies,
redirect rewriting, native Sharp encoding and Next image optimization. UID/GID 10001,
read-only rootfs enforcement, writable image cache, private writable `/tmp` mount
configuration, internal DNS, ICU, CA inventory and SIGTERM delivery were checked.
The main process exited on SIGTERM in approximately 0.11 seconds, without an OOM or
forced-kill exit. No paid/provider or preview calls were made.

Only three explicitly labelled probe containers and one **internal**, port-free network
were used. Their memory caps were **320 + 64 + 96 = 480 MiB**, with swap disabled; the
frontend's recorded cgroup peak was 246,530,048 bytes. All owned probe resources were removed.
A separate, earlier **128 MiB**, no-network backend `java --version` probe reported
Temurin **25.0.4+7**, `os.arch=amd64` and the nonroot application account. It did **not**
launch the backend application. This establishes bounded runtime/image compatibility,
**not a full functional AMD64 backend stack or AMD64 browser acceptance run**.

Local evidence is retained in
`~/.copilot/session-state/b4a28eef-17cc-4a7d-bd56-c34f6bbe958e/files/`:

- `s56-security-{db,java}-verified-download.json`,
  `s56-security-intelligence-latest-check.json`, and the verified OCI archives/cache.
- `s56-security-distroless-signature.{json,log}`: exact publisher identity, certificate
  chain and transparency-log verification, not merely a registry digest lookup.
- `s56-security-{infra-arm64,runner-arm64,infra-amd64}-release-scan.{json,log}`:
  complete vulnerability/secret scans at **01:57 UTC**; corresponding candidate SBOMs.
- `s56-security-backend-full-scan.{json,log}` and
  `s56-security-backend-full-scan-summary.json`: complete backend scan at **02:03–02:04 UTC**.
- `s56-security-candidate-provenance.json`, `s56-security-runtime-qualification.json`,
  `s56-security-amd64-native-integrity.json`, and `s56-security-runtime-contract-tests.log`.
  The latter records two passing Dockerfile contract tests; runtime checks use owned fixtures.
- `s56-security-final-arm64-scan-summary-1789962553-2745.json`: exact complete-scan,
  gate and SBOM commands, image IDs/digests, counts and per-image evidence paths.
- `s56-security-final-arm64-{backend,frontend}-1789962553-2745-scan.json`,
  matching `-gate.json` reports and `.cdx.json` inventories: actual integrated ARM64 images,
  not the earlier preview-derived runtime candidates.
- `s56-security-final-arm64-intelligence-1789962553-2745.json`,
  `s56-security-final-arm64-verification-1789962553-2745.json`, and
  `s56-security-final-arm64-bouncycastle-1789962553-2745.json`: latest published intelligence,
  exact rootfs identity and official packaged-dependency checksum verification.
- `s56-security-final-amd64-scan-summary-20260921-current-source.json` and
  `s56-security-final-amd64-{backend,frontend}-20260921-current-source-{scan,gate}.json`,
  with corresponding `.cdx.json` inventories: exact AMD64 scan/SBOM/gate commands and results.
- `s56-security-final-amd64-images-20260921-current-source.json`,
  `s56-security-final-amd64-intelligence-20260921-current-source.json`, and
  `s56-security-final-amd64-bouncycastle-20260921-current-source.json`: source-build log
  hashes, exact image/rootfs identities, latest intelligence and packaged-JAR checksum.
- `s56-security-final-amd64-runtime-20260921-current-source.json` and
  `s56-security-final-amd64-java-version-20260921-current-source.json`: bounded runtime
  commands/results, ownership labels, memory limits and owned-only cleanup.

With `EVIDENCE` set to that directory, the unchanged HIGH/CRITICAL gate can be reproduced
against both latest immutable ARM64 image IDs:

```sh
for IMAGE in \
  sha256:838c9ee4872a726853af332586d7b6d7167967031c12521a28843c4d10e010a9 \
  sha256:e607b5d4ef45237751ca443f1fb80d5d14402effdf3001e9681b333025a3b0d9
do
  "$EVIDENCE/trivy-v0.74.0/trivy" image \
    --image-src docker --platform linux/arm64 \
    --cache-dir "$EVIDENCE/s56-security-cache" --cache-backend memory \
    --skip-db-update --skip-java-db-update --offline-scan \
    --scanners vuln,secret --ignore-unfixed=false \
    --severity HIGH,CRITICAL --exit-code 1 --timeout 3m "$IMAGE" || exit "$?"
done
```

For AMD64, use `--platform linux/amd64` with the two exact AMD64 IDs above; the retained
AMD64 summary contains the complete commands used, including all-package inventory and SBOM output.

The historical `nsangusa-backend:s56-security-preview-evidence-20260921` alias remains
vulnerable and returns failure as a retained Step 5 artifact. It is not the running preview;
the refreshed preview uses the scan-qualified ARM64 IDs without modifying those historical images.

The integrated ARM64 and actual-source AMD64 image vulnerability/secret gates are satisfied
**only for the exact IDs above**. Full functional AMD64 application acceptance was not exercised
by these probes. Application/operational acceptance, manual assistive-technology acceptance
and actual hosted release protections are tracked separately; image scans do not establish
their completion. These are scanner and compatibility findings, not exploitability certification,
exception acceptance or production approval. No production deployment occurred.

## Runtime safeguards

Fake X, AI, image, and local-storage adapters are available only under explicit `local`, `test`, or
`staging` profiles. The default provider mode is disabled, and the production profile requires
`PROVIDER_MODE=production`. Production image-provider failures remain retryable and cannot silently
create fake media.

The official X adapter requests conversation, reference, and edit-history fields. New edit-chain
versions update the existing source record rather than creating a duplicate story; edited content
is excluded and dependent work is suppressed pending editorial review. A bounded lookup of
least-recently-checked retained nondeleted posts treats only an explicit official
`resource-not-found` response as deletion, then scrubs content, writes a tombstone, and
unpublishes/cancels dependent work. Retained posts continue through this reconciliation even after
an account is paused, blocked, or removed from future ingestion.

Newsletter-provider webhooks are exempt from browser CSRF because providers cannot obtain a browser
CSRF token. They remain unauthenticated only at the session layer and, in production, must pass
Resend's raw-body Svix signature and timestamp verification; `svix-id` provides duplicate replay
protection. Deleting an account immediately unsubscribes, invalidates pending confirmation, and
unlinks any associated newsletter subscription before anonymization.
Production outbound mail is restricted to the Resend adapter so the database idempotency key is
also enforced by the provider during its documented 24-hour retention window.

## X official-API compliance

- X is a **source-ingestion provider only**. The platform does not create posts, replies, reposts, likes, DMs, or publish article content to X.
- Use documented X endpoints and approved authentication for the real developer account/plan. No scraping, browser automation, shared user credentials, or rate-limit circumvention.
- Configure only accounts the product is authorized to monitor. Persist account/post identifiers, canonical URL, publication time, selected topics, and `permittedText` needed for the editorial purpose.
- Do not assume access tier, expansions, historical depth, webhook/streaming support, display rules, or retention rights. Verify current X terms at launch and on change.
- Preserve source attribution and canonical links. Do not imply endorsement by the source account.
- Do not train models on X data unless separately permitted. Send only minimum necessary source material to approved AI regions/providers under compatible retention terms.

## X data correction/deletion process

1. Identify affected account IDs/post IDs and all local `source_posts`, relationships, candidates, AI requests/results, article citations/derived text, caches, search indexes, and backups.
2. Stop further ingestion and mark the source account/post blocked while the request is assessed.
3. Delete or irreversibly redact source text and prohibited derivatives within the applicable deadline. Unpublish affected website articles when correction cannot preserve accuracy/legal basis.
4. Emit minimal internal tombstones so projections remove copies; do not put deleted text in tombstones or DLTs.
5. Reconcile PostgreSQL, Redis, Kafka-retained payload windows, object storage, AI/provider retention controls, analytics, and restored-backup deletion ledgers.
6. Record non-content evidence in audit, notify the authorized requester where required, and alert until complete. Legal hold exceptions must be authorized and reviewed.

The system does not delete posts from X because it did not create or own them.

## Prompt-injection and tool controls

1. Treat X text, comments, article content, retrieved documents, and provider responses as untrusted data.
2. Keep system policy and credentials outside prompts. Bound source material by authorization, size, topic, and classification.
3. Providers return typed analysis/draft/image results. Models cannot alter roles, monitor arbitrary accounts, fetch URLs, publish, moderate, send newsletters, or select credentials.
4. Validate claims, supporting source IDs, URLs, confidence, warnings, safety flags, HTML/Markdown, and image metadata before persistence.
5. Human approval is tied to an immutable revision; edits invalidate approval.
6. Log provider/model/prompt version, timing, counts, and safe failure codes—not prompt bodies or secret-bearing responses.

## SSRF controls and remaining deployment requirements

- Dedicated adapters accept provider base URLs from trusted configuration, not requests or model output.
- Existing provider validation rejects insecure schemes, literal private/local endpoints and
  ambiguous URL components; the image adapter rejects URL-only results instead of fetching them.
- Any future approved fetch feature must additionally normalize hosts, bound decompression and
  MIME types, and revalidate DNS/redirect/connection targets. These are requirements, not a claim
  that a generic safe fetcher currently exists.
- Production must enforce FQDN-aware egress and deny private, carrier-grade NAT, link-local,
  multicast, reserved and cloud-metadata destinations. Kubernetes CIDR policy and configuration
  validation alone do not prove that boundary.
