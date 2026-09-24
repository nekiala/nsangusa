# Requirements and assumptions

Assessment date: **2026-09-12**. Product and quality requirements below describe the target, not
completed functionality. The [completion matrix](completion-matrix.md) maps the original
specification to current evidence, accountable roles, dependencies, approval checkpoints and
acceptance criteria; [implementation status](implementation-status.md) summarizes the gaps.

## Product requirements

Nsangusa is a news platform that:

1. monitors configured X accounts through the official X API and retains only data permitted by the applicable account, plan, terms, and product purpose;
2. normalizes discovered posts, relates sources, identifies candidate stories, and requests AI-supported analysis;
3. generates structured article drafts, claims, uncertainty notes, safety flags, SEO fields, social preview text, and reviewed editorial illustrations with an explicitly approved fallback policy;
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

- Modules: `identity`, `sourceingestion`, `storyprocessing`, `aieditorial`, `articles`, `media`, `publication`, `comments`, `newsletter`, `eventprocessing`, `integration`, `administration`, `audit`, `search`.
- Implemented HTTP surfaces are recorded in `api-and-workflow.md` and `../contracts/openapi.yaml`.
- Local application defaults: PostgreSQL database/user/password `news`; Kafka `localhost:9092`; Redis `localhost:6379`; Mailpit SMTP `localhost:1025`; MinIO/S3 `localhost:9000`, bucket `news-media`, credentials `minioadmin`.
- Provider mode defaults to `disabled`; fake X/AI/image adapters require an explicit
  `local`, `test`, or `staging` profile, while production requires `PROVIDER_MODE=production`.
  Storage selection is separate: S3 is the default even locally; explicit filesystem storage is
  limited to local/test profiles.

## Assumptions requiring confirmation

- X API access tier, polling/search endpoints, expansions, retention/deletion duties, and rate limits are not assumed until verified with the production developer account.
- The backend supports local credentials and federated OIDC, Redis-backed revocable sessions, CSRF cookies, conservative verified-email account linking, configurable role claims, account export, and soft deletion. Production issuer/client registration and MFA policy remain environment-owned configuration.
- Enable the `oidc` Spring profile and provide `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, and `OIDC_CLIENT_SECRET`; the registered redirect URI is `{baseUrl}/login/oauth2/code/primary`.
- Kafka topic replication, partitions, retention, ACLs, and schema registry are platform decisions; local values are not production recommendations.
- Retention periods in `data-model.md` require legal/privacy approval.
- The production editorial Java adapter implements the documented OpenAI Responses protocol.
  Approved model access, policy/region/retention settings and representative editorial quality
  still require qualification; a protocol implementation is not provider acceptance.
- Claim evidence, correction/retraction rules and sensitive-topic policy require editorial
  acceptance. AI confidence and prompt instructions cannot guarantee factual accuracy.
- Cache/CDN/search/feed removal after unpublication, provider email acceptance uncertainty,
  workload/cost limits, named operational ownership and jurisdiction-specific consent need
  explicit acceptance. Decisions D01-D10 in the completion matrix record the open gates.

## Incremental implementation approval

The user requested implementation one step at a time, with approval before each subsequent step.
Step 1 established the documented baseline. Step 2's usable browser-to-backend vertical slice
passed local verification. The user approved beginning Step 3 immediately afterward; its editorial
domain, retry, scheduling and browser gates are now complete locally. Step 4's reader and
administration scope was completed on 2026-09-18. On 2026-09-19 the user authorized undeploying
the local preview and starting Step 5. Step 6 still requires subsequent approval. Each checkpoint
includes its own implementation, relevant checks, documentation and remaining limitations.

## Non-goals

- Publishing articles, replies, reposts, likes, or direct messages to X.
- Unqualified autonomous production publication; production remains locked to mandatory human
  review until policy, provider, and operational acceptance is explicitly completed.
- Scraping X or storing unrestricted X content.
- Distributed microservices or direct cross-module table writes.
