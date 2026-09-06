# Nsangusa news platform

Production-oriented event-driven publishing platform implemented as one Java 25 / Spring Boot 4.1.1 modular monolith and one Next.js 16.3.4 frontend.

## Quick start

Prerequisites: Java 25, Node.js 24 LTS, Docker with Compose.

```bash
make dev
```

This starts PostgreSQL, Kafka, Redis, MinIO, Mailpit, the Spring Boot backend at `http://localhost:8080`, and Next.js at `http://localhost:3000`. Local providers are deterministic fakes; no paid API is required. Mailpit is at `http://localhost:8025`, and MinIO is at `http://localhost:9001`.

Demo accounts are created by the `local` profile:

| Role | Email | Password |
|---|---|---|
| Reader | `reader@example.test` | `reader-demo-password` |
| Moderator | `moderator@example.test` | `moderator-demo-password` |
| Editor | `editor@example.test` | `editor-demo-password` |
| Administrator | `admin@example.test` | `administrator-demo-password` |

Run all ordinary checks with `make test`. Architecture, contracts, security, operations, IntelliJ, provider, and deployment guidance is indexed in [`docs/README.md`](docs/README.md).

Production deployments must provide managed PostgreSQL, Kafka, Redis, object storage, identity/provider credentials, TLS, and secret-manager integration. X ingestion is designed only for X's official API; scraping is prohibited.
