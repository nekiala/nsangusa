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

**Decision:** Browser authentication uses verified local credentials, form login/HTTP Basic, Redis-backed server sessions, CSRF cookies, and server-side roles. An OIDC adapter exists; production tenant/claims/logout behavior requires separate qualification.
**Why:** Central revocation and no bearer token in browser JavaScript.  
**Consequences:** Session rotation/expiry, Redis availability, CSRF, credential recovery, rate limiting and secure cookie configuration are operational requirements.

## ADR-006: Safe content pipeline

**Decision:** Official-API X data passes normalization, relevance/story analysis, typed AI generation, safety/schema checks, human approval, revision freeze, website publication, and contextual rendering.  
**Why:** Defends against prompt injection, misinformation, XSS, policy breach, and accidental publication.  
**Consequences:** No route bypasses approval; generated media receives safety metadata and alt text; public source attribution remains visible.

## ADR-007: Reviewed corrections withdraw the canonical article

**Decision (2026-09-12):** Starting a correction withdraws the currently published article (or
uses its already-unpublished record), records a required correction note and returns it to human
review. Editing, approval and publication then produce full immutable snapshots of content,
metadata, sources, image selection and approval. The original slug, ID and publication timestamp
remain unchanged. Republished corrections and restored articles are not newsletter-eligible.

**Why:** A conservative workflow avoids exposing unreviewed text or maintaining competing live
and draft aggregates. Publication temporarily returns 404 rather than displaying a known incorrect
version. Editors explicitly acknowledge this withdrawal in the browser.

**Consequences:** This is not live shadow-revision editing. Original pre-migration revisions remain
explicitly partial; missing evidence is never reconstructed from current content. Source
invalidation removes newly retained snapshot/AI content; complete historical/provider retention
qualification remains separate.

## ADR-008: Current public visibility takes precedence over caching

**Decision (2026-09-12):** Article/discovery responses are no-store; public frontend rendering
does not retain publication data in ISR. Synchronous internal article-visibility events update the
search projection in the publication transaction. Durable Kafka events reconcile that projection
using the current locked article state, including after delayed unpublication events.

**Why:** Redis/ISR/CDN expiry must not keep withdrawn or unreviewed corrections publicly visible.
Ordinary local consistency does not require a Kafka round trip.

**Consequences:** This deliberately trades caching performance for correctness. Production
capacity must be measured; a later cache design requires version-aware invalidation and explicit
approval. Deployment must purge any pre-existing external CDN caches. Browser history or external
copies cannot be retroactively erased by the application.

## ADR-009: Durable editor retry receipts

**Decision (2026-09-12):** Editorial commands accept an actor-scoped `Idempotency-Key`. A
transaction-scoped advisory lock serializes matching requests; canonical payload hashes and
success results commit with the domain mutation and outbox. Changed targets/payloads/versions
return 409. The UI preserves its key when retrying an unchanged action.

**Consequences:** No credentials or article payloads are copied into receipts. Legacy no-key
callers retain existing behavior. Authorization is checked on every attempt, including replay.
This is duplicate protection for PostgreSQL effects, not exactly-once external image/email effects.
Receipt retention must exceed the agreed client replay window.
Client retry identities are bounded and in-memory: transport/server/decoding failures retain them
until a successful retry, while authentication changes and reloads clear them. There are no
automatic mutation retries; reconcile unconfirmed actions before restarting the session.

## ADR-010: Schedules publish the reviewed version

**Decision (2026-09-12):** Each active schedule records the article version and responsible editor.
Versioned reschedule/cancel actions, per-item transactions and database claims protect concurrent
editors and schedulers. Failure metadata survives a rolled-back publication; one bad article does
not block the rest of the due batch. Historical schedules without version evidence require
cancellation and explicit recreation.

**Consequences:** Scheduled content must be cancelled before editing. A changed image or source
invalidates the captured version rather than silently publishing different material. Approved-source
and draft-generation-only policies are explicit configuration; production remains human-review-only.

## ADR-011: Bounded, versioned administration

**Decision (2026-09-12):** Role, community, newsletter, AI and event controls use discoverable
inventories, backend role checks, current versions, durable mutation receipts and sensitive-action
audit. Role changes persist authentication stamps and revoke indexed sessions after commit;
request checks reject old authorization even if session cleanup is delayed. Identity accesses
newsletter ownership through public services, not another module's tables.

**Consequences:** The user confirms the selected account or operational effect, rather than typing
an internal UUID. Moderator community discovery excludes email/role administration. Data export
describes the implemented account data, not an unimplemented cross-store legal-retention export.

## ADR-012: Immutable editorial configuration within deployment limits

**Decision (2026-09-12):** Administrators publish immutable guidance and choose a stored
provider/model/prompt configuration within the deployed catalog. Java requests persist and use
that snapshot. Hard safety instructions and publication policy are not mutable prompt guidance.

**Consequences:** The browser cannot supply credentials, arbitrary URLs, unsupported capabilities
or models outside the operator ceiling. Provider bindings/model ceilings and image models remain
deployment controls. This intentionally favors approved integrations over a generic provider
configuration form; expanding the catalog requires adapter and policy qualification.

## ADR-013: Confirm replay evidence; do not infer email delivery

**Decision (2026-09-12):** Replay UI confirmation uses the requesting actor's fresh completed dry
run, exact eligible records and a unique durable replay job. Workers share persistent rate windows.
Newsletter administration distinguishes provider acceptance from signed delivery confirmation;
uncertain sends require evidence-backed terminal reconciliation, never a blind resend.

**Consequences:** Workflow counts are not dependency health probes. SMTP acceptance alone cannot
prove inbox delivery or justify a retry after an uncertain external send.
