# Nsangusa frontend

An editorial Next.js 16 App Router frontend with server-rendered public publishing pages and a typed Spring Boot API client.

## API configuration

Copy `.env.example` to `.env.local`. The default `NEXT_PUBLIC_API_MODE=api` calls the implemented
Spring Boot routes. Leave `NEXT_PUBLIC_API_URL` empty to use the same-origin `/api/v1` proxy;
`NSANGUSA_API_URL` is its backend destination and is used for server-rendered public requests.
The `/api/v1/[[...path]]` Node route handler reads that destination at runtime; there is no
build-time Next rewrite. The same standalone image can therefore be restarted against another
backend origin without rebuilding. Supply an exact HTTP(S) origin, without credentials, path,
query, fragment or wildcards, in the running container's `NSANGUSA_API_URL`.
Set server-runtime `PUBLIC_BASE_URL` to the public frontend origin, using the same name/value as
the backend's public-link configuration. It controls canonical/metadata bases, Open Graph and
NewsArticle URLs, robots, RSS and sitemaps. When absent/empty, the existing compiled
`NEXT_PUBLIC_SITE_URL` remains the compatibility fallback, then `http://localhost:3000`.
Internal navigation uses relative links, so it stays on the browser's public origin.
Public pagination uses native document navigation to keep fresh page results, titles and
canonical metadata synchronized, including when returning to a previously visited page.
Neither setting is inferred from untrusted request/forwarding headers. `PUBLIC_BASE_URL` accepts
an exact HTTP(S) origin, not credentials, a path, query, fragment or wildcard.
An explicit browser API origin requires matching backend CORS and cookie settings.
Browser requests use `credentials: "include"` for the opaque Spring session cookie. No
tokens are persisted in `localStorage`.

Mutations bootstrap `/api/v1/auth/csrf` and send its returned header/token pair on the same request
credentials. All API requests, including public articles/discovery, are `no-store`. Article pages,
RSS and sitemap are dynamically rendered without ISR or shared-cache retention so a withdrawn
article is not served from a frontend cache. Configure the API and frontend as same-site (or configure backend CORS and
cookie policy) so session cookies can be sent.

Every admin/editorial mutation sends one `Idempotency-Key` generated with `crypto.randomUUID()` per
action. Unconfirmed transport/server/response-decoding failures retain that key for unchanged
retries within the current client. The bounded retry registry is in memory only and clears on
authentication changes or reload; reconcile unknown outcomes before restarting the session.
Mutating admin client methods also accept an optional request key for explicit retries of
the identical action, original payload and expected version (including unchanged array ordering).
A completed or revised action needs a new key; reusing a key for different input returns `409` on
durable endpoints. The client does not automatically retry mutations. CSRF bootstrapping does not
regenerate the action's key.
Sending a header alone does not make an endpoint durable: implemented receipt boundaries cover
editorial, role, community, newsletter-preference/reconciliation, AI-configuration and replay
commands. Unrelated account/token operations retain their own version/single-use semantics.
Article approval, publication, rejection, unpublication, restoration and archival helpers use
`(id, requestKey?, expectedVersion?)`; the editor always sends the currently displayed article
version. Their legacy no-version callers remain compatible.

Set `NEXT_PUBLIC_API_MODE=fake` explicitly for local visual demos or tests without a backend. Fake
mode supplies explicit demo accounts, sources, candidates, analysis and image generations in memory;
it is not selected automatically and is not a durable publishing backend.

## Step 2 editorial workspace

- `/admin/editor`: paginated articles with a state filter; `/admin/editor/new` creates a manual
  article from actual ingested source records; `/admin/editor/{id}` reviews and edits an article.
- `/admin/candidates`: paginated candidates, source evidence, AI claims, confidence, warnings,
  provider/model/prompt provenance, and regeneration into a new candidate without replacing articles.
- `/admin/handles`: discover accounts and sync health, add/edit/pause/remove/block accounts, inspect
  and exclude source posts. Resolve handles through the configured provider or enter official account
  IDs manually. Permitted simulated-post controls appear only when the backend advertises support.
- Image previews use authenticated, uncached bytes. Editors approve each new image; a replacement
  does not displace the selected image until approved. Refresh the review after asynchronous work.
- Multiple source identities and SEO values survive edits. Content uses safe structured blocks;
  text remains literal and HTML/embeds are never interpreted.
  Unsaved edits and backend state gate approval/publication. Scheduling uses the editor's selected
  local datetime, converted to an explicit UTC instant.
- `/admin/comments` now exposes moderator queues, reports, history, policies and account privileges.

Public articles include attribution, editorial context, updated time, selected images with generated
image disclosure, SEO metadata, correction notes on published articles, and newsletter signup. Subscribers choose immediate/daily/weekly
delivery. Email links land on `/newsletter/confirm` and `/newsletter/unsubscribe`; neither calls the
mutation API until the reader presses its explicit button. Token pages are noindex/private/no-store.

## Step 3 publication lifecycle

- Article details include paginated immutable revisions and side-by-side comparison of changed
  stored fields. Legacy or redacted records with `snapshot:null` are labelled “Full snapshot
  unavailable”: only stored headline/summary/body are shown, never replaced with current article
  content or invented metadata. History inspection never restores an old snapshot.
- Starting a correction requires a public note and acknowledgment that the article is immediately
  withdrawn. Edit, save, review, approve and republish normally. The canonical article ID/slug and
  original publication time remain unchanged; a correction does not send another publication email.
- Published articles can be unpublished; unpublished articles can be restored or archived, and
  rejected articles can be archived. Restoring publication likewise does not send another email.
- `/admin/schedules` lists paginated scheduled/failed/published/cancelled entries with audit/version
  details and publication errors. Active entries can be rescheduled or cancelled using the current
  **schedule** version. Failed schedules require an explicit future reschedule to retry. Entries
  without a captured article version offer cancellation only, followed by review and a new schedule.
  Cancel first before editing a scheduled article's text or images.
- Current publication policy, confidence/topic thresholds and approved source accounts are read-only
  backend configuration. This workspace does not add policy-editing or unrelated administration.

### Step 3 API contract

All paths below begin with `/api/v1/admin`:

- `GET /articles/{id}/revisions?page=0&size=20` → `{items,page,size,total}`.
  Revisions include `id, revisionNumber, reason, actorId, createdAt, headline, summary, body,
  snapshot, aiGenerationResult, legacy`. Full snapshots contain editorial content, sources,
  image selection, review/approval metadata, state, correction note and publication timestamps.
  An unavailable `snapshot:null` (legacy or redacted) is supported using only its stored text fields.
- `GET /articles/{id}/revisions/compare?from=1&to=2` → `{from,to,changedFields}`.
- `POST /articles/{id}/corrections` with `{expectedVersion,note}` → `204`.
  Article responses additionally include nullable `correctionNote, approvedBy, approvedAt`.
- `POST /articles/{id}/{approve|publish|reject|unpublish|restore|archive}?expectedVersion=N`
  → `202` for approval, `204` otherwise. The editor sends its displayed version so a stale review cannot act on another
  revision; a mismatch returns `409` and requires refreshing/reviewing before a new action.
  The query parameter is optional for legacy clients, but retries must preserve its original value.
- `GET /publication-schedules?page=0&size=20&status=scheduled` → `{items,page,size,total}`;
  optional `articleId` is supported by the client. Entries contain `id, articleId, publishAt, status,
  version, articleVersion, scheduledBy, updatedBy, createdAt, updatedAt, completedAt, lastAttemptAt,
  attemptCount, lastError`. Statuses are `scheduled`, `failed`, `published`, `cancelled`.
- `PATCH /publication-schedules/{id}` with `{expectedVersion,publishAt}` and
  `POST /publication-schedules/{id}/cancel` with `{expectedVersion}` return the current entry,
  including on successful replay; the UI refreshes inventory rather than assuming a frozen result.
  Initial scheduling remains `POST /articles/{id}/schedule` with `{publishAt}` → `{scheduleId}`.
- `GET /publication-policy` → `{policy,confidenceThreshold,topicRules,approvedSourceAccounts,
  humanPublicationAllowed,explanation}`. Approved accounts are lowercase source handles without `@`;
  confidence values are fractions. Canonical policies are `HUMAN_REVIEW_ALWAYS`,
  `CONFIDENCE_THRESHOLD`, `APPROVED_SOURCE_ONLY`, `DRAFT_GENERATION_ONLY`, and `TOPIC_RULES`.
  Draft-generation-only prevents automatic publication, not explicit editor approval/publication.

## Step 4 reader and administration workspaces

`/profile` provides versioned account/preference editing, session inventory/revocation, export
and confirmed deletion. `/verify-email` and token-bearing `/password-reset` require explicit
completion; `/newsletter/preferences` requests and consumes expiring consent-bound links.
These pages are private/no-store/no-referrer; no token action runs merely because a link is opened.

Role-aware staff navigation exposes editorial controls to editors, moderation to moderators, and
users/roles, newsletter evidence, AI selections/prompts, audit, failed-event replay and workflow
health to administrators. Backend authorization remains authoritative. User/article selectors use
real inventories rather than manual UUID fields. Replay confirmation requires a fresh completed
dry run and explicit acknowledgment.

Published article discussions fetch personalized current policy, including global/per-article
switches, verified-user eligibility and suspensions. Readers can edit their own comments within
the configured window, delete, report and submit one-level replies. Public pagination and related
articles use actual backend inventory. `/sitemap.xml` is an index of bounded article segments;
`/rss.xml?page=N` provides paginated feeds. All publication-sensitive responses remain no-store.

AI configuration selects only deployed providers/models and immutable prompt versions. It cannot
edit secrets, provider URLs, the operator model ceiling, safety policy or image models.

## Step 5 browser accessibility and response headers

The production Next proxy generates a fresh cryptographic nonce for every HTML request, replaces
untrusted incoming CSP/nonce headers, and forwards the policy to Next so its App Router bootstrap
and streamed scripts receive that nonce. The root layout deliberately uses `force-dynamic`:
**all HTML is request-rendered and private/no-store**, not statically exported, prerendered or
shared-cached with a reused nonce. Static assets remain cacheable; API responses, static assets and
XML/text feeds do not receive the HTML CSP. The existing application nosniff, frame-denial,
referrer and permissions headers remain in place.

Production scripts require a nonce and use `strict-dynamic`; neither script `unsafe-inline`
nor `unsafe-eval` is permitted. Script attributes, objects, frames and base URL changes are
blocked. Stylesheets/style elements require self or a nonce; **element style attributes** remain
allowed for React/Next rendering. Images permit self, blob/data and the explicit browser API origin;
connections permit self and that same API origin. No wildcard sources are used. Development alone
adds eval and the exact local websocket origin. The policy intentionally does not upgrade HTTP:
local HTTP acceptance works, while TLS/HSTS, network egress and deployment ingress controls remain
separate deployment qualifications.

Leave `NEXT_PUBLIC_API_URL` empty for same-origin API access. If required, set it to an exact
HTTP(S) origin, without credentials, paths, query, fragment or wildcards. It is a **build-time**
browser setting, including in the CSP; changing a container's environment alone cannot change it.
The Dockerfile accepts it as an optional build argument. `NSANGUSA_API_URL` remains the internal
runtime server/API-proxy destination, not a browser CSP allowlist entry. A Docker build argument
does not select the eventual runtime destination: provide the running container's environment.
`PUBLIC_BASE_URL` likewise overrides the compiled `NEXT_PUBLIC_SITE_URL` for server-rendered
publication URLs. Root metadata is resolved at request time, and robots is dynamic/no-store,
so none of these outputs retain a build-environment public origin.
Cross-origin API use still requires appropriate backend CORS/cookie configuration.

The runtime proxy streams request/response bodies without JSON re-encoding, preserves opaque
session/CSRF cookies as separate `Set-Cookie` headers, forwards CSRF/idempotency and supported API
headers, and preserves response status, binary/range and download semantics. It neither follows
upstream redirects nor retries requests; same-backend redirect locations become same-origin paths.
The target always comes from operator configuration, never a request host, query parameter or
forwarding header. Hop-by-hop and framework-control headers are removed; incoming forwarded
identity/address headers are not trusted. Backend rate limits therefore see the frontend's
connection address, not a spoofable client IP. Per-client ingress limits, trusted proxy chains and
secure-cookie/TLS settings remain deployment responsibilities. Fetch cancellation and a 30-second
upstream deadline apply; transport failures produce a generic no-store 502, without disclosing
internal destinations or replaying possibly committed mutations.

`npm run test:runtime-proxy` builds API mode against the intentionally unusable
`http://build-time.invalid:8080` API origin and `http://build-time.invalid:3000` public origin, then starts
that same standalone artifact twice with different owned loopback backend/public origins.
It proves runtime API routing and server-rendered discovery, canonical/Open Graph/NewsArticle
metadata, robots/RSS/sitemap URLs, cookies/CSRF/idempotency, status/redirects, streaming and header
filtering. It checks artifact hashes
remain unchanged and closes all its fixture processes/listeners. This uses the existing Vitest
runner and no shared services; the isolated real-backend suite remains a separate qualification.

`@axe-core/playwright` is pinned to 4.13.0. The Step 5 real-backend cases cover public forms/search,
verified-reader profile and structured article/comment content, inline deletion confirmation,
editor preview, moderator review and administrator role-confirmation forms. Desktop and
320/390-pixel layouts are scanned without disabled rules or excluded page regions, using
WCAG 2.0/2.1/2.2 A/AA and best-practice tags. The automated gate is **zero critical or serious
findings**; full results, including lower-severity violations and incomplete checks, are attached
as JSON. Additional assertions cover landmarks/headings, keyboard navigation and actions,
visible focus, skip links, safe initial/returned confirmation focus, reduced motion, semantic
reader content and document-level horizontal overflow. The confirmation is an inline group,
not a modal dialog, and does not trap focus. Each scenario owns its created account/article;
it does not depend on another scenario publishing a comments-enabled article.

Production-browser header checks verify nonce rotation, nonce-bearing Next scripts, prevention of
incoming nonce/policy spoofing, normal client navigation/hydration, and actual blocking of
parser-inserted untrusted inline scripts and event handlers. Fake-mode regression scans provide
fast feedback, including authenticated image blob rendering, but do not substitute for real-backend
acceptance. Automated scans are **not screen-reader certification**: manual keyboard/focus review,
screen-reader announcements and reading order, zoom/OS high-contrast, touch targets and representative
assistive-technology/browser combinations still require human qualification. External API origins,
TLS, production ingress/egress and external providers are not certified by the local browser suite.

### Structured content and fallback illustrations

The editor supports paragraphs, level-two headings, quotations, ordered/unordered lists and
absolute HTTP(S) links, including block reordering and accessible item controls. Preview, public
rendering and revision comparison share the version-one block format. The server derives canonical
plain `body` from `content`; content-only commands work and legacy body-only commands remain
supported. Missing content is interpreted as paragraphs; malformed supplied content is an error,
not silently replaced. Existing historical snapshots are not backfilled.

An editor can explicitly create a neutral fallback illustration with alternative text and a reason.
`POST /api/v1/admin/articles/{id}/images/fallback` requires `{altText,reason,expectedVersion}` and
an `Idempotency-Key`. It is available only for editable drafts, rejects stale versions, and creates
original programmatic PNG artwork without calling the image provider. The candidate records
`nsangusa-editorial / neutral-illustration-v1` provenance and requires separate image approval.
Neither creation nor a failed provider request replaces a selected image automatically.
Approved fallback artwork is not labelled AI-generated. The public site does not claim that it is
a photograph of the reported event.

### Publisher policies

Privacy, terms, editorial standards and corrections use a versioned, server-rendered template.
The copy describes actual account/export/deletion limits, cookies, consent, source/AI attribution,
image disclosure, correction withdrawal and the inability to retract delivered email. No publisher
identity, jurisdiction, retention promise or legal approval is invented.

Configure `PUBLICATION_PUBLISHER`, `PUBLICATION_CONTACT_EMAIL`, `PUBLICATION_PRIVACY_EMAIL`,
`PUBLICATION_JURISDICTION`, `PUBLICATION_RETENTION_SUMMARY` and
`PUBLICATION_POLICY_EFFECTIVE_DATE` in the frontend runtime environment (or Helm `frontendConfig`).
Contacts are plain text, not executable markup. Until the publisher and its editorial/privacy
reviewers approve the exact rendered copy, leave `PUBLICATION_POLICY_APPROVED_VERSION` empty:
the pages remain visibly marked as drafts.

After that separate approval, set `PUBLICATION_POLICY_APPROVED_VERSION=2026-09-v1`. Approval with
missing/placeholder details, an invalid date/contact, or a different template version fails
explicitly rather than publishing success-shaped placeholder content. This setting records an
operator attestation; it does not provide legal certification or replace jurisdiction-specific
review. Re-review the published copy and bump the template version whenever its substance changes.

## Commands

```bash
npm install
npm run dev
npm run lint
npm test
npm run test:runtime-proxy
npm run build
npm run start
npm run test:e2e
```

Public pages include an RSS feed at `/rss.xml`, `sitemap.xml`, and `robots.txt`. Public publication content, admin, profile, and account paths deliberately send private/no-store cache headers. Default Playwright tests build and start the frontend in explicit fake mode; no paid providers are used. Install browsers if needed with `npx playwright install chromium`.

`test:runtime-proxy` builds once against deliberately unusable build-time origins, then starts
the unchanged standalone artifact twice with different runtime API and public origins. Owned
local HTTP fixtures verify cookies, CSRF, header filtering, redirects, streaming, rendered
metadata and feeds. This does not replace the real-backend browser suite below.

### Optional real-backend browser validation

Start an isolated backend using its local fake editorial/image/X providers and SMTP Mailpit, with
a seeded **administrator** login. Copy `.env.example` to `.env.local` for ordinary frontend use;
provide the `FULLSTACK_*` environment variables below in the shell running Playwright (a backend
environment file is not automatically loaded by the frontend). Configure the backend's public site
URL to the frontend origin below. Step 4 tests require the updated backend migrations and APIs,
not a still-running earlier backend.
The test does not provision or reset backend infrastructure.

```bash
FULLSTACK_API_URL=http://127.0.0.1:18080 \
FULLSTACK_FRONTEND_URL=http://127.0.0.1:13000 \
FULLSTACK_MAILPIT_URL=http://127.0.0.1:8025 \
FULLSTACK_EDITOR_EMAIL=your-isolated-admin@example.test \
FULLSTACK_EDITOR_PASSWORD='your-local-test-password' \
npm run test:e2e:fullstack
```

This config builds and starts an API-mode production Next server with an isolated `.next-fullstack`
output directory. Production mode also verifies the actual private/no-store response headers;
Next development mode overrides those headers.
Set `FULLSTACK_START_FRONTEND=false` to use an already running API-mode frontend, or
`FULLSTACK_REUSE_FRONTEND=true` to permit reuse. Backend clustering must retain its short collection
window so two consecutive simulated posts form one candidate; the fullstack timeout is four minutes.
Scenarios start in separate fixed-minute rate-limit windows because their traffic shares the
local proxy's IP. Allow roughly eleven minutes for the full suite. The normal backend limits remain
enabled, and mutations are not automatically retried.

The test creates uniquely named accounts/posts and subscription addresses, then checks real candidate
analysis, image bytes/approval/regeneration, explicit fallback approval/provenance, preserved
multiple sources, structured-content preview/public rendering, publication,
correction withdrawal and cached-page/API 404s, revision comparison, canonical ID/date preservation,
restore, feed/sitemap freshness, schedule rescheduling/cancellation, browser email confirmation,
delivered publication email and explicit unsubscribe. It checks for no second publication email after
correction/restore with a bounded five-second SMTP observation window.
Candidate regeneration must preserve the original published article. It only pauses its own account;
it does not delete shared data or mail. Generated fixture IDs are attached to the test result for
inspection. The fullstack suite is excluded from default unit and browser runs.

The remaining scenarios cover verified reader lifecycle/session invalidation, discussion and
moderation, role changes and revoked authorization, immutable AI guidance/selection, consent-bound
newsletter preferences, audit and replay inventory. Disposable account/mail operations are scoped
to their own fixtures. This local acceptance does not authorize paid providers or deployment.
The three Step 5 accessibility/header scenarios can be selected with
`npm run test:e2e:fullstack -- step5-accessibility.spec.ts`; the full suite includes them automatically.

## Container

The standalone container uses the repository's digest-pinned Node 24.20.0 Debian runtime and runs
as non-root UID/GID 10001. Only the final runner stage removes unused global npm; dependency and
build stages retain it. Local environment files, dependencies and build artifacts are excluded
from the build context.

```bash
docker build -t nsangusa-frontend .
docker run -p 3000:3000 nsangusa-frontend
```
