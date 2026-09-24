import { pathToFileURL } from "node:url";

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}
export function validateReleaseControls({ branch, environment, rulesets, defaultBranch, branchPolicies }) {
  requireValue(branch?.required_status_checks?.strict === true, "Default-branch status checks must require an up-to-date branch");
  const checks = [
    ...(branch.required_status_checks.contexts || []),
    ...(branch.required_status_checks.checks || []).map((check) => check.context)
  ];
  requireValue(checks.some((name) => typeof name === "string" && /(?:^| \/ )release-readiness$/.test(name)),
    "The default branch must require release-readiness");
  const reviews = branch.required_pull_request_reviews;
  requireValue(reviews?.required_approving_review_count >= 1 && reviews.dismiss_stale_reviews === true
    && reviews.require_last_push_approval === true, "Fresh independent pull-request approval must be required");
  requireValue(branch.enforce_admins?.enabled === true && branch.allow_force_pushes?.enabled === false
    && branch.allow_deletions?.enabled === false, "Default-branch protections must include administrators and prohibit force pushes/deletion");
  const reviewers = environment?.protection_rules?.find((rule) => rule.type === "required_reviewers");
  requireValue(reviewers?.prevent_self_review === true && reviewers.reviewers?.length > 0,
    "The deployment environment must require independent reviewers and prevent self-review");
  const protectedBranches = environment.deployment_branch_policy?.protected_branches === true;
  const limitedPatterns = environment.deployment_branch_policy?.custom_branch_policies === true
    && Array.isArray(branchPolicies) && branchPolicies.length > 0
    && branchPolicies.every((policy) => policy.type === "branch" && policy.name === defaultBranch
      || policy.type === "tag" && policy.name === "v*");
  requireValue(protectedBranches || limitedPatterns, "Deployment must be limited to protected branches or the default branch/release tags");
  const immutableReleaseTags = rulesets?.some((ruleset) =>
    ruleset.target === "tag" && ruleset.enforcement === "active"
    && Array.isArray(ruleset.bypass_actors) && ruleset.bypass_actors.length === 0
    && ruleset.conditions?.ref_name?.exclude?.length === 0
    && ruleset.conditions.ref_name.include?.some((pattern) => ["~ALL", "refs/tags/*", "refs/tags/v*"].includes(pattern))
    && ["update", "deletion"].every((type) => ruleset.rules?.some((rule) => rule.type === type)));
  requireValue(immutableReleaseTags, "Release tags need active update/deletion restrictions without bypass actors or exclusions");
}

export async function readReleaseControls({ repository, environment, token, fetcher = fetch }) {
  requireValue(/^[\w.-]+\/[\w.-]+$/.test(repository), "A valid GitHub repository is required");
  requireValue(["staging", "production"].includes(environment), "A staging or production environment is required");
  requireValue(typeof token === "string" && token.length > 0, "Read-only release governance credentials are required");
  const prefix = `/repos/${repository}`;
  async function get(path) {
    const response = await fetcher(`https://api.github.com${path}`, {
      headers: { Accept: "application/vnd.github+json", Authorization: `Bearer ${token}`, "X-GitHub-Api-Version": "2026-03-10" },
      redirect: "error", signal: AbortSignal.timeout(15_000)
    });
    requireValue(response.ok, `GitHub governance request failed (HTTP ${response.status}); settings were not verified`);
    try { return await response.json(); }
    catch (error) {
      if (error instanceof SyntaxError) throw new Error("GitHub governance returned invalid JSON; settings were not verified");
      throw error;
    }
  }
  const repo = await get(prefix);
  requireValue(typeof repo.default_branch === "string" && repo.default_branch.length > 0, "Default branch was not returned");
  const branch = await get(`${prefix}/branches/${encodeURIComponent(repo.default_branch)}/protection`);
  const deployment = await get(`${prefix}/environments/${encodeURIComponent(environment)}`);
  let branchPolicies;
  if (deployment.deployment_branch_policy?.custom_branch_policies === true) {
    const policies = await get(`${prefix}/environments/${encodeURIComponent(environment)}/deployment-branch-policies?per_page=100`);
    requireValue(Array.isArray(policies.branch_policies) && policies.total_count === policies.branch_policies.length,
      "The complete deployment branch policy inventory is required (maximum 100)");
    branchPolicies = policies.branch_policies;
  }
  const rulesets = [];
  for (let page = 1; page <= 20; page++) {
    const entries = await get(`${prefix}/rulesets?includes_parents=true&per_page=100&page=${page}`);
    requireValue(Array.isArray(entries), "GitHub did not return a ruleset inventory");
    for (const entry of entries) {
      if (entry.target !== "tag" || entry.enforcement !== "active") continue;
      requireValue(Number.isSafeInteger(entry.id) && entry.id > 0, "GitHub ruleset identity is invalid");
      rulesets.push(await get(`${prefix}/rulesets/${entry.id}?includes_parents=true`));
    }
    if (entries.length < 100) return { branch, environment: deployment, rulesets, defaultBranch: repo.default_branch, branchPolicies };
  }
  throw new Error("GitHub ruleset inventory exceeded the bounded pagination limit");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  requireValue(!process.env.GITHUB_SERVER_URL || process.env.GITHUB_SERVER_URL === "https://github.com",
    "This governance preflight supports GitHub.com only; Enterprise credentials must not be sent to another host");
  validateReleaseControls(await readReleaseControls({
    repository: process.env.GITHUB_REPOSITORY,
    environment: process.env.TARGET_ENVIRONMENT,
    token: process.env.RELEASE_GOVERNANCE_TOKEN
  }));
  console.log("Hosted required checks, independent reviews and immutable release-tag controls are present.");
}
