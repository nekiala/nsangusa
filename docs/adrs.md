# Architecture decision records

Status: **Accepted, 2026-09-02**, unless stated otherwise.

## ADR-001: Spring Modulith modular monolith

**Decision:** Deploy one Spring Boot application with the 13 implemented modules and `package-info.java` dependency rules.  
**Why:** The workflow needs strong local transactions and is not yet operationally justified as distributed services.  
**Consequences:** Enforce module verification, public APIs/events, table ownership, module-level tests and telemetry; extract only from measured pressure.

## ADR-002: PostgreSQL transactional outbox

**Decision:** Persist domain changes and `outbox_events` atomically; relay to Kafka; deduplicate with `processed_events`.  
**Why:** Prevent database/Kafka dual-write loss.  
**Consequences:** At-least-once delivery, lag monitoring, cleanup, replay controls, and idempotent consumers are mandatory.

## ADR-003: Human editorial review

**Decision:** AI analysis, article drafts, and generated images remain advisory. An editor/administrator approves the current article revision before website publication.  
**Why:** Source material and model output can be wrong, unsafe, manipulated, or legally restricted.  
**Consequences:** Material edits invalidate approval; review evidence, warnings, claims, sources, and actor are retained. No X publishing occurs.

## ADR-004: Provider abstraction

**Decision:** `aieditorial`, `media`, source ingestion, mail, and storage depend on internal ports; production/fake adapters map typed requests and capabilities.  
**Why:** Keep provider semantics and credentials out of domain modules and support deterministic local development.  
**Consequences:** Switching requires safety, schema, privacy, cost, latency and quality qualification; fallback is explicit, observable, and never silently changes approved content.

## ADR-005: Server-side session authentication

**Decision:** Current browser authentication uses verified local credentials, form login/HTTP Basic, Redis-backed server sessions, CSRF cookies, and server-side roles. OIDC is a separately reviewed future adapter.  
**Why:** Central revocation and no bearer token in browser JavaScript.  
**Consequences:** Session rotation/expiry, Redis availability, CSRF, credential recovery, rate limiting and secure cookie configuration are operational requirements.

## ADR-006: Safe content pipeline

**Decision:** Official-API X data passes normalization, relevance/story analysis, typed AI generation, safety/schema checks, human approval, revision freeze, website publication, and contextual rendering.  
**Why:** Defends against prompt injection, misinformation, XSS, policy breach, and accidental publication.  
**Consequences:** No route bypasses approval; generated media receives safety metadata and alt text; public source attribution remains visible.

