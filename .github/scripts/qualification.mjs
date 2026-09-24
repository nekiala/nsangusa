import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const step5Gates = ["security", "accessibility", "release-protections"];
export const step6Gates = [
  "provider-contracts", "platform-security", "observability", "capacity-and-cost",
  "restore-and-rollback", "retention-and-policy", "operational-ownership"
];
const day = 86_400_000;
const digest = /^sha256:[a-f0-9]{64}$/;
function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}
function timestamp(value, label) {
  requireValue(typeof value === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value),
    `${label} must be a UTC timestamp`);
  const parsed = Date.parse(value);
  requireValue(Number.isFinite(parsed) && new Date(parsed).toISOString().replace(".000Z", "Z") === value.replace(".000Z", "Z"),
    `${label} is not a valid date`);
  return parsed;
}

export function validateQualification(record, expected, now = Date.now()) {
  requireValue(record?.schemaVersion === 1, "Qualification schemaVersion must be 1");
  requireValue(["staging", "production"].includes(expected.environment), "Qualification applies to staging or production");
  requireValue(record.environment === expected.environment, "Qualification environment does not match");
  requireValue(/^[\w.-]+\/[\w.-]+$/.test(expected.repository) && record.repository === expected.repository,
    "Qualification repository does not match");
  requireValue(/^[a-f0-9]{40}$/.test(expected.sourceCommit) && record.sourceCommit === expected.sourceCommit,
    "Qualification must cover the exact signed source commit");
  for (const component of ["backend", "frontend", "otelCollector"]) {
    requireValue(digest.test(expected.images?.[component]) && record.images?.[component] === expected.images[component],
      `Qualification ${component} digest does not match`);
  }
  const required = expected.environment === "production" ? [...step5Gates, ...step6Gates] : step5Gates;
  for (const name of required) {
    const gate = record.gates?.[name];
    requireValue(gate?.status === "passed", `Qualification gate is not passed: ${name}`);
    requireValue(typeof gate.approvedBy === "string" && /^[A-Za-z0-9][A-Za-z0-9_.@-]{1,100}$/.test(gate.approvedBy)
      && !/^(pending|unknown|tbd|todo|example|copilot)$/i.test(gate.approvedBy), `A named accountable reviewer is required: ${name}`);
    const completed = timestamp(gate.completedAt, `${name}.completedAt`);
    const expires = timestamp(gate.expiresAt, `${name}.expiresAt`);
    requireValue(completed <= now && expires > now && expires > completed && expires - completed <= 30 * day,
      `Qualification evidence is future-dated, expired or valid for more than 30 days: ${name}`);
    let evidence;
    try { evidence = new URL(gate.evidenceUrl); }
    catch { throw new Error(`An absolute evidence URL is required: ${name}`); }
    requireValue(evidence.protocol === "https:" && !evidence.username && !evidence.password && !evidence.search
      && evidence.hostname.includes(".") && !/\.(invalid|test|example)$/.test(evidence.hostname),
    `Evidence must use HTTPS without credentials or query tokens: ${name}`);
  }
  const updated = timestamp(record.gates.security.databaseUpdatedAt, "security.databaseUpdatedAt");
  requireValue(updated <= now && now - updated <= 2 * day, "Security intelligence must be no more than 48 hours old");
  requireValue(timestamp(record.gates.security.completedAt, "security.completedAt") >= updated,
    "Security scans must be completed using the stated intelligence");
  return required;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let record;
  try { record = JSON.parse(readFileSync(process.argv[2], "utf8")); }
  catch (error) {
    if (error instanceof SyntaxError) throw new Error("Qualification evidence must be valid JSON");
    throw error;
  }
  const gates = validateQualification(record, {
    environment: process.env.TARGET_ENVIRONMENT,
    repository: process.env.GITHUB_REPOSITORY,
    sourceCommit: process.env.QUALIFIED_SOURCE_COMMIT,
    images: {
      backend: process.env.BACKEND_DIGEST,
      frontend: process.env.FRONTEND_DIGEST,
      otelCollector: process.env.OTEL_COLLECTOR_DIGEST
    }
  });
  console.log(`Required reviewed qualification records are present: ${gates.join(", ")}. This validates records, not their underlying exercises.`);
}
