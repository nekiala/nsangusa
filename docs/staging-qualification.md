# Combined Step 5 and Step 6 qualification

Step 6 was authorized on 2026-09-21, including work needed to close Step 5. This permits
implementation and isolated operational exercises; it does not approve provider charges, legal
terms, production deployment or Step 7. Local exercises can proceed while external prerequisites
remain blocked. They are not a substitute for those prerequisites.

## Evidence required before promotion

The controlled deployment workflow requires reviewed, artifact-bound qualification records for
staging and production. A test-environment deployment still requires the existing signed,
quality-qualified images and secure environment configuration, but is not presented as staging
or production qualification.

The reusable quality workflow also requires the isolated recovery/retention and monitoring-rule
exercises. Their synthetic, non-secret evidence is retained separately from backup keys or
archives. These automated exercises cannot populate the production approval records by themselves.

| Gate | Required evidence |
|---|---|
| `security` | Complete dependency/application/runtime image scans, including Java analysis, current intelligence, immutable artifact identities, and disposition of findings under the security policy. No blanket unfixed-vulnerability exception is approved. |
| `accessibility` | Named reviewer's keyboard and screen-reader acceptance, browser/assistive-technology versions, journeys, findings and disposition. Automated axe output alone does not close this gate. |
| `release-protections` | Actual required checks, protected default branch/release tags, workflow ownership, independent environment approval, restricted credentials and administrator-bypass settings. |
| `provider-contracts` | Approved X, AI, image, mail and identity accounts/contracts, regions, quotas, retention, data-processing rights and representative live-account qualification. |
| `platform-security` | Target TLS, encrypted stores, database/Kafka permissions, network egress, workload identity, secret-manager integration and rotation evidence. |
| `observability` | Working metric/trace/log collection, deployed dashboards, actionable alerts and demonstrated delivery to the named on-call receiver. |
| `capacity-and-cost` | Approved traffic/subscriber envelope, provider budgets, measured latency and workflow objectives, saturation and multi-replica failure exercises. |
| `restore-and-rollback` | Target backup/PITR and object/config recovery, deletion-ledger replay, integrity, measured RPO/RTO, schema-compatible rollback and forward recovery. A local dump drill is not managed PITR. |
| `retention-and-policy` | Approved per-store retention/legal holds, provider/backup deletion policy, publisher identity/contact and public policy content. |
| `operational-ownership` | Named engineering, editorial, security/privacy, operations/on-call and release owners, escalation and approval responsibilities. |

Staging entry requires the first three Step 5 records. Production requires all ten. This avoids
requiring a completed staging exercise before the first staging deployment while keeping
unqualified production promotion blocked.

### Record format and trust boundary

Configure the protected GitHub environment secret `QUALIFICATION_EVIDENCE_JSON` with an actual
reviewed record. It must cover the exact repository, destination environment, signed source
commit and backend/frontend/collector digests used by deployment. For example, the following
**incomplete template deliberately cannot pass**:

```json
{
  "schemaVersion": 1,
  "repository": "OWNER/REPOSITORY",
  "environment": "staging",
  "sourceCommit": "REPLACE_WITH_SIGNED_SOURCE_COMMIT",
  "images": {
    "backend": "REPLACE_WITH_BACKEND_DIGEST",
    "frontend": "REPLACE_WITH_FRONTEND_DIGEST",
    "otelCollector": "REPLACE_WITH_COLLECTOR_DIGEST"
  },
  "gates": {
    "security": {
      "status": "pending",
      "approvedBy": "",
      "evidenceUrl": "",
      "completedAt": null,
      "expiresAt": null,
      "databaseUpdatedAt": null
    },
    "accessibility": {
      "status": "pending",
      "approvedBy": "",
      "evidenceUrl": "",
      "completedAt": null,
      "expiresAt": null
    },
    "release-protections": {
      "status": "pending",
      "approvedBy": "",
      "evidenceUrl": "",
      "completedAt": null,
      "expiresAt": null
    }
  }
}
```

Each required gate must have `status: "passed"`, an accountable reviewer identifier, an HTTPS
evidence URL without embedded credentials/query tokens, and valid UTC `completedAt`/`expiresAt`
timestamps. Records expire after at most 30 days. Security intelligence must be at most 48 hours
old and predate the completed scan. Add all seven Step 6 gates, with the same common fields, for
production. Do not copy the synthetic unit-test approvals into a deployment record.

The validator checks the record, binding, completeness and freshness; it does **not** authenticate
the claimed reviewer, fetch/assess evidence or perform the human exercise. Access to the protected
environment secret, independent deployment approval and reviewer accountability are the trust
boundary. Evidence must not contain credentials or private user/source content.

### Hosted governance preflight

Configure `RELEASE_GOVERNANCE_TOKEN` as a read-only GitHub App/fine-grained credential able to
inspect the repository's branch protection, rulesets and deployment environments. Do not grant
write/admin mutation permissions. The preflight makes read-only requests to GitHub's API, refuses
redirects, and fails on unavailable/unauthorized/incomplete responses.

The preflight supports GitHub.com and pins the documented `2026-03-10` REST API version.
It rejects GitHub Enterprise execution rather than forwarding Enterprise credentials to
GitHub.com. The supported policy requires classic default-branch protection with up-to-date
`release-readiness`, fresh independent pull-request approval, administrator enforcement, and no
force pushes/deletion. Tag rulesets must actively prohibit updating/deleting `v*` release tags,
without bypass actors or excluded refs. Environment review must prevent self-review, and
deployment refs must be protected branches or an explicit default-branch/`v*` allowlist.
Repositories using only branch rulesets need the supported classic branch policy too; they are
not silently treated as protected.

GitHub fields unavailable through this preflight, including the complete administrator-bypass
and workflow-ownership policy, still require the reviewed `release-protections` evidence.
This script neither configures protections nor proves an actual hosted release has occurred.

## Post-deployment application smoke

Set the environment variable `PUBLIC_SMOKE_URL` in GitHub to the exact deployed HTTPS origin.
After successful workload rollouts the workflow reads public pages, articles, robots and sitemap,
requires unauthenticated denial for account/admin APIs, verifies no-store and nonce-based script
CSP, and fails on redirects, errors or missing controls. It performs no publication, account
mutation or provider call.

An explicitly local, read-only exercise is:

```bash
PUBLIC_SMOKE_URL=http://localhost:3000 \
  node .github/scripts/deployment-smoke.mjs --allow-loopback-http
```

A post-rollout smoke failure fails the deployment job. It does not automatically reverse a
database migration: use the schema-compatible rollback/forward-recovery runbook and preserve
the failing evidence.

## Manual accessibility acceptance

A human reviewer should record the actual browser and screen reader, date, artifact digests,
pages and findings. At minimum exercise public discovery/article/newsletter navigation; reader
registration, recovery, profile and session navigation; editor source/image/article review;
moderation; and administrator role, AI settings and operational workspaces. Include keyboard-only
focus order/visibility, landmark/headings, form labels/errors/status announcements, confirmation
focus, mobile reflow and reduced motion. Verify that authenticated navigation and role-specific
controls remain comprehensible after history navigation, reload and sign-out.

No reviewer, provider contract, jurisdiction, target cluster, approved capacity/cost envelope or
hosted protection is invented by repository tooling. Missing access or approvals remain blocked
in the completion register until real evidence is supplied.
