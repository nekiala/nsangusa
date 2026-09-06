# Kafka events and delivery

Canonical JSON Schemas are in `../contracts/events`.

## Implemented topics

| Topic | Events |
|---|---|
| `news.ingestion.v1` | `XPostDiscovered`, `XPostNormalized` |
| `news.editorial.v1` | `StoryAnalysisRequested`, `ArticleDraftRequested`, `ArticleDraftGenerated`, `ArticleImageRequested`, `ArticleImageGenerated`, `ArticleReadyForReview` |
| `news.publication.v1` | `ArticleApproved`, `ArticlePublished` |
| `news.notifications.v1` | `NewsletterDispatchRequested`, `CommentSubmitted` |

Each local topic has a corresponding `.dlt` topic created by the Compose init service.

## Envelope

Every event contains exactly:

`eventId`, `eventType`, `schemaVersion`, `aggregateId`, `correlationId`, nullable `causationId`, `timestamp`, `producer`, string-map `traceContext`, `idempotencyKey`, and typed `payload`.

Use `aggregateId` as the Kafka key for per-aggregate ordering. `eventId` identifies delivery; `idempotencyKey` identifies the business effect. Payloads contain permitted facts only—never provider tokens, passwords, session identifiers, full provider responses, or unrestricted source data.

## Evolution

1. Draft 2020-12 schemas are immutable after release.
2. Within a schema major version, add only optional fields with defined semantics; never repurpose or narrow an existing field.
3. Breaking payload or semantic changes create a new schema/topic major version and a dual-publish/adapter migration.
4. Producers validate before outbox insertion; consumers reject unsupported versions and tolerate unknown compatible fields.
5. CI checks schema validity, examples, and compatibility. Registry features depend on the selected production platform.

## Retry, DLT, and replay

- The database transaction writes domain state and `outbox_events`; the relay publishes and marks it only after acknowledgement. Delivery is at least once.
- Consumers insert `(event_id, consumer_name)` in `processed_events` transactionally with their database effect.
- Retry transient failures with bounded exponential backoff and jitter. Validation, unsupported schema, authorization, or invariant failures go directly to `<topic>.dlt`.
- DLT metadata preserves original bytes/headers, safe error code, consumer version, attempts, and timestamps; redact content-bearing exception messages.
- Replay copies an authorized offset/time range to a controlled replay path with operator, reason, replay ID, dry-run count, and rate limit. Consumers and external side effects remain idempotent.
- Kafka transactions provide exactly-once only for compatible Kafka read/process/write paths. They do not atomically include PostgreSQL, X/AI/image/mail HTTP calls, object storage, or Redis. Business outcomes are effectively once through outbox, dedupe, unique constraints, and reconciliation.

