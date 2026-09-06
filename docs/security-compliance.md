# Security, abuse resistance, and X compliance

## Threat model

| Threat | Primary controls |
|---|---|
| Session theft/fixation/CSRF | Redis-backed server sessions, rotation on login, Secure/HttpOnly/SameSite cookies, `XSRF-TOKEN`, origin checks, bounded concurrent sessions |
| Credential attacks | Argon2id-class password hashing, verification, rate limits, generic failures, MFA/step-up for planned admin IdP |
| Broken object authorization | Deny by default, role and object checks, opaque UUIDs, masked `404` where appropriate |
| X API misuse/data overcollection | Official API only, minimum scopes, monitored allow-list, permitted fields, retention/deletion jobs, rate-limit handling |
| Prompt injection/data exfiltration | Content-as-data separation, typed provider schemas/tools, source authorization, output validation, human approval |
| SSRF | No arbitrary fetch by models; HTTPS/FQDN allow-list, DNS/IP validation, redirect revalidation, private-range denial |
| XSS/injection | Parameterized SQL, bean/schema validation, sanitized article/comment rendering, contextual encoding, CSP |
| Event spoofing/replay | TLS/SASL/ACLs, schema validation, event IDs, producer identity, inbox dedupe, controlled replay |
| Supply chain | Lockfiles, SBOM/provenance, digest pins, signatures, scanning, restricted CI and runtime |
| Privileged misuse | Separate editor/moderator/administrator roles, audited actions, dual control for replay/retention changes |

## X official-API compliance

- X is a **source-ingestion provider only**. The platform does not create posts, replies, reposts, likes, DMs, or publish article content to X.
- Use documented X endpoints and approved authentication for the real developer account/plan. No scraping, browser automation, shared user credentials, or rate-limit circumvention.
- Configure only accounts the product is authorized to monitor. Persist account/post identifiers, canonical URL, publication time, selected topics, and `permittedText` needed for the editorial purpose.
- Do not assume access tier, expansions, historical depth, webhook/streaming support, display rules, or retention rights. Verify current X terms at launch and on change.
- Preserve source attribution and canonical links. Do not imply endorsement by the source account.
- Do not train models on X data unless separately permitted. Send only minimum necessary source material to approved AI regions/providers under compatible retention terms.

## X data correction/deletion process

1. Identify affected account IDs/post IDs and all local `source_posts`, relationships, candidates, AI requests/results, article citations/derived text, caches, search indexes, and backups.
2. Stop further ingestion and mark the source account/post blocked while the request is assessed.
3. Delete or irreversibly redact source text and prohibited derivatives within the applicable deadline. Unpublish affected website articles when correction cannot preserve accuracy/legal basis.
4. Emit minimal internal tombstones so projections remove copies; do not put deleted text in tombstones or DLTs.
5. Reconcile PostgreSQL, Redis, Kafka-retained payload windows, object storage, AI/provider retention controls, analytics, and restored-backup deletion ledgers.
6. Record non-content evidence in audit, notify the authorized requester where required, and alert until complete. Legal hold exceptions must be authorized and reviewed.

The system does not delete posts from X because it did not create or own them.

## Prompt-injection and tool controls

1. Treat X text, comments, article content, retrieved documents, and provider responses as untrusted data.
2. Keep system policy and credentials outside prompts. Bound source material by authorization, size, topic, and classification.
3. Providers return typed analysis/draft/image results. Models cannot alter roles, monitor arbitrary accounts, fetch URLs, publish, moderate, send newsletters, or select credentials.
4. Validate claims, supporting source IDs, URLs, confidence, warnings, safety flags, HTML/Markdown, and image metadata before persistence.
5. Human approval is tied to an immutable revision; edits invalidate approval.
6. Log provider/model/prompt version, timing, counts, and safe failure codes—not prompt bodies or secret-bearing responses.

## SSRF controls

- Dedicated adapters accept provider base URLs from trusted configuration, not requests or model output.
- For any approved fetch feature, allow only HTTPS hosts; normalize URLs; reject userinfo, unusual ports, oversized/decompression-heavy responses, and unsafe MIME types.
- Resolve and re-check every redirect/connection target; deny loopback, private, carrier-grade NAT, link-local, multicast, reserved, and cloud-metadata ranges.
- Enforce egress through network policy plus an FQDN-aware proxy/firewall. Kubernetes CIDR policy alone is insufficient.

