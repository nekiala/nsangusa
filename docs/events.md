# Kafka events and delivery

Canonical JSON Schemas are in `../contracts/events`.

## Implemented topics

| Topic | Events |
|---|---|
| `news.ingestion.v1` | `XAccountMonitoringRequested`, `XPostDiscovered`, `XPostNormalized` |
| `news.editorial.v1` | `StoryCandidateCreated`, `StoryAnalysisRequested`, `StoryAnalysisBlocked`, `StoryAnalysisCompleted`, `ArticleDraftRequested`, `ArticleDraftGenerated`, `ArticleImageRequested`, `ArticleImageCandidateGenerated`, legacy `ArticleImageGenerated`, `ArticleImageApproved`, `ArticleReadyForReview` |
| `news.publication.v1` | `ArticleApproved`, `ArticleScheduled`, `ArticlePublished`, `ArticleUnpublished` |
| `news.notifications.v1` | `NewsletterDispatchRequested`, `NewsletterDelivered`, `NewsletterDeliveryFailed`, `CommentSubmitted`, `CommentModerated` |

Each topic has a corresponding `.retry` and `.dlt` topic with the **same partition count**,
created by the Compose and k3s init jobs; production Kafka must provision all twelve before
deployment because broker auto-creation is disabled. A missing retry or DLT topic stops the
failing partition rather than rerouting its records.

### Producers and consumers of the 2026-10-01 events

| Event | Produced when (same transaction as) | Consumed by |
|---|---|---|
| `XAccountMonitoringRequested` | An administrator adds an account or resumes paused monitoring | `x-account-monitoring-v1`: runs that account's first sync instead of waiting for the next poll. Scheduled polling does **not** emit it, so the outbox does not grow per poll |
| `StoryCandidateCreated` | A normalized post starts a new story cluster | No in-process consumer; durable fact for audit and future extraction |
| `StoryAnalysisCompleted` | Analysis passes validation, together with `ArticleDraftRequested` | No in-process consumer; drafting still consumes the explicit `ArticleDraftRequested` command |
| `ArticleScheduled` | A schedule is created or moved to a new time | No in-process consumer; the database scheduler remains authoritative for publication |
| `NewsletterDelivered` / `NewsletterDeliveryFailed` | A delivery attempt's outcome is recorded (its own `REQUIRES_NEW` transaction) | No in-process consumer. Payloads carry delivery, article and campaign identifiers only, never subscriber addresses |
| `CommentModerated` | A moderator decision is saved | No in-process consumer. Moderator reasons stay in the audit trail, not the event |

Like `CommentSubmitted`, events without an in-process consumer record committed domain
facts for operators and downstream services; they never drive publication or delivery.

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
- **Blocking tier.** Transient failures first block the assigned source partition and retry
  after 1, 2 and 4 seconds (four attempts, no jitter). Restart/reassignment resets these
  in-memory counters. Validation, unsupported schema, authorization and explicitly terminal
  invariant failures bypass every retry and go straight to `<topic>.dlt`; ordinary
  `IllegalStateException` remains retryable.
- **Delayed retry-topic tier.** A transient failure that exhausts the blocking tier is
  forwarded to `<topic>.retry` on the original partition, so the source partition moves on.
  Delays come from `news.events.retry.delays` (default `30s,120s`: two delayed attempts;
  each 1 s–4 min, at most five; an empty list disables the tier). Each forward carries
  `x-retry-consumer-group` (the failed group), `x-retry-attempt`, `x-retry-not-before` and the
  cumulative delivery count. Every listener has a retry twin, `<group>-retry`, on
  `<topic>.retry`. Twins skip records addressed to any other group: eight groups share
  `news.editorial.v1`, and a shared retry must not re-run work that already succeeded
  elsewhere. A twin polls one record at a time and holds it until its not-before time,
  never longer than the longest configured delay. Compliance-suppressed aggregates are not
  retried. Failures without a known consumer group are dead-lettered rather than retried.
- **Ordering trade-off.** While a record waits in the retry tier, later records for the
  same aggregate can be processed first. Consumers already tolerate this for replay: they
  deduplicate through the inbox and reject stale or superseded state (for example, a late
  `ArticlePublished` cannot resurrect an unpublished article).
- Recovery publishes to `<topic>.retry` or `<topic>.dlt` on the original partition. The
  broker must acknowledge that publication before the source offset may advance. Failed
  recovery leaves the source record available for redelivery. The first forward's original
  topic, partition, offset and consumer group are retained, so DLT metadata and replay always
  target the main topic and group, never the retry tier.
- DLT metadata preserves original bytes/coordinates, consumer group, attempts, safe
  failure category and timestamps. Content-bearing exception messages and stack traces
  are not published. Metadata persistence retries rather than recursively dead-lettering
  database outages.
- Authorized replay records operator, reason, replay ID and dry-run count, applies rate
  limits and rechecks suppression. `x-replay-consumer-group` restricts execution to the
  failed group; unrelated groups skip the replay. The original partition and coordinates
  are retained, and inbox deduplication remains active. Replay completion means
  broker-acknowledged dispatch, not completed downstream effects.
- **Kafka transactions are not used**; producers use idempotent delivery (`enable.idempotence`,
  `acks=all`) and consumers read with `isolation.level=read_committed`. Kafka transactions only
  make a Kafka read→process→Kafka write atomic, and no consumer here writes its business output
  to Kafka: every consumer writes PostgreSQL state, its inbox row and any new outbox events in
  one **database** transaction, and the outbox relay publishes afterwards. A Kafka transaction
  could not include that database work, or X/AI/image/mail calls, object storage or Redis.
- The only Kafka→Kafka writes are retry/DLT forwards. A crash between the acknowledged forward
  and the offset commit can forward a record twice; the duplicate is harmless because the
  target group's inbox (`processed_events`) deduplicates it and DLT metadata is keyed by DLT
  coordinates. See ADR-015.
- Business outcomes are therefore *effectively* once through the outbox, inbox deduplication,
  unique constraints, reconciliation and persisted provider idempotency keys. No end-to-end
  exactly-once delivery is claimed, in particular not for email or other external effects.
