# Contracts

- `openapi.yaml`: OpenAPI 3.1 language-neutral HTTP contract for every implemented controller
  route. The marked login/logout operations are supplied by Spring Security rather than a
  controller.
- `events/envelope.schema.json`: common Kafka event envelope.
- `events/*.schema.json`: complete runtime integration catalog (23 event types), envelope, and
  shared payload definitions. Event-specific schemas validate the **whole envelope**, including
  `ArticleUnpublished` and `ArticleReadyForReview`.
- `compatibility/v1/`: immutable synthetic supported-v1 examples established on 2026-09-19,
  with checksums pinned in `SupportedV1BaselineTests`. `compatibility/v1-additions-2026-10-01/`
  adds examples for the seven event types introduced on 2026-10-01 and is pinned the same way. They are independent inputs, not generated
  from the current DTOs. Add a new baseline for an intentional protocol change; do not regenerate
  these files to make a failing gate green.

The Java runtime event catalog mirrors the implemented event types. Producers validate typed
payloads before outbox insertion, and consumers reject unknown types, unsupported schema versions,
payload/type mismatches, and Bean Validation failures before applying business effects.

The contracts are implementation-independent. Generate language bindings only in consumer
repositories and keep generated code out of this directory.

## Executable gates

Run the complete contracts suite from the repository root (requires Docker for the mandatory
PostgreSQL migration-overlap test):

```sh
cd backend
./gradlew test --tests 'com.nsangusa.news.contracts.*'
```

The remaining unit gates require no service, Docker, network schema resolution, or paid provider:

```sh
./gradlew test --tests '*PayloadContractTests' --tests '*StructuredContentContractTests' \
  --tests '*SupportedV1BaselineTests' --tests '*StaticOpenApiContractTests' \
  --tests '*StaticControllerRoutesTests' --tests '*ContractSchemasTests'
```

- `StaticOpenApiContractTests` / `StaticControllerRoutesTests`: every MVC/security method/path,
  local references, duplicate/unsupported mappings, unique operation IDs.
- `HttpPayloadContractTests`: real MVC controllers and actual DTO serialization, with application
  ports mocked, checked against the **operation's** request/response schema. Covers registration,
  profile reads/updates, export, sessions, admin user/detail/page/role changes, article body-only,
  null-content and structured-only commands, structured and historical article/revision views,
  explicit non-AI fallback requests/responses, and real exception-advice 400/404/409 problems.
  It is not a replacement for security-filter, persistence, fullstack, or all-endpoint tests.
- `StructuredContentContractTests`: six block types, nullable unused **known** fields, unknown
  fields, versions, URI safety, 1–200 blocks, 1–100 list items, 2048-character URLs, and the
  normalized 100000-character aggregate bound including separators and link URLs. JSON Schema
  cannot sum strings across blocks; the real Jackson constructor is the additional gate.
- `EventPayloadContractTests`: exact catalog/schema/example coverage, every event's frozen
  producer example through the production Jackson configuration and incoming reader, current
  serialization/roundtrip, optional-field omissions, every required field's absence/null,
  unknown envelope/payload/nested fields, canonical UUID/date-time, unsupported versions and
  scalar coercion rejection. Durable invalid examples are composed with every valid event.
  Deliberate schema mutations demonstrate that adding a required field, changing a field type
  or version, removing nullability, and weakening unknown-field rejection are detectable.
- `SupportedV1BaselineTests`: checks byte-level SHA-256 of the established fixture files.

Validation uses test-only `com.networknt:json-schema-validator:1.5.9` (Draft 2020-12, format
assertions enabled), pinned in Gradle and its lockfile. References are bundled locally; the
validator does not fetch `.invalid` URLs. This is a general JSON Schema implementation, not a
keyword-selective validator. OpenAPI's annotation-only formats (e.g. `int64`, `password`) are
not extra runtime constraints. CI's pinned Ajv schema compilation and Redocly lint remain
additional syntax checks, not substitutes for these tests.

Email-format validation retains the library's mailbox checks but uses syntactic domain
validation instead of a delegated-TLD registry. Reserved fixture domains such as `example.test`
remain valid without DNS access. `ContractSchemasTests` covers malformed mailbox/domain/IP
rejection and confirms that other format assertions remain enabled; frozen examples are unchanged.

`ProtocolMigrationOverlapTests` is mandatory and starts its own disposable PostgreSQL 18.4
Testcontainer, following the existing core migration-test pattern. Missing Docker fails rather
than skipping the test; no environment skip exception or externally supplied database is needed.
It also uses a uniquely named schema and cleans only that schema.

```sh
./gradlew test --tests '*ProtocolMigrationOverlapTests'
```

It migrates V18 → V20 → V21, proves nullable expansion preserves old rows and historical revision
JSON without backfill, and runs pre-content SQL insert/update shapes after migration. The existing
`MediaDeliveryMigrationTests` separately checks V21's synchronous exact provider/model provenance
in the existing opt-in acceptance database using `MEDIA_MIGRATION_JDBC_URL`,
`DATABASE_USERNAME`, and `DATABASE_PASSWORD`.
No new migration is required. These are **SQL-shape and current-code** overlap checks, not an
old-application-binary deployment qualification.

## Supported directions and rollout limits

There is no configured remote, released-contract tag, or retrievable released binary in this
repository. **This baseline is not a “last release”.** It establishes a supported-v1 promise from
synthetic examples now; it cannot prove compatibility with an unknown prior deployment. Finite
positive/negative fixture tests are regression evidence, not a mathematical language-inclusion
proof for every possible JSON document.

| Producer / writer | Consumer / reader | Gate and support |
| --- | --- | --- |
| Frozen supported-v1 event examples, including absent optional fields | Current catalog + actual Jackson reader | Supported and executable for all 23 event types across both baselines |
| Current typed event serialization | Current schema + current reader | Supported and executable roundtrip |
| Six-field legacy `ArticleImageApproved` shape | Current reader | Supported; absent/null flag retains the legacy “generated” meaning at application consumption; six-argument constructor still supplies `true` |
| Current approval with `generatedImage` present (`true`, `false`, or `null`) | Strict modeled six-field legacy consumer | **Unsupported**, explicitly rejected by both a frozen schema and a strict Jackson record; this model is not claimed to be an old binary |
| Old body-only HTTP command / historical view lacking content | Current HTTP DTO/controller | Supported; content is optional/nullable and structured content, when supplied, is authoritative |
| Current structured article response | Unknown old strict client | **Not certified**; strict clients can reject the added field |
| Old SQL insert/update of a still-legacy article | Expanded V20/V21 database | Supported SQL-shape overlap; content stays null |
| Old body-only writer after a row has structured content | Expanded database/current structured reader | **Unsupported**: body and content can diverge, demonstrated by the migration test |

Upgrade event consumers before enabling current image producers/fallback actions. Do not make
unknown fields lenient to hide this incompatibility, and do not remove the false flag to feed an
old consumer: that would mislabel an original editorial illustration as AI-generated. V21 is
also semantic (exact `nsangusa-editorial` / `neutral-illustration-v1` provenance), not merely an
additive column change.

Expand the database first, retire old article writers before enabling structured edits, and
upgrade strict HTTP readers before emitting new fields to them. Rollback to an old writer after
structured writes, or to an image consumer without non-AI semantics, is not qualified. An
old/new **application-binary** overlap run remains a release prerequisite when real immutable
application artifacts become available; no fixture or simulated record is certification of one.
