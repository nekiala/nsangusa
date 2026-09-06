# Implementation status

## Implemented

- Java 25 / Spring Boot 4.1.1 modular monolith with verified Spring Modulith boundaries.
- Official-X-API production polling adapter plus fake local ingestion, monitored-account pause/resume, deduplication, normalization, configured-topic relevance, source trail, and rate-limit status capture.
- X account/source administration, account blocking, post exclusion, edit/deletion reconciliation, compliance tombstones, retention processing, and publication safeguards.
- Kafka event envelope, PostgreSQL transactional outbox, at-least-once relay, processed-event deduplication, delayed retry topics, DLT metadata, poison-message controls, correlation IDs, and audited dry-run/replay operations.
- Provider-independent Java AI/image interfaces, deterministic fake providers, structured validated drafts, prompt boundaries, SSRF-restricted production HTTP adapters, usage records, S3-compatible storage, and mandatory human review.
- Editorial image hero/thumbnail/social variants, optimization/metadata stripping, disclosed fallback handling, review/regeneration APIs, and publication approval checks.
- Article creation, editing with optimistic locking, approval, rejection, immediate/scheduled publication, unpublication, restoration, archiving, source attribution, revisions, Redis public caches, and PostgreSQL full-text search with topic/tag indexes.
- Argon2id local credentials, registration, short-lived email verification/reset tokens, OIDC account provisioning/linking, session listing/revocation, brute-force lockout, profile updates, account export/deletion, CSRF, RBAC, rate limiting, security headers, and demo users.
- Authenticated comments with plain-text XSS controls, limited replies, editing windows, soft deletion, abuse reports, spam scoring, suspensions, configurable policies, moderation queues/history, and auditing.
- Double-opt-in newsletter subscriptions, signed preference/unsubscribe tokens, immediate and digest campaigns, multipart email, duplicate-safe delivery records, signed provider webhooks, and bounce/complaint suppression.
- Next.js public/editorial/admin experience connected to live versioned APIs, metadata, dynamic sitemap, robots, RSS, accessibility-focused two-color design, and frontend tests.
- Flyway schema, OpenAPI/event contracts, Compose dependencies, Helm deployments/policies/autoscaling/PDB/OTel, CI, immutable image workflows, SBOM generation, and operations/security documentation.
- Production-profile startup validation rejects missing credentials, placeholder secrets, weak newsletter secrets, and non-TLS public URLs.

## External production qualification required

- Approved X credentials and plan details are required to qualify synchronization limits, retained fields, deletion timing, and attribution against the account's binding X terms.
- AI and image provider credentials, selected models, region/retention terms, quotas, and pricing are required for model-quality, safety, latency, and cost acceptance tests.
- An OIDC tenant is required to validate issuer metadata, redirect URIs, claims/groups mapping, MFA policy, logout, and step-up behavior with the selected identity provider.
- Email provider credentials and signing documentation are required to validate delivery, bounce, complaint, suppression, and webhook behavior end to end.
- Production PostgreSQL, Kafka, Redis, S3, secret-manager, DNS/TLS, registry, and Kubernetes details are required for environment-specific load, recovery, security, and deployment acceptance.
- Docker image builds and the complete Compose runtime were not executed because Docker is unavailable in this environment. Java 25 backend compilation/tests, frontend tests/lint/build/E2E, Helm rendering, contracts, and dependency audits were validated without paid APIs.
