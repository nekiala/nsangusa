# Local development and debugging

## Prerequisites

Java 25, the repository-supported Java build tool, Node.js 24.20.0, the checked-in JavaScript package manager, Docker Compose, Git, and IntelliJ IDEA.

## Start dependencies

```bash
docker compose -f infrastructure/compose/compose.yaml up -d
docker compose -f infrastructure/compose/compose.yaml ps
```

Defaults match `application.yml`: PostgreSQL `news/news/news` on `5432`; Kafka `9092`; Redis `6379`; Mailpit SMTP/UI `1025/8025`; MinIO API/UI `9000/9001`, credentials `minioadmin/minioadmin`. Init services create private bucket `news-media`, the four workflow topics, and their `.dlt` topics.

The Spring `local` profile references this Compose file and selects `news.providers.mode=fake`. Real X/AI/image credentials are intentionally absent.

## IntelliJ

1. Select Java 25 and import the backend with its checked-in build tool.
2. Run the Spring Boot application with profile `local`; set `COOKIE_SECURE=false` only for local HTTP.
3. Use Node.js 24.20.0 for frontend tasks.
4. Add breakpoints at source discovery/normalization, story candidate consumer, editorial workflow consumer, article/image consumers, publication consumer, outbox relay, and DLT/retry handling.
5. Correlate `eventId`, `aggregateId`, `correlationId`, `causationId`, `traceContext`, and `idempotencyKey`.
6. Use Modulith/integration tests for boundaries and Testcontainers for isolated data tests. Shared Compose is for interactive development.

## Provider switching

Set `PROVIDER_MODE=fake` for deterministic local work or the implemented production mode for real adapters. Configure `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`, `AI_PROMPT_VERSION`, `IMAGE_BASE_URL`, `IMAGE_API_KEY`, `IMAGE_MODEL`, and `X_BEARER_TOKEN` only through local ignored environment files or secret tooling.

Real accounts require region/privacy/retention review, quotas, X access-tier confirmation, representative schema/safety tests, latency/cost checks, and operational alerts. Never silently fall back between models/providers for an already approved revision.

## Debugging safety

- Mailpit and MinIO contain local data only.
- Do not log X bearer tokens, AI/image keys, passwords, sessions, CSRF values, complete prompts, article/comment personal data, or provider response bodies.
- Do not expose debuggers, Kafka, PostgreSQL, Redis, MinIO, Mailpit, or OTel ports beyond loopback.
