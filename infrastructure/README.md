# Infrastructure

Development:

```bash
docker compose -f compose/compose.yaml config
docker compose -f compose/compose.yaml up -d
```

Production:

```bash
helm lint helm/nsangusa \
  -f helm/nsangusa/values/production.yaml \
  -f /secure/path/environment-values.yaml \
  --set backend.image.digest=sha256:... \
  --set frontend.image.digest=sha256:... \
  --set otelCollector.image.digest=sha256:...
```

Compose is local-only. The chart never deploys PostgreSQL, Kafka, Redis, S3, or other production stateful services. It deploys backend/frontend workloads, a pre-upgrade Flyway job, and optionally an OpenTelemetry Collector. Test, staging, and production rendering fail unless their required runtime Secret reference, TLS
ingress, secure external endpoints, and transport settings are supplied. Production additionally
requires immutable SHA-256 image digests and production provider mode.

The backend ConfigMap exposes bounded story clustering, X reconciliation, AI retry/circuit/price,
and publication policy settings. Production must retain `PUBLICATION_POLICY=HUMAN_REVIEW_ALWAYS`;
confidence/topic modes are for controlled qualification environments only. Production also requires
`NEWSLETTER_PROVIDER=resend`; `NEWSLETTER_IDEMPOTENCY_WINDOW` must not exceed Resend's documented
24-hour provider-side idempotency window.

`environment-values.yaml` is environment-owned and must not be committed. Start from `helm/nsangusa/values/environment.example.yaml`, replace every documentation address, then store it in the protected GitHub environment's `HELM_VALUES` secret. It supplies public endpoints, ingress host/TLS secret, managed-service addresses, image repositories, and explicit egress CIDRs/ports. Credentials remain in `secret.existingSecret`, ideally synchronized by an external secret controller or provided through workload identity.

NetworkPolicy is default-deny and always permits selected cluster DNS. Backend, frontend, and collector egress are separate allowlists. Kubernetes NetworkPolicy cannot authorize by DNS name: enumerate stable provider CIDRs or route outbound traffic through controlled egress proxies/gateways. Empty non-development egress lists fail rendering unless `externalEgressManagedExternally` is explicitly set after documenting equivalent platform controls.

The migration hook uses the backend image and runtime Secret, enables Flyway, disables regular Flyway in application replicas, and must finish before an atomic upgrade proceeds. Schema changes must be backward-compatible with the prior application revision.

Image tags were checked against Docker Hub on 2026-09-02: PostgreSQL `18.4`, Kafka `4.3.1`, Redis `8.10.0`, SeaweedFS `4.48` (replacing MinIO on 2026-09-29), Mailpit `v1.27.8`, OTel Collector `0.132.0`, Node `24.20.0-bookworm-slim`, and Eclipse Temurin `25-jre-noble`. Compose tags are development-only. Runtime base images and production Helm releases are digest-pinned.
