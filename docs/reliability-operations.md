# Reliability and operations

The SLOs and recovery procedures are provisional qualification targets, not measured production
results. The repository includes deployable, opt-in alert/dashboard resources and an executable
synthetic logical database/object/config restore drill. These do **not** qualify managed PITR,
production availability, legal retention approval or deployment. Approved capacity/cost limits,
external recovery/failover and routed-alert evidence remain open under O01-O03 and D08 in the
[completion matrix](completion-matrix.md).

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

AI provider calls use a configurable semaphore bulkhead, bounded exponential retry with jitter, and
a circuit breaker. Metrics include `news.ai.provider.calls`, `news.ai.provider.attempts`,
`news.ai.provider.retries`, `news.ai.provider.duration`, `news.ai.tokens`,
`news.ai.estimated.cost.usd`, and `news.ai.safety.blocks`. Configure provider pricing with
`AI_INPUT_COST_PER_MILLION` and `AI_OUTPUT_COST_PER_MILLION`.

Production newsletter delivery uses Resend SMTP with the persisted delivery key sent as
`Resend-Idempotency-Key`. Retries reuse that key only within Resend's documented 24-hour
idempotency window; unresolved deliveries older than the configured
`NEWSLETTER_IDEMPOTENCY_WINDOW` move to `reconciliation_required` instead of risking a duplicate
send. Deliveries attempted by a pre-upgrade sender are also reconciliation-required because that
sender did not transmit the provider key. The V7 cutover rejects new legacy delivery reservations
and quarantines the current UTC daily/weekly digest keys, preventing an in-flight pre-upgrade
digest from being resent if its outer transaction rolls back after provider acceptance. A
tokenized `NEWSLETTER_ATTEMPT_LEASE` makes concurrent retries reuse the same provider identity and
makes stale completion callbacks no-ops.

## Backup, restore, DR, and rollback

- PostgreSQL proposal requiring D04/D08 approval: encrypted managed PITR, daily recovery points,
  35-day retention, quarterly restore test; target **RPO ≤ 5 min, RTO ≤ 4 h**. No policy approval
  or managed PITR operation is implied by those proposed values.
- Object storage: encryption, lifecycle/versioning where approved, integrity checks and restore testing.
- Kafka: retain for the maximum outage/replay window but never treat it as the sole record. Back up topic/schema/ACL configuration.
- Redis: sessions fail closed; cache/rate-limit state rebuilds. Do not block DR on Redis data restore.
- Recovery order: external dependencies → PostgreSQL restore → migrations → deletion/correction ledger → projections/cache → consumers paused → validation → consumers → ingress.
- Deploy immutable signed images with expand/migrate/contract database changes. Roll back code/chart only while schema is backward compatible; repair semantic data forward.

### Executable isolated local recovery qualification

From the repository root, using existing Python 3.9+, Docker and OpenSSL:

```sh
python3 infrastructure/scripts/operational-drill.py \
  --output .local/operational-evidence/my-unique-run
```

The output directory must be new and below the repository. No database URLs, host ports,
production credentials, real source data or provider calls are accepted. The script creates
unique labelled containers/volumes, without touching either preview or phase4 infrastructure.
Service artifacts are digest-pinned. PostgreSQL is bounded to 512 MiB / 0.5 CPU, MinIO to
256 MiB / 0.5 CPU, each short-lived object client to 256 MiB / 0.5 CPU (Go memory target
192 MiB, two worker processors). No host ports are
published; services use isolated network namespaces. SQL has 15-second statement / five-second
lock deadlines, at most 15 database connections, two synthetic sources, 100-row ledger limits
and a 16 MiB archive limit.

The drill applies the repository's actual migrations, exercises the actual operational inventory
SQL, and backs up a PostgreSQL custom-format logical dump, object bytes and fake/paused
configuration. The archive has member SHA-256 checks, AES-256-CBC/PBKDF2 encryption and an
independent HMAC-SHA256 integrity check; a tampered backup is rejected before decryption.
The adjacent owner-readable key is **synthetic drill material, not production KMS/key custody**.
In-memory plaintext exists during the drill; never substitute real data.

After the snapshot, the fixture tombstones a source, independently suppresses another article,
records a synthetic newsletter acceptance receipt, removes an object and commits another event.
Recovery:

1. Restores into another isolated database/bucket; checks snapshot inventory, object and config hashes.
2. Rejects ledger references to absent/mismatched source identities without partial mutation.
3. Replays the newer source tombstones and aggregate suppression ledger with consumers absent.
   Source/candidate text, article bodies/structured blocks/revisions/search, image metadata,
   publication schedules and deliverable outbox/DLT payloads are reconciled.
4. Replays explicit object tombstones, preserving an unrelated object.
5. Preserves existing inbox receipts and provider idempotency identities. Known newsletter
   outcomes are reconciled; unresolved attempts/campaigns are quarantined, never reset to pending.
6. Proves idempotent deletion replay and refusal to republish source-restricted **or independently
   suppressed** articles. V23 adds the latter database guard.
7. Reports the deliberately missing post-snapshot committed event rather than inventing its
   payload or claiming lossless recovery. The two-source fixture's newer ledger is available;
   a real recovery must prove an independently durable ledger beyond its recovery point.
8. Exercises a backward-compatible old-column read/write transaction after additive migrations,
   rolls that transaction back and validates forward data reconciliation. **This is a SQL schema
   contract rehearsal, not an old/new application binary or Kubernetes rollout.**

`evidence.json` records image/migration hashes, integrity results, exact limits, measured local
restore duration, logical backup age, missing event IDs, checks, cleanup and blockers.
`backup.enc`, `backup.hmac`, `synthetic-ledger.json` and the synthetic key remain for inspection.
The measured duration excludes managed restore provisioning and downstream catch-up; backup age
is not a production RPO. This is **pg_dump/pg_restore, not WAL/PITR**.

Success/failure normally removes only resources whose exact names and ownership labels match
the current run. `--keep-on-failure` retains those resources plus bounded logs; their exact names
are recorded in evidence. Before manual cleanup, inspect each recorded resource's
`com.nsangusa.operational-drill` label against the run ID, then remove those exact containers
and volumes only. Never use project-wide `down -v`, name-pattern deletion or global pruning.

### Optional real-binary rollback and forward qualification

`infrastructure/scripts/binary-qualification.py` separately runs **candidate backend/frontend →
previous backend/frontend → candidate backend/frontend** against one fresh owned database.
Supply four distinct, already-local full `sha256:` image IDs or repository digests. The runner
never builds or pulls images. Candidates validate current repository Flyway checksums; the previous
binary runs with Flyway disabled against that newer schema. Each phase checks real public API
responses, private-content exclusion, successful reader authentication, admin denial, frontend
proxy/SSR behavior and deterministic data/schema hashes. The fixture represents pre-existing
synthetic articles, not qualification of editorial mutations.
Retained-source reconciliation correctly continues even for a paused account. Raw snapshots
therefore retain and separately report `source_posts.last_checked_at` and its optimistic `version`;
those two observation fields may only advance together monotonically. The comparable data hash
excludes only those fields, not source content/status/identity, article versions or other business
data. The drill neither resets observed rows to manufacture equality nor disables compliance work.

Two HTTP transports are deliberately distinct:

- `--http-transport host` is the default and tests the published loopback ports. If the Docker
  engine does not publish ports on an internal network, this mode **fails**; it never changes
  isolation or silently switches transport.
- Explicit `--http-transport internal` publishes **no host ports**. For every probe it verifies
  the frontend container's exact ownership labels, allowed image and sole internal network,
  then executes Node there to issue a real HTTP GET to `frontend:3000`, `backend:8080`,
  `backend:8081` or `mailpit:8025`. This still traverses the running HTTP servers, authorization,
  frontend proxy and server rendering; it does not invoke application handlers directly.
  Responses retain their actual status, headers and binary body. Redirects are returned rather
  than followed. Bodies are capped at 2 MiB, headers at 16 KiB, and a 15-second end-to-end timer
  bounds DNS/connection/streaming, with a separate 20-second Docker-exec deadline. Ownership
  inspection has a five-second timeout.

Internal mode retains free/configurable loopback origin values for application public-URL
configuration only. Evidence records `httpTransport: internal`,
`publishedPortAccessQualified: false` and `ingressAccessQualified: false`; **it cannot qualify
published-port, ingress, TLS or production access**. Host mode never qualifies actual cluster
ingress either. The owned network remains `internal: true` in both modes. Providers remain fake,
`AI_LIVE_ENABLED=false`, the master key is ephemeral, and unused OTLP metrics export is disabled
without disabling the candidate's Prometheus registry.

```sh
python3 infrastructure/scripts/binary-qualification.py \
  --http-transport internal \
  --previous-backend "$PREVIOUS_BACKEND_ID" \
  --previous-frontend "$PREVIOUS_FRONTEND_ID" \
  --candidate-backend "$CANDIDATE_BACKEND_ID" \
  --candidate-frontend "$CANDIDATE_FRONTEND_ID" \
  --frontend-port 0 --backend-port 0 --management-port 0 --mailpit-port 0 \
  --output .local/operational-evidence/binary-internal-new-run
```

Use a new evidence directory; retain failed evidence rather than overwriting it. Steady-state
container limits total 2,976 MiB, so coordinate this run with other qualification workloads.
The script normally cleans only exact, verified owned containers/volumes/networks;
`--keep-on-failure` retains them for inspection. Live-provider settings/credentials, active AI work,
production deployment/SLOs, concurrent rolling replicas and managed recovery remain explicitly
unqualified. The transport unit checks use local synthetic HTTP fixtures, not application rollout
evidence:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s infrastructure/scripts -p test_binary_qualification.py -v
```

### Explicit-policy payload retention and holds

V23 has **no seeded production policy and no automatic retention schedule**. It supports only:

| Data class | Eligible operation | Never removed |
|---|---|---|
| `published_outbox_payload` | Redact payload after the approved age measured from broker acknowledgement | Row, event/idempotency identity, acknowledgement |
| `suppressed_failure_payload` | Redact already-suppressed DLT payload/error text after approved age | Failure identity, suppression/replay records |

An authorized database operator must separately supply `operational_retention_policies` with
the actual approval reference, approver, approval/expiry times, data class and retention seconds,
then explicitly enable it. `apply_operational_retention(policy_id, batch_size, dry_run)` rejects
missing/disabled/future/expired policies and null/invalid arguments. `batch_size` is 1–1000.
There is no application endpoint or implicit grant: public function execution is revoked.
Grant the function and only necessary table rights to a reviewed operator role; the repository
does not configure the managed database's production grants.

```sql
-- Replace with the ID of an independently approved, operator-installed policy.
begin;
set local statement_timeout = '15s';
set local lock_timeout = '5s';
select apply_operational_retention(:'approved_policy_id'::uuid, 100, true);
rollback;
-- After reviewing the dry run, repeat in a new transaction with false and COMMIT.
```

`operational_legal_holds` can match a data class, exact event ID or aggregate ID; null class/ID
widens to a class-wide/global hold. Active holds are excluded. Hold/policy changes and retention
share a transaction advisory lock, preventing the cleanup/hold-registration race. Authorized
release is explicit (`released_at`); no timer releases a hold. Results include cutoff, reference,
bounded selected/changed counts and active hold count; committed operations retain audit evidence.

The drill uses a plainly named **SYNTHETIC-DRILL-NOT-LEGAL-APPROVAL** policy inside a rolled-back
transaction to test disabled/expired rejection, dry run, one-row bounds, aggregate/failure-record/
original-event hold protection, explicit hold release, and preservation of pending outbox work.
An original event UUID is distinct from its DLT record UUID; either hold preserves both the
failure payload and exception details. The fixture does not approve any real retention period.
The hold facility governs **these two operational payload classes only**; it is not comprehensive
cross-store litigation-hold enforcement. Existing identity token cleanup, consent/audit retention,
source/provider takedowns, Kafka broker/DLT retention, object versions, backups and provider copies
still require approved per-store rules and separately qualified enforcement. Never expire inbox,
command receipts, newsletter identities or source tombstones to force a replay. Resolve conflicts
between a takedown and preservation duty with the accountable legal/security owner, not ad hoc SQL.

## Deployment and rollback

1. Use an approved, protected `v*` release tag. Both PR/main CI and tag publication call the same `quality-gates.yml`; image jobs depend on its success. The aggregate readiness job rejects failed, cancelled or skipped dependencies. GitHub tag/branch protections must be configured separately.
2. After gates pass, the image workflow publishes SBOM/provenance attestations, scans the published digest, signs it with GitHub OIDC/Cosign and records digest artifacts. Copy the backend/frontend digests, their exact shared release tag and a separately verified OTel Collector digest into the manual deployment workflow. Select the protected GitHub environment; reviewers approve environment values and cluster identity.
3. The workflow validates digest syntax and the final Helm render, runs the Flyway hook, performs `helm upgrade --install --atomic`, then waits for backend/frontend rollouts.
4. Verify ingress/TLS, health, errors, saturation, migration status, outbox/consumer lag, and provider connectivity. This repository cannot claim a deployment without environment credentials and cluster evidence.
5. For an application regression with a backward-compatible schema, run `helm history nsangusa -n <namespace>` and `helm rollback nsangusa <revision> -n <namespace> --wait --timeout 10m`.
6. Do not reverse destructive migrations. Halt rollout, restore compatible code, and apply a forward repair. Failed atomic upgrades automatically return workloads to the previous Helm revision, but external database changes may remain.

Promotion verifies the exact image-workflow certificate identity for the supplied tag, issuer,
`quality-gate=passed` annotation and matching source commits for both images. It cannot accept
an arbitrary `v*` signer or mix backend/frontend commits. This is a repository-enforced dependency,
not proof that a hosted run, protected environment or signed release already exists. Helm is
checked out from that same attested source commit, rather than silently using the dispatch
branch's potentially different chart.

Require the reusable workflow's `release-readiness` check in repository rules, protect release
tags/workflow changes and configure independent production reviewers. At the Step 5 local
checkpoint this checkout has no Git remote or target Kubernetes context; those settings and
actual promotion evidence cannot be configured or attested here. No deployment is performed by
the local acceptance runner.

## Runbooks

### Monitoring and alert response

Authoritative rule/dashboard assets live under
`infrastructure/helm/nsangusa/files/observability/`; the chart packages the same files as plain
ConfigMaps or opt-in Prometheus Operator resources. See [infrastructure.md](infrastructure.md)
for secure port/network wiring. Nothing installs Prometheus, Grafana, Alertmanager or their CRDs.

```sh
HELM=/path/to/existing/helm python3 infrastructure/scripts/test-monitoring.py
```

This renders safe/default and CRD-qualified paths, rejects unsafe/missing-controller combinations,
and runs digest-pinned `promtool` tests with bounded resources and no network. Tests cover sustained
alert firing, resolution, missing scrape/inventory, zero traffic, successful traffic without a 5xx
series and the requirement not to add database inventories across replicas. The script does not
contact an Alertmanager or page anyone. On-call routing, receipt/acknowledgement and escalation
must be exercised separately in the approved environment.

`OperationalMetrics` refreshes database inventories every 30 seconds with a five-second SQL
deadline, separately from Prometheus scraping. Before the first snapshot gauges are NaN, not
healthy zeros. Query failure retains the previous values/timestamp, exposes collection failure
and increments `news_operations_collection_failures_total`. A stale/missing snapshot alert is
essential: a cached backlog value must not be mistaken for a recovered database.

| Signal family | Semantics / response |
|---|---|
| `http_server_requests_seconds_*` | HTTP timer counter/histogram; aggregate per-process rates. Public article read 5xx burn and p95 use actual route-template tags, not invented request counters. No traffic is not availability evidence. |
| `news_operations_outbox_*`, `failures_pending`, `replays_pending` | Exact database-wide inventory/oldest age; use **max by namespace/job, never sum replicas**. Outbox age is not broker consumer offset lag. |
| `news_operations_publication_*`, `ai_*`, `ingestion_*` | Overdue/failed schedules, pending AI request age and account poll/rate-limit inventories. Not end-to-end draft completion or proof of provider health. |
| `news_ai_provider_*`, `news_ai_tokens_total`, `news_ai_estimated_cost_usd_total` | Per-process counters; apply `rate`/`increase` before summing, tolerate restart resets. Cost requires reviewed nonzero prices and is estimated token cost, not a billing guarantee. |
| `news_operations_newsletter_*` | Delivered/pending/reconciliation **inventories**, not counters or a delivery-success SLO. |
| `news_operations_moderation_pending` | Pending/spam review inventory; D08 must set an approved backlog/age threshold. |
| `hikaricp_connections_*` | Per-replica pool saturation and queued borrowers; inspect database/slow statements and capacity before scaling replicas. |

Alerts carry `qualification=provisional`, actionable summaries and repository-relative runbook
links. Set `monitoring.runbookUrl` to the actual published HTTPS runbook URL; the chart rewrites
alert anchors and the dashboard documentation link without forking the rule assets.
The 99.9% availability burn-rate and 400 ms / 60 s / 120 s / 600 s thresholds are proposals, not
approved capacity or measured SLO results. AI error/rate-limit alerts preserve result class without
source, user, prompt or payload labels. Cost cap, newsletter-success and moderation-backlog
thresholds remain explicitly blocked on D08 rather than invented. Kafka broker offsets, collector
export/refusal, Kubernetes restart/HPA/PDB and external dependency health need the corresponding
qualified exporters/controllers; no nonexistent metric names are asserted as working here.

1. Missing scrape: check management listener, scrape selectors, CNI policy and target discovery;
   do not expose metrics on public ingress or bypass authentication globally.
2. Stale inventory/pool pressure: check PostgreSQL reachability, bounded query latency and pool
   waiters. Keep liveness dependency-free to avoid restart storms; readiness must reflect required
   database/session dependencies. A passing readiness probe does not prove Kafka/provider health.
3. Availability/latency burn: inspect affected route templates, recent release and dependency/pool
   signals, then degrade/rollback only under an approved incident plan.
4. Backlog: use the workflow-specific runbooks below; never mass-reset receipts or retry markers.

Multi-replica review: the outbox uses pessimistic database locking and persistent consumer
receipts, so duplicate delivery remains possible after broker acknowledgement/transaction failure
but committed work is not intentionally discarded. Relay transactions currently process up to
100 rows and wait up to ten seconds per broker acknowledgement; this can exceed the 30-second
shutdown phase during a broker outage. Existing replay rate windows and newsletter leases are
database-backed. Local AI bulkheads/circuit state and other in-process limits are **per replica**,
not globally shared quota controls. HPA does not prove safe aggregate database/provider capacity.
Rehearse termination under load, broker outage/catch-up and old/new binaries with the approved
traffic envelope before claiming O01 closure.

### Newsletter reconciliation

1. Keep restored consumers/newsletter scheduling paused; preserve provider idempotency keys,
   receipts, suppression lists and leases.
2. Compare independently retained provider acceptance/webhook evidence against each uncertain
   delivery. A restored database's `pending` value does not prove the message was never accepted.
3. Quarantine uncertainty as `reconciliation_required`. Never reset status or delete the existing
   delivery/campaign to force another send, especially after the provider protection window.
4. Obtain explicitly authorized reconciliation before any resend; the local drill sends no mail.

### Editorial cutover and failed schedules
1. Stop pre-V12 scheduling writers before migration. V12 quarantines historical nonterminal
   schedules without a captured approved version and rejects new unversioned schedules; this is
   not a mixed-version scheduler rollout. Preserve Flyway history and repair forward.
2. Review failed entries in `/admin/schedules`. For versioned failures, resolve the error before
   explicitly rescheduling. For changed/unknown article versions, cancel, review again and create
   a new schedule. Never reset a failed status directly in SQL.
3. Each due item uses its own claimed transaction; a failed article does not block the batch.
   Inspect attempt count, last error, responsible editor and audit evidence.
4. Purge any pre-existing external public caches on cutover. Current publication-sensitive API,
   page, feed and sitemap responses are no-store; capacity/CDN qualification is still required.
5. Retain command receipts through the approved client replay window. Do not delete receipts to
   resolve an uncertain request; reconcile article, schedule and outbox state first.

### X ingestion delayed
1. Confirm affected monitored accounts, last successful sync, rate-limit reset, and official API status.
2. Stop aggressive retries; honor provider reset/backoff and protect credentials.
3. Verify token scope/account plan and permitted endpoint; never switch to scraping.
4. Resume bounded polling. The scheduler deduplicates X edit chains, rechecks up to
   `X_RECONCILIATION_LIMIT` least-recently-checked retained nondeleted posts per account,
   tombstones explicit not-found results, and suppresses edited/deleted derivatives.

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

The administration workspace at `/admin/events` pages failure metadata without exposing event
payloads. Select at most 100 events, specify an audited reason and a rate of 1-20 messages/second,
then run a dry run. Compliance-suppressed candidates are shown as blocked. The same administrator
must acknowledge and confirm that exact preview within fifteen minutes. Confirmation rechecks
eligibility and creates only one replay job per preview; inspect the persisted records afterward.
Acceptance does not certify downstream delivery. Failed broker replays need a new reviewed preview;
poison events still require explicit opt-in. A database-backed next-batch timestamp shares the rate
window across replicas. Do not delete consumer receipts or source tombstones to force a replay.

`/admin/audit` provides bounded action/actor/target/time filtering. `/admin/operations` reports exact
outbox/failure/replay counts from PostgreSQL, not truncated queue samples or a claim that external
dependencies are healthy. Continue to use the operational metrics and Actuator for those checks.

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
