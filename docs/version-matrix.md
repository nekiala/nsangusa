# Resolved production version matrix

Verified on **2026-09-02** against official project documentation, Spring Initializr metadata, Maven Central release metadata, npm release metadata, and container registries. Compatibility and reproducibility take precedence over a numerically newer but unqualified tool.

| Component | Resolved version | Decision |
|---|---:|---|
| Java | 25 LTS; validation runtime Temurin 25.0.4.1 | Spring Boot 4.1.1 supports Java through 26 |
| Spring Boot | 4.1.1 | Current stable production line |
| Spring Framework | 7.0.9+ managed by Boot | Boot requirement |
| Spring Modulith | 2.1.1 | Stable line documented for Boot 4.1 |
| Spring AI | 2.0.1 evaluated | Not linked at runtime; explicit `RestClient` provider adapters were selected for fixed egress, payload, retry, and schema control |
| Gradle | 9.7.1 wrapper | Stable, checksum-validated wrapper |
| Spring dependency-management plugin | 1.1.7 | Pinned |
| Springdoc OpenAPI | 3.1.0 | Stable Boot 4-compatible line |
| AWS SDK for Java | 2.54.10 | S3-compatible production adapter |
| Testcontainers | 2.0.5 | PostgreSQL, Kafka, Redis-capable integration tests |
| ArchUnit | 1.4.2 | Version compatible with Spring Modulith 2.1.1 |
| OWASP Java Encoder | 1.4.0 | Contextual output encoding support |
| Bouncy Castle | 1.82 | Argon2id implementation dependency |
| Spotless / google-java-format | 8.10.1 / 1.30.0 | Java 25-compatible formatting |
| CycloneDX Gradle plugin | 3.0.1 | Backend SBOM generation |
| Next.js | 16.3.4 | Official docs stable version |
| React / React DOM | 19.2.8 | Pinned compatible release |
| Node.js | 24.20.0 LTS | Production LTS |
| TypeScript | 5.9.3 | Qualified by the selected Next.js toolchain |
| ESLint / eslint-config-next | 9.39.1 / 16.3.4 | Qualified together |
| Vitest | 4.0.8 | Qualified frontend unit runner |
| Playwright | 1.58.2 | Qualified browser test runner |
| PostgreSQL | 18.4 | Exact development image tag |
| Apache Kafka | 4.3.1 | Supported broker release; Spring Kafka client remains protocol compatible |
| Redis | 8.10.0 | Exact development image tag |
| MinIO | RELEASE.2025-04-22T22-12-26Z | Exact development-only object-store tag |
| Mailpit | 1.27.8 | Exact development-only mail sink tag |
| OpenTelemetry Collector Contrib | 0.132.0 | Exact collector tag |
| Helm | 3.18.4 | Chart validation and deployment CLI |

Backend dependencies are locked in `backend/gradle.lockfile`; frontend dependencies and integrity hashes are locked in `frontend/package-lock.json`. Production images are published with immutable release/SHA tags, provenance, and SBOMs; deployment values support digest pins.
