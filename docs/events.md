# Kafka events and delivery

Canonical JSON Schemas are in `../contracts/events`.

## Implemented topics

| Topic | Events |
|---|---|
| `news.ingestion.v1` | `XPostDiscovered`, `XPostNormalized` |
| `news.editorial.v1` | `StoryAnalysisRequested`, `StoryAnalysisBlocked`, `ArticleDraftRequested`, `ArticleDraftGenerated`, `ArticleImageRequested`, `ArticleImageCandidateGenerated`, legacy `ArticleImageGenerated`, `ArticleImageApproved`, `ArticleReadyForReview` |
| `news.publication.v1` | `ArticleApproved`, `ArticlePublished`, `ArticleUnpublished` |
| `news.notifications.v1` | `NewsletterDispatchRequested`, `CommentSubmitted` |

Each local topic has a corresponding `.dlt` topic with the same partition count, created
by the Compose init service. No `.retry` topics are used. Existing retry-topic backlogs
from older installations require reviewed retirement before migration; do not silently
delete or drain them.

## Envelope

Every event contains exactly:

`eventId`, `eventType`, `schemaVersion`, `aggregateId`, `correlationId`, nullable `causationId`, `timestamp`, `producer`, string-map `traceContext`, `idempotencyKey`, and typed `payload`.

Use `aggregateId` as the Kafka key for per-aggregate ordering. `eventId` identifies delivery; `idempotencyKey` identifies the business effect. Payloads contain permitted facts only—never provider tokens, passwords, session identifiers, full provider responses, or unrestricted source data.

## Evolution

1. Draft 2020-12 schemas are immutable after release.
2. Within a schema major version, add only optional fields with defined semantics; never repurpose or narrow an existing field. Optional additions are not automatically compatible with strict old readers: use the supported matrix in `../contracts/README.md` and upgrade consumers before enabling new producers.
3. Breaking payload or semantic changes create a new schema/topic major version and a dual-publish/adapter migration.
4. The runtime event catalog verifies event type/payload pairing and schema version. Bean Validation
   runs before outbox insertion and again after consumer deserialization; unsupported types,
   versions, and invalid payloads fail closed.
5. CI checks actual HTTP serialization/binding, event readers, frozen synthetic v1 fixtures,
   and PostgreSQL migration overlap. These do not certify an unreleased old application
   binary or arbitrary rolling deployment. Registry features depend on the selected
   production platform.

## Retry, DLT, and replay

- The database transaction writes domain state and `outbox_events`; the relay publishes and marks it only after acknowledgement. Delivery is at least once.
- Consumers insert `(event_id, consumer_name)` in `processed_events` within an existing
  transaction with their database effect and any new outbox records.
- Transient failures block the assigned source partition and retry after 1, 2 and 4
  seconds: four attempts total, without jitter or retry topics. Restart/reassignment
  resets in-memory attempt counters. Validation, unsupported schema, authorization, and
  explicitly terminal invariant failures bypass retries; ordinary `IllegalStateException`
  remains retryable.
- Recovery publishes to `<topic>.dlt` on the original partition. The broker must acknowledge
  the DLT publication before the source offset may advance. Failed recovery leaves the
  source record available for redelivery.
- DLT metadata preserves original bytes/coordinates, consumer group, attempts, safe
  failure category and timestamps. Content-bearing exception messages and stack traces
  are not published. Metadata persistence retries rather than recursively dead-lettering
  database outages.
- Authorized replay records operator, reason, replay ID and dry-run count, applies rate
  limits and rechecks suppression. `x-replay-consumer-group` restricts execution to the
  failed group; unrelated groups skip the replay. The original partition and coordinates
  are retained, and inbox deduplication remains active. Replay completion means
  broker-acknowledged dispatch, not completed downstream effects.
- Kafka transactions provide exactly-once only for compatible Kafka read/process/write paths. They do not atomically include PostgreSQL, X/AI/image/mail HTTP calls, object storage, or Redis. Business outcomes are effectively once through outbox, dedupe, unique constraints, reconciliation, and persisted provider idempotency keys.
