# Selected version matrix and verification evidence

Original selection date: **2026-09-06**. Core release availability and compatibility provenance
were rechecked on **2026-09-12** using the official sources below. This is not a claim that every
entry is the newest release or has passed production qualification. Compatibility and
reproducibility take precedence over a numerically newer but unqualified tool.

| Component | Resolved version | Decision |
|---|---:|---|
| Java | 25 LTS; validation runtime Temurin 25.0.4.1 | Spring Boot 4.1.1 supports Java through 26 |
| Spring Boot | 4.1.1 | Selected stable production release |
| Spring Framework | 7.0.9+ managed by Boot | Boot requirement |
| Spring Modulith | 2.1.1 | Published POM and tagged build target Boot 4.1.1; the fetched documentation compatibility table is stale |
| Spring AI | 2.0.1 evaluated | Not linked at runtime; explicit `RestClient` provider adapters were selected for fixed egress, payload, retry, and schema control |
| Gradle | 9.7.1 wrapper | Stable, checksum-validated wrapper |
| Foojay toolchain resolver | 1.0.0 | Stable Gradle settings plugin used to provision Java 25 reproducibly |
| Spring dependency-management plugin | 1.1.7 | Pinned |
| Springdoc OpenAPI | 3.1.0 | Stable Boot 4-compatible line |
| AWS SDK for Java | 2.54.10 | S3-compatible production adapter |
| Testcontainers | 2.0.5 | PostgreSQL, Kafka, Redis-capable integration tests |
| ArchUnit | 1.4.2 | Version compatible with Spring Modulith 2.1.1 |
| OWASP Java Encoder | 1.4.0 | Contextual output encoding support |
| Embedded Tomcat | 11.0.25 | Step 5 targeted security override aligns core, EL and websocket |
| Bouncy Castle | 1.85 | Exact current-source ARM64 and AMD64 image scans are clear of the previous CRITICAL/HIGH entries; both packaged JARs match the official Maven Central SHA-256 |
| Networknt JSON Schema Validator | 1.5.9 | Test-only Draft 2020-12 executable payload contracts |
| Spotless / google-java-format | 8.10.1 / 1.30.0 | Java 25-compatible formatting |
| CycloneDX Gradle plugin | 3.0.1 | Backend SBOM generation |
| Next.js | 16.3.4 | Published exact-version registry entry; compatible React/Node peer ranges |
| React / React DOM | 19.2.8 | Pinned compatible release |
| Node.js | 24.20.0 LTS builder / 24.21.0 LTS runtime | Builder remains pinned; the supported Node 24 runtime patch preserves module ABI 137 |
| Frontend runtime | Distroless Node 24, Debian 13.7 | Signature-verified multiarchitecture digest; replaces the vulnerable Bookworm runtime without stripping package inventory |
| TypeScript | 5.9.3 | Qualified by the selected Next.js toolchain |
| ESLint / eslint-config-next | 9.39.1 / 16.3.4 | Qualified together |
| Vitest | 4.0.8 | Qualified frontend unit runner |
| Playwright | 1.58.2 | Qualified browser test runner |
| PostgreSQL | 18.4 | Available exact development image tag; official support page now lists a newer minor, requiring normal maintenance review |
| Apache Kafka | 4.3.1 | Supported broker release; Spring Kafka client remains protocol compatible |
| Redis | 8.10.0 | Exact development image tag |
| MinIO | RELEASE.2025-04-22T22-12-26Z | Development only; official Quay distribution replaces unavailable Docker Hub reference; historical community binaries are not a production recommendation |
| MinIO client | RELEASE.2025-05-21T01-59-54Z | Exact official Quay image for local bucket initialization |
| Mailpit | 1.27.8 | Exact development-only mail sink tag |
| OpenTelemetry Collector Contrib | 0.132.0 | Exact collector tag |
| Kubernetes | 1.36.4 | Supported deployment baseline; maintained through 2027-06-28 |
| k3s staging distribution | 1.36.4+k3s1 | Official stable release, Linux AMD64 binary and vendor checksum manifest pinned in `infrastructure/k3s/staging/release.json`; single-node shared-VPS bootstrap, not HA/production qualification |
| Certbot on staging VPS | Ubuntu package 4.0.0-4 | Signed Ubuntu distribution package; isolated webroot issuance, renewal sandbox and certificate-activation checks exercised on 2026-09-22 |
| Helm | 4.2.4 | Current stable line compiled for Kubernetes 1.36 |
| kubeconform | 0.8.0 | Kubernetes manifest validation, archive checksum pinned in CI |
| Trivy | 0.74.0 | Step 5 scan runtime pinned explicitly; the older action default's 0.65.0 release was unavailable |
| X API | v2 contract 2.168 | Official user-post timeline and batch post lookup contracts verified for edit history, conversation metadata, and bounded 100-ID reconciliation |
| Resend SMTP | Contract verified 2026-09-06 | `Resend-Idempotency-Key` is retained for 24 hours; production retries are bounded to that window |

Backend dependencies are locked in `backend/gradle.lockfile`; frontend dependencies and integrity hashes are locked in `frontend/package-lock.json`. Production images are published with immutable release/SHA tags, provenance, and SBOMs; deployment values support digest pins.

Step 5 workflow actions use immutable upstream commit IDs rather than floating action tags.
Trivy 0.74.0 was resolved from the [official release](https://github.com/aquasecurity/trivy/releases/tag/v0.74.0);
the local ARM64 archive was checked against its published SHA-256 list. These provenance checks
are not an independent security audit or evidence that the hosted release workflow has run.

## Official source provenance, 2026-09-12

| Selection | Source and scope of evidence |
|---|---|
| Java 25 | [Oracle LTS roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) lists Java 25 as LTS. The Gradle-resolved compiler/runtime is distinct from the developer shell's JVM |
| Boot / Java / Gradle | [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) identifies 4.1.1, Java 17-26 and Gradle 8.14+/9.x; [published BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) establishes the dependency baseline |
| Modulith | [Published 2.1.1 core POM](https://repo.maven.apache.org/maven2/org/springframework/modulith/spring-modulith-core/2.1.1/spring-modulith-core-2.1.1.pom) and [tagged build](https://github.com/spring-projects/spring-modulith/blob/2.1.1/pom.xml) establish Boot 4.1.1/Framework 7.0.9/ArchUnit 1.4.2 alignment |
| Gradle | [9.7.1 release notes](https://docs.gradle.org/9.7.1/release-notes.html), [Java compatibility](https://docs.gradle.org/9.7.1/userguide/compatibility.html), and [published distribution checksum](https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256); checksum matches the wrapper pin |
| Next / React | Exact [Next 16.3.4](https://registry.npmjs.org/next/16.3.4), [React 19.2.8](https://registry.npmjs.org/react/19.2.8) and [React DOM 19.2.8](https://registry.npmjs.org/react-dom/19.2.8) registry manifests establish publication/integrity and mutually compatible peer ranges, not latest-version status |
| Node | [24.20.0 LTS release](https://nodejs.org/en/blog/release/v24.20.0) and [artifact checksums](https://nodejs.org/dist/v24.20.0/SHASUMS256.txt) establish release availability |
| Kafka | [Apache 4.3.1 distributions](https://downloads.apache.org/kafka/4.3.1/) and [protocol compatibility](https://kafka.apache.org/43/design/protocol/#compatibility); the locked application client is 4.2.1, distinct from the broker |
| PostgreSQL | [18.4 notes](https://www.postgresql.org/docs/release/18.4/) and [support policy](https://www.postgresql.org/support/versioning/) establish availability and the need to review newer supported minor releases |
| Redis | [Official 8.10.0 release](https://github.com/redis/redis/releases/tag/8.10.0) and [container tag](https://hub.docker.com/v2/repositories/library/redis/tags/8.10.0) establish GA availability |
| MinIO | [Selected release's upstream instructions](https://github.com/minio/minio/blob/RELEASE.2025-04-22T22-12-26Z/README.md) identify Quay as official distribution. Both pinned manifests were inspected, images pulled, local server health verified and bucket initializer exited successfully |
| X account lookup | [Official username lookup contract](https://docs.x.com/x-api/users/get-user-by-username) documents `GET /2/users/by/username/{username}`; account access and downstream processing rights remain separate qualification gates |

The backend runtime image's previous digest failed manifest verification. The replacement
`eclipse-temurin:25.0.4_7-jre-noble@sha256:d120abd9d8d7dec94520ce974ece62d0e4eed8576eb00bbc84e6128307ab48ef`
was verified by manifest inspection against the
[official tag metadata](https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/25.0.4_7-jre-noble).
This confirms artifact availability, not a completed application container build or deployment.

## Step 5/6 frontend runtime qualification, 2026-09-21

Both frontend Dockerfiles now select
`gcr.io/distroless/nodejs24-debian13:nonroot@sha256:bb6b03d81066993293a10feda7250e8e1cc034035fe9b61cfceededa7c8bf04d`.
The immutable index includes Linux ARM64
`sha256:0ce5a33cf48ff8d2d38c096ae52e260c50d529aee26d23a07e35505f5c9ff752`
and AMD64
`sha256:7924c53f56526359d0f491c22517306d8d92f1b285656a6094398e2c55bbaeca`.
The [official image list](https://github.com/GoogleContainerTools/distroless#what-images-are-available),
[support policy](https://github.com/GoogleContainerTools/distroless/blob/main/SUPPORT_POLICY.md)
and [Node 24.21.0 release](https://nodejs.org/en/blog/release/v24.21.0) establish
the supported upstream runtime, not application release approval.

Cosign 3.1.3 verified the index signature, certificate chain and transparency-log evidence
with issuer `https://accounts.google.com` and exact identity
`keyless@distroless.iam.gserviceaccount.com`. No compatible verifier was previously available;
the official Cosign container was used for this check. Its immutable digest
`sha256:9e5c2f2edc34351160407ca3416c61855bdf9403c3c5936e0f0be7fc261611b8`
matched the official GHCR and GCR distributions.

The runtime retains glibc (2.41), OpenSSL, CA certificates, timezone data, full Node ICU
(78.3) and upstream Debian package records. It does not inherit unnecessary build-time
shell/package-manager packages. UID/GID 10001, a resolvable application account and `/app`
home are retained; `/nodejs/bin` is added to `PATH` so existing exec-form `node` health
checks still work. Node is PID 1 through an exec-form entrypoint and receives SIGTERM.
Read-only operation still requires writable `/tmp` and `/app/.next/cache` mounts.

Isolated ARM64 and emulated AMD64 checks passed DNS/TLS/CA, ICU locales, native Sharp
encoding and Next image optimization, dynamic rendering/CSP, static assets, streamed
API proxying, cookies/CSRF forwarding, redirects, read-only operation and signal delivery.
Both Dockerfile runtime paths were exercised on ARM64. The initial AMD64 probe used the same historical Step 5
JavaScript artifact plus the **already locked**, integrity-verified AMD64 Sharp/libvips
packages; this is compatibility evidence, **not an AMD64 application rebuild**.

The initial compatibility candidates derive from the retained historical Step 5 real-mode frontend image
`sha256:2c80c8f01e537e30783b9287f9c32ad81a39886693b0427ea4f8b0c85fedb465`.
They are not builds of subsequently integrated application changes. Complete fresh scans
found no HIGH/CRITICAL entries, with 14 OS package records retained per candidate.
See [the security evidence](security-compliance.md#step-5-security-evidence-and-release-blockers)
for scan scope, residual findings and evidence identifiers. A subsequent integrated-source
ARM64 frontend image, now selected as
`sha256:e607b5d4ef45237751ca443f1fb80d5d14402effdf3001e9681b333025a3b0d9`,
also passed complete vulnerability/secret scanning and the unchanged HIGH/CRITICAL gate at
04:00 UTC, with a retained per-image CycloneDX inventory. Earlier acceptance-build evidence
from 03:42 UTC remains historical and unchanged. That ARM64 result alone does not qualify
AMD64 images or satisfy hosted release protections. The original Step 5 tags/IDs remain
preserved, but are no longer running: at 04:37 UTC the integration owner recreated only
the preview frontend/backend using the qualified ARM64 v2 IDs via separate `step6-preview`
tags. Supporting infrastructure and phase4 services were untouched.

At **04:45 UTC**, separate **actual current-source AMD64 builds** also passed complete
vulnerability/secret scans and the unchanged HIGH/CRITICAL gate, with per-image SBOMs:

- Backend: `sha256:7078e44e3ae2c40527968c5468dd76316dd203c9c1383de3d89a98b246e13279`.
- Frontend: `sha256:c1dff8342a59b76186d04e3d38d1c2925b30e258f278dab832f4bc4dff1ac924`.

The actual AMD64 frontend subsequently passed the owned synthetic SSR/proxy/static/CSP,
Sharp/image optimization, nonroot/read-only/cache, private temporary mount and SIGTERM
fixture under local emulation. Its three isolated containers had a combined **480 MiB**
memory cap and no published ports or external network route; all were removed.
A separate 128 MiB, no-network backend probe confirmed **Temurin 25.0.4+7 / amd64**.
This is not a full functional AMD64 backend stack or browser acceptance run. Exact commands,
source-build log hashes, package inventories, checksums and runtime scope are retained in
the security evidence; no production deployment is implied.
