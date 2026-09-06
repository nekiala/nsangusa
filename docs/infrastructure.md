# Infrastructure and observability notes

## Layout

- `infrastructure/compose`: disposable local dependencies only.
- `infrastructure/docker`: runtime-only image contracts; application build output is supplied by CI.
- `infrastructure/helm/nsangusa`: backend/frontend and in-cluster OTel collector.
- `infrastructure/observability`: local collector configuration.

Production PostgreSQL, Kafka, Redis, object storage, identity, email, DNS, certificates, telemetry backend, AI, and X are external managed dependencies. The chart creates none of them. Provider features and credentials require real accounts.

## Configuration

Non-secret endpoints and flags use a ConfigMap. Credentials and sensitive identifiers come from `existingSecret`; production should use an external secret controller/workload identity. Secret values must not be committed or placed in Helm values. Restrict egress further with platform-supported FQDN/proxy controls because Kubernetes NetworkPolicy is IP/port based.

Environment overlays under `helm/nsangusa/values` contain safe sizing and policy defaults only. The GitHub deployment environment must provide a `HELM_VALUES` secret containing reviewed non-secret environment overrides. Set image repositories/digests, host/TLS secret, managed endpoints, runtime Secret name, storage/auth identifiers, telemetry exporter, and explicit egress CIDRs/ports there.

Required runtime Secret keys are mapped under `secret.env`; the chart references but never creates that Secret. Each key is wired with a non-optional `secretKeyRef`, so a missing Secret/key blocks pod startup rather than silently falling back. Production admission/rendering rejects mutable image tags, incomplete secret mappings, insecure PostgreSQL/Kafka/Redis/provider settings, missing TLS ingress, and placeholder required endpoints.

The default-deny policies separate backend, frontend, migration, and collector egress. DNS is restricted to selected CoreDNS pods. Provider IPs can change, so use a platform egress gateway or continuously maintained CIDR inventory; do not open `0.0.0.0/0` merely to make deployment pass.

Flyway executes as a Helm pre-install/pre-upgrade Job using the exact backend digest. Normal backend pods receive `SPRING_FLYWAY_ENABLED=false` when this mode is enabled. The Job disables web, Kafka listeners, scheduling, and tracing and enables lazy initialization, but still uses the application's normal startup path; validate it against a staging environment after changes that add eager provider clients. The Job is deliberately not auto-deleted after success so its logs/status remain available until TTL cleanup or the next hook execution.

## OTel

Applications send OTLP to the collector. The collector batches, limits memory, adds Kubernetes attributes, and exports through a configured OTLP endpoint. The local configuration uses debug output only. Production must enable TLS/auth, redact at the application first, size queues, and monitor collector refusal/export failure. Do not send article bodies, prompts, tokens, or sensitive headers as attributes.

Recommended attributes: service name/version, deployment environment, module, route template, result class, correlation ID, Kafka topic/partition, and provider class. Avoid user IDs unless irreversibly pseudonymized and approved. Trace sampling should retain errors and rare critical workflows while controlling normal traffic volume.

## Image and release controls

- CI produces SBOM/provenance, scans, signs, and promotes the same digest.
- GitHub environments provide approval gates and scoped cluster credentials; deployment consumes reviewed environment values, verifies application Cosign signatures, and deploys exact digests.
- Runtime containers are non-root, read-only, capability-free, and use seccomp defaults.
- Deployment labels the dedicated namespace for the Kubernetes Restricted Pod Security Standard; cluster admission remains the final enforcement point.
- Requested Compose/runtime tags were verified as present on Docker Hub on 2026-09-02; pin resolved, trusted digests because version labels remain mutable.
- Helm rendering is validated for each overlay. Cluster admission should enforce signatures, resources, probes, and restricted Pod Security.
