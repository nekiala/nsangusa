# Contracts

- `openapi.yaml`: OpenAPI 3.1 language-neutral HTTP contract for every implemented controller
  route. The marked login/logout operations are supplied by Spring Security rather than a
  controller.
- `events/envelope.schema.json`: common Kafka event envelope.
- `events/*.schema.json`: implemented integration envelope, shared payload definitions, and key workflow event specializations.

The contracts are implementation-independent. Generate language bindings only in consumer
repositories and keep generated code out of this directory.

Run `cd backend && ./gradlew test --tests '*StaticOpenApiContractTests'` to parse the YAML, resolve
local references, enforce unique operation IDs, and compare static OpenAPI methods/paths with all
Spring MVC controller mappings and configured Spring Security login/logout URLs. The check uses
repository sources and SnakeYAML only; it needs neither Docker nor a paid provider.

Release CI should additionally lint OpenAPI, validate JSON Schema and examples, and check backward
compatibility against the last released contract.
