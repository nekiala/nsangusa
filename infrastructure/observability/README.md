# Observability operations

Local telemetry is printed by the collector debug exporter. Production uses the Helm collector and an external OTLP backend.

Operational requirements:

- Export over TLS with workload identity or a referenced secret.
- Alert on `otelcol_exporter_send_failed_*`, refused spans/metrics/logs, queue saturation, restarts, and memory limiter drops.
- Size queues for a bounded backend outage; telemetry must not exhaust node or application resources.
- Use tail/error-aware sampling only after security/privacy review. Metrics needed for SLOs must not depend on trace sampling.
- Redact at source. Collector filtering is defense in depth, not permission to emit article bodies, prompts, cookies, authorization headers, tokens, or provider payloads.
- Dashboard ingress success/latency, JVM/Node saturation, PostgreSQL pool, Kafka consumer/outbox lag, DLT, deletion deadlines, provider rate/error class, and collector health.

