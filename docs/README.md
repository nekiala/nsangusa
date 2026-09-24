# Nsangusa engineering documentation

Completion baseline assessed on **2026-09-12**. The application is not yet feature-complete or
production-qualified. Start with the [completion matrix](completion-matrix.md) and
[implementation status](implementation-status.md).

Versions currently recorded in the repository:

| Area | Baseline |
|---|---|
| Backend | Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1 |
| Web | Next.js 16.3.4, React 19.2.8, Node.js 24.20.0 LTS |
| Data/messaging | PostgreSQL 18, Redis 8.10, Kafka 4.3.1 |

The [version matrix](version-matrix.md) records the existing 2026-09-06 verification claim.
The completion assessment did not revalidate release availability or compatibility; official
provenance and immutable artifact qualification remain required before promotion.

Documents:

- [Completion matrix and approval checkpoints](completion-matrix.md)
- [Requirements and assumptions](requirements.md)
- [Architecture and ownership](architecture.md)
- [Data model and retention](data-model.md)
- [API and article workflow](api-and-workflow.md)
- [Events and delivery semantics](events.md)
- [Security and X-ingestion compliance](security-compliance.md)
- [Architecture decisions](adrs.md)
- [Reliability and operations](reliability-operations.md)
- [Local development](development.md)
- [Infrastructure notes](infrastructure.md)
- [Resolved version matrix](version-matrix.md)
- [Implemented scope and limitations](implementation-status.md)

Contracts are in `../contracts`; deployment assets are in `../infrastructure`.
