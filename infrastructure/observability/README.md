# Observability operations

Local telemetry is printed by the collector debug exporter. Production uses the Helm collector and an external OTLP backend.

## Executable package

- Single-source Prometheus recording/alert rules:
  `../helm/nsangusa/files/observability/rules.json` (JSON is valid YAML for Prometheus).
- Importable Grafana dashboard: `../helm/nsangusa/files/observability/dashboard.json`.
- `alert-tests.json`: real promtool firing/resolution, replica aggregation, missing-data, healthy
  traffic and no-traffic tests.
- `prometheus-example.yaml`: opt-in plain Prometheus wiring; adapt per-pod discovery for replicas.
- Run `HELM=/path/to/helm python3 infrastructure/scripts/test-monitoring.py` **from the repository
  root**. It uses a digest-pinned, bounded, no-network Prometheus test container and existing Helm,
  without installing a monitoring stack. Its writable working state stays below the repository.

The chart exposes these assets only when monitoring is enabled; operator CRDs and Grafana
sidecars are independently opt-in, never assumed. Scrapes use the isolated management port and
explicit namespace/pod NetworkPolicy selectors. See `docs/infrastructure.md` and
`docs/reliability-operations.md` for controls, metric semantics and provisional/external limits.

Operational requirements:

- Export over TLS with workload identity or a referenced secret.
- Alert on `otelcol_exporter_send_failed_*`, refused spans/metrics/logs, queue saturation, restarts, and memory limiter drops.
- Size queues for a bounded backend outage; telemetry must not exhaust node or application resources.
- Use tail/error-aware sampling only after security/privacy review. Metrics needed for SLOs must not depend on trace sampling.
- Redact at source. Collector filtering is defense in depth, not permission to emit article bodies, prompts, cookies, authorization headers, tokens, or provider payloads.
- Dashboard ingress success/latency, JVM/Node saturation, PostgreSQL pool, Kafka consumer/outbox lag, DLT, deletion deadlines, provider rate/error class, and collector health.
