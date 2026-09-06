# Requirements and assumptions

## Product requirements

Nsangusa is a news platform that:

1. monitors configured X accounts through the official X API and retains only data permitted by the applicable account, plan, terms, and product purpose;
2. normalizes discovered posts, relates sources, identifies candidate stories, and requests AI-supported analysis;
3. generates structured article drafts, claims, uncertainty notes, safety flags, SEO fields, social preview text, and optional hero images;
4. requires human editorial approval before publication to the Nsangusa website; **it does not publish articles to X**;
5. provides public article reads, source attribution, approved comments, account registration/session authentication, and newsletter opt-in/confirmation/unsubscribe;
6. provides administrator/editor workflows for monitored X accounts, article approval/unpublish/restore, comment moderation, operational visibility, audit, event replay, and provider configuration;
7. emits versioned integration events through a transactional outbox and processes duplicates safely.

## Quality requirements

- PostgreSQL is the system of record. Kafka transports asynchronous workflow events. Redis stores server sessions, rate-limit state, and disposable cache data. S3-compatible storage holds generated media.
- Java modules are verified with Spring Modulith. A module writes only its owned tables and communicates through its public API or integration events.
- AI and image providers are replaceable adapters. Real credentials, models, regions, retention controls, quotas, and safety capabilities require approved provider accounts.
- X access uses only official APIs; no scraping, browser automation, credential sharing, or bypass of rate limits.
- Generated content is untrusted until validated and approved. Material edits after approval require renewed approval.
- Production stateful services are external managed dependencies; the Helm chart bundles none of them.

## Confirmed implementation baseline

- Modules: `identity`, `sourceingestion`, `storyprocessing`, `aieditorial`, `articles`, `media`, `publication`, `comments`, `newsletter`, `eventprocessing`, `integration`, `administration`, `audit`.
- Implemented HTTP surfaces are recorded in `api-and-workflow.md` and `../contracts/openapi.yaml`.
- Local application defaults: PostgreSQL database/user/password `news`; Kafka `localhost:9092`; Redis `localhost:6379`; Mailpit SMTP `localhost:1025`; MinIO/S3 `localhost:9000`, bucket `news-media`, credentials `minioadmin`.
- Provider mode defaults to `fake`; production AI, image, X, mail, object-storage, and identity/provider capabilities require real accounts and explicit configuration.

## Assumptions requiring confirmation

- X API access tier, polling/search endpoints, expansions, retention/deletion duties, and rate limits are not assumed until verified with the production developer account.
- The backend supports local credentials and federated OIDC, Redis-backed revocable sessions, CSRF cookies, conservative verified-email account linking, configurable role claims, account export, and soft deletion. Production issuer/client registration and MFA policy remain environment-owned configuration.
- Enable the `oidc` Spring profile and provide `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, and `OIDC_CLIENT_SECRET`; the registered redirect URI is `{baseUrl}/login/oauth2/code/primary`.
- Kafka topic replication, partitions, retention, ACLs, and schema registry are platform decisions; local values are not production recommendations.
- Retention periods in `data-model.md` require legal/privacy approval.

## Non-goals

- Publishing articles, replies, reposts, likes, or direct messages to X.
- Fully autonomous editorial approval or publication.
- Scraping X or storing unrestricted X content.
- Distributed microservices or direct cross-module table writes.
