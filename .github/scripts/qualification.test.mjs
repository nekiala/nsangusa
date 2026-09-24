import assert from "node:assert/strict";
import { test } from "node:test";
import { step5Gates, step6Gates, validateQualification } from "./qualification.mjs";
import { readReleaseControls, validateReleaseControls } from "./release-controls.mjs";
import { deploymentSmoke } from "./deployment-smoke.mjs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { mkdtempSync, readFileSync, rmSync, lstatSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { preparePreviewAi } from "../../infrastructure/scripts/prepare-preview-ai.mjs";

const now = Date.parse("2026-09-21T12:00:00Z");
const expected = {
  repository: "owner/news", environment: "production", sourceCommit: "a".repeat(40),
  images: { backend: `sha256:${"b".repeat(64)}`, frontend: `sha256:${"c".repeat(64)}`, otelCollector: `sha256:${"d".repeat(64)}` }
};
function qualification() {
  return {
    ...structuredClone(expected), schemaVersion: 1,
    gates: Object.fromEntries([...step5Gates, ...step6Gates].map((name) => [name, {
      status: "passed", approvedBy: "reviewer-account",
      completedAt: "2026-09-21T10:00:00Z", expiresAt: "2026-09-22T10:00:00Z",
      evidenceUrl: "https://github.com/owner/news/actions/runs/123",
      ...(name === "security" ? { databaseUpdatedAt: "2026-09-21T06:00:00Z" } : {})
    }]))
  };
}
function controls() {
  return {
    branch: {
      required_status_checks: { strict: true, contexts: ["quality / release-readiness"] },
      required_pull_request_reviews: { required_approving_review_count: 1, dismiss_stale_reviews: true, require_last_push_approval: true },
      enforce_admins: { enabled: true }, allow_force_pushes: { enabled: false }, allow_deletions: { enabled: false }
    },
    environment: {
      protection_rules: [{ type: "required_reviewers", prevent_self_review: true, reviewers: [{ type: "User", reviewer: { id: 1 } }] }],
      deployment_branch_policy: { protected_branches: true, custom_branch_policies: false }
    },
    rulesets: [{
      target: "tag", enforcement: "active", bypass_actors: [],
      conditions: { ref_name: { include: ["refs/tags/v*"], exclude: [] } },
      rules: [{ type: "update" }, { type: "deletion" }]
    }]
  };
}
test("staging entry requires Step5 records; production additionally requires all operational records", () => {
  assert.equal(validateQualification(qualification(), expected, now).length, 10);
  const record = qualification();
  record.environment = "staging";
  for (const name of step6Gates) delete record.gates[name];
  assert.deepEqual(validateQualification(record, { ...expected, environment: "staging" }, now), step5Gates);
  record.environment = "production";
  assert.throws(() => validateQualification(record, expected, now), /provider-contracts/);
});
test("every incomplete gate and mismatched artifact, repository, commit or environment blocks promotion", () => {
  for (const name of [...step5Gates, ...step6Gates]) {
    for (const status of [undefined, "pending", "blocked", "skipped", "failed"]) {
      const record = qualification();
      record.gates[name].status = status;
      assert.throws(() => validateQualification(record, expected, now), /not passed/);
    }
  }
  for (const change of [
    { repository: "another/news" }, { sourceCommit: "b".repeat(40) }, { environment: "staging" },
    { images: { ...expected.images, frontend: `sha256:${"e".repeat(64)}` } }, { schemaVersion: 2 }
  ]) assert.throws(() => validateQualification({ ...qualification(), ...change }, expected, now));
});
test("expired, malformed, future, stale-intelligence and anonymous approvals cannot satisfy qualification", () => {
  for (const change of [
    { expiresAt: "2026-09-20T10:00:00Z" }, { completedAt: "2026-09-22T10:00:00Z" },
    { expiresAt: "2027-09-22T10:00:00Z" }, { completedAt: "2026-02-30T10:00:00Z" },
    { completedAt: "yesterday" }, { approvedBy: "" }, { approvedBy: "copilot" },
    { evidenceUrl: "http://github.com/owner/news" }, { evidenceUrl: "https://user:password@github.com/owner/news" },
    { evidenceUrl: "https://github.com/owner/news?token=private" }, { evidenceUrl: "https://example.invalid/report" }
  ]) {
    const record = qualification();
    Object.assign(record.gates.accessibility, change);
    assert.throws(() => validateQualification(record, expected, now));
  }
  for (const updatedAt of ["2026-09-18T06:00:00Z", "2026-09-22T06:00:00Z", "2026-09-21T11:00:00Z"]) {
    const record = qualification();
    record.gates.security.databaseUpdatedAt = updatedAt;
    assert.throws(() => validateQualification(record, expected, now));
  }
});
test("actual hosted controls must enforce required checks, reviews and immutable tags", () => {
  validateReleaseControls(controls());
  const changes = [
    c => { c.branch.required_status_checks.contexts = []; },
    c => { c.branch.required_status_checks.strict = false; },
    c => { c.branch.required_pull_request_reviews.require_last_push_approval = false; },
    c => { c.branch.enforce_admins.enabled = false; },
    c => { c.branch.allow_deletions.enabled = true; },
    c => { c.environment.protection_rules[0].prevent_self_review = false; },
    c => { c.environment.protection_rules[0].reviewers = []; },
    c => { c.environment.deployment_branch_policy = null; },
    c => { c.rulesets[0].enforcement = "evaluate"; },
    c => { c.rulesets[0].bypass_actors = [{ actor_type: "RepositoryRole", actor_id: 5 }]; },
    c => { c.rulesets[0].conditions.ref_name.exclude = ["refs/tags/v1"]; },
    c => { c.rulesets[0].rules = [{ type: "deletion" }]; }
  ];
  for (const change of changes) {
    const configuration = controls();
    change(configuration);
    assert.throws(() => validateReleaseControls(configuration));
  }
  const custom = controls();
  custom.defaultBranch = "main";
  custom.environment.deployment_branch_policy = { protected_branches: false, custom_branch_policies: true };
  custom.branchPolicies = [{ type: "branch", name: "main" }, { type: "tag", name: "v*" }];
  validateReleaseControls(custom);
  custom.branchPolicies.push({ type: "branch", name: "*" });
  assert.throws(() => validateReleaseControls(custom), /Deployment must be limited/);
});
test("deployment smoke requires real public availability, anonymous denial, uncached responses and nonce CSP", async () => {
  const response = (url) => new Response(null, {
    status: url.includes("/api/v1/auth/me") || url.includes("/api/v1/admin/users") ? 401 : 200,
    headers: { "cache-control": "private, no-store", "content-security-policy": "script-src 'self' 'nonce-test-value'; style-src 'unsafe-inline'" }
  });
  const fetcher = async (url, options) => {
    assert.equal(options.redirect, "error");
    return response(url);
  };
  assert.equal((await deploymentSmoke("https://news.example.com", { fetcher })).checks.length, 7);
  assert.equal((await deploymentSmoke("http://127.0.0.1:3000", { allowLoopbackHttp: true, fetcher })).checks.length, 7);
  for (const origin of ["http://news.example.com", "https://user:secret@news.example.com", "https://news.example.com/path", "https://news.example.com?token=secret"]) {
    await assert.rejects(deploymentSmoke(origin, { allowLoopbackHttp: true, fetcher }), /Smoke target/);
  }
  for (const path of ["/api/v1/auth/me", "/api/v1/admin/users", "/api/v1/articles"]) {
    await assert.rejects(deploymentSmoke("https://news.example.com", {
      fetcher: async (url) => url.includes(path) ? new Response(null, { status: 503 }) : response(url)
    }), /must return/);
  }
  await assert.rejects(deploymentSmoke("https://news.example.com", {
    fetcher: async () => new Response(null, { headers: { "cache-control": "no-store" } })
  }), /nonce-based/);
  await assert.rejects(deploymentSmoke("https://news.example.com", {
    fetcher: async () => new Response(null, { headers: { "cache-control": "public, max-age=60" } })
  }), /missing no-store/);
});
test("hosted inspection fails closed on missing permissions and fetches rule details rather than trusting summaries", async () => {
  const configuration = controls();
  const requests = [];
  const fetcher = async (url, options) => {
    requests.push(url);
    assert.equal(options.redirect, "error");
    assert.equal(options.headers.Authorization, "Bearer test-only-token");
    if (url.endsWith("/owner/news")) return Response.json({ default_branch: "main" });
    if (url.endsWith("/protection")) return Response.json(configuration.branch);
    if (url.endsWith("/environments/staging")) return Response.json(configuration.environment);
    if (url.includes("/rulesets?")) return Response.json([{ id: 123, target: "tag", enforcement: "active" }]);
    if (url.includes("/rulesets/123?")) return Response.json(configuration.rulesets[0]);
    throw new Error("Unexpected fixture request");
  };
  validateReleaseControls(await readReleaseControls({ repository: "owner/news", environment: "staging", token: "test-only-token", fetcher }));
  assert.equal(requests.length, 5);
  await assert.rejects(readReleaseControls({ repository: "owner/news", environment: "staging", token: "" }), /credentials/);
  await assert.rejects(readReleaseControls({
    repository: "owner/news", environment: "staging", token: "test-only-token",
    fetcher: async () => new Response(null, { status: 403 })
  }), /settings were not verified/);
  await assert.rejects(readReleaseControls({
    repository: "owner/news", environment: "staging", token: "test-only-token",
    fetcher: async () => new Response("not-json-response-body", { status: 200 })
  }), error => error.message === "GitHub governance returned invalid JSON; settings were not verified");
});
test("hosted inspection completes pagination and checks custom deployment ref policies", async () => {
  const configuration = controls();
  configuration.environment.deployment_branch_policy = { protected_branches: false, custom_branch_policies: true };
  const requests = [];
  const fetcher = async (url) => {
    requests.push(url);
    if (url.endsWith("/owner/news")) return Response.json({ default_branch: "main" });
    if (url.endsWith("/protection")) return Response.json(configuration.branch);
    if (url.endsWith("/environments/staging")) return Response.json(configuration.environment);
    if (url.includes("/deployment-branch-policies?")) return Response.json({
      total_count: 1, branch_policies: [{ type: "branch", name: "main" }]
    });
    if (url.includes("/rulesets?") && url.endsWith("page=1")) return Response.json(
      Array.from({ length: 100 }, (_, id) => ({ id: id + 1, target: "branch", enforcement: "active" })));
    if (url.includes("/rulesets?") && url.endsWith("page=2")) return Response.json([{ id: 123, target: "tag", enforcement: "active" }]);
    if (url.includes("/rulesets/123?")) return Response.json(configuration.rulesets[0]);
    throw new Error("Unexpected fixture request");
  };
  validateReleaseControls(await readReleaseControls({ repository: "owner/news", environment: "staging", token: "test-only-token", fetcher }));
  assert.equal(requests.length, 7);
});
test("invalid acceptance ports fail before any container or artifact creation", () => {
  const script = fileURLToPath(new URL("./fullstack-acceptance.sh", import.meta.url));
  for (const port of ["0", "65536", "3000:3000", "abc", "03000"]) {
    assert.throws(() => execFileSync("bash", [script], {
      env: { ...process.env, ACCEPTANCE_FRONTEND_PORT: port }, stdio: "pipe"
    }), /Acceptance ports must be decimal integers/);
  }
  assert.throws(() => execFileSync("bash", [script], {
    env: { ...process.env, ACCEPTANCE_FRONTEND_PORT: "13000", ACCEPTANCE_BACKEND_PORT: "13000" }, stdio: "pipe"
  }), /Acceptance service ports must be distinct/);
});
test("preview encryption setup is private, opt-in and never silently rotates a key", (t) => {
  const root = mkdtempSync(path.join(tmpdir(), "nsangusa-preview-key-"));
  t.after(() => rmSync(root, { recursive: true }));
  const initial = preparePreviewAi(root);
  assert.equal(initial.created, true);
  assert.equal(initial.liveAllowed, false);
  assert.equal(lstatSync(initial.file).mode & 0o077, 0);
  const original = readFileSync(initial.file, "utf8");
  const key = original.split("AI_CREDENTIAL_MASTER_KEY=")[1].trim();
  assert.equal(Buffer.from(key, "base64").length, 32);
  assert.equal(preparePreviewAi(root).created, false);
  assert.equal(readFileSync(initial.file, "utf8"), original);
  assert.equal(preparePreviewAi(root, true).liveAllowed, true);
  assert.equal(readFileSync(initial.file, "utf8"), original.replace("AI_LIVE_ENABLED=false", "AI_LIVE_ENABLED=true"));
  assert.equal(preparePreviewAi(root).liveAllowed, true);
  const other = mkdtempSync(path.join(tmpdir(), "nsangusa-preview-link-"));
  t.after(() => rmSync(other, { recursive: true }));
  symlinkSync(path.join(root, ".local"), path.join(other, ".local"));
  assert.throws(() => preparePreviewAi(other), /real directories/);
});
