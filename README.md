# Nsangusa news platform

Production-oriented event-driven publishing platform implemented as one Java 25 / Spring Boot 4.1.1 modular monolith and one Next.js 16.3.6 frontend.

The implementation is not yet feature-complete or production-qualified. See the
[completion matrix](docs/completion-matrix.md) for evidence, gaps and approval-gated delivery,
and [implementation status](docs/implementation-status.md) for the current scope.

## Quick start

Prerequisites: a JDK capable of running Gradle, Node.js 24.20.0, and Docker with Compose.
The Gradle build resolves its Java 25 compiler toolchain automatically when Java 25 is not already
installed. `.java-version` and `.nvmrc` record the preferred local runtimes.

Verify the local tooling before starting:

```bash
make toolchains
```

```bash
make dev
```

This starts PostgreSQL, Kafka, Redis, SeaweedFS (S3), Mailpit, the Spring Boot backend at `http://localhost:8080`, and Next.js at `http://localhost:3000`. Local providers are deterministic fakes; no paid API is required. Mailpit is at `http://localhost:8025`, and the S3 API is at `http://localhost:9000`.

For a persistent, isolated container deployment for manual testing, use the
[local preview instructions](docs/development.md#persistent-local-preview). Its inbox is on port
18025, separate from an existing development stack.

The local profile sends a synthetic source through Kafka to the candidate and article queues.
Sign in at `/admin/candidates`, inspect the evidence, approve the image and article, then publish.
Storage and mail use real local S3 (SeaweedFS) and SMTP transports. Newsletter confirmation links are
available in Mailpit; confirm an immediate subscription before publishing to receive the article.

Demo accounts are created by the `local` profile:

| Role | Email | Password |
|---|---|---|
| Reader | `reader@example.test` | `reader-demo-password` |
| Moderator | `moderator@example.test` | `moderator-demo-password` |
| Editor | `editor@example.test` | `editor-demo-password` |
| Administrator | `admin@example.test` | `administrator-demo-password` |

Run all ordinary checks with `make test`. Architecture, contracts, security, operations, IntelliJ, provider, and deployment guidance is indexed in [`docs/README.md`](docs/README.md).

The [isolated acceptance runner](docs/development.md#isolated-step-5-acceptance) builds real local
application containers, exercises live-service/browser flows and removes its disposable data.
It does not modify persistent preview or existing developer volumes. Its required ports must
be free; see the guide when the preview is running.

Production deployments must provide managed PostgreSQL, Kafka, Redis, object storage, identity/provider credentials, TLS, and secret-manager integration. X ingestion is designed only for X's official API; scraping is prohibited.
