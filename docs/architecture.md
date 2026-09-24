# Architecture and ownership

This document describes the architecture and intended ownership, not production qualification of
every illustrated flow or table-backed feature. AI results and their administrative inspection are
implemented; remaining capabilities and qualification are tracked in the
[completion matrix](completion-matrix.md).

## System context

```mermaid
C4Context
  title Nsangusa system context
  Person(reader, "Reader", "Reads articles, comments and newsletters")
  Person(editor, "Editor/Administrator", "Monitors sources and reviews content")
  System(nsangusa, "Nsangusa", "X-source monitoring, AI-assisted editorial workflow and web publication")
  System_Ext(x, "X official API", "Source-post ingestion only")
  System_Ext(ai, "AI/image providers", "Structured analysis, drafts and images")
  System_Ext(mail, "Mail provider", "Verification and newsletter delivery")
  System_Ext(obs, "Observability backend", "OTLP telemetry")
  Rel(reader, nsangusa, "HTTPS")
  Rel(editor, nsangusa, "HTTPS")
  Rel(nsangusa, x, "Official HTTPS API, read/ingestion")
  Rel(nsangusa, ai, "HTTPS, allow-listed egress")
  Rel(nsangusa, mail, "SMTP/API")
  Rel(nsangusa, obs, "OTLP")
```

## Containers

```mermaid
C4Container
  title Nsangusa containers
  Person(user, "Reader/editor")
  Container(web, "Web", "Next.js 16 / React 19", "Public and authenticated UI")
  Container(app, "Application", "Java 25 / Spring Boot 4 / Modulith", "REST, workflow, ingestion and adapters")
  ContainerDb(pg, "PostgreSQL 18", "SQL", "System of record and outbox")
  ContainerDb(redis, "Redis 8.10", "Key/value", "Sessions, rate limits and cache")
  ContainerQueue(kafka, "Kafka 4.3.1", "Events", "Asynchronous workflow")
  ContainerDb(objects, "Object storage", "S3 API", "Generated media")
  Container(otel, "OTel Collector", "OpenTelemetry", "Telemetry routing")
  System_Ext(ext, "X / AI / image / mail")
  Rel(user, web, "HTTPS")
  Rel(web, app, "HTTPS/JSON")
  Rel(app, pg, "TLS SQL")
  Rel(app, redis, "TLS")
  Rel(app, kafka, "TLS/SASL")
  Rel(app, objects, "TLS/S3")
  Rel(app, ext, "HTTPS or authenticated mail transport")
  Rel(app, otel, "OTLP")
```

## Module flow

```mermaid
flowchart LR
  X[X official API] --> SI[sourceingestion]
  SI -->|discovery / edit-chain lookup / deletion reconciliation| SP[storyprocessing]
  SP -->|clustered StoryAnalysisRequested| AI[aieditorial]
  AI -->|ArticleDraftRequested / Generated or StoryAnalysisBlocked| AR[articles]
  AR -->|ArticleImageRequested| ME[media]
  ME -->|ArticleImageCandidateGenerated / ArticleImageApproved| AR
  AR -->|ArticleReadyForReview / ArticleApproved| PU[publication]
  PU -->|ArticlePublished| NL[newsletter]
  AR --> CO[comments]
  ID[identity] --> CO
  EP[eventprocessing] --> K[(Kafka)]
  SI --> EP
  SP --> EP
  AI --> EP
  AR --> EP
  ME --> EP
  PU --> EP
  CO --> EP
  NL --> EP
  IN[integration contracts] -.-> SI & SP & AI & AR & ME & PU & CO & NL & EP
  AD[administration] --> OPS[Operational summary]
  AU[audit] --> AUD[(Audit records)]
```

## Responsibilities and table ownership

| Module | Responsibility | Owned tables |
|---|---|---|
| `identity` | Registration, credentials, profiles, versioned role administration, verification and session revocation | `users`, `user_roles`, `external_identities`, `verification_tokens`, `identity_administration_guard`; indexed Redis session keys |
| `sourceingestion` | Monitored X accounts, official-API polling, discovery, normalization and source relationships | `monitored_x_accounts`, `blocked_source_accounts`, `source_posts`, `source_relationships` |
| `storyprocessing` | Time-bounded topic/conversation clustering and candidate lifecycle | `story_candidates`, `story_candidate_sources` |
| `aieditorial` | Typed AI requests/results, immutable prompt/configuration versions and generation provenance | `ai_requests`, `ai_results`, `ai_prompt_versions`, `ai_configuration_versions` |
| `articles` | Article aggregate, sources, revisions and editorial state | `articles`, `article_sources`, `article_revisions` |
| `media` | Generated image workflow and object metadata | `media_assets`, `image_generations` |
| `publication` | Website publication/unpublication/restore, schedules and evidence | `publication_records`, `scheduled_publications` |
| `comments` | Submission, thread reads, moderation and reports | `comments`, `comment_reports`, `moderation_actions` |
| `newsletter` | Double opt-in, consent, account linking, preferences, campaigns, delivery evidence and reconciliation | `newsletter_subscriptions`, `consent_records`, `newsletter_campaigns`, `newsletter_deliveries`, `newsletter_preference_links`, `newsletter_delivery_attempts` |
| `eventprocessing` | Outbox/relay, deduplication, durable command receipts, failure inventory and confirmed replay | `outbox_events`, `processed_events`, `request_idempotency`, `failed_events`, `event_replay_requests`, `event_replay_records` |
| `integration` | Stable event envelope, payload records and topic mapping | No tables |
| `administration` | Operational/admin use-case endpoints; no domain ownership | No tables |
| `audit` | Security and domain audit evidence | `audit_records` |
| `search` | Public full-text search and topic/tag projections | `article_search_documents` |

Actual Modulith dependencies are declared in each module's `package-info.java`; `integration` has no
dependencies, while workflow modules depend on `integration` and `eventprocessing`. `articles`
additionally uses the public `sourceingestion` eligibility service for defense-in-depth publication
checks; `comments` depends on `articles` and `identity`; `publication` depends on `articles`.

Publication visibility uses a synchronous internal `ArticleVisibilityChanged` event and the
public search service in the article transaction. Durable Kafka events remain available for other
consumers and reconciliation; delayed search events reconcile the current locked article state.
Public article/search/feed/sitemap responses use no-store. Redis is not authoritative for
publication visibility, and production capacity/CDN cutover remain qualification gates.

`provider_webhooks` is owned by `newsletter`.

Identity accesses linked newsletter preferences only through public newsletter services, never
newsletter SQL/repositories. Comments uses the identity public directory/display-name boundary;
moderators receive no general user email/role inventory. Role changes persist security stamps,
then revoke indexed sessions after commit; request-time authorization rejects obsolete stamps.

AI administration can select only deployed capabilities/models and stored prompt versions.
Provider URLs, credentials, the deployment model ceiling and image configuration remain operator
controls. Audit owns bounded evidence queries; administration composes public audit/workflow
services without accessing their repositories.

Spring Modulith enforces module boundaries and provides runtime observability. Durable transport
uses the application's PostgreSQL outbox/inbox and Spring Kafka, not Modulith's separate JPA event
publication registry or Kafka externalization. Unused persistence/externalization starters are
excluded so there is one explicit durability mechanism and no unmigrated second registry.

Kafka transports validated JSON envelope strings. The event/provider boundary retains an explicit
Jackson 2 mapper; Spring Boot 4 MVC uses its Jackson 3 mapper. API responses use ordinary DTOs,
records and maps rather than exposing Jackson 2 tree objects through Jackson 3 serialization.
