# Architecture and ownership

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
  SI -->|XPostDiscovered / XPostNormalized| SP[storyprocessing]
  SP -->|StoryAnalysisRequested| AI[aieditorial]
  AI -->|ArticleDraftRequested / Generated| AR[articles]
  AR -->|ArticleImageRequested| ME[media]
  ME -->|ArticleImageGenerated| AR
  AR -->|ArticleApproved| PU[publication]
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
| `identity` | Registration, credentials, profiles, roles, verification and sessions | `users`, `user_roles`, `external_identities`, `verification_tokens`; Redis session keys |
| `sourceingestion` | Monitored X accounts, official-API polling, discovery, normalization and source relationships | `monitored_x_accounts`, `blocked_source_accounts`, `source_posts`, `source_relationships` |
| `storyprocessing` | Candidate-story lifecycle and source grouping | `story_candidates` |
| `aieditorial` | Typed AI analysis/draft requests, results, confidence and provider metadata | `ai_requests`, `ai_results` |
| `articles` | Article aggregate, sources, revisions and editorial state | `articles`, `article_sources`, `article_revisions` |
| `media` | Generated image workflow and object metadata | `media_assets`, `image_generations` |
| `publication` | Website publication/unpublication/restore, schedules and evidence | `publication_records`, `scheduled_publications` |
| `comments` | Submission, thread reads, moderation and reports | `comments`, `comment_reports`, `moderation_actions` |
| `newsletter` | Double opt-in, consent, campaigns and delivery | `newsletter_subscriptions`, `consent_records`, `newsletter_campaigns`, `newsletter_deliveries` |
| `eventprocessing` | Transactional outbox, relay and consumer deduplication | `outbox_events`, `processed_events` |
| `integration` | Stable event envelope, payload records and topic mapping | No tables |
| `administration` | Operational/admin use-case endpoints; no domain ownership | No tables |
| `audit` | Security and domain audit evidence | `audit_records` |

Actual Modulith dependencies are declared in each module's `package-info.java`; `integration` has no dependencies, while workflow modules depend on `integration` and `eventprocessing`. `comments` additionally depends on `articles` and `identity`; `publication` additionally depends on `articles`.

`provider_webhooks` exists in the initial migration but has no confirmed owning implementation; assign it to a module before use rather than permitting shared writes.
