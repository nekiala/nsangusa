import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const [directory, mode] = process.argv.slice(2);
assert(directory && ["ordinary", "live"].includes(mode), "Usage: assert-junit.mjs REPORT_DIRECTORY ordinary|live");

const liveClasses = [
  "com.nsangusa.news.media.internal.MediaDeliveryMigrationTests",
  "com.nsangusa.news.media.internal.S3MediaPersistenceTests",
  "com.nsangusa.news.newsletter.internal.MailpitNewsletterDeliveryTests"
];
const required = mode === "live" ? liveClasses : [
  "com.nsangusa.news.EditorialBrokerIntegrationTests",
  "com.nsangusa.news.EventReliabilityBrokerIntegrationTests",
  "com.nsangusa.news.contracts.HttpPayloadContractTests",
  "com.nsangusa.news.contracts.EventPayloadContractTests",
  "com.nsangusa.news.contracts.StructuredContentContractTests",
  "com.nsangusa.news.contracts.SupportedV1BaselineTests",
  "com.nsangusa.news.contracts.ProtocolMigrationOverlapTests",
  "com.nsangusa.news.DeliveryWorkflowTests"
];
const files = (await readdir(directory)).filter((file) => file.startsWith("TEST-") && file.endsWith(".xml"));
assert(files.length > 0, "No JUnit reports were produced");
const suites = new Map();
let executed = 0;
for (const file of files) {
  const xml = await readFile(path.join(directory, file), "utf8");
  const root = xml.match(/^\s*(?:<\?xml[^?]*\?>\s*)?<testsuite\s+([^>]+)>/);
  assert(root, `Unsupported or missing JUnit testsuite root: ${file}`);
  const attributes = Object.fromEntries([...root[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((match) => [match[1], match[2]]));
  const counts = {};
  for (const key of ["tests", "skipped", "failures", "errors"]) {
    assert(/^\d+$/.test(attributes[key]), `Invalid ${key} count in ${file}`);
    counts[key] = Number(attributes[key]);
  }
  assert(attributes.name && !suites.has(attributes.name), `Missing or duplicate suite name: ${file}`);
  assert.equal(counts.failures + counts.errors, 0, `${file} contains failures`);
  assert(counts.skipped <= counts.tests, `Invalid skipped count: ${file}`);
  if (counts.skipped && !(mode === "ordinary" && liveClasses.includes(attributes.name))) {
    assert.fail(`Required tests were skipped: ${attributes.name}`);
  }
  suites.set(attributes.name, counts);
  executed += counts.tests - counts.skipped;
}
for (const name of required) {
  const counts = suites.get(name);
  assert(counts && counts.tests > 0 && counts.skipped === 0, `Missing, empty or skipped required suite: ${name}`);
}
assert(executed > 0, "No tests executed");
console.log(`${mode}: ${executed} executed cases; all required suites ran without skips`);
