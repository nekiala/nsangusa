# Reliability and operations

## Production connection requirements

The `production` profile fails startup when required provider credentials or secure transport
settings are absent. PostgreSQL must use `sslmode=verify-full`; Kafka must use `SASL_SSL` with its
JAAS configuration supplied by the secret manager; Redis must enable TLS and authentication; X,
AI, image, S3, OIDC, and the public base URL must use HTTPS. SMTP authentication and STARTTLS are
configured through `MAIL_*` variables. Helm references these values but never embeds credentials.

## Service objectives

| Indicator | SLO, rolling 28 days |
|---|---|
| Public article/comment read availability | 99.9% non-5xx |
| Authenticated/editor command availability | 99.5% non-5xx, excluding valid 4xx |
| Public article latency | 95% ≤ 400 ms; 99% ≤ 1 s |
| Outbox-to-Kafka freshness | 99% ≤ 60 s |
| X ingestion freshness | 99% within configured poll interval + 5 min, excluding provider rate-limit/outage windows |
| Draft workflow completion | 95% ≤ 10 min, separately reporting provider delay |
| Approved website publication | 99% ≤ 2 min |
| Newsletter dispatch start | 99% ≤ 5 min after eligible publication |

Page on fast/slow error-budget burn and separately tag X/AI/image/mail provider failures.

## Backup, restore, DR, and rollback

- PostgreSQL: encrypted managed PITR, daily recovery points, 35-day retention, quarterly restore test; target **RPO ≤ 5 min, RTO ≤ 4 h**.
- Object storage: encryption, lifecycle/versioning where approved, integrity checks and restore testing.
- Kafka: retain for the maximum outage/replay window but never treat it as the sole record. Back up topic/schema/ACL configuration.
- Redis: sessions fail closed; cache/rate-limit state rebuilds. Do not block DR on Redis data restore.
- Recovery order: external dependencies → PostgreSQL restore → migrations → deletion/correction ledger → projections/cache → consumers paused → validation → consumers → ingress.
- Deploy immutable signed images with expand/migrate/contract database changes. Roll back code/chart only while schema is backward compatible; repair semantic data forward.

## Deployment and rollback

1. Build from a signed release tag. The image workflow publishes SBOM/provenance attestations, scans the published digest, signs it with GitHub OIDC/Cosign, and records digest artifacts.
2. Copy the backend/frontend digests and a verified OTel Collector digest into the manual deployment workflow. Select the protected GitHub environment; reviewers approve environment values and cluster identity.
3. The workflow validates digest syntax and the final Helm render, runs the Flyway hook, performs `helm upgrade --install --atomic`, then waits for backend/frontend rollouts.
4. Verify ingress/TLS, health, errors, saturation, migration status, outbox/consumer lag, and provider connectivity. This repository cannot claim a deployment without environment credentials and cluster evidence.
5. For an application regression with a backward-compatible schema, run `helm history nsangusa -n <namespace>` and `helm rollback nsangusa <revision> -n <namespace> --wait --timeout 10m`.
6. Do not reverse destructive migrations. Halt rollout, restore compatible code, and apply a forward repair. Failed atomic upgrades automatically return workloads to the previous Helm revision, but external database changes may remain.

## Runbooks

### X ingestion delayed
1. Confirm affected monitored accounts, last successful sync, rate-limit reset, and official API status.
2. Stop aggressive retries; honor provider reset/backoff and protect credentials.
3. Verify token scope/account plan and permitted endpoint; never switch to scraping.
4. Resume bounded polling, deduplicate post IDs, and reconcile freshness without duplicating workflow events.

### Outbox lag or Kafka outage
1. Keep writes only while outbox capacity and database load are safe.
2. Check relay health, broker auth/quota, topic existence, partitions and schema failures.
3. Restore connectivity; rate-limit catch-up and watch duplicates/consumer lag.
4. Reconcile unpublished outbox rows and broker acknowledgements.

### DLT growth/replay
1. Pause the affected consumer if bad side effects may spread.
2. Classify schema/poison data versus code/config/provider failure using redacted metadata.
3. Fix and validate; dry-run exact offsets/count and downstream side effects.
4. Authorized operator replays at bounded rate; reconcile `processed_events` and outcomes.

### AI/image provider outage or compromise
1. Set provider mode/feature gate to safe unavailable or approved fake mode outside production; revoke/rotate credentials on compromise.
2. Keep candidates/drafts pending; do not silently substitute a provider or publish incomplete output.
3. Audit outbound calls and provider retention; notify security/privacy as required.
4. Qualify recovery/alternate adapter before draining backlog.

### Incorrect or prohibited source content
1. Disable monitoring/block the source, halt dependent workflow and unpublish affected website articles if necessary.
2. Locate source, AI, article, media, cache, event-retention, analytics and newsletter derivatives.
3. Correct/delete under `security-compliance.md`; replay tombstones after restore.
4. Record evidence and verify no stale public/cache/search copy remains.

### PostgreSQL failover/restore
1. Enter read-only/degraded mode and pause writing consumers/outbox relay.
2. Fail over/restore; verify migration version, checksums, aggregate/outbox consistency and recovery point.
3. Apply deletion/correction ledger, rebuild projections/cache, then resume consumers before public writes.
4. Monitor duplicate events, newsletter sends and publication state.
